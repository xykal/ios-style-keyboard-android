package com.ioskeyboard.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * Single source of truth for every user setting. The keyboard service and the settings
 * screens both read/write through here, and the keyboard re-reads on every
 * [android.inputmethodservice.InputMethodService.onStartInputView] so changes apply live.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ios_keyboard_prefs", Context.MODE_PRIVATE)

    // ---------- layout ----------
    var numberRow: Boolean
        get() = sp.getBoolean(KEY_NUMBER_ROW, true)
        set(v) = sp.edit { putBoolean(KEY_NUMBER_ROW, v) }

    var showToolbar: Boolean
        get() = sp.getBoolean(KEY_TOOLBAR, true)
        set(v) = sp.edit { putBoolean(KEY_TOOLBAR, v) }

    var showClipboardKey: Boolean
        get() = sp.getBoolean(KEY_CLIP_KEY, true)
        set(v) = sp.edit { putBoolean(KEY_CLIP_KEY, v) }

    /** 0 = off, 1 = left, 2 = right */
    var oneHandMode: Int
        get() = sp.getInt(KEY_ONE_HAND, 0)
        set(v) = sp.edit { putInt(KEY_ONE_HAND, v) }

    var keyboardHeightPercent: Int
        get() = sp.getInt(KEY_HEIGHT, 100)
        set(v) = sp.edit { putInt(KEY_HEIGHT, v.coerceIn(75, 140)) }

    var keyFontSizePercent: Int
        get() = sp.getInt(KEY_FONT_SIZE, 100)
        set(v) = sp.edit { putInt(KEY_FONT_SIZE, v.coerceIn(70, 140)) }

    var keyCornerRadiusDp: Int
        get() = sp.getInt(KEY_RADIUS, 5)
        set(v) = sp.edit { putInt(KEY_RADIUS, v.coerceIn(0, 16)) }

    // ---------- typing feel ----------
    var swipeTyping: Boolean
        get() = sp.getBoolean(KEY_SWIPE, true)
        set(v) = sp.edit { putBoolean(KEY_SWIPE, v) }

    var autoCorrect: Boolean
        get() = sp.getBoolean(KEY_AUTOCORRECT, true)
        set(v) = sp.edit { putBoolean(KEY_AUTOCORRECT, v) }

    var predictions: Boolean
        get() = sp.getBoolean(KEY_PREDICTIONS, true)
        set(v) = sp.edit { putBoolean(KEY_PREDICTIONS, v) }

    var autoCapitalize: Boolean
        get() = sp.getBoolean(KEY_AUTO_CAP, true)
        set(v) = sp.edit { putBoolean(KEY_AUTO_CAP, v) }

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean(KEY_DOUBLE_SPACE_PERIOD, true)
        set(v) = sp.edit { putBoolean(KEY_DOUBLE_SPACE_PERIOD, v) }

    var keyPopupPreview: Boolean
        get() = sp.getBoolean(KEY_PREVIEW, true)
        set(v) = sp.edit { putBoolean(KEY_PREVIEW, v) }

    var longPressAccents: Boolean
        get() = sp.getBoolean(KEY_ACCENTS, true)
        set(v) = sp.edit { putBoolean(KEY_ACCENTS, v) }

    var hapticsEnabled: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit { putBoolean(KEY_HAPTICS, v) }

    /** 0..100, mapped onto a real VibrationEffect amplitude */
    var hapticStrength: Int
        get() = sp.getInt(KEY_HAPTIC_STRENGTH, 60)
        set(v) = sp.edit { putInt(KEY_HAPTIC_STRENGTH, v.coerceIn(0, 100)) }

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, false)
        set(v) = sp.edit { putBoolean(KEY_SOUND, v) }

    /** 0..100 → 0f..1f for [android.media.AudioManager.playSoundEffect] */
    var soundVolume: Int
        get() = sp.getInt(KEY_SOUND_VOLUME, 60)
        set(v) = sp.edit { putInt(KEY_SOUND_VOLUME, v.coerceIn(0, 100)) }

    // ---------- theme ----------
    /** [THEME_LIGHT], [THEME_DARK], [THEME_AUTO], [THEME_CUSTOM] */
    var theme: Int
        get() = sp.getInt(KEY_THEME, THEME_AUTO)
        set(v) = sp.edit { putInt(KEY_THEME, v) }

    var accentColor: Int
        get() = sp.getInt(KEY_ACCENT, DEFAULT_ACCENT)
        set(v) = sp.edit { putInt(KEY_ACCENT, v) }

    var customBackground: Int
        get() = sp.getInt(KEY_CUSTOM_BG, 0xFFD1D5DB.toInt())
        set(v) = sp.edit { putInt(KEY_CUSTOM_BG, v) }

    var customKey: Int
        get() = sp.getInt(KEY_CUSTOM_KEY, 0xFFFFFFFF.toInt())
        set(v) = sp.edit { putInt(KEY_CUSTOM_KEY, v) }

    var customKeyText: Int
        get() = sp.getInt(KEY_CUSTOM_TEXT, 0xFF000000.toInt())
        set(v) = sp.edit { putInt(KEY_CUSTOM_TEXT, v) }

    // ---------- floating ----------
    var floatingKeyboard: Boolean
        get() = sp.getBoolean(KEY_FLOATING, false)
        set(v) = sp.edit { putBoolean(KEY_FLOATING, v) }

    /** 30..100 percent opacity of the floating keyboard card */
    var floatingOpacity: Int
        get() = sp.getInt(KEY_FLOAT_OPACITY, 100)
        set(v) = sp.edit { putInt(KEY_FLOAT_OPACITY, v.coerceIn(30, 100)) }

    var floatingWidthPercent: Int
        get() = sp.getInt(KEY_FLOAT_WIDTH, 96)
        set(v) = sp.edit { putInt(KEY_FLOAT_WIDTH, v.coerceIn(55, 100)) }

    var floatingBottomMarginDp: Int
        get() = sp.getInt(KEY_FLOAT_BOTTOM, 24)
        set(v) = sp.edit { putInt(KEY_FLOAT_BOTTOM, v.coerceIn(0, 400)) }

    var floatingXOffsetDp: Int
        get() = sp.getInt(KEY_FLOAT_X, 0)
        set(v) = sp.edit { putInt(KEY_FLOAT_X, v) }

    var floatingBubbleEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOAT_BUBBLE, true)
        set(v) = sp.edit { putBoolean(KEY_FLOAT_BUBBLE, v) }

    // ---------- clipboard ----------
    var clipboardCapture: Boolean
        get() = sp.getBoolean(KEY_CLIP_CAPTURE, true)
        set(v) = sp.edit { putBoolean(KEY_CLIP_CAPTURE, v) }

    var clipboardMaxItems: Int
        get() = sp.getInt(KEY_CLIP_MAX, 1000)
        set(v) = sp.edit { putInt(KEY_CLIP_MAX, v.coerceIn(50, 5000)) }

    var keepClipboardOnClear: Boolean
        get() = sp.getBoolean(KEY_CLIP_KEEP_PINNED, true)
        set(v) = sp.edit { putBoolean(KEY_CLIP_KEEP_PINNED, v) }

    // ---------- translate ----------
    /** BCP-47 style code used by the translate endpoints ("auto" for source) */
    var translateSource: String
        get() = sp.getString(KEY_TR_SRC, "auto") ?: "auto"
        set(v) = sp.edit { putString(KEY_TR_SRC, v) }

    var translateTarget: String
        get() = sp.getString(KEY_TR_DST, "id") ?: "id"
        set(v) = sp.edit { putString(KEY_TR_DST, v) }

    // ---------- emoji recents ----------
    var recentEmoji: List<String>
        get() = (sp.getString(KEY_EMOJI_RECENT, "") ?: "")
            .split('|').filter { it.isNotEmpty() }.take(40)
        set(v) = sp.edit { putString(KEY_EMOJI_RECENT, v.take(40).joinToString("|")) }

    fun pushRecentEmoji(e: String) {
        val next = ArrayList<String>(1 + recentEmoji.size)
        next.add(e)
        recentEmoji.filter { it != e }.take(39).forEach { next.add(it) }
        recentEmoji = next
    }

    fun isDirectBootSafe(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_AUTO = 2
        const val THEME_CUSTOM = 3

        const val DEFAULT_ACCENT = 0xFF0A84FF.toInt()

        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_TOOLBAR = "show_toolbar"
        private const val KEY_CLIP_KEY = "show_clip_key"
        private const val KEY_ONE_HAND = "one_hand"
        private const val KEY_HEIGHT = "kb_height"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_RADIUS = "key_radius"

        private const val KEY_SWIPE = "swipe_typing"
        private const val KEY_AUTOCORRECT = "auto_correct"
        private const val KEY_PREDICTIONS = "predictions"
        private const val KEY_AUTO_CAP = "auto_capitalize"
        private const val KEY_DOUBLE_SPACE_PERIOD = "double_space_period"
        private const val KEY_PREVIEW = "key_preview"
        private const val KEY_ACCENTS = "long_press_accents"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_SOUND = "sound"
        private const val KEY_SOUND_VOLUME = "sound_volume"

        private const val KEY_THEME = "theme"
        private const val KEY_ACCENT = "accent"
        private const val KEY_CUSTOM_BG = "custom_bg"
        private const val KEY_CUSTOM_KEY = "custom_key"
        private const val KEY_CUSTOM_TEXT = "custom_text"

        private const val KEY_FLOATING = "floating_keyboard"
        private const val KEY_FLOAT_OPACITY = "float_opacity"
        private const val KEY_FLOAT_WIDTH = "float_width"
        private const val KEY_FLOAT_BOTTOM = "float_bottom"
        private const val KEY_FLOAT_X = "float_x"
        private const val KEY_FLOAT_BUBBLE = "float_bubble"

        private const val KEY_CLIP_CAPTURE = "clip_capture"
        private const val KEY_CLIP_MAX = "clip_max"
        private const val KEY_CLIP_KEEP_PINNED = "clip_keep_pinned"

        private const val KEY_TR_SRC = "tr_src"
        private const val KEY_TR_DST = "tr_dst"
        private const val KEY_EMOJI_RECENT = "emoji_recent"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
