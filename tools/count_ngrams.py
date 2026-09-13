#!/usr/bin/env python3
"""말뭉치에서 어절 단위 유니그램·바이그램을 센다. (build_lm.py 의 1단계)

입력 디렉터리에 든 파일을 이름으로 알아본다.
  pet_*        청와대 국민청원 (lovit/petitions_archive, JSON lines, "content")
  snli.tsv, mnli.tsv   KorNLI (kakaobrain, 탭 구분, sentence1/sentence2)
  park.tar.gz  korean-english-park (jungyeul, 한국어 줄)
  chatbot.csv  songys/Chatbot_data (Q,A,label)
  ko_full.txt  OpenSubtitles 어절 빈도 (hermitdave/FrequencyWords) — 유니그램만

어절은 **한글 음절로만 된 것**만 센다. 숫자·영문·기호가 섞인 것은 세지 않고
그 자리에서 연쇄를 끊는다. 문장 부호(. ! ? …)도 연쇄를 끊고, 문장 첫 어절은
"<s>" 뒤에 오는 것으로 센다.

출력: <out>/uni.tsv (어절\t빈도), <out>/bi.tsv (어절 어절\t빈도, 빈도 2 이상)
두 번 읽는다 — 첫 번에 유니그램, 둘째 번에 유니그램 2 이상인 어절끼리의 바이그램.
"""
import collections
import csv
import gzip
import io
import json
import os
import re
import sys
import tarfile

HANGUL = re.compile(r'^[가-힣]+$')
STRIP = '.,!?~…"\'()[]「」『』<>:;·/\\-–—*^`“”‘’|＂＇【】〈〉《》#%&+=_{}'
ENDERS = set('.!?…')
MAX_SYL = 12
BOS = '<s>'


def sentences_of(text):
    """줄과 문장 부호로 끊은 어절 목록들."""
    for line in text.split('\n'):
        chain = []
        for raw in line.split():
            ends = raw[-1] in ENDERS
            tok = raw.strip(STRIP)
            if tok and HANGUL.match(tok) and len(tok) <= MAX_SYL:
                chain.append(tok)
            else:
                if chain:
                    yield chain
                chain = []
                continue
            if ends:
                yield chain
                chain = []
        if chain:
            yield chain


def texts(indir):
    for name in sorted(os.listdir(indir)):
        path = os.path.join(indir, name)
        if name.startswith('pet_'):
            with open(path, encoding='utf-8') as f:
                for line in f:
                    try:
                        doc = json.loads(line)
                    except ValueError:
                        continue
                    yield 'pet', doc.get('title', '') + '\n' + doc.get('content', '')
        elif name in ('snli.tsv', 'mnli.tsv'):
            last = None
            with open(path, encoding='utf-8') as f:
                next(f)
                for line in f:
                    parts = line.rstrip('\n').split('\t')
                    if len(parts) < 2:
                        continue
                    if parts[0] != last:
                        yield 'nli', parts[0]
                        last = parts[0]
                    yield 'nli', parts[1]
        elif name == 'park.tar.gz':
            with tarfile.open(path) as tar:
                for member in tar.getmembers():
                    if member.name.endswith('.ko'):
                        for line in io.TextIOWrapper(tar.extractfile(member), encoding='utf-8'):
                            yield 'park', line
        elif name == 'chatbot.csv':
            with open(path, encoding='utf-8') as f:
                reader = csv.reader(f)
                next(reader)
                for row in reader:
                    if len(row) >= 2:
                        yield 'chat', row[0] + '\n' + row[1]


def main():
    indir, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    uni = collections.Counter()
    per_source = collections.Counter()
    for source, text in texts(indir):
        for chain in sentences_of(text):
            uni.update(chain)
            per_source[source] += len(chain)
    # 자막 빈도표는 유니그램에만 보탠다.
    subs = os.path.join(indir, 'ko_full.txt')
    if os.path.exists(subs):
        with open(subs, encoding='utf-8') as f:
            for line in f:
                parts = line.split()
                if len(parts) == 2 and HANGUL.match(parts[0]) and len(parts[0]) <= MAX_SYL:
                    uni[parts[0]] += int(parts[1])
                    per_source['subs'] += int(parts[1])
    print('유니그램 종류', len(uni), '토큰', sum(uni.values()), dict(per_source), flush=True)

    keep = {w for w, c in uni.items() if c >= 2}
    bi = collections.Counter()
    for source, text in texts(indir):
        for chain in sentences_of(text):
            prev = BOS
            for w in chain:
                if w in keep:
                    bi[(prev, w)] += 1
                    prev = w
                else:
                    prev = None
                    # 모르는 어절 뒤는 연쇄가 끊긴다.
            # 문장 끝은 세지 않는다.
    print('바이그램 종류', len(bi), flush=True)

    with open(os.path.join(outdir, 'uni.tsv'), 'w', encoding='utf-8') as f:
        for w, c in uni.most_common():
            f.write(f'{w}\t{c}\n')
    with open(os.path.join(outdir, 'bi.tsv'), 'w', encoding='utf-8') as f:
        for (a, b), c in bi.most_common():
            if c < 2:
                break
            if a is None:
                continue
            f.write(f'{a} {b}\t{c}\n')
    print('완료', flush=True)


if __name__ == '__main__':
    main()
