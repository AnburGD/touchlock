package pl.anbur.applock

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Ustawienia: PIN, włączenie strażnika, wybór aplikacji do zablokowania. */
class MainActivity : Activity() {

    private data class App(val pkg: String, val label: String, val icon: Drawable)

    private var authorized = false
    private var leftAt = 0L
    private var allApps: List<App> = emptyList()
    private var shown: List<App> = emptyList()
    private var locked: MutableSet<String> = HashSet()
    private var adapter: AppsAdapter? = null
    private var statusView: TextView? = null
    private var statusButton: Button? = null
    private var restrictedHint: TextView? = null

    private val accent = Color.parseColor("#3949AB")
    private val ink = Color.parseColor("#1A1A2E")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    override fun onStart() {
        super.onStart()
        // Po minucie poza aplikacją znów trzeba podać PIN do ustawień
        if (authorized && SystemClock.elapsedRealtime() - leftAt > 60_000) authorized = false
        route()
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun route() {
        when {
            !Prefs.hasPin(this) -> showSetPin(first = true)
            !authorized -> showAskPin()
            adapter == null -> showMain()
            else -> updateStatus()
        }
    }

    // ---------------- Ekrany PIN ----------------

    private fun pinScreen(pad: PinPadView, subtitle: String?) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
        }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_lock) },
            LinearLayout.LayoutParams(dp(72), dp(72)))
        if (subtitle != null) {
            root.addView(TextView(this).apply {
                text = subtitle; textSize = 15f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#555570")); setPadding(dp(32), dp(12), dp(32), 0)
            })
        }
        root.addView(pad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        adapter = null
        setContentView(root)
    }

    private fun showSetPin(first: Boolean) {
        val pad = PinPadView(this, dark = false)
        var firstPin: String? = null
        pad.setTitle("Ustaw nowy PIN")
        pad.onSubmit = { p ->
            if (firstPin == null) {
                firstPin = p
                pad.setTitle("Powtórz PIN")
            } else if (firstPin == p) {
                Prefs.setPin(this, p)
                authorized = true
                Toast.makeText(this, "PIN zapisany", Toast.LENGTH_SHORT).show()
                showMain()
            } else {
                firstPin = null
                pad.setTitle("Ustaw nowy PIN")
                pad.setMessage("PIN-y się różnią, spróbuj jeszcze raz")
            }
        }
        pinScreen(pad, if (first) "Witaj! Najpierw ustaw PIN (4–8 cyfr).\nZapamiętaj go dobrze." else null)
    }

    private fun showAskPin() {
        val pad = PinPadView(this, dark = false)
        pad.setTitle("Podaj PIN, aby otworzyć ustawienia")
        pad.onSubmit = { p ->
            val left = Prefs.lockoutRemainingMs(this)
            when {
                left > 0 -> pad.setMessage("Za dużo prób. Poczekaj ${(left + 999) / 1000} s")
                Prefs.checkPin(this, p) -> { Prefs.resetFails(this); authorized = true; showMain() }
                Prefs.registerFail(this) -> pad.setMessage("Za dużo prób. Poczekaj 30 s")
                else -> pad.setMessage("Zły PIN")
            }
        }
        pinScreen(pad, null)
    }

    // ---------------- Główny ekran ----------------

    private fun showMain() {
        locked = Prefs.lockedApps(this).toMutableSet()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 26f; setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        // Karta statusu strażnika
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.parseColor("#E8EAF6"))
            }
        }
        statusView = TextView(this).apply { textSize = 16f; setTextColor(ink) }
        card.addView(statusView)
        restrictedHint = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#555570"))
            text = "Jeśli przełącznik jest szary („ustawienie z ograniczeniami”): " +
                "Informacje o aplikacji → ⋮ (w rogu) → „Zezwól na ustawienia z ograniczeniami”, " +
                "potem wróć tutaj."
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(restrictedHint)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusButton = smallButton("Włącz strażnika") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Znajdź „${getString(R.string.service_name)}” i włącz", Toast.LENGTH_LONG).show()
        }
        buttons.addView(statusButton)
        buttons.addView(smallButton("Info o aplikacji") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        card.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        header.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        header.addView(switchRow("Blokada włączona", Prefs.isEnabled(this)) { Prefs.setEnabled(this, it) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        header.addView(switchRow("Pytaj o PIN przy instalowaniu i odinstalowywaniu aplikacji",
            Prefs.protectInstaller(this)) { Prefs.setProtectInstaller(this, it) })

        header.addView(smallButton("Zmień PIN") { showSetPin(first = false) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        header.addView(TextView(this).apply {
            text = "💡 Zablokuj też „Ustawienia”, żeby nikt nie wyłączył strażnika."
            textSize = 13f; setTextColor(Color.parseColor("#555570")); setPadding(0, dp(10), 0, 0)
        })

        val search = EditText(this).apply {
            hint = "Szukaj aplikacji…"; setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
            })
        }
        header.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        header.addView(TextView(this).apply {
            text = "Zaznacz aplikacje do zablokowania:"; textSize = 15f; setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0)
        })

        val list = ListView(this).apply {
            divider = null
            setBackgroundColor(Color.WHITE)
            addHeaderView(header, null, false)
        }
        val a = AppsAdapter()
        adapter = a
        list.adapter = a
        list.setOnItemClickListener { _, _, position, _ ->
            val idx = position - list.headerViewsCount
            if (idx in shown.indices) toggle(shown[idx].pkg)
        }
        setContentView(list)
        updateStatus()
        loadApps()
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val res = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            val apps = ArrayList<App>()
            for (r in res) {
                val pkg = r.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                apps.add(App(pkg, r.loadLabel(pm).toString(), r.loadIcon(pm)))
            }
            val sorted = apps.sortedWith(compareBy<App>({ it.pkg !in locked }, { it.label.lowercase() }))
            runOnUiThread {
                allApps = sorted
                shown = sorted
                adapter?.notifyDataSetChanged()
            }
        }.start()
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) allApps else allApps.filter { it.label.contains(q.trim(), ignoreCase = true) }
        adapter?.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        val now = pkg !in locked
        if (now) locked.add(pkg) else locked.remove(pkg)
        Prefs.setLocked(this, pkg, now)
        adapter?.notifyDataSetChanged()
    }

    private fun isServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, AppLockService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun updateStatus() {
        val sv = statusView ?: return
        if (isServiceOn()) {
            sv.text = "✅ Strażnik działa"
            statusButton?.text = "Ustawienia dostępności"
            restrictedHint?.visibility = View.GONE
        } else {
            sv.text = "⚠️ Strażnik wyłączony — blokada nie działa"
            statusButton?.text = "Włącz strażnika"
            restrictedHint?.visibility = View.VISIBLE
        }
    }

    // ---------------- Pomocnicze widoki ----------------

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 14f
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(accent) }
        setOnClickListener { onClick() }
    }

    private fun switchRow(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; textSize = 15f; setTextColor(ink); isChecked = checked
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(context).apply { textSize = 16f; setTextColor(ink); setPadding(dp(14), 0, dp(8), 0) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(Switch(context).apply { isClickable = false; isFocusable = false })
            }
            val app = shown[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            (row.getChildAt(2) as Switch).isChecked = app.pkg in locked
            return row
        }
    }
}
