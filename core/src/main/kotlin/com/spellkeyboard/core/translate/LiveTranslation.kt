package com.spellkeyboard.core.translate

/**
 * 번역 입력줄의 **실시간 번역 진행** — 언제 무엇을 번역시키고, 결과를 언제 확정하고, 엔터를 언제 보낼지.
 *
 * 안드로이드에 기대지 않는 순수 코드다. 처음에는 키보드 서비스 안에 풀어 적었는데, 여기는 ① 글이 계속 바뀌고 ② 번역이 늦게 오고
 * ③ 번역기가 둘(ML Kit 초안 + 기기 안 큰 모델 확정본)이고 ④ 엔터가 그 사이에 들어온다. 이런 곳은 눈으로 보고 맞추기 어렵고, 틀리면
 * "번역이 안 나온다"·"엔터가 안 먹는다" 로 보인다. 그래서 따로 떼어 가짜 시계·가짜 번역기로 시험한다([LiveTranslationTest]).
 *
 * ## 흐름
 *
 * 1. 글이 바뀌면 [DEVICE_DEBOUNCE_MS] 뒤에 ML Kit 으로 옮긴다. 큰 모델을 쓸 수 있으면 이것은 **초안**이다(흐리게 보인다).
 * 2. 큰 모델을 쓸 수 있으면 치기를 멈추고 [BIG_DEBOUNCE_MS] 더 뒤에 큰 모델로 옮긴다. 그 결과가 **확정본**이다.
 *    큰 모델이 못 옮긴 문장은 부르는 쪽이 ML Kit 으로 대신해서([FallbackEngine]) 어쨌든 확정본이 온다.
 * 3. 엔터: 확정본이 이미 있으면 바로 보낸다. 없으면 큰 모델을 **지금** 시작하고 확정본이 오면 보낸다. [ENTER_PATIENCE_MS] 가
 *    지나도 안 오면 입력란에 들어가 있는 초안을 보낸다.
 * 4. 결과가 도착했을 때 글이나 언어가 그사이 바뀌었으면 버린다. 확정본이 이미 있으면 늦게 온 초안으로 덮지 않는다.
 *
 * 모든 호출은 한 스레드(메인)에서 한다.
 */
class LiveTranslation(private val env: Env) {

    /** 키보드가 해 주는 일들. */
    interface Env {
        /** 입력줄의 글(앞뒤 공백 없음). */
        fun source(): String

        /** 지금 고른 목표 언어 코드. */
        fun language(): String

        /** 기기 번역기(ML Kit)가 준비됐나. */
        fun deviceReady(): Boolean

        /** 큰 모델을 쓸 수 있나(받아 뒀고 기기가 감당하고 사용자가 안 껐다). */
        fun bigUsable(): Boolean

        /** ML Kit 으로 옮긴다. [onDone] 은 한 번. */
        fun translateDevice(source: String, language: String, onDone: (TranslationPipeline.Result) -> Unit)

        /** 큰 모델로 옮긴다(못 옮긴 문장은 ML Kit 이 대신한다). [onDone] 은 한 번. 취소되면 부르지 않아도 된다. */
        fun translateBig(source: String, language: String, onDone: (TranslationPipeline.Result) -> Unit)

        /** 큰 모델이 하던 낡은 일을 거둔다. */
        fun cancelBig()

        /** 번역문을 입력란과 미리보기에 넣는다. [draft] 면 초안(흐리게). */
        fun show(result: TranslationPipeline.Result, draft: Boolean)

        /** 입력줄이 비었다 — 입력란의 번역문을 지우고 안내를 보인다. */
        fun showEmpty()

        /** 문장을 못 옮긴 것이 있었다는 알림. */
        fun warnFailed()

        /** 입력줄을 닫고 입력란의 번역문을 앱에 보낸다(엔터). */
        fun send()

        /** 입력란에 번역문(초안이라도)이 들어가 있나. 엔터를 기다리다 지쳤을 때 보낼 것이 있는지 본다. */
        fun hasShownText(): Boolean

        /** [ms] 뒤에 [task] 를 한 번 부른다. 돌려준 것으로 취소한다. */
        fun after(ms: Long, task: () -> Unit): Handle
    }

    fun interface Handle {
        fun cancel()
    }

    /** 큰 모델의 확정본이 입력란에 들어가 있는 원문. 지금 글과 같으면 입력란의 번역문이 최신이다. */
    var finalSource: String = ""
        private set

    /** 엔터를 눌렀는데 확정본이 아직 없다. */
    var pendingEnter: Boolean = false
        private set

    /** 큰 모델이 지금 옮기고 있는(또는 시작을 기다리는) 원문. 같은 글로 두 번 시작하지 않으려고 쥔다. */
    private var bigSource = ""

    private var deviceTimer: Handle? = null
    private var bigTimer: Handle? = null
    private var patienceTimer: Handle? = null

    /** 입력줄의 글이 바뀌었다. 잠깐 뒤에 번역한다 — 글자마다 돌리면 낭비다. */
    fun changed() {
        deviceTimer?.cancel()
        deviceTimer = env.after(DEVICE_DEBOUNCE_MS) { translateNow() }
    }

    /** 엔터. 확정본이 있으면 바로 보내고, 없으면 만들어지길 기다렸다 보낸다. */
    fun enter() {
        if (env.source() == finalSource) {
            send()
            return
        }
        pendingEnter = true
        deviceTimer?.cancel()
        translateNow()
        // 큰 모델은 치기를 멈추길 기다리지 않고 지금 시작한다. 너무 오래 걸리면 초안을 보낸다.
        if (pendingEnter && env.bigUsable()) {
            startBig()
            patienceTimer?.cancel()
            patienceTimer = env.after(ENTER_PATIENCE_MS) { patienceOver() }
        }
    }

    /** 번역 모드를 열거나 닫을 때. 걸려 있던 것을 전부 거두고 처음 상태로. */
    fun reset() {
        deviceTimer?.cancel()
        bigTimer?.cancel()
        patienceTimer?.cancel()
        deviceTimer = null
        bigTimer = null
        patienceTimer = null
        finalSource = ""
        bigSource = ""
        pendingEnter = false
    }

    /** 목표 언어가 바뀌었다. 확정본과 진행 중인 큰 모델 일은 틀린 언어다(기억해 둔 번역을 지우는 일은 부르는 쪽 몫). */
    fun languageChanged() {
        bigTimer?.cancel()
        bigTimer = null
        finalSource = ""
        bigSource = ""
        env.cancelBig()
    }

    /** 확정본이 낡았다고 표시한다('전체번역' 으로 입력줄을 비웠을 때 등). */
    fun invalidateFinal() {
        finalSource = ""
    }

    /** 엔터 대기를 취소한다(서버 번역으로 넘어갈 때 등). */
    fun cancelEnter() {
        pendingEnter = false
        patienceTimer?.cancel()
        patienceTimer = null
    }

    // --- 번역 시키기 ---------------------------------------------------------------------------

    private fun translateNow() {
        deviceTimer = null
        val source = env.source()
        if (source.isEmpty()) {
            bigTimer?.cancel()
            bigTimer = null
            bigSource = ""
            env.cancelBig()
            finalSource = ""
            env.showEmpty()
            if (pendingEnter) send()
            return
        }
        val language = env.language()
        val deviceReady = env.deviceReady()
        val useBig = env.bigUsable()
        if (!deviceReady && !useBig) return

        // 글이 바뀌었으면 큰 모델이 옮기던 낡은 일은 거두고, 치기를 멈춘 뒤에 새로 시작한다. 같은 글이면(엔터로 다시 부른 경우) 건드리지 않는다.
        if (useBig && bigSource != source) {
            env.cancelBig()
            bigSource = ""
            bigTimer?.cancel()
            bigTimer = env.after(BIG_DEBOUNCE_MS) { startBig() }
        }

        // ML Kit 은 바로 옮긴다. 큰 모델을 쓸 수 있으면 이것은 '초안' 이다.
        if (deviceReady) {
            env.translateDevice(source, language) { result -> arrived(result, source, language, final = !useBig) }
        }
    }

    private fun startBig() {
        bigTimer?.cancel()
        bigTimer = null
        val source = env.source()
        if (source.isEmpty() || !env.bigUsable() || bigSource == source) return
        val language = env.language()
        bigSource = source
        env.translateBig(source, language) { result -> arrived(result, source, language, final = true) }
    }

    private fun arrived(result: TranslationPipeline.Result, source: String, language: String, final: Boolean) {
        // 그사이 글이나 언어가 바뀌었으면 낡은 결과다.
        if (env.source() != source || env.language() != language) return
        // 확정본이 이미 있는데 늦게 온 초안으로 덮지 않는다.
        if (!final && finalSource == source) return
        env.show(result, draft = !final)
        if (!final) return
        finalSource = source
        if (bigSource == source) bigSource = ""
        if (result.failed > 0) env.warnFailed()
        if (pendingEnter) send()
    }

    private fun patienceOver() {
        patienceTimer = null
        if (!pendingEnter) return
        if (env.hasShownText()) send() else pendingEnter = false
    }

    private fun send() {
        pendingEnter = false
        patienceTimer?.cancel()
        patienceTimer = null
        env.send()
    }

    companion object {
        /** 글이 바뀐 뒤 ML Kit 이 옮기기까지. */
        const val DEVICE_DEBOUNCE_MS = 250L

        /** ML Kit 이 시작된 뒤 큰 모델이 시작하기까지 — 글자마다 돌리면 배터리만 쓰고 계속 중단된다. */
        const val BIG_DEBOUNCE_MS = 700L

        /** 엔터를 누르고 확정본을 기다리는 최대 시간. 지나면 입력란의 초안을 보낸다. */
        const val ENTER_PATIENCE_MS = 9_000L
    }
}
