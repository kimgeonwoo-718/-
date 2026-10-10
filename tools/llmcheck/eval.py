#!/usr/bin/env python3
"""기기 안 모델 번역 시험: 시험지의 한국어를 cli 에 먹여 chrF·한글 남음·지연을 낸다.

쓰는 법: eval.py <cli> <모델.gguf> <지시문 파일> <언어 en|ja|zh> <시험지 a|b|ab> [스레드] [--show]
chrF 는 server/bench/translate.mjs 와 같은 식(β=2, 글자 1~6-gram, 공백 버림)이라 숫자를 바로 견줄 수 있다.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHEETS = {"a": "translate-cases.tsv", "b": "translate-cases-b.tsv"}


def chrf(hyp, ref):
    h = list(re.sub(r"\s+", "", hyp))
    r = list(re.sub(r"\s+", "", ref))
    if not h or not r:
        return 0.0
    ps = rs = 0.0
    used = 0
    for n in range(1, 7):
        def grams(a):
            m = {}
            for i in range(len(a) - n + 1):
                k = "".join(a[i : i + n])
                m[k] = m.get(k, 0) + 1
            return m
        hc, rc = grams(h), grams(r)
        th, tr = sum(hc.values()), sum(rc.values())
        if not th or not tr:
            continue
        match = sum(min(v, rc.get(k, 0)) for k, v in hc.items())
        ps += match / th
        rs += match / tr
        used += 1
    if not used:
        return 0.0
    p, r2 = ps / used, rs / used
    if not p and not r2:
        return 0.0
    return 100 * 5 * p * r2 / (4 * p + r2)


def main():
    cli, model, system, lang, sheets = sys.argv[1:6]
    threads = sys.argv[6] if len(sys.argv) > 6 and not sys.argv[6].startswith("--") else "4"
    show = "--show" in sys.argv
    idx = {"en": 3, "ja": 4, "zh": 5}[lang]
    cases = []
    for key in sheets:
        for line in (ROOT / "server/bench" / SHEETS[key]).read_text(encoding="utf-8").split("\n")[1:]:
            if line:
                cols = line.split("\t")
                cases.append((cols[0], cols[2], cols[idx]))
    stdin = "\n".join(c[1] for c in cases) + "\n"
    proc = subprocess.run([cli, model, system, threads], input=stdin, capture_output=True, text=True)
    lines = [l for l in proc.stdout.split("\n") if l]
    if len(lines) != len(cases):
        sys.exit(f"줄 수가 안 맞는다: 입력 {len(cases)} 출력 {len(lines)}\n{proc.stderr[-500:]}")
    scores, leaks, errs, ms = [], 0, 0, []
    for (cid, ko, ref), line in zip(cases, lines):
        m = re.match(r"^(.*)\t\[(\d+) ms", line)
        if line.startswith("ERR"):
            errs += 1
            if show:
                print(cid, ko, "=> ERROR", line)
            continue
        hyp = m.group(1).replace("⏎", " ")
        ms.append(int(m.group(2)))
        scores.append(chrf(hyp, ref))
        if re.search("[가-힣]", hyp):
            leaks += 1
        if show:
            print(f"{cid}\t{ko}\n   {hyp}   [{scores[-1]:.0f}]")
    n = len(scores)
    ms_sorted = sorted(ms[1:]) if len(ms) > 1 else ms  # 첫 요청은 지시문 계산이 들어 있어 뺀다
    print(f"{lang} chrF {sum(scores)/max(n,1):.1f}  한글남음 {leaks}  오류 {errs}  n={n}  "
          f"첫 요청 {ms[0] if ms else 0}ms  이후 중간값 {ms_sorted[len(ms_sorted)//2] if ms_sorted else 0}ms")


main()
