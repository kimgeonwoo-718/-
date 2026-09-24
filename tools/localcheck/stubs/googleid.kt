package com.google.android.libraries.identity.googleid

import android.os.Bundle
import androidx.credentials.CredentialOption

class GetSignInWithGoogleOption private constructor() : CredentialOption() {
    class Builder(serverClientId: String) {
        fun build(): GetSignInWithGoogleOption = GetSignInWithGoogleOption()
    }
}

class GoogleIdTokenCredential {
    val idToken: String get() = ""
    val displayName: String? get() = null
    val profilePictureUri: android.net.Uri? get() = null

    companion object {
        const val TYPE_GOOGLE_ID_TOKEN_CREDENTIAL =
            "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"

        @JvmStatic fun createFrom(data: Bundle): GoogleIdTokenCredential = GoogleIdTokenCredential()
    }
}
