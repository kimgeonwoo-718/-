package com.spellkeyboard.core.lm

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * 어절 단위 언어모델. 어절 빈도(유니그램)와 어절 연쇄 빈도(바이그램)를 든다.
 *
 * ## 왜 이게 필요한가
 *
 * 형태소 사전(`Spacer`)은 "이 어절이 한국어로 말이 되는가"만 안다. '있는대요'와 '있는데요'는
 * 둘 다 말이 된다 — 그런데 사람들은 후자를 백 배쯤 더 쓴다. '뭐 하 새요'를 '뭐 하세요'로
 * 합치려면 "'하세요'라는 어절이 흔하고 '뭐' 뒤에 잘 온다"는 것을 알아야 한다. 그 지식이
 * 여기 있다. 말뭉치(국민청원·KorNLI·뉴스·자막)에서 `tools/build_lm.py` 가 굽는다.
 *
 * ## 파일 형식 (`lm.bin`, 리틀엔디언)
 *
 * 어절 문자열은 저장하지 않는다. 64비트 FNV-1a 해시의 위 16비트로 버킷을 고르고, 아래
 * 32비트를 버킷 안에 정렬해 둔다 (실효 48비트 — 수백만 항목에 대해 우연히 겹칠 확률은
 * 무시할 만하다). 항목마다 빈도를 `round(8·ln(count))` 한 바이트로 양자화해 둔다.
 * 한 항목이 5바이트라 어절 60만 + 연쇄 200만이 13MB 남짓이다. 압축은 안 되지만
 * (해시는 난수다) mmap 으로 열어 건드린 페이지만 메모리에 든다.
 *
 * ```
 * "KSLM" u32 version
 * u32 uniCount  u32 biCount  f32 lnTotalTokens
 * u32[65537] uniBucketOffset   u32[65537] biBucketOffset
 * uniCount × { u32 low32, u8 q }
 * biCount  × { u32 low32, u8 q }
 * ```
 *
 * 바이그램 키는 `"앞어절 뒤어절"` (사이에 공백 하나). 문장 첫 어절은 앞이 [BOS] 다.
 */
class LanguageModel internal constructor(private val buffer: ByteBuffer) {

    private val uniCount: Int
    private val biCount: Int
    private val uniBucketOffset: Int
    private val biBucketOffset: Int
    private val uniEntryOffset: Int
    private val biEntryOffset: Int

    /** ln(전체 어절 토큰 수). 유니그램 확률은 `ln(count) - lnTotal`. */
    val lnTotal: Float

    init {
        require(buffer.getInt(0) == MAGIC) { "언어모델 파일이 아니다" }
        require(buffer.getInt(4) == VERSION) { "언어모델 버전이 다르다: ${buffer.getInt(4)}" }
        uniCount = buffer.getInt(8)
        biCount = buffer.getInt(12)
        lnTotal = buffer.getFloat(16)
        uniBucketOffset = 20
        biBucketOffset = uniBucketOffset + (BUCKETS + 1) * 4
        uniEntryOffset = biBucketOffset + (BUCKETS + 1) * 4
        biEntryOffset = uniEntryOffset + uniCount * ENTRY_BYTES
    }

    /** 어절의 ln(빈도). 모르는 어절이면 null. */
    fun lnCount(word: String): Float? = lookup(word, uniBucketOffset, uniEntryOffset)

    /** 연쇄 `prev word` 의 ln(빈도). 없으면 null. */
    fun lnBigramCount(prev: String, word: String): Float? =
        lookup("$prev $word", biBucketOffset, biEntryOffset)

    /** 어절 하나의 ln 확률. 모르는 어절이면 null — 호출자가 따로 값을 매긴다. */
    fun lnUnigram(word: String): Float? = lnCount(word)?.let { it - lnTotal }

    /**
     * 앞 어절이 주어졌을 때의 ln 확률.
     *
     * 연쇄 확률 `c(prev word) / c(prev)` 과 유니그램 확률에 [BACKOFF_WEIGHT] 를 곱한 것을
     * **더한다**(보간). 연쇄를 알 때와 모를 때의 차이가 무한정 벌어지지 않게 하려는 것이다 —
     * '내 여동생'은 본 적 있고 '네 여동생'은 없다는 이유만으로 '네'를 '내'로 바꾸면 안 된다.
     * 정규화된 확률은 아니지만, 같은 창 안의 가설끼리 비교하는 데는 충분하다.
     *
     * @return 모르는 어절이면 null.
     */
    fun lnConditional(prev: String?, word: String): Float? {
        val uni = lnUnigram(word) ?: return null
        var probability = BACKOFF_WEIGHT * exp(uni)
        if (prev != null) {
            val bi = lnBigramCount(prev, word)
            val prevCount = lnCount(prev)
            if (bi != null && prevCount != null) {
                // 양자화 때문에 연쇄가 앞 어절보다 아주 조금 많게 나올 수 있다. 0 을 넘기지 않는다.
                probability += exp(minOf(bi - prevCount, 0f))
            }
        }
        return ln(probability)
    }

    private fun lookup(key: String, bucketOffset: Int, entryOffset: Int): Float? {
        val hash = fnv1a64(key)
        val bucket = (hash ushr 48).toInt()
        val low = hash.toInt()
        var lo = buffer.getInt(bucketOffset + bucket * 4)
        var hi = buffer.getInt(bucketOffset + (bucket + 1) * 4) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val at = entryOffset + mid * ENTRY_BYTES
            val value = buffer.getInt(at)
            when {
                value.toUInt() < low.toUInt() -> lo = mid + 1
                value.toUInt() > low.toUInt() -> hi = mid - 1
                else -> return (buffer.get(at + 4).toInt() and 0xFF) / QUANT
            }
        }
        return null
    }

    companion object {
        /** 문장 첫머리를 뜻하는 가짜 어절. `tools/build_lm.py` 와 같아야 한다. */
        const val BOS = "<s>"

        /** 유니그램 확률에 곱하는 보간 무게. 연쇄를 모르면 유니그램에 ln(0.4) 를 얹는 셈이다. */
        const val BACKOFF_WEIGHT = 0.4f
        const val BACKOFF = -0.9163f

        const val RESOURCE = "/lm.bin.gz"

        private const val MAGIC = 0x4D4C534B  // "KSLM" 을 리틀엔디언으로 읽은 값
        private const val VERSION = 1
        private const val BUCKETS = 1 shl 16
        private const val ENTRY_BYTES = 5
        private const val QUANT = 8f

        /** 64비트 FNV-1a. 파이썬 쪽과 바이트 단위로 같아야 한다. */
        fun fnv1a64(text: String): Long {
            var hash = -0x340d631b7bdddcdbL  // 0xcbf29ce484222325
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                hash = hash xor (byte.toLong() and 0xFF)
                hash *= 0x100000001b3L
            }
            return hash
        }

        /** ln(count) 를 파일과 같은 방식으로 양자화한다. 테스트에서 기대값을 만들 때 쓴다. */
        fun quantize(count: Long): Int = Math.round(QUANT * ln(count.toDouble())).toInt().coerceIn(0, 255)

        /** 리소스의 압축 데이터를 [cacheDir] 에 풀고 mmap 해서 연다. 처음 한 번만 푼다. */
        fun open(cacheDir: File): LanguageModel = LanguageModel(mapResource(RESOURCE, File(cacheDir, "lm.bin")))

        /** 이미 메모리에 있는 파일 내용으로 연다. 테스트용. */
        fun of(bytes: ByteArray): LanguageModel =
            LanguageModel(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

        private fun mapResource(resource: String, target: File): ByteBuffer {
            if (!target.exists() || target.length() == 0L) {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                openResource(resource).use { input ->
                    GZIPInputStream(input, 1 shl 16).use { gz ->
                        temp.outputStream().buffered().use { out -> gz.copyTo(out) }
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        private fun openResource(name: String): InputStream =
            LanguageModel::class.java.getResourceAsStream(name)
                ?: throw IllegalStateException("언어모델 리소스를 찾지 못했다: $name")
    }
}
