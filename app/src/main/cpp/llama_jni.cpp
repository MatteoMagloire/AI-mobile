// Pont JNI entre l'app Kotlin et llama.cpp.
// Un seul modèle et une seule conversation à la fois (budget RAM du S21).
// Les fonctions ne doivent jamais être appelées en parallèle (voir LlamaEngine.kt).

#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-cpu.h"
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
ggml_threadpool *g_threadpool = nullptr;
int g_n_ctx = 4096;
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

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Fréquence max d'un cœur en kHz, 0 si illisible.
long cpu_max_freq(int cpu) {
    std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
    long khz = 0;
    return (f >> khz) ? khz : 0;
}

// Threads de calcul gardés d'un mot à l'autre et réservés aux cœurs rapides : sans réserve,
// ggml recrée ses threads à chaque calcul, et Android peut les démarrer sur les petits cœurs.
// Sur le Snapdragon 888 : les cœurs 4 à 7 (Cortex-A78 et X1), pas les Cortex-A55.
void attach_threadpool(int n_threads) {
    if (!g_context) return;
    llama_detach_threadpool(g_context);
    if (g_threadpool) {
        ggml_threadpool_free(g_threadpool);
        g_threadpool = nullptr;
    }

    ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
    const int n_cpus = std::min((int) sysconf(_SC_NPROCESSORS_CONF), GGML_MAX_N_THREADS);
    std::vector<long> freqs(std::max(n_cpus, 0));
    for (int i = 0; i < n_cpus; i++) freqs[i] = cpu_max_freq(i);
    const auto [lo, hi] = std::minmax_element(freqs.begin(), freqs.end());
    if (lo != freqs.end() && *lo > 0 && *hi > *lo) {
        int fast = 0;
        for (int i = 0; i < n_cpus; i++) {
            tpp.cpumask[i] = freqs[i] > *lo;
            fast += tpp.cpumask[i];
        }
        // Moins de cœurs rapides que de threads : on laisse Android choisir.
        if (fast < n_threads) std::fill(tpp.cpumask, tpp.cpumask + GGML_MAX_N_THREADS, false);
        else LOGI("threads sur %d cœurs rapides", fast);
    }

    g_threadpool = ggml_threadpool_new(&tpp);
    if (g_threadpool) llama_attach_threadpool(g_context, g_threadpool, nullptr);
    llama_set_n_threads(g_context, n_threads, n_threads);
}

void free_threadpool() {
    if (g_context) llama_detach_threadpool(g_context);
    if (g_threadpool) ggml_threadpool_free(g_threadpool);
    g_threadpool = nullptr;
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

// Formate un message avec le template de chat du modèle (jinja d'abord, ancien format sinon).
std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    std::string formatted;
    try {
        formatted = common_chat_format_single(
                g_chat_templates.get(), g_chat_msgs, msg, role == ROLE_USER, g_use_jinja);
    } catch (const std::exception &e) {
        LOGW("template jinja en échec (%s), retour au format simple", e.what());
        g_use_jinja = false;
        formatted = common_chat_format_single(
                g_chat_templates.get(), g_chat_msgs, msg, role == ROLE_USER, false);
    }
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

int decode_tokens(const llama_tokens &tokens, bool compute_last_logit) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur = std::min((int) tokens.size() - i, BATCH_SIZE);
        if (g_current_pos + cur >= g_n_ctx - OVERFLOW_HEADROOM) {
            shift_context();
        }
        g_batch.clear();
        for (int j = 0; j < cur; j++) {
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            g_batch.add(tokens[i + j], g_current_pos + j, 0, want_logit);
        }
        if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
            LOGE("llama_process a échoué");
            return 1;
        }
        g_current_pos += cur;
    }
    return 0;
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
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = BATCH_SIZE;
    cparams.n_ubatch = BATCH_SIZE;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return 2;
    }

    g_model = model;
    g_context = ctx;
    attach_threadpool(n_threads);
    g_batch = common_batch(ctx);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_use_jinja = true;
    clear_conversation();
    reset_stats();
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
        const llama_vocab *vocab = llama_model_get_vocab(g_model);
        llama_token end = llama_vocab_eot(vocab);
        if (end == LLAMA_TOKEN_NULL) end = llama_vocab_eos(vocab);
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
    free_threadpool();
    if (g_context) llama_free(g_context);
    if (g_model) llama_model_free(g_model);
    g_context = nullptr;
    g_model = nullptr;
}

// Nombre de cœurs utilisés, changé sans recharger le modèle.
JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_setThreads(JNIEnv *, jobject, jint n_threads) {
    attach_threadpool(n_threads);
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
