package com.spellkeyboard.ko

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible

/**
 * 더보기 서랍. 첫 화면 오른쪽 위 ☰ 를 누르면 오른쪽에서 밀려 나와 **화면 폭의 3분의 2**를
 * 덮고, 나머지는 어둡게 깔린다. 어두운 곳이나 ✕ 나 뒤로 가기를 누르면 닫힌다.
 *
 * 처음엔 따로 화면(액티비티)이었는데 "아예 다른 창으로 넘어간다" 는 평이라 서랍으로 바꿨다.
 * 첫 화면이 뒤에 그대로 비쳐 있어서 어디서 왔는지 잃지 않는다.
 *
 * 안에 든 것: 계정 칸(로그인 전엔 로그인 버튼, 뒤엔 구글 프로필 사진·이름과 로그아웃·회원
 * 탈퇴), 메뉴(키보드 맞춤설정 · 개인정보 처리방침 · 서비스 이용약관 · 고객센터 · 사용 안내 다시
 * 보기), 버전·설치 ID.
 * 이름과 사진은 이 폰에만 있다([Prefs.profileName], [ProfilePhoto]).
 *
 * **약관 두 개는 서버 페이지를 연다.** Play Console 에 적는 주소와 같은 곳이라, 앱 안에 글을
 * 따로 넣었다가 둘이 서로 다른 말을 하게 될 일이 없다.
 */
class MoreDrawer(private val activity: Activity) {

    private val drawer: View = activity.findViewById(R.id.more_drawer)
    private val scrim: View = activity.findViewById(R.id.more_scrim)

    private var account: AccountManager? = null

    /** "사용 안내 다시 보기" 를 눌렀을 때. 둘러보기는 첫 화면 몫이라 첫 화면이 채운다. */
    var onGuide: (() -> Unit)? = null

    /**
     * 로그인할 때 서버가 알려 준 구독 여부. 화면이 다시 만들어지면 사라진다(null) —
     * 그때는 이 폰이 들고 있는 구매 토큰으로 대신 짐작한다. 어느 쪽이든 **표시용**이고,
     * 실제 판단은 교정할 때마다 서버가 Play 에 물어 한다.
     */
    private var accountSubscriber: Boolean? = null

    val isOpen: Boolean get() = drawer.isVisible

    init {
        scrim.setOnClickListener { close() }
        activity.findViewById<View>(R.id.more_close).setOnClickListener { close() }
        bindAccount()
        bindMenu()
        showAppInfo()
    }

    // --- 열고 닫기 ---------------------------------------------------------------

    /**
     * 연다. 폭은 화면의 3분의 2 — 처음엔 반이었는데 좁다는 평이라 넓혔다. 가로로 눕힌 폰이나
     * 태블릿에서는 그래도 너무 넓어서 400dp 에서 멈춘다.
     *
     * [animate] 가 false 면 바로 편다. 테마를 바꿔 화면이 다시 만들어질 때 서랍을 연 채로
     * 되살리는 자리다 — 거기서 다시 미끄러져 나오면 어색하다.
     */
    fun open(animate: Boolean = true) {
        if (isOpen) return
        hideKeyboard()
        val metrics = activity.resources.displayMetrics
        val width = minOf(metrics.widthPixels * 2 / 3, (MAX_WIDTH_DP * metrics.density).toInt())
        drawer.layoutParams = drawer.layoutParams.apply { this.width = width }
        showAccount()

        scrim.isVisible = true
        drawer.isVisible = true
        if (animate) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(DURATION_MS).start()
            drawer.translationX = width.toFloat()
            drawer.animate().translationX(0f).setDuration(DURATION_MS).start()
        } else {
            scrim.alpha = 1f
            drawer.translationX = 0f
        }
    }

    fun close() {
        if (!isOpen) return
        scrim.animate().alpha(0f).setDuration(DURATION_MS).withEndAction { scrim.isVisible = false }.start()
        drawer.animate().translationX(drawer.width.toFloat()).setDuration(DURATION_MS)
            .withEndAction { drawer.isVisible = false }
            .start()
    }

    fun destroy() {
        account?.destroy()
        account = null
    }

    /** 써 보기 칸에서 치다가 ☰ 를 누르면 자판이 서랍을 가린다. 열 때 내린다. */
    private fun hideKeyboard() {
        val manager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(drawer.windowToken, 0)
    }

    // --- 계정 -----------------------------------------------------------------

    /**
     * 폰만 쓰는 사람에게는 로그인이 소용없다 — Play 가 알아서 구독을 복원한다. **윈도우·아이폰
     * 에서 같은 구독을 쓰려는 사람**을 위한 칸이다. 빌드에 클라이언트 ID 가 없으면 칸째 감춘다.
     */
    private fun bindAccount() {
        if (!Prefs.loginAvailable()) {
            activity.findViewById<View>(R.id.account_card).isVisible = false
            return
        }
        account = AccountManager(activity)

        val signIn = activity.findViewById<TextView>(R.id.signin_button)
        signIn.setOnClickListener {
            // 두 번 누르면 계정 창이 두 개 뜬다. 끝날 때까지 막아 둔다.
            signIn.isEnabled = false
            signIn.setText(R.string.setting_account_working)
            account?.signIn(activity) { result ->
                signIn.isEnabled = true
                signIn.setText(R.string.account_signin)
                accountSubscriber = if (result.signedIn) result.subscriber else null
                showAccount()
                result.message?.let { toast(it) }
            }
        }

        activity.findViewById<View>(R.id.signout_button).setOnClickListener {
            account?.signOut {
                accountSubscriber = null
                showAccount()
            }
        }

        activity.findViewById<View>(R.id.delete_button).setOnClickListener { confirmDelete() }
        showAccount()
    }

    /**
     * 탈퇴는 되돌릴 수 없어서 한 번 더 묻는다. **구독은 해지되지 않는다**는 말을 여기서
     * 꼭 한다 — 탈퇴하면 결제도 멈출 거라 믿고 나갔다가 다음 달 청구서를 보는 일이 없게.
     */
    private fun confirmDelete() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.account_delete_title)
            .setMessage(R.string.account_delete_message)
            .setNegativeButton(R.string.account_cancel, null)
            .setPositiveButton(R.string.account_delete_confirm) { _, _ ->
                val button = activity.findViewById<TextView>(R.id.delete_button)
                button.isEnabled = false
                account?.deleteAccount { result ->
                    button.isEnabled = true
                    if (!result.signedIn) accountSubscriber = null
                    showAccount()
                    toast(result.message ?: activity.getString(R.string.account_deleted))
                }
            }
            .show()
    }

    private fun showAccount() {
        if (!Prefs.loginAvailable()) return
        val signedIn = Prefs.signedIn(activity)
        activity.findViewById<View>(R.id.account_signed_out).isVisible = !signedIn
        activity.findViewById<View>(R.id.account_signed_in).isVisible = signedIn
        if (!signedIn) return

        val name = Prefs.profileName(activity).ifEmpty { activity.getString(R.string.account_name_unknown) }
        activity.findViewById<TextView>(R.id.profile_name).text = name

        // 사진이 있으면 사진, 없으면 이름 첫 글자를 동그라미에 얹는다.
        val photo = ProfilePhoto.load(activity)
        activity.findViewById<ImageView>(R.id.profile_photo).apply {
            isVisible = photo != null
            setImageBitmap(photo)
        }
        activity.findViewById<TextView>(R.id.profile_initial).text = name.take(1)

        // 서버에 묻지 않는다. 로그인할 때 받아 둔 답을 쓰고, 없으면 구매 토큰으로 짐작한다.
        val attached = accountSubscriber ?: Prefs.purchaseToken(activity).isNotEmpty()
        activity.findViewById<TextView>(R.id.account_status).setText(
            if (attached) R.string.setting_account_signed_in else R.string.setting_account_signed_in_free
        )
    }

    // --- 메뉴 -----------------------------------------------------------------

    private fun bindMenu() {
        activity.findViewById<View>(R.id.menu_keyboard).setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        activity.findViewById<View>(R.id.menu_privacy).setOnClickListener { openServerPage("/privacy") }
        activity.findViewById<View>(R.id.menu_terms).setOnClickListener { openServerPage("/terms") }
        activity.findViewById<View>(R.id.menu_support).setOnClickListener { writeToSupport() }
        activity.findViewById<View>(R.id.menu_guide).setOnClickListener { onGuide?.invoke() }
    }

    /** 서버가 내는 약관 페이지를 브라우저로 연다. 서버 주소가 없는 빌드에서는 열 곳이 없다. */
    private fun openServerPage(path: String) {
        if (!Prefs.serverAvailable()) {
            toast(activity.getString(R.string.open_failed))
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
        val to = activity.getString(R.string.support_email).trim()
        if (to.isEmpty()) {
            toast(activity.getString(R.string.support_not_ready))
            return
        }
        val body = activity.getString(
            R.string.support_body,
            versionName(),
            "${Build.MANUFACTURER} ${Build.MODEL}",
            Build.VERSION.RELEASE,
            Prefs.installId(activity),
        )
        open(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.support_subject))
                .putExtra(Intent.EXTRA_TEXT, body)
        )
    }

    private fun open(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast(activity.getString(R.string.open_failed))
        }
    }

    // --- 버전 ------------------------------------------------------------------

    /**
     * 맨 밑 흐린 한 줄. 버전만 보인다.
     *
     * 예전엔 설치 ID 도 여기 있었다(길게 눌러 복사). 사용자에게는 뜻 모를 숫자이고, 시험용
     * 구독자 목록(TEST_INSTALL_IDS)에 든 ID 가 스크린샷으로 퍼지면 남이 그 ID 로 AI 를 쓸 수
     * 있어서 뺐다. 문의 메일 밑에는 여전히 붙는다([writeToSupport]) — 시험 기기의 ID 가
     * 필요하면 고객센터를 눌러 메일 본문에서 보면 된다.
     */
    private fun showAppInfo() {
        activity.findViewById<TextView>(R.id.app_info).text = activity.getString(R.string.app_info, versionName())
    }

    private fun versionName(): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }
            .getOrNull().orEmpty()

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val DURATION_MS = 220L
        const val MAX_WIDTH_DP = 400
    }
}
