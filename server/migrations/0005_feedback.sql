-- 앱의 고객센터에서 보낸 의견(글·사진). 운영자가 /feedback 에서 읽고 지운다(src/feedback.js).
-- 크론이 받은 지 180일 지난 것을 지운다 — 개인정보 처리방침 3번에 그렇게 적었다.

CREATE TABLE IF NOT EXISTS feedback (
  id          TEXT    PRIMARY KEY,   -- 무작위 16진수
  created_at  INTEGER NOT NULL,      -- epoch ms
  install_id  TEXT    NOT NULL,
  body        TEXT    NOT NULL,
  reply_to    TEXT,                  -- 답장 받을 곳(적은 경우만)
  app_version TEXT,
  device      TEXT,
  os          TEXT,
  photos      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS feedback_created ON feedback (created_at);

-- 사진은 **base64 글자로** 둔다. D1 은 BLOB 을 바이트 하나가 숫자 하나인 배열로 주고받아서, 사진 한 장이
-- 수십만 개의 숫자가 되어 무료 등급의 CPU 한도(요청당 10ms)를 넘긴다. 글자는 그대로 지나간다.
-- size 는 data 의 글자 수다. 합계를 볼 때 data 를 읽지 않으려고 따로 둔다.
CREATE TABLE IF NOT EXISTS feedback_photos (
  feedback_id TEXT    NOT NULL,
  idx         INTEGER NOT NULL,
  type        TEXT    NOT NULL,      -- image/jpeg | image/png
  size        INTEGER NOT NULL,
  data        TEXT    NOT NULL,
  PRIMARY KEY (feedback_id, idx)
);
