package com.ioskeyboard.app.data

data class ClipItem(
    val id: Long,
    val text: String,
    val category: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Relative path inside the app-private files directory; never a raw external URI. */
    val imagePath: String? = null,
    val mimeType: String? = null
) {
    val isImage: Boolean get() = !imagePath.isNullOrBlank()

    val preview: String
        get() = if (isImage) {
            "Gambar${mimeType?.substringAfter('/')?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        } else {
            text.replace('\n', ' ').replace('\r', ' ').trim().let {
                if (it.length > 120) it.take(117) + "…" else it
            }
        }

    val displayTitle: String
        get() = when (category) {
            CATEGORY_LINK -> "Tautan"
            CATEGORY_EMAIL -> "Email"
            CATEGORY_CODE -> "Kode"
            CATEGORY_NOTE -> "Catatan"
            CATEGORY_IMAGE -> "Gambar"
            else -> "Teks"
        }

    companion object {
        const val CATEGORY_TEXT = "text"
        const val CATEGORY_LINK = "link"
        const val CATEGORY_EMAIL = "email"
        const val CATEGORY_CODE = "code"
        const val CATEGORY_NOTE = "note"
        const val CATEGORY_IMAGE = "image"

        fun classify(text: String): String {
            val value = text.trim()
            return when {
                value.startsWith("https://", true) || value.startsWith("http://", true) ||
                    value.startsWith("www.", true) -> CATEGORY_LINK
                Regex("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", RegexOption.IGNORE_CASE).matches(value) -> CATEGORY_EMAIL
                value.contains("{\n") || value.contains(";\n") || value.startsWith("#!/") ||
                    value.contains("fun ") || value.contains("class ") || value.contains("SELECT ", true) -> CATEGORY_CODE
                value.contains('\n') || value.length > 180 -> CATEGORY_NOTE
                else -> CATEGORY_TEXT
            }
        }
    }
}
