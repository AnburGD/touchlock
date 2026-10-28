package pl.anton.touchlock

import android.content.Context

/** Простое хранилище настроек: PIN и позиция плавающей кнопки. */
object Prefs {
    private const val FILE = "touchlock"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun pin(ctx: Context): String = sp(ctx).getString("pin", "") ?: ""

    fun setPin(ctx: Context, pin: String) {
        sp(ctx).edit().putString("pin", pin).apply()
    }

    fun floatX(ctx: Context, def: Int): Int = sp(ctx).getInt("fx", def)
    fun floatY(ctx: Context, def: Int): Int = sp(ctx).getInt("fy", def)

    fun saveFloatPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt("fx", x).putInt("fy", y).apply()
    }
}
