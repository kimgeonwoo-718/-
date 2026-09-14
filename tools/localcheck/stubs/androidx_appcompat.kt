package androidx.appcompat.app

open class AppCompatActivity : android.app.Activity() {
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): androidx.activity.result.ActivityResultLauncher<I> =
        androidx.activity.result.ActivityResultLauncher()
}

object AppCompatDelegate {
    const val MODE_NIGHT_NO = 1
    const val MODE_NIGHT_YES = 2
    const val MODE_NIGHT_FOLLOW_SYSTEM = -1
    @JvmStatic fun setDefaultNightMode(mode: Int) {}
}
