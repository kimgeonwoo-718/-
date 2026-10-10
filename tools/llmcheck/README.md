# 기기 안 번역 엔진 시험 (tools/llmcheck)

폰에 올라가는 번역 엔진(`keyboard/src/main/cpp/spell_llm.cpp`)을 **컨테이너(x86)에서 진짜 모델로** 시험하는 도구다.
기기가 없어도 엔진의 논리(지시문 앞부분 재사용, 중단, 되풀이 끊기)와 번역 품질을 잴 수 있게 하려고 만들었다.
같은 C++ 파일을 그대로 쓰므로 여기서 되면 폰에서도 같은 코드가 돈다(CPU 명령만 다르다).

## 준비

1. llama.cpp 를 `tools/llm/PUBLISH` 의 `LLAMA_COMMIT` 에 맞춰 받아 빌드한다(`cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF && cmake --build build -j4 --target llama`).
2. 모델: 도커 허브 `ai/gemma4:e2b-q4_K_M` 의 GGUF 한 덩어리(3.1GB). 도커 레지스트리 API 로 받는다(토큰 → 매니페스트 → 첫 층). 허깅페이스는 이 환경에서 막혀 있다.

## 쓰기

    tools/llmcheck/build.sh <llama.cpp 폴더>                       # /tmp/llmcheck/cli 를 만든다
    echo "계산은 따로 해 주세요" | /tmp/llmcheck/cli 모델.gguf core/src/main/resources/llm_prompt_ja.txt 4
    tools/llmcheck/eval.py /tmp/llmcheck/cli 모델.gguf core/src/main/resources/llm_prompt_ja.txt ja b 4 [--show]
    ABORT_TEST=1 /tmp/llmcheck/cli ...                            # 중단·글자 없는 글 시험

`eval.py` 는 시험지(`server/bench/translate-cases*.tsv`)의 한국어를 엔진에 먹여 chrF·한글 남음·지연을 낸다. 점수 식이 `server/bench/translate.mjs` 와 같아서
서버 AI 와 숫자를 바로 견줄 수 있다. 지시문은 **앱이 쓰는 자원 파일 그대로** 읽는다.

## JNI 껍데기까지 시험하려면

`keyboard/src/main/cpp/CMakeLists.txt` 를 호스트에서 `-DLLAMA_DIR=… -DCMAKE_CXX_FLAGS="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux"` 로 빌드하면
`libspellllm.so` 가 나온다. 같은 패키지·이름(`com.spellkeyboard.ko.llm.LlmNative`)의 자바 클래스로 `System.load` 해 부르면 된다(바이트 배열로 주고받는다).
