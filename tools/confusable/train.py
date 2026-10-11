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

    --pairs jebal,yaegi    이 짝만 다시 굽는다. 나머지 짝은 지금 모델 파일의 줄을 그대로 둔다(말뭉치를 다시 받으면
                           같은 코드로도 값이 조금 달라지므로 손대지 않을 짝은 다시 굽지 않는다).
    --cache 폴더            뽑은 용례를 저장해 두고 다음에 다시 쓴다(말뭉치를 훑는 데 짝마다 2~3분).
    --exclude 파일 --out 폴더   그 파일의 문장을 빼고 가르쳐 저장소 대신 그 폴더에 쓴다 — 처음 보는 문장으로 재 볼 때.
                           probe.py --model 폴더/confusable.bin.gz 로 본다.

## 오타 짝 (재발 → 제발 …, 2026-10-11)

오타가 우연히 다른 진짜 낱말이 된 짝(`typo_pair`). 낫/낳 과 달리 한쪽(제발)이 수십 배 흔하고, 고치는 것은 드문 쪽 → 흔한 쪽뿐이다.
두 쪽 무게를 맞추고(`balanced`), 입말 복사본을 끄고(`matrix()`), 드문 쪽 용례는 아주 확실할 때만 실수로 버린다(`clean()`).
말뭉치의 드문 말이 거의 다 한 가지 문맥이면('재발 방지') 채팅 말투의 문장을 `examples/` 에 적어 넣는다(`examples/jebal.tsv`).
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
RARE_DROP_BELOW = 0.005         # 오타 짝의 드문 쪽(재발)은 이보다 낮을 때만 버린다 — clean() 을 보라
LEFT = 2
MIN_SIDE = 100                  # 한쪽 용례가 이보다 적으면 배울 수 없다(말뭉치의 '이따가' 는 6개였다 — 청원에 안 쓰는 입말)
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


class Pair:
    """헷갈리는 짝 하나. 0 쪽과 1 쪽, 어절이 어느 쪽인지 가르는 함수, 시험지·예문 파일 이름, 고치는 문턱.

    - `stem`: 짝이 갈리는 앞부분의 음절 수. 특징의 어미('E:')는 그 뒤부터 본다 — 갈리는 글자가 특징에 들어가면 정답이
      새서 100% 가 나온다(낫/낳은 첫 글자 하나, 재발/제발은 두 글자).
    - `balanced`: 두 쪽 용례 수가 크게 다르면(재발 600 : 제발 4만) 무게를 맞춘다. 안 맞추면 늘 많은 쪽이다.
      그리고 '글쓴이 실수 버리기' 도 맞춘 무게로 본다 — 안 그러면 적은 쪽 용례가 거의 다 실수로 버려진다.
    - `flip`: 쓴 것의 종류 → 반대쪽으로 고칠 문턱. 1.01 이면 그쪽으로는 고치지 않는다.
    - `prune`: 양자화한 가중치가 이 값 이하인 특징은 버린다(모델 파일과 앱 메모리를 줄인다).
    - `informal`: 입말 용례에 입말 전용 특징 복사본을 붙이나(`matrix()`). 오타 짝은 끈다.
    - `rare_drop`: 오타 짝에서 드문 쪽 용례를 '글쓴이 실수' 로 버리는 문턱(`clean()`). None 이면 [DROP_BELOW].
    - `flip_by_mode`: 모드마다 문턱이 다르면 {모드: flip}. 시험지 값을 앱과 같은 문턱으로 찍으려는 것뿐이다.
    """

    def __init__(self, name, class_of, labels, side_names, flip, sheets, stem=1, quick='', balanced=False, prune=0, cap=None,
                 informal=True, rare_drop=None, C=REGULARIZATION, flip_by_mode=None):
        self.name = name
        self.class_of = class_of
        self.labels = labels
        self.side_names = side_names
        self.flip = flip
        self.sheets = sheets
        self.stem = stem
        self.quick = quick
        self.balanced = balanced
        self.prune = prune
        self.cap = cap
        self.informal = informal
        self.rare_drop = DROP_BELOW if rare_drop is None else rare_drop
        self.C = C
        self.flip_by_mode = flip_by_mode or {}


# --- 오타가 우연히 다른 진짜 낱말이 되는 짝 (2026-10-11) ---------------------------------------------------------------
# 0 쪽은 드문 말(사람들이 오타로 내는 꼴), 1 쪽은 흔한 말. 고치는 것은 0→1 쪽뿐이다(1→0 은 문턱 1.01 — 안 고친다).
# 말뭉치 문장 40개씩으로 엔진이 놓친 짝을 골랐다(재발→제발 7/40, 애기→얘기 0/40). 아홉 짝을 해 봤고 둘만 남겼다 —
# 대게/대개·결재/결제·네일/내일·반듯이/반드시·있다가/이따가·들어내/드러내·문안/무난 을 뺀 까닭은 docs/HANDOFF.md 에 있다
# (용례가 모자라거나, 말뭉치의 드문 말이 거의 다 오타라 제 뜻을 못 배운다).
# 앱 쪽 정의는 ConfusableClassifier.kt 에 같은 이름으로 있다 — classOf 가 똑같아야 한다.

def jebal_class(token):
    """재발(병이 다시 생김)이면 0, 제발이면 1. 홀로 선 꼴만 — '제발' 은 '요' 말고는 붙는 말이 없어서 '재발을·재발했다' 는 오타일 수가 없다."""
    if token in ('제발', '제발요'):
        return 1
    if token in ('재발', '재발요'):
        return 0
    return None


def yaegi_class(token):
    """애기(아기)면 0, 얘기(이야기)면 1."""
    if token.startswith('애기'):
        return 0
    if token.startswith('얘기'):
        return 1
    return None


DEUREO_TAILS = ('내', '냈', '낸', '낼', '냄')


def deureonae_class(token):
    """들어내다(들어서 밖으로 옮기다)면 0, 드러내다(보이게 하다)면 1."""
    if token[:2] == '들어' and token[2:3] in DEUREO_TAILS:
        return 0
    if token[:2] == '드러' and token[2:3] in DEUREO_TAILS:
        return 1
    return None


def munan_class(token):
    """문안(웃어른께 안부를 여쭘)이면 0, 무난이면 1."""
    if token.startswith('문안'):
        return 0
    if token.startswith('무난'):
        return 1
    return None


def typo_pair(name, class_of, sides, labels, stem, quick, cap=None, rare_drop=None):
    """오타 짝: 두 쪽 무게를 맞추고, 0→1 만 고친다. 흔한 쪽이 너무 많으면 [cap] 개까지만 쓴다(학습 시간)."""
    return Pair(name, class_of, labels, sides, flip={0: TYPO_FLIP, 1: 1.01}, sheets=(name,), stem=stem, quick=quick,
                balanced=True, prune=TYPO_PRUNE, cap=cap, informal=False, rare_drop=rare_drop,
                flip_by_mode={'rt1': {0: TYPO_FLIP_NEXT, 1: 1.01}})


TYPO_FLIP = 0.95      # 0 쪽(드문 말)으로 쓴 것을 1 쪽으로 고치는 문턱(글 전체·앞말만). ConfusableClassifier.kt 의 TypoPair.FLIP·EARLY 와 같다.
TYPO_FLIP_NEXT = 0.90  # 입력 중 다음 어절이 막 나왔을 때(rt1). TypoPair.FLIP_NEXT 와 같다.
TYPO_PRUNE = 2        # |가중치×64| 가 이 이하인 특징은 버린다 — 짝이 늘어도 앱 메모리가 크게 안 늘게


PAIRS = {
    'natda': Pair('natda', natda_class, {'NAT': 0, 'NAH': 1, 'KEEPNAT': 0, 'KEEPNAH': 1}, ('낫', '낳'),
                  # 영화평에서 '낳' 으로 쓴 것의 24% 가 문맥은 '낫' 이고, '나' 로 쓴 것 중 문맥이 '낳' 인 것은 0.3% 라서 방향마다 다르다.
                  flip={1: 0.90, 0: 0.98}, sheets=('natda', 'natda-hard'), quick='낳낫나'),
    # 재발 은 520개뿐이고 거의 다 '재발 방지' 라, 낯선 문맥의 재발을 실수로 버리면 안 된다(clean()). 애기 는 3천 개가 넘고
    # 말뭉치의 애기 중 '얘기' 오타가 많아서(애기하고·애기했습니다) 보통 문턱으로 버리는 편이 시험지에서 더 낫다(오타 고침 17 ↔ 9 / 59, 건드림 0 ↔ 0).
    'jebal': typo_pair('jebal', jebal_class, ('재발', '제발'), {'JAEBAL': 0, 'JEBAL': 1}, 2, '발', rare_drop=RARE_DROP_BELOW),
    'yaegi': typo_pair('yaegi', yaegi_class, ('애기', '얘기'), {'AEGI': 0, 'YAEGI': 1}, 2, '기', cap=30000),
    'deureonae': typo_pair('deureonae', deureonae_class, ('들어내', '드러내'), {'DEUREO': 0, 'DEUREONAE': 1}, 2, '내'),
    'munan': typo_pair('munan', munan_class, ('문안', '무난'), {'MUNAN0': 0, 'MUNAN': 1}, 2, '안난'),
}


# --- 특징 (ConfusableModel.kt 의 features() 와 똑같아야 한다) ----------------------------------------------
def hg(word):
    return NOT_HANGUL.sub('', word)


def features(words, i, bos, left, right, stem=1):
    """words: 공백으로 자른 어절들. i: 대상. bos: words[0] 이 문장 첫머리인가. stem: 짝이 갈리는 앞부분 음절 수(그 뒤가 어미)."""
    target = hg(words[i])
    f = set()
    ending = target[stem:stem + 3]
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


def extract(corpus, pair):
    seen = set()
    out = []
    for src, text in iter_texts(corpus):
        if not any(ch in text for ch in pair.quick):
            continue
        for sent in SENTENCE_SPLIT.split(text):
            words = sent.split()
            for i, c in targets_in(words, pair.class_of):
                key = (c, tuple(words[max(0, i - 3):i + 4]))
                if key in seen:           # 청원은 복사해 붙인 글이 많다
                    continue
                seen.add(key)
                out.append({'c': c, 'w': words, 'i': i, 's': src})
    if pair.cap:
        out = capped(out, pair.cap)
    return out


def capped(rows, cap):
    """한쪽 용례가 [cap] 개를 넘으면 줄인다. 입말 용례는 다 두고, 나머지는 고르게 건너뛰며 고른다(씨앗 없이 늘 같은 결과)."""
    out = []
    for c in (0, 1):
        side = [r for r in rows if r['c'] == c]
        if len(side) <= cap:
            out += side
            continue
        informal = [r for r in side if r['s'] in INFORMAL]
        formal = [r for r in side if r['s'] not in INFORMAL]
        room = max(0, cap - len(informal))
        step = max(1, len(formal) // room) if room else len(formal) + 1
        out += informal[:cap] + formal[::step][:room]
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


def matrix(rows, left, right, vec=None, stem=1, informal=True):
    """특징 행렬. **입말 용례는 특징을 두 벌로 낸다**(일반 `f` + 입말 전용 `I|f`).

    청원은 출산 이야기가 많아서 '~가 낳' 이 흔하고, 키보드로 치는 입말은 '~가 낫' 이 흔하다. 말투가 다르면 같은 조사도
    다른 쪽으로 기운다. 입말 전용 복사본이 있으면 입말 글이 증거를 대는 자리에서는 그쪽이 일반 값을 덮어쓰고, 증거가
    없는 자리에서는 일반 값이 그대로 남는다. 앱은 늘 입말로 쓰니 내보낼 때 두 값을 더해 한 벌로 굽는다(앱 쪽은 모른다).

    `informal=False` 면 복사본을 안 낸다(오타 짝). 드문 말(재발·네일)은 입말 글에 거의 안 나와서, 복사본의 편향이
    "입말이면 늘 흔한 말" 로 배워져 두 쪽 무게를 맞춘 것을 도로 무른다 — 멀쩡한 '네일 받으러' 를 '내일' 로 바꿨다.
    """
    dicts = []
    for r in rows:
        feats = features(r['w'], r['i'], True, left, right, stem) + ['_']       # '_' 는 늘 켜진 특징 — 입말 쪽 편향을 받는다
        d = {f: 1 for f in feats}
        if informal and is_informal(r):
            d.update({'I|' + f: 1 for f in feats})
        dicts.append(d)
    if vec is None:
        vec = DictVectorizer(sparse=True)
        return vec, vec.fit_transform(dicts)
    return vec, vec.transform(dicts)


def sample_weights(rows):
    return np.array([EXAMPLE_WEIGHT if r['s'] == 'example' else (INFORMAL_WEIGHT if r['s'] in INFORMAL else 1.0) for r in rows])


def balance(w, y):
    """두 쪽의 무게 합을 같게(재발 600 : 제발 4만 같은 짝). 평균 무게는 그대로 둔다."""
    return w * np.where(y, 0.5 / w[y].sum(), 0.5 / w[~y].sum()) * len(w)


def fit(rows, left, right, min_df=2, pair=None):
    stem = pair.stem if pair else 1
    vec, X = matrix(rows, left, right, stem=stem, informal=pair.informal if pair else True)
    df = np.asarray((X > 0).sum(axis=0)).ravel()
    keep = np.where(df >= min_df)[0]                  # 한 번 나온 특징은 버린다 (크기도 줄고, 외우는 것도 막는다)
    names = np.array(vec.get_feature_names_out())[keep]
    Xk = X[:, keep]
    y = np.array([r['c'] == 1 for r in rows])
    w = sample_weights(rows)
    if pair and pair.balanced:
        w = balance(w, y)
    model = LogisticRegression(C=pair.C if pair else REGULARIZATION, max_iter=5000).fit(Xk, y, sample_weight=w)
    return names, model


def clean(rows, left, right, pair=None):
    """자기 정답과 90% 이상 반대로 보이는 용례(= 글쓴이의 실수)를 버린다.

    **오타 짝(`balanced`)의 드문 쪽(0)은 짝마다 정한 문턱(`Pair.rare_drop`)으로 버린다.** 재발 은 [RARE_DROP_BELOW] — 드문 쪽 용례가 적고 문맥이 제각각이라,
    한 겹을 빼고 가르친 모델에게는 낯선 문맥의 '재발'(재발 피해에, 재발 여부)이 '제발' 로 보인다. 그걸 0.1 문턱으로 버리면
    '재발' 520개 중 190~235개가 버려지고 남는 것은 '재발 방지' 같은 뻔한 문맥뿐이라, 모델이 "낯선 문맥이면 제발" 을 배웠다
    (편향 +6.3, 처음 보는 '디스크 재발 때문에' 를 제발로 바꿨다). 하나도 안 버리면 반대로 섞인 진짜 오타('재발요', '재발 좀 처벌 해주세요')가
    '제발' 단서를 깎는다. 0.005 면 버리는 것은 50여 개고 거의 다 진짜 오타였다.
    """
    stem = pair.stem if pair else 1
    vec, X = matrix(rows, left, right, stem=stem, informal=pair.informal if pair else True)
    y = np.array([r['c'] == 1 for r in rows])
    estimator = LogisticRegression(C=pair.C if pair else REGULARIZATION, max_iter=5000,
                                   class_weight='balanced' if pair and pair.balanced else None)
    p = cross_val_predict(estimator, X, y,
                          cv=StratifiedKFold(5, shuffle=True, random_state=1), method='predict_proba')[:, 1]
    own = np.where(y, p, 1 - p)
    keep = (own >= DROP_BELOW) | np.array([r['s'] == 'example' for r in rows])
    if pair and pair.balanced:
        keep |= ~y & (own >= pair.rare_drop)
    return [r for r, k in zip(rows, keep) if k], own


def export_block(name, mode, left, right, names, model, stem=1, prune=0):
    lines = []
    bias = int(round(float(model.intercept_[0]) * QUANT))
    lines.append('model\t%s\t%s\t%d\t%d\t%d' % (name, mode, left, right, bias))
    kept = 0
    merged = collections.defaultdict(float)             # 입말 복사본(`I|f`)은 일반 값에 더한다
    for fname, w in zip(names, model.coef_[0]):
        merged[fname[2:] if fname.startswith('I|') else fname] += float(w)
    bias = int(round((float(model.intercept_[0]) + merged.pop('_', 0.0)) * QUANT))
    # 갈리는 앞부분이 한 음절이 아니면 끝에 적는다(낫/낳 은 안 적는다 — 예전 파일과 같은 모양).
    lines[0] = 'model\t%s\t%s\t%d\t%d\t%d' % (name, mode, left, right, bias) + ('\t%d' % stem if stem != 1 else '')
    for fname in sorted(merged):
        q = int(round(merged[fname] * QUANT))
        if q == 0 or abs(q) <= prune:
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
            models[cur] = {'left': int(parts[3]), 'right': int(parts[4]), 'bias': int(parts[5]),
                           'stem': int(parts[6]) if len(parts) > 6 else 1, 'w': {}}
        elif parts[0] == 'w':
            models[cur]['w'][parts[1]] = int(parts[2])
    return models


def score(models, name, mode, words, i, bos=True):
    m = models[(name, mode)]
    z = m['bias']
    for f in features(words, i, bos, m['left'], m['right'], m['stem']):
        z += m['w'].get(f, 0)
    return 1.0 / (1.0 + math.exp(-z / QUANT))


def report(title, probs, evals, pair, mode=None):
    """시험지에서: 틀리게 쳤다면 고쳤을 것 / 맞게 쳤다면 건드렸을 것."""
    probs = np.asarray(probs)
    gold = np.array([r['c'] == 1 for r in evals])
    keep = np.array([r.get('keep', False) for r in evals])
    flip = pair.flip_by_mode.get(mode, pair.flip)
    to0 = probs <= 1 - flip[1]          # 1 쪽으로 쓴 것을 0 쪽으로 고칠 만큼 확신
    to1 = probs >= flip[0]              # 0 쪽으로 쓴 것을 1 쪽으로 고칠 만큼 확신
    fix0 = int((to0 & ~gold & ~keep).sum()); n0 = int((~gold & ~keep).sum())
    fix1 = int((to1 & gold & ~keep).sum()); n1 = int((gold & ~keep).sum())
    touched = int(((to0 & gold) | (to1 & ~gold)).sum())
    a, b = pair.side_names
    print('  %-30s %s→%s %2d/%2d (%3.0f%%)  %s→%s %2d/%2d (%3.0f%%)  멀쩡한 걸 건드림 %d/%d' % (
        title, b, a, fix0, n0, 100 * fix0 / max(1, n0), a, b, fix1, n1, 100 * fix1 / max(1, n1), touched, len(evals)))


def existing_outputs():
    """지금 모델 파일의 짝별 줄과 골든 파일의 짝별 줄. 다시 굽지 않는 짝은 이걸 그대로 쓴다."""
    blocks = collections.OrderedDict()
    golden = collections.defaultdict(list)
    if os.path.exists(OUT_MODEL):
        with gzip.open(OUT_MODEL, 'rt', encoding='utf-8') as fh:
            current = None
            for line in fh.read().splitlines()[1:]:
                if line.startswith('model\t'):
                    current = line.split('\t')[1]
                if current:
                    blocks.setdefault(current, []).append(line)
    if os.path.exists(OUT_GOLDEN):
        with open(OUT_GOLDEN, encoding='utf-8') as fh:
            for line in fh:
                if line.strip() and not line.startswith('#'):
                    golden[line.split('\t', 1)[0]].append(line.rstrip('\n'))
    return blocks, golden


def is_excluded(row, excluded):
    """용례의 문장이 통째로, 또는 대상 어절을 둘러싼 3~5 어절이 뺄 문장에 들어 있나."""
    words, i = row['w'], row['i']
    if ' '.join(words) in excluded:
        return True
    for n in range(3, 6):
        for k in range(max(0, i - n + 1), min(i, len(words) - n) + 1):
            if ' '.join(words[k:k + n]) in excluded:
                return True
    return False


def cached_extract(args, pair):
    if not args.cache:
        return extract(args.corpus, pair)
    import pickle
    os.makedirs(args.cache, exist_ok=True)
    path = os.path.join(args.cache, pair.name + '.pkl')
    if os.path.exists(path):
        with open(path, 'rb') as fh:
            return pickle.load(fh)
    rows = extract(args.corpus, pair)
    with open(path, 'wb') as fh:
        pickle.dump(rows, fh)
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('corpus', help='fetch_corpus.sh 로 받은 디렉터리')
    ap.add_argument('--no-write', action='store_true', help='시험만 하고 파일은 안 쓴다')
    ap.add_argument('--pairs', help='이 짝들만 다시 굽는다(쉼표로). 나머지 짝은 지금 모델 파일의 것을 그대로 둔다 — '
                    '말뭉치를 다시 받으면 같은 코드로도 값이 조금 달라지므로, 손대지 않을 짝은 다시 굽지 않는다')
    ap.add_argument('--cache', help='뽑은 용례를 여기 저장해 두고 다음에 다시 쓴다(개발용)')
    ap.add_argument('--out', help='모델·골든 파일을 저장소 대신 이 디렉터리에 쓴다(개발용 — probe.py --model 로 본다)')
    ap.add_argument('--C', type=float, help='고른 짝의 정규화 세기(LogisticRegression 의 C)를 이 값으로(개발용)')
    ap.add_argument('--exclude', help='이 파일의 문장(줄마다, 탭이 있으면 탭으로 자른 칸마다)은 배우지 않는다 — 그 문장으로 잴 때 '
                    '모델이 미리 본 문장이 아니게(개발용)')
    args = ap.parse_args()
    excluded = set()            # 뺄 문장의 어절 3~5개짜리 조각 전부 — 말뭉치 쪽 문장 자르기가 달라도 걸리게
    if args.exclude:
        with open(args.exclude, encoding='utf-8') as fh:
            for line in fh:
                for cell in line.rstrip('\n').split('\t'):
                    words = cell.split()
                    excluded.add(' '.join(words))
                    for n in range(3, 6):
                        excluded.update(' '.join(words[k:k + n]) for k in range(len(words) - n + 1))
    chosen = set(args.pairs.split(',')) if args.pairs else set(PAIRS)
    if args.C:
        for name in chosen & set(PAIRS):
            PAIRS[name].C = args.C
    unknown = chosen - set(PAIRS)
    if unknown:
        sys.exit('모르는 짝: %s' % ', '.join(sorted(unknown)))
    old_blocks, old_golden = existing_outputs()

    out_lines = ['KSCF\t%d\t%d' % (FORMAT_VERSION, QUANT)]
    golden = []
    for pair in PAIRS.values():
        if pair.name not in chosen:
            if pair.name in old_blocks:
                out_lines += old_blocks[pair.name]
                golden += old_golden.get(pair.name, [])
                print('== %s (그대로 둠)' % pair.name)
            continue
        print('== %s' % pair.name)
        rows = cached_extract(args, pair)
        if excluded:
            before = len(rows)
            rows = [r for r in rows if not is_excluded(r, excluded)]
            print('  --exclude 로 뺀 용례:', before - len(rows))
        print('  글에서 뽑은 용례:', len(rows), dict(collections.Counter(r['s'] for r in rows)),
              ' 쪽마다:', dict(collections.Counter(pair.side_names[r['c']] for r in rows)))
        thin = [pair.side_names[c] for c in (0, 1) if sum(r['c'] == c for r in rows) < MIN_SIDE]
        if thin:
            print('  ! %s 용례가 %d개도 안 된다 — 이 짝은 굽지 않는다' % ('·'.join(thin), MIN_SIDE))
            continue
        rows_ex = read_tsv(os.path.join(HERE, 'examples', pair.name + '.tsv'), pair.class_of, pair.labels, 'example')
        sheets = {name: read_tsv(os.path.join(HERE, 'eval', name + '.tsv'), pair.class_of, pair.labels, 'eval')
                  for name in pair.sheets}
        evals = [r for rs in sheets.values() for r in rs]
        print('  사용자가 알려 준 문장:', len(rows_ex), ' 시험지 표본:', {k: len(v) for k, v in sheets.items()})
        sentences = {tuple(r['w']) for r in rows_ex}
        overlap = [r for r in evals if tuple(r['w']) in sentences]
        if overlap:
            sys.exit('시험지 문장이 examples 에도 있다: ' + ' '.join(overlap[0]['w']))

        for mode, (left, right) in CONFIGS.items():
            cleaned, own = clean(rows, left, right, pair)
            by_src = collections.defaultdict(lambda: [0, 0])
            for r, o in zip(rows, own):
                by_src[r['s']][0] += 1
                by_src[r['s']][1] += o < DROP_BELOW
            print('  [%s] 정답과 90%% 반대로 읽히는 것(= 글쓴이 실수 또는 못 푸는 문장): ' % mode +
                  ', '.join('%s %d/%d (%.2f%%)' % (s, b, a, b * 100 / a) for s, (a, b) in sorted(by_src.items())) +
                  '  — 쪽마다 %s' % ', '.join('%s %d/%d' % (pair.side_names[c], sum(1 for r, o in zip(rows, own) if r['c'] == c and o < DROP_BELOW),
                                                         sum(1 for r in rows if r['c'] == c)) for c in (0, 1)) +
                  (' (오타 짝의 %s 쪽은 %.3f 아래만 버린다: %d개)' % (pair.side_names[0], pair.rare_drop,
                                                                  sum(1 for r, o in zip(rows, own) if r['c'] == 0 and o < pair.rare_drop))
                   if pair.balanced else ''))
            names, model = fit(cleaned + rows_ex, left, right, pair=pair)
            lines, kept = export_block(pair.name, mode, left, right, names, model, pair.stem, pair.prune)
            out_lines += lines
            print('  [%s] 특징 %d개 (가중치 0 이 아닌 것 %d개)' % (mode, len(names), kept))

        models = parse_export(out_lines)
        a, b = pair.side_names
        print('  처음 보는 문장으로 잰 값 (%s→%s 문턱 %.2f, %s→%s 문턱 %.2f):' % (b, a, pair.flip[1], a, b, pair.flip[0]))
        for sheet, rs in sheets.items():
            for mode in CONFIGS:
                report('%s %s' % (sheet, mode), [score(models, pair.name, mode, r['w'], r['i']) for r in rs], rs, pair, mode)
        # 골든: 시험지 전부 + 큰 글에서 고르게 120개
        step = max(1, len(rows) // 120)
        for r in evals + rows[::step]:
            for mode in CONFIGS:
                p = score(models, pair.name, mode, r['w'], r['i'])
                golden.append('%s\t%s\t%s\t%d\t%.5f' % (pair.name, mode, ' '.join(r['w']), r['i'], p))

    if args.no_write:
        return
    out_model, out_golden = OUT_MODEL, OUT_GOLDEN
    if args.out:
        out_model = os.path.join(args.out, os.path.basename(OUT_MODEL))
        out_golden = os.path.join(args.out, os.path.basename(OUT_GOLDEN))
    os.makedirs(os.path.dirname(out_model), exist_ok=True)
    os.makedirs(os.path.dirname(out_golden), exist_ok=True)
    with gzip.GzipFile(out_model, 'wb', mtime=0) as gz:        # mtime=0 — 같은 입력이면 같은 바이트
        gz.write(('\n'.join(out_lines) + '\n').encode('utf-8'))
    with open(out_golden, 'w', encoding='utf-8') as fh:
        fh.write('# 학습 도구(train.py)가 낸 값. ConfusableModel 이 같은 값을 내는지 시험한다. 손으로 고치지 마라.\n')
        fh.write('# 형식: 짝<TAB>모드<TAB>문장<TAB>대상 위치<TAB>그 짝의 1 쪽(낫/낳 이면 낳)일 확률\n')
        fh.write('\n'.join(golden) + '\n')
    print('쓴 것: %s (%d바이트), %s' % (out_model, os.path.getsize(out_model), out_golden))


if __name__ == '__main__':
    main()
