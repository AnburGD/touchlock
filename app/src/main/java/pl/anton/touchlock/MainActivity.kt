package pl.anton.touchlock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var permStatus: TextView
    private lateinit var permButton: Button
    private lateinit var pinStatus: TextView
    private lateinit var pinInput: EditText
    private lateinit var removePinButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = Settings.canDrawOverlays(this)
        permStatus.text = if (granted) "✅ Разрешение есть" else "❌ Нужно разрешение «Поверх других приложений»"
        permButton.visibility = if (granted) View.GONE else View.VISIBLE

        val pin = Prefs.pin(this)
        pinStatus.text = if (pin.isEmpty()) {
            "PIN не установлен — разблокировка двойным нажатием на замок"
        } else {
            "PIN установлен (" + pin.length + " цифр) — после двойного нажатия спросит PIN"
        }
        removePinButton.visibility = if (pin.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startLockService(action: String) {
        val i = Intent(this, LockService::class.java).setAction(action)
        startForegroundService(i)
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        col.addView(TextView(this).apply {
            text = "🔒 Touch Lock"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Блокирует касания экрана поверх любого приложения — YouTube, мультики, игры."
            textSize = 15f
            setPadding(0, dp(4), 0, dp(8))
        })

        // 1. Разрешение
        col.addView(header("1. Разрешение"))
        permStatus = TextView(this).apply { textSize = 15f }
        col.addView(permStatus)
        permButton = button("Дать разрешение") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        col.addView(permButton)

        // 2. Запуск
        col.addView(header("2. Запуск"))
        col.addView(button("Показать плавающий замок") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Сначала дай разрешение (пункт 1)")
                return@button
            }
            startLockService(LockService.ACTION_SHOW)
            toast("Замок появился. Открой нужное приложение и нажми на 🔒")
            moveTaskToBack(true)
        })
        col.addView(button("Выключить замок") {
            startService(Intent(this, LockService::class.java).setAction(LockService.ACTION_STOP))
            toast("Замок выключен")
        })

        // 3. PIN
        col.addView(header("3. PIN-код (по желанию)"))
        pinStatus = TextView(this).apply { textSize = 15f }
        col.addView(pinStatus)
        pinInput = EditText(this).apply {
            hint = "4–8 цифр"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }
        col.addView(pinInput)
        col.addView(button("Сохранить PIN") {
            val pin = pinInput.text.toString().trim()
            if (pin.length !in 4..8 || !pin.all { it.isDigit() }) {
                toast("PIN — от 4 до 8 цифр")
                return@button
            }
            Prefs.setPin(this, pin)
            pinInput.setText("")
            toast("PIN сохранён")
            refresh()
        })
        removePinButton = button("Убрать PIN") {
            Prefs.setPin(this, "")
            toast("PIN убран")
            refresh()
        }
        col.addView(removePinButton)

        // Инструкция
        col.addView(header("Как пользоваться"))
        col.addView(TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.2f)
            text = """
                • Нажми «Показать плавающий замок» — на экране появится кнопка 🔒. Её можно перетаскивать пальцем.
                • Открой видео или игру и нажми на 🔒 — касания экрана заблокированы, экран не гаснет.
                • Разблокировать: дважды быстро нажми на полупрозрачный замок в правом верхнем углу (или кнопка «Разблокировать» в уведомлении).
                • Если установлен PIN — после двойного нажатия появится клавиатура для PIN.
                • Забыл PIN? Просто перезагрузи телефон — блокировка снимется.
                • Кнопку питания и системные жесты (домой/назад) обычное приложение заблокировать не может.
            """.trimIndent()
        })

        return ScrollView(this).apply { addView(col) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#2E7D6B"))
        setPadding(0, dp(20), 0, dp(6))
        gravity = Gravity.START
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
