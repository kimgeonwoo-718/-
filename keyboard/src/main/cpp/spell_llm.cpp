// spell_llm.h 의 구현. 읽는 순서: 프롬프트 짓기 → 토큰 → 앞부분 재사용 → 생성 → 감시.
#include "spell_llm.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

struct spell_llm {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* sampler = nullptr;
    // 지금 KV 캐시(순서 0)에 들어 있는 토큰들. 다음 요청과 앞이 같은 만큼은 다시 계산하지 않는다.
    std::vector<llama_token> cached;
    std::atomic<bool> abort_flag{false};
    int n_ctx = 0;
    int n_batch = 256;
    long reused = 0;
    long computed = 0;
    int last_error = 0;  // 마지막 spell_llm_generate 의 결과가 오류였으면 그 값(성공이면 0)
};

namespace {

void set_err(char* err, int err_len, const char* msg) {
    if (err && err_len > 0) std::snprintf(err, err_len, "%s", msg);
}

void quiet_log(enum ggml_log_level level, const char* text, void*) {
    // 기본은 조용히. 시험할 때 SPELL_LLM_LOG=1 이면 오류와 경고만 stderr 로.
    static const bool verbose = std::getenv("SPELL_LLM_LOG") != nullptr;
    if (verbose && level >= GGML_LOG_LEVEL_WARN) std::fputs(text, stderr);
}

bool abort_cb(void* data) {
    return static_cast<spell_llm*>(data)->abort_flag.load(std::memory_order_relaxed);
}

// 제마 4 의 대화 모양. 모델 파일의 chat_template 과 같게 손으로 적었다(시험으로 맞춰 봤다).
// 한 모델에 묶어 둔 앱이라 템플릿 엔진(jinja)을 들이지 않았다. 모델을 바꾸면 여기를 같이 바꾼다.
std::string build_prompt(const char* system, const char* user) {
    std::string p;
    p.reserve(std::strlen(system) + std::strlen(user) + 96);
    p += "<|turn>system\n";
    p += system;
    p += "<turn|>\n<|turn>user\n";
    p += user;
    p += "<turn|>\n<|turn>model\n";
    return p;
}

bool tokenize(const llama_vocab* vocab, const std::string& text, std::vector<llama_token>& out) {
    out.resize(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), out.data(), (int32_t)out.size(), /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) {
        out.resize((size_t)(-n));
        n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(), out.data(), (int32_t)out.size(), true, true);
    }
    if (n < 0) return false;
    out.resize((size_t)n);
    return true;
}

// 끝에서부터 주기 1..8 짜리 무늬가 4번 넘게 되풀이되면 참. 작은 모델이 같은 말을 끝없이 되풀이하는 것을 끊는다.
bool looping(const std::vector<llama_token>& gen) {
    const size_t n = gen.size();
    for (size_t period = 1; period <= 8; period++) {
        if (n < period * 5) continue;
        bool same = true;
        for (size_t i = 0; i < period * 4 && same; i++) {
            if (gen[n - 1 - i] != gen[n - 1 - i - period]) same = false;
        }
        if (same) return true;
    }
    return false;
}

}  // namespace

extern "C" {

spell_llm* spell_llm_load(const char* model_path, int n_threads, int n_ctx, char* err, int err_len) {
    if (!model_path || n_threads < 1 || n_ctx < 256) {
        set_err(err, err_len, "bad arguments");
        return nullptr;
    }
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
        backend_ready = true;
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // 파일을 메모리에 얹어 두고(mmap) 필요한 쪽만 읽는다. 제마 4 의 '층별 임베딩'(약 1.4GB)은 토큰마다 몇 줄만 쓰므로
    // LAZY_MODE_ON 이면 그 표를 통째로 올리지 않는다 — '유효 2B' 라는 이름값이 메모리에서 나오는 자리다.
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.lazy_mode = LLAMA_LAZY_MODE_ON;
    llama_model* model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        set_err(err, err_len, "model load failed");
        return nullptr;
    }

    auto* h = new spell_llm();
    h->model = model;
    h->vocab = llama_model_get_vocab(model);
    h->n_ctx = n_ctx;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)n_ctx;
    cp.n_batch = (uint32_t)h->n_batch;
    cp.n_ubatch = (uint32_t)h->n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    // 미끄럼 창 주의(SWA) 층이 있는 모델은 이게 꺼져 있으면 캐시를 중간에서 자를 수 없다 — 지시문 앞부분만 남기고 뒤를 버리는 재사용이 막힌다.
    cp.swa_full = true;
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = h;
    h->ctx = llama_init_from_model(model, cp);
    if (!h->ctx) {
        set_err(err, err_len, "context init failed");
        llama_model_free(model);
        delete h;
        return nullptr;
    }
    h->sampler = llama_sampler_init_greedy();
    return h;
}

static int generate_impl(spell_llm* h, const char* system, const char* user, int max_new_tokens, char* out, int out_len);

int spell_llm_generate(spell_llm* h, const char* system, const char* user, int max_new_tokens, char* out, int out_len) {
    int rc = generate_impl(h, system, user, max_new_tokens, out, out_len);
    if (h) h->last_error = rc < 0 ? rc : 0;
    return rc;
}

int spell_llm_last_error(spell_llm* h) {
    return h ? h->last_error : SPELL_LLM_ERR_ARGS;
}

static int generate_impl(spell_llm* h, const char* system, const char* user, int max_new_tokens, char* out, int out_len) {
    if (!h || !system || !user || !out || out_len < 2 || max_new_tokens < 1) return SPELL_LLM_ERR_ARGS;
    h->abort_flag.store(false);
    out[0] = '\0';

    std::vector<llama_token> prompt;
    if (!tokenize(h->vocab, build_prompt(system, user), prompt)) return SPELL_LLM_ERR_TOKENIZE;
    if ((int)prompt.size() + max_new_tokens > h->n_ctx) return SPELL_LLM_ERR_TOO_LONG;

    llama_memory_t mem = llama_get_memory(h->ctx);

    // 앞이 같은 만큼은 이미 계산돼 있다. 마지막 토큰은 늘 새로 계산해야 다음 글자 확률(logits)을 얻는다.
    size_t keep = 0;
    while (keep < h->cached.size() && keep < prompt.size() && h->cached[keep] == prompt[keep]) keep++;
    if (keep >= prompt.size()) keep = prompt.size() - 1;
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    h->cached.resize(keep);
    h->reused += (long)keep;

    // 계산이 중간에 끊기면(중단·오류) 끊긴 덩어리가 캐시에 반쯤 남았을 수 있다. 확실히 끝난 토큰(h->cached)까지만 남기고 뒤를 잘라 낸다 —
    // 지시문 앞부분은 그대로 살아 있어서, 글을 계속 치며 번역이 자꾸 끊겨도 다음 요청이 지시문을 다시 계산하지 않는다.
    // 자를 수 없으면 그때만 통째로 비운다.
    auto fail_reset = [&](int code) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos)h->cached.size(), -1)) {
            llama_memory_clear(mem, true);
            h->cached.clear();
        }
        return code;
    };

    // 새로 계산할 부분(prompt[keep..])을 덩어리로 먹인다. 덩어리가 끝날 때마다 cached 에 올려 둔다(끊겨도 거기까지는 믿을 수 있다).
    for (size_t i = keep; i < prompt.size();) {
        size_t n = std::min((size_t)h->n_batch, prompt.size() - i);
        llama_batch b = llama_batch_get_one(prompt.data() + i, (int32_t)n);
        int rc = llama_decode(h->ctx, b);
        if (h->abort_flag.load()) return fail_reset(SPELL_LLM_ERR_ABORTED);
        if (rc != 0) return fail_reset(SPELL_LLM_ERR_DECODE);
        h->cached.insert(h->cached.end(), prompt.begin() + (long)i, prompt.begin() + (long)(i + n));
        h->computed += (long)n;
        i += n;
    }

    std::string text;
    std::vector<llama_token> gen;
    char piece[256];
    for (int step = 0; step < max_new_tokens; step++) {
        if (h->abort_flag.load()) return fail_reset(SPELL_LLM_ERR_ABORTED);
        llama_token tok = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int n = llama_token_to_piece(h->vocab, tok, piece, (int32_t)sizeof(piece), 0, /*special=*/false);
        if (n > 0) text.append(piece, (size_t)n);
        gen.push_back(tok);
        if (looping(gen)) return fail_reset(SPELL_LLM_ERR_DEGENERATE);

        llama_batch b = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(h->ctx, b);
        if (h->abort_flag.load()) return fail_reset(SPELL_LLM_ERR_ABORTED);
        if (rc != 0) return fail_reset(SPELL_LLM_ERR_DECODE);
        h->cached.push_back(tok);
        h->computed += 1;
    }

    if ((int)text.size() + 1 > out_len) return SPELL_LLM_ERR_OUT_SMALL;
    std::memcpy(out, text.data(), text.size());
    out[text.size()] = '\0';
    return (int)text.size();
}

void spell_llm_abort(spell_llm* h) {
    if (h) h->abort_flag.store(true);
}

void spell_llm_stats(spell_llm* h, int* reused_tokens, int* computed_tokens) {
    if (!h) return;
    if (reused_tokens) *reused_tokens = (int)h->reused;
    if (computed_tokens) *computed_tokens = (int)h->computed;
}

void spell_llm_free(spell_llm* h) {
    if (!h) return;
    if (h->sampler) llama_sampler_free(h->sampler);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

}  // extern "C"
