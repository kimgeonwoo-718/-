#!/usr/bin/env python3
"""고침 하나하나를 맞다/틀리다로 센다 — live.sh 가 남긴 덤프(LIVE_DUMP)를 읽는다.

    LIVE_DUMP=/tmp/d.tsv tools/localcheck/live.sh 시험지.tsv
    tools/localcheck/score-edits.py /tmp/d.tsv            틀린 고침을 묶어 많은 순으로
    SCORE_SHOW=0 tools/localcheck/score-edits.py …        숫자만
    SCORE_MISSED=1 tools/localcheck/score-edits.py …      놓친 것도 찍는다

live.sh 의 '고침'·'지킴' 은 **줄 단위**라 긴 줄은 하나만 틀려도 0 이다. 이건 **고친 자리 단위**로 본다.
엔진이 바꾼 자리와 정답이 바꾼 자리를 어절 경계까지 넓혀 겹치는 것끼리 묶고, 묶음마다
  맞음 — 엔진이 바꿨고 결과가 정답과 같다
  틀림 — 엔진이 바꿨는데 정답과 다르다(정답은 그대로 두라는데 바꾼 것 포함). **사용자가 화나는 것이 이것이다**
  놓침 — 정답은 바꾸는데 엔진이 그대로 뒀다
정답이 여럿(` | `)이면 엔진 결과에 가장 잘 맞는 것으로 잰다.
덤프 넷째 칸(정답을 그대로 쳤을 때 결과)에서 바뀐 자리는 전부 틀림이다 — '지킴 틀림' 으로 따로 센다.
"""
import collections
import difflib
import os
import sys


def regions(src, dst):
    """src → dst 에서 바뀐 자리를 src 의 어절 경계까지 넓힌 [(a, b)] 와 맞춤표."""
    ops = difflib.SequenceMatcher(None, src, dst, autojunk=False).get_opcodes()
    spans = []
    for tag, i1, i2, _, _ in ops:
        if tag == 'equal':
            continue
        a = src.rfind(' ', 0, i1) + 1
        b = src.find(' ', i2)
        spans.append((a, len(src) if b < 0 else b))
    return spans, ops


def mapper(ops):
    def at(pos):
        for tag, i1, i2, j1, j2 in ops:
            if i1 <= pos <= i2:
                if tag == 'equal':
                    return j1 + (pos - i1)
                return j1 if pos == i1 else j2
        return ops[-1][4] if ops else 0
    return at


def merge(spans):
    merged = []
    for a, b in sorted(spans):
        if merged and a <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], b))
        else:
            merged.append((a, b))
    return merged


def judge(typed, out, gold):
    """[(종류, 친 조각, 결과 조각, 정답 조각)]."""
    er, eops = regions(typed, out)
    gr, gops = regions(typed, gold)
    em, gm = mapper(eops), mapper(gops)
    res = []
    for a, b in merge(er + gr):
        t = typed[a:b].strip()
        o = out[em(a):em(b)].strip()
        g = gold[gm(a):gm(b)].strip()
        if o != t:
            res.append(('맞음' if o == g else '틀림', t, o, g))
        elif g != t:
            res.append(('놓침', t, o, g))
    return res


def best(typed, out, golds):
    def score(r):
        good = sum(1 for k in r if k[0] == '맞음')
        return (len(r) - 2 * good, -good)
    return min((judge(typed, out, g) for g in golds), key=score)


def main():
    paths = sys.argv[1:]
    if not paths:
        print(__doc__)
        sys.exit(2)
    show = int(os.environ.get('SCORE_SHOW', '60'))
    counts = collections.Counter()
    wrong = collections.defaultdict(list)
    keep_wrong = collections.defaultdict(list)
    missed = collections.defaultdict(list)
    rows = keep_rows = keep_bad = 0
    for path in paths:
        for line in open(path, encoding='utf-8'):
            parts = line.rstrip('\n').split('\t')
            if len(parts) < 3:
                continue
            typed, out = parts[0], parts[1]
            golds = [g.strip() for g in parts[2].split(' | ')]
            rows += 1
            for kind, t, o, g in best(typed, out, golds):
                counts[kind] += 1
                if kind == '틀림':
                    wrong[f'{t} → {o}   (정답: {g})'].append(typed)
                elif kind == '놓침':
                    missed[f'{t} → {g}'].append(typed)
            if len(parts) > 3 and typed != golds[0]:
                keep_rows += 1
                bad = [k for k in best(golds[0], parts[3], golds) if k[0] == '틀림']
                keep_bad += bool(bad)
                for _, t, o, _ in bad:
                    counts['지킴 틀림'] += 1
                    keep_wrong[f'{t} → {o}'].append(golds[0])
    edits = counts['맞음'] + counts['틀림']
    print(f'줄 {rows} · 엔진이 고친 자리 {edits} — 맞음 {counts["맞음"]} · 틀림 {counts["틀림"]} · 놓침 {counts["놓침"]}')
    if edits:
        p = counts['맞음'] / edits
        r = counts['맞음'] / max(1, counts['맞음'] + counts['놓침'])
        print(f'정밀도 {p * 100:.1f}% (고친 것 중 맞은 것) · 재현율 {r * 100:.1f}% (고쳐야 할 것 중 고친 것)')
    print(f'정답을 그대로 쳤을 때: {keep_rows}줄 중 {keep_bad}줄을 건드림 · 틀린 자리 {counts["지킴 틀림"]}')
    if show <= 0:
        return

    def dump(title, table):
        if not table:
            return
        print(f'\n## {title} ({sum(len(v) for v in table.values())})')
        for key, ex in sorted(table.items(), key=lambda kv: -len(kv[1]))[:show]:
            print(f'  {len(ex):3d}  {key}')
            print(f'         {ex[0]}')

    dump('틀린 고침', wrong)
    dump('정답을 그대로 쳤는데 바꾼 것', keep_wrong)
    if os.environ.get('SCORE_MISSED') == '1':
        dump('놓친 것', missed)


if __name__ == '__main__':
    main()
