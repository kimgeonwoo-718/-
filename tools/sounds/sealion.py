#!/usr/bin/env python3
"""
바다사자 소리팩을 **합성**한다. 녹음이 아니라 코드로 만든 소리라 저작권이 없다.

    python3 tools/sounds/sealion.py      → keyboard/src/main/res/raw/sealion_*.wav

바다사자 울음은 목이 쉰 듯 거칠고 콧소리가 섞인 짧은 '아우/오우' 다. 그걸 흉내 낸다:
  - 소리의 뼈대: 톱니파(배음이 많아 거칠다). 높이는 살짝 내려가며 끝난다.
  - 거친 느낌: 30~50Hz 로 소리 크기를 떨게 한다(바다사자 울음의 '드르르' 결).
  - 모음: 공명 필터 셋(포먼트)으로 '아'→'우' 로 입 모양이 바뀌게 한다.
  - 짧게 치고 빠진다. 타자 소리는 0.1초 남짓이어야 다음 키와 겹치지 않는다.

numpy 없이 표준 라이브러리만 쓴다 — 이 저장소의 어디서든 그냥 돈다.
진짜 녹음으로 바꾸고 싶으면 같은 이름의 파일(16비트 모노 WAV)만 갈아 끼우면 된다.
"""
import math
import os
import random
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'keyboard', 'src', 'main', 'res', 'raw')


def resonator(signal, freq, bandwidth):
    """2차 공명 필터 하나. 포먼트(모음의 입 모양)를 만든다. freq 는 매 샘플 값의 목록."""
    out = [0.0] * len(signal)
    y1 = y2 = 0.0
    r = math.exp(-math.pi * bandwidth / RATE)
    for i, x in enumerate(signal):
        theta = 2 * math.pi * freq[i] / RATE
        a1 = 2 * r * math.cos(theta)
        a2 = -r * r
        gain = (1 - r) * math.sqrt(1 - 2 * r * math.cos(2 * theta) + r * r)
        y = gain * x + a1 * y1 + a2 * y2
        out[i] = y
        y2, y1 = y1, y
    return out


def lerp(a, b, t):
    return a + (b - a) * t


def bark(duration, f0_start, f0_end, vowel_from, vowel_to, rough_hz, seed, attack=0.008, noise=0.08):
    """바다사자 울음 하나. vowel_* 는 (F1, F2, F3) 포먼트 주파수."""
    rnd = random.Random(seed)
    n = int(duration * RATE)
    phase = 0.0
    source = []
    for i in range(n):
        t = i / n
        # 높이: 처음엔 살짝 오르다가 끝으로 떨어진다(울음의 억양).
        f0 = lerp(f0_start, f0_end, t) * (1 + 0.06 * math.sin(math.pi * min(1, t * 3)))
        phase += f0 / RATE
        phase -= math.floor(phase)
        saw = 2 * phase - 1
        # 거친 떨림 + 숨소리
        rough = 0.65 + 0.35 * math.sin(2 * math.pi * rough_hz * i / RATE + rnd.random() * 0.3)
        source.append(saw * rough + noise * (rnd.random() * 2 - 1))

    def track(k):
        return [lerp(vowel_from[k], vowel_to[k], min(1.0, (i / n) * 1.4)) for i in range(n)]

    f1 = resonator(source, track(0), 90)
    f2 = resonator(source, track(1), 120)
    f3 = resonator(source, track(2), 180)
    mixed = [a * 1.0 + b * 0.7 + c * 0.35 for a, b, c in zip(f1, f2, f3)]

    # 크기 모양: 빠르게 올라와 머물다 사라진다.
    att = max(1, int(attack * RATE))
    rel = int(n * 0.45)
    for i in range(n):
        if i < att:
            env = i / att
        elif i > n - rel:
            env = ((n - i) / rel) ** 1.6
        else:
            env = 1.0
        mixed[i] *= env
    peak = max(abs(v) for v in mixed) or 1.0
    return [v / peak * 0.85 for v in mixed]


def save(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + '.wav')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s)) * 32767)) for s in samples))
    print(path, f'{len(samples) / RATE:.2f}s')


# 모음 입 모양 (F1, F2, F3)
A = (780, 1150, 2500)   # 아
O = (520, 880, 2400)    # 오
U = (360, 760, 2300)    # 우

if __name__ == '__main__':
    # 글자 키: 짧은 '뿍/옥' 넷. 번갈아 내서 같은 소리가 되풀이되지 않게.
    save('sealion_key_1', bark(0.10, 420, 330, O, U, 38, seed=1))
    save('sealion_key_2', bark(0.09, 470, 380, A, O, 44, seed=2))
    save('sealion_key_3', bark(0.11, 380, 300, O, U, 34, seed=3))
    save('sealion_key_4', bark(0.10, 440, 350, A, U, 40, seed=4))
    # 스페이스: 제대로 된 '아우!'
    save('sealion_space', bark(0.30, 360, 250, A, U, 36, seed=5, attack=0.012))
    # 엔터: 두 번 짖기 '아우 아우'
    first = bark(0.20, 380, 280, A, U, 36, seed=6, attack=0.01)
    gap = [0.0] * int(0.05 * RATE)
    second = bark(0.24, 340, 240, A, U, 33, seed=7, attack=0.01)
    save('sealion_enter', first + gap + second)
    # 지우기: 낮고 짧게 '웅'
    save('sealion_delete', bark(0.10, 260, 200, O, U, 30, seed=8, noise=0.05))
