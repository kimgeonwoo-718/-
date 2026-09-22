-- 계정과 기기. 안드로이드·아이폰·윈도우가 **구독 하나를 같이 쓰게** 하려는 것이다.
--
-- 구독 상태의 진실은 계속 Play 가 갖는다. 계정은 "누가 어떤 구매를 샀나" 만 기억하고,
-- 물어볼 때마다 그 구매를 Play 에 확인한다. 그래서 환불·해지·만료를 여기서 관리하지 않는다.
-- 자세한 것은 docs/ACCOUNTS.md.

-- 계정 하나가 구매 하나를 가진다.
--
-- **구매 토큰을 여기 저장한다.** 예전에는 서버가 받아서 쓰고 버렸다. 토큰은 그 자체가
-- 구독 증명이라 새면 남이 쓰므로, 저장은 하되 **밖으로 다시 내보내지 않는다** — 확인
-- 결과(구독 중이냐)만 돌려준다.
CREATE TABLE IF NOT EXISTS accounts (
  id             TEXT    PRIMARY KEY,
  purchase_token TEXT,
  store          TEXT    NOT NULL DEFAULT 'play',
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);

-- 같은 구매가 계정 둘에 붙는 일이 없어야 한다. 붙이기는 "이 구매의 계정" 을 찾는 것부터 한다.
CREATE UNIQUE INDEX IF NOT EXISTS accounts_purchase ON accounts (purchase_token);

-- 계정에 붙은 기기. 윈도우·아이폰이 들고 다니는 것이 이 토큰이다.
--
-- **토큰은 해시로만 저장한다.** 비밀번호와 같은 이유다 — 데이터베이스가 새도 그것만으로는
-- 남의 기기 행세를 할 수 없어야 한다. 발급할 때 한 번 내주고 우리는 해시만 갖는다.
CREATE TABLE IF NOT EXISTS devices (
  token_hash TEXT    PRIMARY KEY,
  account_id TEXT    NOT NULL,
  label      TEXT,
  created_at INTEGER NOT NULL,
  seen_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS devices_account ON devices (account_id);
