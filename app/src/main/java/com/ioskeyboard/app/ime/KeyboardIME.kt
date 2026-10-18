package com.ioskeyboard.app.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.inputmethodservice.InputMethodService
import android.widget.Toast
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.ioskeyboard.app.data.ClipItem
import com.ioskeyboard.app.data.ClipStore
import com.ioskeyboard.app.data.DictionaryEngine
import com.ioskeyboard.app.floatwin.FloatingService
import com.ioskeyboard.app.net.TranslateClient
import com.ioskeyboard.app.ui.SettingsActivity
import com.ioskeyboard.app.util.Prefs
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.Executors

/** Main Android input method. Typing, prediction, clipboard history and swipe are local. */
class KeyboardIME : InputMethodService(), KeyboardActions {
    private lateinit var prefs: Prefs
    private lateinit var clipStore: ClipStore
    private lateinit var dictionary: DictionaryEngine
    private var keyboard: KeyboardSurface? = null
    @Volatile private var floatingSurface: KeyboardSurface? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var sensitiveField = false
    private var predictionsAllowed = true
    private var activeMetaState = 0
    private var currentLanguage = DictionaryEngine.LANG_ID
    private var shiftState = 0
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var currentClipFilter = ""
    private var translateSource = ""
    private var translateResult = ""
    private var translateReplaceLength = 0
    private var translateSelectionMode = false
    private var translationToken = 0
    private val main = Handler(Looper.getMainLooper())
    private val swipeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-decoder").apply { isDaemon = true } }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        clipStore = ClipStore.get(this)
        dictionary = DictionaryEngine.get(this)
        dictionary.warmUp()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        KeyboardBridge.attach(this)
    }

    override fun onCreateInputView(): View {
        keyboard = KeyboardSurface(this, this).apply {
            setLanguageLabel(if (currentLanguage == DictionaryEngine.LANG_EN) "English" else "Indonesia")
            setShifted(shiftState > 0)
        }
        return keyboard!!.apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val info = attribute ?: return
        val type = info.inputType
        val inputClass = type and android.text.InputType.TYPE_MASK_CLASS
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        val password = inputClass == android.text.InputType.TYPE_CLASS_TEXT && variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) || inputClass == android.text.InputType.TYPE_CLASS_NUMBER && variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noPersonalizedLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        sensitiveField = password || noPersonalizedLearning
        val noSuggestionsFlag = (type and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val addressLike = variation in setOf(
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            android.text.InputType.TYPE_TEXT_VARIATION_URI,
            android.text.InputType.TYPE_TEXT_VARIATION_FILTER
        )
        predictionsAllowed = !sensitiveField && inputClass == android.text.InputType.TYPE_CLASS_TEXT && !noSuggestionsFlag && !addressLike
        activeMetaState = 0
        if (sensitiveField) stopClipboardCapture()

        val hintLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) info.hintLocales?.get(0) else null
        currentLanguage = when {
            hintLocale?.language?.startsWith("en", true) == true -> DictionaryEngine.LANG_EN
            hintLocale?.language?.startsWith("id", true) == true -> DictionaryEngine.LANG_ID
            currentLanguage == DictionaryEngine.LANG_EN -> DictionaryEngine.LANG_EN
            else -> DictionaryEngine.LANG_ID
        }
        val noAutoCap = (type and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) == 0 &&
            (type and android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS) == 0 &&
            (type and android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) == 0
        shiftState = if (predictionsAllowed && !noAutoCap && prefs.autoCapitalize) 1 else 0
        translateSource = ""
        translateResult = ""
        translationToken++
        swipeRequestId.incrementAndGet()
        activeSurface()?.setLanguageLabel(if (currentLanguage == DictionaryEngine.LANG_EN) "English" else "Indonesia")
        activeSurface()?.setShifted(shiftState > 0)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        dictionary.warmUp()
        keyboard?.visibility = View.VISIBLE
        activeSurface()?.setMode(KeyboardSurface.Mode.KEYBOARD)
        activeSurface()?.setShifted(shiftState > 0)
        startClipboardCapture()
        refreshSuggestions()
        if (!sensitiveField) capturePrimaryClip()
        if (prefs.floatingKeyboard && Settings.canDrawOverlays(this)) {
            FloatingService.startKeyboard(this)
            hideImeForFloating()
            return
        } else if (prefs.floatingKeyboard) {
            // Permission may have been revoked from Android settings. Fall back to the normal IME.
            prefs.floatingKeyboard = false
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keep capture active while the IME is represented by its non-focusable floating overlay.
        if (floatingSurface == null) stopClipboardCapture()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopClipboardCapture()
        super.onFinishInput()
    }

    override fun onDestroy() {
        stopClipboardCapture()
        KeyboardBridge.detach(this)
        swipeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startClipboardCapture() {
        if (!prefs.clipboardCapture || sensitiveField) return
        val manager = clipboardManager ?: return
        if (clipListener != null) return
        val listener = ClipboardManager.OnPrimaryClipChangedListener { capturePrimaryClip() }
        clipListener = listener
        try { manager.addPrimaryClipChangedListener(listener) } catch (_: Exception) { clipListener = null }
    }

    private fun stopClipboardCapture() {
        val listener = clipListener ?: return
        try { clipboardManager?.removePrimaryClipChangedListener(listener) } catch (_: Exception) { }
        clipListener = null
    }

    private fun capturePrimaryClip() {
        if (!prefs.clipboardCapture || sensitiveField) return
        try {
            val clip = clipboardManager?.primaryClip ?: return
            if (clip.itemCount == 0 || isSensitiveClip(clip)) return
            val description = clip.description
            val firstItem = clip.getItemAt(0)
            val imageMime = (0 until description.mimeTypeCount)
                .map { description.getMimeType(it) }
                .firstOrNull { it.startsWith("image/") }
                ?: firstItem.uri?.let { contentResolver.getType(it) }?.takeIf { it.startsWith("image/") }
            if (imageMime != null && firstItem.uri != null) {
                clipStore.saveImage(firstItem.uri!!, imageMime)
                if (activeSurface()?.getMode() == KeyboardSurface.Mode.CLIPBOARD) refreshClips(currentClipFilter)
                return
            }
            val text = firstItem.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                clipStore.save(text)
                if (activeSurface()?.getMode() == KeyboardSurface.Mode.CLIPBOARD) refreshClips(currentClipFilter)
            }
        } catch (_: SecurityException) {
            // Clipboard access is restricted by Android and some apps; history remains available.
        } catch (_: Exception) { }
    }

    private fun isSensitiveClip(clip: ClipData): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        } catch (_: Exception) {
            false
        }
    }

    /** Set by the overlay service so suggestions and translation keep updating in floating mode. */
    fun setFloatingSurface(surface: KeyboardSurface?) {
        floatingSurface = surface
        if (surface != null) {
            startClipboardCapture()
            surface.setLanguageLabel(if (currentLanguage == DictionaryEngine.LANG_EN) "English" else "Indonesia")
            surface.setShifted(shiftState > 0)
            surface.setMode(KeyboardSurface.Mode.KEYBOARD)
            refreshFromOverlay(surface)
            refreshClips(currentClipFilter)
        } else {
            stopClipboardCapture()
        }
    }

    fun refreshFromOverlay(surface: KeyboardSurface) {
        surface.setSuggestions(suggestionsAtCursor())
    }

    private fun activeSurface(): KeyboardSurface? = floatingSurface ?: keyboard

    private fun wordBeforeCursor(maxChars: Int = 80): String {
        val before = currentInputConnection?.getTextBeforeCursor(maxChars, 0)?.toString().orEmpty()
        return WORD_AT_END.find(before)?.value.orEmpty()
    }

    private fun suggestionsAtCursor(): List<String> {
        if (sensitiveField || !predictionsAllowed || !prefs.predictions) return emptyList()
        val current = wordBeforeCursor()
        if (current.isNotBlank()) return dictionary.suggest(current, currentLanguage, 3)
        val before = currentInputConnection?.getTextBeforeCursor(160, 0)?.toString().orEmpty()
        val previous = WORD_IN_TEXT.findAll(before).lastOrNull()?.value.orEmpty()
        return dictionary.suggestNext(previous, currentLanguage, 3)
    }

    private fun refreshSuggestions() {
        activeSurface()?.setSuggestions(suggestionsAtCursor())
    }

    private fun formatCase(original: String, replacement: String): String = when {
        original.isNotEmpty() && original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase(Locale.getDefault())
        original.firstOrNull()?.isUpperCase() == true -> replacement.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        else -> replacement
    }

    override fun onText(text: String) {
        val value = if (shiftState > 0 && text.length == 1 && text[0].isLetter()) text.uppercase(Locale.getDefault()) else text
        if (prefs.keyboardLayout == Prefs.LAYOUT_PC && activeMetaState != 0 && value.length == 1) {
            val code = hardwareKeyCode(value[0])
            if (code != null) {
                sendHardwareKey(code, activeMetaState or if (shiftState > 0) KeyEvent.META_SHIFT_ON else 0)
                activeMetaState = 0
                if (shiftState == 1) shiftState = 0
                activeSurface()?.setShifted(shiftState > 0)
                refreshSuggestions()
                playKeySound()
                return
            }
        }
        currentInputConnection?.commitText(value, 1)
        if (shiftState == 1 && text.length == 1 && text[0].isLetter()) shiftState = 0
        activeSurface()?.setShifted(shiftState > 0)
        refreshSuggestions()
        playKeySound()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) {
                val iterator = BreakIterator.getCharacterInstance(Locale.getDefault())
                iterator.setText(before)
                val end = iterator.last()
                val start = iterator.previous().coerceAtLeast(0)
                val count = end - start
                if (count > 0) ic.deleteSurroundingText(count, 0) else ic.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                ic.deleteSurroundingTextInCodePoints(1, 0)
            }
        }
        refreshSuggestions()
        playKeySound()
    }

    override fun onEnter() {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val ic = currentInputConnection
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && ic?.performEditorAction(action) == true) {
            playKeySound(returnKey = true)
            return
        }
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        playKeySound(returnKey = true)
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val before = ic.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        if (prefs.doubleSpacePeriod && lastSpaceTap != 0L && now - lastSpaceTap < 650 && before.endsWith(" ") && before.length > 1 && before[before.length - 2].isLetterOrDigit()) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTap = 0
            if (prefs.autoCapitalize && predictionsAllowed) {
                shiftState = 1
                activeSurface()?.setShifted(true)
            }
        } else {
            if (predictionsAllowed && prefs.autoCorrect) {
                val word = WORD_AT_END.find(before)?.value.orEmpty()
                if (word.length >= 3) {
                    val correction = dictionary.correctOneEdit(word, currentLanguage)
                    if (!correction.isNullOrBlank()) {
                        ic.deleteSurroundingText(word.length, 0)
                        ic.commitText(formatCase(word, correction), 1)
                    }
                }
            }
            ic.commitText(" ", 1)
            lastSpaceTap = now
            if (prefs.autoCapitalize && predictionsAllowed && (before.isBlank() || before.trimEnd().endsWith(".") || before.trimEnd().endsWith("!") || before.trimEnd().endsWith("?"))) {
                shiftState = 1
                activeSurface()?.setShifted(true)
            }
        }
        refreshSuggestions()
        playKeySound()
    }

    override fun onShift() {
        val now = android.os.SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == 1 && now - lastShiftTap < 450 -> 2
            shiftState == 2 -> 0
            else -> 1
        }
        lastShiftTap = now
        if (shiftState == 2) activeSurface()?.setCapsLock(true)
        else activeSurface()?.setShifted(shiftState == 1)
    }

    override fun onCandidate(word: String) {
        if (sensitiveField || !predictionsAllowed || word.isBlank()) return
        val current = wordBeforeCursor()
        if (current.isNotBlank()) currentInputConnection?.deleteSurroundingText(current.length, 0)
        val replacement = if (current.isBlank()) word else formatCase(current, word)
        currentInputConnection?.commitText(replacement, 1)
        if (shiftState == 1) shiftState = 0
        activeSurface()?.setShifted(shiftState > 0)
        refreshSuggestions()
    }

    override fun onSwipe(signature: String) {
        if (!prefs.swipeTyping || prefs.keyboardLayout != Prefs.LAYOUT_IOS || sensitiveField || !predictionsAllowed) return
        val requestId = swipeRequestId.incrementAndGet()
        val language = currentLanguage
        swipeExecutor.execute {
            val matches = dictionary.bestSwipe(signature, language, 3)
            main.post {
                if (requestId != swipeRequestId.get() || matches.isEmpty() || sensitiveField || !predictionsAllowed) return@post
                val current = wordBeforeCursor()
                if (current.isNotBlank()) currentInputConnection?.deleteSurroundingText(current.length, 0)
                val best = formatCase(current, matches.first())
                currentInputConnection?.commitText("$best ", 1)
                refreshSuggestions()
                activeSurface()?.setSuggestions(matches)
            }
        }
    }

    override fun onEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        prefs.pushRecentEmoji(emoji)
        refreshSuggestions()
    }

    override fun onAction(action: String) {
        when (action) {
            "LANGUAGE" -> {
                currentLanguage = if (currentLanguage == DictionaryEngine.LANG_ID) DictionaryEngine.LANG_EN else DictionaryEngine.LANG_ID
                activeSurface()?.setLanguageLabel(if (currentLanguage == DictionaryEngine.LANG_EN) "English" else "Indonesia")
                refreshSuggestions()
            }
            "SETTINGS" -> startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "FLOAT_KEYBOARD" -> enableFloatingKeyboard()
            "HIDE_KEYBOARD" -> requestHideSelf(0)
            "CLOSE_FLOAT_KEYBOARD" -> FloatingService.stopKeyboard(this, reopenIme = true)
            "ONE_HAND" -> {
                prefs.oneHandMode = (prefs.oneHandMode + 1) % 3
                activeSurface()?.invalidate()
                toast(if (prefs.oneHandMode == 0) "Mode satu tangan mati" else if (prefs.oneHandMode == 1) "Keyboard rapat kiri" else "Keyboard rapat kanan")
            }
            "LAYOUT_TOGGLE" -> {
                prefs.keyboardLayout = if (prefs.keyboardLayout == Prefs.LAYOUT_IOS) Prefs.LAYOUT_PC else Prefs.LAYOUT_IOS
                shiftState = 0
                activeMetaState = 0
                activeSurface()?.setShifted(false)
                activeSurface()?.invalidate()
                toast(if (prefs.keyboardLayout == Prefs.LAYOUT_PC) "Layout QWERTY PC" else "Layout iOS")
            }
            "PC_MOD_CTRL" -> togglePcModifier(KeyEvent.META_CTRL_ON)
            "PC_MOD_ALT" -> togglePcModifier(KeyEvent.META_ALT_ON)
            "PC_MOD_META" -> togglePcModifier(KeyEvent.META_META_ON)
            "PC_CAPS_LOCK" -> {
                shiftState = if (shiftState == 2) 0 else 2
                activeSurface()?.setCapsLock(shiftState == 2)
            }
            "CLIPBOARD_HISTORY" -> startActivity(Intent(this, com.ioskeyboard.app.ui.ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "CLIPBOARD_CLEAR" -> {
                clipStore.clear(includePinned = false)
                refreshClips(currentClipFilter)
                toast("Item yang tidak disematkan dihapus")
            }
            "TRANSLATE_PREPARE" -> prepareTranslation()
            "TRANSLATE_RUN" -> runTranslation()
            "TRANSLATE_SWAP" -> {
                prefs.translateTarget = if (prefs.translateTarget == "id") "en" else "id"
                activeSurface()?.setTranslation(translateSource, prefs.translateTarget, translateResult, loading = false)
            }
            "TRANSLATE_INSERT" -> insertTranslation()
            "CURSOR_${KeyEvent.KEYCODE_DPAD_LEFT}" -> sendDpad(KeyEvent.KEYCODE_DPAD_LEFT)
            "CURSOR_${KeyEvent.KEYCODE_DPAD_RIGHT}" -> sendDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
            "EMOJI_SEARCH" -> toast("Pilih kategori emoji dengan tombol di atas")
            "EMOJI_BACK" -> activeSurface()?.setMode(KeyboardSurface.Mode.KEYBOARD)
            else -> if (action.startsWith("PC_KEY_")) dispatchPcKey(action)
        }
    }

    private fun togglePcModifier(mask: Int) {
        activeMetaState = activeMetaState xor mask
        activeSurface()?.invalidate()
    }

    private fun dispatchPcKey(action: String) {
        val code = when (action) {
            "PC_KEY_ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            "PC_KEY_TAB" -> KeyEvent.KEYCODE_TAB
            "PC_KEY_FUNCTION" -> KeyEvent.KEYCODE_FUNCTION
            "PC_KEY_DPAD_LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "PC_KEY_DPAD_DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "PC_KEY_DPAD_UP" -> KeyEvent.KEYCODE_DPAD_UP
            "PC_KEY_DPAD_RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "PC_KEY_HOME" -> KeyEvent.KEYCODE_MOVE_HOME
            "PC_KEY_END" -> KeyEvent.KEYCODE_MOVE_END
            "PC_KEY_PAGE_UP" -> KeyEvent.KEYCODE_PAGE_UP
            "PC_KEY_PAGE_DOWN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "PC_KEY_INSERT" -> KeyEvent.KEYCODE_INSERT
            "PC_KEY_FORWARD_DEL" -> KeyEvent.KEYCODE_FORWARD_DEL
            else -> action.removePrefix("PC_KEY_F").toIntOrNull()?.takeIf { it in 1..12 }?.let { KeyEvent.KEYCODE_F1 + it - 1 }
        }
        if (code != null) sendHardwareKey(code, activeMetaState)
    }

    private fun hardwareKeyCode(char: Char): Int? {
        val value = char.lowercaseChar()
        return when {
            value in 'a'..'z' -> KeyEvent.KEYCODE_A + (value - 'a')
            value in '0'..'9' -> when (value) {
                '0' -> KeyEvent.KEYCODE_0
                '1' -> KeyEvent.KEYCODE_1
                '2' -> KeyEvent.KEYCODE_2
                '3' -> KeyEvent.KEYCODE_3
                '4' -> KeyEvent.KEYCODE_4
                '5' -> KeyEvent.KEYCODE_5
                '6' -> KeyEvent.KEYCODE_6
                '7' -> KeyEvent.KEYCODE_7
                '8' -> KeyEvent.KEYCODE_8
                else -> KeyEvent.KEYCODE_9
            }
            else -> null
        }
    }

    private fun sendHardwareKey(code: Int, metaState: Int = activeMetaState) {
        val ic = currentInputConnection ?: return
        val downTime = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, metaState))
        ic.sendKeyEvent(KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, metaState))
    }

    override fun onClipSelected(item: ClipItem) {
        if (item.isImage) {
            if (!pasteImage(item)) toast("Gambar disalin. Aplikasi tujuan harus mendukung tempel gambar.")
            return
        }
        val ic = currentInputConnection
        if (ic != null) {
            ic.commitText(item.text, 1)
            refreshSuggestions()
        } else {
            clipboardManager?.setPrimaryClip(ClipData.newPlainText("Papan klip", item.text))
            toast("Disalin ke papan klip")
        }
    }

    private fun pasteImage(item: ClipItem): Boolean {
        val uri = clipStore.imageUri(item)
        if (uri == null) {
            toast("File gambar tidak ditemukan")
            return true
        }
        val mimeType = item.mimeType
        if (mimeType == null) {
            toast("Format gambar tidak diketahui")
            return true
        }
        val editor = currentInputEditorInfo
        val connection = currentInputConnection
        val accepted = editor?.let { EditorInfoCompat.getContentMimeTypes(it).any { mime -> mimeMatches(mimeType, mime) } } == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && accepted && editor != null && connection != null) {
            val content = InputContentInfoCompat(uri, ClipDescription("Gambar", arrayOf(mimeType)), null)
            val committed = InputConnectionCompat.commitContent(
                connection,
                editor,
                content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
            if (committed) {
                toast("Gambar ditempel")
                return true
            }
        }
        clipboardManager?.setPrimaryClip(ClipData.newUri(contentResolver, "Gambar", uri))
        return false
    }

    private fun mimeMatches(actual: String, accepted: String): Boolean =
        accepted == "*/*" || accepted.equals(actual, true) ||
            (accepted.endsWith("/*") && actual.startsWith(accepted.substringBefore('*'), true))

    override fun onClipPinned(item: ClipItem) {
        clipStore.togglePinned(item.id)
        refreshClips(currentClipFilter)
    }

    override fun onFeedback() { feedbackFromOverlay() }

    override fun onSuggestionsRequested() { refreshSuggestions() }

    override fun onClipsRequested(filter: String) {
        currentClipFilter = filter
        refreshClips(filter)
    }

    private fun refreshClips(filter: String) {
        val pinned = if (filter == "__pinned__") ClipStore.FILTER_PINNED else filter.ifBlank { null }
        activeSurface()?.setClips(clipStore.list(category = pinned, limit = 300), filter)
    }

    private fun prepareTranslation() {
        if (sensitiveField) {
            toast("Terjemahan dinonaktifkan di kolom sandi/pribadi.")
            return
        }
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString().orEmpty()
        translateSelectionMode = selected.isNotBlank()
        translateSource = if (translateSelectionMode) selected else wordBeforeCursor()
        translateReplaceLength = if (translateSelectionMode) 0 else translateSource.length
        translateResult = ""
        val target = prefs.translateTarget
        activeSurface()?.setTranslation(translateSource, target, "", if (translateSource.isBlank()) "Pilih teks lalu ketuk Terjemahkan." else "", false)
        if (translateSource.isNotBlank()) runTranslation()
    }

    private fun runTranslation() {
        if (sensitiveField) return
        if (translateSource.isBlank()) prepareTranslation()
        if (translateSource.isBlank()) {
            activeSurface()?.setTranslation("", prefs.translateTarget, "", "Pilih teks di aplikasi, lalu coba lagi.", false)
            return
        }
        val token = ++translationToken
        val target = prefs.translateTarget
        activeSurface()?.setTranslation(translateSource, target, "", "", true)
        TranslateClient.translate(translateSource, "auto", target) { result ->
            main.post {
                if (token != translationToken) return@post
                result.fold(
                    onSuccess = {
                        translateResult = it
                        activeSurface()?.setTranslation(translateSource, target, it, "", false)
                    },
                    onFailure = {
                        activeSurface()?.setTranslation(translateSource, target, "", it.message ?: "Terjemahan gagal. Cek koneksi internet.", false)
                    }
                )
            }
        }
    }

    private fun insertTranslation() {
        if (sensitiveField) return
        val result = translateResult
        if (result.isBlank()) {
            toast("Belum ada hasil terjemahan")
            return
        }
        val ic = currentInputConnection ?: return
        if (translateSelectionMode) {
            ic.commitText(result, 1)
        } else {
            val nowWord = wordBeforeCursor()
            if (nowWord.isNotBlank() && nowWord.length == translateReplaceLength) ic.deleteSurroundingText(nowWord.length, 0)
            ic.commitText(result, 1)
        }
        translateSource = ""
        translateResult = ""
        activeSurface()?.setMode(KeyboardSurface.Mode.KEYBOARD)
        refreshSuggestions()
    }

    private fun enableFloatingKeyboard() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Izinkan 'Tampil di atas aplikasi lain' dulu di pengaturan.")
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }
        prefs.floatingKeyboard = true
        FloatingService.startKeyboard(this)
        hideImeForFloating()
    }

    fun hideImeForFloating() {
        requestHideSelf(0)
    }

    fun showImeAfterFloating() {
        prefs.floatingKeyboard = false
        requestShowSelf(0)
    }

    fun insertFromOverlay(text: String) {
        if (currentInputConnection != null) {
            currentInputConnection?.commitText(text, 1)
            refreshSuggestions()
        } else {
            clipboardManager?.setPrimaryClip(ClipData.newPlainText("Papan klip", text))
        }
    }

    fun feedbackFromOverlay() {
        if (prefs.hapticsEnabled && prefs.hapticStrength > 0) {
            try {
                val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(10, (prefs.hapticStrength * 2.55f).toInt().coerceIn(1, 255)))
                else @Suppress("DEPRECATION") vibrator.vibrate(10)
            } catch (_: Exception) { }
        }
    }

    private fun sendDpad(code: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun playKeySound(returnKey: Boolean = false) {
        if (!prefs.soundEnabled) return
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.playSoundEffect(if (returnKey) AudioManager.FX_KEYPRESS_RETURN else AudioManager.FX_KEYPRESS_STANDARD, prefs.soundVolume / 100f)
        } catch (_: Exception) { }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private val WORD_AT_END = Regex("[\\p{L}\\p{M}]+$")
        private val WORD_IN_TEXT = Regex("[\\p{L}\\p{M}]+")
        private val swipeRequestId = java.util.concurrent.atomic.AtomicInteger()
    }
}
