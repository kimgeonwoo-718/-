package com.spellkeyboard.ko.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.spellkeyboard.core.net.ResumableDownload
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 기기 안 번역 모델 파일의 받기·확인·지우기.
 *
 * ## 왜 직접 받나 (시스템 DownloadManager 를 안 쓴 이유)
 *
 * DownloadManager 는 앱 전용 **바깥 저장소**(`Android/data/…`)에만 쓴다. 그 폴더는 FUSE 로 걸려 있어서, 모델이 파일을 메모리에 얹어
 * (mmap) 3GB 를 여기저기 읽을 때 훨씬 느리다. 앱 안쪽 폴더([Context.getFilesDir])에 직접 받으면 그 문제가 없다.
 *
 * ## 받는 방식
 *
 * - 조각마다 `.part` 로 받고 다 받으면 SHA-256 을 확인한 뒤 이름을 바꾼다. 끊기면 `.part` 크기부터 이어 받는다(Range).
 * - 리다이렉트는 직접 따라간다(깃허브 릴리스는 다른 주소로 넘긴다). 매번 Range 를 다시 붙인다.
 * - 받는 일은 이 프로세스의 스레드 하나다. 키보드가 켜져 있는 동안은 프로세스가 살아 있으므로 설정 화면을 나가도 이어진다.
 *   프로세스가 죽으면 `.part` 가 남아 다음에 이어 받는다.
 * - 와이파이(종량제 아닌 망)가 기본이다. 사용자가 데이터로도 받겠다고 하면 그때만 허용한다.
 */
object LlmModelStore {

    enum class State { NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, FAILED }

    class Status(val state: State, val doneBytes: Long, val totalBytes: Long, val message: String? = null) {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    // 받는 중에만 의미 있는 값. 여러 스레드가 읽으므로 @Volatile.
    @Volatile private var worker: Thread? = null
    @Volatile private var cancelRequested = false
    @Volatile private var doneBytes = 0L
    @Volatile private var phase = State.NOT_INSTALLED
    @Volatile private var failure: String? = null

    fun dir(context: Context): File = File(context.filesDir, "llm").also { it.mkdirs() }

    private fun finalFile(context: Context, shard: LlmModelFiles.Shard) = File(dir(context), shard.name)
    private fun partFile(context: Context, shard: LlmModelFiles.Shard) = File(dir(context), shard.name + ".part")
    private fun prefs(context: Context) = context.getSharedPreferences("llm_model", Context.MODE_PRIVATE)

    /** 지금 상태. 가볍다(파일 크기만 본다) — 화면이 자주 불러도 된다. */
    fun status(context: Context): Status {
        val total = LlmModelFiles.totalBytes
        when (phase) {
            State.DOWNLOADING -> if (worker?.isAlive == true) return Status(State.DOWNLOADING, doneBytes, total)
            State.VERIFYING -> if (worker?.isAlive == true) return Status(State.VERIFYING, total, total)
            else -> Unit
        }
        if (isReady(context)) return Status(State.READY, total, total)
        failure?.let { return Status(State.FAILED, partBytes(context), total, it) }
        return Status(State.NOT_INSTALLED, partBytes(context), total)
    }

    /** 모델을 쓸 수 있나(조각이 다 있고 확인을 마쳤다). 번역 때마다 부르므로 파일 크기와 기록만 본다. */
    fun isReady(context: Context): Boolean {
        val verified = prefs(context).getString(KEY_VERIFIED, null) ?: return false
        return verified == fingerprint(context)
    }

    /** 엔진에 넘길 첫 조각 경로. 쓸 수 없으면 null. */
    fun firstShardPath(context: Context): String? =
        if (isReady(context)) finalFile(context, LlmModelFiles.SHARDS.first()).absolutePath else null

    /** 조각 파일들의 이름·크기·수정 시각을 이은 것. 확인을 마친 뒤 파일이 바뀌었는지 가려낸다. */
    private fun fingerprint(context: Context): String? {
        val parts = LlmModelFiles.SHARDS.map { shard ->
            val file = finalFile(context, shard)
            if (!file.exists() || file.length() != shard.bytes) return null
            "${shard.name}:${file.length()}:${file.lastModified()}"
        }
        return parts.joinToString(";")
    }

    private fun partBytes(context: Context): Long = LlmModelFiles.SHARDS.sumOf { shard ->
        val done = finalFile(context, shard)
        if (done.exists()) done.length() else partFile(context, shard).takeIf { it.exists() }?.length() ?: 0L
    }

    /** 데이터(종량제) 망에 있나. */
    fun onMeteredNetwork(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: SecurityException) {
        true // 망 상태를 못 보면 데이터라고 가정한다 — 데이터 요금이 나가는 쪽이 더 나쁜 실수다.
    }

    /** 받기 전에 저장 공간이 넉넉한가. 남은 조각만큼과 여유 300MB. */
    fun hasRoom(context: Context): Boolean {
        val need = LlmModelFiles.totalBytes - partBytes(context) + 300L * 1024 * 1024
        return StatFs(dir(context).path).availableBytes >= need
    }

    /**
     * 받기를 시작한다(이미 받는 중이면 무시). 끝나면 확인까지 하고 [onFinished] 를 **받는 스레드에서** 부른다.
     * @param allowMetered 데이터 망에서도 받는가
     */
    @Synchronized
    fun start(context: Context, allowMetered: Boolean, onFinished: (() -> Unit)? = null) {
        if (worker?.isAlive == true) return
        if (isReady(context)) return
        val app = context.applicationContext
        failure = null
        cancelRequested = false
        phase = State.DOWNLOADING
        doneBytes = partBytes(app)
        worker = Thread({
            try {
                var base = 0L // 이미 끝난 조각들의 크기 합 — 진행률의 시작점
                for (shard in LlmModelFiles.SHARDS) {
                    val ready = finalFile(app, shard).let { it.exists() && it.length() == shard.bytes && sha256Of(it) == shard.sha256 }
                    if (!ready) download(app, shard, allowMetered, base)
                    base += shard.bytes
                    doneBytes = base
                }
                phase = State.VERIFYING
                markVerified(app)
                phase = if (isReady(app)) State.READY else State.FAILED
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                phase = State.FAILED
            } finally {
                onFinished?.invoke()
            }
        }, "llm-download").also { it.isDaemon = true; it.start() }
    }

    fun cancel() {
        cancelRequested = true
    }

    /** 받은 파일을 모두 지운다(받는 중이면 먼저 멈춘다). */
    fun delete(context: Context) {
        cancel()
        worker?.join(3000)
        val app = context.applicationContext
        LlmModelFiles.SHARDS.forEach { finalFile(app, it).delete(); partFile(app, it).delete() }
        prefs(app).edit().remove(KEY_VERIFIED).apply()
        failure = null
        phase = State.NOT_INSTALLED
        doneBytes = 0
    }

    // --- 받기 ------------------------------------------------------------------------------------

    /**
     * 조각 하나를 받는다. 이어 받기·리다이렉트·재시도는 [ResumableDownload] 가 한다(컨테이너에서 가짜 서버로 시험했다).
     * 다 받으면 SHA-256 을 확인한 뒤 이름을 바꾼다.
     * @param base 이미 끝난 조각들의 크기 합(진행률 계산용)
     */
    private fun download(context: Context, shard: LlmModelFiles.Shard, allowMetered: Boolean, base: Long) {
        val part = partFile(context, shard)
        if (!allowMetered && onMeteredNetwork(context)) throw IOException("와이파이에 연결되어 있지 않습니다")
        ResumableDownload().download(
            url = shard.url,
            part = part,
            expectedBytes = shard.bytes,
            cancelled = { cancelRequested },
            onProgress = { doneBytes = base + it }
        )
        val got = sha256Of(part)
        if (part.length() != shard.bytes || got != shard.sha256) {
            part.delete()
            throw IOException("받은 파일이 올바르지 않습니다. 다시 받아 주십시오")
        }
        val target = finalFile(context, shard)
        target.delete()
        if (!part.renameTo(target)) throw IOException("파일 이름을 바꾸지 못했습니다")
    }

    // --- 확인 ------------------------------------------------------------------------------------

    /**
     * 조각이 다 있고 크기가 맞으면 '확인 마침' 을 기록한다. 해시는 받으면서(download) 이미 확인했으므로 3GB 를 다시 읽지 않는다.
     * 기록에는 파일 크기·수정 시각이 들어 있어서([fingerprint]) 그 뒤에 파일이 바뀌면 저절로 무효가 된다.
     */
    private fun markVerified(context: Context) {
        for (shard in LlmModelFiles.SHARDS) {
            val file = finalFile(context, shard)
            if (!file.exists() || file.length() != shard.bytes) throw IOException("조각이 없습니다: ${shard.name}")
        }
        prefs(context).edit().putString(KEY_VERIFIED, fingerprint(context)).apply()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private const val KEY_VERIFIED = "verified"
}
