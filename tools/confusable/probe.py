#!/usr/bin/env python3
"""문장을 주면 헷갈리는 어절마다 모델이 몇 % 로 보는지 찍는다. (확률은 그 짝의 1 쪽 — 낫/낳 이면 낳다 쪽일 확률)

    python3 tools/confusable/probe.py "이게 낳은 결과야" "삼전이 낳을까 하이닉스가 낳을까"

`rt` 는 입력 중(앞 두 어절만), `rt1` 은 입력 중 다음 어절이 막 나온 뒤(관형형 '낳은' 용), `all` 은 글 전체(뒤 두 어절까지). 앱은 0.9 이상 또는 0.1 이하일 때만 고친다.
"""
import gzip
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train  # noqa: E402


def explain(models, pair, mode, words, i, top=8):
    """어느 특징이 얼마나 낳다 쪽(+)/낫다 쪽(-)으로 밀었는지."""
    m = models[(pair, mode)]
    rows = [(m['w'].get(f, 0) / train.QUANT, f) for f in train.features(words, i, True, m['left'], m['right'], m['stem']) if m['w'].get(f, 0)]
    rows.sort(key=lambda r: -abs(r[0]))
    print('      %s 편향 %+.2f | ' % (mode, m['bias'] / train.QUANT) + '  '.join('%s %+.2f' % (f, w) for w, f in rows[:top]))


def main():
    explain_on = '--explain' in sys.argv
    sys.argv = [a for a in sys.argv if a != '--explain']
    path = train.OUT_MODEL
    if '--model' in sys.argv:                 # train.py --out 으로 따로 구운 모델을 볼 때
        k = sys.argv.index('--model')
        path = sys.argv[k + 1]
        del sys.argv[k:k + 2]
    with gzip.open(path, 'rt', encoding='utf-8') as fh:
        models = train.parse_export(fh.read().splitlines())
    for sent in sys.argv[1:] or [l.rstrip('\n') for l in sys.stdin if l.strip()]:
        words = sent.split()
        found = False
        for pair in train.PAIRS.values():
            if (pair.name, 'rt') not in models:
                continue
            a, b = pair.side_names
            for i, c in train.targets_in(words, pair.class_of):
                found = True
                rt = train.score(models, pair.name, 'rt', words, i)
                r1 = train.score(models, pair.name, 'rt1', words, i)
                al = train.score(models, pair.name, 'all', words, i)
                verdict = lambda p: '%s 쪽 %2.0f%%' % (b, p * 100) if p >= 0.5 else '%s 쪽 %2.0f%%' % (a, (1 - p) * 100)
                print('%-24s 쓴 것: %s   rt: %s   rt1: %s   all: %s' % (words[i], (a, b)[c], verdict(rt), verdict(r1), verdict(al)))
                if explain_on:
                    explain(models, pair.name, 'rt', words, i)
                    explain(models, pair.name, 'rt1', words, i)
                    explain(models, pair.name, 'all', words, i)
        if not found:
            print('(헷갈리는 어절 없음) ' + sent)


if __name__ == '__main__':
    main()
