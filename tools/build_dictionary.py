#!/usr/bin/env python3
"""mecab-ko-dic 에서 온디바이스 띄어쓰기 분석기용 데이터를 만든다.

입력은 PyPI 의 python-mecab-ko-dic 휠(Apache License 2.0). 여기서
 - 형태소 사전 (표층형 -> 좌/우 문맥 id, 비용, 품사)
 - 연결비용 행렬
을 뽑아 앱이 mmap 으로 읽을 수 있는 납작한 바이너리로 굽는다.

사용:
    python3 tools/build_dictionary.py <휠 경로> <출력 디렉터리>
"""
import gzip
import struct
import sys
import zipfile
from pathlib import Path

CHO = [chr(0x1100 + i) for i in range(19)]
JUNG = [chr(0x1161 + i) for i in range(21)]
JONG = [''] + [chr(0x11A8 + i) for i in range(27)]

MAX_SURFACE = 12
MAGIC_LEX = b'KSPL'
MAGIC_MTX = b'KSPM'
VERSION = 1


def to_jamo(text):
    """음절을 첫가끝 자모로 편다. 어미 'ᆫ다' 처럼 종성으로 시작하는 형태소와 맞물리게 하려는 것."""
    out = []
    for ch in text:
        code = ord(ch)
        if 0xAC00 <= code <= 0xD7A3:
            k = code - 0xAC00
            out.append(CHO[k // 588])
            out.append(JUNG[(k % 588) // 28])
            if k % 28:
                out.append(JONG[k % 28])
        else:
            out.append(ch)
    return ''.join(out)


def read_dictionary(whl):
    z = zipfile.ZipFile(whl)
    sysdic = z.read('mecab_ko_dic/dictionary/sys.dic')
    lexsize = struct.unpack('<I', sysdic[12:16])[0]
    dsize = struct.unpack('<I', sysdic[24:28])[0]
    tsize = struct.unpack('<I', sysdic[28:32])[0]
    fsize = struct.unpack('<I', sysdic[32:36])[0]
    toff = 72 + dsize
    features = sysdic[toff + tsize: toff + tsize + fsize]

    entries = {}
    for i in range(lexsize):
        lc, rc, _posid, wcost, foff, _comp = struct.unpack(
            '<HHHhII', sysdic[toff + i * 16: toff + i * 16 + 16]
        )
        raw = features[foff:features.index(b'\0', foff)].decode('utf-8', 'replace')
        fields = raw.split(',')
        surface = fields[3] if len(fields) > 3 else '*'
        if not surface or surface == '*' or len(surface) > MAX_SURFACE:
            continue
        key = to_jamo(surface)
        slot = entries.setdefault(key, {})
        # 같은 문맥 id 조합은 가장 싼 것만 남긴다.
        prev = slot.get((lc, rc))
        if prev is None or wcost < prev[0]:
            slot[(lc, rc)] = (wcost, fields[0])

    matrix = z.read('mecab_ko_dic/dictionary/matrix.bin')
    id_defs = {}
    for name in ('left-id.def', 'right-id.def'):
        table = {}
        for line in z.read(f'mecab_ko_dic/dictionary/{name}').decode().splitlines():
            if not line.strip():
                continue
            num, feature = line.split(' ', 1)
            table[int(num)] = feature.split(',')[0]
        id_defs[name] = table
    return entries, matrix, id_defs


def write_lexicon(entries, id_defs, out):
    tags = sorted(
        {p.split('+')[0] for slot in entries.values() for _, p in slot.values()}
        | {p.split('+')[-1] for slot in entries.values() for _, p in slot.values()}
        | set(id_defs['left-id.def'].values())
        | set(id_defs['right-id.def'].values())
        | {'BOS'}
    )
    tag_id = {t: i for i, t in enumerate(tags)}

    keys = sorted(entries)
    key_blob = bytearray()
    key_index = []
    morphs = bytearray()
    morph_index = []
    for key in keys:
        key_index.append(len(key_blob))
        key_blob += key.encode('utf-8')
        morph_index.append(len(morphs) // 8)
        for (lc, rc), (wcost, pos) in sorted(entries[key].items()):
            morphs += struct.pack(
                '<HHhBB', lc, rc, wcost,
                tag_id[pos.split('+')[0]], tag_id[pos.split('+')[-1]]
            )
    key_index.append(len(key_blob))
    morph_index.append(len(morphs) // 8)

    tag_blob = '\n'.join(tags).encode('utf-8')
    body = bytearray()
    body += struct.pack('<I', len(keys))
    body += struct.pack('<I', len(morphs) // 8)
    body += struct.pack('<I', len(tag_blob))
    body += tag_blob
    body += struct.pack(f'<{len(key_index)}I', *key_index)
    body += struct.pack(f'<{len(morph_index)}I', *morph_index)
    body += struct.pack('<I', len(key_blob))
    body += key_blob
    body += morphs

    blob = MAGIC_LEX + struct.pack('<I', VERSION) + body
    (out / 'lexicon.bin.gz').write_bytes(gzip.compress(bytes(blob), 9))
    return len(blob), len(keys), len(morphs) // 8, tags


def write_matrix(matrix, id_defs, tags, out):
    tag_id = {t: i for i, t in enumerate(tags)}
    lsize, rsize = struct.unpack('<HH', matrix[:4])
    right_tags = bytes(
        tag_id[id_defs['right-id.def'].get(i, '*')] for i in range(lsize)
    )
    left_tags = bytes(
        tag_id[id_defs['left-id.def'].get(i, '*')] for i in range(rsize)
    )
    blob = (MAGIC_MTX + struct.pack('<IHH', VERSION, lsize, rsize)
            + struct.pack('<I', len(right_tags)) + right_tags
            + struct.pack('<I', len(left_tags)) + left_tags
            + matrix[4:4 + lsize * rsize * 2])
    (out / 'matrix.bin.gz').write_bytes(gzip.compress(bytes(blob), 9))
    return len(blob)


def main():
    whl, outdir = Path(sys.argv[1]), Path(sys.argv[2])
    outdir.mkdir(parents=True, exist_ok=True)
    entries, matrix, id_defs = read_dictionary(whl)
    raw, nkeys, nmorphs, tags = write_lexicon(entries, id_defs, outdir)
    mraw = write_matrix(matrix, id_defs, tags, outdir)
    for name in ('lexicon.bin.gz', 'matrix.bin.gz'):
        print(f"{name}: {(outdir / name).stat().st_size:,} bytes (압축 전 "
              f"{raw if 'lex' in name else mraw:,})")
    print(f"표층형 {nkeys:,}개 / 형태소 {nmorphs:,}개 / 품사 태그 {len(tags)}개")


if __name__ == '__main__':
    main()
