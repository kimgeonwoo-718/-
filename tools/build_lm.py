#!/usr/bin/env python3
"""count_ngrams.py 가 센 빈도표를 앱이 mmap 으로 읽는 lm.bin.gz 로 굽는다. (2단계)

형식은 core/src/main/kotlin/com/spellkeyboard/core/lm/LanguageModel.kt 에 적혀 있다.
여기와 거기가 바이트 단위로 같아야 한다 — 해시(FNV-1a 64), 버킷(위 16비트),
양자화(round(8·ln count)), 정렬(버킷 안에서 아래 32비트 부호 없는 오름차순).

사용:
    python3 tools/build_lm.py <uni.tsv> <bi.tsv> <출력 디렉터리> [--uni-min N] [--bi-min N]
                              [--uni-max N] [--bi-max N]
"""
import argparse
import gzip
import math
import struct
from pathlib import Path

MAGIC = b'KSLM'
VERSION = 1
BUCKETS = 1 << 16
MASK64 = (1 << 64) - 1


def fnv1a64(text):
    h = 0xcbf29ce484222325
    for b in text.encode('utf-8'):
        h ^= b
        h = (h * 0x100000001b3) & MASK64
    return h


def quantize(count):
    return max(0, min(255, round(8 * math.log(count))))


def read_counts(path, minimum, maximum):
    rows = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            key, count = line.rstrip('\n').rsplit('\t', 1)
            count = int(count)
            if count < minimum:
                break          # 빈도 내림차순이라 여기서 끝
            rows.append((key, count))
            if maximum and len(rows) >= maximum:
                break
    return rows


def pack_table(rows):
    """(버킷 오프셋 배열, 항목 바이트열). 항목은 버킷별로 모아 아래 32비트 순으로 정렬."""
    buckets = [[] for _ in range(BUCKETS)]
    for key, count in rows:
        h = fnv1a64(key)
        buckets[h >> 48].append((h & 0xFFFFFFFF, quantize(count)))
    offsets = [0]
    body = bytearray()
    for bucket in buckets:
        bucket.sort()
        for low, q in bucket:
            body += struct.pack('<IB', low, q)
        offsets.append(offsets[-1] + len(bucket))
    return offsets, body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('uni')
    ap.add_argument('bi')
    ap.add_argument('out')
    ap.add_argument('--uni-min', type=int, default=3)
    ap.add_argument('--bi-min', type=int, default=3)
    ap.add_argument('--uni-max', type=int, default=0)
    ap.add_argument('--bi-max', type=int, default=0)
    args = ap.parse_args()

    # 전체 토큰 수는 자르기 전의 표에서 센다 — 확률의 분모다.
    total = 0
    with open(args.uni, encoding='utf-8') as f:
        for line in f:
            total += int(line.rstrip('\n').rsplit('\t', 1)[1])

    uni = read_counts(args.uni, args.uni_min, args.uni_max)
    bi = read_counts(args.bi, args.bi_min, args.bi_max)
    # 문장 첫머리 "<s>" 는 유니그램 표에 없다. 연쇄 "<s> 어절" 의 합이 곧 문장 수이므로
    # 그걸 유니그램으로 넣어야 P(어절 | <s>) 를 셀 수 있다. 자른 표가 아니라 전체 표에서 센다.
    sentences = 0
    with open(args.bi, encoding='utf-8') as f:
        for line in f:
            if line.startswith('<s> '):
                sentences += int(line.rstrip('\n').rsplit('\t', 1)[1])
    if sentences:
        uni.append(('<s>', sentences))
    uni_off, uni_body = pack_table(uni)
    bi_off, bi_body = pack_table(bi)

    blob = bytearray()
    blob += MAGIC + struct.pack('<I', VERSION)
    blob += struct.pack('<IIf', len(uni), len(bi), math.log(total))
    blob += struct.pack(f'<{BUCKETS + 1}I', *uni_off)
    blob += struct.pack(f'<{BUCKETS + 1}I', *bi_off)
    blob += uni_body
    blob += bi_body

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'lm.bin.gz').write_bytes(gzip.compress(bytes(blob), 9))
    print(f"어절 {len(uni):,}개 (빈도≥{args.uni_min}) / 연쇄 {len(bi):,}개 (빈도≥{args.bi_min}) / "
          f"토큰 {total:,}")
    print(f"lm.bin {len(blob):,} bytes → lm.bin.gz {(out / 'lm.bin.gz').stat().st_size:,} bytes")


if __name__ == '__main__':
    main()
