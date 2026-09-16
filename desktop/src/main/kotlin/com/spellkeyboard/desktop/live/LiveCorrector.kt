package com.spellkeyboard.desktop.live

import com.spellkeyboard.core.correct.Correction
import com.spellkeyboard.core.correct.CorrectionResult
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.UndoableEditEvent
import javax.swing.event.UndoableEditListener
import javax.swing.text.AbstractDocument
import javax.swing.text.BadLocationException
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import javax.swing.undo.CompoundEdit
import javax.swing.undo.UndoManager

/**
 * 창 하나 분량의 교정을 **다른 스레드에서** 해 오는 것.
 *
 * `SpellEngine` 의 일꾼 하나에 태우는 것이 정상 구현이고([SpellEngineTail]),
 * 시험에서는 그 자리에서 답하는 가짜를 끼운다. [onResult] 는 반드시 EDT 에서 불러야 한다.
 */
fun interface TailCorrector {
    fun correct(window: String, contextBefore: String?, onResult: (CorrectionResult) -> Unit)
}

/** 화면에 "무엇을 고쳤나" 를 적기 위한 보고. 전부 EDT 에서 온다. */
sealed interface Report {

    /** 실제로 고쳤다. */
    data class Applied(
        val trigger: Trigger,
        val from: String,
        val to: String,
        val corrections: List<Correction>,
        val millis: Long,
    ) : Report

    /** 검사했는데 고칠 것이 없었다. [examined] 는 실제로 본 구간이다. */
    data class Unchanged(val trigger: Trigger, val examined: String, val millis: Long) : Report

    /** 자동 교정을 되돌렸다. 백스페이스가 먹은 그 한 번이다. */
    data class Reverted(val to: String) : Report

    /**
     * 답을 넣지 않았다. 사이에 문서가 움직였거나, 조합이 살아 있었거나, 더 새 요청에 밀렸다.
     *
     * 버리는 것이 맞다 — 여기서 억지로 넣으면 글자가 엉뚱한 자리에 박힌다.
     * 놓친 자리는 [LiveCorrector.correctOnPause] 의 멈춤 그물이 다시 줍는다.
     *
     * **반드시 보고한다.** 조용히 버리면 엔진이 느린 기계에서 교정이 사라지는 것을
     * 아무도 못 본다 — 창이 "버렸다" 를 찍을 수 있어야 한다.
     */
    data class Dropped(val trigger: Trigger, val examined: String, val why: String) : Report
}

/**
 * 타자는 건드리지 않고, 친 뒤에 고치는 실시간 교정기.
 *
 * ## 무엇을 하지 않는가
 *
 * **입력을 가로채지 않는다.** `KeyEvent` 도 `InputMethodEvent` 도 소비하지 않고,
 * 한글 조합에는 손끝도 대지 않는다. IME 와 Swing 이 늘 하던 그대로 문서에 글자를 쓰고,
 * 우리는 그 문서를 뒤에서 본다. 그래서 이 설계는 **한글 입력을 망가뜨릴 수가 없다** —
 * 망가뜨릴 지점이 코드에 없다.
 *
 * (예외가 하나 있다. [revertOnBackspace] 가 켜져 있으면 백스페이스 **한 번**을 먹는다.
 * 방금 우리가 고친 것을 되돌리는 그 한 번뿐이고, 조합 중이면 아예 보지 않는다.
 * IME 조사에서 `KeyEvent` 소비는 Win32 IME 의 결정이 끝난 한참 뒤에 일어나므로
 * 조합에 영향이 없음이 측정돼 있다. 마음에 안 들면 끄면 된다.)
 *
 * ## 어려움은 전부 다른 데 있다
 *
 * 1. **재진입.** 우리가 고쳐 쓰면 그 편집이 다시 `DocumentListener` 를 때린다.
 *    [quietDepth] 로 우리 편집을 표시해 두고, 표시된 동안의 알림은 개정 번호만 올리고 버린다.
 *    문서 편집은 전부 EDT 한 줄에서만 일어나므로 정수 하나면 충분하다.
 * 2. **문서 안에서 알림 도중에 문서를 고칠 수 없다.** `AbstractDocument` 가 막는다.
 *    그래서 알림에서는 아무것도 하지 않고 [SwingUtilities.invokeLater] 로 미룬다.
 *    미룬 일감은 자기가 예약될 때의 개정 번호를 들고 있다가, 그사이 문서가 또 바뀌었으면
 *    조용히 물러난다 — 더 새 일감이 뒤에 서 있기 때문이다.
 * 3. **엔진이 비동기다.** 답이 오는 사이 문서는 얼마든지 움직인다. 그래서 요청할 때
 *    구간의 내용과 그 **왼쪽 [GUARD_CHARS] 글자**를 지문으로 떠 두고, 넣기 직전에 다시
 *    맞춰 본다. 어긋나면 안 넣는다. 사용자가 뒤에 글자를 더 친 경우는 지문이 그대로라
 *    정상으로 들어가고, 커서는 [caretAfterSplice] 가 길이 차이만큼 밀어 준다.
 * 4. **조합 중에는 문서를 건드리면 안 된다.** IME 조사에서 조합 중 문서를 고치자
 *    아직 안 끝난 음절이 엉뚱한 자리(오프셋 0)에 확정되는 것이 재현됐다. 그래서 계획할
 *    때와 넣을 때 두 번 [composingLength] 를 본다. IME 이벤트도 EDT 로 오므로,
 *    같은 EDT 작업 안에서 "확인 후 편집" 은 원자적이다.
 */
class LiveCorrector private constructor(
    private val area: JTextComponent,
    private val corrector: TailCorrector,
    pauseMillis: Int,
) {

    // ---- 켜고 끄기 ----

    /**
     * 꺼지면 **즉시** 평범한 텍스트 영역이 된다. 날아간 요청은 답이 와도 버린다.
     */
    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            idle.stop()
            pending = null
            lastApplied = null
            lastAsked = null
            if (value) schedule()
        }

    /** 손을 멈췄을 때도 고칠까. 끄면 공백·부호·줄바꿈에서만 고친다. */
    var correctOnPause: Boolean = true

    /** 방금 들어간 자동 교정을 백스페이스 한 번으로 물릴까. */
    var revertOnBackspace: Boolean = true

    /** 교정 결과 보고. EDT 에서 불린다. */
    var onReport: ((Report) -> Unit)? = null

    /**
     * 지금 조합 중인 글자 수. 0 이어야 문서를 건드릴 수 있다.
     *
     * `문서 길이 - InputMethodRequests.getCommittedTextLength()` 로 잰다. 리플렉션도
     * 내부 API 도 아니다 — IME 조사에서 조합 중 `composedRange` 와 정확히 일치함을 쟀다.
     * 시험에서는 조합을 흉내 내려고 갈아 끼운다.
     */
    var composingLength: () -> Int = {
        val requests = area.inputMethodRequests
        if (requests == null) 0
        else runCatching { area.document.length - requests.committedTextLength }
            .getOrDefault(0).coerceAtLeast(0)
    }

    /** Ctrl+Z 가 쓰는 되돌리기 관리자. 창이 메뉴를 붙이고 싶으면 가져다 쓴다. */
    val undoManager = UndoManager().apply { limit = 200 }

    // ---- 속 ----

    /** 문서가 바뀔 때마다 오른다. 우리 편집도 센다 — "그 뒤로 움직였나" 를 재는 자다. */
    private var revision = 0

    /** 우리(또는 창)가 문서를 고치는 중. 이 동안의 알림은 타자가 아니다. */
    private var quietDepth = 0

    /** 우리 편집을 되돌리기 한 칸으로 묶는 그릇. */
    private var compound: CompoundEdit? = null

    private var nextRequestId = 0L
    private var pending: Pending? = null

    /** 같은 자리·같은 글을 되묻지 않게 기억해 둔다. 멈춤 그물이 초마다 엔진을 부르는 것을 막는다. */
    private var lastAsked: TailPlan? = null

    /** 방금 넣은 교정. 백스페이스 되돌리기와 "다시 고치지 않기" 에 쓴다. */
    private var lastApplied: AppliedSpan? = null

    /**
     * Ctrl+Z 직후의 커서 앞 글.
     *
     * 되돌린 것을 그 자리에서 다시 고쳐 버리면 사용자는 영원히 되돌릴 수 없다.
     * 그래서 "이 모양일 때는 고치지 않는다" 를 한 장 들고 있는다. 사용자가 한 글자라도
     * 더 치면 모양이 달라져 저절로 풀린다 — 타이머도 상태 기계도 필요 없다.
     */
    private var undoGuard: String? = null

    private data class Pending(
        val id: Long,
        val plan: TailPlan,
        val guardStart: Int,
        val guardText: String,
        val startedNanos: Long,
    )

    /**
     * @param spliced 우리가 실제로 갈아 끼운 글. 방아쇠였던 구분자는 **빠져 있다.**
     * @param applied [spliced] 에 그 구분자까지 붙인 것. 백스페이스로 물릴 덩어리다.
     *
     * 둘을 따로 드는 까닭: 되묻기를 막으려면 다음 계획의 `window` 와 견주어야 하는데
     * `window` 에는 구분자가 없다. 하나로 뭉쳐 두면 언제나 어긋나서, 교정이 들어갈
     * 때마다 멈춤 그물이 **방금 우리가 쓴 글**을 엔진에 한 번 더 보낸다. 실측으로
     * 모든 교정 뒤에 [Report.Unchanged] 가 한 줄씩 따라붙었다 — 엔진 일감이 두 배였다.
     */
    private data class AppliedSpan(
        val start: Int,
        val spliced: String,
        val applied: String,
        val original: String,
    ) {
        val end get() = start + applied.length
    }

    /**
     * 멈춤 그물. **되풀이 타이머다.**
     *
     * 한 번만 울리게 두면 조합 중에 울린 한 번이 헛방으로 끝나고 그걸로 그물이 걷힌다.
     * 놓친 자리를 줍는 것이 이 타이머의 일이므로, 주울 것이 있는 동안은 계속 돈다.
     * 할 일이 없어지면([Look.IDLE]) 스스로 멎고, 막혀만 있으면([Look.BUSY])
     * [BUSY_TICKS] 번까지만 기다린다 — 사용자가 글을 골라 둔 채 자리를 뜬 경우다.
     */
    private val idle = Timer(pauseMillis) { onPause() }.apply { isRepeats = true }

    private var busyTicks = 0

    /** [look] 이 무엇을 하고 왔나. 멈춤 타이머를 계속 돌릴지 정한다. */
    private enum class Look { IDLE, BUSY, ASKED }

    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onDocumentChange(e.length)
        override fun removeUpdate(e: DocumentEvent) = onDocumentChange(e.length)

        /** 속성만 바뀐 것. 글자는 그대로다. */
        override fun changedUpdate(e: DocumentEvent) = Unit
    }

    private val undoableEditListener = UndoableEditListener { e: UndoableEditEvent ->
        val c = compound
        if (c != null) c.addEdit(e.edit) else undoManager.addEdit(e.edit)
    }

    private val backspaceListener: KeyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode == KeyEvent.VK_BACK_SPACE && e.modifiersEx == 0 && backspacePressed()) e.consume()
        }
    }

    // ---- 문서 알림 ----

    /**
     * 문서가 바뀌었다. **여기서는 아무것도 하지 않는다.**
     *
     * 알림 도중에는 문서를 고칠 수 없고, 커서도 아직 안 움직였다(Caret 은 UI 가 먼저
     * 등록해 둔 청취자라 우리보다 **나중에** 불린다). 그래서 읽지도 않는다 — 미룬다.
     */
    private fun onDocumentChange(length: Int) {
        revision++
        if (quietDepth > 0) return

        // 우리가 아닌 누군가 문서를 건드렸다. 방금 넣은 교정을 물릴 근거는 사라졌다.
        lastApplied = null

        if (!enabled) return

        // 한 번에 두 글자 이상 오간 것은 타자가 아니다 — 붙여넣기, `setText`, 고른 글 지우기.
        // IME 조사 기준으로 윈도우 한글 IME 의 확정은 **언제나 한 글자짜리 insert** 고,
        // 조합 중의 되쓰기도 remove 1 + insert 1 이다. 그래서 길이 하나로 갈린다.
        // 이런 변경은 고치지 않는다 — 붙여넣은 글은 [전체교정] 이 맡는 일이다.
        if (length > 1) {
            idle.stop()
            lastAsked = null
            return
        }

        schedule()
    }

    /** 다음 EDT 작업에서 한 번 살펴본다. 그사이 또 바뀌면 그때 예약된 일감이 맡는다. */
    private fun schedule() {
        val at = revision
        SwingUtilities.invokeLater { if (revision == at) look(pause = false) }
        if (correctOnPause) {
            busyTicks = 0
            idle.restart()
        }
    }

    private fun onPause() {
        if (!enabled || !correctOnPause) {
            idle.stop()
            return
        }
        when (look(pause = true)) {
            Look.ASKED -> busyTicks = 0
            Look.BUSY -> if (++busyTicks >= BUSY_TICKS) idle.stop()
            Look.IDLE -> if (pending == null) idle.stop() else busyTicks = 0
        }
    }

    // ---- 계획 ----

    private fun look(pause: Boolean): Look {
        if (!enabled || !area.isEditable) return Look.IDLE

        // 조합이 살아 있으면 문서는 지금 IME 의 것이다. 계획조차 세우지 않는다.
        if (composingLength() > 0) return Look.BUSY

        // 고른 글이 있으면 사용자가 곧 덮어쓸 참이다. 끼어들지 않는다.
        if (area.selectionStart != area.selectionEnd) return Look.BUSY

        val caret = area.caretPosition
        if (caret <= 0) return Look.IDLE

        // 낱말 **안**에 서 있으면 손대지 않는다. 자세한 까닭은 [caretEndsWord] 에 있다.
        if (!caretEndsWord(charAt(caret))) return Look.IDLE

        val before = textBefore(caret, LOOKBEHIND_CHARS) ?: return Look.IDLE
        if (before == undoGuard) return Look.IDLE

        val plan = planTail(before, caret, pause) ?: return Look.IDLE
        if (plan.sameTarget(lastAsked)) return Look.IDLE
        if (plan.sameTarget(pending?.plan)) return Look.BUSY

        // 방금 우리가 넣은 것을 그대로 다시 물어보지 않는다. 엔진이 멱등이 아니면
        // 여기가 없을 때 자기가 쓴 글을 다시 고치며 왔다 갔다 한다.
        val applied = lastApplied
        if (applied != null && applied.start == plan.start && applied.spliced == plan.window) return Look.IDLE

        ask(plan)
        return Look.ASKED
    }

    private fun ask(plan: TailPlan) {
        val guardStart = (plan.start - GUARD_CHARS).coerceAtLeast(0)
        val guardText = textAt(guardStart, plan.end - guardStart) ?: return

        // 아직 답을 못 받은 요청을 밀어낸다. **반드시 알린다** — 엔진이 손가락보다 느린
        // 기계에서는 이렇게 밀려난 어절이 영영 안 고쳐지는데, 조용히 버리면 아무도 모른다.
        pending?.let { report(Report.Dropped(it.plan.trigger, it.plan.window, "더 새 요청에 밀렸다")) }

        val id = ++nextRequestId
        val request = Pending(id, plan, guardStart, guardText, System.nanoTime())
        pending = request
        lastAsked = plan

        corrector.correct(plan.window, plan.contextBefore) { result -> deliver(id, result) }
    }

    // ---- 결과 ----

    /** 일꾼이 답해 왔다. EDT 다. 여기서부터가 진짜 어려운 부분이다. */
    private fun deliver(id: Long, result: CorrectionResult) {
        val request = pending ?: return
        if (request.id != id) return          // 더 새 요청이 이미 자리를 차지했다
        pending = null
        if (!enabled) return                  // 답을 기다리는 사이에 꺼졌다

        val plan = request.plan
        val millis = (System.nanoTime() - request.startedNanos) / 1_000_000

        if (!result.changed) {
            report(Report.Unchanged(plan.trigger, plan.window, millis))
            return
        }

        // 넣어도 되는 상태인가. 다섯 중 하나라도 어긋나면 버린다.
        val why = blockedReason(request)
        if (why != null) {
            lastAsked = null                  // 멈춤 그물이 다시 주워 갈 수 있게 기억을 푼다
            report(Report.Dropped(plan.trigger, plan.window, why))
            return
        }

        if (!splice(plan.start, plan.end, result.text, plan.window)) {
            report(Report.Dropped(plan.trigger, plan.window, "자리를 잃었다"))
            return
        }
        report(Report.Applied(plan.trigger, plan.window, result.text, result.corrections, millis))
    }

    /** 결과를 넣으면 안 되는 까닭. 없으면 null. */
    private fun blockedReason(request: Pending): String? {
        if (composingLength() > 0) return "조합 중"
        if (area.selectionStart != area.selectionEnd) return "고른 글이 있다"
        if (!area.isEditable) return "읽기 전용"
        val now = textAt(request.guardStart, request.guardText.length)
        if (now != request.guardText) return "문서가 움직였다"
        return null
    }

    // ---- 갈아 끼우기 ----

    /**
     * `[start, end)` 를 [replacement] 로 바꾸고 커서를 제자리에 둔다.
     *
     * 지우기와 넣기를 한 묶음으로 돌린다. 되돌리기도 한 칸이고, 화면도 한 번만 그린다 —
     * 따로 돌리면 사용자에게 글자가 깜빡이는 것이 보인다.
     */
    private fun splice(start: Int, end: Int, replacement: String, original: String): Boolean {
        val caret = area.caretPosition
        val ok = edit {
            val doc = area.document
            if (doc is AbstractDocument) {
                doc.replace(start, end - start, replacement, null)
            } else {
                doc.remove(start, end - start)
                doc.insertString(start, replacement, null)
            }
            val moved = caretAfterSplice(caret, start, end, replacement.length)
            area.caretPosition = moved.coerceIn(0, area.document.length)
        }
        if (!ok) return false
        lastApplied = appliedSpan(start, replacement, original)
        undoGuard = null
        return true
    }

    /**
     * 백스페이스로 물릴 덩어리. **갈아 끼운 구간에 방아쇠였던 구분자까지 붙인다.**
     *
     * 구간은 공백 앞에서 끝나는데 커서는 공백 뒤에 있다. 구간만 기억하면 커서가 한 칸
     * 어긋나 되돌리기가 영영 안 걸린다. `TypingSession` 도 `replacement + separator` 를
     * 한 덩어리로 기억한다 — 같은 자를 쓴다.
     *
     * 답을 기다리는 사이 사용자가 많이 쳐 두었으면([MAX_TAIL] 초과) 붙이지 않는다.
     * 그러면 커서가 맞지 않아 되돌리기가 그냥 안 걸릴 뿐, 글은 건드리지 않는다.
     */
    private fun appliedSpan(start: Int, replacement: String, original: String): AppliedSpan {
        val from = start + replacement.length
        val gap = area.caretPosition - from
        val tail = if (gap in 1..MAX_TAIL) textAt(from, gap) ?: "" else ""
        return AppliedSpan(start, replacement, replacement + tail, original + tail)
    }

    /**
     * 우리 편집을 [quietDepth] 로 감싸고 되돌리기 한 칸으로 묶는다.
     *
     * 중첩을 세는 까닭: 창이 [withoutCorrection] 안에서 또 편집을 부를 수 있고,
     * 그때 안쪽이 먼저 묶음을 닫아 버리면 바깥 편집이 되돌리기에서 갈라진다.
     */
    private inline fun edit(block: () -> Unit): Boolean {
        val outermost = quietDepth == 0
        var ok = true
        quietDepth++
        if (outermost) compound = CompoundEdit()
        try {
            block()
        } catch (e: BadLocationException) {
            // 문서가 지문과 맞는지 이미 봤으므로 여기 오면 우리 계산이 틀린 것이다.
            // 글을 망가뜨리느니 그만둔다.
            ok = false
        } finally {
            quietDepth--
            if (outermost) {
                val c = compound
                compound = null
                c?.end()
                if (c != null && c.isSignificant) undoManager.addEdit(c)
            }
        }
        return ok
    }

    // ---- 되돌리기 ----

    /**
     * 백스페이스로 방금 들어간 교정을 물린다.
     *
     * @return true 면 물렸다 — 호출자가 키를 먹어야 한다. false 면 **손대지 않았다.**
     *   그때는 텍스트 영역이 늘 하던 대로 한 글자를 지운다.
     *
     * 이 전략에서 입력에 손대는 곳은 여기 하나뿐이다. 조합 중이면 아예 물러난다
     * (윈도우 IME 는 조합 중 백스페이스를 `KEY_PRESSED` 로 주지도 않으므로 실제로는
     * 여기까지 오지도 않는다). 물릴 것이 없으면 키를 먹지 않는다.
     */
    fun backspacePressed(): Boolean {
        if (!enabled || !revertOnBackspace) return false
        val applied = lastApplied ?: return false
        lastApplied = null

        if (composingLength() > 0) return false
        if (area.selectionStart != area.selectionEnd) return false
        if (area.caretPosition != applied.end) return false
        if (textAt(applied.start, applied.applied.length) != applied.applied) return false

        if (!splice(applied.start, applied.end, applied.original, applied.applied)) return false
        lastApplied = null
        undoGuard = textBefore(area.caretPosition, LOOKBEHIND_CHARS)
        report(Report.Reverted(applied.original))
        return true
    }

    /** 창이 메뉴에서도 부를 수 있게 열어 둔다. Ctrl+Z 가 이걸 부른다. */
    fun undo() {
        if (!undoManager.canUndo()) return
        edit { undoManager.undo() }
        afterManualUndo()
    }

    fun redo() {
        if (!undoManager.canRedo()) return
        edit { undoManager.redo() }
        afterManualUndo()
    }

    /**
     * 사람이 되돌린 뒤에는 그 자리를 다시 고치지 않는다.
     *
     * 이걸 안 하면 Ctrl+Z 가 아무 일도 안 하는 것처럼 보인다 — 되돌리는 순간
     * 어절 경계가 다시 드러나서 우리가 곧바로 같은 교정을 넣어 버리기 때문이다.
     */
    private fun afterManualUndo() {
        pending = null
        lastAsked = null
        lastApplied = null
        idle.stop()
        undoGuard = textBefore(area.caretPosition, LOOKBEHIND_CHARS)
    }

    // ---- 밖에서 문서를 갈아엎을 때 ----

    /**
     * 우리가 아닌 코드가 문서를 바꿀 때 감싼다. [붙여넣기], [전체교정], `setText` 등.
     *
     * 안에서 일어난 편집은 타자로 세지 않고, 되돌리기는 한 칸으로 묶인다 —
     * 전체교정 한 번이 Ctrl+Z 한 번으로 물린다.
     */
    fun <T> withoutCorrection(block: () -> T): T {
        val outermost = quietDepth == 0
        quietDepth++
        if (outermost) compound = CompoundEdit()
        try {
            return block()
        } finally {
            quietDepth--
            if (outermost) {
                val c = compound
                compound = null
                c?.end()
                if (c != null && c.isSignificant) undoManager.addEdit(c)
            }
            forget()
        }
    }

    /**
     * 문서를 통째로 [text] 로 갈아 끼운다. [붙여넣기]·[전체교정]·[지우기] 는 이것을 쓴다.
     *
     * `withoutCorrection { area.text = ... }` 를 손으로 쓰는 것과 한 가지가 다르다.
     * **조합이 살아 있으면 먼저 끝내고 다음 EDT 작업에서 갈아 끼운다.**
     *
     * 안 그러면 실측된 대로 깨진다: `안녕하` 까지 치고(하가 조합 중) 곧바로 `setText("새 글 ")`
     * 하면, 갈아 끼운 **뒤에** 조합 중이던 하가 오프셋 0 에 확정돼 `하좋다 새 글 ` 이 된다.
     * 이것은 맨 `JTextArea` 도 똑같이 겪는 Swing/IME 의 성질이지만, 앱의 모든 호출자가
     * 여기를 지나가므로 한 곳에서 막는 편이 낫다.
     *
     * `endComposition()` 은 **비동기**라 부른 그 자리에서는 아직 조합이 살아 있다
     * (IME 조사 landmine 2). 확정 이벤트는 EDT 큐 뒤에 들어오므로 [ENDC_RETRY_MILLIS]
     * 뒤에 다시 와서 본다. 끝내 안 끝나면 [ENDC_TRIES] 번 만에 포기하고 그냥 갈아 끼운다 —
     * 영원히 미루느니 예전과 같이 동작하는 편이 낫다.
     */
    @JvmOverloads
    fun replaceDocument(text: String, attempt: Int = 0) {
        if (composingLength() > 0 && attempt < ENDC_TRIES) {
            area.inputContext?.endComposition()
            Timer(ENDC_RETRY_MILLIS) { replaceDocument(text, attempt + 1) }
                .apply { isRepeats = false }
                .start()
            return
        }
        withoutCorrection {
            area.text = text
            area.caretPosition = area.document.length
        }
    }

    /** 들고 있던 세션 상태를 전부 버린다. 커서가 멀리 뛰었거나 포커스를 잃었을 때. */
    fun forget() {
        pending = null
        lastAsked = null
        lastApplied = null
        undoGuard = null
        idle.stop()
    }

    // ---- 설치와 철거 ----

    private fun install() {
        area.document.addDocumentListener(documentListener)
        area.document.addUndoableEditListener(undoableEditListener)
        area.addKeyListener(backspaceListener)
        bind("control Z") { undo() }
        bind("control Y") { redo() }
        bind("control shift Z") { redo() }
    }

    fun detach() {
        idle.stop()
        pending = null
        area.document.removeDocumentListener(documentListener)
        area.document.removeUndoableEditListener(undoableEditListener)
        area.removeKeyListener(backspaceListener)
    }

    private fun bind(stroke: String, action: () -> Unit) {
        val key = KeyStroke.getKeyStroke(stroke) ?: return
        val name = "live-$stroke"
        area.getInputMap(JComponent.WHEN_FOCUSED).put(key, name)
        area.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = action()
        })
    }

    // ---- 잔심부름 ----

    private fun report(r: Report) = onReport?.invoke(r)

    private fun textBefore(caret: Int, maxChars: Int): String? {
        val from = (caret - maxChars).coerceAtLeast(0)
        return textAt(from, caret - from)
    }

    /** 문서 [offset] 자리의 한 글자. 문서 끝이면 null — "뒤에 아무것도 없다" 는 뜻이다. */
    private fun charAt(offset: Int): Char? = textAt(offset, 1)?.firstOrNull()

    private fun textAt(offset: Int, length: Int): String? {
        val doc: Document = area.document
        if (offset < 0 || length < 0 || offset + length > doc.length) return null
        return runCatching { doc.getText(offset, length) }.getOrNull()
    }

    companion object {
        /** 지문에 함께 뜨는 왼쪽 문맥 길이. 앞에서 글자가 끼어들면 지문이 어긋나 걸린다. */
        const val GUARD_CHARS = 12

        /** 손을 멈춘 것으로 보는 시간. */
        const val PAUSE_MILLIS = 900

        /** 막힌 채로 몇 번까지 기다려 볼까. 이만큼 지나면 그물을 걷는다. */
        const val BUSY_TICKS = 10

        /** 되돌리기 덩어리에 붙일 수 있는 꼬리의 최대 길이. 구분자 한 글자면 족하다. */
        private const val MAX_TAIL = 4

        /** 조합이 끝나기를 몇 번까지 기다려 볼까. 이만큼 지나면 그냥 갈아 끼운다. */
        private const val ENDC_TRIES = 5

        /** 조합이 끝났나 다시 보러 오는 간격. 다섯 번이면 넉넉히 150ms 다. */
        private const val ENDC_RETRY_MILLIS = 30

        fun attach(
            area: JTextComponent,
            corrector: TailCorrector,
            pauseMillis: Int = PAUSE_MILLIS,
        ): LiveCorrector = LiveCorrector(area, corrector, pauseMillis).also { it.install() }
    }
}
