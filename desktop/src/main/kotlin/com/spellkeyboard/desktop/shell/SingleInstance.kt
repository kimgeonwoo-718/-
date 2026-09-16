package com.spellkeyboard.desktop.shell

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 한 번에 하나만 돌게 한다. 그리고 **두 번째는 조용히 죽지 않는다** —
 * 이미 돌고 있는 쪽의 창을 띄워 주고 끝난다.
 *
 * ## 왜 잠금 파일 **과** 소켓 둘 다인가
 *
 * 잠금 파일만 쓰면 두 번째가 "이미 돌고 있다" 는 것까지만 안다. 사용자는 아이콘을
 * 두 번 눌렀을 뿐인데 아무 일도 안 일어난 것처럼 보인다 — 실은 그것이 가장 흔한
 * 두 번째 실행이다. 그래서 첫 번째가 되돌이 주소(127.0.0.1)에 구멍 하나를 열어
 * 두고, 두 번째가 거기에 "창 좀 띄워라" 한 줄을 보내고 끝난다.
 *
 * 소켓만 쓰면 반대로 곤란하다. 포트는 다른 프로그램이 이미 잡고 있을 수 있고,
 * 방화벽이 막을 수도 있다. 잠금 파일은 운영체제가 **프로세스가 죽으면 반드시**
 * 풀어 주므로 그쪽이 진실의 근거다. 소켓은 편의다.
 *
 * ## 죽은 뒤에 남는 것
 *
 * 강제 종료돼도 [FileLock] 은 운영체제가 푼다. 그래서 파일이 남아 있어도 다음
 * 실행은 멀쩡히 잠금을 딴다. 포트 파일만 낡은 값으로 남는데, 잠금을 딴 쪽이
 * 곧바로 덮어쓰므로 문제가 안 된다.
 */
class SingleInstance private constructor(
    private val lockFile: File,
    private val portFile: File,
    private val raf: RandomAccessFile,
    private val lock: FileLock,
    private val server: ServerSocket?,
    private val token: String,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** 두 번째 실행을 받는 포트. 구멍을 못 열었으면 -1 이다. */
    val port: Int get() = server?.localPort ?: -1

    private fun serve(onShowRequested: () -> Unit) {
        val listening = server ?: return
        val t = Thread({
            while (!closed.get()) {
                val socket = try {
                    listening.accept()
                } catch (_: Exception) {
                    break // close() 가 서버를 닫으면 여기로 떨어진다. 정상 종료다.
                }
                // 한 번에 한 줄만 주고받는다. 오래 잡고 있을 까닭이 없다.
                runCatching {
                    socket.use { s ->
                        s.soTimeout = READ_TIMEOUT_MS
                        val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                            .readLine()
                        if (line == greeting(token)) {
                            s.getOutputStream().write("OK\n".toByteArray(Charsets.UTF_8))
                            s.getOutputStream().flush()
                            // **이 호출은 소켓 실에서 일어난다.** 창을 만지려면
                            // 부르는 쪽이 SwingUtilities.invokeLater 로 넘겨야 한다.
                            runCatching { onShowRequested() }
                        }
                        // 암호가 틀리면 아무 말 없이 끊는다. 되돌이 주소라도 아무나
                        // 우리 창을 띄우게 둘 까닭은 없다.
                    }
                }
            }
        }, "single-instance")
        t.isDaemon = true // 이 실이 살아 있다고 JVM 이 안 끝나면 안 된다.
        t.start()
    }

    /** 잠금과 포트를 놓는다. 종료 경로마다 반드시 불러라. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        runCatching { lock.release() }
        runCatching { raf.close() }
        // 지우기는 덤이다. 안 지워져도(다른 쪽이 붙잡고 있어도) 다음 실행은 멀쩡하다.
        runCatching { portFile.delete() }
        runCatching { lockFile.delete() }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 2000
        private const val CONNECT_TIMEOUT_MS = 1500

        private fun greeting(token: String) = "SPELLDESKTOP 1 $token SHOW"

        /**
         * 자리를 잡아 본다.
         *
         * @param onShowRequested 두 번째 실행이 왔을 때. **소켓 실에서 불린다.**
         * @param dir 잠금 파일을 둘 곳. 시험에서만 바꾼다.
         */
        fun acquire(
            dir: File = defaultDir(),
            onShowRequested: () -> Unit = {},
        ): SingleInstanceResult {
            runCatching { dir.mkdirs() }
            val lockFile = File(dir, "instance.lock")
            val portFile = File(dir, "instance.port")

            val raf = try {
                RandomAccessFile(lockFile, "rw")
            } catch (e: Exception) {
                // 잠금 파일조차 못 만들면(읽기 전용 폴더 등) 가로막을 근거가 없다.
                // 막고 안 뜨는 것보다 그냥 뜨는 편이 낫다.
                return SingleInstanceResult.Unknown("잠금 파일을 못 만들었습니다: ${e.message}")
            }

            val lock = try {
                raf.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // **같은 JVM** 안에서 이미 잠갔을 때 나온다. null 이 아니라 던진다.
                // 이것을 안 받으면 한 프로세스 안에서 두 번 부를 때 터진다.
                null
            } catch (e: Exception) {
                runCatching { raf.close() }
                return SingleInstanceResult.Unknown("잠금에 실패했습니다: ${e.message}")
            }

            if (lock == null) {
                runCatching { raf.close() }
                // 먼저 뜬 쪽이 있다. 그쪽 창을 띄워 달라고 부탁하고 물러난다.
                return SingleInstanceResult.AlreadyRunning(notifiedFirstInstance = ping(portFile))
            }

            val server = try {
                // 되돌이 주소에만 연다. 0 을 주면 운영체제가 빈 포트를 고른다 —
                // 번호를 못 박으면 다른 프로그램과 부딪히고, 그때 대안이 없다.
                ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            } catch (e: Exception) {
                // 소켓이 안 열려도 잠금은 우리 것이다. 혼자 도는 것은 보장된다.
                // 두 번째 실행이 창을 못 띄울 뿐이고, 그것은 못 뜨는 것보다 낫다.
                // 낡은 포트 파일은 반드시 지운다 — 안 그러면 두 번째 실행이 남의
                // 프로그램이 물려받은 그 포트에 대고 말을 건다.
                runCatching { portFile.delete() }
                val self = SingleInstance(lockFile, portFile, raf, lock, null, "")
                return SingleInstanceResult.First(self, "두 번째 실행을 받을 구멍을 못 열었습니다: ${e.message}")
            }

            val token = newToken()
            runCatching { portFile.writeText("${server.localPort}\n$token\n", Charsets.UTF_8) }

            val self = SingleInstance(lockFile, portFile, raf, lock, server, token)
            self.serve(onShowRequested)
            return SingleInstanceResult.First(self, null)
        }

        /** 먼저 뜬 쪽에게 창을 띄우라고 한다. 성공하면 true. */
        private fun ping(portFile: File): Boolean {
            val lines = runCatching { portFile.readLines() }.getOrNull() ?: return false
            val port = lines.getOrNull(0)?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
            val token = lines.getOrNull(1)?.trim() ?: return false
            return runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
                    s.soTimeout = READ_TIMEOUT_MS
                    s.getOutputStream().write((greeting(token) + "\n").toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                    BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine() == "OK"
                }
            }.getOrDefault(false)
        }

        private fun newToken(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * `%LOCALAPPDATA%\SpellDesktop`. 로밍이 아닌 까닭은 잠금 파일이 이 기계에만
         * 뜻이 있어서다 — 회사 계정처럼 로밍 폴더가 여러 PC 를 오가면 엉뚱한 기계의
         * 포트 번호를 물게 된다.
         */
        fun defaultDir(): File {
            val local = System.getenv("LOCALAPPDATA")
            val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"))
            return File(base, "SpellDesktop")
        }
    }
}

sealed interface SingleInstanceResult {
    /**
     * 우리가 첫 번째다. 그대로 뜨면 된다.
     * @param warning 잠금은 땄지만 두 번째 실행을 받을 구멍은 못 연 경우의 설명.
     */
    data class First(val instance: SingleInstance, val warning: String?) : SingleInstanceResult

    /**
     * 이미 돌고 있다. **떠서는 안 된다** — 단축키를 둘이 다투게 된다.
     * @param notifiedFirstInstance 먼저 뜬 쪽 창을 띄워 달라고 전했는지.
     *   false 면 사용자에게 "이미 실행 중입니다" 를 보여 주고 끝내라.
     */
    data class AlreadyRunning(val notifiedFirstInstance: Boolean) : SingleInstanceResult

    /** 판단할 근거가 없다. 막지 말고 그냥 떠라 — 못 뜨는 것이 더 나쁘다. */
    data class Unknown(val why: String) : SingleInstanceResult
}
