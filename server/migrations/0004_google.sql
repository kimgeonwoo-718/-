-- 구글 로그인으로 계정을 찾는 길. docs/ACCOUNTS.md 참고.
--
-- **`sub` 를 그대로 두지 않고 해시해서 넣는다.** `sub` 는 구글 계정마다 붙는 바뀌지 않는
-- 번호라 그 자체가 사람을 가리킨다. 우리는 "같은 사람인가" 만 알면 되므로 해시로 충분하고,
-- 데이터베이스가 새도 누구인지는 안 드러난다. 이메일·이름은 아예 저장하지 않는다.
ALTER TABLE accounts ADD COLUMN google_sub_hash TEXT;

-- 구글 계정 하나에 우리 계정 하나.
CREATE UNIQUE INDEX IF NOT EXISTS accounts_google ON accounts (google_sub_hash);
