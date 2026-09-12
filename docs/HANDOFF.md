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

