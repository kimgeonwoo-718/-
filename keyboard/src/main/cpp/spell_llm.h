// 기기 안 번역 엔진의 C 층 — llama.cpp 위에 얹은 얇은 껍데기.
//
// 왜 이렇게 얇은가: 안드로이드 쪽(JNI)과 컨테이너의 x86 시험 도구가 **같은 코드**를 쓰게 하려는 것이다. 기기가 없는 곳에서
// 실제 모델로 이 파일의 논리(지시문 앞부분 재사용, 중단, 되풀이 감시)를 시험할 수 있어야 한다. JNI 는 이 함수들을 부르기만 한다.
//
// 스레드: 한 핸들은 한 번에 한 스레드만 spell_llm_generate 를 부른다. spell_llm_abort 만 다른 스레드에서 불러도 된다.
#ifndef SPELL_LLM_H
#define SPELL_LLM_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct spell_llm spell_llm;

// spell_llm_generate 의 오류 값.
#define SPELL_LLM_ERR_ARGS       (-1)  // 인자가 틀렸다
#define SPELL_LLM_ERR_TOKENIZE   (-2)  // 글을 토큰으로 못 바꿨다
#define SPELL_LLM_ERR_DECODE     (-3)  // 모델이 계산에 실패했다
#define SPELL_LLM_ERR_ABORTED    (-4)  // spell_llm_abort 로 멈췄다
#define SPELL_LLM_ERR_TOO_LONG   (-5)  // 지시문 + 글이 문맥 길이를 넘는다
#define SPELL_LLM_ERR_DEGENERATE (-6)  // 같은 말을 되풀이하기 시작해 끊었다
#define SPELL_LLM_ERR_OUT_SMALL  (-7)  // 결과를 담을 칸이 모자라다

// 모델을 읽는다. 분할 GGUF(-00001-of-0000N.gguf)는 첫 조각 경로를 주면 나머지를 같은 폴더에서 찾는다.
// 실패하면 NULL 이고 err 에 이유가 적힌다(err 는 NULL 이어도 된다).
spell_llm* spell_llm_load(const char* model_path, int n_threads, int n_ctx, char* err, int err_len);

// 번역 한 번. system 은 지시문(앞부분이라 이전과 같으면 계산을 다시 하지 않는다), user 는 옮길 글.
// 결과(UTF-8, 끝 널 문자 포함)를 out 에 쓰고 글자 수(바이트)를 돌려준다. 음수면 위의 오류 값.
int spell_llm_generate(spell_llm* h, const char* system, const char* user, int max_new_tokens, char* out, int out_len);

// 마지막 spell_llm_generate 가 오류였으면 그 값, 아니면 0.
int spell_llm_last_error(spell_llm* h);

// 돌고 있는 spell_llm_generate 를 멈추게 한다(다른 스레드에서 불러도 된다). 다음 generate 는 정상으로 돈다.
void spell_llm_abort(spell_llm* h);

// 지금까지 앞부분 재사용으로 건너뛴 토큰 수와 새로 계산한 토큰 수(진단용).
void spell_llm_stats(spell_llm* h, int* reused_tokens, int* computed_tokens);

void spell_llm_free(spell_llm* h);

#ifdef __cplusplus
}
#endif
#endif  // SPELL_LLM_H
