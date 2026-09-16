package com.spellkeyboard.desktop.floating

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자리 잡기. **화면 없이 돈다**(`java.awt.headless=true` 로 시험한다).
 *
 * 모니터 배치는 이 기계에서 실제로 읽은 값이다:
 *
 *     DISPLAY3  x=-1920  1920x1080  작업영역 1920x1032
 *     DISPLAY2  x=0      1920x1080  작업영역 1920x1032  (주)
 *     DISPLAY1  x=+1920  1536x864   작업영역 1536x816   (배율이 달라 논리 크기가 작다)
 */
class PlacementTest {

    private val size = Rect(0, 0, 336, 324)

    private val left = ScreenBox("D3", Rect(-1920, 0, 1920, 1080), Rect(-1920, 0, 1920, 1032), false)
    private val main = ScreenBox("D2", Rect(0, 0, 1920, 1080), Rect(0, 0, 1920, 1032), true)
    private val right = ScreenBox("D1", Rect(1920, 0, 1536, 864), Rect(1920, 0, 1536, 816), false)
    private val screens = listOf(left, main, right)

    private fun place(
        policy: PlacementPolicy = PlacementPolicy.FOLLOW_POINTER,
        pointer: Pt? = null,
        remembered: Rect? = null,
        on: List<ScreenBox> = screens,
    ) = placeFloating(policy, size, on, pointer, remembered)

    // ---- 마우스 따라가기 ----

    @Test
    fun `마우스가 있는 모니터에 뜬다`() {
        assertEquals(main, screenOf(place(pointer = Pt(1000, 500))))
        assertEquals(left, screenOf(place(pointer = Pt(-900, 500))))
        assertEquals(right, screenOf(place(pointer = Pt(2600, 400))))
    }

    @Test
    fun `음수 좌표 모니터에서도 창 전체가 그 모니터 안에 있다`() {
        val r = place(pointer = Pt(-1800, 900))
        assertTrue(r.x >= left.workArea.x, "왼쪽으로 삐져나갔다: $r")
        assertTrue(r.right <= left.workArea.right, "오른쪽으로 삐져나갔다: $r")
    }

    @Test
    fun `가로 가운데 세로는 남는 자리의 삼분의 일 지점`() {
        val r = place(pointer = Pt(100, 100))
        assertEquals(0 + (1920 - 336) / 2, r.x)
        assertEquals(0 + (1032 - 324) / 3, r.y)
    }

    @Test
    fun `작업 표시줄 아래로 내려가지 않는다`() {
        // 작업 영역 높이가 1032 이므로 창 아래끝이 1032 를 넘으면 표시줄에 깔린다.
        for (p in listOf(Pt(10, 10), Pt(1900, 1070), Pt(-10, 1070), Pt(3400, 860))) {
            val r = place(pointer = p)
            val work = screenOf(r).workArea
            assertTrue(r.bottom <= work.bottom, "포인터 $p 에서 표시줄 아래로 갔다: $r")
            assertTrue(r.y >= work.y, "포인터 $p 에서 위로 삐져나갔다: $r")
        }
    }

    @Test
    fun `모니터 사이 빈 좌표면 가장 가까운 모니터를 고른다`() {
        // 오른쪽 모니터는 높이가 864 뿐이라 y=1000 은 어느 모니터에도 안 들어간다.
        val r = place(pointer = Pt(2600, 1000))
        assertEquals(right, screenOf(r))
    }

    @Test
    fun `마우스를 못 읽으면 주 모니터로 간다`() {
        assertEquals(main, screenOf(place(pointer = null)))
    }

    @Test
    fun `옮겨 둔 자리는 다른 모니터에서도 같은 비율로 따라간다`() {
        // 주 모니터 오른쪽 아래 구석에 붙여 둔 창.
        val parked = Rect(1920 - 336, 1032 - 324, 336, 324)
        val r = place(pointer = Pt(2600, 400), remembered = parked)
        assertEquals(right, screenOf(r))
        // 작은 모니터에서도 오른쪽 아래 구석이어야 한다. 절대 좌표를 그대로 썼다면
        // x=1584 라 화면 밖으로 나갔을 것이다.
        assertEquals(right.workArea.right, r.right)
        assertEquals(right.workArea.bottom, r.bottom)
        assertEquals(right.workArea.right - 336, r.x)
    }

    @Test
    fun `옮겨 둔 자리가 왼쪽 위면 어디서든 왼쪽 위다`() {
        val parked = Rect(0, 0, 336, 324)
        val r = place(pointer = Pt(-900, 500), remembered = parked)
        assertEquals(left.workArea.x, r.x)
        assertEquals(left.workArea.y, r.y)
    }

    // ---- 자리 기억하기 ----

    @Test
    fun `기억하기는 마우스가 어디 있든 그 자리에 뜬다`() {
        val parked = Rect(-1500, 700, 336, 324)
        val r = place(PlacementPolicy.REMEMBER, pointer = Pt(1000, 500), remembered = parked)
        assertEquals(parked, r)
    }

    @Test
    fun `모니터를 빼면 기억한 자리를 버리고 마우스 쪽으로 데려온다`() {
        val parked = Rect(-1500, 700, 336, 324)   // 이제 없는 왼쪽 모니터
        val r = placeFloating(
            PlacementPolicy.REMEMBER, size, listOf(main, right), Pt(2600, 400), parked,
        )
        assertEquals(right, screenOf(r, listOf(main, right)))
    }

    @Test
    fun `기억한 자리가 표시줄에 걸치면 밀어 올린다`() {
        val parked = Rect(100, 1000, 336, 324)    // 아래끝 1324 > 작업영역 1032
        val r = place(PlacementPolicy.REMEMBER, remembered = parked)
        assertEquals(1032 - 324, r.y)
        assertEquals(100, r.x)
    }

    @Test
    fun `한 번도 안 옮겼으면 기억하기도 마우스 모니터에 뜬다`() {
        val r = place(PlacementPolicy.REMEMBER, pointer = Pt(-900, 500), remembered = null)
        assertEquals(left, screenOf(r))
    }

    // ---- 주 모니터 가운데 ----

    @Test
    fun `가운데는 마우스를 무시한다`() {
        val r = place(PlacementPolicy.CENTER_PRIMARY, pointer = Pt(2600, 400))
        assertEquals(main, screenOf(r))
        assertEquals((1920 - 336) / 2, r.x)
        assertEquals((1032 - 324) / 2, r.y)
    }

    // ---- 안 죽기 ----

    @Test
    fun `모니터 목록이 비어도 터지지 않는다`() {
        val r = placeFloating(PlacementPolicy.FOLLOW_POINTER, size, emptyList(), Pt(0, 0), null)
        assertEquals(336, r.width)
        assertEquals(324, r.height)
    }

    @Test
    fun `창이 모니터보다 크면 왼쪽 위에 붙인다`() {
        val tiny = ScreenBox("tiny", Rect(0, 0, 200, 150), Rect(0, 0, 200, 120), true)
        val r = placeFloating(PlacementPolicy.FOLLOW_POINTER, size, listOf(tiny), Pt(10, 10), null)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
    }

    // ---- 적어 두기 ----

    @Test
    fun `자리를 적었다 되읽는다`() {
        val r = Rect(-1500, 700, 336, 324)
        assertEquals(r, Rect.decode(r.encode()))
    }

    @Test
    fun `상한 설정값은 없던 것으로 친다`() {
        for (bad in listOf(null, "", "1,2,3", "a,b,c,d", "1,2,0,324", "1,2,336,-1", "1,2,3,4,5")) {
            assertNull(Rect.decode(bad), "이것을 받아 주면 안 된다: $bad")
        }
    }

    @Test
    fun `모르는 정책 이름은 기본값으로 떨어진다`() {
        assertEquals(PlacementPolicy.FOLLOW_POINTER, PlacementPolicy.decode(null))
        assertEquals(PlacementPolicy.FOLLOW_POINTER, PlacementPolicy.decode("CENTER_ON_MOON"))
        assertEquals(PlacementPolicy.REMEMBER, PlacementPolicy.decode("REMEMBER"))
    }

    private fun screenOf(r: Rect, on: List<ScreenBox> = screens): ScreenBox =
        on.maxByOrNull { it.bounds.overlap(r) }!!
}
