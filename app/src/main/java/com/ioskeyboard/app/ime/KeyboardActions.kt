package com.ioskeyboard.app.ime

import com.ioskeyboard.app.data.ClipItem

/** Callbacks shared by the normal IME view and the floating keyboard overlay. */
interface KeyboardActions {
    fun onText(text: String)
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onShift()
    fun onCandidate(word: String)
    fun onSwipe(signature: String)
    fun onEmoji(emoji: String)
    fun onAction(action: String)
    fun onClipSelected(item: ClipItem)
    fun onClipPinned(item: ClipItem)
    fun onFeedback()
    fun onSuggestionsRequested()
    fun onClipsRequested(filter: String)
}
