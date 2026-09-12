-- 날짜별 토큰 사용량. 요금이 얼마나 나가는지 구글 콘솔 없이 보려고. day 는 한국 시간.
CREATE TABLE IF NOT EXISTS tokens (
  day      TEXT    PRIMARY KEY,
  requests INTEGER NOT NULL DEFAULT 0,
  prompt   INTEGER NOT NULL DEFAULT 0,
  output   INTEGER NOT NULL DEFAULT 0,
  thoughts INTEGER NOT NULL DEFAULT 0
);
