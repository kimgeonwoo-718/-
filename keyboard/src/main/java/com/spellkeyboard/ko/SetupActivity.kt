package com.spellkeyboard.ko

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 첫 화면.
 *
 * 위 막대: 앱 이름 · 로그인 · 환경설정. 본문: 시작하기(켜기·선택) → 써 보기 → 사용법 →
 * 프리미엄. **처음 온 사람이 할 일만 둔다.** 자판·테마·배경 같은 것은 한 번 맞추면 잘 안
 * 만지므로 [SettingsActivity] 로 옮겼다 — 한 화면에 다 넣었더니 너무 길어져서 정작 해야 할
 * 일(키보드 켜기, 써 보기)이 묻혔다.
 *
 * 사용법이 써 보기 바로 밑인 이유: 쳐 보다가 "이건 뭐지" 싶을 때 눈이 가는 자리다.
 * 그 칸은 [ToolbarGuideView] 가 알아서 그리고 눌린 것을 가리키므로 여기서 묶을 것이 없다.
 */
class SetupActivity : AppCompatActivity() {

    private var quotaOutput: TextView? = null
    private var billing: BillingManager? = null
    private var account: AccountManager? = null

    /**
     * 로그인할 때 서버가 알려 준 구독 여부. 화면이 다시 만들어지면 사라진다(null) —
     * 그때는 이 폰이 들고 있는 구매 토큰으로 대신 짐작한다. 어느 쪽이든 **표시용**이고,
     * 실제 판단은 교정할 때마다 서버가 Play 에 물어 한다.
     */
    private var accountSubscriber: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 앱 화면도 키보드와 같은 밝기를 따른다. 키보드는 어두운데 앱만 하얗게 뜨면 어색하다.
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this).nightMode())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        // 환경설정에서 테마를 바꾸고 돌아오면 이 화면도 통째로 다시 만들어진다. 스크롤
        // 위치를 살려 놓지 않으면 맨 위로 튄다. 레이아웃이 끝난 뒤에 옮겨야 한다.
        savedInstanceState?.getInt(KEY_SCROLL, 0)?.takeIf { it > 0 }?.let { y ->
            findViewById<View>(R.id.setup_scroll).post { findViewById<View>(R.id.setup_scroll).scrollTo(0, y) }
        }

        bindTopBar()
        bindSetup()
        bindPremium()
    }

    override fun onResume() {
        super.onResume()
        // 시스템 설정에서 켜고 돌아오면 '완료' 로 바뀌어야 하고, 키보드에서 AI 를 쓰고
        // 돌아오면 요금 상태가 새로 보여야 한다.
        showSetupProgress()
        showQuota()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SCROLL, findViewById<View>(R.id.setup_scroll).scrollY)
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        account?.destroy()
        account = null
        super.onDestroy()
    }

    // --- 위 막대 ---------------------------------------------------------------

    private fun bindTopBar() {
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        bindAccount()
    }

    /**
     * 로그인 버튼.
     *
     * 폰만 쓰는 사람에게는 아무 소용이 없다 — Play 가 알아서 복원해 준다. **윈도우·아이폰에서
     * 같은 구독을 쓰려는 사람**을 위한 것이라 본문이 아니라 위 막대에 작게 둔다. 빌드에
     * 클라이언트 ID 가 없으면 통째로 감춘다(눌러 봐야 오류만 난다).
     *
     * 로그인 전: 누르면 바로 구글 계정 창. 로그인 뒤: "내 계정" — 누르면 상태와 로그아웃.
     */
    private fun bindAccount() {
        val button = findViewById<TextView>(R.id.account_button)
        if (!Prefs.loginAvailable()) {
            button.isVisible = false
            return
        }
        account = AccountManager(this)
        button.setOnClickListener {
            if (Prefs.signedIn(this)) showAccountDialog() else signIn(button)
        }
        showAccount()
    }

    private fun signIn(button: TextView) {
        // 두 번 누르면 계정 창이 두 개 뜬다. 끝날 때까지 막아 둔다.
        button.isEnabled = false
        button.setText(R.string.setting_account_working)
        account?.signIn(this) { result ->
            button.isEnabled = true
            accountSubscriber = if (result.signedIn) result.subscriber else null
            showAccount()
            val message = result.message ?: if (result.signedIn) getString(accountStatus()) else null
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun showAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.account_dialog_title)
            .setMessage(accountStatus())
            .setPositiveButton(R.string.account_close, null)
            .setNegativeButton(R.string.account_signout) { _, _ ->
                account?.signOut {
                    accountSubscriber = null
                    showAccount()
                }
            }
            .show()
    }

    private fun showAccount() {
        findViewById<TextView>(R.id.account_button)?.setText(
            if (Prefs.signedIn(this)) R.string.top_account else R.string.top_login
        )
    }

    /**
     * 로그인 상태 한 줄.
     *
     * **여기서 서버에 묻지 않는다.** 화면에 들어올 때마다 두드리면 하는 일 없이 요청만
     * 쌓인다. 로그인할 때 받아 둔 답([accountSubscriber])을 쓰고, 그것이 없으면 이 폰이
     * 구매 토큰을 들고 있는지로 짐작한다.
     */
    private fun accountStatus(): Int {
        val attached = accountSubscriber ?: Prefs.purchaseToken(this).isNotEmpty()
        return if (attached) R.string.setting_account_signed_in else R.string.setting_account_signed_in_free
    }

    // --- 시작하기 -------------------------------------------------------------

    private fun bindSetup() {
        findViewById<View>(R.id.enable_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<View>(R.id.pick_button).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    /** 두 단계가 어디까지 됐는지 시스템에 물어 표시한다. 사용자가 기억할 필요가 없다. */
    private fun showSetupProgress() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty().startsWith(packageName)

        markStep(findViewById(R.id.enable_button), enabled)
        markStep(findViewById(R.id.pick_button), selected)
        findViewById<View>(R.id.setup_all_done).isVisible = enabled && selected
    }

    private fun markStep(pill: TextView, done: Boolean) {
        if (done) {
            pill.setText(R.string.setup_done)
            pill.setBackgroundResource(R.drawable.bg_badge)
            pill.setTextColor(ContextCompat.getColor(this, R.color.toss_blue))
        } else {
            pill.setText(R.string.setup_open)
            pill.setBackgroundResource(R.drawable.bg_button_secondary)
            pill.setTextColor(ContextCompat.getColor(this, R.color.toss_text))
        }
    }

    // --- 프리미엄 ---------------------------------------------------------------

    private fun bindPremium() {
        quotaOutput = findViewById(R.id.quota_output)
        val billingStatus = findViewById<TextView>(R.id.billing_status)
        billing = BillingManager(this) { message ->
            runOnUiThread {
                billingStatus.text = message
                showQuota()
            }
        }.also { it.start() }
        // 결제창을 바로 띄우지 않는다. 무엇을 얼마에 사는지 먼저 보여주는 화면을 거친다.
        findViewById<View>(R.id.subscribe_button).setOnClickListener {
            startActivity(Intent(this, PaywallActivity::class.java))
        }
    }

    /**
     * 요금 상태 한 줄.
     *
     * **남은 양은 안 보여준다.** 숫자가 줄어드는 것을 보면 아껴 쓰게 된다 — 쓰라고 만든
     * 기능에 쓰지 말라는 표시를 달아 둔 꼴이다. 한도는 원가를 막는 장치이지 사용자가
     * 신경 쓸 것이 아니고, 실제로 닿는 사람도 거의 없다(카톡 한 줄이면 하루 500번).
     * 닿으면 그때 서버가 알려 준다.
     *
     * 판단은 서버가 한다. 여기 있는 것은 "지금 어느 요금제인가" 뿐이다.
     */
    private fun showQuota() {
        val view = quotaOutput ?: return
        if (!Prefs.serverAvailable()) {
            view.setText(R.string.setting_quota_no_server)
            return
        }
        val quota = Prefs.lastQuota(this)
        view.text = when {
            // 무료는 AI 를 쓰지 않는다. 무엇이 프리미엄인지를 말한다.
            quota?.plan == FREE_PLAN -> getString(R.string.setting_quota_free)
            else -> getString(R.string.setting_quota_unlimited)
        }
    }

    companion object {
        /** 서버가 헤더로 알려 주는 요금제 이름. */
        private const val FREE_PLAN = "free"

        private const val KEY_SCROLL = "scroll_y"
    }
}
