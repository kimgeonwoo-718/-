package com.spellkeyboard.ko

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible

/**
 * 더보기. 첫 화면 오른쪽 위 ☰ 에서 온다.
 *
 * 위에는 계정 칸, 아래에는 메뉴(키보드 맞춤설정 · 개인정보 처리방침 · 서비스 이용약관 ·
 * 고객센터), 맨 밑에는 버전과 설치 ID. 첫 화면 위 막대에 로그인·환경설정 버튼을 따로
 * 늘어놓았더니 지저분하고 서로 따로 놀아서 한곳에 모았다.
 *
 * **계정 칸**: 로그인 전에는 로그인 버튼 하나. 로그인 뒤에는 구글 프로필 사진과 이름, 그 밑에
 * 로그아웃 · 회원 탈퇴. 이름과 사진은 이 폰에만 있다([Prefs.profileName], [ProfilePhoto]).
 *
 * **약관 두 개는 서버 페이지를 연다.** Play Console 에 적는 주소와 같은 곳이라, 앱 안에 글을
 * 따로 넣었다가 둘이 서로 다른 말을 하게 될 일이 없다.
 */
class MoreActivity : AppCompatActivity() {

    private var account: AccountManager? = null

    /**
     * 로그인할 때 서버가 알려 준 구독 여부. 화면이 다시 만들어지면 사라진다(null) —
     * 그때는 이 폰이 들고 있는 구매 토큰으로 대신 짐작한다. 어느 쪽이든 **표시용**이고,
     * 실제 판단은 교정할 때마다 서버가 Play 에 물어 한다.
     */
    private var accountSubscriber: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this).nightMode())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        bindAccount()
        bindMenu()
        showAppInfo()
    }

    override fun onDestroy() {
        account?.destroy()
        account = null
        super.onDestroy()
    }

    // --- 계정 -----------------------------------------------------------------

    /**
     * 폰만 쓰는 사람에게는 로그인이 소용없다 — Play 가 알아서 구독을 복원한다. **윈도우·아이폰
     * 에서 같은 구독을 쓰려는 사람**을 위한 칸이다. 빌드에 클라이언트 ID 가 없으면 칸째 감춘다.
     */
    private fun bindAccount() {
        if (!Prefs.loginAvailable()) {
            findViewById<View>(R.id.account_card).isVisible = false
            return
        }
        account = AccountManager(this)

        val signIn = findViewById<TextView>(R.id.signin_button)
        signIn.setOnClickListener {
            // 두 번 누르면 계정 창이 두 개 뜬다. 끝날 때까지 막아 둔다.
            signIn.isEnabled = false
            signIn.setText(R.string.setting_account_working)
            account?.signIn(this) { result ->
                signIn.isEnabled = true
                signIn.setText(R.string.account_signin)
                accountSubscriber = if (result.signedIn) result.subscriber else null
                showAccount()
                result.message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            }
        }

        findViewById<View>(R.id.signout_button).setOnClickListener {
            account?.signOut {
                accountSubscriber = null
                showAccount()
            }
        }

        findViewById<View>(R.id.delete_button).setOnClickListener { confirmDelete() }
        showAccount()
    }

    /**
     * 탈퇴는 되돌릴 수 없어서 한 번 더 묻는다. **구독은 해지되지 않는다**는 말을 여기서
     * 꼭 한다 — 탈퇴하면 결제도 멈출 거라 믿고 나갔다가 다음 달 청구서를 보는 일이 없게.
     */
    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.account_delete_title)
            .setMessage(R.string.account_delete_message)
            .setNegativeButton(R.string.account_cancel, null)
            .setPositiveButton(R.string.account_delete_confirm) { _, _ ->
                val button = findViewById<TextView>(R.id.delete_button)
                button.isEnabled = false
                account?.deleteAccount { result ->
                    button.isEnabled = true
                    if (!result.signedIn) accountSubscriber = null
                    showAccount()
                    val message = result.message ?: getString(R.string.account_deleted)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showAccount() {
        val signedIn = Prefs.signedIn(this)
        findViewById<View>(R.id.account_signed_out).isVisible = !signedIn
        findViewById<View>(R.id.account_signed_in).isVisible = signedIn
        if (!signedIn) return

        val name = Prefs.profileName(this).ifEmpty { getString(R.string.account_name_unknown) }
        findViewById<TextView>(R.id.profile_name).text = name

        // 사진이 있으면 사진, 없으면 이름 첫 글자를 동그라미에 얹는다.
        val photo = ProfilePhoto.load(this)
        findViewById<ImageView>(R.id.profile_photo).apply {
            isVisible = photo != null
            setImageBitmap(photo)
        }
        findViewById<TextView>(R.id.profile_initial).text = name.take(1)

        // 서버에 묻지 않는다. 로그인할 때 받아 둔 답을 쓰고, 없으면 구매 토큰으로 짐작한다.
        val attached = accountSubscriber ?: Prefs.purchaseToken(this).isNotEmpty()
        findViewById<TextView>(R.id.account_status).setText(
            if (attached) R.string.setting_account_signed_in else R.string.setting_account_signed_in_free
        )
    }

    // --- 메뉴 -----------------------------------------------------------------

    private fun bindMenu() {
        findViewById<View>(R.id.menu_keyboard).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.menu_privacy).setOnClickListener { openServerPage("/privacy") }
        findViewById<View>(R.id.menu_terms).setOnClickListener { openServerPage("/terms") }
        findViewById<View>(R.id.menu_support).setOnClickListener { writeToSupport() }
    }

    /** 서버가 내는 약관 페이지를 브라우저로 연다. 서버 주소가 없는 빌드에서는 열 곳이 없다. */
    private fun openServerPage(path: String) {
        if (!Prefs.serverAvailable()) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        open(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.serverUrl() + path)))
    }

    /**
     * 고객센터: 메일 앱을 열어 받는 사람·제목을 채워 둔다.
     *
     * 본문 밑에 앱 버전·기기·설치 ID 를 붙인다. "안 돼요" 한 줄만 오면 어느 기기의 무슨
     * 판인지부터 되물어야 한다. 설치 ID 는 무작위 값이라 개인 정보가 아니다.
     */
    private fun writeToSupport() {
        val to = getString(R.string.support_email).trim()
        if (to.isEmpty()) {
            Toast.makeText(this, R.string.support_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val body = getString(
            R.string.support_body,
            versionName(),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            Prefs.installId(this),
        )
        open(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.support_subject))
                .putExtra(Intent.EXTRA_TEXT, body)
        )
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // --- 버전 · 설치 ID ---------------------------------------------------------

    /**
     * 맨 밑 흐린 한 줄. 길게 누르면 설치 ID 가 복사된다.
     *
     * 설치 ID 는 이 기기가 서버에 자기를 밝히는 이름이다. 문의할 때 알려 주면 어느 기기인지
     * 짚을 수 있고, 출시 전에는 서버의 시험용 구독자 목록에 넣어 유료 기능을 실기기에서
     * 확인한다. 앱이 처음 켜질 때 만든 무작위 값이라 개인 정보가 아니다.
     */
    private fun showAppInfo() {
        val id = Prefs.installId(this)
        findViewById<TextView>(R.id.app_info).apply {
            text = getString(R.string.app_info, versionName(), id)
            setOnLongClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.setting_install_id_label), id))
                Toast.makeText(this@MoreActivity, R.string.setting_install_id_copied, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
}
