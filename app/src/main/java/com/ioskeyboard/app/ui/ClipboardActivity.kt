package com.ioskeyboard.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ioskeyboard.app.data.ClipItem
import com.ioskeyboard.app.data.ClipStore
import java.text.DateFormat
import java.util.Date

/** Full-screen manager for the durable, on-device clipboard history. */
class ClipboardActivity : AppCompatActivity() {
    private lateinit var store: ClipStore
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var countLabel: TextView
    private var filter = ""
    private var query = ""
    private val blue = 0xFF0A84FF.toInt()
    private val ink = 0xFF17191F.toInt()
    private val muted = 0xFF6A6F79.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ClipStore.get(this)
        buildShell()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildShell() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFFF5F6F9.toInt())
        }
        setContentView(outer)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(44)))
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(this).apply { text = "Papan klip"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink) })
        countLabel = TextView(this).apply { textSize = 11f; setTextColor(muted) }
        titleCol.addView(countLabel)
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = TextView(this).apply {
            text = "Bersihkan"
            textSize = 12f
            setTextColor(0xFFD43B45.toInt())
            setPadding(dp(8), dp(8), dp(4), dp(8))
            setOnClickListener { confirmClear() }
        }
        header.addView(clear)
        outer.addView(header)

        search = EditText(this).apply {
            hint = "Cari teks, tautan, email…"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(Color.WHITE, dp(13).toFloat(), 0xFFE1E4EA.toInt())
            setTextColor(ink)
            setHintTextColor(0xFF969BA5.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString().orEmpty(); refresh() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        outer.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(13) })

        val addCurrent = MaterialButton(this).apply {
            text = "＋  Simpan isi clipboard sekarang"
            isAllCaps = false
            textSize = 12f
            cornerRadius = dp(12)
            setBackgroundColor(0xFFEAF3FF.toInt())
            setTextColor(0xFF0A65CF.toInt())
            setOnClickListener { saveCurrentClipboard() }
        }
        outer.addView(addCurrent, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        val tabsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(11), 0, dp(10)) }
        val categories = listOf(
            "Semua" to "", "Disematkan" to ClipStore.FILTER_PINNED,
            "Teks" to ClipItem.CATEGORY_TEXT, "Link" to ClipItem.CATEGORY_LINK,
            "Email" to ClipItem.CATEGORY_EMAIL, "Catatan" to ClipItem.CATEGORY_NOTE,
            "Kode" to ClipItem.CATEGORY_CODE
        )
        categories.forEach { (label, value) ->
            val tab = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (filter == value) Color.WHITE else ink)
                background = rounded(if (filter == value) blue else Color.WHITE, dp(16).toFloat())
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setOnClickListener { filter = value; refresh(); updateTabs(tabs, categories) }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, dp(35)).apply { marginEnd = dp(6) })
        }
        tabsScroll.addView(tabs)
        outer.addView(tabsScroll)

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(1), 0, dp(14)) }
        scroll.addView(list)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun updateTabs(tabs: LinearLayout, categories: List<Pair<String, String>>) {
        tabs.removeAllViews()
        categories.forEach { (label, value) ->
            val tab = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (filter == value) Color.WHITE else ink)
                background = rounded(if (filter == value) blue else Color.WHITE, dp(16).toFloat())
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setOnClickListener { filter = value; refresh(); updateTabs(tabs, categories) }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, dp(35)).apply { marginEnd = dp(6) })
        }
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val items = store.list(category = filter.ifBlank { null }, query = query, limit = 1000)
        countLabel.text = "${store.count()} tersimpan · ${items.size} ditampilkan"
        if (items.isEmpty()) {
            val empty = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.WHITE)
            }
            empty.addView(TextView(this).apply {
                text = if (query.isNotBlank()) "Tidak ada hasil untuk ‘$query’." else "Belum ada riwayat.\nSalin teks, lalu buka keyboard agar tersimpan otomatis."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(20), dp(30), dp(20), dp(30))
            })
            list.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return
        }
        items.forEach { item -> list.addView(itemCard(item), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
    }

    private fun itemCard(item: ClipItem): View {
        val card = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeColor = 0xFFE8EAF0.toInt()
            strokeWidth = dp(1)
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(10), dp(12), dp(9)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "${item.displayTitle}  ·  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt))}"
            textSize = 10.5f
            setTextColor(muted)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val pin = TextView(this).apply {
            text = if (item.pinned) "★" else "☆"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(if (item.pinned) blue else 0xFF8B909A.toInt())
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { store.togglePinned(item.id); refresh() }
        }
        header.addView(pin, LinearLayout.LayoutParams(dp(34), dp(30)))
        column.addView(header)
        val content = TextView(this).apply {
            text = item.text
            textSize = 14f
            setTextColor(ink)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(8))
            setOnClickListener { copy(item.text) }
            setOnLongClickListener { showCategoryPicker(item); true }
        }
        column.addView(content)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val copy = actionButton("Salin") { copy(item.text) }
        val category = actionButton("Kategori") { showCategoryPicker(item) }
        val delete = actionButton("Hapus") { store.delete(item.id); refresh() }
        buttons.addView(copy, LinearLayout.LayoutParams(0, dp(35), 1f).apply { marginEnd = dp(5) })
        buttons.addView(category, LinearLayout.LayoutParams(0, dp(35), 1f).apply { marginEnd = dp(5) })
        buttons.addView(delete, LinearLayout.LayoutParams(0, dp(35), 1f))
        column.addView(buttons)
        card.addView(column)
        return card
    }

    private fun actionButton(label: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        cornerRadius = dp(10)
        setBackgroundColor(0xFFF2F4F7.toInt())
        setTextColor(if (label == "Hapus") 0xFFD43B45.toInt() else 0xFF333740.toInt())
        setOnClickListener { click() }
    }

    private fun showCategoryPicker(item: ClipItem) {
        val labels = arrayOf("Teks", "Tautan", "Email", "Catatan", "Kode")
        val values = arrayOf(ClipItem.CATEGORY_TEXT, ClipItem.CATEGORY_LINK, ClipItem.CATEGORY_EMAIL, ClipItem.CATEGORY_NOTE, ClipItem.CATEGORY_CODE)
        AlertDialog.Builder(this)
            .setTitle("Atur kategori")
            .setItems(labels) { _: DialogInterface, which: Int -> store.setCategory(item.id, values[which]); refresh() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Papan klip", text))
        Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
    }

    private fun saveCurrentClipboard() {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                return
            }
            val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
            if (text.isBlank()) Toast.makeText(this, "Tidak ada teks yang bisa disimpan", Toast.LENGTH_SHORT).show()
            else {
                store.save(text)
                Toast.makeText(this, "Tersimpan di perangkat", Toast.LENGTH_SHORT).show()
                refresh()
            }
        } catch (_: Exception) { Toast.makeText(this, "Android membatasi akses clipboard ini", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Bersihkan riwayat?")
            .setMessage(if (com.ioskeyboard.app.util.Prefs.get(this).keepClipboardOnClear) "Item yang tidak disematkan akan dihapus. Item favorit tetap aman." else "Semua riwayat clipboard akan dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Bersihkan") { _, _ -> store.clear(); refresh() }
            .show()
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; stroke?.let { setStroke(dp(1), it) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
