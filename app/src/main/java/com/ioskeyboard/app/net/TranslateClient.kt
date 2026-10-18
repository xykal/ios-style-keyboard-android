package com.ioskeyboard.app.net

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Explicit, one-shot translation requests. No keystrokes are uploaded in the background. */
object TranslateClient {
    private const val MAX_QUERY_BYTES = 450 // MyMemory documents a maximum of 500 UTF-8 bytes per q.
    private const val MAX_INPUT_CODE_POINTS = 4500
    private const val AUTO_DETECT_SOURCE = "Autodetect" // MyMemory rejects the common "auto" alias.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "one-shot-translate").apply { isDaemon = true } }
    private val requestExecutor = Executors.newFixedThreadPool(3) { r -> Thread(r, "translation-request").apply { isDaemon = true } }

    private data class QueryChunk(val text: String, val separatorAfter: String)

    fun translate(text: String, source: String, target: String, callback: (Result<String>) -> Unit) {
        val trimmed = limitCodePoints(text.trim(), MAX_INPUT_CODE_POINTS)
        if (trimmed.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Pilih atau ketik teks yang mau diterjemahkan.")))
            return
        }
        val chunks = splitIntoQueries(trimmed)
        if (chunks.isEmpty()) {
            callback(Result.failure(IllegalArgumentException("Pilih atau ketik teks yang mau diterjemahkan.")))
            return
        }
        val sourceCode = source.trim().ifBlank { AUTO_DETECT_SOURCE }.let {
            if (it.equals("auto", ignoreCase = true)) AUTO_DETECT_SOURCE else it
        }
        val targetCode = target.trim().ifBlank { "id" }
        executor.execute {
            val futures = ArrayList<Future<String>>(chunks.size)
            val result = try {
                chunks.forEach { chunk ->
                    futures += requestExecutor.submit(Callable { requestChunk(chunk.text, sourceCode, targetCode) })
                }
                val translated = futures.map { it.get() }
                val assembled = buildString {
                    translated.forEachIndexed { index, segment ->
                        if (index > 0) append(chunks[index - 1].separatorAfter)
                        append(segment.trim())
                    }
                }.trim()
                if (assembled.isBlank()) throw IllegalStateException("Hasil terjemahan kosong.")
                Result.success(assembled)
            } catch (e: Exception) {
                futures.forEach { it.cancel(true) }
                val cause = (e as? ExecutionException)?.cause ?: e
                Result.failure(cause)
            }
            callback(result)
        }
    }

    private fun requestChunk(queryText: String, source: String, target: String): String {
        val query = URLEncoder.encode(queryText, StandardCharsets.UTF_8.name())
        val langs = URLEncoder.encode("$source|$target", StandardCharsets.UTF_8.name())
        val connection = (URL("https://api.mymemory.translated.net/get?q=$query&langpair=$langs").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 9000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "iOSKeyboardAndroid/1.1")
        }
        try {
            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                throw IllegalStateException("Layanan terjemahan menolak permintaan ($responseCode).")
            }
            val json = JSONObject(body)
            val status = json.optInt("responseStatus", 200)
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("responseDetails").ifBlank { "Terjemahan gagal." })
            }
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            val decoded = Html.fromHtml(translated, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            if (decoded.isBlank()) throw IllegalStateException("Hasil terjemahan kosong.")
            return decoded
        } finally {
            connection.disconnect()
        }
    }

    /** Split by Unicode code point so a multibyte character is never cut in half. */
    private fun splitIntoQueries(text: String): List<QueryChunk> {
        val chunks = ArrayList<QueryChunk>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && Character.isWhitespace(text.codePointAt(start))) {
                start += Character.charCount(text.codePointAt(start))
            }
            if (start >= text.length) break

            var end = start
            var bytes = 0
            var lastWhitespaceBoundary = -1
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val next = end + Character.charCount(codePoint)
                val codePointBytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
                if (bytes + codePointBytes > MAX_QUERY_BYTES) break
                bytes += codePointBytes
                end = next
                if (Character.isWhitespace(codePoint)) lastWhitespaceBoundary = end
            }
            if (end < text.length && lastWhitespaceBoundary > start) end = lastWhitespaceBoundary
            if (end == start) end += Character.charCount(text.codePointAt(start))

            val raw = text.substring(start, end)
            val chunkText = raw.trim()
            val separatorStart = start + raw.trimEnd().length
            val separator = text.substring(separatorStart, end)
            if (chunkText.isNotEmpty()) chunks += QueryChunk(chunkText, separator)
            start = end
        }
        return chunks
    }

    private fun limitCodePoints(text: String, maximum: Int): String {
        var index = 0
        var count = 0
        while (index < text.length && count < maximum) {
            index += Character.charCount(text.codePointAt(index))
            count++
        }
        return if (index < text.length) text.substring(0, index) else text
    }
}
