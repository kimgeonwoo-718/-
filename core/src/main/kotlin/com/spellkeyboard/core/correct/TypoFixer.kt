package com.spellkeyboard.core.correct

/**
 * 오타를 **맞춤법만** 고친다. 띄어쓰기는 손대지 않는다.
 *
 * 규칙 표([SpellingRules])는 적어 둔 것만 잡는다. 이건 형태소 분석기가 "이 글자를 저 글자로
 * 바꾸면 말이 되는가" 를 따져서 **적어 두지 않은 오타도** 잡는다. 두 가지가 겹치지 않는다.
 *
 * 코어는 안드로이드도 Kiwi 도 모르므로 자리만 둔다. 앱이 [CorrectionEngine.typoFixer] 에 끼운다.
 * 없으면 규칙 표만으로 간다.
 */
fun interface TypoFixer {
    /** 고칠 것이 있으면 고친 글, 없으면 null. **공백 수는 그대로여야 한다.** */
    fun fix(text: String): String?
}
