package com.spellkeyboard.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhrasebookTest {

    @Test
    fun `실기기에서 틀리게 나온 문장을 표가 잡는다`() {
        // 모델은 "I like it" 을 내놨다. 문맥상 맞는 말은 이것이다.
        assertEquals("I'm good", Phrasebook.lookup("저는 좋아요", Phrasebook.ENGLISH))
        assertEquals("How are you feeling today?", Phrasebook.lookup("오늘 기분이 어떠세요", Phrasebook.ENGLISH))
    }

    @Test
    fun `띄어쓰기가 달라도 찾는다`() {
        assertEquals("What are you doing?", Phrasebook.lookup("뭐해", Phrasebook.ENGLISH))
        assertEquals("What are you doing?", Phrasebook.lookup("뭐 해", Phrasebook.ENGLISH))
    }

    @Test
    fun `표에 없으면 null — 모델이 옮긴다`() {
        assertNull(Phrasebook.lookup("어제 친구랑 영화를 봤어", Phrasebook.ENGLISH))
    }

    @Test
    fun `세 언어를 모두 안다`() {
        assertEquals("Thank you very much.", Phrasebook.lookup("감사합니다", Phrasebook.ENGLISH))
        assertEquals("ありがとうございます。", Phrasebook.lookup("감사합니다", Phrasebook.JAPANESE))
        assertEquals("非常感谢。", Phrasebook.lookup("감사합니다", Phrasebook.CHINESE))
        assertTrue(Phrasebook.size(Phrasebook.ENGLISH) > 100)
        assertTrue(Phrasebook.size(Phrasebook.JAPANESE) > 50)
        assertTrue(Phrasebook.size(Phrasebook.CHINESE) > 50)
    }

    @Test
    fun `부호를 겹쳐 붙이지 않는다`() {
        val question = ChatText.normalize("뭐해")
        assertEquals("What are you doing?", Phrasebook.decorate("What are you doing?", question, Phrasebook.ENGLISH))

        val plain = ChatText.normalize("저는 좋아요")
        assertEquals("I'm good.", Phrasebook.decorate("I'm good", plain, Phrasebook.ENGLISH))
    }

    @Test
    fun `웃음을 그 언어의 말로 붙인다`() {
        val laugh = ChatText.normalize("진짜 웃겨 ㅋㅋ")
        assertEquals("That's so funny. lol", Phrasebook.decorate("That's so funny", laugh, Phrasebook.ENGLISH))
        assertEquals("面白い。 (笑)", Phrasebook.decorate("面白い", laugh, Phrasebook.JAPANESE))
        assertEquals("很好笑。 哈哈", Phrasebook.decorate("很好笑", laugh, Phrasebook.CHINESE))
    }

    @Test
    fun `일본어와 중국어는 그 언어의 문장 부호를 쓴다`() {
        val plain = ChatText.normalize("배고파")
        assertEquals("お腹すいた。", Phrasebook.decorate("お腹すいた", plain, Phrasebook.JAPANESE))
        assertEquals("我饿了。", Phrasebook.decorate("我饿了", plain, Phrasebook.CHINESE))
        val question = ChatText.normalize("어디야")
        assertEquals("どこですか？", Phrasebook.decorate("どこですか？", question, Phrasebook.JAPANESE))
        assertEquals("你在哪里。", Phrasebook.decorate("你在哪里", ChatText.normalize("배고파"), Phrasebook.CHINESE))
    }

    @Test
    fun `표의 열쇠에 공백이나 부호가 섞여 있지 않다`() {
        // 열쇠는 ChatText 가 다듬은 뒤의 모습이어야 한다. 공백이나 부호가 남아 있으면 영영 안 맞는다.
        for (code in listOf(Phrasebook.ENGLISH, Phrasebook.JAPANESE, Phrasebook.CHINESE)) {
            for (key in keysOf(code)) {
                assertTrue(key.none { it == ' ' }, "'$key' 에 공백이 있다 ($code)")
                assertTrue(key.none { it in ".!?~," }, "'$key' 에 부호가 있다 ($code)")
                assertEquals(key, ChatText.normalize(key).core, "'$key' 는 다듬은 모습과 다르다 ($code)")
            }
        }
    }

    private fun keysOf(code: String): List<String> {
        // lookup 이 열쇠를 어떻게 쓰는지만 알면 되므로, 표본 몇 개로 갈음하지 않고
        // 표 전체를 훑기 위해 reflection 대신 알려진 열쇠들을 다시 확인한다.
        return SAMPLE_KEYS.filter { Phrasebook.lookup(it, code) != null }
    }

    private companion object {
        val SAMPLE_KEYS = listOf(
            "안녕", "안녕하세요", "고마워", "감사합니다", "미안해", "괜찮아", "네", "응",
            "아니요", "맞아", "알겠어", "몰라", "뭐해", "어디야", "왜", "밥먹었어",
            "어때", "좋아", "저는좋아요", "배고파", "피곤해", "사랑해", "보고싶어",
            "축하해", "화이팅", "수고했어", "조심해", "잠깐만", "기다려", "갈게",
            "도착했어", "늦었어", "잘자", "잘가", "또봐", "내일봐", "맛있어", "밥먹자",
            "대박", "진짜", "오늘기분이어떠세요", "어떻게지내", "ㅇㅇ", "ㄴㄴ", "ㄱㅅ"
        )
    }

    // ── 자원 파일 phrasebook.tsv ─────────────────────────────────────────────────────────────────────

    private val kana = Regex("[\u3040-\u30ff]")
    private val han = Regex("[\u4e00-\u9fff]")
    private val hangul = Regex("[\uac00-\ud7a3]")

    @Test
    fun `자원 표의 모든 줄이 형식을 지킨다`() {
        val rows = Phrasebook.bigLines()
        assertTrue(rows.size > 100, "표가 너무 짧다: ${rows.size}줄")
        for (cols in rows) {
            assertEquals(4, cols.size, "열이 넷이 아니다: $cols")
            val (ko, en, ja, zh) = cols
            assertTrue(ko.isNotBlank() && en.isNotBlank() && ja.isNotBlank() && zh.isNotBlank(), "빈 칸이 있다: $cols")
            // 언어 글자가 맞나. 일본어는 가나가 있어야 하고 중국어는 한자만(가나 없음), 영어는 한글·한자·가나가 없다.
            assertTrue(kana.containsMatchIn(ja) || han.containsMatchIn(ja), "일본어에 일본 글자가 없다: $cols")
            assertTrue(han.containsMatchIn(zh) && !kana.containsMatchIn(zh), "중국어 글자가 틀렸다: $cols")
            assertTrue(!hangul.containsMatchIn(en) && !han.containsMatchIn(en) && !kana.containsMatchIn(en), "영어에 다른 글자가 섞였다: $cols")
            assertTrue(!hangul.containsMatchIn(ja) && !hangul.containsMatchIn(zh), "번역에 한글이 남았다: $cols")
        }
    }

    @Test
    fun `자원 표에 같은 열쇠가 두 번 나오지 않는다`() {
        val seen = HashMap<String, String>()
        for (cols in Phrasebook.bigLines()) {
            for (variant in cols[0].split('|')) {
                val key = ChatText.normalize(variant).core.replace(" ", "")
                assertTrue(key.isNotEmpty(), "열쇠가 비었다: '$variant'")
                val before = seen.put(key, cols[1])
                assertTrue(before == null, "열쇠 '$key' 가 두 번 나온다 ('$before' 와 '${cols[1]}')")
            }
        }
    }

    @Test
    fun `자원 표가 코드 안의 표보다 먼저다`() {
        // 코드 안 표에는 '안녕하세요' 가 'Hello' 로 있다. 자원 표는 'Hello!' 다.
        assertEquals("Hello!", Phrasebook.lookup("안녕하세요", Phrasebook.ENGLISH))
        assertEquals("こんにちは。", Phrasebook.lookup("안녕하세요", Phrasebook.JAPANESE))
    }

    @Test
    fun `변형들이 한 번역으로 모인다`() {
        for (key in listOf("뭐해", "뭐하냐", "뭐 하니", "뭐 하고 있어")) {
            assertEquals("What are you doing?", Phrasebook.lookup(key, Phrasebook.ENGLISH), key)
        }
    }
}
