package com.spellkeyboard.ko

import android.content.Context
import com.spellkeyboard.core.lm.LanguageModel
import com.spellkeyboard.core.spacing.SpacingDictionary
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 교정 사전 파일을 푸는 일을 **한 번에 한 곳씩만** 하게 한다.
 *
 * 처음 깔고 나면 사전(띄어쓰기·언어모델 약 20MB)과 Kiwi 모델(105MB)이 파일로 풀려 있지 않다. 키보드가
 * 뜨면서 풀기 시작하는데, 풀기는 폰에서 수 초~십수 초 걸린다. 그 사이에 키보드가 한 번 더 떠서(입력기를
 * 바꿨다 돌아오는 것 — 처음 설정할 때 흔하다) **같은 폴더에 두 곳이 동시에 풀면 진 쪽이 죽는다.**
 * `SpacingDictionary.open` 은 `.tmp` 로 풀고 옮기는데, 이긴 쪽이 먼저 옮겨 버리면 진 쪽은
 * `NoSuchFileException` 을 던진다. 키보드는 이 실패를 삼키므로(교정은 사전 없이도 계속돼야 한다)
 * **그 키보드는 사전도 언어모델도 없이 떠서, 교정이 거의 안 된다** — 키보드가 다시 뜰 때까지.
 * 같은 폴더에 두 스레드가 동시에 풀게 하면 매번 이렇게 된다(2 스레드 × 30판 전부).
 *
 * 그래서 푸는 곳은 전부 자물쇠를 거친다. 이미 풀려 있으면 자물쇠 안에서도 바로 끝난다.
 *
 * **자물쇠는 둘이다.** 사전 폴더(`spacing/`)와 Kiwi 폴더(`kiwi-model/`)는 서로 다른 곳이라 하나로 묶을
 * 이유가 없고, 묶으면 105MB 를 복사하는 동안 가벼운 사전 열기까지 줄을 선다.
 */
internal object EngineFiles {

    /** 사전·언어모델(`spacing/`)을 푸는 자물쇠. */
    private val dictLock = Any()

    /** Kiwi 모델(`kiwi-model/`)을 푸는 자물쇠. 105MB 를 복사하는 동안 잡고 있다. */
    private val kiwiLock = Any()
    private val warmedUp = AtomicBoolean(false)

    /** 사전 파일을 풀어 둘 폴더. [SpellKeyboardService] 가 쓰는 곳과 같아야 한다. */
    fun dir(context: Context): File = File(context.filesDir, DICTIONARY_DIR)

    /** Kiwi 모델을 푸는 동안 잡는다. [KiwiSpacer] 가 쓴다. */
    fun <T> exclusiveKiwi(block: () -> T): T = synchronized(kiwiLock) { block() }

    fun openSpacing(target: File): SpacingDictionary = synchronized(dictLock) { SpacingDictionary.open(target) }

    fun openLanguageModel(target: File): LanguageModel = synchronized(dictLock) { LanguageModel.open(target) }

    /**
     * [block] 을 하고, 못 하면 잠깐 뒤 다시 해 본다. 끝내 못 하면 null.
     *
     * 예전에는 한 번 못 열면 그 키보드는 **다시 뜰 때까지 사전 없이** 갔다(디스크가 잠깐 막혔을 때도 같다).
     * 사용자 눈에는 "교정이 안 되다가 이것저것 건드리면 된다" 로 보인다. 몇 번 더 해 보는 값이 싸다.
     * [cancelled] 가 참이면(키보드가 이미 닫혔다) 더 하지 않는다.
     */
    fun <T : Any> openWithRetry(cancelled: () -> Boolean, block: () -> T): T? {
        repeat(ATTEMPTS) { attempt ->
            if (cancelled()) return null
            runCatching(block).onSuccess { return it }
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_WAIT_MS)
        }
        return null
    }

    /**
     * 앱을 처음 열 때 파일을 **미리** 푼다.
     *
     * 사용자는 키보드를 켜기 전에 앱 첫 화면에서 켜는 법을 읽는다. 그 30초~1분 동안 풀어 두면, 처음
     * 키보드를 띄울 때는 이미 풀려 있어서 사전과 언어모델이 바로 올라온다. 이게 없으면 처음 몇 초는
     * 사전 없이(규칙 교정만) 돈다. 풀기만 하고 올리지는 않는다 — Kiwi 를 올리는 100MB 는 키보드 몫이다.
     *
     * 실패는 삼킨다. 못 풀었으면 키보드가 뜰 때 다시 시도한다. 프로세스당 한 번만 한다.
     */
    fun warmUp(context: Context) {
        if (!warmedUp.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            val target = dir(app)
            runCatching { openSpacing(target) }
            runCatching { openLanguageModel(target) }
            runCatching { KiwiSpacer.prepare(app) }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** [SpellKeyboardService] 의 `DICTIONARY_DIR` 와 같은 값. 거기는 private 이라 여기 다시 적는다. */
    private const val DICTIONARY_DIR = "spacing"

    private const val ATTEMPTS = 3
    private const val RETRY_WAIT_MS = 1_500L
}
