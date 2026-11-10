package pl.anbur.applock

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Klawiatura PIN: kropki, komunikat i przyciski 0–9. */
class PinPadView(context: Context, private val dark: Boolean) : LinearLayout(context) {

    var onSubmit: ((String) -> Unit)? = null
    private val pin = StringBuilder()

    private val fg = if (dark) Color.WHITE else Color.parseColor("#1A1A2E")
    private val titleView = TextView(context)
    private val dotsView = TextView(context)
    private val msgView = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        titleView.apply {
            textSize = 20f; setTextColor(fg); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        dotsView.apply {
            textSize = 34f; setTextColor(fg); gravity = Gravity.CENTER
            letterSpacing = 0.3f; minHeight = dp(56)
        }
        msgView.apply {
            textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(28)
            setTextColor(Color.parseColor("#E57373"))
        }
        addView(titleView, lp())
        addView(dotsView, lp(top = 16))
        addView(msgView, lp(top = 4))

        val grid = GridLayout(context).apply { columnCount = 3; rowCount = 4 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        for (k in keys) {
            val b = Button(context).apply {
                text = k
                textSize = if (k == "OK") 18f else 24f
                setTextColor(if (k == "OK") Color.WHITE else fg)
                isAllCaps = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            k == "OK" -> Color.parseColor("#3949AB")
                            dark -> Color.parseColor("#2E2E48")
                            else -> Color.parseColor("#E8EAF6")
                        }
                    )
                }
                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    press(k)
                }
            }
            val size = dp(72)
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = size; height = size
                setMargins(dp(10), dp(8), dp(10), dp(8))
            })
        }
        addView(grid, lp(top = 16))
        refreshDots()
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
            "OK" -> {
                if (pin.length < 4) { setMessage("PIN musi mieć co najmniej 4 cyfry"); return }
                val p = pin.toString()
                clear()
                onSubmit?.invoke(p)
                return
            }
            else -> if (pin.length < 8) pin.append(k)
        }
        msgView.text = ""
        refreshDots()
    }

    private fun refreshDots() {
        dotsView.text = if (pin.isEmpty()) "–" else "●".repeat(pin.length)
    }

    fun setTitle(t: String) { titleView.text = t }
    fun setMessage(m: String) { msgView.text = m }
    fun clear() { pin.setLength(0); refreshDots() }

    private fun lp(top: Int = 0) = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top); gravity = Gravity.CENTER_HORIZONTAL }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
