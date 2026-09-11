-- 설치 ID(또는 IP 버킷)별 하루 사용량. day 는 한국 시간 기준 YYYY-MM-DD.
CREATE TABLE IF NOT EXISTS usage (
  id   TEXT    NOT NULL,
  day  TEXT    NOT NULL,
  used INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (id, day)
);
CREATE INDEX IF NOT EXISTS usage_day ON usage (day);

-- Play 결제 확인 결과와 구글 액세스 토큰. 값은 JSON, 만료는 epoch ms.
CREATE TABLE IF NOT EXISTS cache (
  key        TEXT    PRIMARY KEY,
  value      TEXT    NOT NULL,
  expires_at INTEGER NOT NULL
);
