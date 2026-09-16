package com.spellkeyboard.desktop.floating

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * AWT 와 [ScreenBox] 사이의 **유일한 환전소.**
 *
 * 여기 있는 것은 화면이 있어야 돌아간다. 그래서 자리 계산은 한 줄도 두지 않았다 —
 * 계산은 전부 [placeFloating] 에 있고 그쪽은 화면 없이 시험된다.
 */
object AwtScreens {

    /**
     * 붙어 있는 모니터 전부.
     *
     * 작업 영역은 `bounds - screenInsets` 다. 작업 표시줄이 어디 붙어 있든(아래·위·옆)
     * 이 한 줄이 알아서 빼 준다. 모니터마다 따로 물어봐야 한다 — 표시줄은 한 모니터에만
     * 있기 때문이다.
     *
     * 실패하면 **빈 목록**을 준다. 던지지 않는다: 모니터를 뽑는 순간 단축키를 누르면
     * `getScreenDevices` 가 터질 수 있고, 그 때문에 창이 안 뜨면 안 된다.
     */
    fun all(): List<ScreenBox> = runCatching {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = runCatching { env.defaultScreenDevice }.getOrNull()
        env.screenDevices.map { device ->
            val gc = device.defaultConfiguration
            val b = gc.bounds
            val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
            val work = if (insets == null) b else Rectangle(
                b.x + insets.left,
                b.y + insets.top,
                b.width - insets.left - insets.right,
                b.height - insets.top - insets.bottom,
            )
            ScreenBox(
                id = device.iDstring,
                bounds = b.toRect(),
                workArea = work.toRect(),
                primary = device == primary,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 마우스 위치. **null 이 정상적으로 나온다** — 화면이 잠겼거나, 마우스가 없거나,
     * 원격 세션이 끊겼을 때. 받는 쪽([placeFloating])이 그 경우를 안다.
     */
    fun pointer(): Pt? = runCatching {
        MouseInfo.getPointerInfo()?.location?.let { Pt(it.x, it.y) }
    }.getOrNull()
}

internal fun Rectangle.toRect(): Rect = Rect(x, y, width, height)
