package com.spellkeyboard.ko.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/**
 * 이 기기가 기기 안 번역 모델을 돌릴 수 있는가.
 *
 * 못 돌리는 기기에서 모델을 3GB 받게 하면 안 되므로 **받기 전에** 본다. 어긋나면 이유를 말해 준다([Reason]).
 * 이유가 있어도 번역은 그대로 된다 — 지금까지처럼 ML Kit 이 한다.
 */
object LlmSupport {

    enum class Reason {
        OK,

        /** 네이티브 라이브러리가 arm64 용뿐이다. */
        NOT_ARM64,

        /** 안드로이드 10 (API 29) 미만. */
        OLD_ANDROID,

        /** 메모리 5.5GB 미만. 모델이 쓰는 2~3GB 를 감당하지 못한다. */
        LOW_MEMORY,

        /** CPU 가 dotprod·fp16 연산을 모른다(2017년 이전 칩). 네이티브 코드가 그 명령을 쓰므로 돌면 비정상 종료한다. */
        OLD_CPU
    }

    /** 총 메모리 하한. 8GB 폰은 7.4GiB 쯤, 6GB 폰은 5.6GiB 쯤으로 보고한다. */
    private const val MIN_TOTAL_MEM = (5.5 * 1024 * 1024 * 1024).toLong()

    @Volatile
    private var cached: Reason? = null

    fun check(context: Context): Reason {
        cached?.let { return it }
        val reason = compute(context)
        cached = reason
        return reason
    }

    private fun compute(context: Context): Reason {
        if (!Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a") || !android.os.Process.is64Bit()) return Reason.NOT_ARM64
        if (Build.VERSION.SDK_INT < 29) return Reason.OLD_ANDROID
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        if (info.totalMem < MIN_TOTAL_MEM) return Reason.LOW_MEMORY
        if (!hasDotProd()) return Reason.OLD_CPU
        return Reason.OK
    }

    /** /proc/cpuinfo 의 Features 줄에 asimddp(dotprod)와 fphp 또는 asimdhp(fp16)가 있나. */
    private fun hasDotProd(): Boolean = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.any { line ->
                val features = " " + line.substringAfter(':', "").trim() + " "
                line.startsWith("Features") && " asimddp " in features && (" fphp " in features || " asimdhp " in features)
            }
        }
    }.getOrDefault(false)
}
