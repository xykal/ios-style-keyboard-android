package com.ioskeyboard.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ioskeyboard.app.floatwin.FloatingService
import com.ioskeyboard.app.net.TranslationLanguages
import com.ioskeyboard.app.util.Prefs

/** Compact but complete preferences screen; changes persist locally and apply on next keypress. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var page: LinearLayout
    private val ink = 0xFF17191F.toInt()
    private val muted = 0xFF686D78.toInt()
    private val blue = 0xFF0A84FF.toInt()
    private var pendingOverlay = ""
    private val sizeUpdateHandler = Handler(Looper.getMainLooper())
    private val sizeUpdateRunnable = Runnable { FloatingService.updateKeyboardSize(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlay.isNotEmpty() && Settings.canDrawOverlays(this)) {
            if (pendingOverlay == "bubble") {
                prefs.floatingBubbleEnabled = true
                FloatingService.startClipboard(this)
            } else prefs.floatingKeyboard = true
            pendingOverlay = ""
            build()
        }
    }

    private fun build() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            setBackgroundColor(0xFFF5F6F9.toInt())
        }
        scroll.addView(page)
        setContentView(scroll)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(42), dp(46)))
        val title = TextView(this).apply { text = "Pengaturan"; textSize = 23f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        bar.addView(title)
        page.addView(bar)
        addLabel("Atur keyboard sesuai cara mengetikmu.", 14f, muted, false, dp(3))

        section("Tata letak")
        addChoice("Mode keyboard", listOf("iOS", "QWERTY PC"), prefs.keyboardLayout) { index ->
            prefs.keyboardLayout = if (index == 0) Prefs.LAYOUT_IOS else Prefs.LAYOUT_PC
        }

        section("Mengetik")
        addSwitch("Prediksi kata", "Saran mengikuti awalan dan beberapa konteks kata umum; kamus tetap offline.", prefs.predictions) { prefs.predictions = it }
        addSwitch("Koreksi typo ringan", "Koreksi satu huruf yang meleset saat menekan spasi.", prefs.autoCorrect) { prefs.autoCorrect = it }
        addSwitch("Gesture / swipe typing", "Geser jari antar huruf untuk membentuk kata (offline).", prefs.swipeTyping) { prefs.swipeTyping = it }
        addSwitch("Kapital otomatis", "Huruf pertama kalimat dibuat kapital.", prefs.autoCapitalize) { prefs.autoCapitalize = it }
        addSwitch("Titik dengan spasi ganda", "Ketuk spasi dua kali untuk membuat '. '.", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
        addSwitch("Aksen tekan-lama", "Tahan a, e, i, o, u, c, n, s, atau y untuk pilihan aksen.", prefs.longPressAccents) { prefs.longPressAccents = it }
        addSwitch("Baris angka", "Tampilkan 1–0 di atas tombol huruf.", prefs.numberRow) { prefs.numberRow = it }
        addSwitch("Pratinjau tombol", "Lihat tombol yang ditekan sebelum menulis.", prefs.keyPopupPreview) { prefs.keyPopupPreview = it }

        section("Sentuhan & suara")
        addSwitch("Getar saat mengetik", "Getar singkat dan ringan setiap tombol ditekan.", prefs.hapticsEnabled) { prefs.hapticsEnabled = it }
        addSlider("Kekuatan getar", prefs.hapticStrength, 0, 100, "%") { prefs.hapticStrength = it }
        addSwitch("Suara tombol", "Gunakan efek suara sistem yang halus.", prefs.soundEnabled) { prefs.soundEnabled = it }
        addSlider("Volume suara", prefs.soundVolume, 0, 100, "%") { prefs.soundVolume = it }

        section("Tampilan")
        addChoice("Tema", listOf("Terang", "Gelap", "Otomatis", "Kustom"), prefs.theme) { prefs.theme = it }
        if (prefs.theme == Prefs.THEME_CUSTOM) {
            val backgrounds = listOf(0xFFE7E8EC.toInt(), 0xFFCBD5E1.toInt(), 0xFFDAEEFF.toInt(), 0xFF202127.toInt())
            val bgLabels = listOf("Perak", "Slate", "Biru", "Malam")
            val bgSelected = backgrounds.indexOf(prefs.customBackground).coerceAtLeast(0)
            addChoice("Latar kustom", bgLabels, bgSelected) { index ->
                prefs.customBackground = backgrounds[index]
                prefs.customKeyText = if (index == 3) Color.WHITE else ink
            }
            val keyColors = listOf(Color.WHITE, 0xFFF1F3F6.toInt(), 0xFFEAF3FF.toInt(), 0xFF3B3C41.toInt())
            val keyLabels = listOf("Putih", "Abu", "Biru", "Gelap")
            val keySelected = keyColors.indexOf(prefs.customKey).coerceAtLeast(0)
            addChoice("Warna tombol", keyLabels, keySelected) { index ->
                prefs.customKey = keyColors[index]
                prefs.customKeyText = if (index == 3) Color.WHITE else ink
            }
        }
        addLabel("Warna aksen", 13f, ink, true, dp(11))
        addAccentPalette()
        addSlider("Tinggi keyboard", prefs.keyboardHeightPercent, 75, 140, "%") { prefs.keyboardHeightPercent = it }
        addSlider("Ukuran tulisan tombol", prefs.keyFontSizePercent, 70, 140, "%") { prefs.keyFontSizePercent = it }
        addSlider("Sudut tombol", prefs.keyCornerRadiusDp, 0, 16, " dp") { prefs.keyCornerRadiusDp = it }
        addChoice("Mode satu tangan", listOf("Mati", "Kiri", "Kanan"), prefs.oneHandMode) { prefs.oneHandMode = it }

        section("Mengambang")
        addSwitch("Keyboard mengambang", "Buka keyboard sebagai panel terpisah yang bisa digeser di layar. Perlu izin overlay.", prefs.floatingKeyboard) { enabled ->
            if (enabled && !Settings.canDrawOverlays(this)) {
                prefs.floatingKeyboard = false
                pendingOverlay = "keyboard"
                askOverlay()
            } else {
                prefs.floatingKeyboard = enabled
                if (!enabled) FloatingService.stopKeyboard(this, reopenIme = false)
                toast(if (enabled) "Akan mengambang saat keyboard dibuka" else "Keyboard mengambang dimatikan")
            }
        }
        addSwitch("Tombol clipboard mengambang", "Pintasan papan klip bisa diseret ke tepi layar.", prefs.floatingBubbleEnabled) { enabled ->
            if (enabled && !Settings.canDrawOverlays(this)) {
                prefs.floatingBubbleEnabled = false
                pendingOverlay = "bubble"
                askOverlay()
            } else {
                prefs.floatingBubbleEnabled = enabled
                if (enabled) FloatingService.startClipboard(this) else FloatingService.stopClipboard(this)
            }
        }
        addSlider("Transparansi keyboard mengambang", prefs.floatingOpacity, 30, 100, "%") { prefs.floatingOpacity = it }
        addSlider("Lebar keyboard mengambang", prefs.floatingWidthPercent, 55, 100, "%") {
            prefs.floatingWidthPercent = it
            scheduleFloatingResize()
        }
        addSlider("Tinggi keyboard mengambang", prefs.floatingKeyboardHeightDp, 220, 480, " dp") {
            prefs.floatingKeyboardHeightDp = it
            scheduleFloatingResize()
        }
        addLabel("Ukuran berubah langsung jika overlay sedang terbuka. Layout QWERTY PC tetap memakai lebar layar penuh agar tombol F1–F12 mudah ditekan.", 11f, muted, false, dp(2))
        addSlider("Jarak dari bawah layar", prefs.floatingBottomMarginDp, 0, 240, " dp") {
            prefs.floatingBottomMarginDp = it
            scheduleFloatingResize()
        }

        section("Papan klip")
        addSwitch("Simpan riwayat otomatis", "Simpan teks atau gambar yang disalin saat keyboard aktif; salinan sensitif diabaikan.", prefs.clipboardCapture) { prefs.clipboardCapture = it }
        addSwitch("Pertahankan item disematkan", "Item favorit tetap ada saat membersihkan riwayat.", prefs.keepClipboardOnClear) { prefs.keepClipboardOnClear = it }
        addSlider("Batas item riwayat", prefs.clipboardMaxItems, 50, 2000, " item") { prefs.clipboardMaxItems = it }
        addButton("Buka riwayat clipboard", "Teks dan gambar disimpan lokal di perangkat") { startActivity(Intent(this, ClipboardActivity::class.java)) }

        section("Terjemahan")
        addLanguagePicker("Bahasa sumber", prefs.translateSource, allowAuto = true) {
            prefs.translateSource = it
        }
        addLanguagePicker("Bahasa tujuan", prefs.translateTarget, allowAuto = false) {
            prefs.translateTarget = it
        }
        addLabel("Pilih dari lebih dari 100 kode bahasa yang digunakan adapter MyMemory; ketersediaan tiap pasangan bahasa dapat berbeda. Teks hanya dikirim saat kamu meminta terjemahan. MyMemory membatasi permintaan anonim hingga 5.000 karakter per hari dan 500 byte per potongan; teks panjang dipecah otomatis.", 12f, muted, false, dp(5))

        section("Privasi")
        addLabel("• Prediksi dan koreksi berjalan lokal; kata sandi tidak dipelajari atau disimpan keyboard.\n• Gunakan Android Autofill/password manager untuk login dan sandi.\n• Riwayat clipboard disimpan lokal dan tidak disinkronkan ke cloud; salinan yang ditandai sensitif akan diabaikan.\n• Database aplikasi dikecualikan dari Android Backup. Terjemahan saja yang mengirim teks terpilih ke layanan online saat kamu memintanya.", 12f, muted, false, dp(4))
    }

    private fun section(title: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(22), 0, dp(8)) }
        val label = TextView(this).apply { text = title; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        row.addView(label)
        page.addView(row)
    }

    private fun addSwitch(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
        val card = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(10), dp(10), dp(10)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = title; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) })
        texts.addView(TextView(this).apply { text = description; textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = SwitchMaterial(this).apply {
            isChecked = checked
            showText = false
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(toggle)
        card.addView(row)
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun addSlider(title: String, value: Int, min: Int, max: Int, unit: String, onChanged: (Int) -> Unit) {
        val card = card()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(9), dp(13), dp(7)) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(this).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) }
        heading.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        val number = TextView(this).apply { text = "$value$unit"; textSize = 12f; setTextColor(blue) }
        heading.addView(number)
        column.addView(heading)
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = min + progress
                    number.text = "$current$unit"
                    if (fromUser) onChanged(current)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        column.addView(seek)
        card.addView(column)
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
    }

    private fun addChoice(title: String, values: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        addLabel(title, 13f, ink, true, dp(10))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
        values.forEachIndexed { index, label ->
            val button = MaterialButton(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                cornerRadius = dp(14)
                setTextColor(if (index == selected) Color.WHITE else ink)
                setBackgroundColor(if (index == selected) blue else Color.WHITE)
                strokeWidth = if (index == selected) 0 else dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFFDDE0E6.toInt())
                setOnClickListener { onSelected(index); build() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
        page.addView(row)
    }

    private fun addLanguagePicker(title: String, selectedCode: String, allowAuto: Boolean, onSelected: (String) -> Unit) {
        val selectedName = TranslationLanguages.find(selectedCode)?.name ?: selectedCode.uppercase()
        val button = MaterialButton(this).apply {
            text = "$title: $selectedName"
            textSize = 13f
            isAllCaps = false
            cornerRadius = dp(12)
            setBackgroundColor(Color.WHITE)
            setTextColor(ink)
            strokeWidth = dp(1)
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFDDE0E6.toInt())
            setOnClickListener { showLanguageDialog(title, selectedCode, allowAuto, onSelected) }
        }
        page.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
    }

    private fun showLanguageDialog(title: String, selectedCode: String, allowAuto: Boolean, onSelected: (String) -> Unit) {
        val options = if (allowAuto) TranslationLanguages.all else TranslationLanguages.targets
        val selectedCanonical = TranslationLanguages.find(selectedCode)?.code
        fun displayName(language: TranslationLanguages.Language): String =
            if (language.code == selectedCanonical) "${language.name} (dipilih)" else language.name
        val filtered = options.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map { displayName(it) }.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        val search = EditText(this).apply {
            hint = "Cari nama atau kode bahasa"
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(search, LinearLayout.LayoutParams(-1, dp(52)))
            addView(list, LinearLayout.LayoutParams(-1, dp(340)))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(content).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val language = filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            onSelected(language.code)
            build()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                filtered.clear()
                filtered.addAll(options.filter {
                    query.isBlank() || it.name.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
                })
                adapter.clear()
                adapter.addAll(filtered.map { displayName(it) })
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        dialog.show()
    }

    private fun scheduleFloatingResize() {
        sizeUpdateHandler.removeCallbacks(sizeUpdateRunnable)
        sizeUpdateHandler.postDelayed(sizeUpdateRunnable, 120L)
    }

    private fun addAccentPalette() {
        val colors = listOf(0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFAF52DE.toInt(), 0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF8E8E93.toInt())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(4)) }
        colors.forEach { color ->
            val dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); if (prefs.accentColor == color) setStroke(dp(3), Color.WHITE) }
                setOnClickListener { prefs.accentColor = color; build() }
            }
            row.addView(dot, LinearLayout.LayoutParams(0, dp(31), 1f).apply { marginEnd = dp(6) })
        }
        page.addView(row)
    }

    private fun addButton(title: String, description: String, onClick: () -> Unit) {
        val button = MaterialButton(this).apply {
            text = "$title  ·  $description"
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(12)
            setBackgroundColor(0xFFEAF3FF.toInt())
            setTextColor(0xFF0A65CF.toInt())
            setOnClickListener { onClick() }
        }
        page.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }

    private fun addLabel(text: String, size: Float, color: Int, bold: Boolean, top: Int) {
        val label = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        page.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = top })
    }

    private fun card(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(13).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.WHITE)
        strokeColor = 0xFFE8EAF0.toInt()
        strokeWidth = dp(1)
    }

    private fun askOverlay() {
        try {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")), REQUEST_OVERLAY)
        } catch (_: Exception) {
            toast("Izinkan overlay dari Pengaturan Android → Akses khusus aplikasi.")
        }
    }

    @Deprecated("Deprecated in Android API 33; kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY && Settings.canDrawOverlays(this)) {
            if (pendingOverlay == "bubble") {
                prefs.floatingBubbleEnabled = true
                FloatingService.startClipboard(this)
            } else if (pendingOverlay == "keyboard") prefs.floatingKeyboard = true
            pendingOverlay = ""
            build()
        }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object { private const val REQUEST_OVERLAY = 480 }
}
