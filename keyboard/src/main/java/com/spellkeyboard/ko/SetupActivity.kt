package com.spellkeyboard.ko

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * 첫 화면.
 *
 * 위 막대: 앱 이름과 ☰(더보기). 본문: 시작하기(켜기·선택) → 써 보기 → 사용법 → 프리미엄.
 * **처음 온 사람이 할 일만 둔다.** 로그인·키보드 맞춤설정·약관·고객센터는 ☰ 를 누르면
 * 오른쪽에서 나오는 [MoreDrawer] 에 모았다 — 한 화면에 다 넣었더니 너무 길어져서 정작 해야 할
 * 일(키보드 켜기, 써 보기)이 묻혔고, 위 막대에 버튼을 늘어놓았더니 지저분했다.
 *
 * 사용법이 써 보기 바로 밑인 이유: 쳐 보다가 "이건 뭐지" 싶을 때 눈이 가는 자리다.
 * 그 칸은 [ToolbarGuideView] 가 알아서 그리고 눌린 것을 가리키므로 여기서 묶을 것이 없다.
 */
class SetupActivity : AppCompatActivity() {

    private var quotaOutput: TextView? = null
    private var billing: BillingManager? = null
    private var more: MoreDrawer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 앱 화면도 키보드와 같은 밝기를 따른다. 키보드는 어두운데 앱만 하얗게 뜨면 어색하다.
        AppCompatDelegate.setDefaultNightMode(Prefs.themeMode(this).nightMode())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        // 키보드 맞춤설정에서 테마를 바꾸고 돌아오면 이 화면도 통째로 다시 만들어진다. 스크롤
        // 위치를 살려 놓지 않으면 맨 위로 튄다. 레이아웃이 끝난 뒤에 옮겨야 한다.
        savedInstanceState?.getInt(KEY_SCROLL, 0)?.takeIf { it > 0 }?.let { y ->
            findViewById<View>(R.id.setup_scroll).post { findViewById<View>(R.id.setup_scroll).scrollTo(0, y) }
        }

        more = MoreDrawer(this).also { drawer ->
            findViewById<View>(R.id.menu_button).setOnClickListener { drawer.open() }
            // 서랍에서 키보드 맞춤설정으로 가 테마를 바꾸고 돌아오면 이 화면이 통째로 다시
            // 만들어진다. 서랍을 연 채로 되살려야 "어디 갔지" 가 안 된다.
            if (savedInstanceState?.getBoolean(KEY_MORE_OPEN) == true) drawer.open(animate = false)
        }
        bindSetup()
        bindPremium()

        // 처음 깐 사람에게는 안내를 먼저 보여 준다. 이 화면은 그 밑에 깔려 있다가 안내를
        // 닫으면 드러난다. 화면이 다시 만들어질 때(테마 변경 등)는 다시 띄우지 않는다.
        if (savedInstanceState == null && !Prefs.onboardingSeen(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
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
        outState.putBoolean(KEY_MORE_OPEN, more?.isOpen == true)
    }

    /**
     * 서랍이 열려 있으면 뒤로 가기는 서랍만 닫는다. 앱이 통째로 닫히면 당황스럽다.
     *
     * 옛 방식(onBackPressed)이지만 여기 한 곳뿐이라 이걸로 둔다. 앱이 새 뒤로 가기 방식
     * (enableOnBackInvokedCallback)을 켜지 않아서 그대로 불린다.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val drawer = more
        if (drawer != null && drawer.isOpen) drawer.close() else super.onBackPressed()
    }

    override fun onDestroy() {
        billing?.destroy()
        billing = null
        more?.destroy()
        more = null
        super.onDestroy()
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
        private const val KEY_MORE_OPEN = "more_open"
    }
}
