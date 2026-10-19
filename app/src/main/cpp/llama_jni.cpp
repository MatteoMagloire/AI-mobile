// Pont JNI entre l'app Kotlin et llama.cpp.
// Un seul modèle et une seule conversation à la fois (budget RAM du S21), plus un petit contexte à
// part pour la prise de notes, sur les mêmes poids.
// Les fonctions ne doivent jamais être appelées en parallèle (voir LlamaEngine.kt).

#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#ifndef LLAMA_CPP_REF
#define LLAMA_CPP_REF "inconnu"
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define TAG "aimobile-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define LOGW(...) LOGI(__VA_ARGS__)
#define LOGE(...) LOGI(__VA_ARGS__)
#endif

namespace {

constexpr int BATCH_SIZE = 512;
constexpr int OVERFLOW_HEADROOM = 4;
constexpr const char *ROLE_SYSTEM = "system";
constexpr const char *ROLE_USER = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
common_batch g_batch;
common_chat_templates_ptr g_chat_templates;
common_sampler *g_sampler = nullptr;
int g_n_ctx = 4096;
int g_n_threads = 4;
bool g_use_jinja = true;

std::vector<common_chat_msg> g_chat_msgs;
llama_pos g_system_prompt_pos = 0;
llama_pos g_current_pos = 0;
llama_pos g_stop_pos = 0;
std::string g_cached_chars;
std::ostringstream g_assistant_ss;
bool g_assistant_pending = false;

// Cache du prompt système : tant que le même texte est en tête du contexte,
// une nouvelle conversation garde son KV au lieu de le relire.
std::string g_system_prompt;
bool g_system_prompt_ready = false;
size_t g_system_msg_count = 0;

// Mesures de la dernière réponse (lecture du message, puis écriture).
int64_t g_stat_prompt_tokens = 0;
int64_t g_stat_prompt_us = 0;
int64_t g_stat_gen_tokens = 0;
int64_t g_stat_gen_us = 0;

// Travail de fond (prise de notes) : un second contexte sur les mêmes poids, à part de la
// conversation, qui n'est donc jamais touchée. Ses consignes (le préfixe : prompt système et
// exemples) restent dans son KV d'un travail à l'autre et ne sont lues qu'une fois ; chaque travail
// y ajoute ses messages, retirés à la fin.
// Les consignes font environ 900 tokens ; le reste suffit pour un long message et trois questions.
constexpr int TASK_CTX = 2048;
constexpr int TASK_BATCH = 256;
// Place laissée après les consignes pour le message à lire et les réponses.
constexpr int TASK_MIN_ROOM = 384;
llama_context *g_task_ctx = nullptr;  // créé au premier travail
bool g_task_ctx_failed = false;        // pas de place pour lui : pas de prise de notes avec ce modèle
common_batch g_task_batch;
llama_tokens g_task_prefix;                // tokens des consignes déjà dans le KV de travail
std::vector<common_chat_msg> g_task_msgs;  // messages du travail en cours, pour le template
llama_pos g_task_pos = 0;                  // prochaine position dans le contexte de travail
bool g_task_active = false;
int g_task_error = 0;
// Mesures du travail en cours : tokens et µs des consignes lues, des messages lus, des réponses
// écrites, puis le nombre d'étapes.
int64_t g_task_stats[7] = {};
// Seuls compteurs lus depuis un autre thread. Chaque interruption avance g_abort_epoch ; un travail
// commencé à une époque antérieure s'arrête au plus vite, même au milieu d'un calcul.
std::atomic<uint32_t> g_abort_epoch{0};
std::atomic<uint32_t> g_task_epoch{0};

// Codes d'erreur d'une étape du travail de fond (taskError).
constexpr int TASK_OK = 0;
constexpr int TASK_NOT_STARTED = 1;
constexpr int TASK_ABORTED = 2;
constexpr int TASK_NO_ROOM = 3;
constexpr int TASK_BAD_GRAMMAR = 4;
constexpr int TASK_DECODE_FAILED = 5;

bool task_aborted() {
    return g_abort_epoch.load() != g_task_epoch.load();
}

// Appelé par llama.cpp pendant les calculs du contexte de travail seulement : une réponse n'est
// jamais arrêtée.
bool task_abort_callback(void *) {
    return task_aborted();
}

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

void android_log_callback(ggml_log_level level, const char *text, void *) {
#ifdef __ANDROID__
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, TAG, "%s", text);
#else
    (void) level;
    (void) text;
#endif
}

std::string to_string(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

bool is_valid_utf8(const std::string &s) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(s.data());
    size_t i = 0, n = s.size();
    while (i < n) {
        int len;
        if ((bytes[i] & 0x80) == 0x00) len = 1;
        else if ((bytes[i] & 0xE0) == 0xC0) len = 2;
        else if ((bytes[i] & 0xF0) == 0xE0) len = 3;
        else if ((bytes[i] & 0xF8) == 0xF0) len = 4;
        else return false;
        if (i + len > n) return false;
        for (int k = 1; k < len; k++) {
            if ((bytes[i + k] & 0xC0) != 0x80) return false;
        }
        i += len;
    }
    return true;
}

bool has_template() {
    return common_chat_templates_was_explicit(g_chat_templates.get());
}

// Le texte que le template de chat du modèle ajoute pour [msg] après [past] (jinja d'abord,
// ancien format sinon).
std::string format_message(const std::vector<common_chat_msg> &past, const common_chat_msg &msg) {
    const bool add_ass = msg.role == ROLE_USER;
    try {
        return common_chat_format_single(g_chat_templates.get(), past, msg, add_ass, g_use_jinja);
    } catch (const std::exception &e) {
        LOGW("template jinja en échec (%s), retour au format simple", e.what());
        g_use_jinja = false;
        return common_chat_format_single(g_chat_templates.get(), past, msg, add_ass, false);
    }
}

// Formate un message de la conversation et l'ajoute à son historique.
std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    std::string formatted = format_message(g_chat_msgs, msg);
    g_chat_msgs.push_back(msg);
    return formatted;
}

// Ajoute un message à l'historique du template sans le décoder (son KV est déjà là).
void chat_push(const char *role, const std::string &content) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    g_chat_msgs.push_back(msg);
}

// Termine le message de l'assistant en cours (fin normale, limite atteinte ou arrêt demandé).
void commit_assistant_message() {
    if (!g_assistant_pending) return;
    g_assistant_ss << g_cached_chars;
    chat_add_and_format(ROLE_ASSISTANT, g_assistant_ss.str());
    g_assistant_pending = false;
}

void reset_short_term() {
    g_stop_pos = 0;
    g_cached_chars.clear();
    g_assistant_ss.str("");
    g_assistant_ss.clear();
}

void reset_stats() {
    g_stat_prompt_tokens = g_stat_prompt_us = g_stat_gen_tokens = g_stat_gen_us = 0;
}

// Oublie tout : contexte vide, plus de prompt système en cache.
void clear_conversation() {
    g_chat_msgs.clear();
    g_assistant_pending = false;
    g_system_prompt.clear();
    g_system_prompt_ready = false;
    g_system_msg_count = 0;
    g_system_prompt_pos = g_current_pos = 0;
    reset_short_term();
    llama_memory_clear(llama_get_memory(g_context), false);
}

// Libère de la place en jetant la moitié la plus ancienne de la conversation
// (le prompt système est conservé).
void shift_context() {
    const int n_discard = (g_current_pos - g_system_prompt_pos) / 2;
    LOGI("contexte plein : %d tokens retirés", n_discard);
    auto *mem = llama_get_memory(g_context);
    llama_memory_seq_rm(mem, 0, g_system_prompt_pos, g_system_prompt_pos + n_discard);
    llama_memory_seq_add(mem, 0, g_system_prompt_pos + n_discard, g_current_pos, -n_discard);
    g_current_pos -= n_discard;
    g_stop_pos -= n_discard;
}

// Décode des tokens de la conversation. 0 si tout est décodé.
int decode_tokens(const llama_tokens &tokens, bool compute_last_logit) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur = std::min((int) tokens.size() - i, BATCH_SIZE);
        if (g_current_pos + cur >= g_n_ctx - OVERFLOW_HEADROOM) shift_context();
        g_batch.clear();
        for (int j = 0; j < cur; j++) {
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            g_batch.add(tokens[i + j], g_current_pos + j, 0, want_logit);
        }
        const int rc = llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get());
        if (rc != 0) {
            LOGE("llama_process a échoué (%d)", rc);
            return rc;
        }
        g_current_pos += cur;
    }
    return 0;
}

// Oublie les consignes gardées : contexte de travail vide.
void task_reset() {
    if (g_task_ctx) llama_memory_clear(llama_get_memory(g_task_ctx), false);
    g_task_prefix.clear();
    g_task_msgs.clear();
    g_task_pos = 0;
    g_task_active = false;
}

// Le contexte de travail, créé au premier travail : rien n'est réservé pour un modèle qui ne prend
// pas de notes. Sans lui, l'app marche, mais ne prend pas de notes.
bool ensure_task_context() {
    if (g_task_ctx) return true;
    if (!g_model || g_task_ctx_failed) return false;
    llama_context_params tparams = llama_context_default_params();
    tparams.n_ctx = TASK_CTX;
    tparams.n_batch = TASK_BATCH;
    tparams.n_ubatch = TASK_BATCH;
    tparams.n_outputs_max = 1;
    tparams.n_threads = g_n_threads;
    tparams.n_threads_batch = g_n_threads;
    tparams.abort_callback = task_abort_callback;
    g_task_ctx = llama_init_from_model(g_model, tparams);
    if (!g_task_ctx) {
        g_task_ctx_failed = true;
        LOGW("contexte de travail indisponible : pas de prise de notes");
        return false;
    }
    g_task_batch = common_batch(g_task_ctx);
    task_reset();
    return true;
}

// Décode [n] tokens dans le contexte de travail, à la suite. Un morceau qui échoue ne laisse rien
// dans le KV. 0, TASK_ABORTED ou TASK_DECODE_FAILED.
int task_decode_chunk(const llama_token *tokens, size_t n, bool last_logit) {
    g_task_batch.clear();
    for (size_t j = 0; j < n; j++) {
        g_task_batch.add(tokens[j], g_task_pos + (llama_pos) j, 0, last_logit && j == n - 1);
    }
    const int rc = llama_process(g_task_ctx, LLAMA_PROCESS_TYPE_DECODE, g_task_batch.get());
    if (rc != 0) {
        llama_memory_seq_rm(llama_get_memory(g_task_ctx), 0, g_task_pos, -1);
        if (rc == 2) return TASK_ABORTED;
        LOGE("llama_process a échoué pendant le travail de fond (%d)", rc);
        return TASK_DECODE_FAILED;
    }
    g_task_pos += (llama_pos) n;
    return 0;
}

int task_decode(const llama_tokens &tokens, bool last_logit) {
    for (size_t i = 0; i < tokens.size(); i += TASK_BATCH) {
        const size_t cur = std::min(tokens.size() - i, (size_t) TASK_BATCH);
        const int rc = task_decode_chunk(tokens.data() + i, cur, last_logit && i + cur == tokens.size());
        if (rc != 0) return rc;
    }
    return 0;
}

// Token qui termine un message de l'assistant (comme s'il l'avait écrit lui-même).
llama_token end_of_turn_token() {
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    llama_token end = llama_vocab_eot(vocab);
    if (end == LLAMA_TOKEN_NULL) end = llama_vocab_eos(vocab);
    return end;
}

// Coupe une fin de texte UTF-8 incomplète (limite de tokens au milieu d'un caractère).
void trim_invalid_utf8(std::string &s) {
    while (!s.empty() && !is_valid_utf8(s)) s.pop_back();
}

void free_sampler() {
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_init(JNIEnv *, jobject) {
    llama_log_set(android_log_callback, nullptr);
    llama_backend_init();
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_load(
        JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = to_string(env, jpath);
    LOGI("chargement du modèle : %s", path.c_str());

    llama_model_params mparams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) return 1;

    g_n_ctx = n_ctx;
    g_n_threads = n_threads;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    // Seul le dernier token d'un lot sert (le suivant à choisir) : pas de place réservée pour les
    // logits de tout un lot (environ 256 Mo avec le vocabulaire de Ministral).
    cparams.n_outputs_max = 1;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 2;
    }

    g_model = model;
    g_context = ctx;
    g_batch = common_batch(ctx);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_use_jinja = true;
    clear_conversation();
    reset_stats();
    task_reset();
    g_task_ctx_failed = false;
    return 0;
}

// seed < 0 : graine aléatoire (usage normal) ; sinon réponses reproductibles (banc d'essai).
JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_configureSampler(
        JNIEnv *, jobject, jfloat temp, jfloat top_p, jint seed) {
    if (!g_model) return;
    free_sampler();
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_p = top_p;
    if (seed >= 0) sparams.seed = (uint32_t) seed;
    g_sampler = common_sampler_init(g_model, sparams);
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_startConversation(
        JNIEnv *env, jobject, jstring jsystem_prompt) {
    if (!g_context) return 1;
    std::string prompt = to_string(env, jsystem_prompt);

    if (g_system_prompt_ready && prompt == g_system_prompt) {
        // Même prompt système : on garde son KV et on jette seulement la conversation.
        g_assistant_pending = false;
        reset_short_term();
        llama_memory_seq_rm(llama_get_memory(g_context), 0, g_system_prompt_pos, -1);
        g_current_pos = g_system_prompt_pos;
        g_chat_msgs.resize(g_system_msg_count);
        return 0;
    }

    clear_conversation();
    if (!prompt.empty()) {
        std::string formatted = prompt;
        if (has_template()) {
            formatted = chat_add_and_format(ROLE_SYSTEM, prompt);
            g_system_msg_count = 1;
        }
        const auto tokens = common_tokenize(g_context, formatted, true, true);
        if ((int) tokens.size() > g_n_ctx / 2) {
            LOGE("prompt système trop long (%d tokens)", (int) tokens.size());
            clear_conversation();
            return 2;
        }
        if (decode_tokens(tokens, false)) {
            clear_conversation();
            return 3;
        }
    }
    g_system_prompt_pos = g_current_pos;
    g_system_prompt = prompt;
    g_system_prompt_ready = true;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_sendUserMessage(
        JNIEnv *env, jobject, jstring jtext, jint n_predict) {
    if (!g_context || !g_sampler) return 1;
    commit_assistant_message();
    reset_short_term();
    reset_stats();

    std::string text = to_string(env, jtext);
    if (has_template()) text = chat_add_and_format(ROLE_USER, text);

    // add_special seulement au tout début (BOS), sinon les tokens spéciaux du template sont analysés.
    auto tokens = common_tokenize(g_context, text, g_current_pos == 0, true);
    const int max_tokens = g_n_ctx / 2;
    if ((int) tokens.size() > max_tokens) {
        LOGW("message trop long, tronqué à %d tokens", max_tokens);
        tokens.resize(max_tokens);
    }
    const int64_t t0 = now_us();
    if (decode_tokens(tokens, true)) return 2;
    g_stat_prompt_tokens = (int64_t) tokens.size();
    g_stat_prompt_us = now_us() - t0;

    common_sampler_reset(g_sampler);
    g_stop_pos = g_current_pos + n_predict;
    g_assistant_pending = true;
    return 0;
}

// Rejoue un message déjà échangé (reprise de la conversation au démarrage de l'app).
// Un message de l'assistant est décodé comme s'il venait d'être généré : son texte puis le token de fin.
JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_addHistoryMessage(
        JNIEnv *env, jobject, jboolean from_user, jstring jtext) {
    if (!g_context) return 1;
    commit_assistant_message();
    std::string text = to_string(env, jtext);

    llama_tokens tokens;
    if (from_user) {
        if (has_template()) text = chat_add_and_format(ROLE_USER, text);
        tokens = common_tokenize(g_context, text, g_current_pos == 0, true);
    } else {
        chat_push(ROLE_ASSISTANT, text);
        tokens = common_tokenize(g_context, text, false, false);
        const llama_token end = end_of_turn_token();
        if (end != LLAMA_TOKEN_NULL) tokens.push_back(end);
    }
    if ((int) tokens.size() > g_n_ctx / 2) tokens.resize(g_n_ctx / 2);
    return decode_tokens(tokens, false) ? 2 : 0;
}

// Renvoie le morceau de texte suivant, "" si le token est incomplet (UTF-8), ou null quand c'est fini.
JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_nextToken(JNIEnv *env, jobject) {
    if (!g_context || !g_assistant_pending) return nullptr;

    if (g_current_pos >= g_stop_pos) {
        commit_assistant_message();
        return nullptr;
    }
    // Décalage juste avant un décodage : le KV n'est jamais laissé avec un décalage en attente.
    if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) shift_context();

    const int64_t t0 = now_us();
    const llama_token id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, id, true);

    g_batch.clear();
    g_batch.add(id, g_current_pos, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGE("llama_process a échoué pendant la génération");
        commit_assistant_message();
        return nullptr;
    }
    g_current_pos++;
    g_stat_gen_tokens++;
    g_stat_gen_us += now_us() - t0;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) {
        commit_assistant_message();
        return nullptr;
    }

    g_cached_chars += common_token_to_piece(g_context, id);
    if (!is_valid_utf8(g_cached_chars)) return env->NewStringUTF("");

    jstring out = env->NewStringUTF(g_cached_chars.c_str());
    g_assistant_ss << g_cached_chars;
    g_cached_chars.clear();
    return out;
}

JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_stopGeneration(JNIEnv *, jobject) {
    commit_assistant_message();
}

// Écrit le KV de la conversation dans un fichier, pour la reprendre sans tout relire.
// Renvoie la taille écrite, 0 en cas d'échec.
JNIEXPORT jlong JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_saveSession(JNIEnv *env, jobject, jstring jpath) {
    if (!g_context) return 0;
    commit_assistant_message();
    const std::string path = to_string(env, jpath);
    return (jlong) llama_state_seq_save_file(g_context, path.c_str(), 0, nullptr, 0);
}

// Recharge un KV écrit par saveSession. Les messages ne sont pas relus : ils servent seulement
// au template de chat pour formater la suite. En cas d'échec le contexte est vide.
JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_restoreSession(
        JNIEnv *env, jobject, jstring jpath, jlong expected_bytes, jstring jsystem_prompt,
        jint system_prompt_tokens, jbooleanArray jfrom_user, jobjectArray jtexts) {
    if (!g_context) return 1;
    clear_conversation();
    const std::string path = to_string(env, jpath);

    // llama.cpp s'arrête net (assert) sur un fichier tronqué : on vérifie la taille avant.
    struct stat st {};
    if (stat(path.c_str(), &st) != 0 || (jlong) st.st_size != expected_bytes) return 5;

    llama_token unused[1];
    size_t n_token_count = 0;
    if (llama_state_seq_load_file(g_context, path.c_str(), 0, unused, 1, &n_token_count) == 0) {
        clear_conversation();
        return 2;
    }
    const llama_pos pos_max = llama_memory_seq_pos_max(llama_get_memory(g_context), 0);
    if (pos_max < 0 || system_prompt_tokens < 0 || system_prompt_tokens > pos_max + 1 || pos_max + 1 > g_n_ctx) {
        clear_conversation();
        return 3;
    }
    g_current_pos = pos_max + 1;
    g_system_prompt_pos = system_prompt_tokens;

    const std::string prompt = to_string(env, jsystem_prompt);
    if (!prompt.empty() && has_template()) {
        chat_push(ROLE_SYSTEM, prompt);
        g_system_msg_count = 1;
    }
    g_system_prompt = prompt;
    g_system_prompt_ready = true;

    const jsize n = env->GetArrayLength(jtexts);
    if (env->GetArrayLength(jfrom_user) != n) {
        clear_conversation();
        return 4;
    }
    jboolean *from_user = env->GetBooleanArrayElements(jfrom_user, nullptr);
    for (jsize i = 0; i < n; i++) {
        auto jtext = (jstring) env->GetObjectArrayElement(jtexts, i);
        chat_push(from_user[i] ? ROLE_USER : ROLE_ASSISTANT, to_string(env, jtext));
        env->DeleteLocalRef(jtext);
    }
    env->ReleaseBooleanArrayElements(jfrom_user, from_user, JNI_ABORT);
    return 0;
}

// Nombre de tokens du prompt système, puis de tout le contexte utilisé.
JNIEXPORT jintArray JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_contextUsage(JNIEnv *env, jobject) {
    const jint values[2] = {(jint) g_system_prompt_pos, (jint) g_current_pos};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

// Mesures de la dernière réponse : tokens lus, durée (µs), tokens écrits, durée (µs).
JNIEXPORT jlongArray JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_lastStats(JNIEnv *env, jobject) {
    const jlong values[4] = {g_stat_prompt_tokens, g_stat_prompt_us, g_stat_gen_tokens, g_stat_gen_us};
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, values);
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_modelSize(JNIEnv *, jobject) {
    return g_model ? (jlong) llama_model_size(g_model) : 0;
}

JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_unload(JNIEnv *, jobject) {
    task_reset();
    g_task_batch = common_batch();
    if (g_task_ctx) llama_free(g_task_ctx);
    g_task_ctx = nullptr;
    g_task_ctx_failed = false;
    g_chat_msgs.clear();
    g_assistant_pending = false;
    g_system_prompt.clear();
    g_system_prompt_ready = false;
    g_system_msg_count = 0;
    g_system_prompt_pos = g_current_pos = 0;
    reset_short_term();
    free_sampler();
    g_chat_templates.reset();
    g_batch = common_batch();
    if (g_context) llama_free(g_context);
    if (g_model) llama_model_free(g_model);
    g_context = nullptr;
    g_model = nullptr;
}

// --- Travail de fond (prise de notes) ---
// beginTask (les consignes), puis une ou plusieurs étapes (taskStep), puis endTask qui retire tout
// ce que le travail a ajouté après les consignes. Tout se passe dans le contexte de travail : la
// conversation n'est jamais touchée.

// Époque des interruptions, à lire avant de commencer un travail (voir beginTask).
JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_abortEpoch(JNIEnv *, jobject) {
    return (jint) g_abort_epoch.load();
}

// prefix : le prompt système (vide : aucun), puis des exemples, message et réponse tour à tour.
// Ce qui en est déjà lu est gardé ; une lecture interrompue reprend là où elle s'est arrêtée.
// epoch : abortEpoch lu avant de commencer ; s'il a changé depuis, le travail est déjà interrompu.
JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_beginTask(
        JNIEnv *env, jobject, jobjectArray jprefix, jint epoch) {
    if (g_task_active || !ensure_task_context()) return TASK_NOT_STARTED;
    g_task_epoch = (uint32_t) epoch;
    g_task_error = TASK_OK;
    std::fill(std::begin(g_task_stats), std::end(g_task_stats), 0);
    if (task_aborted()) return g_task_error = TASK_ABORTED;

    // Les consignes en tokens, formatées comme une conversation : une réponse d'exemple est
    // décodée comme si le modèle l'avait écrite (son texte puis le token de fin).
    std::vector<common_chat_msg> msgs;
    llama_tokens want;
    const jsize n = env->GetArrayLength(jprefix);
    for (jsize i = 0; i < n; i++) {
        auto jtext = (jstring) env->GetObjectArrayElement(jprefix, i);
        common_chat_msg msg;
        msg.content = to_string(env, jtext);
        env->DeleteLocalRef(jtext);
        if (i == 0 && msg.content.empty()) continue;
        msg.role = i == 0 ? ROLE_SYSTEM : (i % 2 == 1 ? ROLE_USER : ROLE_ASSISTANT);
        llama_tokens part;
        if (msg.role == ROLE_ASSISTANT) {
            part = common_tokenize(g_task_ctx, msg.content, false, false);
            const llama_token end = end_of_turn_token();
            if (end != LLAMA_TOKEN_NULL) part.push_back(end);
        } else {
            const std::string text = has_template() ? format_message(msgs, msg) : msg.content;
            part = common_tokenize(g_task_ctx, text, want.empty(), true);
        }
        want.insert(want.end(), part.begin(), part.end());
        msgs.push_back(msg);
    }
    if ((int) want.size() + TASK_MIN_ROOM > TASK_CTX) return g_task_error = TASK_NO_ROOM;

    // Ce qui suit les consignes déjà lues (ou la partie qui a changé) est retiré.
    size_t keep = 0;
    while (keep < g_task_prefix.size() && keep < want.size() && g_task_prefix[keep] == want[keep]) keep++;
    if (!llama_memory_seq_rm(llama_get_memory(g_task_ctx), 0, (llama_pos) keep, -1)) {
        task_reset();
        keep = 0;
    }
    g_task_prefix.resize(keep);
    g_task_pos = (llama_pos) keep;

    const int64_t t0 = now_us();
    int rc = 0;
    for (size_t i = keep; rc == 0 && i < want.size(); i += TASK_BATCH) {
        const size_t cur = std::min(want.size() - i, (size_t) TASK_BATCH);
        rc = task_decode_chunk(want.data() + i, cur, false);
        if (rc == 0) g_task_prefix.insert(g_task_prefix.end(), want.begin() + (long) i, want.begin() + (long) (i + cur));
    }
    g_task_stats[0] = (int64_t) (g_task_prefix.size() - keep);
    g_task_stats[1] = now_us() - t0;
    if (rc != 0) return g_task_error = rc;
    g_task_msgs = msgs;
    g_task_active = true;
    return TASK_OK;
}

// Une consigne (message de l'utilisateur pour le template), puis la réponse du modèle : la plus
// probable (pas de hasard), dans le format imposé par la grammaire GBNF si elle n'est pas vide.
// Renvoie null en cas d'échec ou d'interruption (voir taskError).
JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_taskStep(
        JNIEnv *env, jobject, jstring jprompt, jstring jgrammar, jint max_tokens) {
    if (!g_task_active) {
        g_task_error = TASK_NOT_STARTED;
        return nullptr;
    }
    if (task_aborted()) {
        g_task_error = TASK_ABORTED;
        return nullptr;
    }

    common_params_sampling sparams;
    sparams.temp = 0.0f;
    const std::string grammar = to_string(env, jgrammar);
    if (!grammar.empty()) sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    common_sampler *smpl = nullptr;
    try {
        smpl = common_sampler_init(g_model, sparams);
    } catch (const std::exception &e) {
        LOGE("grammaire refusée : %s", e.what());
    }
    if (!smpl) {
        g_task_error = TASK_BAD_GRAMMAR;
        return nullptr;
    }

    common_chat_msg msg;
    msg.role = ROLE_USER;
    msg.content = to_string(env, jprompt);
    const std::string text = has_template() ? format_message(g_task_msgs, msg) : msg.content;
    const auto tokens = common_tokenize(g_task_ctx, text, g_task_pos == 0, true);
    // La consigne, la réponse et son token de fin doivent tenir dans le contexte de travail.
    if (g_task_pos + (int) tokens.size() + max_tokens + 1 >= TASK_CTX - OVERFLOW_HEADROOM) {
        common_sampler_free(smpl);
        g_task_error = TASK_NO_ROOM;
        return nullptr;
    }
    g_task_msgs.push_back(msg);
    int64_t t0 = now_us();
    int rc = task_decode(tokens, true);
    g_task_stats[2] += (int64_t) tokens.size();
    g_task_stats[3] += now_us() - t0;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    std::string out;
    bool ended = false;
    t0 = now_us();
    int written = 0;
    for (int i = 0; rc == 0 && i < max_tokens; i++) {
        if (task_aborted()) {
            rc = TASK_ABORTED;
            break;
        }
        const llama_token id = common_sampler_sample(smpl, g_task_ctx, -1);
        common_sampler_accept(smpl, id, true);
        rc = task_decode_chunk(&id, 1, true);
        if (rc != 0) break;
        written++;
        if (llama_vocab_is_eog(vocab, id)) {
            ended = true;
            break;
        }
        out += common_token_to_piece(g_task_ctx, id);
    }
    common_sampler_free(smpl);
    if (rc == 0 && !ended) {
        // Limite atteinte : le message est fermé comme si le modèle l'avait fini.
        const llama_token end = end_of_turn_token();
        if (end != LLAMA_TOKEN_NULL) rc = task_decode_chunk(&end, 1, false);
    }
    g_task_stats[4] += written;
    g_task_stats[5] += now_us() - t0;
    g_task_stats[6]++;
    if (rc != 0) {
        g_task_error = rc;
        return nullptr;
    }
    trim_invalid_utf8(out);
    common_chat_msg answer;
    answer.role = ROLE_ASSISTANT;
    answer.content = out;
    g_task_msgs.push_back(answer);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_taskError(JNIEnv *, jobject) {
    return g_task_error;
}

// Mesures du dernier travail : tokens des consignes lues et durée (µs), tokens des messages lus et
// durée, tokens écrits et durée, nombre d'étapes.
JNIEXPORT jlongArray JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_taskStats(JNIEnv *env, jobject) {
    jlongArray out = env->NewLongArray(7);
    jlong values[7];
    for (int i = 0; i < 7; i++) values[i] = (jlong) g_task_stats[i];
    env->SetLongArrayRegion(out, 0, 7, values);
    return out;
}

// Retire ce que le travail a ajouté après les consignes. 1 si le KV de travail n'a pas pu être
// remis en état : il est alors vidé, et les consignes seront relues.
JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_endTask(JNIEnv *, jobject) {
    if (!g_task_ctx) return 0;
    g_task_active = false;
    g_task_msgs.clear();
    if (!llama_memory_seq_rm(llama_get_memory(g_task_ctx), 0, (llama_pos) g_task_prefix.size(), -1)) {
        LOGE("travail de fond : impossible de retirer ses tokens");
        task_reset();
        return 1;
    }
    g_task_pos = (llama_pos) g_task_prefix.size();
    return 0;
}

// Seule fonction appelable depuis n'importe quel thread, à tout moment : le travail en cours (ou
// celui qui allait commencer) s'arrête au plus vite. Sans effet sur les réponses.
JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_abortTask(JNIEnv *, jobject) {
    g_abort_epoch++;
}

// Nombre de cœurs utilisés, changé sans recharger le modèle.
JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_setThreads(JNIEnv *, jobject, jint n_threads) {
    g_n_threads = n_threads;
    if (g_context) llama_set_n_threads(g_context, n_threads, n_threads);
    if (g_task_ctx) llama_set_n_threads(g_task_ctx, n_threads, n_threads);
}

JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// Version de llama.cpp compilée : les fichiers de session ne servent qu'avec la même.
JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_buildRef(JNIEnv *env, jobject) {
    return env->NewStringUTF(LLAMA_CPP_REF);
}

}  // extern "C"
