package com.spellkeyboard.ko

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import kotlin.random.Random

/** 어떤 키를 눌렀나. 소리팩이 키 종류마다 다른 소리를 낸다. */
enum class KeySound { LETTER, SPACE, ENTER, DELETE }

/** 키 소리 묶음. [premium] 이면 구독자 전용. */
enum class SoundPack(val premium: Boolean = false) {
    NONE,
    /** 짧은 '뿍'(글자), '아우!'(스페이스), '아우 아우'(엔터), 낮은 '웅'(지우기). */
    SEA_LION(premium = true)
}

/**
 * 키 소리를 낸다.
 *
 * **SoundPool** 을 쓴다 — 짧은 소리를 미리 메모리에 올려 두고 누르는 순간 바로 낸다.
 * MediaPlayer 는 틀 때마다 준비하느라 빨리 치면 소리가 밀리고 겹친다.
 *
 * 소리가 나지 않는 경우:
 * - 소리팩을 안 골랐거나 구독이 끝났을 때
 * - 폰이 **무음·진동 모드**일 때(수업 시간에 바다사자가 울면 안 된다)
 * - 소리 크기를 0 으로 뒀을 때
 */
class KeySounds(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val random = Random.Default

    /** 불러 둔 소리. 소리팩을 처음 켤 때 한 번 올린다 — 안 쓰는 사람은 메모리를 안 쓴다. */
    private var loaded: Map<KeySound, List<Int>>? = null
    private var lastLetter = -1

    /** 설정에서 매번 읽지 않으려고 키보드가 뜰 때 한 번 맞춘다. */
    private var pack = SoundPack.NONE
    private var volume = 0f

    fun refresh() {
        val chosen = Prefs.soundPack(context)
        pack = if (chosen.premium && !Premium.active(context)) SoundPack.NONE else chosen
        volume = Prefs.soundVolume(context) / 100f
        if (pack == SoundPack.SEA_LION && loaded == null) loaded = loadSeaLion()
    }

    fun play(kind: KeySound) {
        if (pack == SoundPack.NONE || volume <= 0f) return
        if (audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val sounds = loaded?.get(kind) ?: return
        if (sounds.isEmpty()) return
        // 글자 키는 넷을 번갈아 — 같은 소리가 연달아 나지 않게. 높이도 살짝씩 흔든다.
        val id = if (kind == KeySound.LETTER && sounds.size > 1) {
            var pick = random.nextInt(sounds.size)
            if (pick == lastLetter) pick = (pick + 1) % sounds.size
            lastLetter = pick
            sounds[pick]
        } else {
            sounds[0]
        }
        val rate = if (kind == KeySound.LETTER) 0.92f + random.nextFloat() * 0.16f else 1f
        pool.play(id, volume, volume, 1, 0, rate)
    }

    fun release() {
        pool.release()
        loaded = null
    }

    private fun loadSeaLion(): Map<KeySound, List<Int>> = mapOf(
        KeySound.LETTER to listOf(
            R.raw.sealion_key_1, R.raw.sealion_key_2, R.raw.sealion_key_3, R.raw.sealion_key_4
        ).map { pool.load(context, it, 1) },
        KeySound.SPACE to listOf(pool.load(context, R.raw.sealion_space, 1)),
        KeySound.ENTER to listOf(pool.load(context, R.raw.sealion_enter, 1)),
        KeySound.DELETE to listOf(pool.load(context, R.raw.sealion_delete, 1))
    )

    private companion object {
        /** 빨리 치면 앞 소리가 끝나기 전에 다음 키가 온다. 넉넉히 겹치게 둔다. */
        const val MAX_STREAMS = 6
    }
}
