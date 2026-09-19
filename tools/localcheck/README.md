# 그레이들 없이 검사하기

이 컨테이너(클로드 코드 원격 환경)는 **`dl.google.com` 이 막혀 있다**. 안드로이드 그레이들
플러그인도 androidx 도 거기서 받아야 해서, `./gradlew` 가 **아무 일감에서나 죽는다** —
`:core:test` 조차 루트 `build.gradle.kts` 가 그 플러그인을 `apply false` 로 걸어 두는 탓에
못 돈다.

그래서 확인이 전부 CI 로 밀렸고, 실제로 **컴파일 오류를 세 번 연속 올렸다.** 여기 둘이
그걸 지역에서 잡는다.

| | 무엇을 | 얼마나 |
|---|---|---|
| `test-core.sh` | 코어 단위 시험 240개 | 1초 |
| `typecheck-keyboard.sh` | 키보드 모듈 코틀린 타입 검사 | 30초 |
| `try.sh` | **진짜 엔진에 글을 넣어 보고 결과를 본다** | 40초 |
| `measure-spacing.sh` | **띄어쓰기 고침이 이득인지 손해인지 잰다** (회귀 그물) | 40초 |
| `eval-spacing.sh` | **띄어쓰기 품질을 네 잣대로 잰다** (자) | 2~6분 |

`try.sh` 는 "실기기에서 이 말이 안 고쳐진다" 는 제보를 재현할 때 쓴다. 단위 시험은
규칙만 켜 놓고 돌지만 이쪽은 사전과 언어모델까지 올린다 — 그래서 결과가 다를 수 있고,
바로 그 차이가 제보의 정체인 경우가 많다.

    tools/localcheck/try.sh 듣는둥마는둥
    tools/localcheck/try.sh -f 문장들.txt

낱말마다 **언어모델이 아는지**(`앎`/`모름`)까지 찍는다. 붙여 쓴 꼴을 낱말로 알고 있으면
디코더가 거기서 멈추므로, 안 고쳐지는 이유가 대개 거기서 드러난다.

Kiwi 는 안 올라간다(안드로이드용 AAR 이라). 공백을 아예 안 친 긴 덩어리는 실기기보다
결과가 나쁘게 나온다.

## `measure-spacing.sh`

띄어쓰기는 **한쪽을 얻으면 다른 쪽을 잃는다.** 문지방 하나를 8에서 7로 내려 보면 붙여 쓴 글을
되살리는 것이 19→20으로 하나 느는 대신, 붙여 써야 맞는 낱말을 가르는 오교정이 6→9로 셋 늘었다.
**숫자 없이 고치면 좋아진 줄 알고 나빠진다.**

고치기 **전에** 한 번 돌려 숫자를 적어 두고, 고친 **뒤에** 다시 돌려 견줘라.

    tools/localcheck/measure-spacing.sh          숫자만
    tools/localcheck/measure-spacing.sh --diff   무엇을 건드렸는지까지

시험지는 `tools/localcheck/spacing/` 에 있다(50문장). 한계도 거기 적어 뒀다 — 회귀를 잡는
그물이지 정확도를 재는 자가 아니다.

## `eval-spacing.sh`

`measure-spacing.sh` 가 50문장짜리 **회귀 그물**이라면 이쪽은 **자**다. 얼마나 좋은지를 잰다.

    tools/localcheck/eval-spacing.sh                  붙어 있는 구어체 230문장
    EVAL_SHOW=40 tools/localcheck/eval-spacing.sh     틀린 것을 찍는다
    EVAL_LIMIT=400 SPELL_EVAL_FILES=a.tsv,b.tsv ...   격식체까지

네 가지를 따로 잰다. **하나만 보고 고치면 나머지가 조용히 나빠진다.**

1. **멀쩡한 글 건드림** — 제일 중요하다. 이걸 올리는 변경은 나머지를 아무리 올려도 손해다.
2. **공백 하나 복원** — 실시간 교정이 실제로 만나는 입력.
3. **절반 지움** — 급히 친 글. 경계 정밀도/재현율/F1.
4. **전부 지움** — 전체교정이 만나는 최악.

시험지는 둘이다.

- `spacing/chat.txt` (저장소에 있다) — 이 키보드에 실제로 칠 법한 구어체 230문장.
  **보조용언은 붙여 쓴 꼴을 정답으로 뒀다**(한글 맞춤법 제47항 허용). 사람들이 그렇게
  치고, 엔진이 손대지 않는 것이 맞기 때문이다. 원칙대로 띄어 쓴 꼴을 정답으로 두면
  "둘 다 맞는 것" 을 틀렸다고 세게 된다.
- `SPELL_EVAL_FILES` — 말뭉치 밖 격식체(KorNLI). 이 컨테이너에서 받을 수 있다:

      curl -fsSL -O https://raw.githubusercontent.com/kakaobrain/kor-nlu-datasets/master/KorNLI/xnli.dev.ko.tsv
      curl -fsSL -O https://raw.githubusercontent.com/kakaobrain/kor-nlu-datasets/master/KorNLI/xnli.test.ko.tsv

Kiwi 는 여기 없다(안드로이드 AAR). 3·4번은 실기기보다 나쁘게 나온다 — **견주는 데 쓰지,
실기기 성능이라고 읽지 마라.**

## 어떻게 되는가

- **코어**는 순수 코틀린이고 의존성이 stdlib 하나뿐이다. 컴파일러와 JUnit 은 이미
  그레이들 캐시에 있으니 직접 부르면 된다. 요령 둘: 시험 코드가 `internal` 을 보려면
  `-Xfriend-paths` 가 필요하고, `core/src/main/resources` 의 `.gz` 를 출력 폴더에
  같이 넣어야 한다.
- **키보드**는 안드로이드가 필요한데 SDK 를 못 받는다. `android.jar` 대신
  **메이븐 중앙**의 `org.robolectric:android-all` 을 쓴다(구글 메이븐이 아니라서 뚫린다).
  androidx·결제·ML Kit 은 `stubs/` 에 **쓰는 자리의 서명만** 흉내낸 껍데기를 뒀다.
  `R` 과 `BuildConfig` 는 소스에서 이름을 긁어 매번 다시 만든다.

껍데기는 **돌리기 위한 것이 아니라 타입을 맞추기 위한 것**이다. 새 API 를 쓰기 시작하면
"unresolved reference" 가 나는데, 그때 그 자리의 서명만 `stubs/` 에 더하면 된다.

## 못 잡는 것

리소스(`res/`)·매니페스트·프로가드·실제 실행. 그건 CI 가 본다. **올린 뒤에는 빌드
결과를 반드시 확인해라** — 이 검사를 통과해도 CI 가 빨간불일 수 있다.
