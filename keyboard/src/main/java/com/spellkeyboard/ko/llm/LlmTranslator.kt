package com.spellkeyboard.ko.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import com.spellkeyboard.core.translate.TranslationPipeline
import com.spellkeyboard.ko.Prefs
import com.spellkeyboard.ko.TargetLanguage

/**
 * 키보드 쪽에서 `:llm` 프로세스([LlmService])의 번역 모델을 부리는 창구. 모든 콜백은 **메인 스레드**로 온다.
 *
 * - [engine] 은 [TranslationPipeline] 에 끼우는 번역기다. 모델이 못 옮기면(실패·쓸 수 없는 글·시간 초과·프로세스가 죽음) `onFailed` 를 부르고,
 *   부르는 쪽이 ML Kit 으로 대신한다([com.spellkeyboard.core.translate.FallbackEngine]).
 * - 요청은 **한 번에 하나씩** 서비스로 보낸다(나머지는 여기서 줄 선다). 모델은 어차피 하나씩 돌고, 그래야 '시간 초과' 가
 *   줄 서서 기다린 시간이 아니라 실제로 계산한 시간만 잰다.
 * - 글이 바뀌어 낡아진 요청은 [cancelAll] 로 거둔다. **거둔 요청은 콜백을 부르지 않는다** — 실패로 알리면 파이프라인이 그 문장을
 *   '못 옮겼다' 고 기억해 버린다.
 * - 연결은 처음 쓸 때 맺는다. 서비스는 쉬면 스스로 프로세스째 끝나고(그러면 [lost]) 키보드가 꺼질 때 [release] 로 끊는다.
 */
class LlmTranslator(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private var binding = false
    private var nextId = 1
    private var served = false // 이 연결에서 한 번이라도 답을 받았나(첫 요청은 모델을 올리느라 오래 걸린다)

    /** 요청을 처리하던 중 모델 프로세스가 죽은 횟수(성공하면 0 으로). 세 번 연달아 죽으면 이번 키보드 실행 동안은 모델을 쓰지 않는다. */
    private var deaths = 0

    private class Job(
        val id: Int,
        val language: String,
        val text: String,
        val onResult: (String) -> Unit,
        val onFailed: (Exception) -> Unit
    ) {
        /** 서비스로 보낸 때(uptime). */
        var sentAt = 0L

        /** 걸린 시간을 잴 요청인가 — 모델이 이미 올라와 있을 때 보낸 것만 잰다. 처음 것은 모델을 올리고 지시문을 계산하는 시간까지 든다. */
        var timed = false
    }

    private val waiting = ArrayDeque<Job>()
    private var inFlight: Job? = null
    private var timeout: Runnable? = null
    private val afterConnect = ArrayList<() -> Unit>()

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        onReply(msg)
        true
    })

    /** 이 기기에서 쓸 수 있고 모델이 받아져 있고 사용자가 안 껐고, 재 보니 너무 느리지도 않은가. */
    fun usable(): Boolean =
        deaths < MAX_DEATHS && Prefs.llmEnabled(context) && Prefs.llmSlowMs(context) == 0L &&
            LlmSupport.check(context) == LlmSupport.Reason.OK && LlmModelStore.isReady(context)

    /**
     * 이 기기에서 너무 느려 쓰지 않기로 정했을 때 한 번 불린다(문장 하나에 걸린 ms). 키보드가 사용자에게 알린다.
     *
     * 폰에서 써 보니 문장 하나에 몇십 초가 걸려 실시간 번역의 엔터가 매번 9초씩 기다리고 '전체번역' 은 시간 초과가 났다(2026-10-11 사용자 제보).
     * 그런 폰에서는 큰 모델이 결과는 못 내고 기다리게만 한다. 그래서 실제로 재서 [SLOW_STREAK] 번 연달아 느리면 끈다([Prefs.llmSlowMs]).
     * 설정에서 '사용' 을 껐다 켜면 다시 재 본다.
     */
    var onTooSlow: ((Long) -> Unit)? = null

    /** 연달아 느렸던 횟수. */
    private var slowStreak = 0

    /** 문장 하나를 옮기는 번역기. [TranslationPipeline] 에 끼운다. */
    fun engine(target: TargetLanguage): TranslationPipeline.Engine =
        TranslationPipeline.Engine { text, onResult, onFailed ->
            waiting += Job(nextId++, target.tag, text, onResult, onFailed)
            pump()
        }

    /** 모델을 올리고 이 언어의 지시문을 미리 계산해 둔다. 번역 모드를 열 때·언어를 바꿀 때 부른다. */
    fun warm(target: TargetLanguage) {
        whenConnected {
            val message = Message.obtain(null, LlmProtocol.MSG_WARM)
            message.replyTo = replies
            message.data = Bundle().apply { putString(LlmProtocol.KEY_LANG, target.tag) }
            send(message)
        }
    }

    /** 낡아진 요청을 모두 거둔다(줄 선 것도, 돌고 있는 것도). 콜백은 부르지 않는다. */
    fun cancelAll() {
        val running = inFlight
        waiting.clear()
        inFlight = null
        clearTimeout()
        if (running != null) send(Message.obtain(null, LlmProtocol.MSG_CANCEL_BEFORE, nextId, 0))
    }

    /** 연결을 끊는다. 서비스는 프로세스째 끝나 모델이 차지하던 메모리가 돌아온다. */
    fun release() {
        cancelAll()
        afterConnect.clear()
        if (binding || service != null) runCatching { context.unbindService(connection) }
        service = null
        binding = false
        served = false
    }

    // --- 줄 세우기 ------------------------------------------------------------------------------

    private fun pump() {
        if (inFlight != null) return
        val job = waiting.removeFirstOrNull() ?: return
        inFlight = job
        armTimeout(job)
        whenConnected {
            // 연결을 기다리는 사이 거둬졌거나 다른 요청으로 넘어갔으면 보내지 않는다.
            if (inFlight === job) {
                job.sentAt = SystemClock.uptimeMillis()
                job.timed = served
                val message = Message.obtain(null, LlmProtocol.MSG_TRANSLATE, job.id, 0)
                message.replyTo = replies
                message.data = Bundle().apply {
                    putString(LlmProtocol.KEY_LANG, job.language)
                    putString(LlmProtocol.KEY_TEXT, job.text)
                }
                send(message)
            }
        }
    }

    private fun armTimeout(job: Job) {
        clearTimeout()
        val run = Runnable {
            if (inFlight === job) {
                inFlight = null
                timeout = null
                if (job.timed) measure(SystemClock.uptimeMillis() - job.sentAt, job.text.length)
                send(Message.obtain(null, LlmProtocol.MSG_CANCEL_BEFORE, job.id + 1, 0))
                job.onFailed(IllegalStateException("번역 모델이 제때 답하지 않았다"))
                pump()
            }
        }
        timeout = run
        main.postDelayed(run, if (served) REPLY_MS else FIRST_REPLY_MS)
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun onReply(msg: Message) {
        when (msg.what) {
            LlmProtocol.R_RESULT -> {
                served = true
                val job = inFlight
                if (job == null || job.id != msg.arg1) return // 이미 거뒀거나 시간이 지난 요청
                inFlight = null
                clearTimeout()
                if (job.timed) measure(SystemClock.uptimeMillis() - job.sentAt, job.text.length)
                val text = msg.data?.getString(LlmProtocol.KEY_TEXT)
                if (msg.arg2 == 0) deaths = 0
                if (msg.arg2 == 0 && text != null) job.onResult(text)
                else job.onFailed(IllegalStateException("번역 모델이 쓸 수 있는 답을 못 냈다(${msg.arg2})"))
                pump()
            }

            LlmProtocol.R_READY -> served = true
        }
    }

    /** 문장 하나에 걸린 시간을 본다. [SLOW_STREAK] 번 연달아 [slowLimit] 를 넘으면 이 기기에서는 쓰지 않는다([onTooSlow]). */
    private fun measure(elapsed: Long, chars: Int) {
        slowStreak = if (elapsed > slowLimit(chars)) slowStreak + 1 else 0
        if (slowStreak < SLOW_STREAK || Prefs.llmSlowMs(context) > 0L) return
        Prefs.setLlmSlowMs(context, elapsed)
        onTooSlow?.invoke(elapsed)
    }

    /** 이 길이의 문장에 기다려 줄 만한 시간. x86 4코어에서 20~30자 문장이 1.3초(중앙값)였다 — 폰이 그 4~5배를 넘으면 쓸모가 없다. */
    private fun slowLimit(chars: Int): Long = SLOW_BASE_MS + chars * SLOW_PER_CHAR_MS

    // --- 연결 ---------------------------------------------------------------------------------

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            binding = false
            val todo = ArrayList(afterConnect)
            afterConnect.clear()
            todo.forEach { it() }
        }

        override fun onServiceDisconnected(name: ComponentName) = lost()

        override fun onBindingDied(name: ComponentName) = lost()

        override fun onNullBinding(name: ComponentName) = lost()
    }

    /** 모델 프로세스가 죽었다(메모리 부족·네이티브 오류·쉬는 동안 스스로 끝냄). 기다리던 요청은 실패로 알려 ML Kit 이 대신하게 한다. */
    private fun lost() {
        service = null
        binding = false
        served = false
        afterConnect.clear()
        clearTimeout()
        val failed = ArrayList<Job>()
        inFlight?.let { failed += it }
        // 일하던 중에 죽었으면 오류로 센다. 쉬다가 스스로 끝낸 것은 오류가 아니다.
        if (inFlight != null) deaths++
        failed += waiting
        inFlight = null
        waiting.clear()
        // 연결 기록을 지워야 다음 요청이 새로 맺는다.
        runCatching { context.unbindService(connection) }
        failed.forEach { it.onFailed(IllegalStateException("번역 모델 프로세스가 끝났다")) }
    }

    private fun whenConnected(action: () -> Unit) {
        if (service != null) {
            action()
            return
        }
        afterConnect += action
        if (binding) return
        binding = true
        val ok = runCatching {
            context.bindService(Intent(context, LlmService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            binding = false
            lost()
        }
    }

    private fun send(message: Message) {
        try {
            service?.send(message)
        } catch (_: Exception) {
            // 연결이 막 끊겼다. onServiceDisconnected 가 정리한다.
        }
    }

    private companion object {
        /** 모델을 올리고 지시문을 처음 계산하는 요청은 폰에서 10초를 넘길 수 있다. */
        const val MAX_DEATHS = 3
        const val FIRST_REPLY_MS = 60_000L
        const val REPLY_MS = 25_000L

        /** 느림 판정: 문장 하나에 기본 4초 + 글자당 0.1초(20자면 6초)를 두 번 연달아 넘으면. */
        const val SLOW_BASE_MS = 4_000L
        const val SLOW_PER_CHAR_MS = 100L
        const val SLOW_STREAK = 2
    }
}
