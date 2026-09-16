package com.spellkeyboard.desktop.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 순수 계산부 시험. 화면도 문서도 없다.
 *
 * 여기서 틀리면 글자가 사라지거나 커서가 튄다. 그래서 경계 하나, 오프셋 하나까지 못 박는다.
 */
class LiveTailPlanTest {

    // ---- 어절 경계 ----

    @Test
    fun `공백과 문장부호가 어절을 끝낸다`() {
        for (c in listOf(' ', '\t', '\n', '\r', '.', ',', '!', '?', ';', ':')) {
            assertTrue(isBoundaryChar(c), "경계여야 한다: [$c]")
        }
        for (c in listOf('가', 'a', '1', 'ㄱ', '-', '(')) {
            assertFalse(isBoundaryChar(c), "경계가 아니어야 한다: [$c]")
        }
    }

    @Test
    fun `커서 오른쪽이 경계가 아니면 어절이 끝난 것이 아니다`() {
        // 문서 끝. 타자 중에는 언제나 이 경우다.
        assertTrue(caretEndsWord(null))
        // 공백·부호 앞. 남의 낱말을 가를 일이 없다.
        for (c in listOf(' ', '\n', '\t', '.', ',', '!', '?')) {
            assertTrue(caretEndsWord(c), "경계 앞이어야 한다: [$c]")
        }
        // 낱말 **안**. 여기서 고치면 사용자가 손대지도 않은 낱말이 쪼개진다.
        for (c in listOf('세', '가', 'a', '1')) {
            assertFalse(caretEndsWord(c), "낱말 안이어야 한다: [$c]")
        }
    }

    @Test
    fun `한글이 없으면 엔진을 부르지 않는다`() {
        assertNull(planTail("hello ", 6, pause = false))
        assertNull(planTail("123, ", 5, pause = false))
        assertNotNull(planTail("가 ", 2, pause = false))
        // 낱자모도 한글이다 — 조합이 깨진 채 확정된 글도 고칠 거리가 된다.
        assertNotNull(planTail("ㄱㄴ ", 3, pause = false))
    }

    // ---- 창 자르기: 엔진의 windowStart 와 같아야 한다 ----

    @Test
    fun `창은 뒤에서 세 어절이다`() {
        assertEquals(0, windowStart("가나", 3))
        assertEquals(0, windowStart("하나 둘 셋", 3))
        assertEquals(2, windowStart("영 하나 둘 셋", 3))
        assertEquals(7, windowStart("영 하나 둘 셋 넷 다섯", 3))
        // 뒤쪽 공백은 어절로 세지 않는다.
        assertEquals(0, windowStart("하나 둘 셋   ", 3))
        // 어절 사이 공백이 여러 개여도 어절 수는 그대로다.
        assertEquals(3, windowStart("영  하나  둘  셋", 3))
        assertEquals(0, windowStart("", 3))
        assertEquals(0, windowStart("   ", 3))
    }

    // ---- 계획: 경계 방아쇠 ----

    @Test
    fun `공백 방아쇠는 공백을 구간 밖에 남긴다`() {
        // "되요 " 를 쳤다. 커서는 3. 고칠 구간은 [0,2) — 공백은 건드리지 않는다.
        val plan = assertNotNull(planTail("되요 ", 3, pause = false))
        assertEquals(Trigger.SPACE, plan.trigger)
        assertEquals(0, plan.start)
        assertEquals(2, plan.end)
        assertEquals("되요", plan.window)
    }

    @Test
    fun `문장부호와 줄바꿈도 어절을 끝낸다`() {
        assertEquals(Trigger.TERMINATOR, assertNotNull(planTail("되요.", 3, false)).trigger)
        assertEquals("되요", assertNotNull(planTail("되요.", 3, false)).window)
        assertEquals(Trigger.NEWLINE, assertNotNull(planTail("되요\n", 3, false)).trigger)
        assertEquals("되요", assertNotNull(planTail("되요\n", 3, false)).window)
    }

    @Test
    fun `어절 한가운데서는 아무 일도 없다`() {
        assertNull(planTail("되요", 2, pause = false))
        assertNull(planTail("안녕하세", 4, pause = false))
        assertNull(planTail("", 0, pause = false))
    }

    @Test
    fun `창은 커서 앞 세 어절까지만 잡는다`() {
        val before = "영 하나 둘 셋 "
        val plan = assertNotNull(planTail(before, before.length, false))
        assertEquals("하나 둘 셋", plan.window)
        assertEquals(before.indexOf("하나"), plan.start)
        assertEquals(before.length - 1, plan.end)
    }

    @Test
    fun `문서 한가운데에서도 오프셋이 맞는다`() {
        // 커서가 40 인데 앞 글자는 5개만 읽어 왔다. 오프셋은 읽기 시작한 자리(35)를 더한 값이다.
        val plan = assertNotNull(planTail("가 되요 ", 40, pause = false))
        assertEquals(35, plan.start)
        assertEquals(39, plan.end)
        assertEquals("가 되요", plan.window)
    }

    // ---- 계획: 멈춤 방아쇠 ----

    @Test
    fun `멈춤은 아직 안 끝난 어절까지 본다`() {
        val plan = assertNotNull(planTail("되요", 2, pause = true))
        assertEquals(Trigger.PAUSE, plan.trigger)
        assertEquals(0, plan.start)
        assertEquals(2, plan.end)
        assertEquals("되요", plan.window)
    }

    @Test
    fun `공백으로 끝났으면 멈춤은 경계 방아쇠와 같은 구간을 낸다`() {
        val boundary = assertNotNull(planTail("되요 ", 3, pause = false))
        val paused = assertNotNull(planTail("되요 ", 3, pause = true))
        assertTrue(paused.sameTarget(boundary), "같은 자리를 두 번 묻지 않아야 한다")
    }

    @Test
    fun `공백뿐이면 멈춤도 할 일이 없다`() {
        assertNull(planTail("   ", 3, pause = true))
        assertNull(planTail("", 0, pause = true))
    }

    // ---- 앞 문맥 ----

    @Test
    fun `창 앞이 비었고 다 읽었으면 문장 첫머리다`() {
        assertEquals("<s>", assertNotNull(planTail("되요 ", 3, false)).contextBefore)
    }

    @Test
    fun `잘라 읽었으면 앞을 모른다고 한다`() {
        // 커서 40, 읽은 것은 3글자 → 앞에 글이 더 있다. 첫머리라고 하면 안 된다.
        assertNull(assertNotNull(planTail("되요 ", 40, false)).contextBefore)
    }

    @Test
    fun `창 앞 어절이 한글이면 그것을 문맥으로 준다`() {
        val before = "영 하나 둘 셋 "
        assertEquals("영", assertNotNull(planTail(before, before.length, false)).contextBefore)
    }

    @Test
    fun `앞 어절이 마침표로 끝나면 문장 첫머리다`() {
        val before = "끝. 하나 둘 셋 "
        assertEquals("<s>", assertNotNull(planTail(before, before.length, false)).contextBefore)
    }

    @Test
    fun `앞 어절이 한글이 아니면 모름이다`() {
        val before = "abc 하나 둘 셋 "
        assertNull(assertNotNull(planTail(before, before.length, false)).contextBefore)
    }

    // ---- 무엇이 달라졌나 ----

    @Test
    fun `바뀐 토막만 잘라 낸다`() {
        assertEquals("되요" to "돼요", changedSpan("되요", "돼요"))
        // 앞뒤로 안 바뀐 어절은 벗겨 낸다. 창이 세 어절이라 안 벗기면 한 줄에 안 들어간다.
        assertEquals(
            "아버지가방에들어가신다" to "아버지가 방에 들어가신다",
            changedSpan("그렇게하면 돼요 아버지가방에들어가신다", "그렇게하면 돼요 아버지가 방에 들어가신다"),
        )
        // 붙은 것을 가른 경우도 한 덩어리로 묶인다.
        assertEquals("할수있다" to "할 수 있다", changedSpan("할수있다", "할 수 있다"))
        // 가운데만 바뀌었으면 앞뒤를 다 벗긴다.
        assertEquals("되요" to "돼요", changedSpan("어제 되요 오늘", "어제 돼요 오늘"))
    }

    @Test
    fun `안 바뀌었으면 보여 줄 것이 없다`() {
        assertNull(changedSpan("안녕하세요", "안녕하세요"))
        assertNull(changedSpan("안녕 하세요", " 안녕  하세요 "))
    }

    // ---- 커서 셈 ----

    @Test
    fun `구간 뒤의 커서는 길이 차이만큼 밀린다`() {
        // [0,4) 를 6글자로 바꿨다. 커서 9 → 11.
        assertEquals(11, caretAfterSplice(caret = 9, start = 0, end = 4, replacementLength = 6))
        // 줄어드는 쪽도 같다.
        assertEquals(7, caretAfterSplice(caret = 9, start = 0, end = 4, replacementLength = 2))
        // 구간 끝에 붙어 있던 커서도 뒤에 있는 것으로 센다.
        assertEquals(6, caretAfterSplice(caret = 4, start = 0, end = 4, replacementLength = 6))
    }

    @Test
    fun `구간 앞의 커서는 움직이지 않는다`() {
        assertEquals(2, caretAfterSplice(caret = 2, start = 5, end = 9, replacementLength = 1))
        assertEquals(5, caretAfterSplice(caret = 5, start = 5, end = 9, replacementLength = 1))
    }

    @Test
    fun `구간 안의 커서는 갈아 끼운 글 끝으로 간다`() {
        assertEquals(8, caretAfterSplice(caret = 6, start = 2, end = 9, replacementLength = 6))
    }

    // ---- 같은 요청 가려내기 ----

    @Test
    fun `자리와 글이 같으면 같은 요청이다`() {
        val a = TailPlan(Trigger.SPACE, 0, 2, "되요", null)
        assertTrue(a.sameTarget(TailPlan(Trigger.PAUSE, 0, 2, "되요", "<s>")))
        assertFalse(a.sameTarget(TailPlan(Trigger.SPACE, 1, 2, "되요", null)))
        assertFalse(a.sameTarget(TailPlan(Trigger.SPACE, 0, 2, "돼요", null)))
        assertFalse(a.sameTarget(null))
    }
}
