#!/usr/bin/env python3
"""헷갈리는 말을 **문맥으로 가리는 작은 모델**을 가르쳐 `confusable.bin.gz` 로 굽는다.

## 왜 규칙이 아니라 모델인가

'나을까/낳을까' 는 소리가 같아서 낱말만으로는 못 가린다. 처음에는 "이게/저게 + 낳 → 낫" 같은 규칙을
하나씩 붙였는데, 그 방식은 **쓰다가 틀린 문장을 볼 때마다 규칙을 하나 더 붙이는 것**이라 끝이 없다.
('삼전이 낳을까' 를 못 고쳤다.) 이 도구는 대신 실제 글에서 두 말이 어떤 말 곁에 쓰이는지를 **센다.**

- 낫다는 앞에 '이/가' 가 붙은 말(비교하는 대상)이 오고, 낳다는 '을/를' 이 붙은 말(낳는 것)이 온다.
- 이 차이는 '삼전이' 처럼 처음 보는 낱말에도 그대로다 — 낱말이 아니라 **조사와 이웃 낱말**을 본다.

그래서 새 낱말이 와도 되고, 틀린 문장이 나오면 규칙을 짜는 대신 `examples/*.tsv` 에 한 줄 적고
이 도구를 다시 돌리면 된다.

## 무엇을 보나 (특징)

대상 어절 앞 두 어절(입력 중에는 뒤를 못 본다)과, 글 전체를 고칠 때는 뒤 두 어절까지. 관형형('낳은')만은 입력 중이라도
다음 어절이 나온 뒤에 한 어절 뒤까지 본다.
어절마다 통째로, 마지막 한 음절(조사 자리), 마지막 두 음절을 본다. 거기에 대상 어절의 어미
('-을까', '-았-') 와 앞 어절 조사의 짝도 본다. **대상 어절의 첫 글자(낳/나/낫)는 절대 보지 않는다** —
그게 정답이라서, 보면 100% 가 나오고 아무것도 배우지 못한다(처음에 그렇게 틀렸다).

## 정답지가 틀린 것

글쓴이가 맞춤법을 틀린 글이 섞여 있다('아이를 나을때', '보다 낳은 미래'). 모델을 한 번 돌려 **자기 정답과
90% 이상 반대로 보는 것**은 글쓴이의 실수로 보고 버린 뒤 다시 가르친다(2.5% 쯤 버려진다).

## 쓰는 법

    tools/confusable/fetch_corpus.sh /tmp/corpus          글 받기 (한 번만, 약 900MB)
    pip install scikit-learn numpy                         (개발 도구다 — 앱에는 안 들어간다)
    python3 tools/confusable/train.py /tmp/corpus

끝나면 `core/src/main/resources/confusable.bin.gz` 와 `core/src/test/resources/confusable-golden.tsv`
가 바뀐다. 코틀린 쪽 `ConfusableModel` 이 같은 특징을 뽑아 같은 값을 내는지를 골든 파일로 시험한다.

**특징을 바꾸면 `ConfusableModel.kt` 의 `features()` 도 똑같이 바꿔야 한다.** 안 그러면 골든 시험이 깨진다.
"""
import argparse
import collections
import glob
import gzip
import json
import math
import os
import re
import sys

import numpy as np
from sklearn.feature_extraction import DictVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT_MODEL = os.path.join(ROOT, 'core', 'src', 'main', 'resources', 'confusable.bin.gz')
OUT_GOLDEN = os.path.join(ROOT, 'core', 'src', 'test', 'resources', 'confusable-golden.tsv')

FORMAT_VERSION = 1
REGULARIZATION = 1.0            # LogisticRegression 의 C. 0.3 이하로 줄이면 확신이 줄지만 고치는 것도 크게 준다(시험지로 쟀다)
QUANT = 64                      # 가중치를 정수로 저장한다: round(w * 64)
ENDERS = '.!?…'
NOT_HANGUL = re.compile('[^가-힣]')
ONE_RUN = re.compile(r'^[^가-힣]*([가-힣]+)[^가-힣]*$')
SENTENCE_SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')

INFORMAL = {'nsmc', 'chat'}     # 입말 글. 키보드에서 칠 말투라 더 무겁게 센다
INFORMAL_WEIGHT = 3.0
EXAMPLE_WEIGHT = 5.0            # examples/*.tsv — 사용자가 "이건 안 돼" 라고 알려 준 문장
DROP_BELOW = 0.1                # 자기 정답을 이보다 낮게 보면 글쓴이의 실수로 보고 버린다
LEFT = 2
# 입력 중(뒤를 못 봄) / 입력 중이지만 다음 어절이 막 나온 뒤('낳은 결과' 처럼 뒤 명사를 봐야 가려지는 관형형) / 글 전체
CONFIGS = {'rt': (LEFT, 0), 'rt1': (LEFT, 1), 'all': (LEFT, 2)}


# --- 짝 정의 -----------------------------------------------------------------------------------------
# 한 짝은 "어절 하나가 어느 쪽인가" 를 가르는 함수 하나다. 0 / 1 / None(둘 다 아님).
NAT_RE = re.compile(r'^(?:낫|나아(?!가|갈|간|갑|갔|감|오|올|온|옵|왔|옴)|나은|나을|나았|나으)')


# 연장 '낫'(낫을 갈았다)과 사람 이름 '나은'(나은이)은 이 짝이 아니다. ConfusableClassifier.kt 의 NatNah.skip 과 같다.
NAT_SKIP = re.compile(r'^(?:낫(?:이|을|은|으로|도|만|과|와|의|에|처럼|질|날|자루)|나은(?:이|아|야|양|씨|님))')


def natda_class(token):
    """낫다 계열이면 0, 낳다 계열이면 1. 이 짝이 아닌 것은 None."""
    if NAT_SKIP.match(token):
        return None
    if token.startswith('낳'):
        return 1
    if NAT_RE.match(token):
        return 0
    return None


PAIRS = {'natda': natda_class}


# --- 특징 (ConfusableModel.kt 의 features() 와 똑같아야 한다) ----------------------------------------------
def hg(word):
    return NOT_HANGUL.sub('', word)


def features(words, i, bos, left, right):
    """words: 공백으로 자른 어절들. i: 대상. bos: words[0] 이 문장 첫머리인가."""
    target = hg(words[i])
    f = set()
    ending = target[1:4]
    f.add('E:' + ending)
    for k in range(1, left + 1):
        j = i - k
        if j < 0:
            if bos:
                f.add('L%d:<s>' % k)
            break
        w = hg(words[j])
        if not w:
            f.add('L%d:<p>' % k)
            continue
        f.add('L%d:%s' % (k, w))
        f.add('L%ds1:%s' % (k, w[-1:]))
        f.add('L%ds2:%s' % (k, w[-2:]))
        f.add('B:' + w)
        if k == 1:
            f.add('c1:%s|%s' % (w[-1:], ending))
            f.add('c2:%s|%s' % (w[-2:], ending))
    for k in range(1, right + 1):
        j = i + k
        if j >= len(words):
            f.add('R%d:</s>' % k)
            break
        w = hg(words[j])
        if not w:
            f.add('R%d:<p>' % k)
            continue
        f.add('R%d:%s' % (k, w))
        f.add('R%ds1:%s' % (k, w[:1]))
        f.add('R%de1:%s' % (k, w[-1:]))
        f.add('B:' + w)
    if right and words[i][-1:] in '.?!':
        f.add('endsent')
    return sorted(f)


# --- 글에서 용례 뽑기 --------------------------------------------------------------------------------------
def iter_texts(corpus):
    for path in sorted(glob.glob(os.path.join(corpus, 'pet_*'))):
        with open(path, encoding='utf-8') as fh:
            for line in fh:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue
                yield 'pet', d.get('title', '') + '\n' + d.get('content', '')
    for path in sorted(glob.glob(os.path.join(corpus, 'raw_*'))):
        name = os.path.basename(path)
        src = 'nli' if 'KorNLI' in name else ('nsmc' if 'nsmc' in name else 'chat')
        with open(path, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                cols = line.rstrip('\n').split('\t')
                if src == 'nli':
                    for c in cols[:2]:
                        yield src, c
                elif src == 'nsmc':
                    yield src, cols[1] if len(cols) > 1 else ''
                else:
                    yield src, line.replace(',', ' ').replace('\t', '\n')


def targets_in(words, class_of):
    for i, w in enumerate(words):
        m = ONE_RUN.match(w)
        if m:
            c = class_of(m.group(1))
            if c is not None:
                yield i, c


def extract(corpus, class_of):
    seen = set()
    out = []
    for src, text in iter_texts(corpus):
        if '낳' not in text and '낫' not in text and '나' not in text:
            continue
        for sent in SENTENCE_SPLIT.split(text):
            words = sent.split()
            for i, c in targets_in(words, class_of):
                key = (c, tuple(words[max(0, i - 3):i + 4]))
                if key in seen:           # 청원은 복사해 붙인 글이 많다
                    continue
                seen.add(key)
                out.append({'c': c, 'w': words, 'i': i, 's': src})
    return out


def read_tsv(path, class_of, label_names, src):
    """`NAT<TAB>문장` 줄들. 문장 속 대상 어절마다 용례 하나."""
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            if line.startswith('#') or '\t' not in line:
                continue
            lab, sent = line.rstrip('\n').split('\t', 1)
            keep = lab.strip().startswith('KEEP')              # 둘 다 되는 문장 — 쓴 그대로 둬야 한다
            want = label_names[lab.strip()]
            words = sent.split()
            for i, c in targets_in(words, class_of):
                if c != want:
                    print('  ! %s: 라벨은 %s 인데 어절 %r 은 반대로 읽힌다 — 건너뜀: %s' % (os.path.basename(path), lab, words[i], sent))
                    continue
                out.append({'c': c, 'w': words, 'i': i, 's': src, 'keep': keep})
    return out


# --- 학습 -------------------------------------------------------------------------------------------------
def is_informal(row):
    return row['s'] in INFORMAL or row['s'] == 'example'


def matrix(rows, left, right, vec=None):
    """특징 행렬. **입말 용례는 특징을 두 벌로 낸다**(일반 `f` + 입말 전용 `I|f`).

    청원은 출산 이야기가 많아서 '~가 낳' 이 흔하고, 키보드로 치는 입말은 '~가 낫' 이 흔하다. 말투가 다르면 같은 조사도
    다른 쪽으로 기운다. 입말 전용 복사본이 있으면 입말 글이 증거를 대는 자리에서는 그쪽이 일반 값을 덮어쓰고, 증거가
    없는 자리에서는 일반 값이 그대로 남는다. 앱은 늘 입말로 쓰니 내보낼 때 두 값을 더해 한 벌로 굽는다(앱 쪽은 모른다).
    """
    dicts = []
    for r in rows:
        feats = features(r['w'], r['i'], True, left, right) + ['_']       # '_' 는 늘 켜진 특징 — 입말 쪽 편향을 받는다
        d = {f: 1 for f in feats}
        if is_informal(r):
            d.update({'I|' + f: 1 for f in feats})
        dicts.append(d)
    if vec is None:
        vec = DictVectorizer(sparse=True)
        return vec, vec.fit_transform(dicts)
    return vec, vec.transform(dicts)


def sample_weights(rows):
    return np.array([EXAMPLE_WEIGHT if r['s'] == 'example' else (INFORMAL_WEIGHT if r['s'] in INFORMAL else 1.0) for r in rows])


def fit(rows, left, right, min_df=2):
    vec, X = matrix(rows, left, right)
    df = np.asarray((X > 0).sum(axis=0)).ravel()
    keep = np.where(df >= min_df)[0]                  # 한 번 나온 특징은 버린다 (크기도 줄고, 외우는 것도 막는다)
    names = np.array(vec.get_feature_names_out())[keep]
    Xk = X[:, keep]
    y = np.array([r['c'] == 1 for r in rows])
    model = LogisticRegression(C=REGULARIZATION, max_iter=5000).fit(Xk, y, sample_weight=sample_weights(rows))
    return names, model


def clean(rows, left, right):
    """자기 정답과 90% 이상 반대로 보이는 용례(= 글쓴이의 실수)를 버린다."""
    vec, X = matrix(rows, left, right)
    y = np.array([r['c'] == 1 for r in rows])
    p = cross_val_predict(LogisticRegression(C=REGULARIZATION, max_iter=5000), X, y,
                          cv=StratifiedKFold(5, shuffle=True, random_state=1), method='predict_proba')[:, 1]
    own = np.where(y, p, 1 - p)
    keep = (own >= DROP_BELOW) | np.array([r['s'] == 'example' for r in rows])
    return [r for r, k in zip(rows, keep) if k], own


def export_block(name, mode, left, right, names, model):
    lines = []
    bias = int(round(float(model.intercept_[0]) * QUANT))
    lines.append('model\t%s\t%s\t%d\t%d\t%d' % (name, mode, left, right, bias))
    kept = 0
    merged = collections.defaultdict(float)             # 입말 복사본(`I|f`)은 일반 값에 더한다
    for fname, w in zip(names, model.coef_[0]):
        merged[fname[2:] if fname.startswith('I|') else fname] += float(w)
    bias = int(round((float(model.intercept_[0]) + merged.pop('_', 0.0)) * QUANT))
    lines[0] = 'model\t%s\t%s\t%d\t%d\t%d' % (name, mode, left, right, bias)
    for fname in sorted(merged):
        q = int(round(merged[fname] * QUANT))
        if q == 0:
            continue
        lines.append('w\t%s\t%d' % (fname, q))
        kept += 1
    return lines, kept


def parse_export(lines):
    """내보낸 글을 다시 읽어 점수를 내는 함수를 만든다 — 골든 값은 **양자화된 값으로** 낸다."""
    models = {}
    cur = None
    for line in lines:
        parts = line.split('\t')
        if parts[0] == 'model':
            cur = (parts[1], parts[2])
            models[cur] = {'left': int(parts[3]), 'right': int(parts[4]), 'bias': int(parts[5]), 'w': {}}
        elif parts[0] == 'w':
            models[cur]['w'][parts[1]] = int(parts[2])
    return models


def score(models, name, mode, words, i, bos=True):
    m = models[(name, mode)]
    z = m['bias']
    for f in features(words, i, bos, m['left'], m['right']):
        z += m['w'].get(f, 0)
    return 1.0 / (1.0 + math.exp(-z / QUANT))


# 반대쪽이라고 이만큼 확신해야 고친다. ConfusableClassifier.kt 의 NatNah.flipConfidence 와 같아야 한다.
# 영화평에서 '낳' 으로 쓴 것의 24% 가 문맥은 '낫' 이고, '나' 로 쓴 것 중 문맥이 '낳' 인 것은 0.3% 라서 방향마다 다르다.
FLIP = {1: 0.90, 0: 0.98}                    # 쓴 것의 종류(0 낫, 1 낳) → 문턱


def report(title, probs, evals):
    """시험지에서: 틀리게 쳤다면 고쳤을 것 / 맞게 쳤다면 건드렸을 것."""
    probs = np.asarray(probs)
    gold = np.array([r['c'] == 1 for r in evals])
    keep = np.array([r.get('keep', False) for r in evals])
    to_nat = probs <= 1 - FLIP[1]            # 낳으로 쓴 것을 낫으로 고칠 만큼 확신
    to_nah = probs >= FLIP[0]                # 낫으로 쓴 것을 낳으로 고칠 만큼 확신
    fix_nat = int((to_nat & ~gold & ~keep).sum()); n_nat = int((~gold & ~keep).sum())
    fix_nah = int((to_nah & gold & ~keep).sum()); n_nah = int((gold & ~keep).sum())
    touched = int(((to_nat & gold) | (to_nah & ~gold)).sum())
    print('  %-30s 낳→낫 %2d/%2d (%3.0f%%)  낫→낳 %2d/%2d (%3.0f%%)  멀쩡한 걸 건드림 %d/%d' % (
        title, fix_nat, n_nat, 100 * fix_nat / max(1, n_nat), fix_nah, n_nah, 100 * fix_nah / max(1, n_nah), touched, len(evals)))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('corpus', help='fetch_corpus.sh 로 받은 디렉터리')
    ap.add_argument('--no-write', action='store_true', help='시험만 하고 파일은 안 쓴다')
    args = ap.parse_args()

    label_names = {'NAT': 0, 'NAH': 1, 'KEEPNAT': 0, 'KEEPNAH': 1}
    out_lines = ['KSCF\t%d\t%d' % (FORMAT_VERSION, QUANT)]
    golden = []
    for pair, class_of in PAIRS.items():
        print('== %s' % pair)
        rows = extract(args.corpus, class_of)
        print('  글에서 뽑은 용례:', len(rows), dict(collections.Counter(r['s'] for r in rows)))
        rows_ex = read_tsv(os.path.join(HERE, 'examples', 'natda.tsv'), class_of, label_names, 'example')
        sheets = {name: read_tsv(os.path.join(HERE, 'eval', name + '.tsv'), class_of, label_names, 'eval')
                  for name in ('natda', 'natda-hard')}
        evals = [r for rs in sheets.values() for r in rs]
        print('  사용자가 알려 준 문장:', len(rows_ex), ' 시험지 표본:', {k: len(v) for k, v in sheets.items()})
        sentences = {tuple(r['w']) for r in rows_ex}
        overlap = [r for r in evals if tuple(r['w']) in sentences]
        if overlap:
            sys.exit('시험지 문장이 examples 에도 있다: ' + ' '.join(overlap[0]['w']))

        for mode, (left, right) in CONFIGS.items():
            cleaned, own = clean(rows, left, right)
            by_src = collections.defaultdict(lambda: [0, 0])
            for r, o in zip(rows, own):
                by_src[r['s']][0] += 1
                by_src[r['s']][1] += o < DROP_BELOW
            print('  [%s] 정답과 90%% 반대로 읽히는 것(= 글쓴이 실수 또는 못 푸는 문장): ' % mode +
                  ', '.join('%s %d/%d (%.2f%%)' % (s, b, a, b * 100 / a) for s, (a, b) in sorted(by_src.items())))
            names, model = fit(cleaned + rows_ex, left, right)
            lines, kept = export_block(pair, mode, left, right, names, model)
            out_lines += lines
            print('  [%s] 특징 %d개 (가중치 0 이 아닌 것 %d개)' % (mode, len(names), kept))

        models = parse_export(out_lines)
        print('  처음 보는 문장으로 잰 값 (낳→낫 문턱 %.2f, 낫→낳 문턱 %.2f):' % (FLIP[1], FLIP[0]))
        for sheet, rs in sheets.items():
            for mode in CONFIGS:
                report('%s %s' % (sheet, mode), [score(models, pair, mode, r['w'], r['i']) for r in rs], rs)
        # 골든: 시험지 전부 + 큰 글에서 고르게 120개
        step = max(1, len(rows) // 120)
        for r in evals + rows[::step]:
            for mode in CONFIGS:
                p = score(models, pair, mode, r['w'], r['i'])
                golden.append('%s\t%s\t%s\t%d\t%.5f' % (pair, mode, ' '.join(r['w']), r['i'], p))

    if args.no_write:
        return
    os.makedirs(os.path.dirname(OUT_MODEL), exist_ok=True)
    os.makedirs(os.path.dirname(OUT_GOLDEN), exist_ok=True)
    with gzip.GzipFile(OUT_MODEL, 'wb', mtime=0) as gz:        # mtime=0 — 같은 입력이면 같은 바이트
        gz.write(('\n'.join(out_lines) + '\n').encode('utf-8'))
    with open(OUT_GOLDEN, 'w', encoding='utf-8') as fh:
        fh.write('# 학습 도구(train.py)가 낸 값. ConfusableModel 이 같은 값을 내는지 시험한다. 손으로 고치지 마라.\n')
        fh.write('# 형식: 짝<TAB>모드<TAB>문장<TAB>대상 위치<TAB>낳 쪽일 확률\n')
        fh.write('\n'.join(golden) + '\n')
    print('쓴 것: %s (%d바이트), %s' % (OUT_MODEL, os.path.getsize(OUT_MODEL), OUT_GOLDEN))


if __name__ == '__main__':
    main()
