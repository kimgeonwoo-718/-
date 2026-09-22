package androidx.credentials.exceptions

open class GetCredentialException(val type: String = "", message: String? = null) : Exception(message)

class GetCredentialCancellationException : GetCredentialException()

class NoCredentialException : GetCredentialException()
