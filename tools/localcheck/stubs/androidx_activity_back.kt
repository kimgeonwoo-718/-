package androidx.activity

abstract class OnBackPressedCallback(enabled: Boolean) {
    var isEnabled: Boolean = enabled
    abstract fun handleOnBackPressed()
}

class OnBackPressedDispatcher {
    fun addCallback(owner: Any, onBackPressedCallback: OnBackPressedCallback) {}
    fun onBackPressed() {}
}
