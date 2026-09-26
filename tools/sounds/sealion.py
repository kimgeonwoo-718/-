#!/usr/bin/env python3
"""
바다사자 소리팩을 **합성**한다. 녹음을 복사한 것이 아니라 코드로 새로 만든 소리다.

    pip install numpy scipy
    python3 tools/sounds/sealion.py            → tools/sounds/out/sealion_*.wav (앱 것을 덮지 않는다)
    python3 tools/sounds/sealion.py --measure  만든 소리의 특징값을 찍는다(아래 목표와 견준다)

## 어떻게 맞췄나 (2026-09-26)

첫 판은 톱니파의 높이를 미끄러뜨려 '레이저' 였고, 둘째 판은 성대 펄스로 바꿨지만 여전히 맞출
기준이 없었다. 셋째 판은 사용자가 준 바다사자 울음 녹음 둘에서 **특징값만 재어**(녹음은 쓰지
않는다 — 남의 영상 소리라 앱에 넣을 수 없다) 그 숫자에 맞게 합성했다. 잰 값:

  (아래 수치에 맞는 공명 세기·숨 양은 격자 탐색으로 찾았다. 합성 결과: 아웅 주기성 0.90, 57/32/10%,
  아아악 주기성 0.48, 50/44/3% — 녹음과 거의 같다. 들어 보고 정한 게 아니라 잰 것이다.)

  짧은 울음('아웅', 글자 키)   0.14초. 높이 700Hz 로 치고 들어가 20ms 만에 500Hz 로 떨어져
                              머물다가 끝으로 600Hz 까지 오르며 가장 커지고, 뚝 끊긴 뒤 300Hz 로
                              잠깐 꺾인다. 맑은 소리(주기성 0.85). 에너지 300~800Hz 58%,
                              800~1500Hz 28%, 1500~3000Hz 13%. 공명 봉우리 600·1600·3800Hz.
  긴 울음('아아악', 스페이스 등) 높이 730Hz 인데 **떨림이 한 번 걸러 어긋나서** 365Hz 반음(아래 옥타브)이
                              섞인다 — 쉰 소리. 거칠다(주기성 0.5). 에너지 300~800Hz 50%,
                              800~1500Hz 43%, 1500Hz 위는 거의 없다(어둡다). 처음 0.1초는 높게
                              긁고 들어간다.
"""
import os
import sys
import wave

import numpy as np
from scipy import signal

RATE = 22050
# 앱에는 지금 **녹음**이 들어 있다(HANDOFF). 합성 소리가 앱 소리를 덮어쓰지 않게 따로 뽑는다.
OUT = os.path.join(os.path.dirname(__file__), 'out')


def glottal(f0_curve, amp_curve, rnd, jitter, shimmer, doubling, breath, open_q=0.6, close_q=0.12):
    """
    성대 펄스열(Rosenberg 펄스의 미분). f0_curve·amp_curve 는 샘플마다의 높이·세기.
    doubling 0..1: 펄스를 하나 걸러 약하고 늦게 — 아래 옥타브가 섞인 쉰 소리.
    """
    n = len(f0_curve)
    out = np.zeros(n)
    i, k = 0, 0
    while i < n:
        period = RATE / f0_curve[i] * (1 + rnd.normal(0, jitter))
        amp = amp_curve[i] * max(0.2, 1 + rnd.normal(0, shimmer))
        if k % 2 == 1:
            period *= 1 + 0.15 * doubling
            amp *= 1 - 0.8 * doubling
        p = max(8, int(period))
        on = int(p * open_q)
        close = max(2, int(p * close_q))
        g = np.zeros(p)
        g[:on] = 0.5 * (1 - np.cos(np.pi * np.arange(on) / on))
        g[on:on + close] = np.cos(np.pi / 2 * np.arange(min(close, p - on)) / close)
        d = np.diff(np.concatenate([[0.0], g])) * amp
        # 숨: 성대가 열려 있을 때 더 센 잡음
        d += breath * amp * (0.2 + g) * rnd.uniform(-1, 1, p) * 0.08
        end = min(n, i + p)
        out[i:end] = d[:end - i]
        i += p
        k += 1
    return out


def resonate(x, freq, bw):
    """2차 공명기(포먼트). 고정 주파수. **봉우리 높이를 1 로 맞춘다** — 안 맞추면 높은 공명일수록
    약해져서 크기 숫자가 뜻을 잃는다(첫 시도가 그랬다: 에너지가 전부 800Hz 아래로 갔다)."""
    r = np.exp(-np.pi * bw / RATE)
    theta = 2 * np.pi * freq / RATE
    a = [1, -2 * r * np.cos(theta), r * r]
    _, h = signal.freqz([1], a, worN=[theta])
    return signal.lfilter([1 / abs(h[0])], a, x)


def tract(x, formants):
    """공명기를 나란히 두고 섞는다(병렬). formants 는 (주파수, 대역폭, 크기)."""
    return sum(g * resonate(x, f, bw) for f, bw, g in formants)


def curve(points, n):
    """[(t 0..1, 값)] 을 부드럽게 이은 샘플별 곡선."""
    ts = np.linspace(0, 1, n)
    xs, ys = zip(*points)
    return np.interp(ts, xs, ys)


def shape(x, attack, release, hard=False):
    n = len(x)
    env = np.ones(n)
    a = max(1, int(attack * RATE))
    env[:a] = np.linspace(0, 1, a) ** 0.7
    r = max(1, int(release * RATE))
    env[-r:] = np.linspace(1, 0, r) ** (1.0 if hard else 1.8)
    return x * env


def normalize(x, peak=0.9):
    x = x - np.mean(x)
    return x / (np.max(np.abs(x)) or 1) * peak


def aung(duration, base, seed, lift=1.2):
    """짧은 '아웅'. base 는 머무는 높이(Hz). 녹음의 모양: 치고 들어가 떨어졌다가 끝에서 올라간다."""
    rnd = np.random.default_rng(seed)
    n = int(duration * RATE)
    f0 = curve([(0, base * 1.4), (0.12, base), (0.55, base * 1.02), (0.86, base * lift), (0.9, base * 0.6), (1, base * 0.58)], n)
    amp = curve([(0, 0.4), (0.1, 0.9), (0.6, 0.85), (0.85, 1.25), (0.92, 0.6), (1, 0.2)], n)
    src = glottal(f0, amp, rnd, jitter=0.012, shimmer=0.06, doubling=0.08, breath=2.0)
    # 공명 세기는 녹음의 대역별 에너지에 맞춰 찾은 값이다(맨 위 주석의 표).
    y = tract(src, [(600, 110, 1.0), (1050, 160, 1.5), (1600, 180, 1.0), (3800, 300, 0.3)])
    y = signal.lfilter(*signal.butter(2, 3200 / (RATE / 2)), y)
    return normalize(shape(y, 0.004, 0.012))


def aaak(duration, seed, f0=730):
    """거친 '아아악'. 730Hz 에 한 번 걸러 어긋나는 떨림(365Hz 가 섞인다), 1.5kHz 위는 거의 없다."""
    rnd = np.random.default_rng(seed)
    n = int(duration * RATE)
    head = min(0.3, 0.1 / duration)
    pitch = curve([(0, f0 * 1.0), (head, f0 * 0.98), (1, f0 * 0.99)], n)
    amp = curve([(0, 0.5), (head * 0.4, 1.0), (1, 1.0)], n)
    # 처음 0.1초는 떨림이 덜 어긋나(높게 긁고 들어감), 그 뒤로 쉰 소리가 된다.
    src_a = glottal(pitch, amp, rnd, jitter=0.03, shimmer=0.18, doubling=0.2, breath=10.0)
    src_b = glottal(pitch, amp, rnd, jitter=0.03, shimmer=0.18, doubling=0.7, breath=10.0)
    mix = curve([(0, 0), (head, 0), (head + 0.05, 1), (1, 1)], n)
    src = src_a * (1 - mix) + src_b * mix
    y = tract(src, [(720, 140, 0.6), (1100, 160, 1.3), (1650, 220, 0.3)])
    y = signal.lfilter(*signal.butter(4, 1700 / (RATE / 2)), y)
    return normalize(shape(y, 0.008, 0.035, hard=True))


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + '.wav')
    data = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    print(path, f'{len(x) / RATE:.2f}s')
    return x


def measure(name, x):
    """녹음을 잰 것과 같은 자로 잰다."""
    frame, hop = int(0.025 * RATE), int(0.01 * RATE)
    rms = np.array([np.sqrt(np.mean(x[i:i + frame] ** 2)) for i in range(0, len(x) - frame, hop)])
    f0s, per = [], []
    for k, i in enumerate(range(0, len(x) - frame, hop)):
        if rms[k] < rms.max() * 0.2:
            continue
        fr = x[i:i + frame] * np.hanning(frame)
        ac = np.correlate(fr, fr, 'full')[frame - 1:]
        ac /= ac[0] + 1e-9
        lo, hi = int(RATE / 1200), int(RATE / 80)
        lag = lo + np.argmax(ac[lo:hi])
        f0s.append(RATE / lag)
        per.append(ac[lag])
    f, p = signal.welch(x, RATE, nperseg=1024)
    tot = p.sum()
    band = lambda a, b: 100 * p[(f >= a) & (f < b)].sum() / tot
    print(f'{name:16s} f0 {np.median(f0s):4.0f}Hz  주기성 {np.median(per):.2f}  '
          f'300-800 {band(300, 800):.0f}%  800-1500 {band(800, 1500):.0f}%  1500-3000 {band(1500, 3000):.0f}%')


if __name__ == '__main__':
    sounds = {
        # 글자 키: '아웅' 넷. 머무는 높이를 조금씩 달리한다(녹음은 500Hz 언저리).
        'sealion_key_1': aung(0.14, 505, seed=1),
        'sealion_key_2': aung(0.13, 540, seed=2, lift=1.15),
        'sealion_key_3': aung(0.15, 480, seed=3, lift=1.25),
        'sealion_key_4': aung(0.14, 520, seed=4),
        # 스페이스·지우기·엔터: '아아악'
        'sealion_space': aaak(0.34, seed=11),
        'sealion_delete': aaak(0.24, seed=12, f0=700),
        'sealion_enter': aaak(0.55, seed=13, f0=745),
    }
    for name, x in sounds.items():
        save(name, x)
    if '--measure' in sys.argv:
        print('\n목표  아웅: f0 ~520  주기성 0.85  58/28/13%   아아악: f0 365(반음 섞임)  주기성 0.5  50/43/6%')
        for name, x in sounds.items():
            measure(name, x)
