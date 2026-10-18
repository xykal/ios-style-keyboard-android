package com.ioskeyboard.app.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Explicit, one-shot translation requests. No keystrokes are uploaded in the background. */
object TranslateClient {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "one-shot-translate").apply { isDaemon = true } }

    fun translate(text: String, source: String, target: String, callback: (Result<String>) -> Unit) {
        val trimmed = text.trim().take(4500)
        if (trimmed.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Pilih atau ketik teks yang mau diterjemahkan.")))
            return
        }
        executor.execute {
            try {
                val langPair = "${source.ifBlank { "auto" }}|${target.ifBlank { "id" }}"
                val query = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
                val langs = URLEncoder.encode(langPair, StandardCharsets.UTF_8.name())
                val connection = (URL("https://api.mymemory.translated.net/get?q=$query&langpair=$langs").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 9000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "iOSKeyboardAndroid/1.0")
                }
                val responseCode = connection.responseCode
                val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (responseCode !in 200..299) throw IllegalStateException("Layanan terjemahan menolak permintaan ($responseCode).")
                val json = JSONObject(body)
                val status = json.optInt("responseStatus", 200)
                if (status !in 200..299) throw IllegalStateException(json.optString("responseDetails").ifBlank { "Terjemahan gagal." })
                val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
                if (translated.isBlank()) throw IllegalStateException("Hasil terjemahan kosong.")
                callback(Result.success(translated.replace("&quot;", "\"").replace("&#39;", "'")))
            } catch (e: Exception) {
                callback(Result.failure(e))
            }
        }
    }
}
