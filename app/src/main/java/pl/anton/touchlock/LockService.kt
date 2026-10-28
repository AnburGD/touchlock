package pl.anton.touchlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Сервис, который держит плавающую кнопку-замок поверх всех приложений
 * и при блокировке накрывает экран прозрачным слоем, съедающим все касания.
 */
class LockService : Service() {

    companion object {
        const val ACTION_SHOW = "pl.anton.touchlock.SHOW"
        const val ACTION_LOCK = "pl.anton.touchlock.LOCK"
        const val ACTION_UNLOCK = "pl.anton.touchlock.UNLOCK"
        const val ACTION_STOP = "pl.anton.touchlock.STOP"

        private const val CHANNEL = "touchlock"
        private const val NOTIF_ID = 1
        private const val DOUBLE_TAP_MS = 450L
        private const val PIN_PAD_TIMEOUT_MS = 20_000L

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var floatView: View? = null
    private var floatParams: WindowManager.LayoutParams? = null
    private var lockView: FrameLayout? = null
    private var hintView: TextView? = null
    private var pinPad: View? = null

    private var locked = false
    private var lastTap = 0L

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Блокировка касаний", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SHOW

        if (action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        goForeground()
        isRunning = true

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нет разрешения на показ поверх других приложений", Toast.LENGTH_LONG).show()
            stopEverything()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_SHOW -> showFloat()
            ACTION_LOCK -> lock()
            ACTION_UNLOCK -> requestUnlock()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeAllViews()
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- уведомление

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)

        if (locked) {
            val hasPin = Prefs.pin(this).isNotEmpty()
            b.setContentTitle("Экран заблокирован")
                .setContentText(if (hasPin) "Разблокировка по PIN-коду" else "Дважды нажми на замок, чтобы разблокировать")
                .addAction(notifAction("Разблокировать", ACTION_UNLOCK, 1))
        } else {
            b.setContentTitle("Замок готов")
                .setContentText("Нажми на плавающий замок, чтобы заблокировать касания")
                .addAction(notifAction("Заблокировать", ACTION_LOCK, 2))
                .addAction(notifAction("Выключить", ACTION_STOP, 3))
        }
        return b.build()
    }

    private fun notifAction(title: String, action: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, LockService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_lock), title, pi).build()
    }

    // ---------------------------------------------------------------- плавающая кнопка

    private fun showFloat() {
        floatView?.let {
            if (!locked) it.visibility = View.VISIBLE
            return
        }

        val size = dp(56)
        val btn = TextView(this).apply {
            text = "🔒"
            textSize = 24f
            gravity = Gravity.CENTER
            background = circle(Color.parseColor("#E6263238"), stroke = true)
        }

        val metrics = resources.displayMetrics
        val p = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.floatX(this@LockService, metrics.widthPixels - size - dp(12))
            y = Prefs.floatY(this@LockService, dp(220))
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = dp(8)

        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x; startY = p.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    v.alpha = 0.7f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        p.x = startX + dx
                        p.y = startY + dy
                        wm.updateViewLayout(v, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (moved) {
                        Prefs.saveFloatPos(this, p.x, p.y)
                    } else {
                        v.performClick()
                        lock()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        wm.addView(btn, p)
        floatView = btn
        floatParams = p
    }

    // ---------------------------------------------------------------- блокировка

    private fun lock() {
        if (locked) return
        if (floatView == null) showFloat()
        locked = true
        floatView?.visibility = View.GONE

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)
        // Главное: все касания по экрану попадают сюда и дальше не идут
        root.setOnTouchListener { _, _ -> true }

        val icon = TextView(this).apply {
            text = "🔒"
            textSize = 22f
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = circle(Color.parseColor("#99263238"), stroke = false)
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < DOUBLE_TAP_MS) {
                    lastTap = 0
                    requestUnlock()
                } else {
                    lastTap = now
                    flashHint("Нажми ещё раз, чтобы разблокировать")
                }
            }
        }
        root.addView(icon, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(64)
            rightMargin = dp(16)
        })

        val hint = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }
            visibility = View.GONE
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(96) })
        hintView = hint

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        wm.addView(root, p)
        lockView = root
        flashHint("Экран заблокирован · дважды нажми на 🔒")
        updateNotification()
    }

    private fun requestUnlock() {
        if (!locked) return
        val pin = Prefs.pin(this)
        if (pin.isEmpty()) unlock() else showPinPad(pin)
    }

    private fun unlock() {
        if (!locked) return
        locked = false
        handler.removeCallbacksAndMessages(null)
        pinPad = null
        hintView = null
        lockView?.let { safeRemove(it) }
        lockView = null
        floatView?.visibility = View.VISIBLE
        Toast.makeText(this, "Разблокировано", Toast.LENGTH_SHORT).show()
        updateNotification()
    }

    private fun flashHint(text: String) {
        val h = hintView ?: return
        h.text = text
        h.alpha = 1f
        h.visibility = View.VISIBLE
        h.animate().cancel()
        h.animate().alpha(0f).setStartDelay(2200).setDuration(600)
            .withEndAction { h.visibility = View.GONE }
            .start()
    }

    // ---------------------------------------------------------------- PIN

    private val closePadRunnable = Runnable { closePinPad() }

    private fun showPinPad(pin: String) {
        val root = lockView ?: return
        if (pinPad != null) return

        val entered = StringBuilder()

        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#F01E272C"))
            }
            isClickable = true
        }

        val title = TextView(this).apply {
            text = "Введи PIN"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val display = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, dp(8), 0, dp(12))
        }
        fun refresh() {
            display.text = "●".repeat(entered.length) + "○".repeat(pin.length - entered.length)
        }
        refresh()

        pad.addView(title)
        pad.addView(display)

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("✕", "0", "⌫")
        )
        for (row in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in row) {
                val key = TextView(this).apply {
                    text = k
                    setTextColor(Color.WHITE)
                    textSize = 26f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = circle(Color.parseColor("#33FFFFFF"), stroke = false)
                    setOnClickListener {
                        handler.removeCallbacks(closePadRunnable)
                        handler.postDelayed(closePadRunnable, PIN_PAD_TIMEOUT_MS)
                        when (k) {
                            "✕" -> closePinPad()
                            "⌫" -> {
                                if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                                refresh()
                            }
                            else -> {
                                if (entered.length < pin.length) entered.append(k)
                                refresh()
                                if (entered.length == pin.length) {
                                    if (entered.toString() == pin) {
                                        unlock()
                                    } else {
                                        entered.clear()
                                        title.text = "Неверный PIN"
                                        title.setTextColor(Color.parseColor("#FF8A80"))
                                        refresh()
                                    }
                                }
                            }
                        }
                    }
                }
                line.addView(key, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            pad.addView(line)
        }

        root.addView(pad, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        pinPad = pad
        handler.postDelayed(closePadRunnable, PIN_PAD_TIMEOUT_MS)
    }

    private fun closePinPad() {
        handler.removeCallbacks(closePadRunnable)
        val pad = pinPad ?: return
        lockView?.removeView(pad)
        pinPad = null
    }

    // ---------------------------------------------------------------- служебное

    private fun stopEverything() {
        removeAllViews()
        locked = false
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeAllViews() {
        handler.removeCallbacksAndMessages(null)
        lockView?.let { safeRemove(it) }
        floatView?.let { safeRemove(it) }
        lockView = null
        floatView = null
        floatParams = null
        pinPad = null
        hintView = null
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun circle(color: Int, stroke: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke) setStroke(dp(2), Color.WHITE)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
