package com.spellkeyboard.core.billing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiQuotaTest {

    /** 메모리에만 적는 가짜 저장소. */
    private class FakeStore(var tier: Tier = Tier.FREE) : AiQuota.Store {
        var day = 0L
        var used = 0
        var writes = 0

        override fun tier(): Tier = tier
        override fun day(): Long = day
        override fun used(): Int = used

        override fun save(day: Long, used: Int) {
            this.day = day
            this.used = used
            writes++
        }
    }

    /** 한국 시간(UTC+9) 고정. 원하는 시각으로 돌릴 수 있다. */
    private class FakeClock(var now: Long) : AiQuota.Clock {
        override fun nowMillis(): Long = now
        override fun offsetMillis(at: Long): Int = KST_OFFSET
    }

    private fun quota(store: FakeStore, clock: FakeClock) = AiQuota(store, clock)

    // --- 무료 한도 -------------------------------------------------------------

    @Test
    fun `무료는 하루 다섯 번까지 쓴다`() {
        val store = FakeStore()
        val quota = quota(store, FakeClock(NOON))

        repeat(AiQuota.FREE_DAILY_LIMIT) {
            assertTrue(quota.canUse(), "${it + 1}번째는 쓸 수 있어야 한다")
            assertTrue(quota.consume(), "${it + 1}번째는 적혀야 한다")
        }

        assertFalse(quota.canUse(), "여섯 번째는 막혀야 한다")
        assertFalse(quota.consume())
        assertEquals(AiQuota.FREE_DAILY_LIMIT, store.used, "막힌 뒤에 더 세면 안 된다")
    }

    @Test
    fun `남은 횟수를 알려준다`() {
        val store = FakeStore()
        val quota = quota(store, FakeClock(NOON))

        assertEquals(AiQuota.FREE_DAILY_LIMIT, quota.status().remaining)
        quota.consume()
        assertEquals(AiQuota.FREE_DAILY_LIMIT - 1, quota.status().remaining)

        repeat(AiQuota.FREE_DAILY_LIMIT) { quota.consume() }
        assertEquals(0, quota.status().remaining)
        assertTrue(quota.status().exhausted)
    }

    // --- 날짜가 바뀔 때 ---------------------------------------------------------

    @Test
    fun `자정을 넘기면 되살아난다`() {
        val store = FakeStore()
        val clock = FakeClock(NOON)
        val quota = quota(store, clock)

        repeat(AiQuota.FREE_DAILY_LIMIT) { quota.consume() }
        assertFalse(quota.canUse())

        // 같은 날 밤 11시 59분 — 아직 안 된다.
        clock.now = NOON + 11 * HOUR + 59 * MINUTE
        assertFalse(quota.canUse(), "자정 전에는 그대로여야 한다")

        // 자정 1분 뒤 — 되살아난다.
        clock.now = NOON + 12 * HOUR + MINUTE
        assertTrue(quota.canUse(), "자정을 넘겼으면 되살아나야 한다")
        assertEquals(AiQuota.FREE_DAILY_LIMIT, quota.status().remaining)
    }

    @Test
    fun `자정 경계는 한국 시간 기준이다`() {
        val store = FakeStore()
        // 한국 시간 2024-01-02 00:30 == UTC 2024-01-01 15:30.
        // UTC 로 잘랐다면 아직 1일이라 초기화가 안 된다.
        val beforeKstMidnight = kst(2024, dayOfYear = 1, hour = 23, minute = 30)
        val afterKstMidnight = kst(2024, dayOfYear = 2, hour = 0, minute = 30)

        val clock = FakeClock(beforeKstMidnight)
        val quota = quota(store, clock)
        repeat(AiQuota.FREE_DAILY_LIMIT) { quota.consume() }
        assertFalse(quota.canUse())

        clock.now = afterKstMidnight
        assertTrue(quota.canUse(), "한국 자정을 넘겼으면 초기화돼야 한다")
    }

    @Test
    fun `날이 바뀌어도 저장된 값을 덮어쓰기 전까지는 세지 않는다`() {
        val store = FakeStore()
        val clock = FakeClock(NOON)
        val quota = quota(store, clock)
        repeat(3) { quota.consume() }

        clock.now = NOON + DAY
        // 읽기만 해서는 저장소를 건드리지 않는다.
        val writesBefore = store.writes
        assertEquals(AiQuota.FREE_DAILY_LIMIT, quota.status().remaining)
        assertEquals(writesBefore, store.writes, "읽기만 했는데 저장하면 안 된다")

        // 실제로 쓸 때 새 날짜로 다시 시작한다.
        quota.consume()
        assertEquals(1, store.used)
    }

    // --- 구독자 ---------------------------------------------------------------

    @Test
    fun `구독자는 무제한이다`() {
        val store = FakeStore(tier = Tier.SUBSCRIBER)
        val quota = quota(store, FakeClock(NOON))

        repeat(50) {
            assertTrue(quota.canUse())
            assertTrue(quota.consume())
        }
        assertTrue(quota.status().unlimited)
        assertNull(quota.status().remaining)
        assertEquals(0, store.writes, "구독자는 셀 이유가 없다")
    }

    @Test
    fun `무료로 다 쓴 뒤 구독하면 바로 풀린다`() {
        val store = FakeStore()
        val quota = quota(store, FakeClock(NOON))
        repeat(AiQuota.FREE_DAILY_LIMIT) { quota.consume() }
        assertFalse(quota.canUse())

        store.tier = Tier.SUBSCRIBER
        assertTrue(quota.canUse(), "구독하면 그 자리에서 풀려야 한다")
    }

    @Test
    fun `구독을 끊으면 그날 쓴 만큼은 그대로 남아 있다`() {
        val store = FakeStore()
        val quota = quota(store, FakeClock(NOON))
        repeat(2) { quota.consume() }

        store.tier = Tier.SUBSCRIBER
        repeat(10) { quota.consume() }

        store.tier = Tier.FREE
        // 구독 중에 쓴 건 안 셌으니, 무료로 돌아오면 아까 2회만 남아 있다.
        assertEquals(AiQuota.FREE_DAILY_LIMIT - 2, quota.status().remaining)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val KST_OFFSET = 9 * 60 * 60 * 1000

        /** 한국 시간 정오쯤 되는 아무 시각. */
        val NOON = kst(2024, dayOfYear = 100, hour = 12, minute = 0)

        /** 한국 시간으로 그 날짜·시각이 되는 epoch millis. */
        fun kst(year: Int, dayOfYear: Int, hour: Int, minute: Int): Long {
            val yearsSinceEpoch = (year - 1970).toLong()
            // 정확한 달력이 필요한 테스트가 아니다 — 하루 경계만 보면 된다.
            val days = yearsSinceEpoch * 365 + dayOfYear
            return days * DAY + hour * HOUR + minute * MINUTE - KST_OFFSET
        }
    }
}
