package com.spellkeyboard.ko

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * 사용자가 고른 밝기.
 *
 * "시스템 따라가기" 는 뺐다 — 폰 다크 모드와 키보드 밝기를 따로 두고 싶다는 요청이었고,
 * 선택지가 셋이면 어느 게 지금 적용된 건지 한눈에 안 들어온다.
 */
enum class ThemeMode(val premium: Boolean = false) {
    LIGHT,
    DARK,
    /** 구독자 전용. 바다 빛깔 자판에 바다 그림 바탕, 스페이스에 바다사자. */
    SEA_LION(premium = true)
}

/**
 * 키보드 팔레트.
 *
 * 색을 리소스 ID 로 곳곳에서 직접 꺼내 쓰면 어두운 테마를 붙일 때 스무 군데를 고쳐야
 * 한다. 한 객체로 모아 두고, 테마가 바뀌면 객체를 통째로 바꾼다.
 */
data class KeyboardTheme(
    val dark: Boolean,
    val background: Int,
    val key: Int,
    val actionKey: Int,
    val pressed: Int,
    val text: Int,
    val hint: Int,
    val status: Int,
    val toolbarButton: Int,
    val accent: Int,
    val onAccent: Int,
    val panelItem: Int,
    /** 키 아래 얇은 그림자. 삼성 키보드의 키가 살짝 떠 보이는 이유다. */
    val keyShadow: Int,
    /** 바탕 그림(drawable). 0 이면 [background] 색만 칠한다. 사용자가 고른 사진이 있으면 사진이 먼저다. */
    val backgroundArt: Int = 0,
    /** 스페이스 키에 쓸 글자. 보통은 비워 둔다. */
    val spaceLabel: String = ""
) {
    companion object {
        /** 설정과 시스템 밝기를 합쳐 지금 써야 할 팔레트. */
        fun current(context: Context): KeyboardTheme = when (Prefs.effectiveTheme(context)) {
            ThemeMode.LIGHT -> light(context)
            ThemeMode.DARK -> dark(context)
            ThemeMode.SEA_LION -> seaLion(context)
        }

        private fun light(context: Context) = KeyboardTheme(
            dark = false,
            background = color(context, R.color.keyboard_background),
            key = color(context, R.color.key_background),
            actionKey = color(context, R.color.key_action_background),
            pressed = color(context, R.color.key_pressed_background),
            text = color(context, R.color.key_text),
            hint = color(context, R.color.key_hint_text),
            status = color(context, R.color.status_text),
            toolbarButton = color(context, R.color.toolbar_button),
            accent = color(context, R.color.popup_background),
            onAccent = color(context, R.color.popup_text),
            panelItem = color(context, R.color.panel_item_background),
            keyShadow = color(context, R.color.key_shadow)
        )

        private fun dark(context: Context) = KeyboardTheme(
            dark = true,
            background = color(context, R.color.keyboard_background_dark),
            key = color(context, R.color.key_background_dark),
            actionKey = color(context, R.color.key_action_background_dark),
            pressed = color(context, R.color.key_pressed_background_dark),
            text = color(context, R.color.key_text_dark),
            hint = color(context, R.color.key_hint_text_dark),
            status = color(context, R.color.status_text_dark),
            toolbarButton = color(context, R.color.toolbar_button_dark),
            accent = color(context, R.color.popup_background),
            onAccent = color(context, R.color.popup_text),
            panelItem = color(context, R.color.panel_item_background_dark),
            keyShadow = color(context, R.color.key_shadow_dark)
        )

        /**
         * 바다사자. 밝은 바다 바탕에 모래빛 기능 키, 강조는 비치볼 주황. 키가 반투명이라
         * 바탕 그림(파도·바다사자)이 키 사이로 비친다.
         */
        private fun seaLion(context: Context) = KeyboardTheme(
            dark = false,
            background = color(context, R.color.sealion_background),
            key = color(context, R.color.sealion_key),
            actionKey = color(context, R.color.sealion_action_key),
            pressed = color(context, R.color.sealion_pressed),
            text = color(context, R.color.sealion_text),
            hint = color(context, R.color.sealion_hint),
            status = color(context, R.color.sealion_text),
            toolbarButton = color(context, R.color.sealion_key),
            accent = color(context, R.color.sealion_accent),
            onAccent = color(context, R.color.sealion_on_accent),
            panelItem = color(context, R.color.sealion_key),
            keyShadow = color(context, R.color.sealion_shadow),
            backgroundArt = R.drawable.sealion_background,
            spaceLabel = "🦭"
        )

        private fun color(context: Context, id: Int) = ContextCompat.getColor(context, id)
    }
}
