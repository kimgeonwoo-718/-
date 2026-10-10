// 기기 안 번역 엔진(keyboard/src/main/cpp/spell_llm.cpp)을 컨테이너(x86)에서 진짜 모델로 시험하는 도구.
//
// 쓰는 법: cli <모델.gguf> <지시문 파일> [스레드 수]   — 표준 입력 한 줄이 번역할 글 하나다.
// 줄마다 번역문, 걸린 시간(ms), 재사용·새로 계산한 토큰 수를 찍는다.
#include <chrono>
#include <thread>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "spell_llm.h"

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s model.gguf system.txt [threads]\n", argv[0]);
        return 2;
    }
    std::ifstream f(argv[2]);
    std::stringstream ss;
    ss << f.rdbuf();
    const std::string system = ss.str();
    const int threads = argc > 3 ? std::atoi(argv[3]) : 4;

    char err[256] = {0};
    auto t0 = std::chrono::steady_clock::now();
    spell_llm* h = spell_llm_load(argv[1], threads, 2048, err, sizeof(err));
    if (!h) {
        std::fprintf(stderr, "load failed: %s\n", err);
        return 1;
    }
    auto ms = [](auto a, auto b) { return (long)std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count(); };
    std::fprintf(stderr, "loaded in %ld ms\n", ms(t0, std::chrono::steady_clock::now()));

    std::vector<char> out(8192);

    // 중단 시험: ABORT_TEST=1 이면 첫 줄을 먼저 번역하다 0.4초 뒤에 끊고, 바로 다음 번역이 정상으로 도는지 본다.
    if (std::getenv("ABORT_TEST")) {
        std::string src;
        std::getline(std::cin, src);
        std::thread killer([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(400));
            spell_llm_abort(h);
        });
        auto a = std::chrono::steady_clock::now();
        int n = spell_llm_generate(h, system.c_str(), src.c_str(), 256, out.data(), (int)out.size());
        std::printf("abort test: rc=%d last_error=%d after %ld ms\n", n, spell_llm_last_error(h), ms(a, std::chrono::steady_clock::now()));
        killer.join();
        a = std::chrono::steady_clock::now();
        n = spell_llm_generate(h, system.c_str(), src.c_str(), 256, out.data(), (int)out.size());
        std::printf("after abort: rc=%d last_error=%d %ld ms: %s\n", n, spell_llm_last_error(h), ms(a, std::chrono::steady_clock::now()), out.data());
        // 너무 긴 글, 한 글자도 없는 글.
        std::string longText(5000, 'x');
        n = spell_llm_generate(h, system.c_str(), longText.c_str(), 256, out.data(), (int)out.size());
        std::printf("too long: rc=%d (expect %d)\n", n, SPELL_LLM_ERR_TOO_LONG);
        n = spell_llm_generate(h, system.c_str(), "ㅋㅋㅋ", 256, out.data(), (int)out.size());
        std::printf("jamo only: rc=%d: %s\n", n, out.data());
        spell_llm_free(h);
        return 0;
    }

    std::string line;
    while (std::getline(std::cin, line)) {
        if (line.empty()) continue;
        auto a = std::chrono::steady_clock::now();
        int n = spell_llm_generate(h, system.c_str(), line.c_str(), 256, out.data(), (int)out.size());
        auto b = std::chrono::steady_clock::now();
        int reused = 0, computed = 0;
        spell_llm_stats(h, &reused, &computed);
        // 번역문 안의 줄바꿈은 ⏎ 로 바꿔 한 줄에 담는다(도구가 줄 단위로 읽는다).
        std::string text = n < 0 ? std::string() : std::string(out.data());
        for (size_t pos = 0; (pos = text.find('\n', pos)) != std::string::npos;) text.replace(pos, 1, "⏎");
        if (n < 0) std::printf("ERR %d\t[%ld ms]\n", n, ms(a, b));
        else std::printf("%s\t[%ld ms, reused %d, computed %d]\n", text.c_str(), ms(a, b), reused, computed);
        std::fflush(stdout);
    }
    spell_llm_free(h);
    return 0;
}
