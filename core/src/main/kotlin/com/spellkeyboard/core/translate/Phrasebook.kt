package com.spellkeyboard.core.translate

/**
 * 자주 쓰는 채팅 문장을 손으로 옮겨 둔 표.
 *
 * ## 왜 표가 필요한가
 *
 * 온디바이스 번역기는 **짧고 주어가 빠진 한국어**에 특히 약하다. 사람이 쓴 정답이 있으면
 * 모델을 부를 이유가 없다 — 실기기에서 나온 '저는 좋아요' → "I like it" 이 그런 경우다.
 * 문맥상 "I'm good" 인데 모델은 알 길이 없다. 이런 문장 몇백 개가 채팅의 큰 몫을 차지한다.
 *
 * 실제 번역 제품도 고빈도 문장은 이렇게 표로 박아 둔다. 표에 없으면 모델로 넘어간다 —
 * 손해가 없는 구조다.
 *
 * ## 열쇠
 *
 * **공백을 모두 뺀 문장**이 열쇠다. '뭐 해' 와 '뭐해' 를 한 줄로 덮는다. 문장 부호와
 * 'ㅋㅋ' 는 [ChatText] 가 이미 떼어 낸 뒤라 여기 오지 않는다.
 */
object Phrasebook {

    const val ENGLISH = "en"
    const val JAPANESE = "ja"
    const val CHINESE = "zh"

    /** 표에 있으면 손으로 옮긴 문장, 없으면 null(모델이 옮긴다). */
    fun lookup(core: String, code: String): String? {
        val key = core.replace(" ", "")
        return table(code)[key]
    }

    /**
     * 번역문에 문장 부호와 감탄을 도로 붙인다.
     *
     * 표의 문장은 이미 부호를 달고 있으므로("What are you doing?") 겹쳐 붙이지 않는다.
     */
    fun decorate(translation: String, normalized: ChatText.Normalized, code: String): String {
        val trimmed = translation.trim()
        if (trimmed.isEmpty()) return trimmed
        val punctuated =
            if (trimmed.last() in ALREADY_PUNCTUATED) trimmed else trimmed + localizeEnding(normalized.ending, code)
        val emphasis = normalized.emphasis?.let { emphasis(it, code) } ?: return punctuated
        return "$punctuated $emphasis"
    }

    /** 일본어·중국어는 문장 부호가 따로 있다. 한국어 쪽에서 고른 부호를 그 언어 것으로 바꾼다. */
    private fun localizeEnding(ending: String, code: String): String {
        if (code != JAPANESE && code != CHINESE) return ending
        return when (ending) {
            "." -> "。"
            "?" -> "？"
            "!" -> "！"
            else -> ending
        }
    }

    /** 'ㅋㅋ' 를 그 언어에서 같은 자리에 쓰는 말로. */
    fun emphasis(kind: ChatText.Emphasis, code: String): String = when (code) {
        JAPANESE -> if (kind == ChatText.Emphasis.LAUGH) "(笑)" else "(泣)"
        CHINESE -> if (kind == ChatText.Emphasis.LAUGH) "哈哈" else "呜呜"
        else -> if (kind == ChatText.Emphasis.LAUGH) "lol" else ":("
    }

    /** 표에 든 문장 수. 테스트와 진단용. */
    fun size(code: String): Int = table(code).size

    private fun table(code: String): Map<String, String> = when (code) {
        JAPANESE -> JA
        CHINESE -> ZH
        else -> EN
    }

    private val ALREADY_PUNCTUATED = setOf('.', '!', '?', '…', '。', '！', '？')

    private val EN = mapOf(
        // 인사
        "안녕" to "Hi", "안녕하세요" to "Hello", "안녕하십니까" to "Hello",
        "반가워" to "Nice to meet you", "반가워요" to "Nice to meet you",
        "반갑습니다" to "Nice to meet you",
        "오랜만이야" to "Long time no see", "오랜만이에요" to "Long time no see",
        "잘지내" to "How are you?", "잘지내요" to "How are you?",
        "잘지냈어" to "How have you been?", "잘지냈어요" to "How have you been?",
        "좋은아침" to "Good morning", "좋은아침이에요" to "Good morning",
        "잘자" to "Good night", "잘자요" to "Good night",
        "안녕히주무세요" to "Good night",
        "안녕히계세요" to "Goodbye", "안녕히가세요" to "Goodbye",
        "잘가" to "Bye", "또봐" to "See you", "또봐요" to "See you",
        "나중에봐" to "See you later", "이따봐" to "See you later",
        "내일봐" to "See you tomorrow", "내일봐요" to "See you tomorrow",

        // 감사·사과
        "고마워" to "Thanks", "고마워요" to "Thank you", "고맙습니다" to "Thank you",
        "감사합니다" to "Thank you", "감사해요" to "Thank you",
        "정말고마워" to "Thank you so much", "정말감사합니다" to "Thank you so much",
        "미안" to "Sorry", "미안해" to "Sorry", "미안해요" to "I'm sorry",
        "죄송합니다" to "I'm sorry", "죄송해요" to "I'm sorry",
        "괜찮아" to "It's okay", "괜찮아요" to "It's okay",
        "천만에요" to "You're welcome",

        // 대답
        "응" to "Yeah", "네" to "Yes", "넵" to "Got it", "예" to "Yes",
        "아니" to "No", "아니야" to "No", "아니요" to "No",
        "맞아" to "That's right", "맞아요" to "That's right",
        "그래" to "Okay", "그래요" to "Okay",
        "알겠어" to "Got it", "알겠어요" to "Got it", "알겠습니다" to "Understood",
        "알았어" to "Got it", "오케이" to "Okay",
        "몰라" to "I don't know", "몰라요" to "I don't know",
        "모르겠어" to "I don't know", "모르겠어요" to "I don't know",
        "당연하지" to "Of course", "물론이죠" to "Of course",
        "ㅇㅇ" to "Yeah", "ㄴㄴ" to "Nope", "ㄱㅅ" to "Thanks",
        "ㅊㅋ" to "Congrats", "ㅇㅋ" to "Okay",

        // 질문
        "뭐해" to "What are you doing?", "뭐해요" to "What are you doing?",
        "뭐하고있어" to "What are you doing?", "지금뭐해" to "What are you doing right now?",
        "어디야" to "Where are you?", "어디예요" to "Where are you?",
        "어디있어" to "Where are you?", "어딨어" to "Where are you?",
        "언제와" to "When are you coming?", "언제만나" to "When should we meet?",
        "누구세요" to "Who is this?",
        "왜" to "Why?", "왜요" to "Why?", "왜그래" to "What's wrong?",
        "무슨일이야" to "What's going on?", "무슨일이에요" to "What's going on?",
        "얼마예요" to "How much is it?",
        "몇시야" to "What time is it?", "몇시예요" to "What time is it?",
        "밥먹었어" to "Did you eat?", "밥먹었어요" to "Did you eat?",
        "잘잤어" to "Did you sleep well?",
        "어때" to "How is it?", "어때요" to "How is it?",
        "어떻게생각해" to "What do you think?",
        "진짜" to "Really?", "진짜요" to "Really?", "정말" to "Really?",
        "오늘기분이어떠세요" to "How are you feeling today?",
        "기분이어떠세요" to "How are you feeling?",
        "어떻게지내" to "How are you doing?", "어떻게지내세요" to "How are you doing?",

        // 감정·상태
        "좋아" to "Sounds good", "좋아요" to "Sounds good",
        "저는좋아요" to "I'm good", "나는좋아" to "I'm good", "난좋아" to "I'm good",
        "싫어" to "I don't want to", "싫어요" to "I don't want to",
        "배고파" to "I'm hungry", "배고파요" to "I'm hungry", "배불러" to "I'm full",
        "피곤해" to "I'm tired", "피곤해요" to "I'm tired",
        "졸려" to "I'm sleepy", "심심해" to "I'm bored",
        "바빠" to "I'm busy", "바빠요" to "I'm busy",
        "슬퍼" to "I'm sad", "화나" to "I'm mad", "무서워" to "I'm scared",
        "재밌어" to "It's fun", "재밌어요" to "It's fun", "재밌다" to "That's fun",
        "힘들어" to "It's tough", "힘들어요" to "It's tough",
        "신난다" to "I'm excited",
        "대박" to "Wow", "헐" to "Whoa",
        "웃겨" to "That's funny", "웃기다" to "That's funny",
        "짱이야" to "That's awesome", "최고야" to "You're the best",

        // 일상
        "사랑해" to "I love you", "사랑해요" to "I love you",
        "보고싶어" to "I miss you", "보고싶어요" to "I miss you",
        "축하해" to "Congratulations", "축하해요" to "Congratulations",
        "축하합니다" to "Congratulations",
        "화이팅" to "You got this", "파이팅" to "You got this",
        "수고했어" to "Good work", "수고하셨습니다" to "Thank you for your work",
        "조심해" to "Be careful", "조심히가" to "Get home safe",
        "잠깐만" to "Hold on", "잠시만요" to "One moment, please",
        "기다려" to "Wait", "기다려줘" to "Please wait", "빨리와" to "Come quickly",
        "갈게" to "I'll go", "갈게요" to "I'll go",
        "지금가" to "I'm on my way", "가는중" to "I'm on my way",
        "가고있어" to "I'm on my way",
        "다왔어" to "I'm almost there", "도착했어" to "I'm here",
        "늦었어" to "I'm late", "미안늦었어" to "Sorry I'm late",

        // 식사
        "밥먹자" to "Let's eat", "뭐먹을까" to "What should we eat?",
        "맛있어" to "It's delicious", "맛있어요" to "It's delicious",
        "맛있다" to "It's delicious", "맛없어" to "It doesn't taste good",
        "시켜먹자" to "Let's order in", "커피마실래" to "Want to get coffee?",
        "치킨먹자" to "Let's get chicken",

        // 소개
        "저는학생입니다" to "I'm a student"
    )

    private val JA = mapOf(
        "안녕" to "こんにちは", "안녕하세요" to "こんにちは",
        "반가워요" to "はじめまして", "반갑습니다" to "はじめまして",
        "오랜만이야" to "久しぶり", "오랜만이에요" to "お久しぶりです",
        "잘자" to "おやすみ", "잘자요" to "おやすみなさい",
        "안녕히계세요" to "さようなら", "잘가" to "じゃあね",
        "또봐" to "またね", "내일봐" to "また明日",

        "고마워" to "ありがとう", "고마워요" to "ありがとうございます",
        "감사합니다" to "ありがとうございます", "고맙습니다" to "ありがとうございます",
        "미안해" to "ごめん", "미안" to "ごめん", "미안해요" to "ごめんなさい",
        "죄송합니다" to "申し訳ありません",
        "괜찮아" to "大丈夫", "괜찮아요" to "大丈夫です",

        "네" to "はい", "응" to "うん", "아니" to "いいえ", "아니요" to "いいえ",
        "맞아" to "そうだよ", "맞아요" to "そうです", "그래" to "うん",
        "알겠어" to "わかった", "알겠어요" to "わかりました",
        "알겠습니다" to "かしこまりました",
        "몰라" to "わからない", "몰라요" to "わかりません",

        "뭐해" to "何してる？", "뭐해요" to "何してますか？",
        "어디야" to "どこ？", "어디예요" to "どこですか？",
        "언제와" to "いつ来る？",
        "왜" to "なんで？", "왜요" to "どうしてですか？",
        "밥먹었어" to "ご飯食べた？", "밥먹었어요" to "ご飯食べましたか？",
        "어때" to "どう？", "어때요" to "どうですか？",
        "진짜" to "本当？",
        "오늘기분이어떠세요" to "今日の気分はどうですか？",
        "어떻게지내" to "元気？", "어떻게지내세요" to "お元気ですか？",

        "좋아" to "いいよ", "좋아요" to "いいですね", "저는좋아요" to "私は元気です",
        "싫어" to "いやだ",
        "배고파" to "お腹すいた", "배불러" to "お腹いっぱい",
        "피곤해" to "疲れた", "졸려" to "眠い", "바빠" to "忙しい",
        "재밌어" to "楽しい", "대박" to "すごい",

        "사랑해" to "愛してる", "보고싶어" to "会いたい",
        "축하해" to "おめでとう", "축하합니다" to "おめでとうございます",
        "화이팅" to "頑張って", "파이팅" to "頑張って",
        "수고했어" to "お疲れさま", "조심해" to "気をつけて",
        "잠깐만" to "ちょっと待って", "잠시만요" to "少々お待ちください",
        "기다려" to "待って",
        "갈게" to "行くね", "가는중" to "向かってる", "가고있어" to "向かってる",
        "도착했어" to "着いたよ", "늦었어" to "遅れた",

        "맛있어" to "おいしい", "맛있어요" to "おいしいです", "밥먹자" to "ご飯食べよう"
    )

    private val ZH = mapOf(
        "안녕" to "你好", "안녕하세요" to "你好",
        "반가워요" to "很高兴认识你", "반갑습니다" to "很高兴认识你",
        "오랜만이야" to "好久不见", "오랜만이에요" to "好久不见",
        "잘자" to "晚安", "잘자요" to "晚安",
        "안녕히계세요" to "再见", "잘가" to "再见",
        "또봐" to "回头见", "내일봐" to "明天见",

        "고마워" to "谢谢", "고마워요" to "谢谢", "감사합니다" to "谢谢您",
        "고맙습니다" to "谢谢您",
        "미안해" to "对不起", "미안" to "对不起", "미안해요" to "对不起",
        "죄송합니다" to "非常抱歉",
        "괜찮아" to "没关系", "괜찮아요" to "没关系",

        "네" to "是", "응" to "嗯", "아니" to "不", "아니요" to "不是",
        "맞아" to "对", "맞아요" to "对", "그래" to "好",
        "알겠어" to "知道了", "알겠어요" to "知道了", "알겠습니다" to "明白了",
        "몰라" to "不知道", "몰라요" to "我不知道",

        "뭐해" to "你在做什么？", "뭐해요" to "你在做什么？",
        "어디야" to "你在哪里？", "어디예요" to "你在哪里？",
        "언제와" to "你什么时候来？",
        "왜" to "为什么？", "왜요" to "为什么？",
        "밥먹었어" to "你吃饭了吗？", "밥먹었어요" to "你吃饭了吗？",
        "어때" to "怎么样？", "어때요" to "怎么样？",
        "진짜" to "真的吗？",
        "오늘기분이어떠세요" to "你今天感觉怎么样？",
        "어떻게지내" to "你好吗？", "어떻게지내세요" to "您好吗？",

        "좋아" to "好啊", "좋아요" to "好啊", "저는좋아요" to "我很好",
        "싫어" to "我不要",
        "배고파" to "我饿了", "배불러" to "我饱了",
        "피곤해" to "我累了", "졸려" to "我困了", "바빠" to "我很忙",
        "재밌어" to "很有趣", "대박" to "太厉害了",

        "사랑해" to "我爱你", "보고싶어" to "我想你",
        "축하해" to "恭喜", "축하합니다" to "恭喜您",
        "화이팅" to "加油", "파이팅" to "加油",
        "수고했어" to "辛苦了", "조심해" to "小心",
        "잠깐만" to "等一下", "잠시만요" to "请稍等",
        "기다려" to "等等",
        "갈게" to "我要走了", "가는중" to "我在路上", "가고있어" to "我在路上",
        "도착했어" to "我到了", "늦었어" to "我迟到了",

        "맛있어" to "很好吃", "맛있어요" to "很好吃", "밥먹자" to "我们去吃饭吧"
    )
}
