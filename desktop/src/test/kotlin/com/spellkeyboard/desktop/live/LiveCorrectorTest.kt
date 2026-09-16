package com.spellkeyboard.desktop.live

import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.core.correct.CorrectionResult
import java.awt.event.KeyEvent
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 부품 시험. 화면은 없지만 **진짜 `JTextArea`, 진짜 `Document`, 진짜 EDT** 다.
 *
 * 헤드리스에서도 텍스트 영역의 모델과 캐럿은 그대로 산다. 그래서 이 전략의 어려운 곳 —
 * 재진입, 비동기 경합, 커서 셈, 토글, 붙여넣기 구분 — 을 화면 없이 그대로 몰아붙일 수 있다.
 * 모든 조작은 실제와 같이 EDT 에서 한다.
 */
/** EDT 에서 돌리고 결과를 가져온다. 시험 코드도 실제와 같은 스레드에 있어야 한다. */
private fun <T> edt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var out: Result<T>? = null
    SwingUtilities.invokeAndWait { out = runCatching(block) }
    return out!!.getOrThrow()
}

/** 밀려 있는 invokeLater 일감을 다 흘려보낸다. 일감이 또 일감을 낳을 수 있어 여러 번 돈다. */
private fun pump(rounds: Int = 6) {
    repeat(rounds) { SwingUtilities.invokeAndWait { } }
}

private fun waitUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        pump(2)
        if (condition()) return
        Thread.sleep(20)
    }
    pump()
}

class LiveCorrectorTest {

    /** 창을 열지 않고도 도는 시험판. 멈춤 타이머는 기본적으로 안 울리게 멀리 밀어 둔다. */
    private class Bench(
        answers: Map<String, String> = emptyMap(),
        pauseMillis: Int = 60_000,
    ) {
        val area = JTextArea()
        val fake = FakeCorrector(answers)
        val reports = mutableListOf<Report>()
        val live: LiveCorrector = edt {
            LiveCorrector.attach(area, fake, pauseMillis).also { it.onReport = { r -> reports += r } }
        }

        val text: String get() = edt { area.text }
        val caret: Int get() = edt { area.caretPosition }

        /** 한 글자씩 실제 타자처럼 넣는다. IME 확정도 문서에는 이렇게 도착한다. */
        fun type(text: String) {
            for (c in text) {
                edt {
                    val at = area.caretPosition
                    area.document.insertString(at, c.toString(), null)
                    area.caretPosition = at + 1
                }
                pump()
            }
        }

        /** 붙여넣기. 한 번에 통째로 들어온다 — 타자와 구분되는 유일한 표지다. */
        fun paste(text: String) {
            edt {
                val at = area.caretPosition
                area.document.insertString(at, text, null)
                area.caretPosition = at + text.length
            }
            pump()
        }

        /** 문서 어디에든 통째로 밀어 넣는다. 붙여넣기·프로그램 편집이 이 모양으로 도착한다. */
        fun insertAt(offset: Int, text: String) {
            edt { area.document.insertString(offset, text, null) }
            pump()
        }

        /** 커서를 옮긴다. 사용자가 글 가운데를 클릭한 것과 같다. */
        fun caretTo(offset: Int) {
            edt { area.caretPosition = offset }
            pump()
        }

        /** 백스페이스가 눌렸다. true 면 우리가 먹은 것이다. */
        fun backspace(): Boolean = edt { live.backspacePressed() }.also { pump() }

        val applied: List<Report.Applied> get() = reports.filterIsInstance<Report.Applied>()
        val dropped: List<Report.Dropped> get() = reports.filterIsInstance<Report.Dropped>()
    }

    /** 답을 표에서 찾아 주는 가짜 엔진. [defer] 를 켜면 답을 손에 쥐고 있는다. */
    private class FakeCorrector(private val answers: Map<String, String>) : TailCorrector {
        val asked = mutableListOf<String>()
        var defer = false
        private val held = ArrayDeque<Pair<String, (CorrectionResult) -> Unit>>()

        override fun correct(window: String, contextBefore: String?, onResult: (CorrectionResult) -> Unit) {
            asked += window
            if (defer) held += window to onResult else onResult(answer(window))
        }

        /** 쥐고 있던 답을 전부 돌려준다. 실제로는 일꾼 스레드가 늦게 돌아오는 상황이다. */
        fun release() {
            while (held.isNotEmpty()) {
                val (window, callback) = held.removeFirst()
                edt { callback(answer(window)) }
                pump()
            }
        }

        private fun answer(window: String): CorrectionResult {
            val fixed = answers[window] ?: window
            val list = if (fixed == window) emptyList() else listOf(Correction(window, fixed, "시험"))
            return CorrectionResult(window, fixed, list)
        }
    }

    // ---- 기본 ----

    @Test
    fun `공백을 치면 앞 어절이 고쳐지고 공백은 그대로 남는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요 ")
        assertEquals("돼요 ", b.text)
        assertEquals(3, b.caret)
        assertEquals(listOf("되요"), b.fake.asked)
        assertEquals(Trigger.SPACE, b.applied.single().trigger)
    }

    @Test
    fun `어절 한가운데서는 엔진을 부르지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요")
        assertEquals("되요", b.text)
        assertTrue(b.fake.asked.isEmpty(), "물어본 것이 있다: ${b.fake.asked}")
    }

    @Test
    fun `문장부호도 어절을 끝낸다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요.")
        assertEquals("돼요.", b.text)
        assertEquals(3, b.caret)
    }

    // ---- 남의 낱말은 건드리지 않는다 ----

    @Test
    fun `낱말 한가운데서 친 글은 남의 낱말을 가르지 않는다`() {
        // 실측된 사고 그대로다. 고치기 전에는 "안녕 하돼요 세요 꼬리" 가 나왔다 —
        // 사용자가 손대지도 않은 "안녕하세요" 가 반으로 쪼개졌다.
        val b = Bench(mapOf("안녕하되요" to "안녕 하돼요", "되요" to "돼요"))
        b.paste("안녕하세요 꼬리")
        b.caretTo(3)                       // 하와 세 사이를 클릭했다
        b.type("되요 ")
        assertEquals("안녕하되요 세요 꼬리", b.text)
        assertTrue(b.fake.asked.isEmpty(), "낱말 안에 서 있는데 엔진을 불렀다: ${b.fake.asked}")
    }

    @Test
    fun `커서 뒤가 공백이면 문서 한가운데여도 고친다`() {
        // 막는 것은 낱말 **안**뿐이다. 낱말 끝에 서 있으면 문서 한가운데여도 평소대로 고친다.
        val b = Bench(mapOf("되요" to "돼요"))
        b.paste("되요 꼬리")
        b.caretTo(2)                       // "되요| 꼬리" — 커서 뒤는 공백이다
        b.type(".")
        assertEquals("돼요. 꼬리", b.text)
        assertEquals(listOf("되요"), b.fake.asked)
    }

    // ---- 재진입 ----

    @Test
    fun `우리가 고쳐 쓴 것은 타자로 세지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요", "돼요" to "됐어요"))
        b.type("되요 ")
        // 갈아 끼운 "돼요" 를 다시 물어보면 무한히 자기 꼬리를 문다.
        assertEquals(listOf("되요"), b.fake.asked)
        assertEquals("돼요 ", b.text)
    }

    @Test
    fun `같은 자리 같은 글은 두 번 묻지 않는다`() {
        val b = Bench()
        b.type("안녕 ")
        b.type(" ")   // 공백을 하나 더 쳐도 창은 같다
        assertEquals(1, b.fake.asked.count { it == "안녕" })
    }

    // ---- 토글 ----

    @Test
    fun `꺼 두면 평범한 텍스트 영역이다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        edt { b.live.enabled = false }
        b.type("되요 ")
        assertEquals("되요 ", b.text)
        assertTrue(b.fake.asked.isEmpty())
    }

    @Test
    fun `치는 도중에 꺼도 곧바로 멎는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요 ")
        assertEquals("돼요 ", b.text)
        edt { b.live.enabled = false }
        b.type("되요 ")
        assertEquals("돼요 되요 ", b.text)
        assertEquals(1, b.fake.asked.size)
    }

    @Test
    fun `답을 기다리는 사이에 끄면 그 답은 버린다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.fake.defer = true
        b.type("되요 ")
        assertEquals("되요 ", b.text)
        edt { b.live.enabled = false }
        b.fake.release()
        assertEquals("되요 ", b.text)
    }

    @Test
    fun `다시 켜면 그 자리에서 이어 간다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        edt { b.live.enabled = false }
        b.type("되요 ")
        assertEquals("되요 ", b.text)
        edt { b.live.enabled = true }
        pump()
        assertEquals("돼요 ", b.text)
    }

    // ---- 붙여넣기와 프로그램이 넣은 글 ----

    @Test
    fun `붙여넣기는 타자가 아니다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.paste("되요 ")
        assertEquals("되요 ", b.text)
        assertTrue(b.fake.asked.isEmpty(), "붙여넣은 글을 물어봤다: ${b.fake.asked}")
    }

    @Test
    fun `프로그램이 넣은 글도 타자가 아니다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        edt { b.live.withoutCorrection { b.area.text = "되요 " } }
        pump()
        assertEquals("되요 ", b.text)
        assertTrue(b.fake.asked.isEmpty())
    }

    @Test
    fun `붙여넣은 뒤에 이어 치면 그때부터는 고친다`() {
        val b = Bench(mapOf("안녕 되요" to "안녕 돼요"))
        b.paste("안녕 ")
        assertTrue(b.fake.asked.isEmpty())
        b.type("되요 ")
        assertEquals("안녕 돼요 ", b.text)
    }

    // ---- 조합 중 ----

    @Test
    fun `조합이 살아 있으면 계획조차 세우지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        edt { b.live.composingLength = { 1 } }
        b.type("되요 ")
        assertEquals("되요 ", b.text)
        assertTrue(b.fake.asked.isEmpty())
    }

    @Test
    fun `답이 왔는데 그새 조합이 시작됐으면 넣지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.fake.defer = true
        b.type("되요 ")
        edt { b.live.composingLength = { 1 } }   // 사용자가 다음 음절을 조합하기 시작했다
        b.fake.release()
        assertEquals("되요 ", b.text)
        assertEquals("조합 중", b.dropped.single().why)
    }

    // ---- 비동기 경합 ----

    @Test
    fun `답을 기다리는 사이에 뒤에 더 쳤으면 그대로 넣고 커서를 민다`() {
        val b = Bench(mapOf("할수있다" to "할 수 있다"))
        b.fake.defer = true
        b.type("할수있다 ")
        assertEquals(5, b.caret)
        b.type("지")                       // 답이 오기 전에 한 글자 더
        assertEquals(6, b.caret)
        b.fake.release()
        assertEquals("할 수 있다 지", b.text)
        assertEquals(8, b.caret)            // 두 글자 늘었으니 커서도 두 칸 뒤로
    }

    @Test
    fun `답을 기다리는 사이에 앞이 바뀌었으면 지문이 어긋나 버린다`() {
        val b = Bench(mapOf("되요" to "돼야"))
        b.fake.defer = true
        b.type("되요 ")
        // 요청은 [0,2) 로 나갔다. 그 앞에 글을 밀어 넣어 자리를 통째로 흔든다.
        b.insertAt(0, "머리말 ")
        b.fake.release()
        assertEquals("머리말 되요 ", b.text)
        assertEquals("문서가 움직였다", b.dropped.single().why)
        assertTrue(b.applied.isEmpty(), "움직인 문서에 답을 박았다")
    }

    @Test
    fun `프로그램이 문서를 갈아엎으면 날아간 요청은 잊는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.fake.defer = true
        b.type("되요 ")
        edt { b.live.withoutCorrection { b.area.text = "딴 글" } }
        b.fake.release()
        assertEquals("딴 글", b.text)
        assertTrue(b.applied.isEmpty())
    }

    @Test
    fun `늦게 온 옛 답은 새 요청에 밀려난다`() {
        val b = Bench(mapOf("되요" to "돼요", "되요 되요" to "돼요 돼요"))
        b.fake.defer = true
        b.type("되요 ")          // 1번 요청: "되요"
        b.type("되요 ")          // 2번 요청: "되요 되요"
        assertEquals(listOf("되요", "되요 되요"), b.fake.asked)
        b.fake.release()
        // 1번 답은 버려지고 2번만 들어간다. 둘 다 넣으면 글자가 겹쳐 무너진다.
        assertEquals(1, b.applied.size, "옛 답까지 넣었다: ${b.applied}")
        assertEquals("돼요 돼요 ", b.text)
    }

    // ---- 되돌리기 ----

    @Test
    fun `Ctrl+Z 한 번이면 친 그대로 돌아온다`() {
        val b = Bench(mapOf("할수있다" to "할 수 있다"))
        b.type("할수있다 ")
        assertEquals("할 수 있다 ", b.text)
        edt { b.live.undo() }
        pump()
        assertEquals("할수있다 ", b.text)
    }

    @Test
    fun `되돌린 것을 곧바로 다시 고치지 않는다`() {
        val b = Bench(mapOf("할수있다" to "할 수 있다"))
        b.type("할수있다 ")
        edt { b.live.undo() }
        pump()
        val askedAfterUndo = b.fake.asked.size
        pump()
        assertEquals("할수있다 ", b.text)
        assertEquals(askedAfterUndo, b.fake.asked.size, "되돌린 자리를 다시 물어봤다")
    }

    @Test
    fun `되돌린 뒤 이어 치면 다시 고친다`() {
        val b = Bench(mapOf("할수있다" to "할 수 있다", "할수있다 되요" to "할수있다 돼요"))
        b.type("할수있다 ")
        edt { b.live.undo() }
        pump()
        // Swing 의 되돌리기는 커서를 되살린 글 **끝**에 둔다 — 뒤의 공백보다 앞이다.
        assertEquals(4, b.caret)
        edt { b.area.caretPosition = b.area.document.length }
        b.type("되요 ")
        assertEquals("할수있다 돼요 ", b.text)
    }

    @Test
    fun `백스페이스 한 번으로 방금 들어간 교정을 물린다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요 ")
        assertEquals("돼요 ", b.text)
        assertTrue(b.backspace(), "백스페이스를 먹지 않았다")
        assertEquals("되요 ", b.text)
        assertEquals(3, b.caret)
        // 방아쇠였던 공백까지 한 덩어리로 물린다. TypingSession 의 되돌리기와 같은 자다.
        assertEquals("되요 ", assertNotNull(b.reports.filterIsInstance<Report.Reverted>().singleOrNull()).to)
    }

    @Test
    fun `물릴 것이 없으면 백스페이스에 손대지 않는다`() {
        val b = Bench()
        b.type("안녕")
        assertTrue(!b.backspace(), "물릴 것이 없는데 백스페이스를 먹었다")
    }

    @Test
    fun `교정 뒤에 한 글자라도 더 치면 백스페이스는 평범하게 돈다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("되요 ")
        b.type("가")
        assertTrue(!b.backspace())
    }

    // ---- 멈춤 그물 ----

    @Test
    fun `손을 멈추면 끝내지 않은 어절도 본다`() {
        val b = Bench(mapOf("되요" to "돼요"), pauseMillis = 40)
        b.type("되요")
        assertEquals("되요", b.text)
        waitUntil { b.text == "돼요" }
        assertEquals("돼요", b.text)
        assertEquals(Trigger.PAUSE, b.applied.single().trigger)
        assertEquals(2, b.caret)
    }

    @Test
    fun `멈춤은 꺼 둘 수 있다`() {
        val b = Bench(mapOf("되요" to "돼요"), pauseMillis = 40)
        edt { b.live.correctOnPause = false }
        b.type("되요")
        Thread.sleep(250)
        pump()
        assertEquals("되요", b.text)
        assertTrue(b.fake.asked.isEmpty())
    }

    @Test
    fun `조합 중에는 멈춤도 손대지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요"), pauseMillis = 40)
        edt { b.live.composingLength = { 1 } }
        b.type("되요")
        Thread.sleep(250)
        pump()
        assertEquals("되요", b.text)
        assertTrue(b.fake.asked.isEmpty())
    }

    @Test
    fun `버린 자리는 멈춤 그물이 다시 줍는다`() {
        val b = Bench(mapOf("되요" to "돼요"), pauseMillis = 40)
        b.fake.defer = true
        b.type("되요 ")
        edt { b.live.composingLength = { 1 } }
        b.fake.release()
        assertEquals("되요 ", b.text)                 // 조합 중이라 버렸다
        edt { b.live.composingLength = { 0 } }        // 조합이 끝났다
        b.fake.defer = false
        waitUntil { b.text == "돼요 " }
        assertEquals("돼요 ", b.text)
    }

    @Test
    fun `교정을 넣은 뒤 멈춤 그물이 같은 자리를 다시 묻지 않는다`() {
        // 갈아 끼운 구간과 다음 계획의 창을 견줄 때 구분자가 끼어 있으면 언제나 어긋나서,
        // 교정이 들어갈 때마다 멈춤 그물이 방금 우리가 쓴 글을 한 번 더 엔진에 보냈다.
        val b = Bench(mapOf("되요" to "돼요"), pauseMillis = 40)
        b.type("되요 ")
        assertEquals("돼요 ", b.text)
        Thread.sleep(250)
        pump()
        assertEquals(listOf("되요"), b.fake.asked)
    }

    // ---- 밀려난 요청 ----

    @Test
    fun `엔진이 손보다 느려 밀려난 요청도 버렸다고 알린다`() {
        // 조용히 버리면 느린 기계에서 교정이 사라지는 것을 아무도 못 본다.
        val b = Bench(mapOf("되요" to "돼요", "되요 되요" to "돼요 돼요"))
        b.fake.defer = true
        b.type("되요 ")
        b.type("되요 ")
        b.fake.release()
        val pushed = assertNotNull(b.dropped.singleOrNull { it.why == "더 새 요청에 밀렸다" })
        assertEquals("되요", pushed.examined)
    }

    // ---- 문서 갈아엎기 ----

    @Test
    fun `조합 중에 문서를 갈아엎으면 조합이 끝나기를 기다린다`() {
        // 그냥 갈아 끼우면 조합 중이던 음절이 **갈아 끼운 뒤에** 오프셋 0 에 확정돼
        // 유령 글자가 앞에 붙는다. 실측된 Swing/IME 의 성질이다.
        val b = Bench(mapOf("되요" to "돼요"))
        b.type("안녕하")
        val composing = java.util.concurrent.atomic.AtomicInteger(1)
        edt { b.live.composingLength = { composing.get() } }
        edt { b.live.replaceDocument("새 글 ") }
        Thread.sleep(60)
        pump()
        assertEquals("안녕하", b.text, "조합이 살아 있는데 갈아 끼웠다")
        composing.set(0)                            // IME 가 조합을 끝냈다
        waitUntil { b.text == "새 글 " }
        assertEquals("새 글 ", b.text)
        assertEquals(4, b.caret)
    }

    @Test
    fun `조합이 끝나지 않아도 끝내 갈아 끼운다`() {
        val b = Bench()
        b.type("안녕")
        edt { b.live.composingLength = { 1 } }      // 영영 안 끝난다
        edt { b.live.replaceDocument("새 글") }
        waitUntil { b.text == "새 글" }
        assertEquals("새 글", b.text)
    }

    // ---- 고른 글 ----

    @Test
    fun `고른 글이 있으면 끼어들지 않는다`() {
        val b = Bench(mapOf("되요" to "돼요"))
        b.fake.defer = true
        b.type("되요 ")
        edt { b.area.select(0, 2) }
        b.fake.release()
        assertEquals("되요 ", b.text)
        assertEquals("고른 글이 있다", b.dropped.single().why)
    }

}
