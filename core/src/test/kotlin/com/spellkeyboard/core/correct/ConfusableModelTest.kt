package com.spellkeyboard.core.correct

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 학습 도구(`tools/confusable/train.py`)가 낸 값과 코틀린이 내는 값이 **같은가.**
 *
 * 특징을 뽑는 코드가 파이썬과 코틀린에 따로 있어서, 한쪽만 고치면 모델이 엉뚱한 값을 내는데도 아무
 * 시험이 안 깨진다. 골든 파일(`confusable-golden.tsv`)이 그걸 막는다 — 학습 도구가 문장 수백 개의 확률을
 * 적어 두고, 여기서 같은 문장을 코틀린으로 풀어 같은 값이 나오는지 본다.
 */
class ConfusableModelTest {

    private val model = assertNotNull(ConfusableModel.loadBundled(), "묶음 안에 confusable.bin.gz 가 없다")

    @Test
    fun `코틀린이 낸 확률이 학습 도구가 낸 값과 같다`() {
        val lines = ConfusableModelTest::class.java.getResourceAsStream("/confusable-golden.tsv")!!
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 300, "골든 파일이 너무 짧다: ${lines.size}")
        var worst = 0.0
        for (line in lines) {
            val p = line.split('\t')
            val words = p[2].split(' ')
            val look = ConfusableModel.Look.values().first { it.key == p[1] }
            val got = model.probability(p[0], look, words = words, i = p[3].toInt(), bos = true)
            assertNotNull(got, line)
            val diff = abs(got - p[4].toDouble())
            if (diff > worst) worst = diff
            assertTrue(diff < 0.0001, "값이 다르다 (코틀린 $got, 학습 도구 ${p[4]}): $line")
        }
        println("골든 ${lines.size}줄, 가장 큰 차이 $worst")
    }

    @Test
    fun `특징을 뽑는 모양`() {
        // 대상 어절의 첫 글자(낳/나/낫)는 특징에 들어 있으면 안 된다. 그게 정답이다.
        val f = ConfusableModel.features(listOf("이게", "낳을까", "저게"), 1, bos = true, left = 2, right = 2)
        assertTrue("E:을까" in f)
        assertTrue("L1:이게" in f && "L1s1:게" in f && "L2:<s>" in f)
        assertTrue("R1:저게" in f && "R2:</s>" in f)
        assertTrue("c1:게|을까" in f)
        assertTrue(f.none { it.contains('낳') }, "정답 글자가 특징에 들어 있다: $f")
        // 부호는 버리고 한글만 본다
        val g = ConfusableModel.features(listOf("(이게)", "나을까?"), 1, bos = false, left = 2, right = 0)
        assertTrue("L1:이게" in g)
        assertEquals(false, "L2:<s>" in g)           // 첫머리인지 모르면 표시하지 않는다
    }
}
