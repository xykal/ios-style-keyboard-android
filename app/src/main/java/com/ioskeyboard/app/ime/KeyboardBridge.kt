package com.ioskeyboard.app.ime

import android.content.ClipData
import android.content.Context
import android.widget.Toast
import com.ioskeyboard.app.data.ClipItem
import com.ioskeyboard.app.data.ClipStore
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference

/** In-process bridge used by the non-focusable floating keyboard and clipboard overlays. */
object KeyboardBridge {
    @Volatile private var current = WeakReference<KeyboardIME>(null)

    fun attach(ime: KeyboardIME) { current = WeakReference(ime) }
    fun detach(ime: KeyboardIME) { if (current.get() === ime) current.clear() }
    fun get(): KeyboardIME? = current.get()

    fun insertText(context: Context, text: String) {
        val ime = current.get()
        if (ime != null) {
            Handler(Looper.getMainLooper()).post { ime.insertFromOverlay(text) }
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("iOS Keyboard", text))
        }
    }

    fun insertClip(context: Context, item: ClipItem) {
        val ime = current.get()
        if (ime != null) {
            Handler(Looper.getMainLooper()).post { ime.onClipSelected(item) }
            return
        }
        if (item.isImage) {
            val uri = ClipStore.get(context).imageUri(item)
            if (uri != null) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Gambar", uri))
                Toast.makeText(context, "Gambar disalin ke papan klip", Toast.LENGTH_SHORT).show()
            }
        } else {
            insertText(context, item.text)
        }
    }

    fun hideImeForFloating() {
        Handler(Looper.getMainLooper()).post { current.get()?.hideImeForFloating() }
    }

    fun showImeAfterFloating(context: Context) {
        Handler(Looper.getMainLooper()).post {
            val ime = current.get()
            if (ime != null) ime.showImeAfterFloating()
            else {
                val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showInputMethodPicker()
            }
        }
    }

    fun feedback() { Handler(Looper.getMainLooper()).post { current.get()?.feedbackFromOverlay() } }
}
