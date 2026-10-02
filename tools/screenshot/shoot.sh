#!/usr/bin/env bash
# 에뮬레이터에서 첫 화면을 여러 크기로 캡처한다(.github/workflows/screenshot.yml 이 부른다).
#
# 크기:
#   user   988x2000, 384dpi(=412x833dp) — 사용자 폰 스크린숏과 같은 크기
#   small  720x1520, 320dpi(=360x760dp) — 작은 폰
#   tall   1080x2400, 420dpi(=411x914dp) — 긴 폰
# 키보드를 켜고 고른 상태("키보드를 쓰고 있어요")와 아직 안 켠 상태를 다 찍는다.
set -uo pipefail
PKG=com.spellkeyboard.ko
IME=$PKG/.SpellKeyboardService
mkdir -p shots
adb install -r "$(find keyboard/build/outputs/apk/debug -name '*.apk' | head -n 1)"

shot() { # 이름
  adb shell am force-stop $PKG
  adb shell am start -W -n $PKG/.SetupActivity >/dev/null
  sleep 4
  adb exec-out screencap -p > "shots/$1.png"
  echo "찍음: $1 ($(stat -c %s "shots/$1.png") bytes)"
}

size() { adb shell wm size "$1"; adb shell wm density "$2"; sleep 2; }

# 1) 키보드를 아직 안 켠 상태
size 988x2000 384; shot home-user-fresh

# 2) 키보드를 켜고 고른 상태
adb shell ime enable $IME
adb shell ime set $IME
size 988x2000 384; shot home-user
size 720x1520 320; shot home-small
size 1080x2400 420; shot home-tall

adb shell wm size reset
adb shell wm density reset
ls -l shots
