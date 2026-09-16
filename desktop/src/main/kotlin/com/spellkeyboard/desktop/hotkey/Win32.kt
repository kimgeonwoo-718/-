package com.spellkeyboard.desktop.hotkey

import com.sun.jna.IntegerType
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary

/**
 * `RegisterHotKey` 한 가지를 부르려고 두는 최소한의 Win32 창구.
 *
 * jna-platform 의 `User32` 를 쓰지 않는다. 그쪽은 `RegisterHotKey(HWND,…)` 와
 * `UnregisterHotKey(Pointer,…)` 의 첫 인자 꼴이 서로 달라 코틀린에서 `null as WinDef.HWND?`
 * / `null as Pointer?` 를 번갈아 써야 하고, 메시지 고리에 쓰는 `PeekMessage` 는 W32APIOptions
 * 를 타고 이름이 정해져 어느 것이 링크되는지 눈으로 확인할 수 없다. 여기서는 필요한 다섯
 * 가지만 **이름을 손으로 적어** 무엇이 링크되는지 분명히 해 둔다.
 */
internal object Win32 {
    const val MOD_NOREPEAT = 0x4000

    const val WM_QUIT = 0x0012
    const val WM_HOTKEY = 0x0312
    const val WM_USER = 0x0400
    const val WM_APP = 0x8000

    /** 고리 실에게 "할 일이 큐에 있다" 고 알리는 우리 신호. WM_APP 위는 앱 몫이다. */
    const val WM_APP_TASK = WM_APP + 1
    const val PM_NOREMOVE = 0x0000

    // 이름으로 다뤄야 할 오류들. 그때그때 숫자로 적어 두면 아무도 못 알아본다.
    const val ERROR_INVALID_FLAGS = 1004              // fsModifiers 에 엉뚱한 비트
    const val ERROR_HOTKEY_ALREADY_REGISTERED = 1409  // 누군가(우리 자신일 수도) 이미 쥐고 있다
    const val ERROR_HOTKEY_NOT_REGISTERED = 1419      // 쥐지도 않은 id 를 놓으려 했다
    const val ERROR_INVALID_THREAD_ID = 1444          // 큐가 없거나 이미 죽은 실에 부쳤다

    /** 이 기계에서 잰 값. 다르면 구조체 꼴이 어긋난 것이고 WM_HOTKEY 를 영영 못 본다. */
    const val EXPECTED_MSG_SIZE = 48

    val user32: U32 by lazy { Native.load("user32", U32::class.java) }
    val kernel32: K32 by lazy { Native.load("kernel32", K32::class.java) }
}

/**
 * WPARAM/LPARAM 은 **포인터 크기**다. `Int` 나 `NativeLong`(Win64 에서 4바이트)으로 적으면
 * MSG 가 48이 아니라 40바이트가 되고, 뒤쪽 칸이 통째로 밀려 고리가 **아무 말 없이** WM_HOTKEY
 * 를 못 본다. 터지지 않아서 더 나쁘다.
 */
internal class UlongPtr @JvmOverloads constructor(value: Long = 0) :
    IntegerType(Native.POINTER_SIZE, value, true) {
    override fun toByte(): Byte = toLong().toByte()
    override fun toShort(): Short = toLong().toShort()
}

@Structure.FieldOrder("x", "y")
internal class POINT : Structure() {
    @JvmField var x = 0
    @JvmField var y = 0
}

/** 이 기계에서 잰 꼴: 크기 48, 자리 hWnd 0 / message 8 / wParam 16 / lParam 24 / time 32 / pt 36. */
@Structure.FieldOrder("hWnd", "message", "wParam", "lParam", "time", "pt")
internal class MSG : Structure() {
    @JvmField var hWnd: Pointer? = null
    @JvmField var message: Int = 0
    @JvmField var wParam: UlongPtr = UlongPtr()
    @JvmField var lParam: UlongPtr = UlongPtr()
    @JvmField var time: Int = 0
    @JvmField var pt: POINT = POINT()
}

/**
 * `W` 꼬리표는 **빼면 안 된다.** user32 에는 맨몸 `GetMessage`/`PeekMessage`/`PostThreadMessage`
 * 라는 내보내기가 아예 없다. 그런데 `Native.load` 는 그냥 지나가고 **처음 부를 때** 비로소
 * UnsatisfiedLinkError 가 난다 — 그 자리가 하필 갓 띄운 단축키 실 안이라, 링크 오류가 아니라
 * "실이 죽었다"로 보인다. 반대로 `RegisterHotKey`/`UnregisterHotKey` 는 A/W 갈래가 없어
 * 맨몸 이름이어야 한다.
 */
internal interface U32 : StdCallLibrary {
    fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessageW(lpMsg: MSG, hWnd: Pointer?, min: Int, max: Int): Int
    fun PeekMessageW(lpMsg: MSG, hWnd: Pointer?, min: Int, max: Int, removeMsg: Int): Boolean
    fun PostThreadMessageW(idThread: Int, msg: Int, wParam: UlongPtr, lParam: UlongPtr): Boolean
}

internal interface K32 : StdCallLibrary {
    fun GetCurrentThreadId(): Int
}
