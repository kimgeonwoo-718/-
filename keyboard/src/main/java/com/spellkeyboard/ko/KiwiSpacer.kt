package com.spellkeyboard.ko

import android.content.Context
import android.util.Log
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
class KiwiSpacer private constructor(private val kiwi: Kiwi) : LongSpacer {

    /** [isOneWord] 의 답을 기억해 둔다. 같은 낱말이 자주 되돌아온다. 넘치면 통째로 버린다. */
    private val oneWordCache = HashMap<String, Boolean>()

    override fun space(text: String): String? {
        val tokens = runCatching {
            kiwi.tokenize(text, Kiwi.AnalyzeOption(Kiwi.Match.allWithNormalizing))
        }.getOrNull() ?: return null

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
            if (token.tag == Kiwi.POSTag.vx && token.form == GLUED_AUXILIARY) continue
            if (prev != null && prev.tag in NOUNS && token.tag in NOUNS &&
                isOneWord(text.substring(prev.position, token.position + token.length))
            ) continue
            breakAt[token.position] = true
        }

        val out = StringBuilder(text.length + 16)
        for (index in text.indices) {
            val char = text[index]
            if (breakAt[index] && out.isNotEmpty() && !out.last().isWhitespace() && !char.isWhitespace()) {
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
            kiwi.tokenize(joined, Kiwi.AnalyzeOption(Kiwi.Match.allWithNormalizing))
        }.getOrNull() ?: return false
        val one = parts.size == 1 && parts[0].tag in NOUNS
        if (oneWordCache.size >= CACHE_LIMIT) oneWordCache.clear()
        oneWordCache[joined] = one
        return one
    }

    fun close() {
        runCatching { kiwi.close() }
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

        /** 앞말에 붙여 쓰는 보조용언. '-어지다' 하나뿐이다. */
        private const val GLUED_AUXILIARY = "지"

        /**
         * 어절을 시작할 수 있는 품사. 조사·어미·접미사는 앞말에 붙으므로 뺀다.
         *
         * 이 목록이 곧 띄어쓰기 규칙이다. 넓히면 재현율이 오르고 정밀도가 떨어진다 —
         * 지금 값에 [isOneWord] 까지 얹어 공백 없는 글에서 경계 F1 96.5%, 문장 통째 69.3% 다.
         */
        private val WORD_STARTS: Set<Byte> = setOf(
            Kiwi.POSTag.nng, Kiwi.POSTag.nnp, Kiwi.POSTag.nnb, Kiwi.POSTag.nr, Kiwi.POSTag.np,
            Kiwi.POSTag.vv, Kiwi.POSTag.va, Kiwi.POSTag.vx,
            Kiwi.POSTag.mag, Kiwi.POSTag.maj, Kiwi.POSTag.mm, Kiwi.POSTag.ic,
            Kiwi.POSTag.xpn, Kiwi.POSTag.xr,
            // 부정지정사 '아니다'. 이게 빠져 있어서 '게아니라고'·'거아니냐' 가 안 띄워졌다.
            Kiwi.POSTag.vcn,
            Kiwi.POSTag.sl, Kiwi.POSTag.sh, Kiwi.POSTag.sn
        )

        /** 합성어인지 따져 볼 대상. 명사 둘이 붙어 있을 때만 본다. */
        private val NOUNS: Set<Byte> = setOf(Kiwi.POSTag.nng, Kiwi.POSTag.nnp)

        /** 기억해 둘 낱말 수. 넘치면 통째로 버린다 — 다시 물어보면 되는 값이다. */
        private const val CACHE_LIMIT = 2048

        /**
         * Kiwi 를 올린다. **오래 걸린다(실기기에서 수 초)** — 반드시 다른 스레드에서 불러라.
         *
         * 못 올리면 null 이다. 32비트 폰(네이티브 라이브러리 없음), 저장 공간 부족,
         * 메모리 부족 — 어느 쪽이든 교정 자체는 계속돼야 하므로 조용히 넘어간다.
         */
        fun open(context: Context): KiwiSpacer? = runCatching {
            val modelDir = unpack(context)
            val builder = KiwiBuilder(
                modelDir.absolutePath,
                1, // 스레드 하나. 키보드에서 여러 개를 띄울 이유가 없다.
                KiwiBuilder.BuildOption.default_,
                KiwiBuilder.ModelType.none
            )
            KiwiSpacer(builder.build())
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
