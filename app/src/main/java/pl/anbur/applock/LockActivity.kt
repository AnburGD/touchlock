package pl.anbur.applock

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Ekran z PIN-em, który zasłania zablokowaną aplikację. */
class LockActivity : Activity() {

    companion object {
        const val EXTRA_TARGET = "target"
    }

    private var target: String = ""
    private lateinit var pad: PinPadView
    private lateinit var icon: ImageView
    private lateinit var name: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nie pokazuj zawartości na zrzutach ekranu / w podglądzie ostatnich
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.parseColor("#151526")
        window.navigationBarColor = Color.parseColor("#151526")

        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#151526"))
        }
        icon = ImageView(this)
        root.addView(icon, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()))
        name = TextView(this).apply {
            textSize = 16f; setTextColor(Color.parseColor("#B0B0C8")); gravity = Gravity.CENTER
        }
        root.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (8 * d).toInt() })

        pad = PinPadView(this, dark = true)
        pad.setTitle("Wpisz PIN")
        pad.onSubmit = { tryPin(it) }
        root.addView(pad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (24 * d).toInt() })
        setContentView(root)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        target = i?.getStringExtra(EXTRA_TARGET) ?: ""
        pad.clear()
        pad.setMessage("")
        try {
            val pm = packageManager
            val info = pm.getApplicationInfo(target, 0)
            icon.setImageDrawable(pm.getApplicationIcon(info))
            name.text = pm.getApplicationLabel(info)
        } catch (_: PackageManager.NameNotFoundException) {
            icon.setImageResource(R.drawable.ic_lock)
            name.text = "Instalowanie / odinstalowywanie"
        }
        showLockoutIfAny()
    }

    private fun tryPin(p: String) {
        if (Prefs.lockoutRemainingMs(this) > 0) { showLockoutIfAny(); return }
        if (Prefs.checkPin(this, p)) {
            Prefs.resetFails(this)
            AppLockService.unlockedPackage = target
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } else {
            if (Prefs.registerFail(this)) showLockoutIfAny()
            else pad.setMessage("Zły PIN")
        }
    }

    private fun showLockoutIfAny() {
        handler.removeCallbacksAndMessages(null)
        val left = Prefs.lockoutRemainingMs(this)
        if (left <= 0) return
        pad.setMessage("Za dużo prób. Poczekaj ${(left + 999) / 1000} s")
        handler.postDelayed({
            if (Prefs.lockoutRemainingMs(this) > 0) showLockoutIfAny() else pad.setMessage("")
        }, 1000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
