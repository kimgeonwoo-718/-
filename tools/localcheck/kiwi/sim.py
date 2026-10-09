"""폰의 Kiwi 오타 교정 단계(KiwiSpacer.fix)를 컴퓨터에서 그대로 흉내 낸다.

폰은 Kiwi 자바 바인딩(버전은 keyboard/build.gradle.kts 의 kiwiVersion), 여기는 kiwipiepy(같은 버전 = 같은 엔진)다.
같은 설정을 쓴다: basicTypoSet, 임계값 1.5, 어절 단위로 되짚기, 12자 넘는 어절 건너뜀, 공백이 끼면 건너뜀,
언어모델이 아는 낱말이면 건너뜀, TypoGuard 가 막는 고침 건너뜀 — 뒤의 둘은 KiwiHelper(진짜 코틀린 코드)에 묻는다.

직접 부르지 말고 tools/localcheck/kiwi-fix.sh 로 부른다(필요한 것을 모아 준다).
"""
import os
import re
import subprocess
import sys

from kiwipiepy import Kiwi, Match

TYPO_THRESHOLD = 1.5
MAX_TYPO_WORD = 12
MATCH = Match.ALL | Match.NORMALIZING_CODA | Match.OOV_CHR_MODEL | Match.JOIN_NOUN_PREFIX | Match.MERGE_SAISIOT

_kiwi = Kiwi()
_helper = None
_cache = {}


def _ask(*fields):
    global _helper
    key = fields
    if key in _cache:
        return _cache[key]
    if _helper is None:
        env = dict(os.environ, JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8")
        _helper = subprocess.Popen(
            ["java", "-cp", os.environ["KIWI_SIM_CP"], "KiwiHelperKt", os.environ["KIWI_SIM_LM"]],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8", env=env)
    _helper.stdin.write("\t".join(fields) + "\n")
    _helper.stdin.flush()
    _cache[key] = _helper.stdout.readline().strip() == "1"
    return _cache[key]


def fix(text, guard=True):
    """KiwiSpacer.fix 와 같은 길. 고친 글을 돌려주고, 안 고쳤으면 None. guard=False 면 TypoGuard 를 건너뛴다(고치기 전 모습)."""
    toks = _kiwi.tokenize(text, match_options=MATCH, typos="basic", typo_cost_threshold=TYPO_THRESHOLD)
    if not any(t.typo_cost > 0 for t in toks):
        return None
    out = text
    for m in reversed(list(re.finditer(r"\S+", text))):
        a, b = m.start(), m.end()
        if b - a > MAX_TYPO_WORD:
            continue
        mine = [t for t in toks if t.start >= a and t.start + t.len <= b]
        if not mine or not any(t.typo_cost > 0 for t in mine):
            continue
        parts = [(mine[0].form, mine[0].tag)] + [(t.form, t.tag, False) for t in mine[1:]]
        try:
            joined = _kiwi.join(parts)
        except Exception:
            continue
        if not joined or any(c.isspace() for c in joined):
            continue
        original = text[a:b]
        if _ask("known", original):
            continue
        if guard and _ask("reject", original, joined):
            continue
        out = out[:a] + joined + out[b:]
    return None if out == text else out


if __name__ == "__main__":
    args = sys.argv[1:]
    use_guard = True
    if args and args[0] == "--no-guard":
        use_guard, args = False, args[1:]
    for s in args:
        print(f"{s}  →  {fix(s, use_guard) or '(그대로)'}")
