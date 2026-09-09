package com.spellkeyboard.ko

import android.content.Context

/** 키보드 설정. 지금은 자동 교정 on/off 하나뿐이다. */
object Prefs {

    private const val FILE = "spell_keyboard"
    private const val KEY_AUTO_CORRECT = "auto_correct"

    fun autoCorrectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CORRECT, true)

    fun setAutoCorrectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CORRECT, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
