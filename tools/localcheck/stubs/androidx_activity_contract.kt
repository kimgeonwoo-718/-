package androidx.activity.result.contract

import android.net.Uri

interface ActivityResultContract<I, O>

object ActivityResultContracts {
    class PickVisualMedia : ActivityResultContract<Any, Uri?> {
        companion object { val ImageOnly: Any = Any() }
    }
}
