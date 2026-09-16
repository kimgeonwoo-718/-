package com.spellkeyboard.desktop.shell

import com.spellkeyboard.desktop.koreanFont
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * 알림 영역 아이콘을 **코드로 그린다.**
 *
 * ## 왜 그림 파일을 안 쓰는가
 *
 * 하나 쓰면 16, 20, 24, 32px 짜리를 다 넣어야 한다 — 알림 영역 크기는 화면 배율에
 * 따라 달라진다. 그리고 jar 안의 그림은 `packageWindows` 로 묶을 때 빠뜨리기 쉬운
 * 종류의 것이다(빠져도 빌드는 되고, 실행할 때 아이콘 자리가 빈다). 글자 하나를
 * 그리는 것이라 코드가 여남은 줄이면 끝나고, 어떤 크기든 선명하다.
 */
object TrayIconArt {

    /** 켜졌을 때. 교정기가 살아 있다는 뜻. */
    private val ON_BACKGROUND = Color(0x2C, 0x5F, 0xA8)

    /** 실시간 교정을 껐을 때. **한눈에 달라 보여야 한다** — 껐다는 것을 잊고
     *  "왜 안 고쳐지지" 하는 것이 가장 흔한 고장 신고다. */
    private val OFF_BACKGROUND = Color(0x78, 0x7C, 0x82)

    private val FOREGROUND = Color.WHITE

    /**
     * `가` 한 글자. 이 프로그램이 다루는 것이 한글이라는 말을 가장 짧게 한 것.
     *
     * @param size 한 변. 알림 영역이 알려 준 크기를 그대로 쓴다.
     * @param live 실시간 교정이 켜져 있는가.
     */
    fun render(size: Int, live: Boolean): BufferedImage {
        val n = size.coerceIn(8, 256)
        val image = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // 둥근 네모를 꽉 채우지 않고 한 픽셀 남긴다. 밝은 작업 표시줄에서
            // 가장자리가 잘려 보이는 것을 막는다.
            val pad = (n * 0.04f).toInt()
            val arc = (n * 0.28f).toInt()
            g.color = if (live) ON_BACKGROUND else OFF_BACKGROUND
            g.fillRoundRect(pad, pad, n - pad * 2, n - pad * 2, arc, arc)

            val text = "가"
            g.color = FOREGROUND
            g.font = fitFont(n)
            // FontMetrics 의 높이가 아니라 **글자의 실제 테두리**로 가운데를 잡는다.
            // 한글은 글꼴이 잡아 둔 줄 사이 여백이 커서, ascent/descent 로 맞추면
            // 눈에 띄게 위로 붙는다.
            val bounds = g.fontMetrics.getStringBounds(text, g).bounds
            val x = (n - bounds.width) / 2 - bounds.x
            val y = (n - bounds.height) / 2 - bounds.y
            g.drawString(text, x, y)
        } finally {
            g.dispose() // Graphics2D 를 안 놓으면 네이티브 자원이 샌다.
        }
        return image
    }

    private fun fitFont(n: Int): Font =
        koreanFont((n * 0.72f).toInt().coerceAtLeast(8)).deriveFont(Font.BOLD)
}
