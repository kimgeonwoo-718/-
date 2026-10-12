package com.spellkeyboard.ko

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import java.io.File

/**
 * 고객센터 — 글을 적고 사진을 붙여 바로 보낸다.
 *
 * 예전엔 더보기의 고객센터가 메일 앱을 열었다. "메일이라 부담스럽다, 앱에서 글이나 사진을 넣어 바로 보내게" 라는 말에
 * (2026-10-12) 이 화면을 만들었다. 서버가 받아 두고 운영자가 읽는다([Feedback]). 메일은 맨 아래 "메일로 보내기" 로 남긴다 —
 * 메일이 편한 사람, 서버가 안 될 때.
 *
 * 사진은 시스템 사진 선택창으로 고른다(저장소 권한이 필요 없다). 고르는 즉시 다른 스레드에서 줄여 캐시에 두고, 그동안은
 * 보내기를 막는다. 화면을 돌려 다시 만들어져도 줄여 둔 사진은 이어진다(글은 입력칸이 스스로 지킨다).
 *
 * 보내다 실패하면 적은 것을 그대로 두고 왜 안 됐는지 아래에 적는다. 저절로 다시 보내지 않는다([Feedback.send]).
 */
class FeedbackActivity : AppCompatActivity() {

    /** 줄여 둔 사진. 보낼 때 이 파일들을 읽는다. */
    private val photos = ArrayList<File>()

    /** 줄이는 중인 사진 수. 0 이 될 때까지 보내기를 막는다. */
    private var preparing = 0

    private var sending = false

    private lateinit var textInput: EditText
    private lateinit var replyInput: EditText
    private lateinit var photoRow: LinearLayout
    private lateinit var status: TextView
    private lateinit var sendButton: TextView

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Feedback.MAX_PHOTOS)) { uris ->
            addPhotos(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        textInput = findViewById(R.id.feedback_text)
        replyInput = findViewById(R.id.feedback_reply)
        photoRow = findViewById(R.id.feedback_photos)
        status = findViewById(R.id.feedback_status)
        sendButton = findViewById(R.id.feedback_send)

        findViewById<View>(R.id.feedback_back).setOnClickListener { finish() }
        sendButton.setOnClickListener { send() }
        findViewById<View>(R.id.feedback_email).setOnClickListener { writeMail() }

        val counter = findViewById<TextView>(R.id.feedback_count)
        fun showCount() {
            counter.text = getString(R.string.feedback_count, textInput.length(), Feedback.MAX_TEXT)
        }
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showCount()
        })
        showCount()

        if (savedInstanceState == null) {
            // 새로 열었다 — 지난번에 보내지 않고 나간 사진이 캐시에 남아 있으면 치운다.
            Feedback.photoDir(this).listFiles()?.forEach { it.delete() }
        } else {
            savedInstanceState.getStringArrayList(KEY_PHOTOS)?.map(::File)?.filter { it.isFile }?.let(photos::addAll)
        }
        showPhotos()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PHOTOS, ArrayList(photos.map { it.path }))
    }

    override fun onDestroy() {
        // 나가는 것이면(돌려서 다시 만드는 것이 아니면) 줄여 둔 사진을 지운다.
        if (isFinishing) photos.forEach { it.delete() }
        super.onDestroy()
    }

    // --- 사진 --------------------------------------------------------------------

    private fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = Feedback.MAX_PHOTOS - photos.size - preparing
        if (uris.size > room) toast(getString(R.string.feedback_photo_limit, Feedback.MAX_PHOTOS))
        val chosen = uris.take(maxOf(0, room))
        if (chosen.isEmpty()) return
        preparing += chosen.size
        showPhotos()
        val app = applicationContext
        Thread {
            val done = chosen.map { Feedback.shrink(app, it) }
            runOnUiThread {
                preparing -= chosen.size
                if (isFinishing || isDestroyed) {
                    done.forEach { it?.delete() }
                    return@runOnUiThread
                }
                done.filterNotNull().forEach(photos::add)
                if (done.any { it == null }) toast(getString(R.string.feedback_photo_failed))
                showPhotos()
            }
        }.start()
    }

    /** 사진 줄: 붙인 사진(오른쪽 위 ✕ 로 뺀다)과, 자리가 남았으면 "사진 추가" 칸. */
    private fun showPhotos() {
        photoRow.removeAllViews()
        val size = dp(84)
        for (file in photos) {
            val tile = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
                background = ContextCompat.getDrawable(context, R.drawable.bg_input)
                clipToOutline = true
            }
            tile.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumbnail(file))
            }, FrameLayout.LayoutParams(size, size))
            tile.addView(TextView(this).apply {
                text = "✕"
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_photo_remove)
                contentDescription = getString(R.string.feedback_photo_remove)
                setOnClickListener {
                    photos.remove(file)
                    file.delete()
                    showPhotos()
                }
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            photoRow.addView(tile)
        }
        if (preparing > 0) {
            photoRow.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_input)
                text = getString(R.string.feedback_photo_preparing)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.toss_text3))
            })
        }
        if (photos.size + preparing < Feedback.MAX_PHOTOS) {
            photoRow.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_input)
                text = getString(R.string.feedback_photo_add, photos.size + preparing, Feedback.MAX_PHOTOS)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.toss_text2))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            })
        }
        setSendEnabled(!sending && preparing == 0)
    }

    /** 사진 칸에 얹을 작은 그림. 줄여 둔 파일(긴 변 1280)을 4분의 1로 읽는다. */
    private fun thumbnail(file: File) =
        runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()

    // --- 보내기 ------------------------------------------------------------------

    private fun send() {
        if (sending || preparing > 0) return
        val text = textInput.text.toString().trim()
        val reply = replyInput.text.toString().trim()
        if (text.isEmpty() && photos.isEmpty()) return showStatus(getString(R.string.feedback_empty))
        if (reply.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(reply).matches()) {
            return showStatus(getString(R.string.feedback_reply_invalid))
        }
        if (!Prefs.serverAvailable()) return writeMail()

        sending = true
        setSendEnabled(false)
        sendButton.setText(R.string.feedback_sending)
        showStatus(null)
        val files = photos.toList()
        val app = applicationContext
        Thread {
            val result = Feedback.send(app, text, reply, files)
            runOnUiThread { onSent(result) }
        }.start()
    }

    private fun onSent(result: Feedback.Result) {
        sending = false
        sendButton.setText(R.string.feedback_send)
        setSendEnabled(preparing == 0)
        when (result) {
            Feedback.Result.Sent, Feedback.Result.SentWithoutPhotos -> {
                toast(
                    getString(
                        if (result == Feedback.Result.Sent) R.string.feedback_sent else R.string.feedback_sent_without_photos
                    )
                )
                // 보냈다. 사진은 onDestroy 가 지운다.
                finish()
            }
            Feedback.Result.Limit -> showStatus(getString(R.string.feedback_failed_limit))
            Feedback.Result.TooLarge -> showStatus(getString(R.string.feedback_failed_too_large))
            Feedback.Result.PhotoStorageFull -> showStatus(getString(R.string.feedback_failed_photo_full))
            Feedback.Result.Network -> showStatus(getString(R.string.feedback_failed_network))
            is Feedback.Result.Server -> showStatus(getString(R.string.feedback_failed_server, result.reason))
        }
    }

    /** 메일 앱으로 — 적어 둔 글을 본문에 옮겨 준다. 사진은 메일 앱에서 붙인다. */
    private fun writeMail() {
        val intent = Feedback.emailIntent(this, textInput.text.toString().trim())
        if (intent == null) {
            toast(getString(R.string.support_not_ready))
            return
        }
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast(getString(R.string.open_failed))
        }
    }

    /** 보내기 단추는 눌림 색만 바뀌는 그림이라, 막혔을 때는 흐리게 해서 보인다. */
    private fun setSendEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun showStatus(message: String?) {
        status.text = message.orEmpty()
        status.isVisible = message != null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_PHOTOS = "feedback_photos"
    }
}
