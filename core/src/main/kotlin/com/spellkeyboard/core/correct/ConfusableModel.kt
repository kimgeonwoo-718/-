package com.spellkeyboard.core.correct

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.math.exp

/**
 * 소리가 같거나 비슷해 헷갈리는 말('나을까/낳을까')을 **문맥으로 가르는** 작은 모델.
 *
 * 규칙이 아니라 **센 값**이다. `tools/confusable/train.py` 가 실제 글(청원·영화평·댓글·번역문 2만 5천
 * 용례)에서 두 말이 어떤 말 곁에 쓰이는지를 세어 가중치로 구워 둔다. 새 낱말이 와도 되는 이유는
 * 낱말이 아니라 **조사와 이웃 낱말**을 보기 때문이다 — '삼전이 ○○' 는 처음 보는 말이어도 '이'가 붙었으니
 * 낫다 쪽이다. 틀린 문장이 나오면 규칙을 짜지 않고 `tools/confusable/examples/` 에 적어 다시 굽는다.
 *
 * ## 무엇을 보나
 *
 * 대상 어절 앞 두 어절(입력 중에는 뒤를 못 본다), 글 전체를 고칠 때는 뒤 두 어절까지. 어절마다 통째로,
 * 마지막 한 음절(조사 자리), 마지막 두 음절. 거기에 대상 어절의 어미와 앞 어절 조사의 짝.
 * **대상 어절의 첫 글자(낳/나/낫)는 보지 않는다** — 그게 정답이다.
 *
 * **[features] 는 `train.py` 의 `features()` 와 똑같아야 한다.** 한쪽만 바꾸면 값이 어긋나고,
 * `ConfusableModelTest` 의 골든 파일이 그걸 잡는다.
 *
 * ## 파일 형식 (`confusable.bin.gz`, UTF-8 글)
 *
 * ```
 * KSCF <버전> <양자화>
 * model <짝> <rt|all> <앞 어절 수> <뒤 어절 수> <편향>
 * w <특징> <가중치>          (가중치와 편향은 round(값 × 양자화) 한 정수)
 * ```
 *
 * `rt` 는 입력 중(뒤를 못 봄), `rt1` 은 입력 중 다음 어절이 막 나온 뒤(관형형 용), `all` 은 글 전체를 고칠 때.
 */
class ConfusableModel private constructor(
    private val quant: Int,
    private val blocks: Map<String, Block>
) {

    internal class Block(val left: Int, val right: Int, val bias: Int) {
        val weights = HashMap<String, Int>(1 shl 15)
    }

    /** 뒤 어절을 얼마나 볼 수 있나. 모델은 이 셋을 따로 배운다. */
    enum class Look(internal val key: String) {
        /** 입력 중. 뒤를 못 본다. */
        NONE("rt"),

        /** 입력 중이지만 다음 어절이 막 나왔다. 한 어절 뒤까지 본다. */
        NEXT("rt1"),

        /** 글 전체. 두 어절 뒤까지 본다. */
        ALL("all")
    }

    /**
     * 대상 어절이 **1 번 쪽**(낳다)일 확률. 그 짝의 모델이 없으면 null.
     *
     * @param words 대상이 든 문장의 어절들(공백으로 자른 것)
     * @param i 대상의 위치
     * @param bos words[0] 이 문장 첫머리인가
     * @param look 뒤 어절을 얼마나 볼 수 있나
     */
    fun probability(pair: String, look: Look, words: List<String>, i: Int, bos: Boolean): Double? {
        val block = blocks[pair + ":" + look.key] ?: return null
        var z = block.bias
        for (f in features(words, i, bos, block.left, block.right)) {
            z += block.weights[f] ?: 0
        }
        return 1.0 / (1.0 + exp(-z.toDouble() / quant))
    }

    fun has(pair: String): Boolean = Look.values().all { blocks.containsKey(pair + ":" + it.key) }

    companion object {
        const val RESOURCE = "/confusable.bin.gz"
        private const val VERSION = 1

        fun parse(input: InputStream): ConfusableModel {
            BufferedReader(InputStreamReader(GZIPInputStream(input), Charsets.UTF_8)).use { reader ->
                val header = reader.readLine()?.split('\t') ?: error("빈 파일이다")
                require(header.size >= 3 && header[0] == "KSCF") { "헷갈리는 말 모델 파일이 아니다" }
                require(header[1].toInt() == VERSION) { "모델 버전이 다르다: ${header[1]}" }
                val quant = header[2].toInt()
                val blocks = HashMap<String, Block>()
                var current: Block? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    val p = line.split('\t')
                    when (p[0]) {
                        "model" -> {
                            current = Block(left = p[3].toInt(), right = p[4].toInt(), bias = p[5].toInt())
                            blocks[p[1] + ":" + p[2]] = current
                        }
                        "w" -> (current ?: error("model 줄 앞에 w 줄이 있다")).weights[p[1]] = p[2].toInt()
                        else -> error("알 수 없는 줄: $line")
                    }
                }
                return ConfusableModel(quant, blocks)
            }
        }

        /** 묶음 안에 구워 둔 모델을 읽는다. 없거나 깨졌으면 null. */
        fun loadBundled(): ConfusableModel? =
            runCatching {
                ConfusableModel::class.java.getResourceAsStream(RESOURCE)?.use { parse(it) }
            }.getOrNull()

        private fun hangulOnly(word: String): String {
            val sb = StringBuilder(word.length)
            for (c in word) if (c in '가'..'힣') sb.append(c)
            return sb.toString()
        }

        /**
         * 대상 어절의 문맥 특징. **`train.py` 의 `features()` 와 똑같아야 한다.**
         *
         * 한글 음절만 남긴 어절로 본다(부호·영문·자모는 버린다).
         */
        internal fun features(words: List<String>, i: Int, bos: Boolean, left: Int, right: Int): Set<String> {
            val f = LinkedHashSet<String>()
            val target = hangulOnly(words[i])
            val ending = if (target.length > 1) target.substring(1, minOf(4, target.length)) else ""
            f.add("E:$ending")

            for (k in 1..left) {
                val j = i - k
                if (j < 0) {
                    if (bos) f.add("L$k:<s>")
                    break
                }
                val w = hangulOnly(words[j])
                if (w.isEmpty()) {
                    f.add("L$k:<p>")
                    continue
                }
                f.add("L$k:$w")
                f.add("L${k}s1:${w.takeLast(1)}")
                f.add("L${k}s2:${w.takeLast(2)}")
                f.add("B:$w")
                if (k == 1) {
                    f.add("c1:${w.takeLast(1)}|$ending")
                    f.add("c2:${w.takeLast(2)}|$ending")
                }
            }

            for (k in 1..right) {
                val j = i + k
                if (j >= words.size) {
                    f.add("R$k:</s>")
                    break
                }
                val w = hangulOnly(words[j])
                if (w.isEmpty()) {
                    f.add("R$k:<p>")
                    continue
                }
                f.add("R$k:$w")
                f.add("R${k}s1:${w.take(1)}")
                f.add("R${k}e1:${w.takeLast(1)}")
                f.add("B:$w")
            }
            if (right > 0 && words[i].lastOrNull() in SENTENCE_END_MARKS) f.add("endsent")
            return f
        }

        // train.py 가 '.?!' 만 본다('…' 는 안 본다). 같게 둔다.
        private val SENTENCE_END_MARKS = setOf('.', '?', '!')
    }
}
