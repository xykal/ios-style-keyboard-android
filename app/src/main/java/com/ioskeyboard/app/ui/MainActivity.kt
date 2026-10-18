package com.ioskeyboard.app.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ioskeyboard.app.R
import com.ioskeyboard.app.floatwin.FloatingService
import com.ioskeyboard.app.ime.KeyboardIME
import com.ioskeyboard.app.util.Prefs

/** Setup dashboard: enable/select the IME, test typing and reach clipboard/settings. */
class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var prefs: Prefs
    private var testField: EditText? = null
    private var pendingOverlayAction: String? = null
    private val blue = 0xFF0A84FF.toInt()
    private val ink = 0xFF17191F.toInt()
    private val muted = 0xFF666B76.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        buildUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) buildUi()
        if (pendingOverlayAction != null && Settings.canDrawOverlays(this)) {
            when (pendingOverlayAction) {
                ACTION_CLIP -> { prefs.floatingBubbleEnabled = true; FloatingService.startClipboard(this) }
                ACTION_KB -> prefs.floatingKeyboard = true
            }
            pendingOverlayAction = null
            buildUi()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            setBackgroundColor(0xFFF5F6F9.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        val mark = TextView(this).apply {
            text = "⌨"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(blue, dp(17).toFloat())
        }
        root.addView(mark, LinearLayout.LayoutParams(dp(58), dp(58)).apply { bottomMargin = dp(16) })
        addText("iOS Keyboard", 30f, ink, true)
        addText("Rasa keyboard iPhone, dibuat untuk Android.", 15f, muted, false, dp(5))

        val enabled = isImeEnabled()
        val selected = isImeSelected()
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = 0xFFE5E7EC.toInt()
            strokeWidth = dp(1)
        }
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(8), dp(15), dp(12))
        }
        addStep(steps, if (enabled) "✓" else "1", "Aktifkan keyboard", if (enabled) "Sudah aktif di pengaturan Android" else "Android akan meminta konfirmasi keamanan.", enabled)
        addStep(steps, if (selected) "✓" else "2", "Pilih iOS Keyboard", if (selected) "Keyboard ini sedang dipakai" else "Pilih 'iOS Keyboard' dari daftar metode input.", selected)
        card.addView(steps)
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        if (!enabled) addButton(root, "1  Aktifkan di pengaturan Android", "primary") {
            try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        } else if (!selected) addButton(root, "2  Pilih sebagai keyboard", "primary") {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } else {
            addButton(root, "✓  Keyboard siap — coba ketik", "primary") { focusTestField() }
        }

        addText("COBA LANGSUNG", 11f, muted, true, dp(23))
        val edit = EditText(this).apply {
            hint = "Ketuk di sini untuk mencoba keyboard…"
            textSize = 15f
            minLines = 2
            maxLines = 3
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, dp(14).toFloat(), 0xFFE0E3EA.toInt())
            setTextColor(ink)
            setHintTextColor(0xFF9297A2.toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        testField = edit
        root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        addText("FITUR UTAMA", 11f, muted, true, dp(22))
        addFeatureCard(root, "▤", "Papan klip tersimpan", "Cari, sematkan, dan pakai lagi teks yang pernah disalin.") {
            startActivity(Intent(this, ClipboardActivity::class.java))
        }
        addFeatureCard(root, "⤢", "Panel mengambang", "Buka papan klip atau keyboard sebagai panel yang bisa digeser.") {
            if (Settings.canDrawOverlays(this)) {
                prefs.floatingBubbleEnabled = true
                FloatingService.startClipboard(this)
                toast("Panel papan klip mengambang aktif")
            } else {
                pendingOverlayAction = ACTION_CLIP
                requestOverlayPermission()
            }
        }
        addFeatureCard(root, "⚙", "Atur keyboard", "Bahasa, swipe, koreksi, getar, suara, tema, dan tinggi tombol.") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val privacy = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFEAF3FF.toInt())
        }
        val privacyText = TextView(this).apply {
            text = "Privasi: prediksi, swipe, dan riwayat clipboard disimpan di perangkat. Internet hanya dipakai saat kamu menekan Terjemahkan; teks yang dipilih dikirim ke layanan terjemahan."
            textSize = 12f
            setTextColor(0xFF31577C.toInt())
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setLineSpacing(0f, 1.12f)
        }
        privacy.addView(privacyText)
        root.addView(privacy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        addText("Tip: riwayat clipboard mulai menangkap teks saat keyboard aktif. Item yang disematkan tidak ikut terhapus saat membersihkan riwayat.", 12f, muted, false, dp(14))
    }

    private fun addStep(parent: LinearLayout, number: String, title: String, detail: String, done: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        val badge = TextView(this).apply {
            text = number
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (done) Color.WHITE else blue)
            background = rounded(if (done) 0xFF26A269.toInt() else 0xFFEAF3FF.toInt(), dp(18).toFloat())
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = TextView(this).apply { text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        val desc = TextView(this).apply { text = detail; textSize = 11.5f; setTextColor(muted); setPadding(0, dp(2), 0, 0) }
        texts.addView(heading); texts.addView(desc)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun addFeatureCard(parent: LinearLayout, icon: String, title: String, detail: String, click: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = 0xFFE8EAF0.toInt()
            strokeWidth = dp(1)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(11), dp(12), dp(11)) }
        val badge = TextView(this).apply { text = icon; textSize = 20f; gravity = Gravity.CENTER; setTextColor(blue); background = rounded(0xFFEAF3FF.toInt(), dp(12).toFloat()) }
        row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply { text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) })
        copy.addView(TextView(this).apply { text = detail; textSize = 11.5f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(0xFF8B909B.toInt()) })
        card.addView(row)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun addButton(parent: LinearLayout, text: String, style: String, click: () -> Unit) {
        val button = MaterialButton(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(13)
            if (style == "primary") {
                setBackgroundColor(blue)
                setTextColor(Color.WHITE)
            }
            setOnClickListener { click() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
    }

    private fun addText(text: String, size: Float, color: Int, bold: Boolean, top: Int = 0) {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
    }

    private fun focusTestField() {
        val field = testField ?: return
        field.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun isImeEnabled(): Boolean {
        val service = ComponentName(this, KeyboardIME::class.java).flattenToShortString()
        val full = ComponentName(this, KeyboardIME::class.java).flattenToString()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.id == service || it.id == full }
    }

    private fun isImeSelected(): Boolean {
        val value = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        return value.contains("${packageName}/.ime.KeyboardIME") || value.contains("${packageName}/com.ioskeyboard.app.ime.KeyboardIME")
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            } catch (_: Exception) { toast("Buka Pengaturan Android → Akses khusus aplikasi → Tampil di atas aplikasi lain.") }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Android API 33; kept for compatibility with the min SDK")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY && Settings.canDrawOverlays(this)) {
            when (pendingOverlayAction) {
                ACTION_CLIP -> { prefs.floatingBubbleEnabled = true; FloatingService.startClipboard(this) }
                ACTION_KB -> prefs.floatingKeyboard = true
            }
            pendingOverlayAction = null
            buildUi()
        }
    }

    companion object {
        private const val REQUEST_OVERLAY = 401
        private const val REQUEST_NOTIFICATIONS = 402
        private const val ACTION_CLIP = "clip"
        private const val ACTION_KB = "keyboard"
    }
}
