#!/usr/bin/env python3
"""큰 시험지를 만든다 — `tools/confusable/fetch_corpus.sh` 로 받아 둔 말뭉치에서 문장을 고른다.

**말뭉치는 저장소에 넣지 않는다.** 크고, 출처마다 이용 조건이 다르다. 같은 씨앗으로 고르니 누가 돌려도 같은 시험지가 나온다.

    tools/confusable/fetch_corpus.sh /tmp/corpus
    tools/localcheck/build-sheet.py /tmp/corpus /tmp/sheet
    tools/localcheck/mine.sh /tmp/sheet/*.txt

만드는 것 (파일 이름 앞이 clean 이면 맞춤법이 깔끔한 글, typed 면 사람이 실제로 친 글이다):

    clean-chat.txt     챗봇의 답 — 사람이 공들여 쓴 채팅 말투. 여기서 글자가 바뀌면 거의 다 오교정이다.
    clean-formal.txt   번역문(KorNLI) — 격식체, 가장 깨끗하다.
    typed-chat.txt     챗봇에 사람이 친 말 — 오타·붙여 쓰기가 섞인 진짜 채팅.
    typed-ask.txt      비서에게 하는 말(3i4k).
    typed-review.txt   영화평(NSMC).
    typed-comment.txt  댓글(korean-hate-speech).

typed 쪽에서 바뀐 것은 진짜 오타를 고친 것도 많다. 그래서 mine.sh 는 고친 것을 **종류별로 묶어 많은 순으로** 보여 준다 —
사람이 그 목록을 훑어 오교정 종류를 찾는다.
"""
import csv
import random
import re
import sys
from pathlib import Path

SEED = 20261011
HANGUL = re.compile(r"[가-힣]")


def clean(line: str) -> str | None:
    text = re.sub(r"\s+", " ", line).strip()
    if not text or "http" in text or "www." in text:
        return None
    if len(HANGUL.findall(text)) < 3 or len(text) > 100:
        return None
    # 깨진 글자(옮겨 담다 잘린 것)는 뺀다.
    if "�" in text:
        return None
    return text


def unique(lines):
    seen = set()
    out = []
    for line in lines:
        text = clean(line)
        if text and text not in seen:
            seen.add(text)
            out.append(text)
    return out


def sample(lines, n, rng):
    lines = list(lines)
    rng.shuffle(lines)
    return sorted(lines[:n]) if n < len(lines) else sorted(lines)


def chatbot(corpus: Path):
    questions, answers = [], []
    with open(corpus / "raw_chatbot.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            questions.append(row["Q"])
            answers.append(row["A"])
    return questions, answers


def column(path: Path, index: int, sep="\t", skip_header=False):
    out = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for k, line in enumerate(f):
            if skip_header and k == 0:
                continue
            parts = line.rstrip("\n").split(sep)
            if len(parts) > index:
                out.append(parts[index])
    return out


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    corpus, dest = Path(sys.argv[1]), Path(sys.argv[2])
    dest.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)

    questions, answers = chatbot(corpus)
    formal = column(corpus / "raw_KorNLI_snli.tsv", 1, skip_header=True)
    sheets = {
        "clean-chat.txt": sample(unique(answers), 6000, rng),
        "clean-formal.txt": sample(unique(formal), 2000, rng),
        "typed-chat.txt": sample(unique(questions), 6000, rng),
        "typed-ask.txt": sample(unique(column(corpus / "raw_3i4k.txt", 1)), 3000, rng),
        "typed-review.txt": sample(unique(column(corpus / "raw_nsmc_test.txt", 1, skip_header=True)), 3000, rng),
        "typed-comment.txt": sample(unique(column(corpus / "raw_hate.tsv", 0, skip_header=True)), 2000, rng),
    }
    for name, lines in sheets.items():
        (dest / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{name:20s} {len(lines):5d}문장")


if __name__ == "__main__":
    main()
