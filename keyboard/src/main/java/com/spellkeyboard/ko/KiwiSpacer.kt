package com.spellkeyboard.ko

import android.content.Context
import android.util.Log
import com.spellkeyboard.core.correct.TypoFixer
import com.spellkeyboard.core.spacing.LongSpacer
import kr.pe.bab2min.Kiwi
import kr.pe.bab2min.KiwiBuilder
import java.io.File

/**
 * Kiwi 형태소 분석기로 **공백을 아예 안 친 글**을 띄운다.
 *
 * ## 왜 여기만 쓰나
 *
 * 같은 말뭉치 3,000 문장으로 두 가지 자를 대 봤다.
 *
 * | | 우리 엔진 | Kiwi |
 * |---|---|---|
 * | 멀쩡한 문장을 건드리는 비율 | **0.37%** | 6.9~18% |
 * | 공백 전부 지운 글의 경계 F1 | 87.6% | **96.1%** |
 *
 * 같은 엔진이 자에 따라 이기고 진다. Kiwi 는 멀쩡한 글에 들이대면 멀쩡한 띄어쓰기를 헤집는다.
 * 그래서 **띄어쓰기가 아예 없는 덩어리에만** 쓴다 — 거기엔 망가뜨릴 띄어쓰기가 없고,
 * 그 자리에서는 확실히 이긴다.
 *
 * ## 없을 수도 있다
 *
 * Kiwi 네이티브 라이브러리는 **arm64-v8a 에만** 있다. 32비트 폰에서는 안 올라오고,
 * 모델을 꺼내다 실패할 수도 있다. 그때는 [open] 이 null 을 주고 기존 엔진이 그대로 한다.
 * 이 기능이 없다고 교정이 멈추면 안 된다.
 */
class KiwiSpacer private constructor(
    private val kiwi: Kiwi,
    private val typo: KiwiBuilder.PreparedTypoTransformer?,
    private val knownWord: (String) -> Boolean
) : LongSpacer, TypoFixer {

    /** [isOneWord] 의 답을 기억해 둔다. 같은 낱말이 자주 되돌아온다. 넘치면 통째로 버린다. */
    private val oneWordCache = HashMap<String, Boolean>()

    /**
     * 이미 닫혔는가.
     *
     * 전체교정은 **다른 스레드**에서 돈다. 그 사이에 키보드를 닫으면 [close] 가 네이티브
     * 객체를 놓아 버리고, 돌고 있던 쪽이 그 위에서 계속 읽는다 — 자바 예외가 아니라
     * **프로세스가 통째로 죽는 종류**다. 닫기와 쓰기를 같은 자물쇠로 묶고 깃발로 한 번 더 막는다.
     */
    @Volatile
    private var closed = false

    @Synchronized
    override fun space(text: String): String? {
        if (closed) return null
        val tokens = runCatching { kiwi.tokenize(text, Kiwi.AnalyzeOption(MATCH)) }.getOrNull() ?: return null

        // **토큰 표면을 이어 붙이면 안 된다.** 축약형('했' = 하 + 았)은 여러 형태소가 같은
        // 자리를 가리켜서 글자가 겹친다. 원문은 그대로 두고 공백만 끼워 넣는다.
        val breakAt = BooleanArray(text.length + 1)
        var previous: Kiwi.Token? = null
        for (token in tokens) {
            val prev = previous
            previous = token
            if (token.position <= 0 || token.tag !in WORD_STARTS) continue
            // 보조용언 '-어지다' 는 앞말에 붙여 쓴다('행복해지는', '이어지는'). Kiwi 는 이걸
            // 따로 떼어 놓는데, 그대로 두면 '행복해 지는' 이 된다.
            //
            // '하' 는 넣지 않았다. 같은 보조용언이라도 '해야 하는' 은 띄어 쓴다 —
            // 넣었더니 말뭉치 숫자가 되레 내려갔다(통째 67.6% → 65.1%).
            if (token.tag in AUXILIARY_VERBS && token.form == GLUED_AUXILIARY) continue
            // '-아/어 하다' 도 한 낱말처럼 붙여 쓴다('좋아하다', '행복해하다'). 앞이 '-아/어'
            // 어미일 때만이다 — '해야 하는' 의 '하' 는 앞이 '야' 라 걸리지 않는다.
            if (token.tag in AUXILIARY_VERBS && token.form == GLUED_HADA &&
                prev != null && prev.tag == Kiwi.POSTag.ec && prev.form.lastOrNull() in EU_ENDINGS
            ) continue
            // '그것'·'이곳'·'그때' 는 한 낱말이다. 관형사 + 의존명사로 잘리면 갈라진다.
            if (token.tag == Kiwi.POSTag.nnb && token.form in BOUND_JOIN &&
                prev != null && prev.tag == Kiwi.POSTag.mm && prev.form in DEMONSTRATIVES
            ) continue
            if (prev != null && prev.tag in NOUNS && token.tag in NOUNS &&
                isOneWord(text.substring(prev.position, token.position + token.length))
            ) continue
            breakAt[token.position] = true
        }

        val out = StringBuilder(text.length + 16)
        for (index in text.indices) {
            val char = text[index]
            if (breakAt[index] && out.isNotEmpty() && !out.last().isWhitespace() &&
                !char.isWhitespace() && out.last() !in OPENERS
            ) {
                out.append(' ')
            }
            out.append(char)
        }
        val spaced = out.toString()
        return if (spaced == text) null else spaced
    }

    /**
     * 명사 둘을 붙여 놓고 그것만 따로 돌려 봤을 때 형태소 하나로 나오면 합성어다.
     *
     * 필요한 이유는 이렇다. 규칙이 "내용어 태그 앞에서 띄운다" 라서 명사가 둘 붙어 있으면
     * 무조건 갈라진다 — '개똥으로' 가 문장 속에서는 개/NNG + 똥/NNG 로 잘려 '개 똥으로' 가 됐다.
     * 그런데 '개똥' 만 따로 돌리면 Kiwi 사전이 이걸 한 낱말로 안다. 반대로 '일처리' 는
     * 혼자 돌려도 일 + 처리 로 갈린다 — 그건 진짜 두 낱말이니 띄우는 게 맞다.
     *
     * "명사 + 명사는 무조건 안 띄운다" 는 뭉툭한 규칙도 대 봤는데 재현율이 무너졌다
     * (97.0% → 88.4%, 통째 67.6% → 49.6%). 사전에 물어보는 이 방식은 정밀도만 올린다
     * (95.9 → 96.3%, 재현율 96.9 → 96.8%, 통째 68.7 → 69.3%).
     */
    private fun isOneWord(joined: String): Boolean {
        oneWordCache[joined]?.let { return it }
        val parts = runCatching {
            kiwi.tokenize(joined, Kiwi.AnalyzeOption(MATCH))
        }.getOrNull() ?: return false
        val one = parts.size == 1 && parts[0].tag in NOUNS
        if (oneWordCache.size >= CACHE_LIMIT) oneWordCache.clear()
        oneWordCache[joined] = one
        return one
    }

    /**
     * 오타를 맞춤법만 고친다. 띄어쓰기는 건드리지 않는다.
     *
     * ## 어떻게
     *
     * Kiwi 에 **오타 변형기**가 들어 있다. 켜면 분석기가 "이 글자를 저 글자로 바꾸면 말이
     * 되는가" 를 같이 따져서, 바뀐 형태소에 [Kiwi.Token.typoCost] 를 남긴다. 그 값이 붙은
     * 어절만 형태소로 되짚어 다시 만든다.
     *
     * ## 왜 어절 단위인가
     *
     * [Kiwi.join] 으로 문장을 통째로 다시 만들면 **띄어쓰기까지 갈아엎는다** — 멀쩡한 글의
     * 30%를 건드렸다('되어 → 돼', '체크포인트 → 체크 포인트'). 문맥은 문장 전체로 보되,
     * 되짚는 것은 오타가 난 어절 하나로 좁힌다.
     *
     * ## 말뭉치가 아는 낱말은 안 건드린다
     *
     * [knownWord] 가 마지막 문지방이다. 없이 돌리면 ㅐ/ㅔ 되살림이 89.3% 로 1.2%p 높지만,
     * 멀쩡한 글을 건드리는 비율이 0.90% 로 두 배 오른다 — '계획이 → 계획의', '줄리안 →
     * 줄리아' 처럼 **멀쩡한 낱말을 다른 멀쩡한 낱말로** 바꾸는 것이 그 차이다.
     *
     * | | ㅐ/ㅔ 되살림 | 멀쩡한 글 건드림 |
     * |---|---|---|
     * | 안 씀 | 77.0% | 0.70% |
     * | 씀 | **88.1%** | **0.80%** |
     */
    @Synchronized
    override fun fix(text: String): String? {
        if (closed) return null
        val transformer = typo ?: return null
        val tokens = runCatching {
            kiwi.tokenize(text, Kiwi.AnalyzeOption(MATCH, null, 0, 0f, transformer, TYPO_THRESHOLD))
        }.getOrNull() ?: return null
        if (tokens.none { it.typoCost > 0f }) return null

        val out = StringBuilder(text)
        // 뒤에서부터 바꾼다. 앞에서부터 바꾸면 길이가 달라진 만큼 뒤쪽 자리가 어긋난다.
        for (word in wordRanges(text).asReversed()) {
            // **긴 덩어리는 손대지 않는다.** 띄어쓰기를 통째로 생략한 글은 어절 하나가
            // 30~50음절로 들어오는데, 그걸 형태소로 되짚어 다시 만들면 오타 한 자 때문에
            // 덩어리 전체가 다시 쓰인다. 이 단계가 맡는 것은 어절 하나의 맞춤법이다.
            if (word.last + 1 - word.first > MAX_TYPO_WORD) continue
            val mine = tokens.filter { it.position >= word.first && it.position + it.length <= word.last + 1 }
            if (mine.isEmpty() || mine.none { it.typoCost > 0f }) continue
            val joined = runCatching {
                kiwi.join(Array(mine.size) { index ->
                    Kiwi.JoinableToken(mine[index]).also { if (index > 0) it.space = Kiwi.Space.no_space }
                })
            }.getOrNull() ?: continue
            // 띄어쓰기가 끼어들었으면 이 단계가 할 일이 아니다.
            if (joined.isEmpty() || joined.any { it.isWhitespace() }) continue
            if (knownWord(text.substring(word.first, word.last + 1))) continue
            out.replace(word.first, word.last + 1, joined)
        }
        val fixed = out.toString()
        return if (fixed == text) null else fixed
    }

    /** 공백으로 나눈 어절들의 자리. */
    private fun wordRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var index = 0
        while (index < text.length) {
            while (index < text.length && text[index].isWhitespace()) index++
            if (index >= text.length) break
            val start = index
            while (index < text.length && !text[index].isWhitespace()) index++
            out += start until index
        }
        return out
    }

    @Synchronized
    fun close() {
        closed = true
        runCatching { kiwi.close() }
        runCatching { typo?.close() }
        oneWordCache.clear()
    }

    companion object {
        private const val TAG = "KiwiSpacer"

        /** 에셋 안 모델 폴더 이름. `keyboard/build.gradle.kts` 의 fetchKiwi 가 여기에 넣는다. */
        private const val ASSET_DIR = "kiwi"

        /** 모델을 꺼내 둘 폴더 이름. 한 번 꺼내면 다시 안 꺼낸다. */
        private const val LOCAL_DIR = "kiwi-model"

        /** 다 꺼냈다는 표시. 중간에 끊긴 것을 온전한 것으로 착각하지 않게 맨 마지막에 쓴다. */
        private const val DONE_MARK = ".complete"

        /**
         * 분석 방식.
         *
         * `allWithNormalizing` 만 쓰다가 세 가지를 더 켰다. 같은 3,000 문장 기준이다.
         *
         * | | 경계 F1 | 문장 통째 |
         * |---|---|---|
         * | allWithNormalizing 만 | 97.28 | 75.5% |
         * | + `oovChrModel` | 97.67 | 79.2% |
         * | + `joinNounPrefix` | (같이 적용) | |
         * | + `mergeSaisiot` | **97.70** | **79.3%** |
         *
         * - **`oovChrModel`**: 사전에 없는 말을 글자 모델로 가른다. 기본값은 규칙만 쓰는
         *   `oovRuleOnly` 라 외국 인명이 통째로 뭉쳤다('패니플로노', '유진뎁스').
         * - **`joinNounPrefix`**: 접두사를 뒷 명사에 붙인다('맨손'). 이걸 손으로 짜 넣었더니
         *   네이티브 옵션과 결과가 **똑같아서** 규칙을 지우고 옵션만 남겼다.
         * - **`mergeSaisiot`**: 사이시옷 합성어를 한 낱말로 둔다.
         *
         * `splitComplex` 는 안 켠다 — 합성명사를 되레 쪼갠다. `joinAffix` 는 차이가 없었다.
         */
        private const val MATCH = Kiwi.Match.allWithNormalizing or
            Kiwi.Match.oovChrModel or
            Kiwi.Match.joinNounPrefix or
            Kiwi.Match.mergeSaisiot

        /** 앞말에 붙여 쓰는 보조용언. '-어지다' 하나뿐이다. */
        private const val GLUED_AUXILIARY = "지"

        /** '-아/어 하다'. 앞이 '-아/어' 어미일 때만 붙인다. */
        private const val GLUED_HADA = "하"

        /** '-아/어' 계열 어미의 끝 글자. */
        private val EU_ENDINGS = setOf('아', '어', '여', '해')

        /** 관형사와 붙어 한 낱말이 되는 의존명사. '그것', '이곳', '그때'. */
        private val BOUND_JOIN = setOf("것", "곳", "때", "거", "쪽", "편", "놈", "년")

        /** 위 의존명사와 붙는 관형사. */
        private val DEMONSTRATIVES = setOf("그", "이", "저")

        /** 보조용언 태그. 불규칙 활용은 값이 따로라 둘 다 본다. */
        private val AUXILIARY_VERBS: Set<Byte> = setOf(Kiwi.POSTag.vx, Kiwi.POSTag.vxi)

        /**
         * 어절을 시작할 수 있는 품사. 조사·어미·접미사는 앞말에 붙으므로 뺀다.
         *
         * 이 목록이 곧 띄어쓰기 규칙이다. 넓히면 재현율이 오르고 정밀도가 떨어진다 —
         * [MATCH] 와 [isOneWord] 까지 얹어 공백 없는 글에서 경계 F1 97.7%, 문장 통째 79.3% 다.
         */
        private val WORD_STARTS: Set<Byte> = setOf(
            Kiwi.POSTag.nng, Kiwi.POSTag.nnp, Kiwi.POSTag.nnb, Kiwi.POSTag.nr, Kiwi.POSTag.np,
            Kiwi.POSTag.vv, Kiwi.POSTag.va, Kiwi.POSTag.vx,
            // **불규칙 활용은 태그 값이 다르다.** '돕는'은 vv 가 아니라 vvi(VV-I), '어떻게'는
            // vai 다. 이게 빠져 있어서 '당신을돕는'·'백악관은어떻게'가 통째로 붙어 나왔다 —
            // ㅂ·ㅎ·ㅅ 불규칙은 한국어에서 드문 말이 아니다.
            Kiwi.POSTag.vvi, Kiwi.POSTag.vai, Kiwi.POSTag.vxi,
            Kiwi.POSTag.mag, Kiwi.POSTag.maj, Kiwi.POSTag.mm, Kiwi.POSTag.ic,
            Kiwi.POSTag.xpn, Kiwi.POSTag.xr,
            // 부정지정사 '아니다'. 이게 빠져 있어서 '게아니라고'·'거아니냐' 가 안 띄워졌다.
            Kiwi.POSTag.vcn,
            Kiwi.POSTag.sl, Kiwi.POSTag.sh, Kiwi.POSTag.sn
        )

        /**
         * 이 부호 바로 뒤에는 띄우지 않는다.
         *
         * Kiwi 는 여는 괄호를 부호로 보고 그 다음 낱말을 새 어절로 시작한다. 그대로 두면
         * '이루어졌다(유나이티드' 가 '이루어졌다( 유나이티드' 가 된다 — 여는 괄호는 뒷말에
         * 붙여 쓴다.
         */
        private const val OPENERS = "([{〈《「『【\u201C\u2018\"'"

        /** 합성어인지 따져 볼 대상. 명사 둘이 붙어 있을 때만 본다. */
        private val NOUNS: Set<Byte> = setOf(Kiwi.POSTag.nng, Kiwi.POSTag.nnp)

        /** 기억해 둘 낱말 수. 넘치면 통째로 버린다 — 다시 물어보면 되는 값이다. */
        private const val CACHE_LIMIT = 2048

        /**
         * 오타 변형을 어디까지 봐줄 것인가. Kiwi 기본값은 2.5 다.
         *
         * 1.5 로 내려도 ㅐ/ㅔ 되살림은 89.3% 로 같고, 이미 틀어진 글을 엉뚱하게 바꾸는 비율만
         * 내려간다(5.5% → 4.4%). 넓힐 이유가 없다.
         */
        private const val TYPO_THRESHOLD = 1.5f

        /** 이보다 긴 어절은 오타 교정을 안 한다. 실제 한국어 어절은 대개 열 자를 안 넘는다. */
        private const val MAX_TYPO_WORD = 12

        /**
         * Kiwi 를 올린다. **오래 걸린다(실기기에서 수 초)** — 반드시 다른 스레드에서 불러라.
         *
         * 못 올리면 null 이다. 32비트 폰(네이티브 라이브러리 없음), 저장 공간 부족,
         * 메모리 부족 — 어느 쪽이든 교정 자체는 계속돼야 하므로 조용히 넘어간다.
         */
        fun open(context: Context, knownWord: (String) -> Boolean = { false }): KiwiSpacer? = runCatching {
            val modelDir = unpack(context)
            val builder = KiwiBuilder(
                modelDir.absolutePath,
                1, // 스레드 하나. 키보드에서 여러 개를 띄울 이유가 없다.
                KiwiBuilder.BuildOption.default_,
                KiwiBuilder.ModelType.none
            )
            // 오타 변형기는 못 만들어도 띄어쓰기는 돌아야 한다.
            val typo = runCatching { KiwiBuilder.basicTypoSet.prepare() }.getOrNull()
            KiwiSpacer(builder.build(), typo, knownWord)
        }.onFailure { Log.w(TAG, "Kiwi 를 올리지 못했다. 기존 엔진으로 간다.", it) }.getOrNull()

        /**
         * 에셋의 모델을 파일로 꺼낸다. 네이티브 쪽이 파일을 mmap 해야 해서 스트림으로는 안 된다.
         *
         * 105MB 라 한 번만 한다. 꺼내다 끊기면 [DONE_MARK] 가 없으니 다음에 다시 꺼낸다.
         */
        private fun unpack(context: Context): File {
            val target = File(context.filesDir, LOCAL_DIR)
            if (File(target, DONE_MARK).exists()) return target

            target.deleteRecursively()
            target.mkdirs()
            val assets = context.assets
            val names = assets.list(ASSET_DIR).orEmpty()
            check(names.isNotEmpty()) { "에셋에 Kiwi 모델이 없다" }
            for (name in names) {
                assets.open("$ASSET_DIR/$name").use { input ->
                    File(target, name).outputStream().use { input.copyTo(it) }
                }
            }
            File(target, DONE_MARK).writeText("")
            return target
        }
    }
}
