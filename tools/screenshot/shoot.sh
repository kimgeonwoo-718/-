#!/usr/bin/env bash
# 에뮬레이터에서 첫 화면을 여러 크기로 캡처한다(.github/workflows/screenshot.yml 이 부른다).
#
# 크기:
#   user   988x2000, 384dpi(=412x833dp) — 사용자 폰 스크린숏과 같은 크기
#   small  720x1520, 320dpi(=360x760dp) — 작은 폰
#   tall   1080x2400, 420dpi(=411x914dp) — 긴 폰
#
# 사용자 폰과 같은 상태로 찍는다: 첫 실행 안내(코치)는 이미 봤고, 키보드는 켜고 골랐고, 구독자다.
# 첫 판은 안내 화면과 "Pixel Launcher isn't responding" 창이 덮어서 아무것도 못 봤다(2026-10-02).
set -uo pipefail
PKG=com.spellkeyboard.ko
IME=$PKG/.SpellKeyboardService
mkdir -p shots

adb install -r "$(find keyboard/build/outputs/apk/debug -name '*.apk' | head -n 1)"

# 부팅 직후 런처가 한동안 버벅이며 "응답 없음" 창을 띄운다. 가라앉을 때까지 기다리고, 떠 있으면 닫는다.
sleep 25
dismiss_dialogs() {
  for _ in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 0
    xml=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
    # "Wait" 단추의 가운데를 누른다. 없으면 끝.
    b=$(printf '%s' "$xml" | grep -o 'text="Wait"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -n 1)
    [ -z "$b" ] && return 0
    read -r x1 y1 x2 y2 <<<"$(echo "$b" | tr '[],' '   ')"
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
    echo "응답 없음 창을 닫았다"
    sleep 2
  done
}
dismiss_dialogs

# 앱 언어를 한국어로 — 에뮬레이터는 영어라 한국어 어절 줄바꿈(lineBreakWordStyle=phrase)이 안 먹는다.
# 사용자 폰은 한국어다. (안드로이드 13+ 앱별 언어)
adb shell cmd locale set-app-locales $PKG --locales ko-KR || true

# 앱 설정: 안내는 이미 봤고, 서버가 마지막에 구독자라고 했다(사용자 폰과 같게 '프리미엄 · 이용 중').
adb shell am start -W -n $PKG/.SetupActivity >/dev/null; sleep 2; adb shell am force-stop $PKG
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/spell_keyboard.xml'" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarding_seen" value="true" />
    <string name="quota_plan">subscriber</string>
</map>
XML
adb shell run-as $PKG cat shared_prefs/spell_keyboard.xml

shot() { # 이름 [am start 에 더 붙일 것]
  adb shell am force-stop $PKG
  dismiss_dialogs
  adb shell am start -W -n $PKG/.SetupActivity "${@:2}" >/dev/null
  sleep 4
  dismiss_dialogs
  adb exec-out screencap -p > "shots/$1.png"
  echo "찍음: $1 ($(stat -c %s "shots/$1.png") bytes)"
}
size() { adb shell wm size "$1"; adb shell wm density "$2"; sleep 3; }

# 1) 키보드를 아직 안 켠 상태(처음 깐 사람)
size 988x2000 384; shot home-user-fresh

# 2) 키보드를 켜고 고른 상태(사용자 폰).
#    에뮬레이터(x86)에서는 키보드 엔진(arm 전용)이 못 떠서 시스템이 기본 입력기를 되돌린다 — 앱이 "고르지
#    않음" 으로 본다. 그래서 디버그 빌드에만 있는 스위치(screenshot_setup_done)로 "마친 화면" 을 띄운다.
adb shell ime enable $IME
DONE=(--ez screenshot_setup_done true)
size 988x2000 384; shot home-user "${DONE[@]}"
size 720x1520 320; shot home-small "${DONE[@]}"
size 1080x2400 420; shot home-tall "${DONE[@]}"

adb shell wm size reset
adb shell wm density reset
ls -l shots
