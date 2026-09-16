package com.spellkeyboard.desktop.live

import com.spellkeyboard.core.correct.CorrectionEngine
import com.spellkeyboard.core.editor.TypingSession

/**
 * 사후 교정의 순수 계산부 — 화면도, 스레드도, 문서도 없는 부분만 모았다.
 *
 * 이 파일에 Swing 이 한 줄도 없는 것이 핵심이다. 어절 경계를 어디서 잡을지,
 * 문서의 어느 구간을 갈아 끼울지, 갈아 끼운 뒤 커서를 어디에 둘지 — 틀리면
 * 글자가 사라지거나 커서가 튀는 계산은 전부 여기에 있고, 전부 헤드리스로 시험한다.
 */

/** 교정을 촉발한 까닭. 화면에 "왜 지금 고쳤나" 를 보여 주는 데도 쓴다. */
enum class Trigger(val label: String) {
    SPACE("공백"),
    TERMINATOR("문장부호"),
    NEWLINE("줄바꿈"),

    /** 손을 멈춰서. 경계 방아쇠가 놓친 자리를 줍는 그물이다. */
    PAUSE("멈춤"),
}

/**
 * 교정 요청 한 건. **문서 좌표로** 적혀 있다.
 *
 * @param start 갈아 끼울 구간의 시작. 문서 오프셋이다.
 * @param end   구간의 끝(배타적). 방아쇠가 된 공백·부호는 구간 **밖**이다 —
 *   `TypingSession.correctThenCommit` 이 구분자를 넣기 전에 교정하는 것과 같은 자리다.
 * @param window 지금 `[start, end)` 에 들어 있는 글. 엔진에 넘길 글이자,
 *   결과가 돌아왔을 때 "그사이 문서가 움직였나" 를 대조할 지문이다.
 * @param contextBefore 창 바로 앞 어절. 문맥 교정기가 첫 어절을 볼 때 쓴다.
 */
data class TailPlan(
    val trigger: Trigger,
    val start: Int,
    val end: Int,
    val window: String,
    val contextBefore: String?,
) {
    /** 방아쇠(trigger)만 다르고 같은 자리·같은 글이면 같은 요청이다. 되묻기를 막는 데 쓴다. */
    fun sameTarget(other: TailPlan?) =
        other != null && other.start == start && other.end == end && other.window == window
}

/** 문장 첫머리 표시. 언어모델의 것과 같은 값이어야 한다. */
private const val BOS = "<s>"

/** 커서 앞에서 읽어 오는 글자 수. `TypingSession` 과 같은 자를 쓴다. */
const val LOOKBEHIND_CHARS = TypingSession.LOOKBEHIND_CHARS

/** 실시간 교정이 보는 어절 수. 엔진의 기본값을 그대로 따른다. */
const val WINDOW_WORDS = CorrectionEngine.DEFAULT_WINDOW_WORDS

private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')

/** 이 글자가 커서 바로 앞에 나타나면 어절이 끝난 것으로 본다. */
fun isBoundaryChar(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c in TypingSession.WORD_TERMINATORS

/**
 * 커서 **오른쪽**이 어절의 끝인가. [after] 는 커서 바로 뒤 한 글자, 문서 끝이면 null.
 *
 * 이것을 안 보면 남의 낱말을 반으로 가른다. 실측된 사고다 — `안녕하세요 꼬리` 의 하와 세
 * 사이(오프셋 3)에 커서를 놓고 `되요 ` 를 치면, 커서 앞은 `안녕하되요` 가 되고 창은 그것을
 * 통째로 엔진에 넘겨 `안녕 하돼요 세요 꼬리` 를 만든다. 사용자가 손대지도 않은 `안녕하세요`
 * 가 반으로 쪼개진다. 오타를 고치러 문장 가운데를 클릭하는 것은 가장 흔한 편집이므로
 * 이 사고는 흔하다.
 *
 * 그래서 커서 오른쪽이 경계가 아니면 — 즉 낱말 **안**에 서 있으면 — 아무것도 하지 않는다.
 * 잃는 것은 `되요꼬리` 한가운데에 공백을 넣어 가르는 것 같은 드문 편집의 교정 기회뿐이고,
 * 그건 [전체교정] 이 맡는다. 남의 글을 망가뜨리는 것보다 안 고치는 편이 낫다.
 */
fun caretEndsWord(after: Char?): Boolean = after == null || isBoundaryChar(after)

/** 한글이 한 글자라도 있나. 없으면 엔진을 부를 것도 없다 — 일꾼 스레드를 아낀다. */
fun hasHangul(text: String): Boolean =
    text.any { it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x3163 }

/**
 * 뒤에서부터 [maxWords] 개의 어절을 품는 창의 시작 위치.
 *
 * `CorrectionEngine.windowStart` 와 **글자 하나까지 같아야 한다.** 그쪽이 internal 이라
 * 여기 다시 적었고, 같은지는 시험이 지킨다([LiveTailPlanTest]).
 */
fun windowStart(text: String, maxWords: Int): Int {
    var i = text.length
    while (i > 0 && text[i - 1].isWhitespace()) i--

    var words = 0
    while (i > 0 && words < maxWords) {
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        words++
        if (words < maxWords) {
            while (i > 0 && text[i - 1].isWhitespace()) i--
        }
    }
    return i
}

/**
 * 창 바로 앞 어절. 없으면 문장 첫머리([BOS]), 앞 어절이 한글이 아니면 모름(null).
 *
 * [truncated] 는 커서 앞을 [LOOKBEHIND_CHARS] 로 잘라 읽었다는 뜻이다. 잘라 읽었는데
 * 창 앞이 비어 보이면 실제로는 앞에 글이 더 있는 것이므로 "문장 첫머리" 라고 하면 안 된다.
 */
fun contextBefore(source: String, windowStart: Int, truncated: Boolean): String? {
    val prefix = source.substring(0, windowStart).trimEnd()
    if (prefix.isEmpty()) return if (truncated) null else BOS
    val token = prefix.substring(prefix.indexOfLast { it.isWhitespace() } + 1)
    if (token.last() in SENTENCE_ENDERS) return BOS
    return if (token.all { it in '가'..'힣' }) token else null
}

/**
 * 커서 앞 글을 보고 지금 고칠 것이 있는지 정한다. 없으면 null.
 *
 * @param before 커서 바로 앞의 글. 최대 [LOOKBEHIND_CHARS] 글자.
 * @param caret  [before] 의 **끝**에 해당하는 문서 오프셋.
 * @param pause  손이 멈춰서 부른 것인가.
 *
 * 경계 방아쇠(공백·부호·줄바꿈)는 마지막 한 글자를 **떼고** 그 앞을 고친다. 그래야
 * 사용자가 방금 친 구분자가 교정 창 안으로 끌려 들어가지 않는다.
 *
 * 멈춤 방아쇠는 뒤쪽 공백만 털고 나머지를 통째로 본다. 공백으로 끝나 있었다면
 * 결과는 경계 방아쇠와 **정확히 같은 구간**이 되고, 그래서 [TailPlan.sameTarget] 으로
 * "이미 물어본 것" 을 걸러낼 수 있다. 멈춤은 새 일을 만드는 것이 아니라 놓친 일을 줍는다.
 */
fun planTail(before: String, caret: Int, pause: Boolean): TailPlan? {
    if (before.isEmpty()) return null
    val readFrom = caret - before.length
    if (readFrom < 0) return null

    val trigger: Trigger
    val source: String
    if (pause) {
        trigger = Trigger.PAUSE
        source = before.trimEnd()
    } else {
        val last = before.last()
        trigger = when {
            last == ' ' || last == '\t' -> Trigger.SPACE
            last == '\n' || last == '\r' -> Trigger.NEWLINE
            last in TypingSession.WORD_TERMINATORS -> Trigger.TERMINATOR
            else -> return null
        }
        source = before.dropLast(1)
    }

    if (source.isEmpty() || !hasHangul(source)) return null

    val start = windowStart(source, WINDOW_WORDS)
    val window = source.substring(start)
    if (window.isBlank()) return null

    return TailPlan(
        trigger = trigger,
        start = readFrom + start,
        end = readFrom + source.length,
        window = window,
        contextBefore = contextBefore(source, start, truncated = readFrom > 0),
    )
}

private val WHITESPACE = Regex("\\s+")

/**
 * [from] 과 [to] 에서 **정말로 달라진 가운데 토막**만 잘라 낸다. 같으면 null.
 *
 * 창이 "무엇을 고쳤나" 를 찍을 때 엔진이 돌려준 `corrections` 목록을 그대로 쓰면 안 된다.
 * 그 목록은 [NBestCorrector] 의 **재분절 이전** 값이라 화면의 글과 어긋난다 — 실측:
 * 문서에는 `아버지가 방에 들어가신다` 가 들어갔는데 목록은 `아버지 가방에 들어가신다`
 * 라고 말한다. 정반대 분절이다.
 *
 * 그래서 목록을 믿지 않고 **실제로 넣기 전과 후의 글**에서 직접 뽑는다. 이 값은 화면과
 * 어긋날 수가 없다. 어절 단위로 앞뒤의 같은 부분을 벗겨 내는 것이 전부다.
 */
fun changedSpan(from: String, to: String): Pair<String, String>? {
    if (from == to) return null
    val a = from.trim().split(WHITESPACE)
    val b = to.trim().split(WHITESPACE)

    var head = 0
    while (head < a.size && head < b.size && a[head] == b[head]) head++

    var tail = 0
    while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++

    val left = a.subList(head, a.size - tail).joinToString(" ")
    val right = b.subList(head, b.size - tail).joinToString(" ")
    return if (left.isEmpty() && right.isEmpty()) null else left to right
}

/**
 * `[start, end)` 를 [replacementLength] 글자로 갈아 끼운 뒤 커서를 둘 자리.
 *
 * 세 경우뿐이다. 커서가 구간 **뒤**면 길이 차이만큼 밀린다 — 사용자가 교정을 기다리는
 * 동안 계속 친 글자가 여기 있다. 구간 **앞**이면 그대로다. 구간 **안**이면 갈아 끼운
 * 글 끝으로 보낸다 — 그 자리는 사라졌으니 가장 가까운 성한 자리로 옮기는 것이다.
 */
fun caretAfterSplice(caret: Int, start: Int, end: Int, replacementLength: Int): Int {
    val delta = replacementLength - (end - start)
    return when {
        caret >= end -> caret + delta
        caret <= start -> caret
        else -> start + replacementLength
    }
}
