package com.spellkeyboard.ko

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 구글 로그인으로 이 기기를 계정에 붙인다.
 *
 * 왜 필요한가: 구독은 **구글 플레이 구매 토큰** 하나로 돌아가는데, 윈도우에는 스토어가
 * 없고 아이폰은 다른 스토어다. 그래서 "누가 어떤 구매를 샀나" 를 서버에 적어 두고,
 * 스토어가 없는 기기는 로그인해서 그 구매를 빌려 쓴다. 자세한 것은 `docs/ACCOUNTS.md`.
 *
 * **여기서 구독 여부를 판단하지 않는다.** 폰이 하는 일은 구글이 서명한 ID 토큰을 받아
 * 서버에 넘기고, 돌려받은 기기 토큰을 [Prefs] 에 넣어 두는 것뿐이다. 살아 있는 구독인지는
 * 서버가 Play 에 물어본다 — 요청마다 다시.
 *
 * **이름·사진은 이 폰에만 둔다.** 더보기 화면에 "누구로 로그인했나" 를 보여 주려고 구글이
 * 준 이름과 프로필 사진을 폰에 저장하지만, 우리 서버로는 보내지 않는다 — 서버는 ID 토큰에서
 * 구글 계정 번호(`sub`)만 꺼내 해시로 바꿔 쓴다. 이메일은 폰에서도 안 읽는다.
 * 로그아웃·탈퇴하면 폰에서도 지운다.
 */
class AccountManager(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** 로그인은 어쩌다 한 번이라, 누를 때 만들고 끝나면 접는다. 상주시킬 값이 아니다. */
    private val workers = Executors.newSingleThreadExecutor()

    /** 로그인 결과. 사람에게 보여줄 한 줄과, 지금 상태. */
    data class Result(val signedIn: Boolean, val subscriber: Boolean, val message: String?)

    /**
     * 구글 계정을 고르게 한 뒤 서버에 붙인다.
     *
     * 구매 토큰이 이 폰에 있으면 같이 보낸다 — 그래야 **이 폰이 산 구독이 계정에 붙어**
     * 윈도우·아이폰이 그것을 찾아 쓸 수 있다. 없으면 로그인만 된다(다른 기기가 산 구독을
     * 이 폰이 빌려 쓰는 쪽).
     *
     * 계정 고르는 창이 Activity 위에 떠야 해서 [activity] 를 받는다.
     */
    fun signIn(activity: Activity, onDone: (Result) -> Unit) {
        val clientId = Prefs.googleClientId()
        if (clientId.isEmpty()) {
            onDone(Result(false, false, app.getString(R.string.account_error_not_configured)))
            return
        }

        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        CredentialManager.create(activity).getCredentialAsync(
            activity,
            request,
            null,
            workers,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val google = googleOf(result)
                    if (google == null) {
                        deliver(onDone, Result(false, false, app.getString(R.string.account_error_no_token)))
                        return
                    }
                    // 이미 작업 스레드다(executor 가 여기로 부른다). 그대로 서버에 간다.
                    val attached = attach(google.idToken)
                    if (attached.signedIn) {
                        // 서버가 받아 준 뒤에만 남긴다. 로그인이 실패했는데 이름이 떠 있으면 헷갈린다.
                        Prefs.setProfileName(app, google.displayName?.trim().orEmpty())
                        ProfilePhoto.download(app, google.profilePictureUri?.toString())
                    }
                    deliver(onDone, attached)
                }

                override fun onError(error: GetCredentialException) {
                    deliver(onDone, Result(false, false, explain(error)))
                }
            },
        )
    }

    /**
     * 이 기기를 계정에서 뗀다.
     *
     * **서버가 뭐라 하든 폰에서는 지운다.** 로그아웃을 눌렀는데 망이 끊겼다고 로그인
     * 상태로 남아 있으면 사람이 다시 누를 길이 없다. 서버 쪽에 남은 기기는 나중에
     * "내 기기 목록" 에서 뺀다.
     */
    fun signOut(onDone: (Result) -> Unit) {
        val device = Prefs.deviceToken(app)
        forget()
        if (device.isEmpty()) {
            onDone(Result(false, false, null))
            return
        }
        workers.execute {
            runCatching { post("/v1/account/signout", mapOf("X-Device-Token" to device), null) }
            deliver(onDone, Result(false, false, null))
        }
    }

    /**
     * 회원 탈퇴. 서버의 계정과, 그 계정에 붙은 **모든** 기기를 지운다.
     *
     * 로그아웃과 달리 **서버가 지웠다고 해야** 폰에서도 지운다. 망이 끊겨 서버에 계정이
     * 남았는데 폰에서 "탈퇴했어요" 가 뜨면 거짓말이 된다. 실패하면 로그인 상태 그대로 두고
     * 왜 안 됐는지 알린다. 서버가 "그런 기기 없다(404)" 고 하면 이미 없는 것이니 지운다.
     *
     * 구독은 Play 에 있어서 여기서 해지되지 않는다. 그 말은 확인 창이 먼저 한다.
     * [Result.signedIn] 이 false 면 지워졌다는 뜻이다.
     */
    fun deleteAccount(onDone: (Result) -> Unit) {
        val device = Prefs.deviceToken(app)
        if (device.isEmpty()) {
            forget()
            onDone(Result(false, false, null))
            return
        }
        workers.execute {
            val result = try {
                val (code, text) = post("/v1/account/delete", mapOf("X-Device-Token" to device), null)
                if (code == 200 || code == 404) {
                    forget()
                    Result(false, false, null)
                } else {
                    Result(true, false, app.getString(R.string.account_error_server, reasonOf(text, code)))
                }
            } catch (error: Exception) {
                Result(true, false, app.getString(R.string.account_error_network))
            }
            deliver(onDone, result)
        }
    }

    fun destroy() {
        workers.shutdown()
    }

    /** 이 폰에 남은 로그인 흔적을 전부 지운다 — 기기 토큰, 이름, 사진. */
    private fun forget() {
        Prefs.setDeviceToken(app, "")
        Prefs.setProfileName(app, "")
        ProfilePhoto.clear(app)
    }

    // --- 서버 --------------------------------------------------------------------

    /** 서버에 ID 토큰을 주고 기기 토큰을 받아 온다. 작업 스레드에서 부른다. */
    private fun attach(idToken: String): Result {
        val purchase = Prefs.purchaseToken(app)
        val body = buildString {
            append("{\"idToken\":").append(quote(idToken))
            if (purchase.isNotEmpty()) append(",\"purchaseToken\":").append(quote(purchase))
            append(",\"label\":").append(quote(android.os.Build.MODEL ?: "Android"))
            append('}')
        }

        val (code, text) = try {
            post("/v1/account/signin", emptyMap(), body)
        } catch (error: Exception) {
            return Result(false, false, app.getString(R.string.account_error_network))
        }

        if (code != 200) {
            return Result(false, false, app.getString(R.string.account_error_server, reasonOf(text, code)))
        }

        val device = field(text, "deviceToken")
        if (device.isEmpty()) {
            return Result(false, false, app.getString(R.string.account_error_server, "deviceToken"))
        }
        Prefs.setDeviceToken(app, device)
        return Result(true, field(text, "plan") == "subscriber", null)
    }

    private fun post(path: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val connection = (URL(Prefs.serverUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = body != null
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            return code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * JSON 한 겹에서 문자열 값 하나를 꺼낸다.
     *
     * 파서를 들이지 않는 이유: 여기서 읽는 것은 **우리 서버가 만든 답** 셋뿐이고
     * (`deviceToken`·`plan`·`error`), 셋 다 16진수·소문자 코드라 이스케이프가 들어올 일이
     * 없다. 코어의 JSON 은 `internal` 이라 이 모듈에서 못 쓴다 — 그것 하나 때문에 코어의
     * 공개 면을 넓히지 않는다.
     */
    private fun field(text: String, name: String): String =
        Regex("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"").find(text)?.groupValues?.get(1).orEmpty()

    /** 문자열을 JSON 리터럴로. 제어문자는 버린다 — 넣어 봐야 깨진 JSON 이 된다. */
    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch < ' ' -> Unit
            else -> append(ch)
        }
        append('"')
    }

    private fun deliver(onDone: (Result) -> Unit, result: Result) {
        main.post { onDone(result) }
    }

    // --- 말로 풀기 ----------------------------------------------------------------

    /**
     * 서버가 준 오류 코드를 사람 말로.
     *
     * 모르는 코드는 그대로 보여 준다 — 문의가 들어왔을 때 짚을 것이 있어야 한다.
     */
    private fun reasonOf(text: String, code: Int): String {
        val error = field(text, "error").takeIf { it.isNotEmpty() }
        return when (error) {
            "too_many_devices" -> app.getString(R.string.account_error_too_many_devices)
            "google_not_configured", "google_unreachable" -> app.getString(R.string.account_error_google_down)
            "bad_id_token" -> app.getString(R.string.account_error_bad_token)
            else -> error ?: "HTTP $code"
        }
    }

    /**
     * 계정 고르는 창이 낸 오류.
     *
     * 취소는 오류가 아니다 — 사람이 닫은 것이라 아무 말도 안 띄운다. 계정이 하나도 없는
     * 폰이 흔해서(공기계, 새 폰) 그건 따로 짚어 준다.
     */
    private fun explain(error: GetCredentialException): String? = when (error) {
        is GetCredentialCancellationException -> null
        is NoCredentialException -> app.getString(R.string.account_error_no_google)
        else -> app.getString(R.string.account_error_server, error.type)
    }

    private fun googleOf(result: GetCredentialResponse): GoogleIdTokenCredential? {
        val credential = result.credential
        if (credential !is CustomCredential) return null
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null
        return runCatching { GoogleIdTokenCredential.createFrom(credential.data) }
            .getOrNull()
            ?.takeIf { it.idToken.isNotEmpty() }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
