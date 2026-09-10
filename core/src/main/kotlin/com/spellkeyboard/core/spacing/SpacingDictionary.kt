package com.spellkeyboard.core.spacing

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream

/**
 * mecab-ko-dic 에서 구운 형태소 사전과 연결비용 행렬.
 *
 * 원본 데이터는 Apache License 2.0 (mecab-ko-dic, 은전한닢 프로젝트).
 * `tools/build_dictionary.py` 가 굽고, 여기서는 읽기만 한다.
 *
 * 두 파일 합쳐 압축 전 50MB 가까이 된다. 힙에 올리면 키보드가 감당하지 못하므로
 * 처음 한 번 캐시 디렉터리에 풀어 두고 mmap 으로 읽는다. 이러면 상주 메모리는
 * 실제로 건드린 페이지만큼만 든다.
 */
class SpacingDictionary internal constructor(
    private val lexicon: ByteBuffer,
    private val matrix: ByteBuffer
) {

    private val tags: List<String>
    private val keyCount: Int
    private val keyIndexOffset: Int
    private val morphIndexOffset: Int
    private val keyBlobOffset: Int
    private val morphOffset: Int

    /** 연결비용 행렬 */
    private val leftContextCount: Int
    private val rightTagOffset: Int
    private val leftTagOffset: Int
    private val costOffset: Int

    init {
        require(lexicon.getInt(0) == MAGIC_LEXICON) { "사전 파일이 아니다" }
        require(matrix.getInt(0) == MAGIC_MATRIX) { "행렬 파일이 아니다" }

        keyCount = lexicon.getInt(8)
        val tagBlobLength = lexicon.getInt(16)
        val tagBytes = ByteArray(tagBlobLength)
        // 절대 위치 get 만 쓴다. position(int) 의 반환형이 안드로이드 버전에 따라 갈려서
        // 새 JDK 로 컴파일한 코드가 옛 기기에서 NoSuchMethodError 를 내는 함정이 있다.
        for (i in 0 until tagBlobLength) tagBytes[i] = lexicon.get(20 + i)
        tags = String(tagBytes, Charsets.UTF_8).split('\n')

        keyIndexOffset = 20 + tagBlobLength
        morphIndexOffset = keyIndexOffset + (keyCount + 1) * 4
        val keyBlobLengthOffset = morphIndexOffset + (keyCount + 1) * 4
        keyBlobOffset = keyBlobLengthOffset + 4
        morphOffset = keyBlobOffset + lexicon.getInt(keyBlobLengthOffset)

        leftContextCount = matrix.getShort(8).toInt() and 0xFFFF
        val rightTagLength = matrix.getInt(12)
        rightTagOffset = 16
        val leftTagLengthOffset = rightTagOffset + rightTagLength
        leftTagOffset = leftTagLengthOffset + 4
        costOffset = leftTagOffset + matrix.getInt(leftTagLengthOffset)
    }

    fun tagName(id: Int): String = tags[id]

    fun tagId(name: String): Int = tags.indexOf(name)

    /** 왼쪽 형태소의 우문맥 id 와 오른쪽 형태소의 좌문맥 id 사이의 연결비용. */
    fun connectionCost(rightContextOfLeft: Int, leftContextOfRight: Int): Int =
        matrix.getShort(costOffset + (rightContextOfLeft + leftContextCount * leftContextOfRight) * 2)
            .toInt()

    /** 우문맥 id 가 나타내는 품사. 앞 형태소가 무엇으로 끝났는지 판단할 때 쓴다. */
    fun tagOfRightContext(id: Int): Int = matrix.get(rightTagOffset + id).toInt() and 0xFF

    /**
     * [key] 와 정확히 일치하는 표층형을 찾는다.
     *
     * @return 형태소 배열에서의 시작 인덱스. 없으면 -1.
     */
    fun find(key: ByteArray, from: Int, to: Int): Int {
        var low = 0
        var high = keyCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareKey(mid, key, from, to)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun morphStart(keyIndex: Int): Int = lexicon.getInt(morphIndexOffset + keyIndex * 4)

    fun morphEnd(keyIndex: Int): Int = lexicon.getInt(morphIndexOffset + (keyIndex + 1) * 4)

    fun leftContext(morph: Int): Int =
        lexicon.getShort(morphOffset + morph * 8).toInt() and 0xFFFF

    fun rightContext(morph: Int): Int =
        lexicon.getShort(morphOffset + morph * 8 + 2).toInt() and 0xFFFF

    fun wordCost(morph: Int): Int = lexicon.getShort(morphOffset + morph * 8 + 4).toInt()

    /** 형태소가 시작하는 품사. 활용형은 'VV+EF' 처럼 복합이라 앞쪽을 본다. */
    fun headTag(morph: Int): Int = lexicon.get(morphOffset + morph * 8 + 6).toInt() and 0xFF

    /** 형태소가 끝나는 품사. */
    fun tailTag(morph: Int): Int = lexicon.get(morphOffset + morph * 8 + 7).toInt() and 0xFF

    private fun compareKey(index: Int, key: ByteArray, from: Int, to: Int): Int {
        val start = keyBlobOffset + lexicon.getInt(keyIndexOffset + index * 4)
        val end = keyBlobOffset + lexicon.getInt(keyIndexOffset + (index + 1) * 4)
        var a = start
        var b = from
        while (a < end && b < to) {
            val diff = (lexicon.get(a).toInt() and 0xFF) - (key[b].toInt() and 0xFF)
            if (diff != 0) return diff
            a++
            b++
        }
        return (end - start) - (to - from)
    }

    companion object {
        // 파일에는 "KSPL"/"KSPM" 이 그대로 적혀 있고, 버퍼를 리틀엔디언으로 읽으므로
        // 바이트 순서가 뒤집힌 값과 비교한다.
        private const val MAGIC_LEXICON = 0x4C50534B
        private const val MAGIC_MATRIX = 0x4D50534B

        const val LEXICON_RESOURCE = "/lexicon.bin.gz"
        const val MATRIX_RESOURCE = "/matrix.bin.gz"

        /**
         * 리소스에 든 압축 데이터를 [cacheDir] 에 풀고 mmap 해서 연다.
         *
         * 이미 풀려 있으면 다시 풀지 않는다. 압축 해제는 처음 한 번뿐이라
         * 백그라운드 스레드에서 부르는 것이 좋다.
         */
        fun open(cacheDir: File): SpacingDictionary {
            val lexicon = mapResource(LEXICON_RESOURCE, File(cacheDir, "lexicon.bin"))
            val matrix = mapResource(MATRIX_RESOURCE, File(cacheDir, "matrix.bin"))
            return SpacingDictionary(lexicon, matrix)
        }

        private fun mapResource(resource: String, target: File): ByteBuffer {
            if (!target.exists() || target.length() == 0L) {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                openResource(resource).use { input ->
                    GZIPInputStream(input, 1 shl 16).use { gz ->
                        temp.outputStream().buffered().use { out -> gz.copyTo(out) }
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { channel ->
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
            }
        }

        private fun openResource(name: String): InputStream =
            SpacingDictionary::class.java.getResourceAsStream(name)
                ?: throw IllegalStateException("사전 리소스를 찾지 못했다: $name")
    }
}
