package com.spellkeyboard.core.correct

import com.spellkeyboard.core.lm.ContextCorrector
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.LongSpacer
import com.spellkeyboard.core.spacing.Spacer
import com.spellkeyboard.core.spacing.Speller

/** [CorrectionEngine.correct] 의 결과. */
data class CorrectionResult(
    val original: String,
    val text: String,
    val corrections: List<Correction>
) {
    val changed: Boolean get() = text != original
}

/**
 * 커서 앞 텍스트를 얼마나 지우고 무엇으로 바꿔 쓸지.
 *
 * IME 는 이 값을 받아 `deleteSurroundingText(deleteBefore, 0)` 후
 * `commitText(replacement)` 하면 된다.
 */
data class TailCorrection(
    val deleteBefore: Int,
    val original: String,
    val replacement: String,
    val corrections: List<Correction>
)

/**
 * 규칙 기반 한국어 맞춤법/띄어쓰기 교정기.
 *
 * 안드로이드에 의존하지 않는 순수 Kotlin 이라 데스크톱에서도 그대로 쓴다.
 * 실시간 입력 경로에서 호출되므로 정규식 몇 개를 순서대로 훑는 것 이상은 하지 않는다.
 */
class CorrectionEngine(
    private val wordRules: List<TextRule> = SpellingRules.WORD,
    private val textRules: List<TextRule> = SpellingRules.CONTEXT + SpacingRules.DEFAULT
) {

    /**
     * 일반 띄어쓰기 복원기. 사전을 푸는 데 시간이 걸려 나중에 끼워 넣을 수 있게 열어 둔다.
     * null 이면 규칙만으로 동작한다 — 패턴은 고치지만 '아버지가방에' 류는 손대지 못한다.
     */
    @Volatile
    var spacer: Spacer? = null
        set(value) {
            field = value
            // 사전이 없던 동안 "고칠 것 없음" 으로 기억해 둔 것들은 이제 틀렸다.
            clearAnalysed()
        }

    /** 사전 기반 맞춤법 교정기. 규칙 사전이 못 잡는 오타를 분석 비용으로 잡는다. */
    @Volatile
    var speller: Speller? = null
        set(value) {
            field = value
            clearAnalysed()
        }

    /**
     * 공백을 아예 안 친 긴 덩어리를 푸는 더 나은 도구. 없으면 [spacer] 가 한다.
     *
     * 같은 말뭉치 3,000 문장을 공백 전부 지우고 재면 우리 형태소 사전은 경계 F1 87.6%,
     * Kiwi 는 96.1% 다(문장 통째로는 44.2% vs 66.2%). 그 차이를 쓰려고 낸 자리다.
     * 안드로이드·Kiwi 에 기대지 않으려고 코어에는 자리만 두고 앱이 끼운다.
     */
    @Volatile
    var longSpacer: LongSpacer? = null
        set(value) {
            field = value
            clearAnalysed()
        }

    /**
     * 형태소 분석기로 오타를 잡는 교정기. 규칙 표가 못 잡는 것을 잡는다.
     *
     * 같은 말뭉치 2,000 문장에 ㅐ/ㅔ 를 하나씩 뒤집어 넣고 되돌려 보면 77.0% → **88.3%** 다.
     * 멀쩡한 글을 건드리는 비율은 그대로다.
     *
     * **파이프라인의 맨 뒤에서 돈다.** 규칙 표와 디코더가 못 잡은 것만 넘어온다.
     */
    @Volatile
    var typoFixer: TypoFixer? = null
        set(value) {
            field = value
            clearAnalysed()
        }

    /**
     * 문맥 교정기. 언어모델이 올라오면 끼운다.
     *
     * 이게 있으면 [spacer] 와 [speller] 는 직접 쓰지 않는다 — 문맥 교정기가 창 전체를
     * 한 번에 풀면서 띄어쓰기·맞춤법·어절 합치기를 같이 결정한다. 어절 하나씩 보던
     * 두 부품은 언어모델 파일이 없거나 못 열었을 때의 대비책으로 남는다.
     */
    @Volatile
    var context: ContextCorrector? = null
        set(value) {
            field = value
            clearAnalysed()
        }

    /**
     * 한 번 분석한 어절의 결과.
     *
     * **스페이스를 칠 때마다 커서 앞 몇 어절을 통째로 다시 본다.** 그런데 그중 새로
     * 끝난 것은 하나뿐이고 나머지는 직전에 이미 분석한 것들이다. 형태소 분석은
     * 어절 하나에 수 ms 가 들어서, 이 헛일이 그대로 타이핑을 막는다 — 실기기에서
     * "스페이스 누를 때 키가 씹힌다" 로 나타났다.
     *
     * 어절 하나를 어떻게 고치는지는 앞뒤 문맥과 무관해서(사전과 비용만 본다) 기억해
     * 둬도 안전하다. 사전이 바뀌면 통째로 버린다.
     */
    private val analysed = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > ANALYSIS_CACHE_SIZE
    }

    @Synchronized
    private fun analyse(word: String, compute: (String) -> String): String =
        analysed.getOrPut(word) { compute(word) }

    /**
     * 기억해 둔 것을 버린다. **[analyse] 와 같은 자물쇠를 쓴다.**
     *
     * 읽는 쪽은 실시간 입력 스레드고, 버리는 쪽은 사전·Kiwi·언어모델이 올라오는 딴
     * 스레드다 — 키보드가 뜨고 몇 초 뒤, 사용자가 한창 타이핑하는 그 시점이다.
     * `LinkedHashMap` 은 스레드 안전하지 않고, 게다가 이건 접근 순서 맵이라 **읽기만
     * 해도 내부 연결을 고친다.** 한쪽에만 자물쇠가 있는 것은 그냥 틀린 코드다.
     *
     * **다만 이걸로 교정이 틀려지지는 않는다.** `getOrPut` 은 그 열쇠의 값 아니면 방금
     * 계산한 값을 주지, 남의 값을 주지 않는다. 실제로 일어날 법한 일은 기억해 둔 것이
     * 몇 개 사라지는 것이고 그건 다시 계산하면 그만이다. 재현 시험도 써 봤지만 자물쇠를
     * 떼고도 통과해서 지웠다 — 통과만 하는 시험은 시험이 아니다.
     */
    @Synchronized
    private fun clearAnalysed() {
        analysed.clear()
    }

    /**
     * 문자열 전체를 교정한다.
     *
     * @param contextBefore [text] 바로 앞 어절. 문맥 교정기가 첫 어절의 앞뒤를 볼 때 쓴다.
     *   [LanguageModel.BOS] 면 문장 첫머리, null 이면 모름.
     */
    fun correct(text: String, contextBefore: String? = null): CorrectionResult {
        if (!hasHangul(text)) return CorrectionResult(text, text, emptyList())

        val sink = mutableListOf<Correction>()
        var current = applyWordRules(text, sink)
        val context = this.context
        if (context != null) {
            current = applyLongSplit(current, sink)
            current = applyContext(context, current, contextBefore, sink)
        } else {
            current = applySpacer(current, sink)
            current = applySpeller(current, sink)
        }
        // **오타 교정은 맨 뒤다.** 규칙 표와 디코더가 먼저 손을 대고, 둘 다 못 잡은 것만
        // 넘어온다. 처음에는 맨 앞에 뒀는데 그게 틀렸다 — 정확히 아는 것(규칙)과 근거를
        // 견주는 것(디코더)이 있는데, 넘겨짚는 쪽을 먼저 돌리면 그 답을 뒤집을 수 없다.
        // '햇어' 가 '했어'(규칙이 아는 답) 대신 '해서' 로, '연라할게' 가 '연락할게'
        // (디코더가 아는 답) 대신 '열라할게' 로 굳었다.
        current = applyTypoFixer(current, sink)
        current = applyTextRules(current, sink)
        // 띄어쓰기 교정으로 새 어절이 드러날 수 있어 어절 규칙을 한 번 더 돌린다.
        current = applyWordRules(current, sink)

        return CorrectionResult(text, current, sink)
    }

    /**
     * 글 전체를 한 번에 교정한다. **기기 안에서만 돈다** — 서버도, 한도도, 통신도 없다.
     *
     * 실시간 교정은 커서 앞 세 어절만 본다. 타이핑을 따라가야 하니 그래야 한다.
     * 이건 반대다. 이미 다 쓴 글을 통째로 받아 처음부터 끝까지 고친다.
     *
     * ## 왜 통째로 넘기지 않고 잘라서 넘기나
     *
     * 문맥 교정기는 한 번에 푸는 구간이 [ContextCorrector.MAX_SEGMENT_SYLLABLES] 음절을
     * 넘으면 **그 구간을 통째로 포기한다**(비터비 칸이 음절 제곱으로 늘어서 그렇다).
     * 긴 글을 그대로 넘기면 고쳐지기는커녕 **아무 일도 안 일어나고, 그게 조용히 일어난다.**
     * 그래서 여기서 미리 잘라 준다.
     *
     * 자르는 자리는 두 가지다.
     * - **문장 끝**(`. ! ? …` 과 줄바꿈). 문장이 바뀌면 앞 문맥도 끊긴다.
     * - 한 문장이 그보다 길면 어절 단위로 더 잘게. 이때는 **앞 조각의 마지막 어절을
     *   다음 조각의 앞 문맥으로 넘겨준다** — 안 그러면 자른 자리에서 문맥이 끊겨
     *   그 어절만 유독 엉뚱하게 고쳐진다.
     */
    fun correctAll(text: String): CorrectionResult {
        if (!hasHangul(text)) return CorrectionResult(text, text, emptyList())

        val sink = mutableListOf<Correction>()
        val out = StringBuilder()
        var previous: String? = LanguageModel.BOS

        for (chunk in chunk(text)) {
            if (chunk.isBlank() || !hasHangul(chunk)) {
                out.append(chunk)
                // 줄바꿈이나 부호만 있는 조각을 지났으면 문장이 바뀐 것으로 본다.
                if (chunk.any { it in SENTENCE_ENDERS || it == '\n' }) previous = LanguageModel.BOS
                continue
            }
            val fixed = correct(chunk, previous)
            out.append(fixed.text)
            sink += fixed.corrections
            previous = tailContext(fixed.text)
        }
        return CorrectionResult(text, out.toString(), sink)
    }

    /**
     * 띄어쓰기를 안 친 덩어리가 들어 있나. **훑기만 한다 — 고치지 않는다.**
     *
     * AI 에 보내기 전에 우리가 먼저 풀어 줄지 정하는 데 쓴다. 같은 시험지로 재 보면
     * 띄어쓰기를 통째로 지운 글에서 우리가 문장의 78.1% 를 되살리는데
     * `gemini-3.5-flash-lite` 는 70.0% 고, 게다가 **30%** 를 엉뚱하게 바꾼다.
     * 우리가 잘하는 것을 굳이 돈 주고 시킬 이유가 없다.
     *
     * 그런데 먼저 푸는 데도 시간이 든다(2,000자면 폰에서 몇 초다). 그래서 **풀 것이
     * 있을 때만** 푼다. 이 함수가 그 값을 싸게 판단한다 — 사전도 언어모델도 안 부르고
     * 길이만 센다.
     *
     * 판단 기준은 [applyLongSplit] 과 같다: 여덟 음절이 넘는 한 덩어리. 다만 여기서는
     * "언어모델이 아는 말인가" 까지는 안 본다 — 그건 비싸고, 어차피 푸는 쪽이 다시 본다.
     * 여기서 틀리면 괜히 한 번 더 돌 뿐이지 글이 나빠지지는 않는다.
     */
    fun hasGluedRun(text: String): Boolean {
        var run = 0
        for (ch in text) {
            if (ch in HANGUL_SYLLABLES) {
                run++
                if (run > GLUED_RUN_SYLLABLES) return true
            } else if (ch.isWhitespace()) {
                run = 0
            }
            // 한글도 공백도 아닌 것(부호·숫자·영문)은 덩어리를 끊지 않는다.
            // '오늘은3시에만나자' 처럼 가운데 숫자가 끼어도 붙여 쓴 것은 붙여 쓴 것이다.
        }
        return false
    }

    /** 고친 조각의 마지막 어절. 다음 조각이 이걸 앞 문맥으로 쓴다. */
    private fun tailContext(fixed: String): String? {
        val trimmed = fixed.trimEnd()
        if (trimmed.isEmpty()) return LanguageModel.BOS
        if (trimmed.last() in SENTENCE_ENDERS) return LanguageModel.BOS
        val token = trimmed.substring(trimmed.indexOfLast { it.isWhitespace() } + 1)
        return if (token.all { it in HANGUL_SYLLABLES }) token else null
    }

    /**
     * 커서 앞 텍스트의 끝부분만 교정한다.
     *
     * 사용자가 이미 지나간 문장을 건드리지 않도록 마지막 [maxWords] 어절까지만 본다.
     * 고칠 것이 없으면 null.
     */
    fun correctTail(textBeforeCursor: String, maxWords: Int = DEFAULT_WINDOW_WORDS): TailCorrection? {
        if (textBeforeCursor.isEmpty()) return null

        val start = windowStart(textBeforeCursor, maxWords)
        val window = textBeforeCursor.substring(start)
        val result = correct(window, contextBefore(textBeforeCursor, start))
        if (!result.changed) return null

        return TailCorrection(
            deleteBefore = window.length,
            original = window,
            replacement = result.text,
            corrections = result.corrections
        )
    }

    /** [correctTail] 이 실제로 검사하는 구간. 진단 표시에 쓴다. */
    fun tailWindow(textBeforeCursor: String, maxWords: Int = DEFAULT_WINDOW_WORDS): String =
        textBeforeCursor.substring(windowStart(textBeforeCursor, maxWords))

    /**
     * 어절 규칙을 훑는다.
     *
     * 규칙이 백 개가 넘고 한 번 교정할 때 두 번 도는데, 창 안의 어절 대부분은 직전에
     * 이미 훑은 것이다. 어절 하나에 대한 규칙 적용 결과는 앞뒤와 무관하므로 기억해 둔다.
     */
    private fun applyWordRules(text: String, sink: MutableList<Correction>): String =
        mapWords(text) { word ->
            val fixed = analyse(RULE_KEY + word) {
                wordRules.fold(word) { acc, rule -> rule.apply(acc, mutableListOf()) }
            }
            if (fixed != word) sink += Correction(word, fixed, "맞춤법")
            fixed
        }

    /**
     * 창 전체를 문맥 교정기에 넘긴다. 창과 앞 어절이 같으면 답도 같으므로 기억해 둔다.
     * 무엇이 바뀌었는지는 어절 단위로 짝지어 기록한다 — 개수가 다르면 통째로 하나.
     */
    private fun applyContext(
        context: ContextCorrector,
        text: String,
        contextBefore: String?,
        sink: MutableList<Correction>
    ): String {
        val fixed = analyse(CONTEXT_KEY + (contextBefore ?: "") + "\u0000" + text) {
            context.correct(text, contextBefore) ?: text
        }
        if (fixed != text) {
            val before = text.trim().split(WHITESPACE)
            val after = fixed.trim().split(WHITESPACE)
            if (before.size == after.size) {
                for (k in before.indices) if (before[k] != after[k]) sink += Correction(before[k], after[k], "문맥")
            } else {
                sink += Correction(text.trim(), fixed.trim(), "문맥")
            }
        }
        return fixed
    }

    /**
     * 문맥 교정기가 **손도 못 대는** 긴 덩어리를 형태소 사전이 먼저 푼다.
     *
     * 디코더는 [ContextCorrector.MAX_TOKEN_SYLLABLES] 음절이 넘는 어절을 아예 쳐다보지
     * 않는다(비터비 칸이 음절 제곱으로 늘어서 그렇다). 그런데 **띄어쓰기를 통째로 생략한
     * 글은 30~50음절로 들어온다** — 그게 진짜 쓰임이다. 실기기에서 그런 글에 전체교정을
     * 눌렀더니 공백이 하나도 안 들어갔다.
     *
     * 여기서 대강 풀어 주면 디코더가 그 결과를 받아 다듬는다. 사전이 과하게 나눈 자리는
     * 디코더의 합치기(KIND_MERGE)가 도로 붙인다.
     */
    private fun applyLongSplit(text: String, sink: MutableList<Correction>): String {
        val long = longSpacer ?: LongSpacer { (this.spacer ?: return@LongSpacer null).spaceLong(it) }
        return mapWords(text) { word ->
            if (word.length <= GLUED_RUN_SYLLABLES) return@mapWords word
            // 형태소 사전은 **어절**을 받는다. 끝에 붙은 마침표 하나가 분석을 통째로
            // 실패시켜서, 부호를 떼고 넘긴 뒤 도로 붙인다. 이걸 안 했더니 마지막 조각이
            // 늘 안 풀렸다 — 문장 끝이라 부호가 거의 항상 붙어 있기 때문이다.
            val start = word.indexOfFirst { it in HANGUL_SYLLABLES }
            if (start < 0) return@mapWords word
            val end = word.indexOfLast { it in HANGUL_SYLLABLES } + 1
            val core = word.substring(start, end)
            val floor = if (context?.knowsWord(core) == false) GLUED_RUN_SYLLABLES else LONG_WORD_SYLLABLES
            if (core.length <= floor) return@mapWords word

            val spaced = analyse(LONG_KEY + core) { long.space(core) ?: core }
            if (spaced == core) return@mapWords word
            val whole = word.substring(0, start) + spaced + word.substring(end)
            sink += Correction(word, whole, "띄어쓰기")
            whole
        }
    }

    /**
     * 분석기 오타 교정을 태운다. **공백 수가 달라지면 버린다** — 이 단계는 맞춤법만 맡는다.
     *
     * 창 전체를 한 번에 넘기는 것은 문맥이 필요해서다. '되요' 가 '돼요' 인지 '되어요' 인지는
     * 그 어절만 봐서는 못 가린다.
     */
    private fun applyTypoFixer(text: String, sink: MutableList<Correction>): String {
        val fixer = this.typoFixer ?: return text
        val fixed = analyse(TYPO_KEY + text) { fixer.fix(text) ?: text }
        if (fixed == text) return text
        val before = text.trim().split(WHITESPACE)
        val after = fixed.trim().split(WHITESPACE)
        if (before.size != after.size) return text
        for (k in before.indices) if (before[k] != after[k]) sink += Correction(before[k], after[k], "맞춤법")
        return fixed
    }

    /** 붙여 쓴 덩어리를 어절로 나눈다. 사전이 아직 안 올라왔으면 아무것도 하지 않는다. */
    private fun applySpacer(text: String, sink: MutableList<Correction>): String {
        val spacer = this.spacer ?: return text
        return mapWords(text) { word ->
            val spaced = analyse(SPACE_KEY + word) { spacer.space(word) ?: word }
            if (spaced != word) sink += Correction(word, spaced, "띄어쓰기")
            spaced
        }
    }

    /** 규칙 사전이 못 잡은 오타를 형태소 분석으로 잡는다. */
    private fun applySpeller(text: String, sink: MutableList<Correction>): String {
        val speller = this.speller ?: return text
        return mapWords(text) { word ->
            val fixed = analyse(SPELL_KEY + word) { speller.correct(word) ?: word }
            if (fixed != word) sink += Correction(word, fixed, "맞춤법")
            fixed
        }
    }

    private inline fun mapWords(text: String, transform: (String) -> String): String =
        buildString {
            for (token in TOKEN.findAll(text)) {
                val value = token.value
                if (value.isBlank()) append(value) else append(transform(value))
            }
        }

    private fun applyTextRules(text: String, sink: MutableList<Correction>): String =
        textRules.fold(text) { acc, rule -> rule.apply(acc, sink) }

    companion object {
        /** 기억해 둘 어절 수. 한 문단 분량이면 충분하고, 넘으면 오래된 것부터 버린다. */
        private const val ANALYSIS_CACHE_SIZE = 512

        // 띄어쓰기와 맞춤법은 같은 어절에 서로 다른 답을 내므로 키를 갈라 둔다.
        private const val SPACE_KEY = "\u0000s"
        private const val SPELL_KEY = "\u0000p"
        private const val RULE_KEY = "\u0000r"
        private const val CONTEXT_KEY = "\u0000c"
        private const val LONG_KEY = "\u0000l"
        private const val TYPO_KEY = "\u0000t"

        /**
         * 이보다 긴 어절은 문맥 교정기가 보지 못하므로 형태소 사전이 먼저 푼다.
         * [ContextCorrector.MAX_TOKEN_SYLLABLES] 와 같아야 한다 — 그 값이 디코더의 한계다.
         */
        private const val LONG_WORD_SYLLABLES = ContextCorrector.MAX_TOKEN_SYLLABLES

        /**
         * 언어모델이 모르는 어절이 이보다 길면 **붙여 쓴 덩어리로 보고** [longSpacer] 에 넘긴다.
         *
         * [LONG_WORD_SYLLABLES] 는 "디코더가 아예 못 보는 길이" 지 "디코더가 잘하는 길이" 가
         * 아니다. 공백을 전부 지운 3,000 문장을 길이로 갈라 재 보면 그 차이가 그대로 보인다.
         *
         * | | 14음절 이하 | 넘는 것 |
         * |---|---|---|
         * | 디코더 (예전) | 51.3% | — |
         * | Kiwi | **85.6%** | 72.3% |
         *
         * 짧은 덩어리는 디코더가 반도 못 풀고 있었다. 문지방을 내리면 그 자리를 Kiwi 가 맡는다.
         *
         * | 문지방 | 공백 없는 글 통째 | 멀쩡한 글을 건드림 |
         * |---|---|---|
         * | 14 (예전) | 68.0% | 0.80% |
         * | **8** | **75.8%** | **1.10%** |
         * | 6 | 76.4% | 1.83% |
         * | 5 | 76.5% | 3.47% |
         *
         * 8 에서 멈춘다. 6 은 0.6%p 를 더 얻자고 오교정을 0.7%p 더 내는데, 그 오교정이
         * '중국요리사들 → 중국 요리사들', '스파르타인들 → 스파르타 인들' 처럼 멀쩡한 말을
         * 가르는 것이다. 8 에서 늘어난 오교정은 3,000 문장에 대여섯 건이고, 같이 늘어난
         * 교정 중에는 제대로 고친 것도 있다('발령해야하는 → 발령해야 하는').
         *
         * **언어모델이 아는 어절은 이 문지방을 안 탄다.** 흔한 말은 길어도 그대로 둔다.
         */
        internal const val GLUED_RUN_SYLLABLES = 8

        private val WHITESPACE = Regex("\\s+")

        const val DEFAULT_WINDOW_WORDS = 3

        private val TOKEN = Regex("""\S+|\s+""")

        private fun hasHangul(text: String): Boolean =
            text.any { it.code in 0xAC00..0xD7A3 || it.code in 0x3131..0x3163 }

        /**
         * 창 바로 앞 어절. 창 앞에 아무것도 없으면 문장 첫머리([LanguageModel.BOS]),
         * 앞 어절이 문장 부호로 끝나도 첫머리다. 앞 어절이 한글이 아니면 모름(null).
         *
         * 커서 앞 텍스트는 [FULL_LOOKBEHIND] 만큼만 읽어 오므로, 그 길이를 꽉 채운 채
         * 창 앞이 비었다면 실제로는 앞에 더 있을 수 있다. 그때는 모름으로 둔다.
         */
        internal fun contextBefore(textBeforeCursor: String, windowStart: Int): String? {
            val prefix = textBeforeCursor.substring(0, windowStart).trimEnd()
            if (prefix.isEmpty()) {
                return if (textBeforeCursor.length < FULL_LOOKBEHIND) LanguageModel.BOS else null
            }
            val token = prefix.substring(prefix.indexOfLast { it.isWhitespace() } + 1)
            if (token.last() in SENTENCE_ENDERS) return LanguageModel.BOS
            return if (token.all { it in HANGUL_SYLLABLES }) token else null
        }

        /** `TypingSession.LOOKBEHIND_CHARS` 와 같다. 순환 참조를 피해 여기 다시 적는다. */
        private const val FULL_LOOKBEHIND = 64
        private val SENTENCE_ENDERS = setOf('.', '!', '?', '…')
        private val HANGUL_SYLLABLES = '가'..'힣'

        /**
         * 한 번에 문맥 교정기에 넘길 최대 음절 수.
         *
         * [ContextCorrector.MAX_SEGMENT_SYLLABLES] 보다 **넉넉히 작아야** 한다. 여기서
         * 세는 것은 조각 전체 길이인데, 교정기가 재는 것은 공백을 뺀 음절 수라 둘이
         * 정확히 같지 않다. 아슬아슬하게 맞춰 두면 어떤 문장에서만 조용히 건너뛰어진다.
         */
        internal const val CHUNK_SYLLABLES = 36

        /**
         * 글을 교정하기 좋은 크기로 자른다. 자른 조각을 **그대로 이어 붙이면 원문**이다 —
         * 공백도 줄바꿈도 하나 잃지 않는다. 그래야 고친 글에 원래 줄 모양이 남는다.
         */
        internal fun chunk(text: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var syllables = 0

            fun flush() {
                if (current.isNotEmpty()) {
                    out += current.toString()
                    current.setLength(0)
                    syllables = 0
                }
            }

            for (token in TOKEN.findAll(text)) {
                val value = token.value
                if (value.isBlank()) {
                    // 줄바꿈은 문단이 바뀌는 자리다. 여기서 끊어야 앞 문맥도 같이 끊긴다.
                    if (value.contains('\n')) {
                        flush()
                        out += value
                    } else {
                        current.append(value)
                    }
                    continue
                }
                // 이 어절을 넣으면 넘치는가. 넘치면 먼저 내보낸다 — 어절은 쪼개지 않는다.
                if (syllables > 0 && syllables + value.length > CHUNK_SYLLABLES) flush()
                current.append(value)
                syllables += value.length
                // 문장이 끝났으면 여기서 끊는다.
                if (value.last() in SENTENCE_ENDERS) flush()
            }
            flush()
            return out
        }

        /** 뒤에서부터 [maxWords] 개의 어절을 포함하는 창의 시작 위치를 찾는다. */
        internal fun windowStart(text: String, maxWords: Int): Int {
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
    }
}
