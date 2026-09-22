package androidx.credentials

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import androidx.credentials.exceptions.GetCredentialException
import java.util.concurrent.Executor

// 구글 로그인(Credential Manager)의 겉모습만. 실제 동작은 기기에서만 돈다.

abstract class Credential(val type: String, val data: Bundle)

open class CustomCredential(type: String, data: Bundle) : Credential(type, data)

abstract class CredentialOption

class GetCredentialRequest private constructor() {
    class Builder {
        fun addCredentialOption(option: CredentialOption): Builder = this
        fun build(): GetCredentialRequest = GetCredentialRequest()
    }
}

class GetCredentialResponse {
    val credential: Credential get() = throw UnsupportedOperationException()
}

interface CredentialManagerCallback<R, E> {
    fun onResult(result: R)
    fun onError(error: E)
}

class CredentialManager {
    fun getCredentialAsync(
        context: Context,
        request: GetCredentialRequest,
        cancellationSignal: CancellationSignal?,
        executor: Executor,
        callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>,
    ) = Unit

    companion object {
        @JvmStatic fun create(context: Context): CredentialManager = CredentialManager()
    }
}
