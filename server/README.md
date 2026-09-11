# AI 중계 서버

맞춤법 키보드의 AI 교정 요청을 받아 Gemini 로 넘기는 Cloudflare Worker.

```
앱 ──(X-Install-Id, X-Purchase-Token)──▶ Worker ──(x-goog-api-key)──▶ Gemini
                                          │
                                          ├─ Play Developer API 로 구독 확인 (1시간 캐시)
                                          └─ D1: 설치 ID·IP 별 하루 사용량
```

- 앱에는 Gemini 키가 없다. 여기만 갖고 있다.
- 경로는 구글과 같다 (`/v1beta/models`, `/v1beta/models/{m}:generateContent`). 앱은 호스트만 바꾼다.
- 무료: 설치 ID 당 하루 5 회, 성공한 것만 센다. 초과는 **402** (앱이 429 를 "붐빔" 으로 보고
  재시도하기 때문에 429 를 쓰지 않는다).
- 구독자: `X-Purchase-Token` 을 Play 에 물어 살아 있으면 무제한.

## 로컬

```
cd server
npm test            # 의존성 없음, node --test
```

## 배포

`.github/workflows/deploy-worker.yml` 이 `server/**` 가 바뀔 때 한다. 필요한 GitHub 비밀값:

| 이름 | 어디서 |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare → My Profile → API Tokens → "Edit Cloudflare Workers" 템플릿 |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 대시보드 Workers 페이지 오른쪽 |
| `GEMINI_API_KEY` | (이미 있음) |
| `PLAY_SERVICE_ACCOUNT` | Play Console 에 연결한 서비스 계정 JSON 통째로. 없으면 전부 무료로 동작 |

D1 데이터베이스는 CI 가 없으면 만들고 `wrangler.toml` 의 자리표시자에 ID 를 채운다.
