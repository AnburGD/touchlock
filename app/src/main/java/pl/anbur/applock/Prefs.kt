package pl.anbur.applock

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** Wszystkie ustawienia: PIN (jako hash), lista zablokowanych aplikacji, licznik błędów. */
object Prefs {
    private const val NAME = "applock"
    private const val K_HASH = "pin_hash"
    private const val K_SALT = "pin_salt"
    private const val K_LOCKED = "locked_apps"
    private const val K_ENABLED = "enabled"
    private const val K_INSTALLER = "protect_installer"
    private const val K_FAILS = "fails"
    private const val K_LOCKOUT = "lockout_until"

    const val MAX_FAILS = 5
    const val LOCKOUT_MS = 30_000L

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------- PIN ----------
    fun hasPin(c: Context) = p(c).contains(K_HASH)

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        p(c).edit().putString(K_SALT, salt).putString(K_HASH, hash(salt, pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val salt = p(c).getString(K_SALT, null) ?: return false
        val stored = p(c).getString(K_HASH, null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var data = (salt + pin).toByteArray()
        repeat(5000) { data = md.digest(data) } // spowalnia zgadywanie
        return data.toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ---------- Błędne próby ----------
    fun lockoutRemainingMs(c: Context): Long =
        (p(c).getLong(K_LOCKOUT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Zwraca true, jeśli po tym błędzie włączyła się blokada czasowa. */
    fun registerFail(c: Context): Boolean {
        val fails = p(c).getInt(K_FAILS, 0) + 1
        return if (fails >= MAX_FAILS) {
            p(c).edit().putInt(K_FAILS, 0)
                .putLong(K_LOCKOUT, System.currentTimeMillis() + LOCKOUT_MS).apply()
            true
        } else {
            p(c).edit().putInt(K_FAILS, fails).apply()
            false
        }
    }

    fun resetFails(c: Context) = p(c).edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, 0L).apply()

    // ---------- Aplikacje ----------
    fun lockedApps(c: Context): MutableSet<String> =
        HashSet(p(c).getStringSet(K_LOCKED, emptySet()) ?: emptySet())

    fun setLocked(c: Context, pkg: String, locked: Boolean) {
        val s = lockedApps(c)
        if (locked) s.add(pkg) else s.remove(pkg)
        p(c).edit().putStringSet(K_LOCKED, s).apply()
    }

    fun isEnabled(c: Context) = p(c).getBoolean(K_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean(K_ENABLED, v).apply()

    fun protectInstaller(c: Context) = p(c).getBoolean(K_INSTALLER, false)
    fun setProtectInstaller(c: Context, v: Boolean) = p(c).edit().putBoolean(K_INSTALLER, v).apply()
}
