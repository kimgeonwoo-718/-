package com.spellkeyboard.core.lm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageModelTest {

    @Test
    fun `해시가 파이썬 쪽과 같다`() {
        // tools/build_lm.py 의 fnv1a64 로 계산한 값.
        assertEquals(-4372753260828767127L, LanguageModel.fnv1a64("안녕"))
        assertEquals(453901623883911358L, LanguageModel.fnv1a64("<s> 안녕"))
        assertEquals(995535133796461581L, LanguageModel.fnv1a64("여기 있는데요"))
    }

    @Test
    fun `양자화가 파이썬 쪽과 같다`() {
        assertEquals(0, LanguageModel.quantize(1))
        assertEquals(24, LanguageModel.quantize(20))
        assertEquals(111, LanguageModel.quantize(1_000_000))
    }

    @Test
    fun `직접 구운 작은 모델에서 빈도를 읽는다`() {
        val lm = build(
            uni = mapOf("여기" to 1000L, "있는데요" to 100L, "있는대요" to 3L, LanguageModel.BOS to 500L),
            bi = mapOf("여기 있는데요" to 20L, "<s> 여기" to 50L),
            total = 1_000_000L
        )
        assertEquals(ln(1000.0).toFloat(), lm.lnCount("여기")!!, 0.07f)
        assertEquals(ln(3.0).toFloat(), lm.lnCount("있는대요")!!, 0.07f)
        assertNull(lm.lnCount("없는말"))
        assertEquals(ln(20.0).toFloat(), lm.lnBigramCount("여기", "있는데요")!!, 0.07f)
        assertNull(lm.lnBigramCount("여기", "있는대요"))
        assertEquals(ln(1_000_000.0).toFloat(), lm.lnTotal, 0.001f)
    }

    @Test
    fun `연쇄를 알면 조건부 확률 모르면 유니그램에 물러선다`() {
        val lm = build(
            uni = mapOf("여기" to 1000L, "있는데요" to 100L, "있는대요" to 3L),
            bi = mapOf("여기 있는데요" to 20L),
            total = 1_000_000L
        )
        val known = lm.lnConditional("여기", "있는데요")!!
        assertEquals((ln(20.0) - ln(1000.0)).toFloat(), known, 0.1f)
        val backoff = lm.lnConditional("여기", "있는대요")!!
        assertEquals((ln(3.0) - ln(1_000_000.0)).toFloat() + LanguageModel.BACKOFF, backoff, 0.1f)
        assertNull(lm.lnConditional("여기", "없는말"))
        // 앞 어절을 모르면(문맥 없음) 유니그램.
        assertEquals((ln(100.0) - ln(1_000_000.0)).toFloat() + LanguageModel.BACKOFF, lm.lnConditional(null, "있는데요")!!, 0.1f)
    }

    @Test
    fun `앱에 실린 모델이 열리고 흔한 말을 안다`() {
        val dir = Files.createTempDirectory("lm").toFile().also { it.deleteOnExit() }
        val lm = LanguageModel.open(dir)
        assertNotNull(lm.lnCount("있는데요"))
        assertNotNull(lm.lnCount("하세요"))
        assertNotNull(lm.lnCount(LanguageModel.BOS))
        assertNotNull(lm.lnBigramCount("할", "수"))
        assertTrue(lm.lnCount("있는데요")!! > lm.lnCount("있는대요") ?: Float.NEGATIVE_INFINITY)
        assertTrue(lm.lnTotal > 17f)
    }

    companion object {
        /**
         * tools/build_lm.py 와 같은 형식으로 굽는다. 테스트가 파일 형식을 두 번 적는 셈이라
         * 한쪽만 바꾸면 여기서 깨진다 — 그게 의도다.
         */
        fun build(uni: Map<String, Long>, bi: Map<String, Long>, total: Long): LanguageModel {
            fun table(entries: Map<String, Long>): Pair<IntArray, ByteArray> {
                val buckets = Array(65536) { ArrayList<Pair<Int, Int>>() }
                for ((key, count) in entries) {
                    val hash = LanguageModel.fnv1a64(key)
                    buckets[(hash ushr 48).toInt()] += hash.toInt() to LanguageModel.quantize(count)
                }
                val offsets = IntArray(65537)
                val body = ByteArrayOutputStream()
                for ((b, bucket) in buckets.withIndex()) {
                    offsets[b + 1] = offsets[b] + bucket.size
                    for ((low, q) in bucket.sortedBy { it.first.toUInt() }) {
                        val cell = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(low).array()
                        body.write(cell)
                        body.write(q)
                    }
                }
                return offsets to body.toByteArray()
            }

            val (uniOffsets, uniBody) = table(uni)
            val (biOffsets, biBody) = table(bi)
            val buffer = ByteBuffer.allocate(20 + 65537 * 8 + uniBody.size + biBody.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("KSLM".toByteArray())
            buffer.putInt(1)
            buffer.putInt(uni.size)
            buffer.putInt(bi.size)
            buffer.putFloat(ln(total.toDouble()).toFloat())
            for (o in uniOffsets) buffer.putInt(o)
            for (o in biOffsets) buffer.putInt(o)
            buffer.put(uniBody)
            buffer.put(biBody)
            return LanguageModel.of(buffer.array())
        }
    }
}
