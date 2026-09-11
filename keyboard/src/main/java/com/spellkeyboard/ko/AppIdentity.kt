package com.spellkeyboard.ko

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * 구글 API 에 "이 요청은 이 앱에서 왔다" 고 알리는 헤더.
 *
 * 구글 콘솔에서 API 키에 안드로이드 앱 제한(패키지명 + 서명 SHA-1)을 걸면, 서버는
 * 이 두 헤더를 보고 허용된 앱인지 가린다. 공식 클라이언트 라이브러리는 이걸 알아서
 * 붙이는데 우리는 REST 를 직접 쓰니 손으로 붙인다.
 *
 * 헤더는 위조할 수 있다 — 서명 값은 APK 에서 읽을 수 있으니까. 그래도 "키 문자열
 * 하나 복사해서 아무 데서나 쓴다" 는 가장 흔한 남용은 막힌다. 구글도 이 방식을
 * 안드로이드 앱에 키를 넣는 정석으로 안내한다.
 */
object AppIdentity {

    fun headers(context: Context): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["X-Android-Package"] = context.packageName
        signingSha1(context)?.let { headers["X-Android-Cert"] = it }
        return headers
    }

    /** 서명 인증서의 SHA-1. 구글 콘솔에 적는 값과 같은 형식(대문자 16진수, 구분자 없음). */
    fun signingSha1(context: Context): String? {
        val signature = firstSignature(context) ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun firstSignature(context: Context): Signature? = runCatching {
        val manager = context.packageManager
        val name = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(name, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
    }.getOrNull()
}
