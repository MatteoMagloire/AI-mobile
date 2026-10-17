// Pont JNI entre l'app Kotlin et llama.cpp.
// Un seul modèle et une seule conversation à la fois (budget RAM du S21).
// Les fonctions ne doivent jamais être appelées en parallèle (voir LlamaEngine.kt).

#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <sstream>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

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
bool g_use_jinja = true;

std::vector<common_chat_msg> g_chat_msgs;
llama_pos g_system_prompt_pos = 0;
llama_pos g_current_pos = 0;
llama_pos g_stop_pos = 0;
std::string g_cached_chars;
std::ostringstream g_assistant_ss;
bool g_assistant_pending = false;

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
    g_batch = common_batch(ctx);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_use_jinja = true;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_configureSampler(
        JNIEnv *, jobject, jfloat temp, jfloat top_p) {
    if (!g_model) return;
    free_sampler();
    common_params_sampling sparams;
    sparams.temp = temp;
    sparams.top_p = top_p;
    g_sampler = common_sampler_init(g_model, sparams);
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_startConversation(
        JNIEnv *env, jobject, jstring jsystem_prompt) {
    if (!g_context) return 1;
    g_chat_msgs.clear();
    g_assistant_pending = false;
    g_system_prompt_pos = g_current_pos = 0;
    reset_short_term();
    llama_memory_clear(llama_get_memory(g_context), false);

    std::string prompt = to_string(env, jsystem_prompt);
    if (prompt.empty()) return 0;

    const bool has_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_template) prompt = chat_add_and_format(ROLE_SYSTEM, prompt);

    const auto tokens = common_tokenize(g_context, prompt, true, true);
    if ((int) tokens.size() > g_n_ctx / 2) {
        LOGE("prompt système trop long (%d tokens)", (int) tokens.size());
        return 2;
    }
    if (decode_tokens(tokens, false)) return 3;
    g_system_prompt_pos = g_current_pos;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_sendUserMessage(
        JNIEnv *env, jobject, jstring jtext, jint n_predict) {
    if (!g_context || !g_sampler) return 1;
    commit_assistant_message();
    reset_short_term();

    std::string text = to_string(env, jtext);
    const bool has_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_template) text = chat_add_and_format(ROLE_USER, text);

    // add_special seulement au tout début (BOS), sinon les tokens spéciaux du template sont analysés.
    auto tokens = common_tokenize(g_context, text, g_current_pos == 0, true);
    const int max_tokens = g_n_ctx / 2;
    if ((int) tokens.size() > max_tokens) {
        LOGW("message trop long, tronqué à %d tokens", max_tokens);
        tokens.resize(max_tokens);
    }
    if (decode_tokens(tokens, true)) return 2;

    common_sampler_reset(g_sampler);
    g_stop_pos = g_current_pos + n_predict;
    g_assistant_pending = true;
    return 0;
}

// Renvoie le morceau de texte suivant, "" si le token est incomplet (UTF-8), ou null quand c'est fini.
JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_nextToken(JNIEnv *env, jobject) {
    if (!g_context || !g_assistant_pending) return nullptr;

    if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM) shift_context();
    if (g_current_pos >= g_stop_pos) {
        commit_assistant_message();
        return nullptr;
    }

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

JNIEXPORT void JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_unload(JNIEnv *, jobject) {
    g_chat_msgs.clear();
    g_assistant_pending = false;
    reset_short_term();
    free_sampler();
    g_chat_templates.reset();
    g_batch = common_batch();
    if (g_context) llama_free(g_context);
    if (g_model) llama_model_free(g_model);
    g_context = nullptr;
    g_model = nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_matteomagloire_aimobile_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

}  // extern "C"
