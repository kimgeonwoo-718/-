// 자바 쪽(LlmNative.kt)과 spell_llm.h 를 잇는 껍데기. 논리는 여기 없다.
//
// 글은 전부 UTF-8 ByteArray 로 주고받는다. JNI 의 GetStringUTFChars 는 '고친 UTF-8'(이모지 같은 보충 문자를 서러게이트 쌍
// 둘로 따로 적는 CESU-8)이라, 채팅에 흔한 😭 가 모델에 깨져 들어간다.
#include <jni.h>

#include <string>
#include <vector>

#include "spell_llm.h"

namespace {

std::string to_string(JNIEnv* env, jbyteArray a) {
    if (!a) return std::string();
    const jsize n = env->GetArrayLength(a);
    std::string s((size_t)n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(&s[0]));
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_spellkeyboard_ko_llm_LlmNative_nativeLoad(JNIEnv* env, jclass, jbyteArray path, jint threads, jint nCtx) {
    const std::string p = to_string(env, path);
    char err[256] = {0};
    spell_llm* h = spell_llm_load(p.c_str(), (int)threads, (int)nCtx, err, (int)sizeof(err));
    return reinterpret_cast<jlong>(h);
}

// 성공하면 번역문(UTF-8), 실패하면 null — 이유는 nativeLastError.
JNIEXPORT jbyteArray JNICALL Java_com_spellkeyboard_ko_llm_LlmNative_nativeGenerate(JNIEnv* env, jclass, jlong handle, jbyteArray system, jbyteArray user, jint maxNew) {
    spell_llm* h = reinterpret_cast<spell_llm*>(handle);
    const std::string sys = to_string(env, system);
    const std::string usr = to_string(env, user);
    std::vector<char> out(16384);
    const int n = spell_llm_generate(h, sys.c_str(), usr.c_str(), (int)maxNew, out.data(), (int)out.size());
    if (n < 0) return nullptr;
    jbyteArray result = env->NewByteArray(n);
    if (!result) return nullptr;
    if (n > 0) env->SetByteArrayRegion(result, 0, n, reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

JNIEXPORT jint JNICALL Java_com_spellkeyboard_ko_llm_LlmNative_nativeLastError(JNIEnv*, jclass, jlong handle) {
    return (jint)spell_llm_last_error(reinterpret_cast<spell_llm*>(handle));
}

JNIEXPORT void JNICALL Java_com_spellkeyboard_ko_llm_LlmNative_nativeAbort(JNIEnv*, jclass, jlong handle) {
    spell_llm_abort(reinterpret_cast<spell_llm*>(handle));
}

JNIEXPORT void JNICALL Java_com_spellkeyboard_ko_llm_LlmNative_nativeFree(JNIEnv*, jclass, jlong handle) {
    spell_llm_free(reinterpret_cast<spell_llm*>(handle));
}

}  // extern "C"
