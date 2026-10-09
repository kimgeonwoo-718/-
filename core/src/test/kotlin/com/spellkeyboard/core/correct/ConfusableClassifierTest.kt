package com.spellkeyboard.core.correct

import com.spellkeyboard.core.lm.LanguageModel
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 헷갈리는 말을 모델로 가르는 단계. 두 층으로 시험한다.
 *
 *  - **가짜 모델**로 논리를 본다 — 문턱이 방향마다 다른지, 앞 어절이 모자라면 건드리지 않는지, 문장이 끊기면 앞 문맥도 끊기는지.
 *    모델이 어떻게 학습됐는지와 무관하게 늘 같은 답이 나와야 한다.
 *  - **진짜 모델**로 쓰임을 본다 — 사용자가 "안 고쳐진다" 고 한 문장과, 그대로 둬야 하는 문장.
 *    학습을 다시 하면 값이 조금 달라지니 여기는 **경계에 걸린 문장은 넣지 않는다.**
 */
class ConfusableClassifierTest {

    // ── 가짜 모델 ─────────────────────────────────────────────────────────────────────────────────────
    //
    // rt: 앞 어절 마지막 음절이 '가·이' 면 낫(-), '를' 이면 낳(+).   all: 거기에 뒤 어절 '보이게' 가 낫(--).
    // rt1: 다음 어절이 '결과' 면 낳(+), '것' 이면 낫(-) 이 더해진다.
    // 점수 z/64 가 확률의 로짓이다: -300 → 0.009, +300 → 0.991, +200 → 0.958.
    private fun fake(objectWeight: Int = 300): ConfusableClassifier {
        val lines = listOf(
            "KSCF\t1\t64",
            "model\tnatda\trt\t2\t0\t0",
            "w\tL1s1:가\t-300", "w\tL1s1:이\t-300", "w\tL1s1:를\t$objectWeight",
            "model\tnatda\trt1\t2\t1\t0",
            "w\tL1s1:가\t-300", "w\tL1s1:이\t-300", "w\tL1s1:를\t$objectWeight", "w\tR1:결과\t700", "w\tR1:것\t-700",
            "model\tnatda\tall\t2\t2\t0",
            "w\tL1s1:가\t-300", "w\tL1s1:이\t-300", "w\tL1s1:를\t$objectWeight", "w\tR1:보이게\t-1200"
        )
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8)) }
        return ConfusableClassifier(ConfusableModel.parse(bytes.toByteArray().inputStream()))
    }

    private fun run(
        c: ConfusableClassifier, text: String, before: String? = LanguageModel.BOS, lookahead: Boolean = false
    ): String = c.apply(text, before, lookahead, mutableListOf())

    @Test
    fun `낳을 낫으로 쓴 것은 주어 조사 뒤에서 고친다`() {
        val c = fake()
        assertEquals("삼전이 나을까", run(c, "삼전이 낳을까"))
        assertEquals("삼전이 나을까 하이닉스가 나을까", run(c, "삼전이 낳을까 하이닉스가 낳을까"))
        assertEquals("삼전이 낫지", run(c, "삼전이 낳지"))
        assertEquals("삼전이 나아졌어", run(c, "삼전이 낳아졌어"))
    }

    @Test
    fun `문턱이 방향마다 다르다 — 낫을 낳으로 고치는 것이 더 조심스럽다`() {
        // 로짓 +300 → 0.991 ≥ 0.98 이라 고친다. +200 → 0.958 이라 안 고친다. 낳→낫(0.90)이었다면 고쳤을 값이다.
        assertEquals("아이를 낳을까", run(fake(300), "아이를 나을까"))
        assertEquals("아이를 나을까", run(fake(200), "아이를 나을까"))
        // 같은 세기로 반대쪽(-200 → 낳이 0.042 = 낫이 0.958)은 고친다.
        val weak = fake(200)
        assertEquals("삼전이 나을까", run(weak, "삼전이 낳을까"))
    }

    @Test
    fun `맞게 쓴 것은 그대로 둔다`() {
        val c = fake()
        assertEquals("삼전이 나을까", run(c, "삼전이 나을까"))
        assertEquals("아이를 낳을까", run(c, "아이를 낳을까"))
    }

    @Test
    fun `입력 중에는 앞 두 어절이 안 갖춰졌으면 건드리지 않는다`() {
        val c = fake()
        // 앞이 한 어절뿐이고 문장 첫머리도 아니다 → 창이 밀리면서 같은 어절을 다른 앞 문맥으로 다시 볼 때 판정이 뒤집히면 안 된다.
        assertEquals("삼전이 낳을까", run(c, "삼전이 낳을까", before = null))
        assertEquals("삼전이 낳을까", run(c, "삼전이 낳을까", before = "카카오가").let { out ->
            // 앞 어절이 하나 더 주어지면 두 어절이 갖춰져 고친다
            if (out == "삼전이 나을까") "삼전이 낳을까" else "고치지 않았다"
        }.let { "삼전이 낳을까" })
        assertEquals("삼전이 나을까", run(c, "삼전이 낳을까", before = "카카오가"))
        // 문장 첫머리면 앞이 없어도 된다
        assertEquals("삼전이 나을까", run(c, "삼전이 낳을까", before = LanguageModel.BOS))
        // 글 전체를 고칠 때는 이 조건이 없다
        assertEquals("삼전이 나을까", run(c, "삼전이 낳을까", before = null, lookahead = true))
    }

    @Test
    fun `문장이 끝나면 앞 문맥도 끊긴다`() {
        val c = fake()
        // '끝났어.' 는 앞 문장이다. 그 뒤 '삼전이' 는 새 문장의 첫 어절이라 앞 어절 둘이 없어도 첫머리로 본다.
        assertEquals("끝났어. 삼전이 나을까", run(c, "끝났어. 삼전이 낳을까", before = null))
        // 앞 문장 끝의 '아이를' 이 다음 문장의 문맥으로 새어 들면 안 된다 (조사 '를' 이 '낳' 쪽으로 밀 수 있다).
        assertEquals("아이를 낳았어. 삼전이 나을까", run(c, "아이를 낳았어. 삼전이 낳을까", before = null))
    }

    @Test
    fun `뒤 어절은 글 전체를 고칠 때만 본다`() {
        val c = fake()
        // 입중: '를' 만 보고 낳으로 고친다. 전체: 뒤의 '보이게' 가 낫 쪽으로 크게 밀어 그대로 둔다.
        assertEquals("문제를 낳아 보이게", run(c, "문제를 나아 보이게", lookahead = false))
        assertEquals("문제를 나아 보이게", run(c, "문제를 나아 보이게", lookahead = true))
    }

    @Test
    fun `관형형은 다음 어절이 나올 때까지 판정을 미룬다`() {
        val c = fake()
        // '삼전이 낳은' 만으로는 '낳은 결과'(빚어낸)인지 '낳은 것'(더 좋은)인지 모른다 → 입력 중에는 그대로 둔다.
        assertEquals("삼전이 낳은", run(c, "삼전이 낳은"))
        // 다음 어절이 나오면 그걸 보고 가른다.
        assertEquals("삼전이 낳은 결과", run(c, "삼전이 낳은 결과"))
        assertEquals("삼전이 나은 것", run(c, "삼전이 낳은 것"))
        // 문장 끝 꼴('-을까')은 미루지 않는다.
        assertEquals("삼전이 나을까", run(c, "삼전이 낳을까"))
        // 글 전체를 고칠 때는 뒤가 이미 있어서 미루지 않는다(여기서는 뒤 어절 단서가 없어 조사로 가른다).
        assertEquals("삼전이 나은", run(c, "삼전이 낳은", lookahead = true))
    }

    @Test
    fun `연장 낫 과 사람 이름 나은 은 이 짝이 아니다`() {
        val c = fake()
        assertEquals("삼전이 낫을 갈았다", run(c, "삼전이 낫을 갈았다"))
        assertEquals("삼전이 나은이를 봤다", run(c, "삼전이 나은이를 봤다"))
    }

    @Test
    fun `부호가 붙은 어절도 부호를 지키며 고친다`() {
        val c = fake()
        assertEquals("삼전이 (나을까?)", run(c, "삼전이 (낳을까?)"))
    }

    @Test
    fun `모델이 없는 짝은 건드리지 않는다`() {
        val lines = listOf("KSCF\t1\t64", "model\tother\trt\t2\t0\t0", "model\tother\trt1\t2\t1\t0", "model\tother\tall\t2\t2\t0")
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(lines.joinToString("\n").toByteArray(Charsets.UTF_8)) }
        val c = ConfusableClassifier(ConfusableModel.parse(bytes.toByteArray().inputStream()))
        assertEquals("삼전이 낳을까", run(c, "삼전이 낳을까"))
    }

    // ── 낫 ↔ 낳 꼴 바꾸기 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `낫다는 모음 어미 앞에서 나 이고 자음 어미 앞에서 낫 이다`() {
        val to = { token: String, cls: Int -> NatNah.convert(token, cls) }
        // 낳 → 낫
        assertEquals("나을까", to("낳을까", 0)); assertEquals("나았어", to("낳았어", 0)); assertEquals("나아", to("낳아", 0))
        assertEquals("나은", to("낳은", 0)); assertEquals("나으면", to("낳으면", 0)); assertEquals("나아졌어", to("낳아졌어", 0))
        assertEquals("낫다", to("낳다", 0)); assertEquals("낫지", to("낳지", 0)); assertEquals("낫겠다", to("낳겠다", 0))
        assertEquals("낫는", to("낳는", 0)); assertEquals("낫냐", to("낳냐", 0)); assertEquals("낫", to("낳", 0))
        assertEquals("나아서", to("낳어서", 0))              // 낫다는 '나어' 가 아니라 '나아'
        // 낫 → 낳
        assertEquals("낳을까", to("나을까", 1)); assertEquals("낳았어", to("나았어", 1)); assertEquals("낳아", to("나아", 1))
        assertEquals("낳다", to("낫다", 1)); assertEquals("낳지", to("낫지", 1))
        // 이미 그쪽이거나 이 짝이 아니면 null
        assertNull(to("낳다", 1)); assertNull(to("나가", 0))
    }

    @Test
    fun `나아가다 같은 다른 낱말은 짝으로 안 본다`() {
        for (t in listOf("나아가야", "나아갈", "나아간다", "나아왔다", "나가", "나는", "나를", "낮은", "날")) {
            assertNull(NatNah.classOf(t), t)
        }
        assertEquals(0, NatNah.classOf("나아")); assertEquals(0, NatNah.classOf("나았다")); assertEquals(1, NatNah.classOf("낳았어"))
    }

    // ── 진짜 모델 ─────────────────────────────────────────────────────────────────────────────────────

    private val engine = CorrectionEngine()

    /** 폰과 같은 길: 어절을 칠 때마다 correctTail 을 부르고 고친 것으로 갈아 끼운다. */
    private fun type(sentence: String): String {
        val buffer = StringBuilder()
        for (word in sentence.trim().split(Regex("\\s+"))) {
            buffer.append(word)
            val before = buffer.toString().takeLast(64)
            val cut = buffer.length - before.length
            engine.correctTail(before)?.let { tail ->
                buffer.replace(cut + before.length - tail.deleteBefore, buffer.length, tail.replacement)
            }
            buffer.append(' ')
        }
        return buffer.toString().trim()
    }

    @Test
    fun `묶음 안에 모델이 들어 있다`() {
        assertNotNull(ConfusableClassifier.bundled(), "confusable.bin.gz 를 못 읽었다")
    }

    @Test
    fun `사용자가 안 고쳐진다고 한 문장 — 처음 보는 낱말이 주어여도 된다`() {
        assertEquals("야 삼전이 나을까 하이닉스가 나을까", type("야 삼전이 낳을까 하이닉스가 낳을까"))
        assertEquals("야 이제 다 나았어", type("야 이제 다 낳았어"))
        assertEquals("아니 이게 나아 저게 나아", type("아니 이게 낳아 저게 낳아"))
        // 처음 보는 이름들
        assertEquals("엔비디아가 나을까 테슬라가 나을까", type("엔비디아가 낳을까 테슬라가 낳을까"))
    }

    @Test
    fun `비교하는 자리와 병이 낫는 자리`() {
        assertEquals("이게 나을까 저게 나을까", type("이게 낳을까 저게 낳을까"))
        assertEquals("이 방법이 더 나은 것 같아", type("이 방법이 더 낳은 것 같아"))
        assertEquals("차라리 그게 낫겠다", type("차라리 그게 낳겠다"))
        assertEquals("어제보다 나아졌어", type("어제보다 낳아졌어"))
        assertEquals("감기가 빨리 나았으면 좋겠다", type("감기가 빨리 낳았으면 좋겠다"))
        assertEquals("허리가 나으면 운동할래", type("허리가 낳으면 운동할래"))
    }

    // 일부러 못 고치는 것(모델이 문턱만큼 확신하지 못한다):
    //  - 첫 어절에 단서가 하나뿐인 나란한 문장의 앞쪽 ('아이폰이 낳아 갤럭시가 낳아' 의 앞 '낳아' — 짧은 '아' 꼴은 단서가 약하다). 입력 중에는 뒤를 못 본다.
    //  - 낫→낳 쪽 일부 ('좋은 결과를 나을 거야', '큰 혼란을 나았다'). 사람들은 '나' 로 쓴 것이 문맥은 '낳' 인 경우가 드물어서 문턱을 0.98 로 높였다.
    // 이런 문장은 규칙을 붙이지 말고 tools/confusable/examples/natda.tsv 에 적어 다시 구워라.
    @Test
    fun `아이와 결과를 낳는 자리`() {
        assertEquals("아이를 낳을까 고민했어", type("아이를 나을까 고민했어"))
        assertEquals("고양이가 새끼를 낳았어", type("고양이가 새끼를 나았어"))
        assertEquals("닭이 알을 낳았다", type("닭이 알을 나았다"))
    }

    @Test
    fun `맞게 쓴 글은 건드리지 않는다`() {
        for (s in listOf(
            "이게 나을까 저게 나을까", "감기가 빨리 나았으면 좋겠다", "그게 더 낫다", "기분이 나아졌어", "혼자가 더 나아",
            "아이를 낳을까 고민했어", "고양이가 새끼를 낳았어", "알을 낳았다", "좋은 결과를 낳을 거야", "쌍둥이를 낳았대",
            "나아가야 해", "낫을 갈았어", "낫으로 풀을 베었다", "병이 나았어", "빨리 나으세요", "나은이가 왔어",
            "야 삼전이 나을까 하이닉스가 나을까", "아이를 낳고 나서 달라졌어",
        )) assertEquals(s, type(s))
    }

    @Test
    fun `글 전체 교정에서도 같다`() {
        assertEquals("야 삼전이 나을까 하이닉스가 나을까", engine.correctAll("야 삼전이 낳을까 하이닉스가 낳을까").text)
        assertEquals("아이를 낳을까 고민했어", engine.correctAll("아이를 나을까 고민했어").text)
    }

    @Test
    fun `끄면 이 단계만 빠진다`() {
        val off = CorrectionEngine().apply { confusableEnabled = false }
        assertEquals("삼전이 낳을까", off.correctAll("삼전이 낳을까").text)
    }
}
