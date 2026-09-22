package com.spellkeyboard.desktop.floating

/**
 * 창에게 시키는 일. Swing 쪽은 [SwingFloatingHost] 하나뿐이고, 시험은 가짜를 끼운다.
 *
 * 모두 **EDT 에서만** 불린다. 그래서 여기에 잠금이 하나도 없다.
 */
interface FloatingHost {
    fun place(bounds: Rect)

    /** `isVisible = true` + 맨 앞으로. **절대 `dispose()` 하지 않는다.** */
    fun showWindow()

    /** `isVisible = false`. 창을 부수지 않는다 — 사전이 올라온 채로 남아 있어야 한다. */
    fun hideWindow()

    /** 글칸에 커서를 넣는다. 불러 놓고 어디에 쳐야 할지 찾게 만들면 안 된다. */
    fun focusEditor()

    fun editorText(): String

    /**
     * 글칸을 비운다. Enter 로 넘긴 **뒤에** 부른다.
     *
     * 안 비우면 다음에 창을 불렀을 때 넘긴 글이 그대로 남아 있고, 거기에 이어 쳐서
     * 다시 Enter 를 누르면 **옛 글까지 통째로 한 번 더 들어간다.** 실제로 그랬다 —
     * 두 번 시험하니 받는 창에 `GUMAE KAMSA` 가 두 벌 쌓였다.
     */
    fun clearEditor()

    fun windowSize(): Rect
    fun windowBounds(): Rect?
    fun screens(): List<ScreenBox>
    fun pointer(): Pt?
}

/** 왜 숨었는지. 붙여넣기 쪽이 "Enter 로 숨은 것" 만 골라 처리해야 해서 남긴다. */
enum class HideReason { ESC, APPLY, FOCUS_LOST, TOGGLE, CLOSE_BUTTON, EXTERNAL }

/**
 * 떠 있는 창의 노릇. **위젯이 하나도 안 들어 있다** — 그래서 화면 없이 통째로 시험된다.
 *
 * ## 규칙
 * - 만들기는 프로그램 뜰 때 한 번. 부르고 치우는 것은 `isVisible` 뿐이다. 사전을 다시
 *   올리면 몇 초가 걸려 "즉시 뜬다" 가 거짓말이 된다.
 * - 모든 메서드는 **EDT 에서** 부른다. 단축키 실에서 오는 것은 [SwingFloatingHost] 가
 *   `invokeLater` 로 넘겨 준다.
 * - [onApply] 는 EDT 가 아닌 곳에서 불린다([SwingFloatingHost] 가 일꾼 실로 던진다).
 *   붙여넣기는 앞 창이 돌아오기를 기다리느라 몇 ms 를 잡아먹는데 그것을 EDT 에서 하면
 *   화면이 언다.
 */
class FloatingController(
    private val host: FloatingHost,
    private val settings: FloatingSettings,
    private val onApply: (String) -> Unit,
    private val onCorrectAll: () -> Unit,
    private val onHidden: (HideReason) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    var isShowing: Boolean = false
        private set

    /** 창이 키보드 초점을 들고 있는가. 단축키를 한 번 더 눌렀을 때 뜻이 달라진다. */
    var isFocused: Boolean = false
        private set

    /** 마지막으로 띄운 때. 초점 잃기 유예를 재는 데 쓴다. */
    private var shownAt: Long = Long.MIN_VALUE

    /** 우리가 `place()` 로 창을 옮기는 중인가. 그때 오는 이동 알림은 사용자 것이 아니다. */
    private var placing: Boolean = false

    // ---- 부르기 / 치우기 ----

    /**
     * 창을 띄운다. 이미 떠 있으면 앞으로 끌어내고 초점만 다시 준다.
     *
     * 쟁반 아이콘 누르기가 이 길로 온다. **쟁반을 눌렀는데 창이 사라지면 안 된다** —
     * 아이콘은 "띄워라" 이지 "뒤집어라" 가 아니다.
     */
    fun summon() {
        if (!isShowing) {
            moveTo(placement())
            isShowing = true
            shownAt = now()
        }
        host.showWindow()
        host.focusEditor()
    }

    /**
     * 전역 단축키가 오는 길.
     *
     * 떠 있고 **초점까지 들고 있으면** 숨긴다 — 같은 키로 부른 것을 같은 키로 치우는 것이
     * 자연스럽다. 떠 있지만 초점이 없으면(초점 잃어도 안 숨김으로 둔 사람의 경우)
     * 숨기지 않고 **끌어낸다.** 그 사람은 창을 보고 있었고 쓰려고 부른 것이다.
     */
    fun toggle() {
        if (isShowing && isFocused) hide(HideReason.TOGGLE) else summon()
    }

    /** 다른 쪽(쟁반 메뉴 "숨기기" 등)에서 치울 때. */
    fun hideExternally() = hide(HideReason.EXTERNAL)

    private fun hide(reason: HideReason) {
        if (!isShowing) return
        rememberWhereItIs()
        isShowing = false
        isFocused = false
        host.hideWindow()
        onHidden(reason)
    }

    // ---- 창에서 올라오는 것 ----

    fun onFocusGained() {
        isFocused = true
    }

    /**
     * 초점을 잃었다.
     *
     * **띄운 직후 [FOCUS_GRACE_MS] 동안은 흘린다.** 윈도우에서 창을 띄우면 활성화가
     * 확정되기 전에 잃음 알림이 한 번 스쳐 지나갈 수 있고(쟁반 아이콘을 눌러 부른
     * 경우엔 쟁반 쪽 창이 먼저 활성화된다), 그것을 그대로 믿으면 부르자마자 숨는
     * 창이 된다. 사용자에게는 "단축키가 안 먹는다" 로 보인다 — 가장 나쁜 고장이다.
     */
    fun onFocusLost() {
        isFocused = false
        if (!isShowing) return
        if (!settings.hideOnFocusLoss) return
        if (now() - shownAt < FOCUS_GRACE_MS) return
        hide(HideReason.FOCUS_LOST)
    }

    /**
     * 창 닫기 단추(X).
     *
     * **숨기기만 한다.** 여기서 프로그램을 끝내면 전역 단축키도 같이 죽어서, 사용자는
     * 창을 닫은 순간 다시 부를 방법을 잃는다. 끝내기는 쟁반 메뉴의 "종료" 하나뿐이다.
     */
    fun onCloseButton() = hide(HideReason.CLOSE_BUTTON)

    /** 사용자가 창을 끌어 옮겼다. 우리가 옮긴 것은 [placing] 으로 걸러진다. */
    fun onWindowMoved() {
        if (placing || !isShowing) return
        rememberWhereItIs()
    }

    // ---- 자판 ----

    /**
     * 자판 한 번. [FloatKeymap] 이 고른 뜻을 실행한다.
     *
     * @return 우리가 먹었으면 true. false 면 Swing 이 원래 하던 일(줄바꿈 따위)을 한다.
     */
    fun onKey(keyCode: Int, modifiersEx: Int, composing: Boolean): Boolean =
        onIntent(FloatKeymap.intentFor(keyCode, modifiersEx, composing))

    fun onIntent(intent: FloatIntent): Boolean = when (intent) {
        FloatIntent.APPLY_AND_HIDE -> {
            // 글을 **먼저** 집는다. 숨긴 뒤에 읽어도 되지만, 순서를 못 박아 두면
            // 나중에 누가 숨기기와 함께 글칸을 비우는 짓을 해도 안전하다.
            val text = host.editorText()
            hide(HideReason.APPLY)
            // 넘긴 글은 글칸에서 지운다. 안 지우면 다음에 불렀을 때 그대로 남아 두 번
            // 들어간다([FloatingHost.clearEditor] 참고). 못 넣었을 때 글을 잃지 않는 것은
            // 받는 쪽 몫이다 — 실패하면 창을 다시 띄우면서 글을 도로 넣어 준다.
            host.clearEditor()
            // 숨긴 **뒤에** 넘긴다. 받는 쪽은 앞 창이 돌아오기를 기다렸다가 Ctrl+V 를
            // 보내야 하는데, 우리가 아직 떠 있으면 그 기다림이 통째로 헛돈다.
            onApply(text)
            true
        }
        FloatIntent.CANCEL -> {
            hide(HideReason.ESC)
            true
        }
        FloatIntent.CORRECT_ALL -> {
            onCorrectAll()
            true
        }
        FloatIntent.NEWLINE, FloatIntent.PASS -> false
    }

    // ---- 설정 ----

    fun setHideOnFocusLoss(on: Boolean) {
        settings.hideOnFocusLoss = on
    }

    /**
     * 자리 잡는 방식을 바꾼다. 떠 있는 중이면 **바로 옮겨 보여 준다** —
     * 설정에서 고르고 나서 다음에 부를 때까지 뭐가 달라졌는지 모르면 고를 수가 없다.
     */
    fun setPolicy(policy: PlacementPolicy) {
        settings.policy = policy
        if (isShowing) moveTo(placement())
    }

    // ---- 속 ----

    private fun placement(): Rect = placeFloating(
        policy = settings.policy,
        size = host.windowSize(),
        screens = host.screens(),
        pointer = host.pointer(),
        remembered = settings.remembered,
    )

    /**
     * 지금 자리를 적어 둔다. 숨을 때와 옮겼을 때 둘 다에서 부른다 —
     * 이동 알림은 창 관리자마다 오고 안 오고가 다르지만 숨기기는 언제나 우리가 한다.
     */
    private fun rememberWhereItIs() {
        val b = host.windowBounds() ?: return
        if (b.width <= 0 || b.height <= 0) return
        settings.remembered = b
    }

    /**
     * 창을 옮기는 **유일한 통로.** [placing] 깃발을 세워 두어 이 이동이
     * [onWindowMoved] 로 돌아왔을 때 사용자가 끈 것으로 오해하지 않게 한다.
     * (Swing 은 `setBounds` 에도 `componentMoved` 를 쏜다.)
     */
    private fun moveTo(bounds: Rect) {
        placing = true
        try {
            host.place(bounds)
        } finally {
            placing = false
        }
    }

    companion object {
        /**
         * 띄운 뒤 이만큼은 초점 잃음을 안 믿는다. 이 기계에서 창이 초점을 실제로
         * 얻기까지 17~20ms 가 걸렸다(붙여넣기 탐침 PHASE 2). 250ms 는 그 열 배가 넘고,
         * 사용자가 부르자마자 다른 창을 누르기에는 짧은 시간이다.
         */
        const val FOCUS_GRACE_MS = 250L
    }
}
