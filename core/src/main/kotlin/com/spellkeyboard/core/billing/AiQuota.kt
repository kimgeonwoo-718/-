package com.spellkeyboard.core.billing

import java.util.TimeZone

/**
 * 요금제.
 *
 * 결제는 아직 붙이지 않았다. 지금은 설정에서 손으로 바꾸고, 나중에 결제가 붙으면
 * 그쪽이 이 값을 세운다. 요금제를 판정하는 곳을 여기 하나로 모아 두면 그때 바꿀 데가
 * 한 군데뿐이다.
 */
enum class Tier {
    /** 무료. AI 전체 교정은 하루 [AiQuota.FREE_DAILY_LIMIT] 회. */
    FREE,

    /** 구독. AI 전체 교정 무제한. */
    SUBSCRIBER
}

/**
 * AI 전체 교정을 하루 몇 번 썼는지 센다.
 *
 * **온디바이스 교정은 여기 걸리지 않는다.** 그건 네트워크도 돈도 안 드니 무제한이다.
 * 세는 건 돈이 나가는 것 — 사용자가 버튼을 눌러 문장 전체를 API 로 보내는 경우뿐이다.
 *
 * 저장은 [Store] 에 맡긴다. 이 클래스는 안드로이드를 모르고, 그래서 자정을 넘기는
 * 상황까지 JVM 테스트로 확인할 수 있다.
 *
 * ## 기기 시계를 돌리면 초기화된다
 *
 * 날짜를 기기에서 읽으니 시계를 앞뒤로 돌리면 하루치가 되살아난다. 막으려면 서버가
 * 시각과 사용량을 쥐고 있어야 하는데, 그건 결제를 붙일 때 같이 할 일이다. 지금은
 * 알고 두는 구멍이다 — 무료 5 회를 우회하려고 매번 시계를 돌릴 사람이면 어차피
 * 자기 API 키를 넣고 만다.
 */
class AiQuota(
    private val store: Store,
    private val clock: Clock = SystemClock
) {

    /** 사용량을 어디에 적어 둘지. 안드로이드에서는 SharedPreferences 가 된다. */
    interface Store {
        fun tier(): Tier

        /** 마지막으로 적은 날 ([today] 와 같은 기준의 일련번호). */
        fun day(): Long

        /** 그날 쓴 횟수. */
        fun used(): Int

        fun save(day: Long, used: Int)
    }

    /** 시계. 테스트에서 자정을 넘겨 보려면 갈아 끼울 수 있어야 한다. */
    interface Clock {
        fun nowMillis(): Long

        /** [at] 시점의 현지 시간대 오프셋(ms). 하루 경계를 현지 자정으로 잡는 데 쓴다. */
        fun offsetMillis(at: Long): Int
    }

    object SystemClock : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()

        override fun offsetMillis(at: Long): Int = TimeZone.getDefault().getOffset(at)
    }

    /**
     * 지금 남은 사용량.
     *
     * @property limit 하루 한도. 무제한이면 null.
     */
    data class Status(val tier: Tier, val used: Int, val limit: Int?) {

        /** 남은 횟수. 무제한이면 null. */
        val remaining: Int? get() = limit?.let { (it - used).coerceAtLeast(0) }

        val unlimited: Boolean get() = limit == null

        val exhausted: Boolean get() = remaining == 0
    }

    fun status(): Status {
        val tier = store.tier()
        // 구독자는 세지 않는다. 세어 봐야 쓸 데가 없고, 남은 횟수를 보여줄 이유도 없다.
        if (tier == Tier.SUBSCRIBER) return Status(tier, used = 0, limit = null)
        return Status(tier, used = usedToday(), limit = FREE_DAILY_LIMIT)
    }

    fun canUse(): Boolean = !status().exhausted

    /**
     * 한 번 썼다고 적는다.
     *
     * **성공했을 때만 불러야 한다.** 서버가 거절했는데 횟수를 깎으면 사용자는 아무것도
     * 못 받고 손해만 본다. 모델 이름이 틀렸거나 네트워크가 막힌 건 사용자 잘못이 아니다.
     *
     * @return 적었으면 true. 이미 다 썼으면 false 이고 아무것도 바뀌지 않는다.
     */
    fun consume(): Boolean {
        if (store.tier() == Tier.SUBSCRIBER) return true

        val today = today()
        val used = usedToday(today)
        if (used >= FREE_DAILY_LIMIT) return false
        store.save(today, used + 1)
        return true
    }

    private fun usedToday(today: Long = today()): Int =
        if (store.day() == today) store.used() else 0

    /**
     * 현지 기준 며칠째인가.
     *
     * 자정을 넘기면 값이 바뀐다. UTC 로 자르면 한국에서는 오전 9 시에 초기화돼서
     * "하루 5 회" 가 말이 안 된다. 그래서 현지 오프셋을 더해서 자른다.
     */
    private fun today(): Long {
        val now = clock.nowMillis()
        return Math.floorDiv(now + clock.offsetMillis(now), DAY_MILLIS)
    }

    companion object {
        /** 무료 사용자의 하루 AI 전체 교정 횟수. */
        const val FREE_DAILY_LIMIT = 5

        private const val DAY_MILLIS = 86_400_000L
    }
}
