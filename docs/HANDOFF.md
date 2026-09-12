# 이어서 작업하는 사람에게

맞춤법 키보드(`core/`, `keyboard/`) 작업 인계 메모. 세션이 끊겨도 여기부터 읽으면 된다.

브랜치: `claude/auto-spell-spacing-checker-r4ymq5`
APK: https://github.com/kimgeonwoo-718/-/releases/tag/apk-latest (푸시할 때마다 갱신)

---

## 지금 어디까지 왔나

목표는 **타이핑을 따라가며 실시간으로 맞춤법·띄어쓰기를 고치는 안드로이드 키보드(IME)**.
카카오톡 수준의 즉각적인 교정이 기준선이다.

동작이 **검증된** 것 (JVM 테스트 88개, `./gradlew :core:test`):

| 부품 | 상태 |
|---|---|
| 한글 두벌식 오토마타 | 겹받침·복합모음·받침이동·백스페이스 역조합까지 |
| 규칙 교정 | 맞춤법 ~120항목 + 띄어쓰기 패턴 |
| 띄어쓰기 (`Spacer`) | mecab-ko-dic + 연결비용 행렬 Viterbi. `아버지가방에들어가신다` 갈림. 어절당 0.25ms |
| 맞춤법 (`Speller`) | 분석비용 비교. `안녕하새요`, `햇는대요` 잡음 |
| Gemini 교정 | 요청 조립·응답 파싱·오류 처리·모델 자동 교체 |

**즉 엔진은 다 됐다.** 남은 건 전부 안드로이드 연결부와 실기기 동작이다.

---

## 아직 안 되는 것 (여기서부터 시작)

사용자가 마지막으로 확인한 결과: **실기기에서 여전히 만족스럽게 동작하지 않음.**
어느 부분이 어떻게 안 되는지는 아직 못 들었다. 그걸 먼저 물어야 한다.

직전 커밋(`7b51c21`)에서 고친 것 — 아직 사용자 확인 전:

1. **`Speller` 가 키보드에 연결조차 안 돼 있었다.** `loadSpacingDictionary()` 에서
   `Spacer` 만 넣고 `session.engine.speller` 는 null 로 뒀다. 즉 지금까지 실기기에서는
   규칙 목록에 없는 오타가 **하나도** 안 잡혔다. 사용자가 계속 말한
   "교정 안 되는 게 너무 많다" 의 유력한 원인. → 연결함.
2. **AI 버튼이 "교정하는 중…" 에서 안 움직임.** 모델 자동 교체 경로가 붙으면서
   요청이 최대 3회 오가는데 건당 타임아웃이 40초였다. → 20초 워치독 + 건당 13초로 축소.

---

## 이 환경의 제약 (매번 걸려 넘어지는 것들)

- **AGP 를 못 받는다.** `dl.google.com` 이 프록시에서 403. `:keyboard` 모듈은
  **이 컨테이너에서 컴파일조차 안 된다.** 안드로이드 코드 검증은 CI 가 유일한 경로다.
- `:core` 만 로컬에서 돈다. 그것도 별도 `settings.gradle.kts` 를 스크래치패드에 만들어
  `./gradlew -p <scratch> :core:test` 로 돌려야 한다 (루트 빌드는 AGP 때문에 죽는다).
- **실기기 로그를 볼 수 없다.** logcat 없음. 그래서 지금까지 계속 추측으로 고쳐 왔고,
  그게 이 프로젝트가 느리게 진행된 가장 큰 이유다.
- Actions 아티팩트(Azure 블롭)도 막혀 있다. APK 전달은 릴리스 경유만 가능.
- 위키 덤프·HuggingFace 차단. 한국어 코퍼스를 못 구해서 딥러닝 모델 학습은 불가.
  PyPI 는 열려 있다 (mecab-ko-dic 을 여기서 구했다).

---

## 다음에 할 일

**먼저 사용자에게 이것부터 물어라. 추측으로 고치지 마라.**

1. 어떤 문장이 안 고쳐졌나 (정확한 글자)
2. 키보드 **상단 줄에 뜬 문구** — 진단 정보가 전부 거기 나온다:
   - `검사함: … · 고칠 것 없음` → 엔진까지 갔는데 규칙/사전이 안 걸림
   - `커서 앞 글자를 읽지 못했습니다` → `getTextBeforeCursor` 실패 (mirror 폴백 확인)
   - `이 입력란에서는 교정하지 않습니다` → `isCorrectableField` 판정 문제
   - 아무 변화 없음 → 키 입력 자체가 안 들어옴
3. 설정 화면 **"교정 엔진 점검하기"** 결과 — 여기가 전부 OK 인데 키보드에서 안 되면
   원인은 100% 연결부다. `띄어쓰기 사전: 준비됨` 이 뜨는지도 같이 본다.
4. AI 가 안 되면 **"이 키로 쓸 수 있는 모델 보기"** 버튼 결과

### AI 교정이 계속 실패한다면

`GeminiCorrector.DEFAULT_MODEL = "gemini-3.5-flash-lite"` — 구글이 2.x 를 404 로 거절하며
직접 지목한 이름이다(2026-09). 서버가 거절하면 `ListModels` 로 자동 교체한다. 진단 화면의
실패 줄에는 번역문 아래 "원문:" 으로 예외 종류와 서버 메시지 앞부분이 같이 찍힌다.
"Deploy AI server" 로그 끝에는 CI 가 서버를 거쳐 모델 목록과 교정 한 번을 보낸 결과가 있다.

### 품질을 더 올리려면

지금 `Speller` 는 자모 혼동집합 기반 2회 편집까지만 본다. 문맥이 필요한 판단
(로서/로써, 든지/던지)은 원리적으로 못 한다. 그건 AI 버튼의 영역이거나,
문자 단위 딥러닝 모델이 필요하다 (코퍼스가 없어서 이 환경에서는 불가).

---

## 설치할 때 주의

CI 가 매번 **다른 디버그 키로 서명**한다. 덮어쓰기 설치가 "서명이 일치하지 않습니다"
로 실패하므로, 새 APK 를 깔 때는 **기존 앱을 먼저 지워야 한다.** 매번 안내할 것.

Play Protect 경고는 정상이다 — 사이드로드 + 디버그 서명 + 키보드 앱 조합이라
구조적으로 악성 키보드와 똑같이 생겼다. 앱은 `INTERNET` 권한만 쓰고, 그것도
AI 교정에만 쓴다 (온디바이스 교정은 네트워크 없이 동작).

---

## 설계에서 흔들리지 말 것

- **오교정 한 번이 미교정 열 번보다 나쁘다.** 문맥 없이도 거의 항상 틀린 표기만 고친다.
  신조어·고유명사·은어는 건드리지 않는다.
- **실시간 경로에 네트워크를 쓰지 않는다.** 타이핑 간격이 100~200ms 인데 API 왕복은
  수백 ms 다. AI 는 사용자가 버튼을 눌렀을 때만.
- **로직은 안드로이드 밖에 둔다.** `TypingSession` + `FakeEditor` 로 "스페이스를 눌렀을 때
  화면이 어떻게 바뀌는가" 를 JVM 테스트로 재현할 수 있다. 실기기 로그가 없는 환경에서
  이게 유일한 방어선이다.

---

## AI 중계 서버 + Play 구독 (2026-09-11)

앱에는 Gemini 키가 **없다.** 사용자가 자기 키를 넣지 않으면 `server/`(Cloudflare Worker)로
보내고, 서버가 키를 붙여 구글로 넘긴다. 서버가 무료 한도(설치 ID 당 하루 5 회)를 세고,
`X-Purchase-Token` 을 Play Developer API 에 물어 구독자를 가린다. 폰의 요금제 스위치는
없앴다 — 폰에 저장된 값은 누구나 고칠 수 있다.

```
앱 ──(X-Install-Id, X-Purchase-Token)──▶ Worker ──(x-goog-api-key)──▶ Gemini
                                          ├─ D1: 설치 ID·IP 별 하루 사용량
                                          └─ Play API 로 구독 확인 (1시간 캐시)
```

서버 로직은 `server/test` 에 있고 `node --test` 로 돈다(의존성 없음). 앱은 호스트만
바꾸므로 모델 고르기·갈아타기 로직과 그 테스트는 그대로다.

### 사용자가 해야 하는 것 (순서대로)

**1. Cloudflare** — https://dash.cloudflare.com 가입(무료)
- 오른쪽 위 프로필 → My Profile → API Tokens → Create Token → "Edit Cloudflare Workers" 템플릿
  → Continue → Create Token → 복사
- Workers & Pages 페이지 오른쪽에 Account ID 가 보인다 → 복사
- GitHub 비밀값: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`
- GitHub 비밀값 `GEMINI_API_KEY` 에 AI Studio 키. 이게 없으면 서버는 뜨지만 교정 요청마다
  503(`server_not_configured`)을 돌려준다.
- `server/**` 를 건드린 푸시(또는 Actions 에서 "Deploy AI server" 수동 실행)가 D1 을 만들고
  workers.dev 이름을 API 로 정하고(계정당 한 번, 대시보드 안 거침) 배포한다. 실행 요약 위에
  노란 notice 로 주소가 뜬다: `https://spell-keyboard.<이름>.workers.dev`
- Cloudflare 토큰은 "Edit Cloudflare Workers" 템플릿에 **Account / D1 / Edit** 를 더해야 한다.
  Account ID 는 대시보드 주소창의 32 자리다 (새 대시보드에는 따로 적힌 칸이 없다).
- 그 주소는 build-apk.yml 에 기본값으로 박혀 있다(비밀 아님). 서버를 옮기면 GitHub 변수
  `AI_SERVER_URL` 로 덮어쓴다. 진단 화면 첫 줄이 "중계 서버 경유" 로 나오면 앱이 서버를 쓰는 것.

**2. Play Console** — https://play.google.com/console 개발자 등록($25, 개인 가능)
- 앱 만들기 → 패키지 `com.spellkeyboard.ko`
- 수익 창출 → 구독 → 구독 만들기: 상품 ID **`ai_unlimited_monthly`** (BillingManager 와
  글자 하나까지 같아야 한다), 기본 요금제 월 2,990 원
- 내부 테스트 트랙에 APK 올리고 테스터 이메일 등록 → 그 링크로 설치한 앱에서만 결제창이 뜬다.
  사이드로드한 APK 에서는 Play 결제가 "상품을 찾지 못했습니다" 로 끝난다 — 정상이다.
- 설정 → API 액세스 → 서비스 계정 만들기(Cloud Console 로 넘어감) → 키(JSON) 만들기 →
  Play Console 로 돌아와 그 계정에 "재무 데이터 보기" 권한 부여
- 그 JSON 파일 내용을 통째로 GitHub 비밀값 `PLAY_SERVICE_ACCOUNT` 에 → 서버 재배포
  ("Deploy AI server" 수동 실행). 이게 없으면 서버는 모든 토큰을 무료로 본다.

### 구글이 "User location is not supported" 라고 하면

Worker 는 사용자 근처 데이터센터에서 돌고, 한국 통신사 트래픽은 홍콩 센터에 붙는 일이
잦다. 구글은 홍콩 IP 를 거절한다. 그래서 구글 호출은 `GoogleRelay`(Durable Object,
위치 힌트 `enam`) 안에서만 한다. 이 오류가 다시 보이면 그 객체가 다른 곳에 만들어진
것이다 — `RELAY_NAME` 을 바꿔 새 객체를 만들면 힌트가 다시 먹는다.

### 알아 둘 구멍

- 설치 ID 는 지우고 다시 깔면 새로 난다 → 무료 5 회도 새로 난다. IP 한도(하루 300)가
  한 번 더 거른다. 제대로 막으려면 Play Integrity 나 로그인 — 아직 그럴 가치가 없다.
- Play 확인 결과를 1 시간 캐시하므로 해지 후 그만큼은 더 쓸 수 있다.
- D1 무료 등급: 하루 쓰기 10 만 건. 교정 한 번에 쓰기 두 번(설치 ID, IP).
- Billing 라이브러리 7.1.1 의 `PendingPurchasesParams` API 는 이 컨테이너에서 컴파일해
  보지 못했다(AGP 차단). CI 가 첫 검증이다.

---

## 키보드 모양 설정 (2026-09-12)

- **테마**: `ThemeMode`(밝게/어둡게, 시스템 따라가기는 요청으로 뺌) → `KeyboardTheme.current()`.
  키보드 색은 전부 이 객체에서 나온다. 리소스 ID 를 직접 꺼내 쓰지 말 것.
- **배경 사진**: 설정에서 고르면 `BackgroundImage.save()` 가 줄여서 `files/keyboard_background.jpg`
  에 둔다. 키보드는 `applyAppearance()` 에서 파일 수정 시각이 바뀐 경우에만 다시 푼다.
  사진 위에서는 키가 86% 불투명, 상단 문구엔 반투명 바탕.
- **실시간 교정 토글**: 자판 도구 줄 '교정' 버튼 ↔ 설정 스위치, 둘 다 `Prefs.autoCorrectEnabled`.
  버튼은 지금 입력란에 즉시 먹는다(`session.correctionEnabled`).
- **스페이스 꾹 → 커서 이동**: 스페이스는 다른 키와 달리 **뗄 때** 들어간다(`attachSpaceTouch`).
  380ms 누르면 커서 모드, 18dp 마다 한 글자, 서비스는 DPAD 키 이벤트로 옮긴다.
- **톤**: 키 아래 1dp 그림자(`keyFace`), 바탕 #E8EAEE, 키 48dp/틈 3dp/줄 간격 5dp, 도구 줄은 단색 기호.

---

## 천지인 · 토스 톤 설정 화면 · API 칸 제거 (2026-09-12)

- **천지인**: `core/hangul/CheonjiinAutomata` (JVM 테스트 18개). 모음은 ㅣㆍㅡ 획 열로 들고
  있다가 접는다(`VOWEL_TRANSITIONS`). 자음 연타(ㄱ→ㅋ→ㄲ)는 `press(key, repeat=true)` 로
  받고, "같은 키를 700ms 안에" 는 서비스가 잰다(`MULTI_TAP_MS`). `.,?!` 키도 같은 창으로
  돈다. 자판은 전화기 3×4 + 오른쪽 기능 열(⌫ ↵ 스페이스 한/영). 설정 → 키보드 → 자판.
  `TypingSession.automata` 로 조립기를 갈아 끼운다(`JamoAutomata` 인터페이스).
- **설정 화면**: 카드/행/알약/분할 선택은 `values/styles.xml`, 색은 `toss_*`(밤 값은
  `values-night`). 시작하기 카드는 시스템에 물어 "완료" 를 표시한다. 문제 해결 카드는 접혀 있다.
- **API 칸 제거**: 사용자 키·모델 칸과 그 코드 경로(`Prefs.userApiKey/model`)를 뺐다. AI 는
  언제나 중계 서버, 모델은 `DEFAULT_MODEL` 에서 시작해 교정기가 스스로 갈아탄다.
- **사진 위 키**: 30% 불투명.

---

## 천지인 버그·삼성 기호·클립보드 격자 (2026-09-12)

- **천지인 안 모아짐 고침**: 천지인 낱자 키는 `onChar` 가 아니라 전용 `onCheonjiinKey` 로
  보낸다. onChar 는 자판을 `session.automata` 동일성으로 되판별했는데, 그게 어긋나면
  점(ㆍ)이 두벌식 경로에서 그냥 글자로 박혀 모음이 안 생겼다. 뷰가 이미 천지인 키라고
  알고 있으니 다시 판별하지 않는다.
- **삼성 기호 자판**: `KeyboardLayout.SYMBOL_PAGES` 두 페이지(각 10·10·10 + 특수 7).
  `renderSymbols()` 가 [1/2·특수·⌫] 줄과 바닥 기능 줄을 그린다. `KeyAction.SYMBOL_PAGE`.
- **클립보드**: 2열 카드 격자(`clipboardCard`), 카드 눌러 붙여넣기, 오른쪽 위 ✕ 로 삭제.

---

## 삼성식 천지인 판·이모티콘·키 씹힘 (2026-09-12)

- **천지인 판**: 한글(숫자 힌트, 길게 누르면 숫자) / 숫자 판(`KeyboardMode.NUMPAD`) /
  기호 판(6열 3페이지 `CHEONJIIN_SYMBOL_PAGES`). `KeyAction.NUMPAD`·`KOREAN` 으로 오간다.
  길게 눌러 숫자를 넣을 때는 `CheonjiinAutomata.undoPress()` 로 직전 입력을 통째로 물린다
  (받침이 넘어간 뒤에도) — `TypingSession.undoLastJamo`.
- **이모티콘**: 도구 줄 ☺ → `EmojiSet` 갈래별 8열 격자. `session.pressString`.
- **키 씹힘 대책 셋**: (1) 스페이스를 잡고 있는 동안 다른 키가 눌리면 스페이스를 먼저 넣는다
  (`heldSpaceFlush`). (2) 스페이스 CANCEL 도 탭으로 친다. (3) `onUpdateSelection` 은 시간
  창 뒤에 **내용**(조합 글자가 커서 바로 앞인가)으로 우리 알림을 가린다.

---

## API 비용 (2026-09-12)

- 요청 하나의 비용 = 보내는 글자(지시문 ~300토큰 + 본문) + 돌아오는 글자(본문과 같은 길이) +
  **숙고 토큰**(켜져 있으면 출력 요금으로 청구). 숙고를 끄는 게 제일 크다.
- 숙고 끄기(`thinkingBudget:0`)를 거절당하면 **그 모델에만** 빼고 보낸다(`thinkingRejected`).
  예전엔 한 번 거절에 모든 모델에 영영 뺐고, 그때부터 답마다 숙고 토큰이 붙었다.
- 보내는 본문은 커서 앞 1,500 자 + 뒤 500 자(`AI_BEFORE_CHARS`/`AI_AFTER_CHARS`). 예전 2,000+2,000.
- 서버가 구글 응답의 `usageMetadata` 를 날짜별로 D1 `tokens` 에 쌓는다.
  `GET /stats` → `{days:[{day, requests, prompt, output, thoughts}]}` (최근 31일).
  `thoughts` 가 0 이 아니면 어딘가에서 숙고가 켜진 것이다.
- **한도 되돌리기**: Actions → "Reset AI usage" → Run workflow. 오늘(KST) `usage` 행을 지운다.
  테스트하다 무료 5회를 다 썼을 때. 서버·앱 코드와 무관.

---

## Play 구독 붙이기 — 남은 것은 콘솔뿐 (2026-09-12)

코드는 끝나 있다: 앱 `BillingManager`(상품 `ai_unlimited_monthly`), 서버 `verifySubscription`
(`PLAY_SERVICE_ACCOUNT` 비밀값이 있을 때만 동작, 없으면 전부 무료).

- CI 가 서명된 릴리스 번들 `spell-keyboard-release.aab` 를 만들어 릴리스에 같이 붙인다
  (`bundleRelease`, versionCode = Actions 실행 번호). Play 는 APK 를 안 받고 AAB 만 받는다.
- 개인정보 처리방침: `https://spell-keyboard.spell-keyboard.workers.dev/privacy` (Worker 가 낸다).
  wrangler `CONTACT_EMAIL` 변수를 두면 문의 줄이 붙는다.
- **Play 에서 설치한 앱은 Play 의 서명 키로 서명된다.** 사이드로드한 디버그 APK 와 서명이
  달라 덮어쓰기가 안 되므로, 내부 테스트로 깔 때는 기존 앱을 지워야 한다.

콘솔 순서: 개발자 등록 → 앱 만들기(`com.spellkeyboard.ko`) → 수익 창출 > 구독 > 상품
`ai_unlimited_monthly` + 월 2,990원 기본 요금제 → 내부 테스트에 AAB 올리고 테스터 등록 →
설정 > API 액세스 > 서비스 계정 만들기(JSON 키) → 그 계정에 "재무 데이터 보기" 권한 →
JSON 전체를 GitHub 비밀값 `PLAY_SERVICE_ACCOUNT` → "Deploy AI server" 수동 실행.

---

## 천지인 "다음" 키·키 씹힘·키 투명도 (2026-09-12)

> "다음" 키는 다음 절에서 없앴다. 스페이스가 그 일을 겸한다.

- **천지인 "다음" 키**: 바닥 줄 오른쪽(예전 쉼표 자리). 조합 중인 글자만 끝내고 아무것도
  넣지 않는다(`KeyAction.NEXT_CHAR`). "안녕" 처럼 받침 ㄴ 뒤에 초성 ㄴ 이 올 때 700ms 를
  기다리지 않아도 된다. 쉼표는 오른쪽 `.,?!` 키에서 나온다.
- **키 씹힘의 확인된 원인 하나**: 터치 처리가 `ACTION_POINTER_DOWN` 을 안 받고 있었다.
  손가락 하나가 키에 남아 있는 채로 두 번째 손가락이 같은 키에 닿으면 DOWN 이 아니라
  POINTER_DOWN 이 오는데, 그 입력이 조용히 사라졌다. POINTER_DOWN/UP 을 DOWN/UP 과 같이
  다룬다. 빠르게 칠수록 자주 났다.
- **키 투명도**: 설정 → 키보드 → 배경 사진 아래 슬라이더(0~100, 기본 70).
  `Prefs.keyTransparency` → `KeyboardView.alphaFor`. 배경 사진이 있을 때만 쓰인다.


---

## 키보드 전체가 키 · 스페이스가 "다음 글자" 겸함 (2026-09-12)

### 키 씹힘의 진짜 원인: 키 사이 여백이 아무 키도 아니었다

사용자가 삼성 키보드와 비교해 찾아냈다. **삼성은 자판 바닥 전체가 키다** — ㅅ과 ㅎ
사이의 빈틈을 누르면 가까운 쪽이 눌리고, 줄 맨 끝 키(ㅁ, ㅣ)는 화면 가장자리까지가
제 영역이다. 우리는 키 뷰마다 `OnTouchListener` 를 달아서 그 여백(키 사이 3dp, 판
둘레 2~6dp)이 어느 키도 아니었고, 거기 떨어진 손가락은 아무 일도 하지 않았다.
빠르게 칠수록 손가락이 키 한가운데에 정확히 떨어지지 않으니 자주 났다.

고친 방법 — 손가락 배분을 뷰에서 판으로 옮겼다(`KeyboardView.kt`):

- `KeyPad`(`rowContainer`)가 `dispatchTouchEvent` 를 가로채 자식에게 내려보내지 않는다.
- 키를 만들 때 뷰에 리스너를 달지 않고 `addPadKey` 로 `keySlots` 에 등록한다.
  자판을 다시 그릴 때(`render`) 목록을 비운다.
- `slotAt(x, y)` 가 점을 품은 키를 주고, 없으면 **가장 가까운** 키를 준다. 여백은
  자연히 양쪽 키의 한가운데에서 갈린다.
- `activeSlots`(손가락 id → 키)로 여러 손가락을 따로 추적한다. `ACTION_DOWN` 이면
  남아 있는 항목은 놓친 것이므로 전부 취소한다(⌫ 반복이 멎지 않던 경우 대비).
- 판 둘레 여백을 바깥(`KeyboardView` 의 padding)에서 **판 안쪽** padding 으로 옮겼다.
  그래야 화면 맨 아래·좌우 가장자리도 판이 받는다. 도구 줄과 두 패널은 같은 크기의
  padding 을 스스로 갖게 해서 생김새는 그대로다.
- 터치 처리 코드는 `KeyTouch`(down/move/up/cancel) 로 묶었다. 자판 밖의 키(도구 줄,
  이모티콘 칸, 클립보드 칸)는 그대로 뷰마다 리스너를 단다 — 드문드문 있어서 "가까운
  것을 누른 것으로 친다" 가 오히려 해롭다.

### 천지인 "다음" 키를 없애고 스페이스에 합쳤다

원래 천지인이 그렇다: 조합 중에 스페이스를 누르면 글자가 끊기고, 한 번 더 누르면
그때 띄어쓰기다. `KeyAction.NEXT_CHAR` 와 "다음" 키는 지웠고(쉼표가 제자리로 돌아옴),
`SpellKeyboardService.onAction` 의 `KeyAction.SPACE` 가 천지인이면서 조합 중일 때만
`commitPending` 을 한다. `onAction` 첫머리에서 `lastTapKey` 를 비우므로, 끊은 뒤
같은 키를 바로 눌러도 연타(ㄴ→ㄹ)가 아니라 새 글자가 된다.

---

## AI 모델을 gpt-5-nano 로 (2026-09-12)

### 왜

gemini-3.5-flash-lite 는 출력 100만 토큰에 **$2.50**, gpt-5-nano 는 **$0.40** 이다.
맞춤법 교정은 원문을 통째로 다시 뱉는 일이라 출력이 요금의 8할이고, 그래서 바꾸는
것만으로 요금이 **6분의 1** 이 된다. 교정은 판단이 아니라 패턴이라 nano 로 충분하다.

조사한 것들(2026-09 기준, 입력/출력 100만 토큰당): Gemini 3.5 Flash-Lite $0.30/$2.50,
Gemini 3.1 Flash-Lite $0.25/$1.50, **GPT-5 nano $0.05/$0.40**, Qwen3.7 Flash $0.03/$0.13,
DeepSeek V4 Flash $0.12/$0.35, 하이퍼클로바X DASH ₩0.001/토큰. Qwen 이 제일 싸지만
키보드에 치는 모든 글이 중국 업체로 가는 것이 걸려서 뺐다. GPT-5.4 nano 는 $0.20/$1.25
로 오히려 비싸다.

한 달 요금(2,000자 × 하루 150회 기준): 41,784원 → **6,721원**.

### 어떻게 — 앱은 그대로 두고 서버에서 번역한다

이미 깔린 APK 들은 구글 모양으로 보내고 구글 모양 응답을 읽는다. 그래서 번역을
서버에 뒀다. **앱을 다시 깔지 않아도 적용된다.**

- `server/src/openai.js` (새 파일): `toOpenAiRequest` / `toGeminiReply` / `modelList`.
- `server/src/index.js`: `OPENAI_API_KEY` 비밀값이 있으면 OpenAI, 없으면 예전처럼 구글.
  **되돌리기는 비밀값 하나 지우는 것**이다.
- 앱이 보낸 모델 이름은 쓰지 않는다. 무엇으로 고칠지는 서버가 정한다
  (`OPENAI_MODEL` 환경변수로 바꿀 수 있고, 기본은 `gpt-5-nano`).
- `/v1beta/models` 는 물어보지 않고 쓰는 이름 하나만 돌려준다.

주의해서 다룬 것들:

- **잘린 답은 주지 않는다.** 앱은 받은 글로 입력란을 통째로 덮으므로, `finish_reason`
  이 `length` 면 502 로 실패시킨다. 안 그러면 사용자 글의 뒷부분이 사라진다.
- **출력 한도를 넉넉히 준다.** OpenAI 는 숙고 토큰도 `max_completion_tokens` 에서
  깎는다. 앱이 부른 4096 을 그대로 주면 긴 글에서 잘린다. 2배로 주되 2048~16384 로 자른다.
- **temperature 를 안 보낸다.** gpt-5 계열은 이 항목을 받으면 400 이다.
- **`reasoning_effort: 'minimal'`**. 거절당하면 빼고 한 번 더 보낸다(앱이 구글의
  thinkingBudget 에 하던 것과 같은 수법).
- **토큰 집계**: OpenAI 의 `completion_tokens` 에는 숙고가 들어 있고 구글의
  `candidatesTokenCount` 에는 없다. 빼서 넣어야 `/stats` 가 두 번 세지 않는다.
- 중계 Durable Object(`enam` 고정)는 그대로 쓴다. OpenAI 도 홍콩을 지원 지역에서 뺐다.
- 개인정보 처리방침 페이지와 앱의 고지 문구를 "OpenAI" 로 바꿨다. 방침 페이지는
  실제 설정을 따라가므로 구글로 되돌리면 문구도 같이 돌아간다.

### 남은 일

사용자가 OpenAI 키를 만들어 GitHub 비밀값 `OPENAI_API_KEY` 에 넣고 "Deploy AI server"
를 돌려야 한다. 그 전까지는 서버가 예전처럼 구글로 간다.

다음 단계(더 큰 절감): 지금은 고친 문장 **전체**를 다시 뱉게 한다. 바뀐 부분만
내보내게 하면 출력이 1/5~1/10 로 준다. 모델 교체와 곱해져 지금의 30~50분의 1이 된다.

### 품질 보정 1차 (2026-09-12)

실기기에서 "예전 Gemini 는 100%, gpt-5-nano 는 95% 쯤" 이라는 평가가 나왔다. 원인 셋:

1. **온도를 못 낮춘다.** Gemini 에는 `temperature: 0` 을 줬는데 gpt-5 계열은 이 항목을
   받으면 400 이다(기본값 1 고정). 답이 매번 조금씩 흔들린다. 직접 고칠 수단이 없어
   지시문으로 누르는 수밖에 없다.
2. **지시문이 짧았다.** 큰 모델은 규칙 한 줄로 알아듣지만 작은 모델은 예시를 봐야 안다.
3. **숙고를 완전히 껐다.** `minimal` → `low`.

고친 것:

- **지시문을 서버로 옮겼다**(`KO_SYSTEM_PROMPT`, 1,278자 ≈ 1,534토큰). 앱이 보낸 지시문은
  쓰지 않는다. 앞으로 프롬프트를 손볼 때 APK 를 새로 깔 필요가 없다 — 배포 한 번이다.
  되돌리려면 `OPENAI_PROMPT=app`.
- 지시문을 길게 쓴 것은 낭비가 아니다. 입력은 출력의 8분의 1 값이고, 1024 토큰이 넘는
  같은 앞부분은 OpenAI 가 **90% 깎아 준다**(캐싱, $0.005/1M). 그래서 문턱을 넘겨 두었다 —
  테스트가 이 길이를 지킨다.
- 지시문에 한국어 오류 유형별 예시 12개. "왜케" 같은 줄임말을 고치지 말라는 것을
  **예시를 한 번 틀리게 보여 준 뒤 바로잡는** 식으로 넣었다. 작은 모델에 잘 먹는다.
- `reasoning_effort` 기본 `low`, `OPENAI_REASONING` 으로 바꿀 수 있다.

값은 거의 그대로다(최악 월 6,721원 → 7,215원). 이래도 모자라면 `OPENAI_MODEL=gpt-5-mini`
($0.13/$1.00)로 한 단계 올린다 — 그래도 예전 Gemini 보다 2.5배 싸다.
