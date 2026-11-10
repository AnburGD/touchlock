package pl.anbur.applock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * "Strażnik": dostaje informację, która aplikacja wyszła na wierzch.
 * Jeśli jest na liście zablokowanych — pokazuje ekran PIN.
 */
class AppLockService : AccessibilityService() {

    companion object {
        /** Aplikacja odblokowana PIN-em — wolna, dopóki z niej nie wyjdziesz albo nie zgaśnie ekran. */
        @Volatile var unlockedPackage: String? = null

        /** Pakiet okna instalatora/odinstalowywania (różny na różnych telefonach). */
        fun installerPackages(c: Context): Set<String> {
            val pm = c.packageManager
            val out = HashSet<String>()
            val probes = listOf(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:" + c.packageName)),
                Intent(Intent.ACTION_VIEW).setDataAndType(
                    Uri.parse("content://x/y.apk"), "application/vnd.android.package-archive"
                )
            )
            for (i in probes) {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(i, 0).forEach { out.add(it.activityInfo.packageName) }
            }
            out.addAll(
                listOf(
                    "com.google.android.packageinstaller",
                    "com.android.packageinstaller",
                    "com.samsung.android.packageinstaller"
                )
            )
            return out
        }
    }

    /** Pakiety, które traktujemy jako „prawdziwe” aplikacje (pomijamy klawiaturę, pasek systemowy itp.). */
    private var relevant: Set<String> = emptySet()
    private var installers: Set<String> = emptySet()
    private var lastLockPkg: String? = null
    private var lastLockTime = 0L
    private var lastRefresh = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            unlockedPackage = null // zgasł ekran -> wszystko znów zablokowane
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshRelevant()
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenOffReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, f)
        }
    }

    private fun refreshRelevant() {
        val pm = packageManager
        val s = HashSet<String>()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0).forEach { s.add(it.activityInfo.packageName) }
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(home, 0).forEach { s.add(it.activityInfo.packageName) }
        installers = installerPackages(this)
        s.addAll(installers)
        relevant = s
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val locked = Prefs.lockedApps(this)
        if (pkg !in relevant && pkg !in locked) {
            // może to świeżo zainstalowana aplikacja — odśwież listę (najwyżej co 15 s)
            val now = SystemClock.elapsedRealtime()
            if (now - lastRefresh > 15_000) { lastRefresh = now; refreshRelevant() }
            if (pkg !in relevant) return
        }

        // Przeszliśmy do innej aplikacji -> poprzednio odblokowana znowu zablokowana
        if (pkg != unlockedPackage) unlockedPackage = null

        if (!Prefs.isEnabled(this) || !Prefs.hasPin(this)) return
        val mustLock = pkg in locked || (Prefs.protectInstaller(this) && pkg in installers)
        if (mustLock && unlockedPackage == null) showLock(pkg)
    }

    private fun showLock(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastLockPkg && now - lastLockTime < 600) return
        lastLockPkg = pkg
        lastLockTime = now
        val i = Intent(this, LockActivity::class.java)
            .putExtra(LockActivity.EXTRA_TARGET, pkg)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        startActivity(i)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
