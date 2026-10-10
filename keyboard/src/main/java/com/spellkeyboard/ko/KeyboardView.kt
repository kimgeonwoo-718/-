package com.spellkeyboard.ko

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import kotlin.math.abs

/**
 * 소프트 키보드 화면.
 *
 * 키 배열을 코드로 조립한다. 자판이 세 가지(한글/영문/기호)뿐이고 시프트에 따라
 * 라벨만 바뀌어서, XML 레이아웃 여섯 벌을 만드는 것보다 이쪽이 짧고 어긋날 여지가 적다.
 *
 * 생김새는 삼성 키보드(One UI)를 기준으로 맞췄다 — 흰 글자 키, 한 단계 어두운 기능 키,
 * 늘 떠 있는 숫자 줄, 위쪽 도구 줄. 쓰던 키보드와 자판 위치가 다르면 오타가 나서,
 * 교정을 아무리 잘해도 손에 붙지 않는다.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onChar(c: Char)

        /** 길게 눌러 나온 대체 글자. 방금 넣은 글자를 이것으로 바꾼다. */
        fun onLongPressChar(c: Char)

        fun onAction(action: KeyAction)

        /** 문장 전체를 중계 서버(AI)로 교정한다. */
        /** 자판 위 '전체' 를 짧게 눌렀을 때. 기기 안에서 글 전체를 고친다. */
        fun onCorrectAll()

        /** '전체' 를 길게 눌렀을 때. 서버 AI 로 고친다(프리미엄). */
        fun onAiCorrect()

        /** 클립보드 목록을 열었다. 지금 복사돼 있는 것을 담을 기회다. */
        fun onClipboardOpened()

        /** 목록에서 고른 글을 넣는다. */
        fun onClipboardPaste(text: String)

        /** 목록에서 하나를 지운다. */
        fun onClipboardDelete(text: String)

        fun onOpenSettings()

        /** 자판 위 '교정' 동그라미. 실시간 교정을 끄고 켠다. */
        fun onToggleAutoCorrect()

        /** 번역 입력줄 열기/닫기. */
        fun onToggleTranslate()

        /** 번역 패널의 언어 칩을 눌렀다. [index] 는 [setTranslateMode] 에 준 언어 목록의 번호. */
        fun onSelectTranslateTarget(index: Int)

        /** 번역 패널의 '전체번역'(또는 '되돌리기')을 눌렀다. 번역 동그라미를 길게 눌러도 같다. */
        fun onTranslateAll()

        /** 번역 동그라미를 길게 눌렀다. 입력란에 이미 쓴 글을 통째로 옮긴다. */
        fun onTranslateField()

        /** 번역 패널의 'AI정밀번역'을 눌렀다. 구독자는 서버 AI 로 옮기고, 아니면 결제 화면으로 간다. */
        fun onTranslateAi()

        /**
         * 번역 패널의 지우기 키. 앱 입력란에 들어가 있는 글을 **한 글자** 지운다(꾹 누르면 반복).
         * 자판의 ⌫ 는 번역 중에 한국어 입력줄을 지우므로 입력줄이 비면 이미 들어간 번역문에 손이 안 닿는다 — 그 길이다.
         */
        fun onTranslateErase()

        /** 번역 입력줄의 글자를 눌러 커서를 [index] 로 옮기려 한다. */
        fun onTranslateCaret(index: Int)

        /** 자판 위 '교정' 버튼. 실시간 온디바이스 교정을 끄고 켠다. */

        /** 스페이스를 꾹 누른 채 밀어서 커서를 [delta] 글자만큼 옮긴다. 음수면 왼쪽. */
        fun onMoveCursor(delta: Int)

        /** 커서 이동 모드에 들어가거나 나왔다. 상단 줄 안내를 바꿀 기회다. */
        fun onCursorModeChanged(active: Boolean)

        /**
         * 연타로 도는 부호 키(천지인 `.,?!`, 숫자 판 `.,-/`). [options] 순서로 돈다.
         * 시간 판단은 받는 쪽 몫.
         */
        fun onPunctuationCycle(options: String)

        /** 이모티콘 판에서 하나 골랐다. 여러 코드 단위일 수 있다. */
        fun onEmoji(text: String)

        /**
         * 천지인 낱자 키. 두벌식과 달리 **전용 통로**로 보낸다 — onChar 로 보내면
         * 서비스가 어느 자판인지 다시 판별해야 하고, 그 판별이 어긋나면 점(ㆍ)이 그냥
         * 글자로 박혀 모음이 안 만들어진다(실기기에서 "안 모아짐"). 여기로 오면 무조건
         * 천지인 오토마타로 간다.
         */
        fun onCheonjiinKey(key: Char)
    }

    var listener: Listener? = null

    /** 키를 누르는 순간 불린다. 소리팩([KeySounds])이 여기서 소리를 낸다. */
    var keySound: ((KeySound) -> Unit)? = null

    private var mode = KeyboardMode.KOREAN
    private var shifted = false
    private var symbolPage = 0
    /** 숫자 키패드의 모양. [KeyboardMode.DIGITS] 일 때만 쓴다. */
    private var digitPad = DigitPad.NUMBER
    /** 숫자 키패드가 뜨기 전의 자판. 숫자 입력란을 떠나면 이리로 돌아간다. */
    private var modeBeforeDigits = KeyboardMode.KOREAN

    /** 지금 팔레트. [applyAppearance] 가 설정을 읽어 바꾼다. */
    private var theme: KeyboardTheme = KeyboardTheme.current(context)

    /** 사용자가 고른 배경 사진. 없으면 팔레트의 바탕색. */
    private var photo: Bitmap? = null
    private var photoStamp = -1L

    /** 테마의 바탕 사진(바다사자). 사용자 사진이 없을 때만 깐다. 테마가 바뀔 때 푼다. */
    private var themePhoto: Bitmap? = null
    private var themePhotoRes = 0
    private var layoutType: LayoutType = Prefs.layoutType(context)
    private var keyAlpha = alphaFor(Prefs.keyTransparency(context))

    private val emojiButton: TextView
    private val aiButton: TextView
    private val clipboardButton: TextView
    private val correctionButton: TextView
    private var autoCorrectOn = true

    /**
     * 교정 엔진(사전·언어모델·Kiwi)이 아직 올라오는 중인가.
     *
     * 처음 깔거나 갱신한 직후에는 엔진이 올라오는 동안(수 초~십수 초) LIVE 가 파랗게 켜져 있어도 교정이 일부만
     * 되거나 안 된다 — 사용자는 "켜져 있는데 안 된다" 로 본다. 그래서 그동안은 LIVE 자리에 '…' 를 보인다.
     */
    private var engineLoading = false
    private val translateButton: TextView
    private var translateOn = false
    private val translatePanel: LinearLayout
    private val translateSource: TranslateSourceView
    private val translateNote: TextView
    private val translateChips = ArrayList<TextView>()
    private val translateAllButton: TextView
    private val translateAiButton: TextView
    private val translateErase: TextView
    /** 패널 폭이 좁아 머리 줄(칩·단추)의 글자와 여백을 줄여야 하는 상태. */
    private var translateCompact = false
    private val translateClose: TextView
    private var translateSelected = 0
    private var translateUndo = false
    private var translateBusy = false
    private val settingsButton: TextView

    /** 도구 줄을 접는 손잡이. 접혀 있어도 이것만은 남아 있어야 다시 펼 수 있다. */
    private val collapseButton: TextView
    /** 도구 줄 전체. 높이를 바꿔서 접는다. */
    private val toolbar: LinearLayout
    /** 접을 때 감추는 부분 — 동그라미 여섯 개. [collapseButton] 은 여기 안 든다. */
    private val toolbarItems: LinearLayout
    private var toolbarCollapsed = Prefs.toolbarCollapsed(context)

    private val clipboardTitle: TextView
    private val rowContainer: LinearLayout
    private val clipboardPanel: LinearLayout
    private val clipboardList: LinearLayout
    private val emojiPanel: LinearLayout
    private val emojiList: LinearLayout
    private val emojiTitle: TextView

    /**
     * 잡고 있는 스페이스. 다른 키가 눌리는 순간 이걸 먼저 넣는다.
     *
     * 스페이스는 떼는 순간에 넣는데(꾹 누르면 커서 이동이라), 빨리 치면 스페이스를 떼기
     * 전에 다음 키가 먼저 들어가 "다 ㅁ" 이 "다ㅁ " 이 된다 — 220타 사용자가 "키가 씹힌다"
     * 로 겪은 것. 삼성처럼 다음 키의 DOWN 에서 잡고 있던 스페이스를 확정한다.
     */
    private var heldSpaceFlush: (() -> Unit)? = null
    private val repeatHandler = Handler(Looper.getMainLooper())

    /**
     * 지금 자판에 올라 있는 키들. [KeyPad] 가 여기서 손가락 아래 키를 찾는다.
     * 자판을 다시 그릴 때마다 비운다.
     */
    private val keySlots = ArrayList<PadSlot>()

    /** 손가락마다 어느 키를 잡고 있는지. 두 손가락이 동시에 눌려도 섞이지 않는다. */
    private val activeSlots = android.util.SparseArray<PadSlot>()

    /** 잠깐 떴다 사라지는 알림. [flash] 참고. */
    private var flashView: TextView? = null
    private val hideFlash = Runnable { dismissFlash() }

    /** 길게 누르는 중에 떠 있는 미리보기. 손을 떼면 여기 글자가 들어간다. */
    private var bubble: TextView? = null
    private var pendingAlternate: Char? = null

    private val repeatBackspace = object : Runnable {
        override fun run() {
            listener?.onAction(KeyAction.BACKSPACE)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /** 번역 패널 지우기 키를 꾹 누를 때의 반복. */
    private val repeatErase = object : Runnable {
        override fun run() {
            listener?.onTranslateErase()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(0, dp(2), 0, 0)

        // 이모티콘·클립보드·설정은 글자가 아니라 그림이다(삼성 키보드와 같은 모양, styleIconButton).
        emojiButton = toolbarButton("") { showEmoji() }.apply {
            contentDescription = context.getString(R.string.emoji_title)
        }
        // 짧게 누르면 **기기 안에서** 글 전체를 고친다 — 공짜고, 빠르고, 인터넷이 없어도 된다.
        // 길게 누르면 서버 AI(프리미엄). 자주 쓰는 쪽이 짧은 누르기다.
        //
        // 글자가 "AI" 가 아니라 "ALL" 인 이유: 짧게 누르는 쪽이 AI 가 아니기 때문이다.
        // 옆의 'LIVE' 는 실시간 교정 켬/끔이라 하는 일이 겹치지 않는다.
        aiButton = toolbarButton(
            context.getString(R.string.toolbar_correct_all),
            onPress = { listener?.onCorrectAll() },
            onLongPress = { listener?.onAiCorrect() }
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOLBAR_LABEL_SP)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // 28dp 동그라미라 넘치면 줄바꿈이 되어 모양이 무너진다. 잘리는 편이 낫다.
            maxLines = 1
            // 읽어 주는 이름은 우리말로 둔다. 화면에 쓰인 글자는 짧아야 해서 영문이다.
            contentDescription = context.getString(R.string.toolbar_correct_all_desc)
        }
        clipboardButton = toolbarButton("") { showClipboard() }.apply {
            contentDescription = context.getString(R.string.clipboard_title)
        }
        // 실시간 교정 켬/끔. 켜져 있으면 강조색 동그라미라 글자 없이도 상태가 보인다.
        correctionButton = toolbarButton(context.getString(R.string.toolbar_correction)) {
            listener?.onToggleAutoCorrect()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOLBAR_LABEL_SP)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            contentDescription = context.getString(R.string.toolbar_correction_desc)
        }
        // 번역 입력줄 열기/닫기. 열려 있으면 '교정' 처럼 강조색.
        translateButton = toolbarButton(
            context.getString(R.string.toolbar_translate),
            onPress = { listener?.onToggleTranslate() },
            onLongPress = { listener?.onTranslateField() }
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        settingsButton = toolbarButton("") { listener?.onOpenSettings() }.apply {
            contentDescription = context.getString(R.string.settings_title)
        }

        // 도구 줄 접기. 안 쓰는 사람에게는 34dp 를 늘 잡아먹는 줄이라, 접으면 자판이
        // 통째로 그만큼 내려앉는다. 접힌 상태에서는 이 손잡이가 줄 전체로 넓어져서
        // 얇아도 누르기 쉽다 — 높이는 못 키우니 너비로 벌어 준다.
        collapseButton = TextView(context).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            attachKeyTouch(this, onPress = { toggleToolbar() })
        }

        // 삼성처럼 동그라미들을 줄 전체에 고르게 펼친다. 사이와 양끝의 빈칸이 같은 무게라
        // 간격이 저절로 같아진다.
        toolbarItems = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(6), 0)
            listOf(emojiButton, aiButton, clipboardButton, correctionButton, translateButton, settingsButton).forEach { button ->
                addView(View(context), LayoutParams(0, 1, 1f))
                addView(button, toolbarParams())
            }
            addView(View(context), LayoutParams(0, 1, 1f))
        }
        toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            addView(collapseButton, LayoutParams(dp(COLLAPSE_BUTTON_DP), LayoutParams.MATCH_PARENT))
            addView(toolbarItems, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(TOOLBAR_HEIGHT_DP)))
        applyToolbarCollapsed()

        // 번역 패널. 자판 바로 위에 열린다:
        //   1) 언어 칩(영어·일본어·중국어)과 'AI정밀번역'·'전체번역'·닫기
        //   2) 한국어를 쓰는 줄 — 깜빡이는 커서가 있다. 한 줄 높이로 시작해 글이 길어지면 두 줄까지 자란다. 오른쪽에 지우기 키
        //   3) 안내 줄 — **할 말이 있을 때만** 나타난다(언어팩 받는 중, 받기 실패 등). 평소에는 없다
        // 번역 결과는 앱 입력란에 바로 들어가므로 따로 미리보기 줄을 두지 않는다.
        translateSource = TranslateSourceView(context).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            onCaretRequested = { index -> listener?.onTranslateCaret(index) }
        }
        translateNote = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            isVisible = false
        }
        translateAllButton = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            text = context.getString(R.string.translate_all)
            attachKeyTouch(this, onPress = { listener?.onTranslateAll() })
        }
        // 구독자 전용 서버 AI 번역. 구독자가 아니면 눌렀을 때 결제 화면으로 간다(서비스가 가른다).
        translateAiButton = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            text = context.getString(R.string.translate_ai)
            attachKeyTouch(this, onPress = { listener?.onTranslateAi() })
        }
        // 입력줄 오른쪽 지우기 키. 앱 입력란에 이미 들어간 번역문을 한 글자씩 지운다.
        translateErase = TextView(context).apply {
            text = "⌫"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = context.getString(R.string.translate_erase_desc)
            attachRepeatingKeyTouch(this, repeatErase, KeySound.DELETE) { listener?.onTranslateErase() }
        }
        translateClose = toolbarButton("✕") { listener?.onToggleTranslate() }
        val translateHeader = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            // 언어 칩은 목록이 정해진 뒤([setTranslateMode])에 이 줄 앞쪽에 끼운다. 자리만 잡아 둔다.
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(translateAiButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(TRANSLATE_CHIP_DP)).apply {
                rightMargin = dp(4)
            })
            addView(translateAllButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(TRANSLATE_CHIP_DP)))
            addView(View(context), LayoutParams(dp(6), 1))
            addView(translateClose, toolbarParams())
            // 칩 셋 + 단추 둘 + 닫기가 한 줄에 다 들어가야 한다. 좁은 폰(360dp 대)에서는 안 들어가서, 폭을 보고 글자·여백을 줄인다.
            // 레이아웃 도중에 크기를 바꾸면 안 되므로 post 로 미룬다.
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val compact = right - left < dp(TRANSLATE_COMPACT_BELOW_DP)
                if (compact != translateCompact) {
                    translateCompact = compact
                    post { styleTranslateHeaderSizes() }
                }
            }
        }
        translatePanel = LinearLayout(context).apply {
            orientation = VERTICAL
            isVisible = false
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(translateHeader, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRANSLATE_HEADER_DP)))
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(translateSource, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(translateErase, LayoutParams(dp(TRANSLATE_ERASE_WIDTH_DP), dp(TRANSLATE_CHIP_DP)).apply {
                    leftMargin = dp(4)
                })
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(translateNote, LayoutParams(LayoutParams.MATCH_PARENT, dp(TRANSLATE_NOTE_DP)))
        }
        addView(translatePanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        rowContainer = KeyPad(context).apply {
            orientation = VERTICAL
            // 자판 둘레의 여백. **판 바깥이 아니라 판 안쪽 여백이어야 한다** — 그래야
            // 화면 맨 아래나 좌우 가장자리를 눌러도 가까운 키가 받는다.
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        addView(rowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        clipboardList = LinearLayout(context).apply { orientation = VERTICAL }
        clipboardTitle = TextView(context).apply {
            text = context.getString(R.string.clipboard_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), 0, 0, 0)
        }
        clipboardPanel = buildClipboardPanel()
        addView(clipboardPanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(CLIPBOARD_HEIGHT_DP)))

        emojiList = LinearLayout(context).apply { orientation = VERTICAL }
        emojiTitle = TextView(context).apply {
            text = context.getString(R.string.emoji_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }
        emojiPanel = buildEmojiPanel()
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, dp(CLIPBOARD_HEIGHT_DP)))

        applyAppearance(force = true)
        padForNavigationBar()
    }

    /**
     * Android 15(targetSdk 35) 부터 키보드 창이 화면 맨 밑까지 그려진다. 그대로 두면 맨 아랫줄
     * (스페이스·엔터)이 밑의 시스템 단추(키보드 바꾸기 ⌨, 내리기 ∨)에 가리고, 가린 곳은 눌러도
     * 시스템이 받는다 — 스페이스가 반만 눌렸다. 바가 차지한 높이만큼 아래를 띄운다.
     *
     * **`navigationBars()` 만 보면 안 된다.** 제스처 내비게이션에서 그 단추 줄은 키보드 창 **안에**
     * 시스템이 그려 넣는 'IME 내비게이션 바' 이고, 키보드 창에는 **captionBar** 로 알려진다
     * (AOSP `inputmethodservice/NavigationBarController`). 첫 판은 navigationBars 만 봐서 0 을
     * 받았고, 삼성 폰에서 아랫줄이 단추 밑에 깔렸다(2026-09-27). 시스템이 그 단추 줄 높이를 잴
     * 때와 같은 식 — systemBars | displayCutout 의 아래 — 으로 잰다. 세 단추 내비게이션이면
     * navigationBars 가, 제스처면 captionBar 가 여기에 들어온다.
     *
     * 34 이하는 시스템이 창 내용을 바 위로 올려 주므로(decorFitsSystemWindows) 손대지 않는다.
     *
     * **알림(insets 전달)만 믿으면 안 된다.** 시스템은 창의 여백이 바뀔 때만 알린다. 키보드 화면이
     * 그 알림 **뒤에** 창에 붙으면(설정이 바뀌어 [SpellKeyboardService.onCreateInputView] 가 새로
     * 불렸을 때 등) 다음 알림까지 여백 0 으로 산다 — "됐다 안 됐다, 멀티윈도우 들어갔다 나오면
     * 된다" 가 그것이었다(2026-09-29). 그래서 창에 붙을 때와 키보드가 뜰 때마다 창의 **지금**
     * 여백([getRootWindowInsets])을 직접 읽어 맞춘다([syncNavigationBarPadding]).
     */
    private fun padForNavigationBar() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        setOnApplyWindowInsetsListener { _, insets ->
            padBottom(insets)
            insets
        }
    }

    /** 창의 지금 여백으로 아래를 맞춘다. 키보드가 뜰 때마다 서비스가 부른다. 바뀐 게 없으면 아무 일도 안 한다. */
    fun syncNavigationBarPadding() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        rootWindowInsets?.let { padBottom(it) }
        requestApplyInsets()
        // 뜨는 도중에는 단추 줄 높이가 아직 안 정해졌을 수 있다. 한 박자 뒤에 한 번 더 본다.
        post { rootWindowInsets?.let { padBottom(it) } }
    }

    private fun padBottom(insets: android.view.WindowInsets) {
        val types = android.view.WindowInsets.Type.systemBars() or
            android.view.WindowInsets.Type.displayCutout()
        val bottom = insets.getInsets(types).bottom
        if (paddingBottom != bottom) setPadding(paddingLeft, paddingTop, paddingRight, bottom)
    }

    /** '교정' 동그라미의 켜짐 표시. 켜져 있으면 강조색, 꺼져 있으면 흐리게. */
    fun setAutoCorrectOn(on: Boolean) {
        autoCorrectOn = on
        styleCorrectionButton()
    }

    /** 교정 엔진이 올라오는 중이면 true. 올라오는 동안 LIVE 자리에 '…' 가 보인다. */
    fun setEngineLoading(loading: Boolean) {
        if (engineLoading == loading) return
        engineLoading = loading
        styleCorrectionButton()
    }

    private fun toggleToolbar() {
        toolbarCollapsed = !toolbarCollapsed
        Prefs.setToolbarCollapsed(context, toolbarCollapsed)
        // 접는 김에 열려 있던 판도 닫는다. 접힌 줄 밑에 클립보드가 펼쳐져 있으면
        // 무엇을 접은 것인지 알 수 없다.
        if (toolbarCollapsed) closePanels()
        applyToolbarCollapsed()
    }

    /**
     * 접힘 상태를 실제 배치에 반영한다.
     *
     * 동그라미들을 감추고 줄 높이를 줄인다. 감추기만 하면 빈 줄이 그대로 남아서 아무것도
     * 아낀 게 없다 — 접는 이유가 자판을 그만큼 내리는 것이라 높이를 같이 줄여야 한다.
     */
    private fun applyToolbarCollapsed() {
        toolbarItems.isVisible = !toolbarCollapsed
        collapseButton.text = if (toolbarCollapsed) "▼" else "▲"
        collapseButton.contentDescription =
            context.getString(if (toolbarCollapsed) R.string.toolbar_expand else R.string.toolbar_collapse)
        // 접히면 손잡이가 줄 전체를 차지한다. 22dp 짜리 띠라 세로로는 좁으니 가로로 넓혀
        // 어디를 눌러도 펴지게 한다.
        collapseButton.updateLayoutParams<LayoutParams> {
            width = if (toolbarCollapsed) LayoutParams.MATCH_PARENT else dp(COLLAPSE_BUTTON_DP)
        }
        toolbar.updateLayoutParams<LayoutParams> {
            height = dp(if (toolbarCollapsed) TOOLBAR_COLLAPSED_DP else TOOLBAR_HEIGHT_DP)
        }
        styleCollapseButton()
    }

    /**
     * 접기 손잡이의 색.
     *
     * 펴져 있을 때는 배경 없이 작은 삼각형만 둔다 — 동그라미를 주면 일곱 번째 도구처럼
     * 보인다. 접혀 있을 때는 이 띠 하나만 남으므로, 눌러도 되는 것임이 보이도록 옅은
     * 바탕을 깐다.
     */
    private fun styleCollapseButton() {
        collapseButton.setTextColor(theme.hint)
        collapseButton.background = if (toolbarCollapsed) roundRect(toolbarFill(theme.toolbarButton)) else null
    }

    /** AI 가 도는 동안 AI 버튼을 흐리게. */
    fun setAiBusy(busy: Boolean) {
        aiButton.alpha = if (busy) 0.35f else 1f
    }

    /** 서버 번역이 도는 동안 번역 버튼을 흐리게. */
    fun setTranslateBusy(busy: Boolean) {
        translateBusy = busy
        translateButton.alpha = if (busy) 0.35f else 1f
        translateAllButton.alpha = if (busy) 0.35f else 1f
        translateAiButton.alpha = if (busy) 0.35f else 1f
    }

    /**
     * 번역 패널을 열거나 닫는다.
     *
     * @param labels 언어 칩에 쓸 이름들("영어", "일본어", "중국어"). 칩은 이 목록대로 만든다.
     * @param selected 지금 고른 언어의 번호
     */
    fun setTranslateMode(on: Boolean, labels: List<String>, selected: Int) {
        translateOn = on
        translateSelected = selected
        if (translateChips.size != labels.size) rebuildTranslateChips(labels)
        labels.forEachIndexed { i, label -> translateChips[i].text = label }
        // 가로 화면은 높이가 모자라 한국어 줄이 자라지 않고 안내 줄도 없앤다.
        translateSource.maxRows = if (isLandscape()) 1 else TRANSLATE_SOURCE_MAX_ROWS
        applyTranslateNote()
        translatePanel.isVisible = on
        if (on) closePanels()
        styleTranslateButton()
    }

    /** 언어 칩을 목록대로 다시 만든다. 머리 줄의 맨 앞(빈칸 앞)에 끼운다. */
    private fun rebuildTranslateChips(labels: List<String>) {
        val header = translatePanel.getChildAt(0) as LinearLayout
        translateChips.forEach { header.removeView(it) }
        translateChips.clear()
        labels.forEachIndexed { index, label ->
            val chip = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(11), 0, dp(11), 0)
                maxLines = 1
                attachKeyTouch(this, onPress = { listener?.onSelectTranslateTarget(index) })
            }
            translateChips += chip
            header.addView(chip, index, LayoutParams(LayoutParams.WRAP_CONTENT, dp(TRANSLATE_CHIP_DP)).apply {
                rightMargin = dp(4)
            })
        }
        styleTranslateHeaderSizes()
    }

    /** 머리 줄의 칩·단추 글자 크기와 가로 여백. 폭이 좁으면([translateCompact]) 줄인다. */
    private fun styleTranslateHeaderSizes() {
        val size = if (translateCompact) 11f else 12f
        val chipPad = dp(if (translateCompact) 7 else 11)
        val buttonPad = dp(if (translateCompact) 7 else 12)
        translateChips.forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            it.setPadding(chipPad, 0, chipPad, 0)
        }
        listOf(translateAiButton, translateAllButton).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            it.setPadding(buttonPad, 0, buttonPad, 0)
        }
    }

    /** 고른 언어가 바뀌었다(칩 색만 바꾼다). */
    fun setTranslateSelected(selected: Int) {
        translateSelected = selected
        styleTranslateButton()
    }

    /** 입력줄에 지금까지 쓴 한국어와 커서 자리. 비면 [hint] 가 흐리게 보인다. */
    fun setTranslateSource(text: String, cursor: Int, hint: String) {
        translateSource.setContent(text, cursor, hint)
    }

    private var translateNoteText = ""

    private fun isLandscape() =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /**
     * 입력줄 아래 안내 줄. [note] 가 비면 줄째 없어진다 — 평소에는 자리를 안 차지한다.
     * 언어팩 받는 중·받기 실패처럼 **사용자가 알아야 하는데 다른 데 보일 곳이 없는 말**만 여기 온다.
     */
    fun setTranslateNote(note: String) {
        if (note == translateNoteText) return
        translateNoteText = note
        applyTranslateNote()
    }

    private fun applyTranslateNote() {
        translateNote.text = translateNoteText
        translateNote.isVisible = translateNoteText.isNotEmpty() && !isLandscape()
    }

    /** '전체번역' 자리를 '되돌리기' 로 바꾼다(방금 입력란을 통째로 옮겼을 때). */
    fun setTranslateUndo(available: Boolean) {
        translateUndo = available
        translateAllButton.text = context.getString(if (available) R.string.translate_undo else R.string.translate_all)
    }

    private fun styleTranslateButton() {
        if (translateOn) {
            translateButton.setTextColor(theme.onAccent)
            translateButton.background = circle(theme.accent)
        } else {
            styleToolbarButton(translateButton)
        }
        translateSource.setColors(theme.text, theme.hint, theme.accent)
        translateNote.setTextColor(theme.hint)
        translatePanel.background = roundRect(toolbarFill(theme.panelItem))
        translateChips.forEachIndexed { index, chip ->
            if (index == translateSelected) {
                chip.setTextColor(theme.onAccent)
                chip.background = roundRect(theme.accent)
            } else {
                chip.setTextColor(theme.text)
                chip.background = roundRect(toolbarFill(theme.toolbarButton))
            }
        }
        translateAllButton.setTextColor(if (translateUndo) theme.onAccent else theme.text)
        translateAllButton.background = roundRect(if (translateUndo) theme.accent else toolbarFill(theme.toolbarButton))
        // AI정밀번역은 글자색만 강조색으로 해서 다른 단추와 구분한다.
        translateAiButton.setTextColor(theme.accent)
        translateAiButton.background = roundRect(toolbarFill(theme.toolbarButton))
        translateErase.setTextColor(theme.text)
        translateErase.background = roundRect(toolbarFill(theme.toolbarButton))
        styleToolbarButton(translateClose)
    }

    /**
     * 자판 위에 작은 알림을 잠깐 띄웠다 지운다.
     *
     * 자판에 글을 상시로 두지 않기로 했으므로, 꼭 알아야 할 것(눌렀는데 안 된 이유,
     * 고칠 게 없었다는 것)만 이렇게 스쳐 지나가게 한다. 시스템 토스트는 자판 아래
     * 엉뚱한 데 뜨고 키를 가려서 쓰지 않는다. 오버레이에 그리므로 자판 배치는 안 흔들린다.
     */
    fun flash(text: String) {
        dismissFlash()
        val view = TextView(context).apply {
            this.text = text
            setTextColor(theme.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundRect(withAlpha(theme.text, 0.88f))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            measure(
                MeasureSpec.makeMeasureSpec(width - dp(24), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val left = (width - view.measuredWidth) / 2
        // 도구 줄 자리에 띄운다. 접혀 있으면 그 줄이 얇아지니 실제 높이를 쓴다 —
        // 상수를 쓰면 접었을 때 알림이 자판 키를 덮는다.
        val strip = if (toolbar.height > 0) toolbar.height else dp(TOOLBAR_HEIGHT_DP)
        val top = (strip - view.measuredHeight) / 2 + dp(2)
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
        view.alpha = 0f
        overlay.add(view)
        view.animate().alpha(1f).setDuration(120).start()
        flashView = view
        repeatHandler.postDelayed(hideFlash, FLASH_MS)
    }

    private fun dismissFlash() {
        repeatHandler.removeCallbacks(hideFlash)
        flashView?.let { view ->
            view.animate().alpha(0f).setDuration(220).withEndAction { overlay.remove(view) }.start()
        }
        flashView = null
    }


    // --- 모양 -------------------------------------------------------------------

    /**
     * 설정(테마·배경 사진)을 다시 읽어 적용한다.
     *
     * 키보드가 뜰 때마다 불린다. 바뀐 게 없으면 아무것도 하지 않는다 — 자판을 다시
     * 조립하는 건 싸지 않고, 사진을 다시 푸는 건 더 비싸다.
     */
    fun applyAppearance(force: Boolean = false) {
        val nextTheme = KeyboardTheme.current(context)
        val nextStamp = BackgroundImage.stamp(context)
        val nextLayout = Prefs.layoutType(context)
        val nextAlpha = alphaFor(Prefs.keyTransparency(context))
        val themeChanged = nextTheme != theme
        val photoChanged = nextStamp != photoStamp
        val layoutChanged = nextLayout != layoutType
        val alphaChanged = nextAlpha != keyAlpha
        if (!force && !themeChanged && !photoChanged && !layoutChanged && !alphaChanged) return

        theme = nextTheme
        layoutType = nextLayout
        keyAlpha = nextAlpha
        if (photoChanged || force) {
            photo = if (nextStamp == 0L) null else BackgroundImage.load(context)
            photoStamp = nextStamp
        }
        if (theme.backgroundPhoto != themePhotoRes) {
            themePhotoRes = theme.backgroundPhoto
            // RGB_565 — 투명이 없는 그림이라 절반 메모리로 충분하다(3000×1000 이 6MB).
            themePhoto = themePhotoRes.takeIf { it != 0 }?.let { res ->
                runCatching {
                    android.graphics.BitmapFactory.decodeResource(
                        resources, res,
                        android.graphics.BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                    )
                }.getOrNull()
            }
        }
        restyle()
        render()
        updateBackground()
    }

    /** 자판 밖의 것들(도구 줄, 클립보드 머리)에 팔레트를 입힌다. 키는 [render] 가 한다. */
    private fun restyle() {
        styleToolbarButton(aiButton)
        styleIconButton(emojiButton, R.drawable.ic_emoji)
        styleIconButton(clipboardButton, R.drawable.ic_clipboard)
        styleIconButton(settingsButton, R.drawable.ic_settings)
        styleCollapseButton()
        styleCorrectionButton()
        styleTranslateButton()
        clipboardTitle.setTextColor(theme.text)
        emojiTitle.setTextColor(theme.text)
    }

    private fun styleToolbarButton(view: TextView) {
        view.setTextColor(theme.text)
        view.background = circle(toolbarFill(theme.toolbarButton))
    }

    /**
     * 그림 아이콘 동그라미(이모티콘·클립보드·설정). 글자가 아니라 그림이라 배경을 두 겹으로
     * 쌓는다 — 아래가 동그라미, 위가 아이콘. 아이콘은 사방에 같은 여백을 두고 넣어서 가운데에
     * 놓이게 한다. 셋 다 같은 크기·같은 선 굵기라 나란히 놓아도 톤이 맞는다.
     */
    private fun styleIconButton(button: TextView, iconRes: Int) {
        val face = circle(toolbarFill(theme.toolbarButton))
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (icon == null) {
            button.background = face
            return
        }
        icon.setTint(theme.text)
        val inset = (dp(TOOLBAR_BUTTON_DP) - dp(TOOLBAR_ICON_DP)) / 2
        button.background = LayerDrawable(arrayOf(face, InsetDrawable(icon, inset)))
    }

    private fun styleCorrectionButton() {
        // 올라오는 중에는 켜짐·꺼짐과 상관없이 '…' 를 흐리게 보인다. 눌러서 켜고 끄는 것은 그대로 된다.
        correctionButton.text = context.getString(
            if (engineLoading) R.string.toolbar_correction_loading else R.string.toolbar_correction
        )
        correctionButton.contentDescription = context.getString(
            if (engineLoading) R.string.toolbar_correction_loading_desc else R.string.toolbar_correction_desc
        )
        if (engineLoading) {
            correctionButton.setTextColor(theme.hint)
            correctionButton.background = circle(toolbarFill(theme.toolbarButton))
        } else if (autoCorrectOn) {
            correctionButton.setTextColor(theme.onAccent)
            correctionButton.background = circle(theme.accent)
        } else {
            correctionButton.setTextColor(theme.hint)
            correctionButton.background = circle(toolbarFill(theme.toolbarButton))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) updateBackground()
    }

    /**
     * 바탕을 깐다. 사진이 있으면 키보드 크기에 맞춰 가운데를 잘라 쓴다.
     *
     * 크기가 정해진 뒤에만 그릴 수 있어서 [onSizeChanged] 에서도 부른다. 클립보드를
     * 열면 높이가 바뀌므로 그때도 다시 잘린다.
     */
    private fun updateBackground() {
        val source = photo ?: themePhoto
        if (source == null || width <= 0 || height <= 0) {
            // 사진이 없으면 테마 그림(바다사자 바다), 그것도 없으면 바탕색.
            val art = theme.backgroundArt.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it) }
            background = art ?: ColorDrawable(theme.background)
            return
        }
        val scale = maxOf(width / source.width.toFloat(), height / source.height.toFloat())
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }
        val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(cropped).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        background = BitmapDrawable(resources, cropped)
    }

    /**
     * 사진 위에서는 키를 반투명하게 한다. 사진을 깔았는데 안 보이면 깐 의미가 없다.
     * 86% → 50% → 30% 로, "사진이 더 보여야 한다" 는 말을 두 번 듣고 내렸다. 글자는
     * 진한 색 그대로라 키가 거의 비쳐도 읽힌다.
     */
    private fun keyFill(color: Int): Int = when {
        photo != null -> withAlpha(color, keyAlpha)
        themePhoto != null -> withAlpha(color, theme.photoKeyAlpha)
        else -> color
    }

    /**
     * 도구 줄 단추·번역 입력줄. 사용자 사진 위에서는 키처럼 비치지만, **테마 사진 위에서는 비치지
     * 않는다** — 바다사자 테마에서 사용자가 "위 기능키는 원래에 가깝게" 를 골랐다. 단추가 작아서
     * 비치면 무엇인지 안 보인다.
     */
    private fun toolbarFill(color: Int): Int = if (photo != null) withAlpha(color, keyAlpha) else color

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /**
     * '전체' 단추를 보일지.
     *
     * 예전에는 **서버가 있을 때만** 보였다(`setAiAvailable`). 이제는 짧게 누르는 쪽이
     * 기기 안 전체교정이라 서버와 무관하다. 대신 **고쳐도 되는 입력란인지**는 그대로
     * 따진다 — 비밀번호 칸에서는 교정하지 않는다.
     */
    fun setCorrectAllAvailable(available: Boolean) {
        aiButton.isVisible = available
    }

    /**
     * 번역 동그라미를 내놓을지. 길게 누르면 입력란의 글이 통째로 서버로 가므로,
     * 비밀번호 같은 칸에서는 아예 보이지 않아야 한다.
     */
    fun setTranslateAvailable(available: Boolean) {
        translateButton.isVisible = available
    }

    /**
     * 숫자 입력란에 들어왔다. 숫자 키패드를 띄운다. 이미 떠 있어도 모양([pad])이 다르면 다시 그린다.
     */
    fun showDigitPad(pad: DigitPad) {
        if (mode == KeyboardMode.DIGITS && digitPad == pad) return
        if (mode != KeyboardMode.DIGITS) modeBeforeDigits = mode
        digitPad = pad
        mode = KeyboardMode.DIGITS
        shifted = false
        render()
    }

    /** 숫자 입력란을 떠났다. 숫자 키패드였으면 그 전 자판(한글·영문)으로 돌아간다. */
    fun leaveDigitPad() {
        if (mode != KeyboardMode.DIGITS) return
        setMode(if (modeBeforeDigits == KeyboardMode.ENGLISH) KeyboardMode.ENGLISH else KeyboardMode.KOREAN)
    }

    fun setMode(newMode: KeyboardMode) {
        if (mode == newMode) return
        mode = newMode
        shifted = false
        symbolPage = 0
        render()
    }

    /** 기호 페이지를 넘긴다(1/2 ↔ 2/2). */
    fun flipSymbolPage() {
        symbolPage = (symbolPage + 1) % symbolPageCount()
        render()
    }

    private fun symbolPageCount(): Int =
        if (layoutType == LayoutType.CHEONJIIN) KeyboardLayout.CHEONJIIN_SYMBOL_PAGES.size
        else KeyboardLayout.SYMBOL_PAGES.size

    fun toggleShift() {
        if (!KeyboardLayout.supportsShift(mode)) return
        shifted = !shifted
        render()
    }

    fun clearShift() {
        if (!shifted) return
        shifted = false
        render()
    }

    fun currentMode(): KeyboardMode = mode

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 창에 붙기 전에 부른 requestApplyInsets 는 헛일이다(부모가 없다). 붙은 지금 맞춘다.
        syncNavigationBarPadding()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatHandler.removeCallbacksAndMessages(null)
        dismissAlternate()
    }

    // --- 클립보드 -------------------------------------------------------------

    private fun buildClipboardPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = false
        setPadding(dp(2), 0, dp(2), dp(6))

        clipboardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clipboardTitle.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
            addView(clipboardTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toolbarButton("✕") { hideClipboard() }, toolbarParams())
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        clipboardList.setPadding(dp(4), 0, dp(4), dp(6))
        addView(
            ScrollView(this@KeyboardView.context).apply {
                isVerticalScrollBarEnabled = false
                addView(clipboardList)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /**
     * 클립보드 목록을 삼성처럼 2열 카드 격자로 채운다. 비어 있으면 왜 비었는지 알려 준다.
     */
    fun showClipboardItems(items: List<String>) {
        clipboardList.removeAllViews()
        if (items.isEmpty()) {
            clipboardList.addView(
                TextView(context).apply {
                    text = context.getString(R.string.clipboard_empty)
                    setTextColor(theme.status)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(28), dp(24), dp(28))
                }
            )
            return
        }
        // 두 개씩 한 줄. 홀수면 마지막 칸은 빈 자리로 폭을 맞춘다.
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            pair.forEach { row.addView(clipboardCard(it), cardParams()) }
            if (pair.size == 1) row.addView(View(context), cardParams())
            clipboardList.addView(row)
        }
    }

    private fun cardParams() = LayoutParams(0, dp(CLIPBOARD_CARD_HEIGHT_DP), 1f).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    /** 카드 하나 — 눌러 붙여넣기, 오른쪽 위 작은 ✕ 로 삭제. */
    private fun clipboardCard(text: String): View {
        val card = android.widget.FrameLayout(context).apply {
            background = roundRect(keyFill(theme.panelItem))
        }
        val label = TextView(context).apply {
            this.text = text
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(label, android.widget.FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        ))
        attachPanelTouch(card, onPress = {
            listener?.onClipboardPaste(text)
            hideClipboard()
        })

        val delete = TextView(context).apply {
            this.text = "✕"
            gravity = Gravity.CENTER
            setTextColor(theme.status)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = circle(theme.background)
            attachPanelTouch(this, onPress = { listener?.onClipboardDelete(text) })
        }
        card.addView(delete, android.widget.FrameLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(6), dp(6), 0)
        })
        return card
    }

    private fun showClipboard() {
        listener?.onClipboardOpened()
        emojiPanel.isVisible = false
        clipboardPanel.isVisible = true
        rowContainer.isVisible = false
    }

    private fun hideClipboard() {
        clipboardPanel.isVisible = false
        rowContainer.isVisible = true
    }

    /** 클립보드나 이모티콘 판이 열려 있으면 닫고 true. 뒤로 가기에서 쓴다. */
    fun closePanels(): Boolean {
        if (!clipboardPanel.isVisible && !emojiPanel.isVisible) return false
        clipboardPanel.isVisible = false
        emojiPanel.isVisible = false
        rowContainer.isVisible = true
        return true
    }

    // --- 이모티콘 -------------------------------------------------------------

    private fun buildEmojiPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = false
        setPadding(dp(2), 0, dp(2), dp(6))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, 0, 0)
            addView(emojiTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            // 판을 닫지 않고 지울 수 있어야 한다. 하나 넣고 보니 아니다 싶은 게 잦다.
            addView(toolbarButton("⌫") { listener?.onAction(KeyAction.BACKSPACE) }, toolbarParams())
            addView(toolbarButton("✕") { closePanels() }, toolbarParams())
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        emojiList.setPadding(dp(4), 0, dp(4), dp(6))
        addView(
            ScrollView(this@KeyboardView.context).apply {
                isVerticalScrollBarEnabled = false
                addView(emojiList)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun showEmoji() {
        if (emojiList.childCount == 0) fillEmoji()
        clipboardPanel.isVisible = false
        emojiPanel.isVisible = true
        rowContainer.isVisible = false
    }

    /** 갈래 이름 한 줄, 그 아래 8열 격자. 처음 열 때 한 번만 만든다. */
    private fun fillEmoji() {
        EmojiSet.CATEGORIES.forEach { category ->
            emojiList.addView(
                TextView(context).apply {
                    text = category.name
                    setTextColor(theme.status)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(8), dp(8), dp(8), dp(2))
                }
            )
            category.items.chunked(EMOJI_COLUMNS).forEach { line ->
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                line.forEach { emoji ->
                    val cell = TextView(context).apply {
                        text = emoji
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        contentDescription = emoji
                    }
                    attachPanelTouch(cell, onPress = { listener?.onEmoji(emoji) })
                    row.addView(cell, LayoutParams(0, dp(EMOJI_CELL_DP), 1f))
                }
                repeat(EMOJI_COLUMNS - line.size) { row.addView(View(context), LayoutParams(0, dp(EMOJI_CELL_DP), 1f)) }
                emojiList.addView(row)
            }
        }
    }

    // --- 자판 조립 -------------------------------------------------------------

    private fun render() {
        // 화면을 다시 그리면 누르고 있던 키의 뷰가 사라진다. 예약해 둔 길게 누르기나
        // 반복 입력이 그대로 살아 있으면 손을 뗀 뒤에 글자가 튀어나온다.
        repeatHandler.removeCallbacksAndMessages(null)
        dismissAlternate()
        // 사라질 뷰를 가리키는 키가 남아 있으면, 손을 떼는 순간 없는 키가 입력된다.
        keySlots.clear()
        activeSlots.clear()
        rowContainer.removeAllViews()
        // 숫자 키패드는 자판 종류(쿼티·천지인)와 상관없이 같다.
        if (mode == KeyboardMode.DIGITS) return renderDigits()
        if (layoutType == LayoutType.CHEONJIIN) {
            when (mode) {
                KeyboardMode.KOREAN -> return renderCheonjiin()
                KeyboardMode.NUMPAD -> return renderNumpad()
                KeyboardMode.SYMBOLS -> return renderCheonjiinSymbols()
                KeyboardMode.ENGLISH, KeyboardMode.DIGITS -> Unit
            }
        }
        if (mode == KeyboardMode.SYMBOLS || mode == KeyboardMode.NUMPAD) {
            renderSymbols()
            return
        }
        val rows = KeyboardLayout.rowsFor(mode, shifted)

        // 숫자 줄은 늘 떠 있다. 숫자 하나 넣자고 기호 자판을 오갈 일이 없어진다.
        if (KeyboardLayout.showsNumberRow(mode)) {
            rowContainer.addView(buildRow { KeyboardLayout.NUMBER_ROW.forEach { addCharKey(it) } })
        }

        rowContainer.addView(buildRow { rows[0].forEach { addCharKey(it) } })

        // 둘째 줄은 9 개라 한 칸이 남는다. 좌우에 반 칸씩 둬서 **가운데**에 놓는다 —
        // 그냥 두면 왼쪽으로 쏠려서 위아래 줄과 키 위치가 어긋나고, 그 어긋남이
        // 그대로 오타가 된다.
        rowContainer.addView(buildRow {
            addSpacer(0.5f)
            rows[1].forEach { addCharKey(it) }
            addSpacer(0.5f)
        })

        // 시프트 + 문자 + 백스페이스.
        rowContainer.addView(buildRow {
            if (KeyboardLayout.supportsShift(mode)) {
                addActionKey(if (shifted) "⬆" else "⇧", KeyAction.SHIFT, weight = 1.5f)
            } else {
                addSpacer(1.5f)
            }
            rows[2].forEach { addCharKey(it) }
            addSpacer((7 - rows[2].length).coerceAtLeast(0) * 1f)
            addActionKey("⌫", KeyAction.BACKSPACE, weight = 1.5f, repeatable = true)
        })

        // 자판 전환과 스페이스.
        rowContainer.addView(buildRow {
            val symbolLabel = if (mode == KeyboardMode.SYMBOLS) "가A" else "!#1"
            addActionKey(symbolLabel, KeyAction.SYMBOLS, weight = 1.35f)
            val language = if (mode == KeyboardMode.ENGLISH) "EN" else "한/영"
            addActionKey(language, KeyAction.LANGUAGE, weight = 1.05f)
            addCharKey(',', weight = 0.9f)
            addActionKey("", KeyAction.SPACE, weight = 4.35f, letterKey = true)
            addCharKey('.', weight = 0.9f)
            addActionKey("↵", KeyAction.ENTER, weight = 1.45f)
        })
    }

    /**
     * 천지인 한글 판. 삼성 배열 그대로:
     *
     *     ㅣ¹  ㆍ²  ㅡ³   ⌫
     *     ㄱㅋ⁴ ㄴㄹ⁵ ㄷㅌ⁶  ↵
     *     ㅂㅍ⁷ ㅅㅎ⁸ ㅈㅊ⁹  .,?!
     *     !#1 한/영 ㅇㅁ⁰  ␣   ,
     *
     * 줄이 넷뿐이라 쿼티(다섯 줄)와 높이를 맞추려고 키를 더 높게 둔다.
     */
    private fun renderCheonjiin() {
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,?!", KeyboardLayout.CHEONJIIN_PUNCTUATION) }
        )
        KeyboardLayout.CHEONJIIN_GRID.forEachIndexed { index, gridRow ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                gridRow.forEach { addCheonjiinKey(it) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("!#1", KeyAction.NUMPAD, weight = GRID_W / 2)
            addActionKey("한/영", KeyAction.LANGUAGE, weight = GRID_W / 2)
            addCheonjiinKey(KeyboardLayout.CHEONJIIN_ZERO_KEY)
            // 삼성처럼 스페이스가 "다음 글자" 를 겸한다. 조합 중이면 한 번 눌러 글자를
            // 끊고, 한 번 더 누르면 그때 띄어쓰기다. 처리는 SpellKeyboardService 쪽에 있다.
            addActionKey("", KeyAction.SPACE, weight = GRID_W, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /**
     * 천지인 숫자 판(삼성의 !#1). 1~9 · ⌫ ↵ .,-/ · 넷째 줄 !@# 가 0 ␣ ,
     */
    private fun renderNumpad() {
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,-/", KeyboardLayout.NUMPAD_PUNCTUATION) }
        )
        KeyboardLayout.CHEONJIIN_NUMPAD.forEachIndexed { index, digits ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                digits.forEach { addCharKey(it, weight = GRID_W) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("!@#", KeyAction.SYMBOLS, weight = GRID_W / 2)
            addActionKey("가", KeyAction.KOREAN, weight = GRID_W / 2)
            addCharKey('0', weight = GRID_W)
            addActionKey("", KeyAction.SPACE, weight = GRID_W, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /**
     * 숫자 입력란용 키패드(삼성처럼 숫자 입력란에서 저절로 뜬다). 모양은 [DigitPad] 주석.
     *
     * 숫자 키는 **길게 눌러도 기호가 안 나온다** — 인증번호 칸에서 꾹 눌렀다가 '!' 가 들어가면
     * 안 된다. '가A' 로 온 자판으로 나갈 수 있다(입력란이 숫자라고 잘못 알려 주는 앱도 있다).
     */
    private fun renderDigits() {
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addDigitKey(digitPad.third) }
        )
        KeyboardLayout.CHEONJIIN_NUMPAD.forEachIndexed { index, digits ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                digits.forEach { addDigitKey(it) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addDigitKey(digitPad.bottom[0])
            addDigitKey('0')
            addDigitKey(digitPad.bottom[1])
            addActionKey("가A", KeyAction.KOREAN, weight = GRID_W)
        })
    }

    /** 숫자 키패드의 한 칸. 길게 누르기 없음. 빈 글자면 빈칸. */
    private fun LinearLayout.addDigitKey(c: Char) {
        if (c == ' ') return addSpacer(GRID_W)
        val view = keyView(c.toString(), keyBackground(isAction = false))
        addPadKey(view, GRID_W, charTouch(view, onPress = { listener?.onChar(c) }))
    }

    /**
     * 천지인 기호 판(삼성의 !@#). 6열 × 3줄 + 오른쪽 기능 열, 넷째 줄 123 가 1/3 ␣ ,
     */
    private fun renderCheonjiinSymbols() {
        val pages = KeyboardLayout.CHEONJIIN_SYMBOL_PAGES
        val page = pages[symbolPage % pages.size]
        val symbolW = (10f - GRID_W) / 6f
        val side = listOf<LinearLayout.() -> Unit>(
            { addActionKey("⌫", KeyAction.BACKSPACE, weight = GRID_W, repeatable = true) },
            { addActionKey("↵", KeyAction.ENTER, weight = GRID_W) },
            { addPunctuationKey(".,?!", KeyboardLayout.CHEONJIIN_PUNCTUATION) }
        )
        page.forEachIndexed { index, symbols ->
            rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
                symbols.forEach { addCharKey(it, weight = symbolW) }
                side[index](this)
            })
        }
        rowContainer.addView(buildRow(heightDp = CHEONJIIN_KEY_HEIGHT_DP) {
            addActionKey("123", KeyAction.NUMPAD, weight = symbolW)
            addActionKey("가", KeyAction.KOREAN, weight = symbolW)
            addActionKey("${symbolPage % pages.size + 1}/${pages.size}", KeyAction.SYMBOL_PAGE, weight = symbolW * 2)
            addActionKey("", KeyAction.SPACE, weight = symbolW * 2, letterKey = true)
            addCharKey(',', weight = GRID_W)
        })
    }

    /** 천지인 낱자 키. 오른쪽 위 숫자 힌트, 길게 누르면 그 숫자. */
    private fun LinearLayout.addCheonjiinKey(key: CheonjiinKey) {
        val view = hintedKeyView(key.label, key.hint, if (key.label.length > 1) 19f else 22f)
        addPadKey(view, GRID_W, charTouch(view, onPress = { listener?.onCheonjiinKey(key.key) }, alternate = key.hint))
    }

    /**
     * 큰 글자 가운데, 작은 힌트 오른쪽 위. 배경과 눌림 상태는 바깥 틀이 받는다.
     */
    private fun hintedKeyView(label: String, hint: Char, textSp: Float): View =
        android.widget.FrameLayout(context).apply {
            background = keyBackground(isAction = false)
            contentDescription = label
            addView(
                TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(theme.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                },
                android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            addView(
                TextView(context).apply {
                    text = hint.toString()
                    setTextColor(theme.hint)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                },
                android.widget.FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(4), dp(7), 0)
                }
            )
        }

    private fun LinearLayout.addPunctuationKey(label: String, options: String) {
        val view = keyView(label, keyBackground(isAction = true), textSp = 15f)
        addPadKey(view, GRID_W, charTouch(view, onPress = { listener?.onPunctuationCycle(options) }))
    }

    /**
     * 삼성식 기호 자판. 숫자·기호 3줄 + [1/2 · 특수 7키 · ⌫] 줄 + 바닥 기능 줄.
     */
    private fun renderSymbols() {
        val page = KeyboardLayout.SYMBOL_PAGES[symbolPage]
        rowContainer.addView(buildRow { page.rows[0].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow { page.rows[1].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow { page.rows[2].forEach { addCharKey(it) } })
        rowContainer.addView(buildRow {
            val label = "${symbolPage + 1}/${KeyboardLayout.SYMBOL_PAGES.size}"
            addActionKey(label, KeyAction.SYMBOL_PAGE, weight = 1.5f)
            page.extra.forEach { addCharKey(it, weight = 1f) }
            addSpacer((7 - page.extra.length).coerceAtLeast(0) * 1f)
            addActionKey("⌫", KeyAction.BACKSPACE, weight = 1.5f, repeatable = true)
        })
        rowContainer.addView(buildRow {
            addActionKey("가A", KeyAction.SYMBOLS, weight = 1.35f)
            addActionKey("한/영", KeyAction.LANGUAGE, weight = 1.05f)
            addCharKey(',', weight = 0.9f)
            addActionKey("", KeyAction.SPACE, weight = 4.35f, letterKey = true)
            addCharKey('.', weight = 0.9f)
            addActionKey("↵", KeyAction.ENTER, weight = 1.45f)
        })
    }

    private fun buildRow(heightDp: Int = KEY_HEIGHT_DP, build: LinearLayout.() -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isMotionEventSplittingEnabled = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(heightDp)).apply {
                topMargin = dp(ROW_GAP_DP)
            }
            weightSum = 10f
        }
        row.build()
        return row
    }

    private fun LinearLayout.addCharKey(c: Char, weight: Float = 1f) {
        val alternate = KeyboardLayout.longPressOf(c)
        val view = keyView(c.toString(), keyBackground(isAction = false))
        addPadKey(view, weight, charTouch(view, onPress = { listener?.onChar(c) }, alternate = alternate))
    }

    private fun LinearLayout.addActionKey(
        label: String,
        action: KeyAction,
        weight: Float,
        repeatable: Boolean = false,
        letterKey: Boolean = false
    ) {
        // 테마가 스페이스에 글자를 얹을 수 있다(바다사자 테마의 🦭).
        val shown = if (action == KeyAction.SPACE && label.isEmpty()) theme.spaceLabel else label
        val view = keyView(shown, keyBackground(isAction = !letterKey))
        if (action == KeyAction.SPACE && shown.isNotEmpty()) view.contentDescription = context.getString(R.string.key_space)
        val sound = when (action) {
            KeyAction.BACKSPACE -> KeySound.DELETE
            KeyAction.ENTER -> KeySound.ENTER
            else -> KeySound.LETTER
        }
        val touch =
            if (action == KeyAction.SPACE) spaceTouch(view)
            else charTouch(view, onPress = { listener?.onAction(action) }, repeatable = repeatable, sound = sound)
        addPadKey(view, weight, touch)
    }

    /**
     * 키를 줄에 넣고 판에 등록한다. **뷰에 리스너를 달지 않는다** — 손가락을 어느 키에
     * 줄지는 [KeyPad] 가 정한다.
     */
    private fun LinearLayout.addPadKey(view: View, weight: Float, touch: KeyTouch) {
        keySlots += PadSlot(view, touch)
        addView(view, keyParams(weight))
    }

    /**
     * 스페이스만 다르게 다룬다: **손을 뗄 때** 띄어쓰기가 들어가고, 꾹 누르면 커서 이동
     * 모드다. 누르는 순간에 넣어 버리면 꾹 눌렀을 때 이미 들어간 공백을 도로 빼야 하고,
     * 그 공백이 교정까지 한 번 돌린 뒤라 되돌리기가 지저분하다. 삼성 키보드도 스페이스는
     * 떼는 순간에 넣는다.
     */
    private fun spaceTouch(key: View): KeyTouch = object : KeyTouch {
        private var cursorMode = false
        private var consumed = false
        private var anchorX = 0f
        private val stepPx = dp(CURSOR_STEP_DP).toFloat()
        private val enterCursorMode = Runnable {
            cursorMode = true
            key.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            listener?.onCursorModeChanged(true)
        }

        override fun down(x: Float, y: Float) {
            flushHeldSpace()
            cursorMode = false
            consumed = false
            anchorX = x
            key.isPressed = true
            key.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            keySound?.invoke(KeySound.SPACE)
            repeatHandler.postDelayed(enterCursorMode, SPACE_HOLD_MS)
            // 다음 키가 눌리면 그 키보다 먼저 이 스페이스를 넣는다.
            heldSpaceFlush = {
                repeatHandler.removeCallbacks(enterCursorMode)
                if (!cursorMode && !consumed) {
                    consumed = true
                    listener?.onAction(KeyAction.SPACE)
                }
            }
        }

        override fun move(x: Float, y: Float, inside: Boolean) {
            if (!cursorMode) return
            val steps = ((x - anchorX) / stepPx).toInt()
            if (steps != 0) {
                listener?.onMoveCursor(steps)
                anchorX += steps * stepPx
            }
        }

        override fun up() {
            key.isPressed = false
            repeatHandler.removeCallbacks(enterCursorMode)
            heldSpaceFlush = null
            if (cursorMode) {
                listener?.onCursorModeChanged(false)
            } else if (!consumed) {
                listener?.onAction(KeyAction.SPACE)
            }
            consumed = true
            cursorMode = false
        }

        /**
         * 취소도 탭으로 친다. 스페이스는 화면 맨 아래라 제스처 영역에 걸려 취소되는
         * 일이 있는데, 그때 띄어쓰기가 통째로 사라졌다.
         */
        override fun cancel() = up()
    }

    private fun flushHeldSpace() {
        val flush = heldSpaceFlush ?: return
        heldSpaceFlush = null
        flush()
    }

    private fun LinearLayout.addSpacer(weight: Float) {
        if (weight <= 0f) return
        addView(View(context), keyParams(weight))
    }

    private fun keyParams(weight: Float) =
        LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
            marginStart = dp(KEY_GAP_DP)
            marginEnd = dp(KEY_GAP_DP)
        }

    // 파라미터 이름을 background 로 두면 apply 블록 안에서 TextView 자신의
    // background 프로퍼티가 먼저 잡힌다. 조용히 배경이 사라지므로 이름을 달리한다.
    private fun keyView(
        label: String,
        keyFace: StateListDrawable,
        textSp: Float = if (label.length > 1) 14f else 20f
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            isFocusable = false
            background = keyFace
            // 누름 시점에 처리하느라 onClick 을 쓰지 않는다. 화면 낭독기가 키를
            // 읽을 수 있도록 라벨은 남겨 둔다.
            contentDescription = label
        }

    /**
     * 도구 줄 동그라미.
     *
     * **[onPress] 가 마지막 인자여야 한다.** 부르는 쪽이 전부 후행 람다
     * (`toolbarButton("☺") { ... }`)를 쓰는데, 뒤에 다른 인자를 붙이면 그 람다가
     * 조용히 그쪽에 붙는다. 한 번 그렇게 깨뜨린 적이 있다.
     */
    private fun toolbarButton(
        label: String,
        onLongPress: (() -> Unit)? = null,
        onPress: () -> Unit
    ): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            // 아이콘이 동그라미를 거의 채우도록. 28dp 동그라미에 18sp 면 그 비율이다.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            includeFontPadding = false
            contentDescription = label
            if (onLongPress == null) {
                attachKeyTouch(this, onPress = onPress)
            } else {
                attachTouch(this, toolbarTouch(this, onPress, onLongPress))
            }
            styleToolbarButton(this)
        }

    /**
     * 길게 누르기가 달린 도구 줄 단추.
     *
     * 다른 단추들은 **누르는 순간** 실행한다 — 자판 키와 같아서 손끝이 빠르다. 여기서는
     * 그럴 수 없다. 누르자마자 실행해 버리면 길게 누른 사람에게 두 가지가 다 일어난다
     * (입력줄이 열리고, 곧이어 입력란이 통째로 번역된다). 그래서 짧게 누르면 손을 뗄 때,
     * 길게 누르면 그 자리에서 한 가지만 실행한다.
     */
    private fun toolbarTouch(key: View, onPress: () -> Unit, onLongPress: () -> Unit): KeyTouch =
        object : KeyTouch {
            private var fired = false
            private val longPress = Runnable {
                fired = true
                key.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                onLongPress()
            }

            override fun down(x: Float, y: Float) {
                flushHeldSpace()
                fired = false
                key.isPressed = true
                key.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                repeatHandler.postDelayed(longPress, LONG_PRESS_MS)
            }

            override fun move(x: Float, y: Float, inside: Boolean) {
                if (!inside) cancel()
            }

            override fun up() {
                key.isPressed = false
                repeatHandler.removeCallbacks(longPress)
                if (!fired) onPress()
                fired = false
            }

            override fun cancel() {
                key.isPressed = false
                repeatHandler.removeCallbacks(longPress)
                fired = true
            }
        }

    /** [attachKeyTouch] 와 같지만 손가락 처리를 밖에서 받는다. */
    private fun attachTouch(view: View, touch: KeyTouch) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touch.down(event.x, event.y)
                MotionEvent.ACTION_MOVE -> touch.move(event.x, event.y, insideView(target, event))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touch.up()
                MotionEvent.ACTION_CANCEL -> touch.cancel()
            }
            true
        }
    }

    private fun toolbarParams() = LayoutParams(dp(TOOLBAR_BUTTON_DP), dp(TOOLBAR_BUTTON_DP))

    /**
     * 키 하나를 손가락에 붙인다.
     *
     * **누르는 순간(down)에 입력한다.** onClick 은 손을 뗄 때까지 기다리고 이동 거리까지
     * 따져서, 빨리 치면 눌린 게 씹힌 것처럼 느껴진다. 시중 키보드가 전부 누름 시점에
     * 반응하는 이유다.
     *
     * [alternate] 가 있으면 **꾹 누르는 동안 미리보기를 띄우고, 손을 떼는 순간** 그
     * 글자로 바꾼다. 예전에는 타이머가 울리는 즉시 바꿔 버려서, 바뀐 줄 모르고 계속
     * 누르고 있다 손을 떼면 이미 딴 글자가 들어가 있었다.
     */
    private fun charTouch(
        key: View,
        onPress: () -> Unit,
        alternate: Char? = null,
        repeatable: Boolean = false,
        /** null 이면 소리를 안 낸다 — 자판 밖 도구 줄 단추가 그렇다. */
        sound: KeySound? = KeySound.LETTER,
        /** [repeatable] 일 때 꾹 누르는 동안 되풀이할 것. 기본은 ⌫ 반복이다. */
        repeat: Runnable = repeatBackspace
    ): KeyTouch = object : KeyTouch {
        // **이 상태는 키마다 따로 있어야 한다.** 빠르게 치면 앞 손가락이 떨어지기 전에
        // 다음 손가락이 닿아서 두 키가 동시에 눌린 상태가 된다. 상태를 하나로 공유하면
        // 나중 키를 떼는 순간 앞 키의 대체 글자가 들어가고, 앞 키를 떼면 아무 일도
        // 일어나지 않는다 — 엉뚱한 글자가 들어가거나 씹힌 것처럼 보인다.
        private var previewing = false
        private val showPreview = alternate?.let { alt ->
            Runnable {
                showAlternate(key, alt)
                previewing = true
            }
        }

        override fun down(x: Float, y: Float) {
            // 잡고 있던 스페이스가 있으면 이 키보다 먼저 들어가야 한다.
            flushHeldSpace()
            previewing = false
            key.isPressed = true
            key.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            sound?.let { keySound?.invoke(it) }
            onPress()
            showPreview?.let { repeatHandler.postDelayed(it, LONG_PRESS_MS) }
            if (repeatable) repeatHandler.postDelayed(repeat, REPEAT_DELAY_MS)
        }

        override fun move(x: Float, y: Float, inside: Boolean) {
            // 키 밖으로 끌고 나가면 대체 글자만 취소한다. 이미 들어간 글자는 그대로
            // 둔다 — 여기서 지우면 빠르게 칠 때 글자가 사라진다.
            if (previewing && !inside) {
                showPreview?.let { repeatHandler.removeCallbacks(it) }
                previewing = false
                dismissAlternate()
            }
        }

        override fun up() {
            key.isPressed = false
            showPreview?.let { repeatHandler.removeCallbacks(it) }
            repeatHandler.removeCallbacks(repeat)
            // 미리보기를 띄운 것이 **이 키** 일 때만 갈아 끼운다.
            if (previewing && alternate != null) listener?.onLongPressChar(alternate)
            if (previewing) dismissAlternate()
            previewing = false
        }

        override fun cancel() {
            key.isPressed = false
            showPreview?.let { repeatHandler.removeCallbacks(it) }
            repeatHandler.removeCallbacks(repeat)
            if (previewing) dismissAlternate()
            previewing = false
        }
    }

    /**
     * **넘겨 보는 목록 안의 칸**(이모티콘, 클립보드 카드)을 손가락에 붙인다.
     *
     * [attachKeyTouch] 를 쓰면 안 된다. 그쪽은 [charTouch] 라 **손가락이 닿는 순간**
     * 입력하는데, 목록에서는 그게 곧 버그다 — 목록을 넘기려고 손을 대는 순간 손 닿은
     * 칸이 입력돼 버린다. ScrollView 가 스크롤을 알아채고 취소를 보내 주기는 하지만
     * 그때는 이미 들어간 뒤다.
     *
     * 그래서 여기서는 **손을 뗄 때** 입력하고, 그 전에 손가락이 터치 슬롭을 넘게
     * 움직였으면 넘기려던 것으로 보고 포기한다. 목록은 자판처럼 빨리 칠 일이 없으니
     * 손 뗄 때 입력해도 굼뜨게 느껴지지 않는다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachPanelTouch(view: View, onPress: () -> Unit) =
        attachTouch(view, panelTouch(view, onPress))

    private fun panelTouch(key: View, onPress: () -> Unit): KeyTouch =
        object : KeyTouch {
            // 안드로이드가 "누른 것" 과 "끈 것" 을 가르는 그 값을 그대로 쓴다.
            // insideView 의 여유(슬롭 3배)와는 목적이 다르므로 곱하지 않는다.
            private val slop = ViewConfiguration.get(key.context).scaledTouchSlop
            private var startX = 0f
            private var startY = 0f
            private var cancelled = false

            override fun down(x: Float, y: Float) {
                flushHeldSpace()
                cancelled = false
                startX = x
                startY = y
                key.isPressed = true
            }

            override fun move(x: Float, y: Float, inside: Boolean) {
                if (cancelled) return
                if (!inside || abs(x - startX) > slop || abs(y - startY) > slop) cancel()
            }

            override fun up() {
                key.isPressed = false
                if (!cancelled) {
                    // 떨림은 자판과 같게 준다. 다만 **정말 입력할 때만** — 넘기는 동안
                    // 손 닿는 칸마다 울리면 목록이 시끄러워진다.
                    key.performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                    )
                    onPress()
                }
                cancelled = false
            }

            override fun cancel() {
                key.isPressed = false
                cancelled = true
            }
        }

    /**
     * 자판 밖의 키(도구 줄)는 뷰마다 리스너를 단다. 이쪽은 키가 드문드문 있어서
     * "가까운 것을 누른 것으로 친다" 가 오히려 해롭다.
     *
     * **넘겨 보는 목록에는 쓰지 마라 — [attachPanelTouch] 가 따로 있다.**
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachKeyTouch(view: View, onPress: () -> Unit) {
        val touch = charTouch(view, onPress, sound = null)
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touch.down(event.x, event.y)
                MotionEvent.ACTION_MOVE -> touch.move(event.x, event.y, insideView(target, event))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touch.up()
                MotionEvent.ACTION_CANCEL -> touch.cancel()
            }
            true
        }
    }

    /** [attachKeyTouch] 와 같지만 꾹 누르면 [repeat] 가 되풀이된다(⌫ 처럼). */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachRepeatingKeyTouch(view: View, repeat: Runnable, sound: KeySound?, onPress: () -> Unit) {
        val touch = charTouch(view, onPress, repeatable = true, sound = sound, repeat = repeat)
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> touch.down(event.x, event.y)
                MotionEvent.ACTION_MOVE -> touch.move(event.x, event.y, insideView(target, event))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touch.up()
                MotionEvent.ACTION_CANCEL -> touch.cancel()
            }
            true
        }
    }

    /**
     * 손가락이 아직 이 뷰 위에 있는가.
     *
     * **여유(slop)를 둔다.** 예전에는 뷰 경계를 딱 맞게 봤는데, 그러면 길게 누르는 동안
     * 손가락이 1픽셀만 흔들려도 밖으로 나간 것이 되어 취소된다. 도구 줄 동그라미는
     * 28dp 라 더 그렇다 — 실기기에서 "꾹 눌러도 AI 가 안 된다" 로 나타났다.
     * 사람 손가락은 가만히 있어도 떨린다. 안드로이드가 터치 슬롭을 두는 이유다.
     */
    private fun insideView(view: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop * TOUCH_SLOP_FACTOR
        val bounds = Rect(-slop, -slop, view.width + slop, view.height + slop)
        return bounds.contains(event.x.toInt(), event.y.toInt())
    }

    // --- 자판 바닥 전체가 키다 ---------------------------------------------------

    /**
     * 자판 바닥.
     *
     * **키와 키 사이의 여백, 화면 가장자리까지 전부 어느 한 키에 속한다.** 삼성
     * 키보드가 그렇다 — ㅅ과 ㅎ 사이를 누르면 가까운 쪽이 눌리고, 줄 맨 끝 키는
     * 화면 가장자리까지가 제 영역이다.
     *
     * 키 뷰마다 리스너를 달면 그 여백은 아무 키도 아니어서 입력이 조용히 사라진다.
     * 빠르게 칠수록 손가락이 키 한가운데에 정확히 떨어지지 않으니 자주 겪는다 —
     * 220타로 칠 때 "키가 씹힌다" 던 것의 정체다. 그래서 손가락을 어느 키에 줄지는
     * 뷰에 맡기지 않고 판이 직접 정한다.
     */
    private inner class KeyPad(context: Context) : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            routeTouch(event)
            return true
        }
    }

    /** 자판 위의 키 한 자리. */
    private class PadSlot(val view: View, val touch: KeyTouch) {
        /** 판 기준 좌표. 누를 때마다 다시 잰다 — 줄 높이나 자판이 바뀔 수 있다. */
        val bounds = Rect()
    }

    /**
     * 키 하나가 손가락에 반응하는 법. 판이 직접 불러 주거나([KeyPad]), 자판 밖에서는
     * 뷰의 터치 리스너가 불러 준다([attachKeyTouch]).
     */
    private interface KeyTouch {
        /** 좌표는 키 왼쪽 위 기준. */
        fun down(x: Float, y: Float)

        /** [inside] 는 손가락이 아직 이 키의 영역에 있는지. */
        fun move(x: Float, y: Float, inside: Boolean)
        fun up()
        fun cancel()
    }

    /**
     * 손가락을 키에 나눠 준다.
     *
     * 손가락(pointer)마다 처음 닿은 키를 끝까지 잡고 있는다. 여러 손가락이 동시에
     * 눌려도 서로 섞이지 않고, 한 키 위에 손가락 두 개가 겹쳐도 둘 다 입력된다.
     */
    private fun routeTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 첫 손가락이라면 남아 있는 것은 전부 놓친 것이다(화면이 바뀌는 사이
                // 손을 떼면 UP 이 안 온다). 그대로 두면 ⌫ 반복이 멎지 않는다.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelActiveSlots()
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val slot = slotAt(x, y) ?: return
                activeSlots.put(event.getPointerId(index), slot)
                slot.touch.down(x - slot.bounds.left, y - slot.bounds.top)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val slot = activeSlots.get(event.getPointerId(index)) ?: continue
                    val x = event.getX(index)
                    val y = event.getY(index)
                    // 끌고 나가도 키는 바뀌지 않는다. 이미 들어간 글자가 있으니
                    // 넘겨줄 수 없다 — 대체 글자 미리보기만 취소된다.
                    val inside = slotAt(x, y) === slot
                    slot.touch.move(x - slot.bounds.left, y - slot.bounds.top, inside)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointer = event.getPointerId(event.actionIndex)
                activeSlots.get(pointer)?.touch?.up()
                activeSlots.remove(pointer)
            }

            MotionEvent.ACTION_CANCEL -> cancelActiveSlots()
        }
    }

    private fun cancelActiveSlots() {
        for (i in 0 until activeSlots.size()) activeSlots.valueAt(i).touch.cancel()
        activeSlots.clear()
    }

    /**
     * 이 점을 품은 키. 없으면 **가장 가까운** 키.
     *
     * 여백은 양쪽 키의 한가운데를 경계로 갈린다. 키 사이 거리가 3dp 안팎이라 이
     * 한 줄이 곧 "키보드 전체가 키" 다.
     */
    private fun slotAt(x: Float, y: Float): PadSlot? {
        var nearest: PadSlot? = null
        var best = Float.MAX_VALUE
        for (slot in keySlots) {
            // 아직 자리를 못 잡은 키(폭 0)는 거리가 엉뚱하게 나온다.
            if (slot.view.width == 0) continue
            fillBounds(slot)
            val dx = gapTo(x, slot.bounds.left.toFloat(), slot.bounds.right.toFloat())
            val dy = gapTo(y, slot.bounds.top.toFloat(), slot.bounds.bottom.toFloat())
            if (dx == 0f && dy == 0f) return slot
            val distance = dx * dx + dy * dy
            if (distance < best) {
                best = distance
                nearest = slot
            }
        }
        return nearest
    }

    /** 구간 [min]~[max] 에서 [value] 가 벗어난 거리. 안에 있으면 0. */
    private fun gapTo(value: Float, min: Float, max: Float): Float = when {
        value < min -> min - value
        value > max -> value - max
        else -> 0f
    }

    /** 키의 자리를 판 기준 좌표로 잰다. 키는 줄 안에 있고, 줄은 판 안에 있다. */
    private fun fillBounds(slot: PadSlot) {
        var view: View? = slot.view
        var left = 0
        var top = 0
        while (view != null && view !== rowContainer) {
            left += view.left
            top += view.top
            view = view.parent as? View
        }
        slot.bounds.set(left, top, left + slot.view.width, top + slot.view.height)
    }

    /**
     * 키 바로 위에 큼직하게 띄운다. 손가락에 가려지면 띄운 의미가 없다.
     *
     * ## PopupWindow 를 쓰지 않는 이유
     *
     * 미리보기를 별도 윈도우로 띄우면 입력기 윈도우의 포커스가 흔들리고, 그 바람에
     * `onStartInput` 이 다시 불려 `session.reset()` 이 조합 상태를 날린다. 그러면
     * 쌍자음 다음에 친 모음이 새 글자로 시작해 `ㄲ우` 가 된다 — 실기기에서 "쌍자음이
     * 모음이랑 안 합쳐진다" 로 나타났다. 그래서 윈도우를 만들지 않고 키보드 자신의
     * 오버레이에 그린다. 입력 상태를 건드릴 일이 아예 없다.
     */
    private fun showAlternate(anchor: View, alternate: Char) {
        dismissAlternate()
        val width = anchor.width.coerceAtLeast(dp(44))
        val height = (anchor.height * 1.25f).toInt()

        val view = TextView(context).apply {
            text = alternate.toString()
            gravity = Gravity.CENTER
            setTextColor(theme.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            background = roundRect(theme.accent)
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }

        // 키의 위치를 키보드 기준 좌표로 옮긴다.
        val keyAt = IntArray(2).also { anchor.getLocationInWindow(it) }
        val selfAt = IntArray(2).also { getLocationInWindow(it) }
        val left = keyAt[0] - selfAt[0] - (width - anchor.width) / 2
        // 오버레이는 키보드 밖으로 못 나간다. 맨 윗줄이면 잘리지 않게 끌어내린다.
        val top = (keyAt[1] - selfAt[1] - height - dp(4)).coerceAtLeast(0)
        view.layout(left, top, left + width, top + height)

        overlay.add(view)
        bubble = view
        pendingAlternate = alternate
        anchor.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    private fun dismissAlternate() {
        bubble?.let { overlay.remove(it) }
        bubble = null
        pendingAlternate = null
    }

    private fun keyBackground(isAction: Boolean): StateListDrawable {
        val normalColor = keyFill(if (isAction) theme.actionKey else theme.key)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundRect(theme.pressed))
            addState(intArrayOf(), keyFace(normalColor))
        }
    }

    /**
     * 키 면. 아래에 1dp 그림자를 깔아 살짝 떠 보이게 한다 — 삼성 키보드의 키가 평면
     * 사각형과 다르게 보이는 이유가 이 한 줄이다. 사진 위에서는 그림자도 같이 비친다.
     */
    private fun keyFace(fill: Int): Drawable {
        val shadow = roundRect(keyFill(theme.keyShadow))
        val face = roundRect(fill)
        return LayerDrawable(arrayOf(shadow, face)).apply {
            setLayerInset(0, 0, dp(1), 0, 0)
            setLayerInset(1, 0, 0, 0, dp(1))
        }
    }

    private fun roundRect(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(fill)
    }

    private fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private companion object {
        /**
         * 도구 줄. 삼성 실측은 동그라미 ≈33dp, 줄 48dp 였는데 실기기 화면에서 그마저 커
         * 보여, 사용자가 그어 준 선(위쪽 약 16dp)만큼 더 줄였다. 줄 34dp 에 동그라미 28dp.
         */
        const val TOOLBAR_HEIGHT_DP = 34

        /**
         * 접었을 때 남는 띠의 높이.
         *
         * 0 으로 만들어 완전히 없애면 다시 펼 수가 없다. 22dp 는 세로로 좁지만 접힌
         * 손잡이가 줄 **전체 너비**로 넓어지므로 겨냥할 것이 없다 — 위쪽 아무 데나 누르면
         * 펴진다. 그래도 12dp 를 벌어 준다.
         */
        const val TOOLBAR_COLLAPSED_DP = 22

        /** 펴져 있을 때 손잡이가 차지하는 너비. 동그라미들보다 좁아서 도구로 안 보인다. */
        const val COLLAPSE_BUTTON_DP = 22

        /** 번역 패널의 세 줄. 한국어 줄은 두 줄까지 보인다(가로 화면은 한 줄). */
        const val TRANSLATE_HEADER_DP = 32
        const val TRANSLATE_CHIP_DP = 26
        const val TRANSLATE_SOURCE_MAX_ROWS = 2
        const val TRANSLATE_NOTE_DP = 22
        const val TRANSLATE_ERASE_WIDTH_DP = 40

        /** 번역 패널 머리 줄이 이보다 좁으면 글자와 여백을 줄인다(dp). */
        const val TRANSLATE_COMPACT_BELOW_DP = 400
        const val TOOLBAR_BUTTON_DP = 28

        /**
         * 도구 줄의 글자 단추 크기. 28dp 동그라미에 'LIVE' 네 글자가 들어가는 한계다.
         * 더 키우면 넘치고, 옆의 '번역' 과도 크기가 안 맞는다.
         */
        const val TOOLBAR_LABEL_SP = 10f

        /**
         * 그림 아이콘(24 칸 판)이 동그라미 안에서 차지하는 크기. 삼성 키보드 캡처에서 아이콘 지름이
         * 동그라미의 절반쯤이었다 — 28dp 동그라미에 18dp 판이면 그 비율이 된다.
         */
        const val TOOLBAR_ICON_DP = 18
        const val FLASH_MS = 1600L
        // 삼성 키보드 실측에 맞춘 값. 키는 조금 높고, 틈은 조금 넓다.
        const val KEY_HEIGHT_DP = 48
        const val ROW_GAP_DP = 5
        const val KEY_GAP_DP = 3
        /** 스페이스를 이만큼 누르고 있으면 커서 이동 모드. 쌍자음보다 조금 길게. */
        const val SPACE_HOLD_MS = 380L
        /** 커서 이동 모드에서 이만큼 밀 때마다 한 글자. */
        const val CURSOR_STEP_DP = 18
        /** 설정의 "키 투명도"(0~100)를 불투명도로. 100 이어도 완전히 사라지지는 않게 둔다. */
        fun alphaFor(transparency: Int): Float = (1f - transparency / 100f).coerceIn(0.08f, 1f)
        const val CHEONJIIN_KEY_HEIGHT_DP = 58
        /** 천지인·숫자 판의 한 칸 너비(weightSum 10 기준). 넷이면 딱 맞는다. */
        const val GRID_W = 2.5f
        const val EMOJI_COLUMNS = 8
        const val EMOJI_CELL_DP = 44
        const val CLIPBOARD_CARD_HEIGHT_DP = 76
        const val CLIPBOARD_HEIGHT_DP = 244
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        /**
         * 이만큼 누르고 있어야 대체 글자가 뜬다.
         *
         * 450ms 로 늘렸다가 "쌍자음 낼 때 너무 느리다" 는 말을 듣고 되돌렸다. 짧으면
         * 의도치 않게 쌍자음이 들어갈 수 있지만, 쌍자음을 자주 쓰는 쪽이 체감이 크다.
         * 대신 키를 떼는 순간에 확정하므로, 미리보기가 뜬 걸 보고 손가락을 옆으로
         * 빼면 취소된다.
         */
        /**
         * 손가락 떨림을 얼마나 봐줄 것인가. 안드로이드 기본 슬롭의 배수다.
         *
         * 길게 누르는 동안은 손이 더 흔들린다 — 누르고 기다리는 동작이라 그렇다.
         * 기본값 그대로면 도구 줄 동그라미에서 길게 누르기가 자주 취소된다.
         */
        const val TOUCH_SLOP_FACTOR = 3

        const val LONG_PRESS_MS = 300L
    }
}
