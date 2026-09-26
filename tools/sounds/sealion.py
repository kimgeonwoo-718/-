#!/usr/bin/env python3
"""
바다사자 소리팩을 **합성**한다. 녹음이 아니라 코드로 만든 소리라 저작권이 없다.

    python3 tools/sounds/sealion.py      → keyboard/src/main/res/raw/sealion_*.wav

## 첫 판이 '레이저' 였던 까닭 (2026-09-26 사용자 평)

매끈한 톱니파의 높이를 쭉 미끄러뜨렸다 — 그게 정확히 레이저 '퓨웅' 을 만드는 방법이다.
생물 소리는 그렇지 않다:
  - 성대가 **한 번씩 열렸다 닫히는 펄스**로 울린다(여기서는 Rosenberg 펄스의 미분).
  - 펄스 간격(지터)과 세기(시머)가 **매번 조금씩 흔들린다.** 이게 없으면 기계음이다.
  - 숨이 섞인다(성대 펄스에 맞춰 커졌다 작아지는 잡음).
  - 높이는 크게 미끄러지지 않는다. 오르내림이 작다.

## 두 가지 울음

  아아악  거칠게 긁는 소리. 펄스를 하나 걸러 약하게·늦게 해서(주기 두 배 떨림) 쉰 소리를 내고,
          숨을 많이 섞고, 끝을 '악' 처럼 뚝 끊은 뒤 짧게 '윽' 터뜨린다.  → 스페이스·지우기·엔터
  아웅!   '아' 로 열었다가 입을 오므려 '우', 콧소리 '웅' 으로 닫는다. 높이는 거의 평평. → 글자 키

numpy 없이 표준 라이브러리만 쓴다. 진짜 녹음으로 바꾸려면 같은 이름의 WAV(16비트 모노)만 갈아 끼운다.
"""
import math
import os
import random
import struct
import wave

RATE = 22050
T = 1.0 / RATE
OUT = os.path.join(os.path.dirname(__file__), '..', '..', 'keyboard', 'src', 'main', 'res', 'raw')


def lerp(a, b, t):
    return a + (b - a) * t


def glottal_source(n, f0_at, rnd, jitter=0.04, shimmer=0.15, doubling=0.0, breath=0.25, amp_at=None):
    """
    성대 펄스열. f0_at(t)·amp_at(t) 는 0..1 시각에 대한 높이·세기.
    doubling: 0..1, 펄스를 하나 걸러 약하고 늦게 — 쉰 소리·으르렁.
    """
    out = [0.0] * n
    i = 0
    k = 0
    while i < n:
        t = i / n
        f0 = f0_at(t)
        period = RATE / f0 * (1 + rnd.gauss(0, jitter))
        if k % 2 == 1:
            period *= 1 + 0.12 * doubling
        period = max(8, int(period))
        amp = (amp_at(t) if amp_at else 1.0) * max(0.2, 1 + rnd.gauss(0, shimmer))
        if k % 2 == 1:
            amp *= 1 - 0.55 * doubling
        # Rosenberg 펄스: 열림 60%, 닫힘 25%. 미분해서 넣는다(입술에서 나가는 소리가 그 모양이다).
        open_n = int(period * 0.6)
        close_n = max(2, int(period * 0.25))
        prev = 0.0
        for j in range(period):
            if j < open_n:
                g = 0.5 * (1 - math.cos(math.pi * j / open_n))
            elif j < open_n + close_n:
                g = math.cos(math.pi / 2 * (j - open_n) / close_n)
            else:
                g = 0.0
            if i + j >= n:
                break
            d = (g - prev) * amp
            # 숨소리: 성대가 열려 있을 때 더 세다
            d += breath * amp * (0.3 + g) * (rnd.random() * 2 - 1) * 0.35
            out[i + j] = d
            prev = g
        i += period
        k += 1
    return out


def formant(x, freq_at, bw):
    """Klatt 공명기(직류 이득 1). freq_at(t) 는 시각별 주파수."""
    n = len(x)
    y1 = y2 = 0.0
    out = [0.0] * n
    c = -math.exp(-2 * math.pi * bw * T)
    for i in range(n):
        f = freq_at(i / n)
        b = 2 * math.exp(-math.pi * bw * T) * math.cos(2 * math.pi * f * T)
        a = 1 - b - c
        y = a * x[i] + b * y1 + c * y2
        out[i] = y
        y2, y1 = y1, y
    return out


def vocal_tract(src, tracks, bws):
    """공명기를 줄줄이 잇는다(모음). tracks 는 시각별 (F1..F4) 를 주는 함수들."""
    y = src
    for track, bw in zip(tracks, bws):
        y = formant(y, track, bw)
    return y


def envelope(x, attack, release_frac, hard_stop=False):
    n = len(x)
    att = max(1, int(attack * RATE))
    rel = max(1, int(n * release_frac))
    for i in range(n):
        if i < att:
            e = (i / att) ** 0.7
        elif hard_stop and i > n - int(0.006 * RATE):
            e = (n - i) / (0.006 * RATE)          # '악': 뚝 끊는다
        elif not hard_stop and i > n - rel:
            e = ((n - i) / rel) ** 1.5
        else:
            e = 1.0
        x[i] *= e
    return x


def normalize(x, peak=0.85):
    m = max(abs(v) for v in x) or 1.0
    return [v / m * peak for v in x]


def stepwise(points):
    """[(t, v), ...] 사이를 부드럽게 잇는 함수."""
    def f(t):
        for (t0, v0), (t1, v1) in zip(points, points[1:]):
            if t <= t1:
                u = (t - t0) / (t1 - t0) if t1 > t0 else 1
                u = u * u * (3 - 2 * u)
                return v0 + (v1 - v0) * u
        return points[-1][1]
    return f


def aaak(duration, f0, seed, doubling=0.75, breath=0.55):
    """'아아악' — 거칠게 긁다가 뚝."""
    rnd = random.Random(seed)
    n = int(duration * RATE)
    # 높이: 살짝 올랐다 그대로 버틴다(미끄러뜨리지 않는다).
    pitch = stepwise([(0, f0 * 0.93), (0.15, f0 * 1.04), (0.8, f0), (1, f0 * 0.97)])
    loud = stepwise([(0, 0.6), (0.2, 1.0), (1, 0.95)])
    src = glottal_source(n, pitch, rnd, jitter=0.05, shimmer=0.22, doubling=doubling, breath=breath, amp_at=loud)
    # '애/아' 사이의 넓게 벌린 입. 대역폭을 넓게 — 짐승 소리는 모음이 뚜렷하지 않다.
    tracks = [stepwise([(0, 820), (1, 900)]), stepwise([(0, 1350), (1, 1450)]),
              lambda t: 2600, lambda t: 3500]
    y = vocal_tract(src, tracks, [160, 200, 260, 320])
    y = envelope(y, 0.012, 0, hard_stop=True)
    # '윽' — 목을 닫았다 여는 짧은 터짐
    gap = [0.0] * int(0.018 * RATE)
    burst_n = int(0.022 * RATE)
    burst = [(rnd.random() * 2 - 1) * (1 - i / burst_n) ** 2 * 0.15 for i in range(burst_n)]
    burst = formant(burst, lambda t: 1800, 900)
    return normalize(y + gap + burst)


def aung(duration, f0, seed, rough=0.25, breath=0.22):
    """'아웅!' — 열었다가 오므려 콧소리로 닫는다."""
    rnd = random.Random(seed)
    n = int(duration * RATE)
    pitch = stepwise([(0, f0 * 0.96), (0.3, f0 * 1.05), (1, f0 * 0.9)])
    loud = stepwise([(0, 0.7), (0.25, 1.0), (0.7, 0.8), (1, 0.45)])
    src = glottal_source(n, pitch, rnd, jitter=0.035, shimmer=0.16, doubling=rough, breath=breath, amp_at=loud)
    # 아(800,1250) → 우(380,800) → 웅(콧소리: F1 낮고 약함)
    f1 = stepwise([(0, 800), (0.35, 760), (0.65, 400), (1, 280)])
    f2 = stepwise([(0, 1250), (0.35, 1200), (0.65, 820), (1, 950)])
    f3 = stepwise([(0, 2500), (1, 2300)])
    y = vocal_tract(src, [f1, f2, f3, lambda t: 3400], [130, 150, 220, 300])
    # 콧소리 구간은 윗소리가 죽는다 — 끝으로 갈수록 한 번 더 걸러 둥글게.
    tail = int(n * 0.35)
    smooth = 0.0
    for i in range(n - tail, n):
        u = (i - (n - tail)) / tail
        smooth = smooth + (0.25 + 0.75 * (1 - u)) * (y[i] - smooth)
        y[i] = smooth
    y = envelope(y, 0.01, 0.3)
    return normalize(y, 0.8)


def save(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + '.wav')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s)) * 32767)) for s in samples))
    print(path, f'{len(samples) / RATE:.2f}s')


if __name__ == '__main__':
    # 글자 키: 짧은 '아웅' 넷. 높이·길이가 조금씩 달라 되풀이가 덜 느껴진다.
    save('sealion_key_1', aung(0.16, 330, seed=11))
    save('sealion_key_2', aung(0.14, 380, seed=12, rough=0.3))
    save('sealion_key_3', aung(0.17, 300, seed=13, rough=0.2))
    save('sealion_key_4', aung(0.15, 355, seed=14))
    # 스페이스·지우기·엔터: '아아악'
    save('sealion_space', aaak(0.26, 470, seed=21))
    save('sealion_delete', aaak(0.18, 430, seed=22, doubling=0.85))
    save('sealion_enter', aaak(0.40, 440, seed=23, doubling=0.8, breath=0.6))
