package com.spellkeyboard.desktop.floating

import java.awt.event.InputEvent.CTRL_DOWN_MASK
import java.awt.event.InputEvent.SHIFT_DOWN_MASK
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_SPACE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 창 노릇 전체를 **화면 없이** 시험한다. 위젯 대신 [FakeHost] 를 끼운다.
 *
 * 이것이 논리와 위젯을 갈라 놓은 값이다 — 여기 있는 것들은 전부 진짜 창에서
 * 손으로 재현하려면 몇 분씩 걸리고, 초점 유예 같은 것은 손으로는 아예 못 잰다.
 */
class FloatingControllerTest {

    private class FakeHost : FloatingHost {
        var visible = false
        var bounds: Rect? = null
        var text = "되요"
        var focusCalls = 0
        var toFrontCalls = 0
        val log = mutableListOf<String>()

        var monitors: List<ScreenBox> = listOf(
            ScreenBox("main", Rect(0, 0, 1920, 1080), Rect(0, 0, 1920, 1032), true),
            ScreenBox("side", Rect(1920, 0, 1536, 864), Rect(1920, 0, 1536, 816), false),
        )
        var mouse: Pt? = Pt(100, 100)

        override fun place(bounds: Rect) {
            this.bounds = bounds
            log += "place"
        }

        override fun showWindow() {
            visible = true
            toFrontCalls++
            log += "show"
        }

        override fun hideWindow() {
            visible = false
            log += "hide"
        }

        override fun focusEditor() {
            focusCalls++
            log += "focus"
        }

        override fun editorText(): String = text
        override fun windowSize(): Rect = Rect(0, 0, 336, 324)
        override fun windowBounds(): Rect? = bounds
        override fun screens(): List<ScreenBox> = monitors
        override fun pointer(): Pt? = mouse
    }

    private class Fixture(
        val settings: MemorySettings = MemorySettings(),
        val host: FakeHost = FakeHost(),
    ) {
        var clock = 1_000L
        val applied = mutableListOf<String>()
        val hides = mutableListOf<HideReason>()
        var corrections = 0

        val controller = FloatingController(
            host = host,
            settings = settings,
            onApply = { applied += it },
            onCorrectAll = { corrections++ },
            onHidden = { hides += it },
            now = { clock },
        )

        /** 초점 유예를 지나간 것으로 만든다. */
        fun settle() {
            clock += FloatingController.FOCUS_GRACE_MS + 1
        }
    }

    // ---- 부르기 ----

    @Test
    fun `부르면 자리를 잡고 띄우고 글칸에 커서를 넣는다`() {
        val f = Fixture()
        f.controller.summon()
        assertTrue(f.host.visible)
        assertEquals(1, f.host.focusCalls)
        assertEquals(listOf("place", "show", "focus"), f.host.log)
        assertTrue(f.controller.isShowing)
    }

    @Test
    fun `이미 떠 있으면 다시 자리 잡지 않고 앞으로만 끌어낸다`() {
        val f = Fixture()
        f.controller.summon()
        val where = f.host.bounds
        f.host.mouse = Pt(2600, 400)   // 그새 마우스가 옆 모니터로 갔다
        f.controller.summon()
        // 사용자가 보고 있는 창이 발밑에서 다른 모니터로 튀면 안 된다.
        assertEquals(where, f.host.bounds)
        assertEquals(2, f.host.toFrontCalls)
        assertEquals(2, f.host.focusCalls)
    }

    @Test
    fun `다시 부를 때 창을 부수지 않는다`() {
        val f = Fixture()
        repeat(5) {
            f.controller.summon()
            f.controller.onIntent(FloatIntent.CANCEL)
        }
        // 부수기(dispose)는 FloatingHost 에 아예 없다. 남는 것은 show/hide 뿐이다.
        assertEquals(5, f.host.log.count { it == "show" })
        assertEquals(5, f.host.log.count { it == "hide" })
    }

    // ---- 단축키 한 번 더 ----

    @Test
    fun `떠 있고 초점도 있으면 단축키가 숨긴다`() {
        val f = Fixture()
        f.controller.summon()
        f.controller.onFocusGained()
        f.controller.toggle()
        assertFalse(f.host.visible)
        assertEquals(listOf(HideReason.TOGGLE), f.hides)
    }

    @Test
    fun `떠 있지만 초점이 없으면 단축키가 끌어낸다`() {
        // 초점 잃어도 안 숨기게 해 둔 사람. 창은 옆에 떠 있고 사용자는 워드에 있다.
        val f = Fixture(MemorySettings(hideOnFocusLoss = false))
        f.controller.summon()
        f.controller.onFocusGained()
        f.controller.onFocusLost()
        f.controller.toggle()
        assertTrue(f.host.visible, "쓰려고 부른 창을 숨기면 안 된다")
        assertEquals(2, f.host.focusCalls)
    }

    // ---- Esc ----

    @Test
    fun `Esc 는 아무것도 내보내지 않고 숨긴다`() {
        val f = Fixture()
        f.controller.summon()
        assertTrue(f.controller.onKey(VK_ESCAPE, 0, composing = false))
        assertFalse(f.host.visible)
        assertTrue(f.applied.isEmpty(), "Esc 는 취소다. 글이 나가면 안 된다")
        assertEquals(listOf(HideReason.ESC), f.hides)
    }

    @Test
    fun `안 떠 있을 때 Esc 는 아무 일도 안 한다`() {
        val f = Fixture()
        f.controller.onKey(VK_ESCAPE, 0, composing = false)
        assertTrue(f.hides.isEmpty())
    }

    // ---- Enter ----

    @Test
    fun `Enter 는 글을 넘기고 숨긴다 그리고 숨기는 것이 먼저다`() {
        val f = Fixture()
        f.host.text = "돼요"
        f.controller.summon()
        assertTrue(f.controller.onKey(VK_ENTER, 0, composing = false))
        assertEquals(listOf("돼요"), f.applied)
        assertFalse(f.host.visible)
        // 받는 쪽은 앞 창이 돌아오기를 기다린다. 우리가 아직 떠 있으면 그 기다림이 헛돈다.
        assertEquals(listOf("place", "show", "focus", "hide"), f.host.log)
        assertEquals(listOf(HideReason.APPLY), f.hides)
    }

    @Test
    fun `Shift Enter 는 우리 일이 아니다`() {
        val f = Fixture()
        f.controller.summon()
        assertFalse(f.controller.onKey(VK_ENTER, SHIFT_DOWN_MASK, composing = false))
        assertTrue(f.host.visible, "줄바꿈으로 창이 사라지면 못 쓴다")
        assertTrue(f.applied.isEmpty())
    }

    @Test
    fun `조합 중 Enter 로는 창이 안 사라진다`() {
        val f = Fixture()
        f.controller.summon()
        assertFalse(f.controller.onKey(VK_ENTER, 0, composing = true))
        assertTrue(f.host.visible)
        assertTrue(f.applied.isEmpty())
    }

    @Test
    fun `Ctrl Space 는 전체교정만 부르고 창은 그대로 둔다`() {
        val f = Fixture()
        f.controller.summon()
        assertTrue(f.controller.onKey(VK_SPACE, CTRL_DOWN_MASK, composing = false))
        assertEquals(1, f.corrections)
        assertTrue(f.host.visible)
    }

    // ---- 초점 잃기 ----

    @Test
    fun `초점을 잃으면 숨는다 설정이 켜져 있을 때만`() {
        val on = Fixture(MemorySettings(hideOnFocusLoss = true))
        on.controller.summon()
        on.settle()
        on.controller.onFocusLost()
        assertFalse(on.host.visible)
        assertEquals(listOf(HideReason.FOCUS_LOST), on.hides)

        val off = Fixture(MemorySettings(hideOnFocusLoss = false))
        off.controller.summon()
        off.settle()
        off.controller.onFocusLost()
        assertTrue(off.host.visible, "끈 사람에게는 옆에 띄워 두는 창이다")
    }

    @Test
    fun `띄운 직후의 초점 잃음은 믿지 않는다`() {
        // 윈도우는 창이 활성화되기 전에 잃음 알림을 한 번 흘릴 수 있다. 그것을 믿으면
        // 부르자마자 숨는 창이 되고, 사용자 눈에는 "단축키가 안 먹는다" 로 보인다.
        val f = Fixture()
        f.controller.summon()
        f.clock += FloatingController.FOCUS_GRACE_MS - 1
        f.controller.onFocusLost()
        assertTrue(f.host.visible, "유예 안에서 숨으면 안 된다")

        f.clock += 2
        f.controller.onFocusLost()
        assertFalse(f.host.visible, "유예가 지나면 숨어야 한다")
    }

    @Test
    fun `설정을 켜고 끄는 것이 다음 번에 그대로 먹는다`() {
        val f = Fixture(MemorySettings(hideOnFocusLoss = false))
        f.controller.summon()
        f.settle()
        f.controller.onFocusLost()
        assertTrue(f.host.visible)

        f.controller.setHideOnFocusLoss(true)
        assertTrue(f.settings.hideOnFocusLoss, "설정에 적혀야 다음에 열어도 산다")
        f.controller.onFocusLost()
        assertFalse(f.host.visible)
    }

    // ---- 자리 기억 ----

    @Test
    fun `사용자가 끌어 옮기면 그 자리를 적어 둔다`() {
        val f = Fixture()
        f.controller.summon()
        f.host.bounds = Rect(700, 800, 336, 324)
        f.controller.onWindowMoved()
        assertEquals(Rect(700, 800, 336, 324), f.settings.remembered)
    }

    @Test
    fun `우리가 옮긴 것은 사용자가 옮긴 것으로 치지 않는다`() {
        // Swing 은 setBounds 에도 componentMoved 를 쏜다. 그것까지 "사용자가 옮겼다" 로
        // 세면 기본 자리가 사용자의 자리인 양 굳어 버린다.
        val f = Fixture()
        f.controller.summon()
        assertNull(f.settings.remembered, "우리가 놓은 자리를 기억하면 안 된다")
    }

    @Test
    fun `숨을 때도 자리를 적어 둔다`() {
        // 창 관리자에 따라 componentMoved 가 안 올 수 있다. 숨기기는 언제나 우리가 한다.
        val f = Fixture()
        f.controller.summon()
        f.host.bounds = Rect(11, 22, 336, 324)
        f.controller.onIntent(FloatIntent.CANCEL)
        assertEquals(Rect(11, 22, 336, 324), f.settings.remembered)
    }

    @Test
    fun `방식을 바꾸면 떠 있는 창이 바로 옮겨 간다`() {
        val f = Fixture()
        f.host.mouse = Pt(2600, 400)      // 옆 모니터
        f.controller.summon()
        assertTrue(f.host.bounds!!.x >= 1920)

        f.controller.setPolicy(PlacementPolicy.CENTER_PRIMARY)
        assertTrue(f.host.bounds!!.x < 1920, "고른 것이 바로 안 보이면 고를 수가 없다")
        assertEquals(PlacementPolicy.CENTER_PRIMARY, f.settings.policy)
    }

    // ---- 창 닫기 단추 ----

    @Test
    fun `X 단추는 숨기기일 뿐이다`() {
        val f = Fixture()
        f.controller.summon()
        f.controller.onCloseButton()
        assertFalse(f.host.visible)
        assertEquals(listOf(HideReason.CLOSE_BUTTON), f.hides)
        // 프로그램이 끝나면 전역 단축키도 같이 죽는다. 다시 부를 수 있어야 한다.
        f.controller.summon()
        assertTrue(f.host.visible)
    }

    @Test
    fun `두 번 숨겨도 한 번만 숨는다`() {
        val f = Fixture()
        f.controller.summon()
        f.controller.onIntent(FloatIntent.CANCEL)
        f.controller.onCloseButton()
        f.controller.hideExternally()
        assertEquals(listOf(HideReason.ESC), f.hides)
    }
}
