package com.ioskeyboard.app.floatwin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ioskeyboard.app.R
import com.ioskeyboard.app.data.ClipItem
import com.ioskeyboard.app.data.ClipStore
import com.ioskeyboard.app.ime.KeyboardActions
import com.ioskeyboard.app.ime.KeyboardBridge
import com.ioskeyboard.app.ime.KeyboardIME
import com.ioskeyboard.app.ime.KeyboardSurface
import com.ioskeyboard.app.ui.ClipboardActivity
import com.ioskeyboard.app.util.Prefs
import kotlin.math.abs
import kotlin.math.max

/** User-enabled, non-focusable system overlays for the floating keyboard and clip bubble. */
class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var clipStore: ClipStore
    private var bubble: ImageView? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var keyboardSurface: KeyboardSurface? = null
    private var keyboardParams: WindowManager.LayoutParams? = null
    private var keyboardOverlayVisible = false
    private var clipboardBubbleVisible = false
    private var overlayClipFilter = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        clipStore = ClipStore.get(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: Intent().setAction(ACTION_SHOW_CLIPBOARD)
        when (command.action ?: ACTION_SHOW_CLIPBOARD) {
            ACTION_SHOW_KEYBOARD -> {
                beginForeground()
                showKeyboardOverlay()
                KeyboardBridge.hideImeForFloating()
            }
            ACTION_HIDE_KEYBOARD -> {
                removeKeyboardOverlay()
                if (command.getBooleanExtra(EXTRA_REOPEN_IME, false)) KeyboardBridge.showImeAfterFloating(this)
                finishIfNothingVisible()
            }
            ACTION_UPDATE_KEYBOARD_SIZE -> {
                if (keyboardSurface != null) updateKeyboardSize()
                else finishIfNothingVisible()
            }
            ACTION_SHOW_CLIPBOARD -> {
                beginForeground()
                showBubble()
            }
            ACTION_HIDE_CLIPBOARD -> {
                removePanel()
                removeBubble()
                finishIfNothingVisible()
            }
            ACTION_STOP_ALL -> {
                val reopen = command.getBooleanExtra(EXTRA_REOPEN_IME, false)
                removeKeyboardOverlay()
                removePanel()
                removeBubble()
                if (reopen) KeyboardBridge.showImeAfterFloating(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val stop = PendingIntent.getService(
            this, 301,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Tutup overlay", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan tampil di atas aplikasi terlebih dahulu.")
            finishIfNothingVisible()
            return
        }
        if (bubble != null) return
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(15), dp(15), dp(15), dp(15))
            contentDescription = "Papan klip mengambang"
            background = rounded(0xFF0A84FF.toInt(), dp(27).toFloat())
            elevation = dp(8).toFloat()
        }
        val type = overlayType()
        val params = WindowManager.LayoutParams(dp(54), dp(54), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.RIGHT or Gravity.BOTTOM
            x = dp(20)
            y = dp(180)
        }
        var startX = 0f
        var startY = 0f
        var baseX = 0
        var baseY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    baseX = params.x
                    baseY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = startX - event.rawX
                    val dy = startY - event.rawY
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                    params.x = (baseX + dx.toInt()).coerceAtLeast(0)
                    params.y = (baseY + dy.toInt()).coerceAtLeast(dp(20))
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) showPanel()
                    true
                }
                else -> true
            }
        }
        try {
            windowManager.addView(view, params)
            bubble = view
            bubbleParams = params
            clipboardBubbleVisible = true
        } catch (_: Exception) {
            toast("Overlay tidak bisa dibuka. Periksa izin tampil di atas aplikasi.")
        }
    }

    private fun showPanel() {
        if (panel != null) {
            removePanel()
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = rounded(0xFFF8F9FC.toInt(), dp(18).toFloat(), 0xFFE6E8ED.toInt())
            elevation = dp(14).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFF8F9FC.toInt(), dp(12).toFloat())
            setPadding(dp(6), dp(3), dp(3), dp(3))
        }
        val title = TextView(this).apply {
            text = "Papan klip"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF191A1E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF666A73.toInt())
            background = rounded(0xFFE9EBEF.toInt(), dp(18).toFloat())
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filters = listOf(
            "Semua" to "", "Disematkan" to ClipStore.FILTER_PINNED,
            "Link" to ClipItem.CATEGORY_LINK, "Email" to ClipItem.CATEGORY_EMAIL,
            "Catatan" to ClipItem.CATEGORY_NOTE, "Kode" to ClipItem.CATEGORY_CODE,
            "Gambar" to ClipItem.CATEGORY_IMAGE
        )
        filters.forEach { (label, filter) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(0xFF0A65CF.toInt())
                background = rounded(0xFFEAF3FF.toInt(), dp(14).toFloat())
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { populateClips(list, filter) }
            }
            tabs.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(31)).apply {
                marginEnd = dp(5)
            })
        }
        tabsScroll.addView(tabs)
        root.addView(tabsScroll, LinearLayout.LayoutParams(-1, dp(36)))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        populateClips(list, "")

        close.setOnClickListener { removePanel() }
        var dragRawX = 0f
        var dragRawY = 0f
        var panelX = 0
        var panelY = 0
        header.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.x > view.width - dp(48)) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragRawX = event.rawX; dragRawY = event.rawY
                    panelX = panelParams?.x ?: 0; panelY = panelParams?.y ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = panelParams
                    if (lp != null) {
                        lp.x = panelX + (event.rawX - dragRawX).toInt()
                        lp.y = panelY + (event.rawY - dragRawY).toInt()
                        try { windowManager.updateViewLayout(root, lp) } catch (_: Exception) { }
                    }
                    true
                }
                else -> true
            }
        }

        val params = WindowManager.LayoutParams(dp(318), dp(400), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            x = dp(2)
            y = -dp(38)
        }
        try {
            windowManager.addView(root, params)
            panel = root
            panelParams = params
        } catch (_: Exception) { toast("Panel papan klip tidak bisa ditampilkan.") }
    }

    private fun populateClips(container: LinearLayout, filter: String) {
        container.removeAllViews()
        val items = clipStore.list(category = filter.ifBlank { null }, limit = 100)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Belum ada isi clipboard.\nTeks yang disalin akan tersimpan saat keyboard aktif."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF6B707A.toInt())
                setPadding(dp(10), dp(32), dp(10), dp(32))
            }
            container.addView(empty)
            return
        }
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(6), dp(6))
                background = rounded(Color.WHITE, dp(11).toFloat(), 0xFFE9EBEF.toInt())
            }
            val label = TextView(this).apply {
                text = "${item.displayTitle}${if (item.pinned) "  ★" else ""}\n${item.preview}"
                textSize = 12f
                setTextColor(0xFF1A1B20.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(3), dp(3), dp(5), dp(3))
                setOnClickListener {
                    KeyboardBridge.insertClip(this@FloatingService, item)
                    removePanel()
                }
            }
            val pin = TextView(this).apply {
                text = if (item.pinned) "★" else "☆"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (item.pinned) 0xFF0A84FF.toInt() else 0xFF7B7E86.toInt())
                setOnClickListener {
                    clipStore.togglePinned(item.id)
                    populateClips(container, filter)
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, dp(54), 1f))
            row.addView(pin, LinearLayout.LayoutParams(dp(34), dp(48)))
            label.setOnLongClickListener {
                clipStore.togglePinned(item.id)
                populateClips(container, filter)
                true
            }
            container.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) })
        }
    }

    private fun showKeyboardOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan tampil di atas aplikasi untuk memakai keyboard mengambang.")
            finishIfNothingVisible()
            return
        }
        if (keyboardSurface != null) return
        val surface = KeyboardSurface(this, OverlayActions(), overlay = true).apply {
            setClips(clipStore.list(limit = 300), "")
            setOnTouchListener { view, event ->
                // Drag only the small grab strip; all keys continue through KeyboardSurface.
                if (event.y > dp(22).toFloat() || event.x > view.width - dp(46)) return@setOnTouchListener false
                val lp = keyboardParams ?: return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawX = event.rawX; dragStartRawY = event.rawY
                        dragStartX = lp.x; dragStartY = lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = dragStartX + (event.rawX - dragStartRawX).toInt()
                        lp.y = dragStartY + (event.rawY - dragStartRawY).toInt()
                        clampKeyboardPosition(lp)
                        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) { }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.floatingXOffsetDp = ((lp.x - (resources.displayMetrics.widthPixels - lp.width) / 2f) / resources.displayMetrics.density).toInt()
                        prefs.floatingBottomMarginDp = ((resources.displayMetrics.heightPixels - lp.y - lp.height) / resources.displayMetrics.density).toInt().coerceAtLeast(0)
                        true
                    }
                    else -> true
                }
            }
        }
        val display = resources.displayMetrics
        val screenW = display.widthPixels
        val screenH = display.heightPixels
        val requestedWidth = floatingKeyboardWidth(screenW)
        val params = WindowManager.LayoutParams(
            requestedWidth,
            dp(prefs.floatingKeyboardHeightDp),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW - width) / 2 + prefs.floatingXOffsetDp * resources.displayMetrics.density).toInt().coerceAtLeast(0)
            y = (screenH - height - dp(prefs.floatingBottomMarginDp)).coerceAtLeast(dp(24))
        }
        try {
            windowManager.addView(surface, params)
            keyboardSurface = surface
            keyboardParams = params
            keyboardOverlayVisible = true
            prefs.floatingKeyboard = true
            KeyboardBridge.get()?.setFloatingSurface(surface)
        } catch (_: Exception) {
            toast("Keyboard mengambang gagal dibuka. Periksa izin overlay.")
        }
    }

    private fun floatingKeyboardWidth(screenWidth: Int): Int {
        val requested = if (prefs.keyboardLayout == Prefs.LAYOUT_PC) screenWidth
        else (screenWidth * prefs.floatingWidthPercent / 100).coerceAtLeast(dp(250))
        return requested.coerceAtMost(screenWidth)
    }

    /** Apply the width/height controls immediately when the overlay is already open. */
    private fun updateKeyboardSize() {
        val view = keyboardSurface ?: return
        val params = keyboardParams ?: return
        val display = resources.displayMetrics
        params.width = floatingKeyboardWidth(display.widthPixels)
        params.height = dp(prefs.floatingKeyboardHeightDp)
        params.x = ((display.widthPixels - params.width) / 2f + prefs.floatingXOffsetDp * display.density).toInt()
        params.y = (display.heightPixels - params.height - dp(prefs.floatingBottomMarginDp)).coerceAtLeast(dp(20))
        clampKeyboardPosition(params)
        try {
            windowManager.updateViewLayout(view, params)
            view.requestLayout()
            view.invalidate()
        } catch (_: Exception) {
            // The overlay may have been dismissed while the user was changing settings.
        }
    }

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    private fun clampKeyboardPosition(params: WindowManager.LayoutParams) {
        val bounds = resources.displayMetrics
        params.x = params.x.coerceIn(0, max(0, bounds.widthPixels - params.width))
        params.y = params.y.coerceIn(dp(20), max(dp(20), bounds.heightPixels - params.height))
    }

    private fun removeKeyboardOverlay() {
        val view = keyboardSurface
        if (view != null) try { windowManager.removeView(view) } catch (_: Exception) { }
        val previous = keyboardSurface
        if (previous != null) KeyboardBridge.get()?.setFloatingSurface(null)
        keyboardSurface = null
        keyboardParams = null
        keyboardOverlayVisible = false
        prefs.floatingKeyboard = false
    }

    private fun removePanel() {
        val view = panel
        if (view != null) try { windowManager.removeView(view) } catch (_: Exception) { }
        panel = null
        panelParams = null
    }

    private fun removeBubble() {
        val view = bubble
        if (view != null) try { windowManager.removeView(view) } catch (_: Exception) { }
        bubble = null
        bubbleParams = null
        clipboardBubbleVisible = false
    }

    private fun finishIfNothingVisible() {
        if (!keyboardOverlayVisible && !clipboardBubbleVisible) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private inner class OverlayActions : KeyboardActions {
        private fun ime(): KeyboardIME? = KeyboardBridge.get()
        override fun onText(text: String) { ime()?.onText(text) ?: KeyboardBridge.insertText(this@FloatingService, text) }
        override fun onBackspace() { ime()?.onBackspace() }
        override fun onEnter() { ime()?.onEnter() }
        override fun onSpace() { ime()?.onSpace() }
        override fun onShift() { ime()?.onShift() }
        override fun onCandidate(word: String) { ime()?.onCandidate(word) }
        override fun onSwipe(signature: String) { ime()?.onSwipe(signature) }
        override fun onEmoji(emoji: String) { ime()?.onEmoji(emoji) }
        override fun onAction(action: String) {
            when (action) {
                "CLOSE_FLOAT_KEYBOARD" -> stopKeyboard(this@FloatingService, reopenIme = true)
                "HIDE_KEYBOARD" -> stopKeyboard(this@FloatingService, reopenIme = false)
                else -> ime()?.onAction(action)
            }
        }
        override fun onClipSelected(item: ClipItem) { ime()?.onClipSelected(item) ?: KeyboardBridge.insertClip(this@FloatingService, item) }
        override fun onClipPinned(item: ClipItem) {
            clipStore.togglePinned(item.id)
            val category = if (overlayClipFilter == ClipStore.FILTER_PINNED) ClipStore.FILTER_PINNED else overlayClipFilter.ifBlank { null }
            keyboardSurface?.setClips(clipStore.list(category = category, limit = 300), overlayClipFilter)
        }
        override fun onFeedback() { KeyboardBridge.feedback() }
        override fun onSuggestionsRequested() { keyboardSurface?.let { ime()?.refreshFromOverlay(it) } }
        override fun onClipsRequested(filter: String) {
            overlayClipFilter = filter
            val category = if (filter == ClipStore.FILTER_PINNED) filter else filter.ifBlank { null }
            keyboardSurface?.setClips(clipStore.list(category = category, limit = 300), filter)
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        removeKeyboardOverlay()
        removePanel()
        removeBubble()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floating_controls"
        private const val NOTIFICATION_ID = 7302
        private const val EXTRA_REOPEN_IME = "reopen_ime"
        private const val ACTION_SHOW_KEYBOARD = "com.ioskeyboard.app.action.SHOW_KEYBOARD"
        private const val ACTION_HIDE_KEYBOARD = "com.ioskeyboard.app.action.HIDE_KEYBOARD"
        private const val ACTION_UPDATE_KEYBOARD_SIZE = "com.ioskeyboard.app.action.UPDATE_KEYBOARD_SIZE"
        private const val ACTION_SHOW_CLIPBOARD = "com.ioskeyboard.app.action.SHOW_CLIPBOARD"
        private const val ACTION_HIDE_CLIPBOARD = "com.ioskeyboard.app.action.HIDE_CLIPBOARD"
        private const val ACTION_STOP_ALL = "com.ioskeyboard.app.action.STOP_ALL"

        fun startClipboard(context: Context) = start(context, ACTION_SHOW_CLIPBOARD)
        fun startKeyboard(context: Context) = start(context, ACTION_SHOW_KEYBOARD)
        fun updateKeyboardSize(context: Context) {
            context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_UPDATE_KEYBOARD_SIZE))
        }
        fun stopKeyboard(context: Context, reopenIme: Boolean = false) {
            val intent = Intent(context, FloatingService::class.java).setAction(ACTION_HIDE_KEYBOARD).putExtra(EXTRA_REOPEN_IME, reopenIme)
            context.startService(intent)
        }
        fun stopClipboard(context: Context) = context.startService(Intent(context, FloatingService::class.java).setAction(ACTION_HIDE_CLIPBOARD))
        fun stopAll(context: Context, reopenIme: Boolean = false) {
            val intent = Intent(context, FloatingService::class.java).setAction(ACTION_STOP_ALL).putExtra(EXTRA_REOPEN_IME, reopenIme)
            context.startService(intent)
        }

        private fun start(context: Context, action: String) = start(context, Intent(context, FloatingService::class.java).setAction(action))
        private fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
