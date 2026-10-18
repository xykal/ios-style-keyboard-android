package com.ioskeyboard.app.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.ioskeyboard.app.data.ClipItem
import com.ioskeyboard.app.util.Prefs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A deliberately lightweight, Canvas-rendered keyboard. Drawing keys in one view keeps
 * touch latency low and avoids a large tree of Android buttons in every text field.
 */
class KeyboardSurface @JvmOverloads constructor(
    context: Context,
    private val actions: KeyboardActions? = null,
    private val overlay: Boolean = false,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { KEYBOARD, SYMBOLS, EMOJI, CLIPBOARD, TRANSLATE, TOOLS }

    private enum class HitKind { TEXT, BACKSPACE, ENTER, SPACE, SHIFT, LAYOUT, ACTION, CANDIDATE, EMOJI, CLIP, PIN, CATEGORY, ACCENT }
    private data class Hit(
        val kind: HitKind,
        val rect: RectF,
        val value: String = "",
        val secondary: String = "",
        val clip: ClipItem? = null
    )

    private data class PcKey(
        val label: String,
        val kind: HitKind = HitKind.TEXT,
        val value: String = label,
        val secondary: String = "",
        val units: Float = 1f
    )

    private data class Palette(
        val background: Int,
        val key: Int,
        val specialKey: Int,
        val text: Int,
        val secondary: Int,
        val accent: Int,
        val divider: Int,
        val pressed: Int,
        val card: Int
    )

    private val prefs = Prefs.get(context)
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val hits = ArrayList<Hit>(80)
    private var suggestions: List<String> = emptyList()
    private var clips: List<ClipItem> = emptyList()
    private var clipFilter: String = ""
    private var clipOffset = 0f
    private var mode = Mode.KEYBOARD
    private var shift = true
    private var capsLock = false
    private var symbolPage = 0
    private var emojiCategory = 0
    private var emojiOffset = 0f
    private var translationSource = ""
    private var translationResult = ""
    private var translationStatus = ""
    private var translationTarget = "id"
    private var translationLoading = false
    private var languageLabel = "Indonesia"
    private var panelScrollBounds = RectF()
    private var scrollingPanel = false
    private var touchStartInScrollArea = false
    private var pressed: Hit? = null
    private var accentHits: List<Hit> = emptyList()
    private var accentOpen = false
    private var accentSelected = ""
    private var isSwipe = false
    private var swipePoints = ArrayList<Pair<Float, Float>>(100)
    private var swipeSignature = StringBuilder()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var longPressHandled = false
    private var cursorDrag = false
    private var cursorLastX = 0f
    private var backspaceRepeating = false
    private var repeatDelete: Runnable? = null
    private var overlayCloseBounds = RectF()
    private var overlayDragBounds = RectF()

    private val light = Palette(
        background = 0xFFE7E8EC.toInt(), key = 0xFFFDFDFE.toInt(),
        specialKey = 0xFFBFC2CA.toInt(), text = 0xFF16171B.toInt(),
        secondary = 0xFF666A73.toInt(), accent = prefs.accentColor,
        divider = 0x33747782, pressed = 0xFFD4D7DE.toInt(), card = 0xFFF1F2F5.toInt()
    )
    private val dark = Palette(
        background = 0xFF222327.toInt(), key = 0xFF3B3C41.toInt(),
        specialKey = 0xFF303137.toInt(), text = 0xFFF7F7F8.toInt(),
        secondary = 0xFFB9BBC2.toInt(), accent = prefs.accentColor,
        divider = 0x338F929A, pressed = 0xFF56585E.toInt(), card = 0xFF2B2C31.toInt()
    )

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        minimumHeight = dp(270)
        contentDescription = "iOS Keyboard"
    }

    fun setSuggestions(words: List<String>) {
        suggestions = words.take(3)
        invalidate()
    }

    fun setClips(items: List<ClipItem>, filter: String = clipFilter) {
        clips = items
        clipFilter = filter
        clipOffset = clipOffset.coerceAtMost(max(0f, clips.size * dp(62) - height.toFloat()))
        invalidate()
    }

    fun setLanguageLabel(label: String) {
        languageLabel = label
        invalidate()
    }

    fun setTranslation(source: String, target: String, result: String, status: String = "", loading: Boolean = false) {
        translationSource = source
        translationTarget = target
        translationResult = result
        translationStatus = status
        translationLoading = loading
        invalidate()
    }

    fun setMode(value: Mode) {
        mode = value
        pressed = null
        accentOpen = false
        if (value == Mode.CLIPBOARD) actions?.onClipsRequested(clipFilter)
        if (value == Mode.KEYBOARD || value == Mode.SYMBOLS) actions?.onSuggestionsRequested()
        invalidate()
    }

    fun getMode(): Mode = mode

    fun setShifted(value: Boolean) {
        shift = value
        if (!value) capsLock = false
        invalidate()
    }

    fun setCapsLock(value: Boolean) {
        capsLock = value
        shift = value
        invalidate()
    }

    fun isInOverlay(): Boolean = overlay

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val target = dp((if (overlay) 292 else 286) * prefs.keyboardHeightPercent / 100)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(target, MeasureSpec.getSize(heightMeasureSpec))
            else -> target.coerceAtLeast(dp(230))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hits.clear()
        val colors = palette()
        val w = width.toFloat()
        val h = height.toFloat()
        if (!overlay) canvas.drawColor(colors.background)
        if (overlay) {
            // The overlay window itself is movable; a floating card gives it an iOS-like surface.
            p.color = 0x00000000
            canvas.drawColor(0x00000000)
            val card = RectF(dp(3).toFloat(), dp(3).toFloat(), w - dp(3), h - dp(3))
            p.color = withAlpha(colors.background, prefs.floatingOpacity)
            p.setShadowLayer(dp(12).toFloat(), 0f, dp(4).toFloat(), 0x66000000)
            canvas.drawRoundRect(card, dp(18).toFloat(), dp(18).toFloat(), p)
            p.clearShadowLayer()
            p.color = colors.background
            p.alpha = prefs.floatingOpacity * 255 / 100
            canvas.drawRoundRect(card, dp(18).toFloat(), dp(18).toFloat(), p)
            p.alpha = 255
            overlayDragBounds = RectF(card.left, card.top, card.right - dp(48), card.top + dp(22))
            overlayCloseBounds = RectF(card.right - dp(43), card.top, card.right, card.top + dp(27))
            p.color = colors.secondary
            canvas.drawRoundRect(RectF(w / 2 - dp(24), card.top + dp(5), w / 2 + dp(24), card.top + dp(8)), dp(2).toFloat(), dp(2).toFloat(), p)
            drawIcon(canvas, "close", overlayCloseBounds, colors.secondary)
            hits += Hit(HitKind.ACTION, overlayCloseBounds, "CLOSE_FLOAT_KEYBOARD")
        }

        val oneHand = if (overlay || prefs.keyboardLayout == Prefs.LAYOUT_PC) 0 else prefs.oneHandMode
        val keyboardWidth = if (oneHand == 0) w else w * 0.78f
        val left = when {
            overlay -> dp(7).toFloat()
            oneHand == 2 -> w - keyboardWidth
            else -> 0f
        }
        val right = if (overlay) w - dp(7) else left + keyboardWidth
        val top = if (overlay) dp(23).toFloat() else 0f
        val bottom = if (overlay) h - dp(5).toFloat() else h
        val headerH = dp(39).toFloat()
        val isNumberMode = mode == Mode.KEYBOARD && prefs.numberRow
        val numberH = if (isNumberMode) dp(29).toFloat() else 0f
        val bottomH = dp(47).toFloat()
        val gap = dp(5).toFloat()
        val rowGap = dp(7).toFloat()
        val contentBottom = bottom - dp(3)
        val rowsTop = top + headerH + numberH + dp(3)
        val rowsHeight = contentBottom - rowsTop - bottomH - rowGap
        val rowH = ((rowsHeight - 2 * rowGap) / 3f).coerceAtLeast(dp(24).toFloat())

        drawHeader(canvas, left, right, top, headerH, colors)

        if (mode == Mode.KEYBOARD) {
            if (prefs.keyboardLayout == Prefs.LAYOUT_PC) {
                drawPcKeyboard(canvas, left, right, top + headerH, contentBottom, colors)
            } else {
                var y = top + headerH
                if (isNumberMode) {
                    drawNumberRow(canvas, left, right, y, numberH, gap, colors)
                    y += numberH + dp(3)
                }
                drawKeyboardRows(canvas, left, right, y, rowH, rowGap, gap, colors, symbols = false)
                drawBottomRow(canvas, left, right, contentBottom - bottomH, bottomH, gap, colors, keyboard = true)
            }
        } else if (mode == Mode.SYMBOLS) {
            drawKeyboardRows(canvas, left, right, top + headerH + dp(4), rowH, rowGap, gap, colors, symbols = true)
            drawBottomRow(canvas, left, right, contentBottom - bottomH, bottomH, gap, colors, keyboard = false)
        } else {
            val panelTop = top + headerH + dp(2)
            val panelBottom = contentBottom - bottomH - dp(2)
            when (mode) {
                Mode.EMOJI -> drawEmojiPanel(canvas, left, right, panelTop, panelBottom, colors)
                Mode.CLIPBOARD -> drawClipboardPanel(canvas, left, right, panelTop, panelBottom, colors)
                Mode.TRANSLATE -> drawTranslatePanel(canvas, left, right, panelTop, panelBottom, colors)
                Mode.TOOLS -> drawToolsPanel(canvas, left, right, panelTop, panelBottom, colors)
                else -> Unit
            }
            drawPanelBottom(canvas, left, right, contentBottom - bottomH, bottomH, gap, colors)
        }
        if (prefs.keyPopupPreview && mode == Mode.KEYBOARD && !accentOpen && pressed?.kind == HitKind.TEXT) drawPopupPreview(canvas, colors)
        if (accentOpen && accentHits.isNotEmpty()) drawAccentPopup(canvas, colors)
    }

    private fun palette(): Palette {
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (prefs.theme) {
            Prefs.THEME_DARK -> dark.copy(accent = prefs.accentColor)
            Prefs.THEME_LIGHT -> light.copy(accent = prefs.accentColor)
            Prefs.THEME_CUSTOM -> Palette(
                background = prefs.customBackground,
                key = prefs.customKey,
                specialKey = shade(prefs.customBackground, 0.85f),
                text = prefs.customKeyText,
                secondary = shade(prefs.customKeyText, 0.72f),
                accent = prefs.accentColor,
                divider = 0x33747782,
                pressed = shade(prefs.customKey, 0.88f),
                card = shade(prefs.customBackground, 1.05f)
            )
            else -> if (night) dark.copy(accent = prefs.accentColor) else light.copy(accent = prefs.accentColor)
        }
    }

    private fun drawHeader(canvas: Canvas, left: Float, right: Float, y: Float, h: Float, c: Palette) {
        val r = dp(13).toFloat()
        val toolRect = RectF(left + dp(5), y + dp(5), left + dp(43), y + h - dp(5))
        drawPill(canvas, toolRect, c.card, r)
        drawIcon(canvas, "more", toolRect, c.secondary)
        hits += Hit(HitKind.ACTION, toolRect, "TOOLS")

        when (mode) {
            Mode.KEYBOARD, Mode.SYMBOLS -> {
                val rightButtonW = dp(38).toFloat()
                val clipRect = RectF(right - dp(82), y + dp(5), right - dp(43), y + h - dp(5))
                val trRect = RectF(right - dp(42), y + dp(5), right - dp(3), y + h - dp(5))
                drawPill(canvas, clipRect, c.card, r)
                drawPill(canvas, trRect, c.card, r)
                drawIcon(canvas, "clipboard", clipRect, c.text)
                drawIcon(canvas, "translate", trRect, c.text)
                hits += Hit(HitKind.ACTION, clipRect, "CLIPBOARD")
                hits += Hit(HitKind.ACTION, trRect, "TRANSLATE")
                val candidateLeft = toolRect.right + dp(3)
                val candidateRight = clipRect.left - dp(3)
                val cellW = (candidateRight - candidateLeft) / 3f
                val fallbackIcons = listOf("emoji", "language", "settings")
                val fallbackActions = listOf("EMOJI", "LANGUAGE", "SETTINGS")
                for (index in 0 until 3) {
                    val rect = RectF(candidateLeft + index * cellW, y + dp(4), candidateLeft + (index + 1) * cellW, y + h - dp(4))
                    if (index > 0) {
                        p.color = c.divider
                        p.strokeWidth = dp(1).toFloat()
                        canvas.drawLine(rect.left, rect.top + dp(8), rect.left, rect.bottom - dp(8), p)
                    }
                    if (index < suggestions.size) {
                        val word = suggestions[index]
                        drawText(canvas, word, rect.centerX(), rect.centerY(), dp(15).toFloat(), c.text, false, maxWidth = rect.width() - dp(8))
                        hits += Hit(HitKind.CANDIDATE, rect, word)
                    } else {
                        drawIcon(canvas, fallbackIcons[index], rect, c.secondary)
                        hits += Hit(HitKind.ACTION, rect, fallbackActions[index])
                    }
                }
            }
            Mode.EMOJI -> {
                drawText(canvas, "Emoji", left + dp(54), y + h / 2, dp(14).toFloat(), c.text, true, Paint.Align.LEFT)
                val search = RectF(right - dp(88), y + dp(4), right - dp(44), y + h - dp(4))
                val close = RectF(right - dp(42), y + dp(4), right - dp(3), y + h - dp(4))
                drawPill(canvas, search, c.card, r)
                drawPill(canvas, close, c.card, r)
                drawIcon(canvas, "search", search, c.text)
                drawIcon(canvas, "close", close, c.secondary)
                hits += Hit(HitKind.ACTION, search, "EMOJI_SEARCH")
                hits += Hit(HitKind.ACTION, close, "KEYBOARD")
            }
            Mode.CLIPBOARD -> {
                drawText(canvas, "Papan klip", left + dp(54), y + h / 2, dp(14).toFloat(), c.text, true, Paint.Align.LEFT)
                val search = RectF(right - dp(88), y + dp(4), right - dp(44), y + h - dp(4))
                val close = RectF(right - dp(42), y + dp(4), right - dp(3), y + h - dp(4))
                drawPill(canvas, search, c.card, r)
                drawPill(canvas, close, c.card, r)
                drawIcon(canvas, "search", search, c.text)
                drawIcon(canvas, "close", close, c.secondary)
                hits += Hit(HitKind.ACTION, search, "CLIPBOARD_HISTORY")
                hits += Hit(HitKind.ACTION, close, "KEYBOARD")
            }
            Mode.TRANSLATE -> {
                drawText(canvas, "Terjemahkan", left + dp(54), y + h / 2, dp(14).toFloat(), c.text, true, Paint.Align.LEFT)
                val target = RectF(right - dp(88), y + dp(4), right - dp(44), y + h - dp(4))
                val close = RectF(right - dp(42), y + dp(4), right - dp(3), y + h - dp(4))
                drawPill(canvas, target, c.card, r)
                drawPill(canvas, close, c.card, r)
                drawText(canvas, translationTarget.uppercase(), target.centerX(), target.centerY(), dp(12).toFloat(), c.accent, true)
                drawIcon(canvas, "close", close, c.secondary)
                hits += Hit(HitKind.ACTION, target, "TRANSLATE_SWAP")
                hits += Hit(HitKind.ACTION, close, "KEYBOARD")
            }
            Mode.TOOLS -> {
                drawText(canvas, "Akses cepat", left + dp(54), y + h / 2, dp(14).toFloat(), c.text, true, Paint.Align.LEFT)
                val close = RectF(right - dp(42), y + dp(4), right - dp(3), y + h - dp(4))
                drawPill(canvas, close, c.card, r)
                drawIcon(canvas, "close", close, c.secondary)
                hits += Hit(HitKind.ACTION, close, "KEYBOARD")
            }
        }
    }

    private fun drawNumberRow(canvas: Canvas, left: Float, right: Float, y: Float, h: Float, gap: Float, c: Palette) {
        val count = 10
        val kw = (right - left - gap * (count - 1)) / count
        for (i in 0 until count) {
            val rect = RectF(left + i * (kw + gap), y + dp(1), left + i * (kw + gap) + kw, y + h - dp(1))
            drawKey(canvas, rect, "${(i + 1) % 10}", false, false, c, radius = dp(5).toFloat())
            hits += Hit(HitKind.TEXT, rect, "${(i + 1) % 10}")
        }
    }

    private fun drawKeyboardRows(
        canvas: Canvas, left: Float, right: Float, top: Float,
        rowH: Float, rowGap: Float, gap: Float, c: Palette, symbols: Boolean
    ) {
        val width = right - left
        val row1 = if (!symbols) "qwertyuiop".map { it.toString() } else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row2 = if (!symbols) "asdfghjkl".map { it.toString() } else listOf("@", "#", "$", "&", "*", "-", "+", "(", ")")
        val row3 = if (!symbols) "zxcvbnm".map { it.toString() } else if (symbolPage == 0) listOf("%", "\"", "'", ":", ";", "!", "?") else listOf("\\", "|", "~", "<", ">", "€", "£")
        val keyW = (width - gap * 9) / 10f
        val rows = listOf(row1, row2, row3)
        val yStarts = listOf(top, top + rowH + rowGap, top + (rowH + rowGap) * 2)

        rows.forEachIndexed { rowIndex, row ->
            val y = yStarts[rowIndex]
            when (rowIndex) {
                0 -> row.forEachIndexed { i, text ->
                    val rect = RectF(left + i * (keyW + gap), y, left + i * (keyW + gap) + keyW, y + rowH)
                    val label = if (!symbols && (shift || capsLock)) text.uppercase() else text
                    drawKey(canvas, rect, label, pressedMatches(HitKind.TEXT, text), false, c)
                    hits += Hit(HitKind.TEXT, rect, if (!symbols && (shift || capsLock)) text.uppercase() else text)
                }
                1 -> {
                    val start = left + (width - (keyW * row.size + gap * (row.size - 1))) / 2f
                    row.forEachIndexed { i, text ->
                        val rect = RectF(start + i * (keyW + gap), y, start + i * (keyW + gap) + keyW, y + rowH)
                        val label = if (!symbols && (shift || capsLock)) text.uppercase() else text
                        drawKey(canvas, rect, label, pressedMatches(HitKind.TEXT, text), false, c)
                        hits += Hit(HitKind.TEXT, rect, if (!symbols && (shift || capsLock)) text.uppercase() else text)
                    }
                }
                else -> {
                    val wide = (keyW * 1.42f)
                    val rowWidth = wide * 2 + keyW * row.size + gap * (row.size + 1)
                    var x = left + (width - rowWidth) / 2f
                    val shiftRect = RectF(x, y, x + wide, y + rowH)
                    drawKey(canvas, shiftRect, if (symbols) "=\\<" else "", pressedMatches(HitKind.SHIFT, "shift"), true, c)
                    if (!symbols) drawIcon(canvas, "shift", shiftRect, c.text)
                    hits += if (symbols) Hit(HitKind.LAYOUT, shiftRect, "SYMBOL_PAGE") else Hit(HitKind.SHIFT, shiftRect, "SHIFT")
                    x += wide + gap
                    row.forEach { text ->
                        val rect = RectF(x, y, x + keyW, y + rowH)
                        val label = if (!symbols && (shift || capsLock)) text.uppercase() else text
                        drawKey(canvas, rect, label, pressedMatches(HitKind.TEXT, text), false, c)
                        hits += Hit(HitKind.TEXT, rect, if (!symbols && (shift || capsLock)) text.uppercase() else text)
                        x += keyW + gap
                    }
                    val backRect = RectF(x, y, x + wide, y + rowH)
                    drawKey(canvas, backRect, "", pressedMatches(HitKind.BACKSPACE, "back"), true, c)
                    drawIcon(canvas, "backspace", backRect, c.text)
                    hits += Hit(HitKind.BACKSPACE, backRect)
                }
            }
        }
    }

    private fun drawPcKeyboard(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, c: Palette) {
        val rowGap = dp(3).toFloat()
        val rows = listOf(
            listOf(PcKey("Esc", HitKind.ACTION, "PC_KEY_ESCAPE", units = 1.15f)) +
                (1..12).map { PcKey("F$it", HitKind.ACTION, "PC_KEY_F$it", units = 0.9f) },
            listOf(
                PcKey("`", secondary = "~"), PcKey("1", secondary = "!"),
                PcKey("2", secondary = "@"), PcKey("3", secondary = "#"),
                PcKey("4", secondary = 36.toChar().toString()), PcKey("5", secondary = "%"),
                PcKey("6", secondary = "^"), PcKey("7", secondary = "&"),
                PcKey("8", secondary = "*"), PcKey("9", secondary = "("),
                PcKey("0", secondary = ")"), PcKey("-", secondary = "_"),
                PcKey("=", secondary = "+"),
                PcKey("⌫", HitKind.BACKSPACE, "PC_BACKSPACE", units = 1.55f)
            ),
            listOf(PcKey("Tab", HitKind.ACTION, "PC_KEY_TAB", units = 1.4f)) +
                "qwertyuiop".map { PcKey(it.toString()) } +
                listOf(PcKey("[", secondary = "{"), PcKey("]", secondary = "}"), PcKey(92.toChar().toString(), secondary = "|", units = 1.15f)),
            listOf(PcKey("Caps", HitKind.ACTION, "PC_CAPS_LOCK", units = 1.55f)) +
                "asdfghjkl".map { PcKey(it.toString()) } +
                listOf(PcKey(";", secondary = ":"), PcKey(39.toChar().toString(), secondary = 34.toChar().toString()), PcKey("Enter", HitKind.ENTER, "PC_ENTER", units = 1.5f)),
            listOf(PcKey("Shift", HitKind.SHIFT, "pc_shift_left", units = 1.85f)) +
                "zxcvbnm".map { PcKey(it.toString()) } +
                listOf(PcKey(",", secondary = "<"), PcKey(".", secondary = ">"), PcKey("/", secondary = "?"), PcKey("Shift", HitKind.SHIFT, "pc_shift_right", units = 1.85f)),
            listOf(
                PcKey("Ctrl", HitKind.ACTION, "PC_MOD_CTRL", units = 1.35f),
                PcKey("Fn", HitKind.ACTION, "PC_KEY_FUNCTION", units = 1.0f),
                PcKey("Alt", HitKind.ACTION, "PC_MOD_ALT", units = 1.05f),
                PcKey("Win", HitKind.ACTION, "PC_MOD_META", units = 1.15f),
                PcKey("Space", HitKind.SPACE, "", units = 3.7f),
                PcKey("Alt", HitKind.ACTION, "PC_MOD_ALT", units = 1.05f),
                PcKey("Ctrl", HitKind.ACTION, "PC_MOD_CTRL", units = 1.35f),
                PcKey("←", HitKind.ACTION, "PC_KEY_DPAD_LEFT", units = 0.9f),
                PcKey("↓", HitKind.ACTION, "PC_KEY_DPAD_DOWN", units = 0.9f),
                PcKey("↑", HitKind.ACTION, "PC_KEY_DPAD_UP", units = 0.9f),
                PcKey("→", HitKind.ACTION, "PC_KEY_DPAD_RIGHT", units = 0.9f)
            )
        )
        val availableHeight = (bottom - top - rowGap * (rows.size - 1)).coerceAtLeast(dp(6).toFloat())
        val rowH = availableHeight / rows.size
        rows.forEachIndexed { index, row ->
            val y = top + index * (rowH + rowGap)
            drawPcRow(canvas, row, left, right, y, rowH, c, index)
        }
    }

    private fun drawPcRow(canvas: Canvas, keys: List<PcKey>, left: Float, right: Float, y: Float, h: Float, c: Palette, rowIndex: Int) {
        val gap = dp(if (rowIndex == 0) 2 else 3).toFloat()
        val totalUnits = keys.sumOf { it.units.toDouble() }.toFloat()
        val unit = (right - left - gap * (keys.size - 1)) / totalUnits
        var x = left
        keys.forEach { key ->
            val w = unit * key.units
            val rect = RectF(x, y, x + w, y + h)
            val letter = key.label.length == 1 && key.label[0].isLetter()
            val display = when {
                letter && (shift || capsLock) -> key.label.uppercase()
                !letter && shift && key.secondary.isNotEmpty() -> key.secondary
                else -> key.label
            }
            val value = when {
                key.kind != HitKind.TEXT -> key.value
                letter -> key.label.lowercase()
                shift && key.secondary.isNotEmpty() -> key.secondary
                else -> key.value
            }
            val pressedValue = if (key.kind == HitKind.SPACE) "" else value
            val special = key.kind != HitKind.TEXT && key.kind != HitKind.SPACE
            val font = when (rowIndex) {
                0 -> dp(9).toFloat()
                5 -> if (key.label.length > 2) dp(8).toFloat() else dp(13).toFloat()
                else -> if (key.label.length > 1) dp(10).toFloat() else dp(15).toFloat()
            }
            drawKey(canvas, rect, if (key.kind == HitKind.BACKSPACE) "" else display, pressedMatches(key.kind, pressedValue), special, c, radius = dp(4).toFloat(), font = font)
            if (key.kind == HitKind.BACKSPACE) drawIcon(canvas, "backspace", rect, c.text)
            hits += if (key.kind == HitKind.SPACE) Hit(HitKind.SPACE, rect)
            else Hit(key.kind, rect, value)
            x += w + gap
        }
    }

    private fun drawBottomRow(canvas: Canvas, left: Float, right: Float, y: Float, h: Float, gap: Float, c: Palette, keyboard: Boolean) {
        val totalUnits = 9.15f
        val unit = (right - left - gap * 6) / totalUnits
        var x = left
        val entries = if (keyboard) listOf(
            Triple("123", 1.25f, "LAYOUT_SYMBOLS"), Triple("", 0.85f, "EMOJI"),
            Triple(",", 0.70f, "TEXT_COMMA"), Triple(languageLabel, 3.20f, "SPACE"),
            Triple(".", 0.70f, "TEXT_PERIOD"), Triple("", 0.85f, "LANGUAGE"), Triple("", 1.25f, "ENTER")
        ) else listOf(
            Triple("ABC", 1.25f, "KEYBOARD"), Triple("", 0.85f, "EMOJI"),
            Triple(",", 0.70f, "TEXT_COMMA"), Triple(languageLabel, 3.20f, "SPACE"),
            Triple(".", 0.70f, "TEXT_PERIOD"), Triple("", 0.85f, "LANGUAGE"), Triple("", 1.25f, "ENTER")
        )
        entries.forEachIndexed { index, (label, units, action) ->
            val w = units * unit
            val rect = RectF(x, y + dp(1), x + w, y + h - dp(1))
            val special = index == 0 || index == 6
            drawKey(canvas, rect, label, pressed?.value == action, special, c, radius = dp(6).toFloat(), font = when (index) {
                3 -> dp(12).toFloat(); 0 -> dp(13).toFloat(); else -> dp(18).toFloat()
            })
            when (action) {
                "EMOJI" -> drawIcon(canvas, "emoji", rect, c.text)
                "LANGUAGE" -> drawIcon(canvas, "language", rect, c.text)
                "ENTER" -> drawIcon(canvas, "enter", rect, c.text)
            }
            when (action) {
                "SPACE" -> hits += Hit(HitKind.SPACE, rect)
                "ENTER" -> hits += Hit(HitKind.ENTER, rect)
                "LANGUAGE" -> hits += Hit(HitKind.ACTION, rect, action)
                "EMOJI" -> hits += Hit(HitKind.ACTION, rect, action)
                "LAYOUT_SYMBOLS" -> hits += Hit(HitKind.LAYOUT, rect, action)
                "KEYBOARD" -> hits += Hit(HitKind.ACTION, rect, action)
                "TEXT_COMMA" -> hits += Hit(HitKind.TEXT, rect, ",")
                "TEXT_PERIOD" -> hits += Hit(HitKind.TEXT, rect, ".")
                else -> Unit
            }
            x += w + gap
        }
    }

    private fun drawPanelBottom(canvas: Canvas, left: Float, right: Float, y: Float, h: Float, gap: Float, c: Palette) {
        val isClipboard = mode == Mode.CLIPBOARD
        val items = when (mode) {
            Mode.CLIPBOARD -> listOf("ABC" to "KEYBOARD", "Cari" to "CLIPBOARD_HISTORY", "Hapus" to "CLIPBOARD_CLEAR")
            Mode.EMOJI -> listOf("ABC" to "KEYBOARD", "⇦" to "EMOJI_BACK", "⌫" to "BACKSPACE")
            Mode.TRANSLATE -> listOf("ABC" to "KEYBOARD", "Terjemahkan" to "TRANSLATE_RUN", "Pakai hasil" to "TRANSLATE_INSERT")
            Mode.TOOLS -> listOf("ABC" to "KEYBOARD", "Terapung" to "FLOAT_KEYBOARD", "Sembunyi" to "HIDE_KEYBOARD")
            else -> listOf("ABC" to "KEYBOARD", "⇦" to "KEYBOARD", "⌫" to "BACKSPACE")
        }
        val widths = when (mode) {
            Mode.CLIPBOARD -> listOf(1.2f, 1.4f, 1.3f)
            Mode.TRANSLATE -> listOf(1.1f, 1.9f, 1.5f)
            else -> listOf(1.2f, 1.6f, 1.5f)
        }
        val total = widths.sum()
        val u = (right - left - gap * (items.size - 1)) / total
        var x = left
        items.forEachIndexed { index, pair ->
            val rect = RectF(x, y + dp(1), x + widths[index] * u, y + h - dp(1))
            val isPrimary = index == 0 || (mode == Mode.TRANSLATE && index == 2)
            drawKey(canvas, rect, pair.first, pressed?.value == pair.second, isPrimary, c, font = dp(13).toFloat())
            val kind = when (pair.second) {
                "KEYBOARD" -> HitKind.ACTION
                "CLIPBOARD_HISTORY", "CLIPBOARD_CLEAR", "TRANSLATE_RUN", "TRANSLATE_INSERT", "FLOAT_KEYBOARD", "HIDE_KEYBOARD", "EMOJI_BACK" -> HitKind.ACTION
                "BACKSPACE" -> HitKind.BACKSPACE
                else -> HitKind.ACTION
            }
            hits += Hit(kind, rect, pair.second)
            x += rect.width() + gap
        }
    }

    private fun drawEmojiPanel(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, c: Palette) {
        val categories = listOf("Wajah", "Hati", "Hewan", "Makan", "Aktivitas", "Tempat")
        val tabsH = dp(28).toFloat()
        val tabGap = dp(4).toFloat()
        val tabW = (right - left - tabGap * (categories.size - 1) - dp(8)) / categories.size
        categories.forEachIndexed { i, label ->
            val x = left + dp(4) + i * (tabW + tabGap)
            val rect = RectF(x, top, x + tabW, top + tabsH)
            drawPill(canvas, rect, if (emojiCategory == i) c.accent else c.card, dp(9).toFloat())
            drawText(canvas, label, rect.centerX(), rect.centerY(), dp(10).toFloat(), if (emojiCategory == i) 0xFFFFFFFF.toInt() else c.text, true)
            hits += Hit(HitKind.CATEGORY, rect, "EMOJI_CATEGORY", i.toString())
        }
        val rows = listOf(
            listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "😊", "😇", "🙂", "🙃", "😉", "😍", "🥰", "😘", "😋", "😎", "🤩", "🥳", "😴", "🤔", "😮", "😢", "😭", "😡", "🙏", "👍", "👎", "👏", "💪"),
            listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "❣️", "💋", "💌", "💐", "🌹", "🌷", "🌻", "🌈", "✨", "⭐", "🌟", "🔥", "💯", "🎉", "🎁"),
            listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦄", "🐝", "🦋", "🐢", "🐬", "🐳", "🦕", "🦖", "🐾", "🌿", "🌸", "🌼", "🍀", "🌙"),
            listOf("🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🥑", "🥦", "🥕", "🌽", "🍞", "🧀", "🍕", "🍔", "🍟", "🌮", "🍜", "🍣", "🍩", "🍪", "☕"),
            listOf("⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱", "🏓", "🏸", "🥊", "🥋", "⛳", "🏹", "🎣", "🥇", "🏆", "🎮", "🎲", "🎯", "🎳", "🎨", "🎤", "🎧", "🎼", "🎵", "🎹", "🎸", "🥁", "🎬", "📷", "📱"),
            listOf("🚗", "🚕", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚲", "🛵", "🚂", "✈️", "🚀", "🛸", "🚁", "🛳️", "🚢", "⛵", "🏠", "🏢", "🏥", "🏫", "🌍", "🌎", "🌏", "🗺️", "⛺", "🏖️", "🏝️", "🎡", "🎢", "🗽")
        )
        val emojis = if (emojiCategory == 0 && prefs.recentEmoji.isNotEmpty()) {
            (prefs.recentEmoji + rows[0]).distinct().take(40)
        } else rows.getOrElse(emojiCategory.coerceIn(0, rows.lastIndex)) { rows[0] }
        val gridTop = top + tabsH + dp(7)
        panelScrollBounds = RectF(left, gridTop, right, bottom)
        val availableH = max(dp(30).toFloat(), bottom - gridTop)
        val cols = 8
        val cellW = (right - left) / cols
        val cellH = min(dp(39).toFloat(), availableH / 4f)
        val first = (emojiOffset / cellH).toInt().coerceAtLeast(0) * cols
        for (i in first until min(emojis.size, first + cols * 4)) {
            val local = i - first
            val row = local / cols
            val col = local % cols
            val rect = RectF(left + col * cellW + dp(1), gridTop + row * cellH, left + (col + 1) * cellW - dp(1), gridTop + (row + 1) * cellH)
            drawText(canvas, emojis[i], rect.centerX(), rect.centerY(), dp(22).toFloat(), c.text, false)
            hits += Hit(HitKind.EMOJI, rect, emojis[i])
        }
    }

    private fun drawClipboardPanel(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, c: Palette) {
        val labels = listOf("Semua", "Pin", "Link", "Email", "Kode", "Gambar")
        val filters = listOf("", ClipStoreFilter.PINNED, ClipItem.CATEGORY_LINK, ClipItem.CATEGORY_EMAIL, ClipItem.CATEGORY_CODE, ClipItem.CATEGORY_IMAGE)
        val tabsH = dp(28).toFloat()
        val gap = dp(4).toFloat()
        val tabW = (right - left - gap * (labels.size - 1) - dp(8)) / labels.size
        labels.forEachIndexed { i, label ->
            val x = left + dp(4) + i * (tabW + gap)
            val rect = RectF(x, top, x + tabW, top + tabsH)
            val selected = clipFilter == filters[i]
            drawPill(canvas, rect, if (selected) c.accent else c.card, dp(9).toFloat())
            drawText(canvas, label, rect.centerX(), rect.centerY(), dp(10).toFloat(), if (selected) 0xFFFFFFFF.toInt() else c.text, true)
            hits += Hit(HitKind.CATEGORY, rect, "CLIP_FILTER", filters[i])
        }
        val listTop = top + tabsH + dp(6)
        panelScrollBounds = RectF(left, listTop, right, bottom)
        val cardH = dp(48).toFloat()
        val rowGap = dp(5).toFloat()
        val maxRows = ((bottom - listTop) / (cardH + rowGap)).toInt().coerceAtLeast(1) + 1
        val maxClipOffset = max(0f, clips.size * (cardH + rowGap) - (bottom - listTop))
        clipOffset = clipOffset.coerceIn(0f, maxClipOffset)
        val start = (clipOffset / (cardH + rowGap)).toInt().coerceAtLeast(0)
        val visible = clips.drop(start).take(maxRows)
        if (visible.isEmpty()) {
            drawText(canvas, if (clipFilter == ClipStoreFilter.PINNED) "Belum ada item disematkan" else "Papan klip masih kosong", (left + right) / 2, listTop + dp(38), dp(13).toFloat(), c.secondary, false)
            return
        }
        visible.forEachIndexed { idx, item ->
            val i = start + idx
            val y = listTop + i * (cardH + rowGap) - clipOffset
            if (y + cardH < listTop || y > bottom) return@forEachIndexed
            val card = RectF(left + dp(4), y, right - dp(4), y + cardH)
            drawPill(canvas, card, c.card, dp(10).toFloat())
            val textRect = RectF(card.left + dp(10), card.top + dp(3), card.right - dp(36), card.bottom - dp(3))
            drawText(canvas, item.preview, textRect.left, textRect.centerY(), dp(12).toFloat(), c.text, false, Paint.Align.LEFT, textRect.width())
            drawText(canvas, item.displayTitle, textRect.left, card.top + dp(9), dp(9).toFloat(), c.secondary, true, Paint.Align.LEFT)
            val pinRect = RectF(card.right - dp(31), card.top, card.right, card.bottom)
            drawText(canvas, if (item.pinned) "★" else "☆", pinRect.centerX(), pinRect.centerY(), dp(17).toFloat(), if (item.pinned) c.accent else c.secondary, true)
            hits += Hit(HitKind.CLIP, textRect, clip = item)
            hits += Hit(HitKind.PIN, pinRect, clip = item)
        }
    }

    private fun drawTranslatePanel(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, c: Palette) {
        val area = RectF(left + dp(8), top + dp(5), right - dp(8), bottom - dp(5))
        drawPill(canvas, area, c.card, dp(12).toFloat())
        val padding = dp(13).toFloat()
        val maxW = area.width() - padding * 2
        drawText(canvas, if (translationSource.isBlank()) "Pilih teks lalu ketuk Terjemahkan" else translationSource,
            area.left + padding, area.top + dp(18), dp(12).toFloat(), c.secondary, false, Paint.Align.LEFT, maxW)
        p.color = c.divider
        p.strokeWidth = dp(1).toFloat()
        canvas.drawLine(area.left + padding, area.top + dp(31), area.right - padding, area.top + dp(31), p)
        val status = when {
            translationLoading -> "Menerjemahkan…"
            translationStatus.isNotBlank() -> translationStatus
            translationResult.isNotBlank() -> translationResult
            else -> "Tekan Terjemahkan untuk mulai"
        }
        drawText(canvas, status, area.left + padding, area.top + dp(51), dp(15).toFloat(), if (translationResult.isNotBlank()) c.text else c.secondary, false, Paint.Align.LEFT, maxW)
        drawText(canvas, "Hanya teks yang dipilih dikirim ke layanan terjemahan online.", area.left + padding, area.bottom - dp(11), dp(9).toFloat(), c.secondary, false, Paint.Align.LEFT, maxW)
        hits += Hit(HitKind.ACTION, area, "TRANSLATE_RUN")
    }

    private fun drawToolsPanel(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float, c: Palette) {
        val layoutLabel = if (prefs.keyboardLayout == Prefs.LAYOUT_PC) "Mode: iOS" else "Mode: PC"
        val baseTools = listOf(
            listOf("emoji", "Emoji", "EMOJI"), listOf("clipboard", "Papan klip", "CLIPBOARD"),
            listOf("translate", "Terjemah", "TRANSLATE"), listOf("floating", "Mengambang", "FLOAT_KEYBOARD"),
            listOf("settings", "Pengaturan", "SETTINGS"), listOf("language", "Bahasa", "LANGUAGE"),
            listOf("keyboard", layoutLabel, "LAYOUT_TOGGLE"), listOf("keyboard", "Satu tangan", "ONE_HAND"),
            listOf("close", "Sembunyikan", "HIDE_KEYBOARD")
        )
        val pcNavigation = if (prefs.keyboardLayout == Prefs.LAYOUT_PC) listOf(
            listOf("keyboard", "Home", "PC_KEY_HOME"), listOf("keyboard", "End", "PC_KEY_END"),
            listOf("keyboard", "Page Up", "PC_KEY_PAGE_UP"), listOf("keyboard", "Page Down", "PC_KEY_PAGE_DOWN"),
            listOf("keyboard", "Insert", "PC_KEY_INSERT"), listOf("keyboard", "Delete", "PC_KEY_FORWARD_DEL")
        ) else emptyList()
        val tools = baseTools + pcNavigation
        val cols = 4
        val rowCount = (tools.size + cols - 1) / cols
        val gap = dp(7).toFloat()
        val cellW = (right - left - gap * (cols - 1) - dp(8)) / cols
        val cellH = min(dp(51).toFloat(), (bottom - top - gap * (rowCount - 1)) / rowCount)
        tools.forEachIndexed { index, item ->
            val row = index / cols
            val col = index % cols
            val x = left + dp(4) + col * (cellW + gap)
            val y = top + row * (cellH + gap)
            val rect = RectF(x, y, x + cellW, y + cellH)
            drawPill(canvas, rect, c.card, dp(11).toFloat())
            drawIcon(canvas, item[0], RectF(rect.left + dp(4), rect.top + dp(2), rect.right - dp(4), rect.top + cellH * 0.58f), c.accent)
            drawText(canvas, item[1], rect.centerX(), rect.top + cellH * 0.79f, dp(9).toFloat(), c.text, false, maxWidth = rect.width() - dp(6))
            hits += Hit(HitKind.ACTION, rect, item[2])
        }
    }

    private fun drawPopupPreview(canvas: Canvas, colors: Palette) {
        val key = pressed ?: return
        val rect = key.rect
        val width = dp(42).toFloat()
        val height = dp(42).toFloat()
        val left = (rect.centerX() - width / 2).coerceIn(dp(2).toFloat(), this.width - width - dp(2))
        val top = (rect.top - height - dp(4)).coerceAtLeast(dp(3).toFloat())
        val bubble = RectF(left, top, left + width, top + height)
        p.color = colors.card
        p.setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), 0x44000000)
        canvas.drawRoundRect(bubble, dp(11).toFloat(), dp(11).toFloat(), p)
        p.clearShadowLayer()
        drawText(canvas, key.value, bubble.centerX(), bubble.centerY(), dp(24).toFloat(), colors.text, true)
    }

    private fun drawAccentPopup(canvas: Canvas, colors: Palette) {
        val center = pressed?.rect?.centerX() ?: downX
        val chars = accentOptions(pressed?.value.orEmpty())
        if (chars.isEmpty()) return
        val kw = dp(38).toFloat()
        val kh = dp(44).toFloat()
        val totalW = chars.size * kw
        val left = (center - totalW / 2).coerceIn(dp(3).toFloat(), width - totalW - dp(3))
        val top = (downY - kh - dp(12)).coerceAtLeast(dp(4).toFloat())
        val rect = RectF(left, top, left + totalW, top + kh)
        p.color = colors.card
        p.setShadowLayer(dp(7).toFloat(), 0f, dp(3).toFloat(), 0x44000000)
        canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), p)
        p.clearShadowLayer()
        accentHits = chars.mapIndexed { index, value ->
            val cell = RectF(left + index * kw, top, left + (index + 1) * kw, top + kh)
            drawText(canvas, value, cell.centerX(), cell.centerY(), dp(21).toFloat(), colors.text, true)
            Hit(HitKind.ACCENT, cell, value)
        }
    }

    private fun drawKey(canvas: Canvas, rect: RectF, label: String, isPressed: Boolean, special: Boolean, c: Palette, radius: Float = dp(5).toFloat(), font: Float = dp(20).toFloat()) {
        val fill = when {
            isPressed -> c.pressed
            special -> c.specialKey
            else -> c.key
        }
        p.color = if (overlay && prefs.floatingOpacity < 100) withAlpha(fill, prefs.floatingOpacity) else fill
        if (!special && !isPressed) p.setShadowLayer(dp(1.3f).toFloat(), 0f, dp(1.6f).toFloat(), 0x27000000)
        val keyRadius = if (radius == dp(5).toFloat()) dp(prefs.keyCornerRadiusDp).toFloat() else radius
        canvas.drawRoundRect(rect, keyRadius, keyRadius, p)
        p.clearShadowLayer()
        drawText(canvas, label, rect.centerX(), rect.centerY(), font * prefs.keyFontSizePercent / 100f, c.text, false, maxWidth = (rect.width() - dp(6)).coerceAtLeast(dp(4).toFloat()))
    }

    private fun drawPill(canvas: Canvas, rect: RectF, color: Int, radius: Float) {
        p.color = color
        canvas.drawRoundRect(rect, radius, radius, p)
    }

    /** Small monochrome line icons keep controls crisp and avoid emoji-font substitutions. */
    private fun drawIcon(canvas: Canvas, name: String, bounds: RectF, color: Int) {
        val oldStyle = p.style
        val oldStroke = p.strokeWidth
        val oldCap = p.strokeCap
        val oldJoin = p.strokeJoin
        val oldColor = p.color
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val size = min(bounds.width(), bounds.height()) * 0.56f
        val half = size / 2f
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.7f).toFloat()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        when (name) {
            "clipboard" -> {
                canvas.drawRoundRect(RectF(cx - half * 0.72f, cy - half * 0.62f, cx + half * 0.72f, cy + half), dp(2).toFloat(), dp(2).toFloat(), p)
                canvas.drawRoundRect(RectF(cx - half * 0.32f, cy - half * 0.82f, cx + half * 0.32f, cy - half * 0.48f), dp(2).toFloat(), dp(2).toFloat(), p)
                canvas.drawLine(cx - half * 0.42f, cy - half * 0.1f, cx + half * 0.42f, cy - half * 0.1f, p)
                canvas.drawLine(cx - half * 0.42f, cy + half * 0.2f, cx + half * 0.42f, cy + half * 0.2f, p)
            }
            "search" -> {
                canvas.drawCircle(cx - half * 0.08f, cy - half * 0.08f, half * 0.56f, p)
                canvas.drawLine(cx + half * 0.32f, cy + half * 0.32f, cx + half * 0.78f, cy + half * 0.78f, p)
            }
            "close" -> {
                canvas.drawLine(cx - half * 0.48f, cy - half * 0.48f, cx + half * 0.48f, cy + half * 0.48f, p)
                canvas.drawLine(cx + half * 0.48f, cy - half * 0.48f, cx - half * 0.48f, cy + half * 0.48f, p)
            }
            "emoji" -> {
                canvas.drawCircle(cx, cy, half * 0.72f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx - half * 0.25f, cy - half * 0.16f, dp(1.3f).toFloat(), p)
                canvas.drawCircle(cx + half * 0.25f, cy - half * 0.16f, dp(1.3f).toFloat(), p)
                p.style = Paint.Style.STROKE
                canvas.drawArc(RectF(cx - half * 0.34f, cy - half * 0.1f, cx + half * 0.34f, cy + half * 0.48f), 15f, 150f, false, p)
            }
            "language" -> {
                canvas.drawCircle(cx, cy, half * 0.74f, p)
                canvas.drawOval(RectF(cx - half * 0.34f, cy - half * 0.72f, cx + half * 0.34f, cy + half * 0.72f), p)
                canvas.drawLine(cx - half * 0.68f, cy, cx + half * 0.68f, cy, p)
                canvas.drawArc(RectF(cx - half * 0.68f, cy - half * 0.32f, cx + half * 0.68f, cy + half * 0.32f), 0f, 180f, false, p)
            }
            "translate" -> {
                p.style = Paint.Style.FILL
                drawText(canvas, "A", cx - half * 0.35f, cy, dp(11).toFloat(), color, true)
                drawText(canvas, "文", cx + half * 0.35f, cy, dp(10).toFloat(), color, true)
            }
            "settings" -> {
                canvas.drawCircle(cx, cy, half * 0.28f, p)
                canvas.drawCircle(cx, cy, half * 0.63f, p)
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val x1 = cx + kotlin.math.cos(angle).toFloat() * half * 0.68f
                    val y1 = cy + kotlin.math.sin(angle).toFloat() * half * 0.68f
                    val x2 = cx + kotlin.math.cos(angle).toFloat() * half * 0.9f
                    val y2 = cy + kotlin.math.sin(angle).toFloat() * half * 0.9f
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
            }
            "keyboard" -> {
                val keyRect = RectF(cx - half * 0.92f, cy - half * 0.58f, cx + half * 0.92f, cy + half * 0.58f)
                canvas.drawRoundRect(keyRect, dp(3).toFloat(), dp(3).toFloat(), p)
                for (row in 0..1) for (col in 0..4) {
                    val x = keyRect.left + half * (0.28f + col * 0.29f)
                    val y = keyRect.top + half * (0.3f + row * 0.38f)
                    canvas.drawCircle(x, y, dp(0.65f).toFloat(), p)
                }
                canvas.drawLine(cx - half * 0.35f, cy + half * 0.38f, cx + half * 0.35f, cy + half * 0.38f, p)
            }
            "floating" -> {
                canvas.drawRoundRect(RectF(cx - half * 0.78f, cy - half * 0.62f, cx + half * 0.78f, cy + half * 0.62f), dp(3).toFloat(), dp(3).toFloat(), p)
                val path = Path().apply {
                    moveTo(cx - half * 0.25f, cy + half * 0.28f)
                    lineTo(cx + half * 0.36f, cy - half * 0.32f)
                    lineTo(cx + half * 0.08f, cy - half * 0.32f)
                    moveTo(cx + half * 0.36f, cy - half * 0.32f)
                    lineTo(cx + half * 0.36f, cy - half * 0.03f)
                }
                canvas.drawPath(path, p)
            }
            "backspace" -> {
                val path = Path().apply {
                    moveTo(cx - half * 0.82f, cy)
                    lineTo(cx - half * 0.34f, cy - half * 0.55f)
                    lineTo(cx + half * 0.78f, cy - half * 0.55f)
                    lineTo(cx + half * 0.78f, cy + half * 0.55f)
                    lineTo(cx - half * 0.34f, cy + half * 0.55f)
                    close()
                    moveTo(cx - half * 0.1f, cy - half * 0.22f)
                    lineTo(cx + half * 0.34f, cy + half * 0.22f)
                    moveTo(cx + half * 0.34f, cy - half * 0.22f)
                    lineTo(cx - half * 0.1f, cy + half * 0.22f)
                }
                canvas.drawPath(path, p)
            }
            "shift" -> {
                val path = Path().apply {
                    moveTo(cx, cy - half * 0.78f)
                    lineTo(cx - half * 0.62f, cy - half * 0.05f)
                    lineTo(cx - half * 0.28f, cy - half * 0.05f)
                    lineTo(cx - half * 0.28f, cy + half * 0.58f)
                    lineTo(cx + half * 0.28f, cy + half * 0.58f)
                    lineTo(cx + half * 0.28f, cy - half * 0.05f)
                    lineTo(cx + half * 0.62f, cy - half * 0.05f)
                    close()
                }
                canvas.drawPath(path, p)
            }
            "enter" -> {
                val path = Path().apply {
                    moveTo(cx + half * 0.6f, cy - half * 0.45f)
                    lineTo(cx + half * 0.6f, cy + half * 0.05f)
                    lineTo(cx - half * 0.35f, cy + half * 0.05f)
                    lineTo(cx - half * 0.08f, cy - half * 0.22f)
                    moveTo(cx - half * 0.35f, cy + half * 0.05f)
                    lineTo(cx - half * 0.08f, cy + half * 0.32f)
                }
                canvas.drawPath(path, p)
            }
            "more" -> {
                p.style = Paint.Style.FILL
                for (i in -1..1) canvas.drawCircle(cx + i * half * 0.52f, cy, dp(1.5f).toFloat(), p)
            }
        }
        p.style = oldStyle
        p.strokeWidth = oldStroke
        p.strokeCap = oldCap
        p.strokeJoin = oldJoin
        p.color = oldColor
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        centerY: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        align: Paint.Align = Paint.Align.CENTER,
        maxWidth: Float = Float.MAX_VALUE
    ) {
        if (text.isBlank()) return
        p.color = color
        p.textSize = size
        p.typeface = if (bold) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        p.textAlign = align
        val y = centerY - (p.ascent() + p.descent()) / 2f
        val drawable = if (maxWidth < Float.MAX_VALUE) ellipsize(text, maxWidth) else text
        canvas.drawText(drawable, x, y, p)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (p.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && p.measureText(text.substring(0, end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.substring(0, end) + ellipsis
    }

    private fun pressedMatches(kind: HitKind, value: String): Boolean = pressed?.kind == kind && pressed?.value.equals(value, true)

    private fun hitAt(x: Float, y: Float): Hit? = hits.asReversed().firstOrNull { it.rect.contains(x, y) }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                scrollingPanel = false
                touchStartInScrollArea = (mode == Mode.CLIPBOARD || mode == Mode.EMOJI) && panelScrollBounds.contains(event.x, event.y)
                downAt = SystemClock.uptimeMillis()
                longPressHandled = false
                isSwipe = false
                cursorDrag = false
                backspaceRepeating = false
                swipePoints.clear()
                swipeSignature.clear()
                pressed = hitAt(event.x, event.y)
                pressed?.let { hit ->
                    if (hit.kind == HitKind.TEXT) {
                        swipePoints += event.x to event.y
                        scheduleLongPress(hit)
                    } else if (hit.kind == HitKind.BACKSPACE) {
                        scheduleBackspaceRepeat()
                    } else if (hit.kind == HitKind.CLIP) {
                        postDelayed({
                            if (pressed?.clip?.id == hit.clip?.id) {
                                longPressHandled = true
                                hit.clip?.let { actions?.onClipPinned(it) }
                                performHaptic()
                            }
                        }, LONG_PRESS_MS)
                    } else if (hit.kind == HitKind.SPACE) {
                        cursorLastX = event.x
                        postDelayed({
                            if (pressed?.kind == HitKind.SPACE && abs(lastX - downX) > dp(7)) cursorDrag = true
                        }, 180L)
                    }
                }
                performHaptic()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val fingerDeltaY = event.y - lastY
                lastX = event.x
                lastY = event.y
                if (accentOpen) {
                    accentSelected = accentHits.firstOrNull { it.rect.contains(event.x, event.y) }?.value.orEmpty()
                    invalidate()
                    return true
                }
                if (touchStartInScrollArea && abs(dy) > dp(7)) {
                    scrollingPanel = true
                    pressed = null
                    when (mode) {
                        Mode.CLIPBOARD -> clipOffset = (clipOffset - fingerDeltaY).coerceAtLeast(0f)
                        Mode.EMOJI -> emojiOffset = (emojiOffset - fingerDeltaY).coerceAtLeast(0f)
                        else -> Unit
                    }
                    invalidate()
                    return true
                }
                if (scrollingPanel) return true
                if (pressed?.kind == HitKind.SPACE && abs(dx) > dp(14)) {
                    cursorDrag = true
                    val step = dp(10).toFloat()
                    if (abs(event.x - cursorLastX) >= step) {
                        val count = (abs(event.x - cursorLastX) / step).toInt().coerceAtMost(6)
                        val code = if (event.x < cursorLastX) android.view.KeyEvent.KEYCODE_DPAD_LEFT else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                        repeat(count) { actions?.onAction("CURSOR_$code") }
                        cursorLastX = event.x
                    }
                    invalidate()
                    return true
                }
                if (mode == Mode.KEYBOARD && prefs.keyboardLayout == Prefs.LAYOUT_IOS && prefs.swipeTyping && pressed?.kind == HitKind.TEXT && !longPressHandled) {
                    if (!isSwipe && sqrt(dx * dx + dy * dy) > dp(18)) isSwipe = true
                    if (isSwipe) {
                        if (swipePoints.isEmpty() || distance(swipePoints.last(), event.x, event.y) > dp(4)) {
                            swipePoints += event.x to event.y
                            val under = hitAt(event.x, event.y)
                            if (under?.kind == HitKind.TEXT) {
                                val letter = under.value.firstOrNull()?.lowercaseChar()
                                if (letter != null && (swipeSignature.isEmpty() || swipeSignature.last() != letter)) swipeSignature.append(letter)
                            }
                        }
                        invalidate()
                        return true
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatDelete)
                if (accentOpen) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && accentSelected.isNotEmpty()) actions?.onText(if (shift) accentSelected.uppercase() else accentSelected)
                    accentOpen = false
                    accentHits = emptyList()
                    pressed = null
                    invalidate()
                    return true
                }
                val item = pressed
                if (event.actionMasked == MotionEvent.ACTION_UP && !longPressHandled && !cursorDrag && !scrollingPanel) {
                    if (isSwipe && swipeSignature.length >= 2) {
                        actions?.onSwipe(swipeSignature.toString())
                    } else if (item != null) dispatch(item)
                }
                pressed = null
                scrollingPanel = false
                touchStartInScrollArea = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun dispatch(hit: Hit) {
        when (hit.kind) {
            HitKind.TEXT -> actions?.onText(hit.value)
            HitKind.BACKSPACE -> actions?.onBackspace()
            HitKind.ENTER -> actions?.onEnter()
            HitKind.SPACE -> actions?.onSpace()
            HitKind.SHIFT -> actions?.onShift()
            HitKind.CANDIDATE -> actions?.onCandidate(hit.value)
            HitKind.EMOJI -> actions?.onEmoji(hit.value)
            HitKind.CLIP -> hit.clip?.let { actions?.onClipSelected(it) }
            HitKind.PIN -> hit.clip?.let { actions?.onClipPinned(it) }
            HitKind.ACCENT -> actions?.onText(hit.value)
            HitKind.CATEGORY -> {
                if (hit.value == "EMOJI_CATEGORY") {
                    emojiCategory = hit.secondary.toIntOrNull() ?: 0
                    emojiOffset = 0f
                } else if (hit.value == "CLIP_FILTER") {
                    clipFilter = hit.secondary
                    clipOffset = 0f
                    actions?.onClipsRequested(clipFilter)
                }
            }
            HitKind.LAYOUT -> {
                when (hit.value) {
                    "LAYOUT_SYMBOLS" -> setMode(Mode.SYMBOLS)
                    "SYMBOL_PAGE" -> { symbolPage = 1 - symbolPage; invalidate() }
                }
            }
            HitKind.ACTION -> handleAction(hit.value)
        }
    }

    private fun handleAction(action: String) {
        when (action) {
            "TOOLS" -> setMode(Mode.TOOLS)
            "EMOJI" -> setMode(Mode.EMOJI)
            "CLIPBOARD" -> setMode(Mode.CLIPBOARD)
            "TRANSLATE" -> { setMode(Mode.TRANSLATE); actions?.onAction("TRANSLATE_PREPARE") }
            "KEYBOARD" -> setMode(Mode.KEYBOARD)
            "LANGUAGE" -> actions?.onAction(action)
            "SETTINGS", "FLOAT_KEYBOARD", "ONE_HAND", "HIDE_KEYBOARD", "CLIPBOARD_HISTORY", "CLIPBOARD_CLEAR", "TRANSLATE_RUN", "TRANSLATE_INSERT", "TRANSLATE_SWAP", "EMOJI_SEARCH", "EMOJI_BACK", "CLOSE_FLOAT_KEYBOARD" -> actions?.onAction(action)
            else -> actions?.onAction(action)
        }
    }

    private fun scheduleLongPress(hit: Hit) {
        postDelayed({
            if (pressed?.kind == HitKind.TEXT && pressed?.value == hit.value && prefs.longPressAccents) {
                val options = accentOptions(hit.value)
                if (options.isNotEmpty()) {
                    longPressHandled = true
                    accentOpen = true
                    accentSelected = ""
                    invalidate()
                }
            }
        }, LONG_PRESS_MS)
    }

    private fun scheduleBackspaceRepeat() {
        val runnable = object : Runnable {
            override fun run() {
                if (pressed?.kind != HitKind.BACKSPACE) return
                backspaceRepeating = true
                actions?.onBackspace()
                postDelayed(this, 60L)
            }
        }
        repeatDelete = runnable
        postDelayed(runnable, 430L)
    }

    private fun performHaptic() {
        actions?.onFeedback()
    }

    private fun accentOptions(value: String): List<String> = when (value.lowercase().firstOrNull()) {
        'a' -> listOf("á", "à", "â", "ä", "ã", "å", "æ")
        'e' -> listOf("é", "è", "ê", "ë")
        'i' -> listOf("í", "ì", "î", "ï")
        'o' -> listOf("ó", "ò", "ô", "ö", "õ", "ø")
        'u' -> listOf("ú", "ù", "û", "ü")
        'c' -> listOf("ç", "ć", "č")
        'n' -> listOf("ñ", "ń")
        's' -> listOf("ß", "ś", "š")
        'y' -> listOf("ý", "ÿ")
        else -> emptyList()
    }

    private fun distance(point: Pair<Float, Float>, x: Float, y: Float): Float {
        val dx = point.first - x
        val dy = point.second - y
        return sqrt(dx * dx + dy * dy)
    }

    private fun withAlpha(color: Int, percent: Int): Int = (color and 0x00FFFFFF) or ((percent.coerceIn(0, 100) * 255 / 100) shl 24)

    private fun shade(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = (((color shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private object ClipStoreFilter { const val PINNED = "__pinned__" }

    companion object {
        private const val LONG_PRESS_MS = 450L
    }
}
