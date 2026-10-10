package com.spellkeyboard.ko.llm

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.spellkeyboard.core.translate.LlmOutput
import com.spellkeyboard.core.translate.LlmPrompt
import java.io.File

/**
 * 기기 안 번역 모델을 돌리는 서비스. **별도 프로세스(`:llm`)** 에서 돈다(AndroidManifest).
 *
 * 왜 따로 도나: 모델은 메모리를 2~3GB 쓰고 네이티브 코드는 가끔 죽는다. 키보드와 같은 프로세스면 모델이 죽거나 메모리 부족으로
 * 정리될 때 **키보드도 같이 죽는다** — 치던 중에 키보드가 사라지는 일은 어떤 번역 품질보다 나쁘다. 따로 돌면 이 프로세스만 죽고,
 * 키보드는 그걸 알아채 ML Kit 번역으로 대신한다.
 *
 * 구조: 메시지는 메인 스레드로 받고(중단은 즉시 처리해야 해서), 모델을 돌리는 일은 일꾼 스레드 하나에서 차례로 한다.
 * 일정 시간 요청이 없으면 모델을 놓고 프로세스를 끝낸다 — 쓰지 않을 때 메모리를 쥐고 있지 않으려는 것이다.
 */
class LlmService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("llm-worker").also { it.start() }
    private val workerHandler = Handler(worker.looper)

    @Volatile private var handle = 0L
    @Volatile private var loadFailed = false
    @Volatile private var cancelBefore = 0
    @Volatile private var busy = false

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) = onMessage(msg)
    })

    override fun onBind(intent: Intent?): IBinder? {
        armIdle()
        return messenger.binder
    }

    override fun onDestroy() {
        // 키보드가 연결을 끊었다. 프로세스째 끝낸다 — 네이티브가 쥔 메모리는 프로세스가 사라져야 확실히 돌아온다.
        shutdownProcess()
        super.onDestroy()
    }

    private fun onMessage(msg: Message) {
        armIdle()
        when (msg.what) {
            LlmProtocol.MSG_TRANSLATE -> {
                val id = msg.arg1
                val data = msg.data ?: return
                val lang = data.getString(LlmProtocol.KEY_LANG).orEmpty()
                val text = data.getString(LlmProtocol.KEY_TEXT).orEmpty()
                val replyTo = msg.replyTo
                workerHandler.post { translate(id, lang, text, replyTo) }
            }

            LlmProtocol.MSG_CANCEL_BEFORE -> {
                cancelBefore = maxOf(cancelBefore, msg.arg1)
                // 돌고 있는 것을 멈춘다. 엔진은 다음 요청이 시작될 때 중단 표시를 스스로 지우므로 새 요청은 영향받지 않는다.
                val h = handle
                if (h != 0L && busy) LlmNative.nativeAbort(h)
            }

            LlmProtocol.MSG_WARM -> {
                val lang = msg.data?.getString(LlmProtocol.KEY_LANG).orEmpty()
                val replyTo = msg.replyTo
                workerHandler.post { warm(lang, replyTo) }
            }

            LlmProtocol.MSG_SHUTDOWN -> shutdownProcess()
        }
    }

    // --- 일꾼 스레드 -------------------------------------------------------------------------

    /** 모델을 올린다(이미 올렸으면 바로). 못 올리면 이유를 돌려준다. */
    private fun ensureLoaded(): String? {
        if (handle != 0L) return null
        if (loadFailed) return "model unavailable"
        val path = LlmModelStore.firstShardPath(this) ?: return "model files missing"
        val loaded = try {
            LlmNative.nativeLoad(path.toByteArray(Charsets.UTF_8), pickThreads(), CONTEXT_TOKENS)
        } catch (t: Throwable) { // 라이브러리가 없거나(UnsatisfiedLinkError) 네이티브 초기화가 실패
            loadFailed = true
            return t.javaClass.simpleName
        }
        if (loaded == 0L) {
            loadFailed = true
            return "model load failed"
        }
        handle = loaded
        return null
    }

    private fun warm(lang: String, replyTo: Messenger?) {
        val system = LlmPrompt.system(lang)
        val error = if (system == null) "no prompt" else ensureLoaded()
        if (error == null && system != null) {
            // 짧은 글 하나를 옮겨 보며 지시문 앞부분의 계산을 끝내 둔다. 결과는 버린다.
            busy = true
            try {
                LlmNative.nativeGenerate(handle, system.toByteArray(Charsets.UTF_8), WARM_TEXT.toByteArray(Charsets.UTF_8), 24)
            } finally {
                busy = false
            }
        }
        replyTo?.let { reply(it, Message.obtain(null, LlmProtocol.R_READY, if (error == null) 1 else 0, 0).apply { data = Bundle().apply { putString(LlmProtocol.KEY_ERROR, error) } }) }
    }

    private fun translate(id: Int, lang: String, text: String, replyTo: Messenger?) {
        if (id < cancelBefore) return // 그사이 낡아졌다 — 답하지 않는다
        val system = LlmPrompt.system(lang)
        val error = if (system == null) "no prompt" else ensureLoaded()
        if (error != null || system == null) {
            replyResult(replyTo, id, LlmProtocol.STATUS_FAILED, null)
            return
        }
        if (id < cancelBefore) return

        busy = true
        val raw: ByteArray? = try {
            LlmNative.nativeGenerate(
                handle,
                system.toByteArray(Charsets.UTF_8),
                text.toByteArray(Charsets.UTF_8),
                (64 + text.length * 3).coerceAtMost(MAX_NEW_TOKENS)
            )
        } finally {
            busy = false
        }
        if (id < cancelBefore) return // 중단됐거나 그사이 낡아졌다 — 답하지 않는다

        if (raw == null) {
            replyResult(replyTo, id, LlmProtocol.STATUS_FAILED, null)
            return
        }
        val cleaned = LlmOutput.clean(raw.toString(Charsets.UTF_8), text, lang)
        if (cleaned == null) replyResult(replyTo, id, LlmProtocol.STATUS_BAD_OUTPUT, null)
        else replyResult(replyTo, id, 0, cleaned)
    }

    private fun replyResult(replyTo: Messenger?, id: Int, status: Int, text: String?) {
        val message = Message.obtain(null, LlmProtocol.R_RESULT, id, status)
        message.data = Bundle().apply { if (text != null) putString(LlmProtocol.KEY_TEXT, text) }
        replyTo?.let { reply(it, message) }
    }

    private fun reply(to: Messenger, message: Message) {
        try {
            to.send(message)
        } catch (_: Exception) {
            // 키보드가 사라졌다. 답할 곳이 없다.
        }
    }

    // --- 쉬는 동안 ---------------------------------------------------------------------------

    private val idleStop = Runnable {
        if (busy) armIdle() else shutdownProcess()
    }

    /** 마지막 요청 뒤 [IDLE_MS] 가 지나면 모델을 놓고 끝낸다. */
    private fun armIdle() {
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, IDLE_MS)
    }

    /**
     * 모델을 놓고 이 프로세스를 끝낸다. 네이티브 쪽을 따로 해제하지 않는다 — 돌고 있는 계산 밑에서 해제하면 그것이 더 위험하고,
     * 프로세스가 사라지면 운영체제가 전부 거둔다. 키보드는 연결이 끊긴 것을 알고(onServiceDisconnected) 다음에 다시 연결한다.
     */
    private fun shutdownProcess() {
        val h = handle
        if (h != 0L) runCatching { LlmNative.nativeAbort(h) }
        Process.killProcess(Process.myPid())
    }

    /** 큰 코어 수만큼 쓴다. 작은 코어까지 쓰면 오히려 느려진다(큰 코어가 작은 코어를 기다린다). 2~4개. */
    private fun pickThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).mapNotNull { index ->
            runCatching { File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        val fastest = freqs.maxOrNull() ?: return (cores / 2).coerceIn(2, 4)
        return freqs.count { it >= fastest * 7 / 10 }.coerceIn(2, 4)
    }

    private companion object {
        const val CONTEXT_TOKENS = 2048
        const val MAX_NEW_TOKENS = 300
        const val IDLE_MS = 90_000L
        const val WARM_TEXT = "안녕"
    }
}
