# 기기 안 번역 엔진(libspellllm.so). llama.cpp 를 소스째 정적으로 끌어와 한 덩어리로 묶는다.
#
# llama.cpp 소스는 저장소에 없다. Gradle(fetchLlama)이 우리 릴리스에서 고정된 꾸러미를 받아 풀어 놓고 그 경로를 LLAMA_DIR 로 준다.
cmake_minimum_required(VERSION 3.22.1)

# 안드로이드 그래들은 디버그 변형이면 CMAKE_BUILD_TYPE=Debug 를 준다. 그대로 두면 모델 계산이 -O0 으로 돌아 수십 배 느려진다.
# 디버그 APK 도 최적화해서 빌드한다(CI 가 올리는 것이 디버그 APK 다).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project(spellllm LANGUAGES C CXX)

if(NOT LLAMA_DIR OR NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "LLAMA_DIR 이 비었거나 llama.cpp 소스가 없다: '${LLAMA_DIR}'")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -g0")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -g0")

# 안드로이드 15+ 는 16KB 페이지 기기가 있다. Play 는 네이티브 라이브러리가 16KB 로 정렬돼 있기를 요구한다.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# llama.cpp 설정: 정적으로, 필요한 것만, 네트워크(FetchContent·curl) 없이.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# 폰 CPU 의 바닥선: dotprod 와 fp16 연산(2017년 이후 칩). 이보다 오래된 기기는 앱이 쓰지 않는다(LlmSupport).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama-build EXCLUDE_FROM_ALL)

add_library(spellllm SHARED spell_llm.cpp spell_llm_jni.cpp)
target_include_directories(spellllm PRIVATE "${LLAMA_DIR}/include" "${LLAMA_DIR}/ggml/include")
target_link_libraries(spellllm PRIVATE llama ggml)
if(ANDROID)
    target_link_libraries(spellllm PRIVATE log android)
endif()
