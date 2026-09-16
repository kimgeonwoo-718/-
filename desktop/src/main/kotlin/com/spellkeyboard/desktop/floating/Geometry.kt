package com.spellkeyboard.desktop.floating

/**
 * 떠 있는 창의 자리 계산에 쓰는 아주 작은 기하 타입들.
 *
 * ## 왜 `java.awt.Rectangle` 을 안 쓰는가
 *
 * 자리 계산은 **화면 없이 시험되어야 한다.** AWT 타입을 쓰면 시험이 언제라도
 * `GraphicsEnvironment` 를 건드리는 쪽으로 슬금슬금 번져 가고, 그러면 headless 에서
 * 터진다. 여기에 AWT import 가 하나도 없으면 그 길이 아예 막힌다 —
 * AWT 와의 환전은 [AwtScreens] 한 곳에서만 한다.
 */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom

    /** 겹치는 넓이. 0 이면 안 겹친다. 어느 모니터에 얹힌 창인지 고를 때 쓴다. */
    fun overlap(other: Rect): Long {
        val w = (minOf(right, other.right) - maxOf(x, other.x)).coerceAtLeast(0)
        val h = (minOf(bottom, other.bottom) - maxOf(y, other.y)).coerceAtLeast(0)
        return w.toLong() * h.toLong()
    }

    /** [px],[py] 에서 이 사각형까지의 거리의 제곱. 안에 있으면 0. */
    fun distanceSquared(px: Int, py: Int): Long {
        val dx = when {
            px < x -> (x - px).toLong()
            px >= right -> (px - right + 1).toLong()
            else -> 0L
        }
        val dy = when {
            py < y -> (y - py).toLong()
            py >= bottom -> (py - bottom + 1).toLong()
            else -> 0L
        }
        return dx * dx + dy * dy
    }

    fun at(nx: Int, ny: Int): Rect = Rect(nx, ny, width, height)

    /**
     * 창 전체가 [area] 안에 들어오도록 민다.
     *
     * 창이 작업 영역보다 크면 **왼쪽 위에 붙인다.** 오른쪽 아래에 붙이면 제목줄이
     * 화면 밖으로 나가 사용자가 창을 잡을 수 없게 된다.
     */
    fun clampInto(area: Rect): Rect {
        val nx = if (width >= area.width) area.x else x.coerceIn(area.x, area.right - width)
        val ny = if (height >= area.height) area.y else y.coerceIn(area.y, area.bottom - height)
        return at(nx, ny)
    }

    /** `x,y,w,h`. [Preferences] 에 적을 때 쓴다. */
    fun encode(): String = "$x,$y,$width,$height"

    companion object {
        /**
         * 적어 둔 값을 되읽는다. **절대 던지지 않는다** — 설정 파일이 상한 것 때문에
         * 프로그램이 안 뜨면 사용자는 고칠 방법이 없다. 이상하면 없던 것으로 친다.
         */
        fun decode(text: String?): Rect? {
            val parts = text?.split(',') ?: return null
            if (parts.size != 4) return null
            val n = parts.map { it.trim().toIntOrNull() ?: return null }
            if (n[2] <= 0 || n[3] <= 0) return null
            return Rect(n[0], n[1], n[2], n[3])
        }
    }
}

data class Pt(val x: Int, val y: Int)

/**
 * 모니터 하나.
 *
 * [bounds] 와 [workArea] 를 굳이 둘 다 든다. 작업 표시줄이 [workArea] 를 깎기 때문이다 —
 * 이 기계에서 1920x1080 짜리 주 모니터의 작업 영역은 1920x1032 다. 떠 있는 창을
 * [bounds] 기준으로 놓으면 작업 표시줄 밑에 깔린다.
 */
data class ScreenBox(
    val id: String,
    val bounds: Rect,
    val workArea: Rect,
    val primary: Boolean,
)
