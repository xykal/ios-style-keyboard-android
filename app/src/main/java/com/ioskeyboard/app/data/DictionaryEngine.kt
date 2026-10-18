package com.ioskeyboard.app.data

import android.content.Context
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Small, bundled offline prediction engine. Loads the two ranked dictionaries and spell
 * lists once on a worker thread; no network calls are made for prediction or swipe typing.
 */
class DictionaryEngine private constructor(context: Context) {
    private val app = context.applicationContext
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dictionary-loader").apply { isDaemon = true } }
    @Volatile private var data: DictionaryData? = null
    @Volatile private var loading = false

    private data class DictionaryData(
        val rankedId: List<Word>,
        val rankedEn: List<Word>,
        val knownId: Set<String>,
        val knownEn: Set<String>,
        val rankId: Map<String, Int>,
        val rankEn: Map<String, Int>,
        val prefixId: Map<String, List<String>>,
        val prefixEn: Map<String, List<String>>
    )

    private data class Word(val value: String, val rank: Int)

    fun warmUp() {
        if (loading || data != null) return
        synchronized(this) {
            if (loading || data != null) return
            loading = true
            loader.execute {
                try {
                    val idRanked = readRanked("dict_id.txt")
                    val enRanked = readRanked("dict_en.txt")
                    val idWords = readWords("spell_id.txt").toMutableSet().apply { addAll(idRanked.map { it.value }) }
                    val enWords = readWords("spell_en.txt").toMutableSet().apply { addAll(enRanked.map { it.value }) }
                    data = DictionaryData(
                        rankedId = idRanked,
                        rankedEn = enRanked,
                        knownId = idWords,
                        knownEn = enWords,
                        rankId = idRanked.mapIndexed { index, word -> word.value to index }.toMap(),
                        rankEn = enRanked.mapIndexed { index, word -> word.value to index }.toMap(),
                        prefixId = buildPrefixIndex(idRanked),
                        prefixEn = buildPrefixIndex(enRanked)
                    )
                } catch (_: Exception) {
                    // Keep the keyboard responsive with an empty prediction set if assets are unavailable.
                    data = DictionaryData(emptyList(), emptyList(), emptySet(), emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
                } finally {
                    loading = false
                }
            }
        }
    }

    fun isReady(): Boolean = data != null

    fun isKnown(word: String, language: String): Boolean {
        val d = data ?: return true // avoid false auto-corrections during warm-up
        return word.lowercase(Locale.ROOT) in if (language == LANG_EN) d.knownEn else d.knownId
    }

    fun suggest(prefix: String, language: String, limit: Int = 3): List<String> {
        val d = data ?: return emptyList()
        val clean = prefix.lowercase(Locale.ROOT).trim()
        if (clean.isEmpty()) return emptyList()
        val index = if (language == LANG_EN) d.prefixEn else d.prefixId
        // Prefix candidates are precomputed from the frequency-ranked list to keep each
        // keypress bounded and fast; only the first 60 likely words are retained per prefix.
        return index[clean.take(4)].orEmpty()
            .asSequence()
            .filter { it.startsWith(clean) && it != clean }
            .take(limit)
            .toList()
    }

    /** Small offline context hints for common next-word positions; no typed text leaves the device. */
    fun suggestNext(previousWord: String, language: String, limit: Int = 3): List<String> {
        val key = previousWord.trim().lowercase(Locale.ROOT)
        if (key.isBlank()) return emptyList()
        val source = if (language == LANG_EN) NEXT_EN else NEXT_ID
        return source[key].orEmpty().take(limit)
    }

    /** One-edit keyboard typo correction, using hash-set membership rather than scanning the corpus. */
    fun correctOneEdit(input: String, language: String): String? {
        val d = data ?: return null
        val word = input.lowercase(Locale.ROOT)
        if (word.length !in 2..18 || isKnown(word, language)) return null
        val ranked = if (language == LANG_EN) d.rankedEn else d.rankedId
        val known = if (language == LANG_EN) d.knownEn else d.knownId
        val rank = if (language == LANG_EN) d.rankEn else d.rankId
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        fun consider(candidate: String) {
            val candidateRank = rank[candidate] ?: return
            if (candidate != word && candidateRank < bestRank) {
                best = candidate
                bestRank = candidateRank
            }
        }
        // Deletion and transposition.
        for (i in word.indices) {
            val deleted = word.removeRange(i, i + 1)
            if (deleted in known) consider(deleted)
            if (i + 1 < word.length && word[i] != word[i + 1]) {
                val chars = word.toCharArray()
                val c = chars[i]
                chars[i] = chars[i + 1]
                chars[i + 1] = c
                val swapped = String(chars)
                if (swapped in known) consider(swapped)
            }
        }
        // Substitution and insertion (QWERTY-friendly correction comes from the ranked choice).
        for (i in word.indices) {
            for (c in 'a'..'z') {
                if (c == word[i]) continue
                val candidate = word.substring(0, i) + c + word.substring(i + 1)
                if (candidate in known) consider(candidate)
            }
        }
        if (word.length <= 14) {
            for (i in 0..word.length) {
                for (c in 'a'..'z') {
                    val candidate = word.substring(0, i) + c + word.substring(i)
                    if (candidate in known) consider(candidate)
                }
            }
        }
        return best
    }

    /** Returns the best ranked word for a collapsed, left-to-right swipe-key signature. */
    fun bestSwipe(signature: String, language: String, limit: Int = 3): List<String> {
        val d = data ?: return emptyList()
        val path = signature.filter { it in 'a'..'z' }.take(24)
        if (path.length < 2) return emptyList()
        val words = if (language == LANG_EN) d.rankedEn else d.rankedId
        val scored = ArrayList<Pair<String, Int>>(32)
        val minLen = (path.length - maxOf(2, path.length / 3)).coerceAtLeast(2)
        val maxLen = (path.length + maxOf(2, path.length / 3)).coerceAtMost(18)
        for (word in words) {
            if (word.value.length !in minLen..maxLen) continue
            val dist = boundedDistance(path, word.value, maxOf(3, path.length / 2))
            if (dist <= maxOf(3, path.length / 2)) {
                // Frequency is a small tie-breaker, not enough to beat a clear path match.
                val score = dist * 100 + abs(path.length - word.value.length) * 12 + (word.rank / 1800)
                scored += word.value to score
            }
        }
        return scored.sortedBy { it.second }.distinctBy { it.first }.take(limit).map { it.first }
    }

    private fun boundedDistance(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                rowMin = minOf(rowMin, curr[j])
            }
            if (rowMin > max) return max + 1
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
    }

    private fun buildPrefixIndex(words: List<Word>): Map<String, List<String>> {
        val mutable = HashMap<String, MutableList<String>>(4096)
        for (word in words) {
            for (size in 1..minOf(4, word.value.length)) {
                val key = word.value.substring(0, size)
                val list = mutable.getOrPut(key) { ArrayList(24) }
                if (list.size < 60) list.add(word.value)
            }
        }
        return mutable
    }

    private fun readRanked(asset: String): List<Word> = app.assets.open(asset).bufferedReader().useLines { lines ->
        lines.mapNotNull { line ->
            val split = line.split('\t', limit = 2)
            if (split.isEmpty()) null else Word(split[0].trim().lowercase(Locale.ROOT), split.getOrNull(1)?.toIntOrNull() ?: 0)
        }.filter { it.value.length in 2..18 }.toList()
    }

    private fun readWords(asset: String): Set<String> = app.assets.open(asset).bufferedReader().useLines { lines ->
        lines.map { it.trim().lowercase(Locale.ROOT) }.filter { it.length in 2..20 }.toSet()
    }

    companion object {
        const val LANG_ID = "id"
        const val LANG_EN = "en"

        private val NEXT_ID = mapOf(
            "aku" to listOf("ingin", "mau", "bisa", "sudah", "tidak"),
            "saya" to listOf("ingin", "akan", "bisa", "sudah", "tidak"),
            "kamu" to listOf("bisa", "mau", "akan", "sudah", "tidak"),
            "anda" to listOf("dapat", "bisa", "akan", "sudah", "tidak"),
            "yang" to listOf("bisa", "ada", "akan", "lebih", "sudah"),
            "tidak" to listOf("bisa", "ada", "akan", "perlu", "tahu"),
            "terima" to listOf("kasih"),
            "selamat" to listOf("pagi", "siang", "sore", "malam", "datang"),
            "apa" to listOf("kabar", "yang", "saja", "itu", "ini"),
            "di" to listOf("sini", "sana", "dalam", "atas", "bawah"),
            "ke" to listOf("rumah", "sana", "depan", "kantor", "kota"),
            "untuk" to listOf("kamu", "saya", "kita", "membuat", "itu")
        )
        private val NEXT_EN = mapOf(
            "i" to listOf("am", "have", "will", "think", "can"),
            "you" to listOf("are", "can", "will", "have", "should"),
            "we" to listOf("can", "are", "will", "have", "need"),
            "the" to listOf("best", "same", "first", "new", "other"),
            "thank" to listOf("you"),
            "how" to listOf("are", "do", "can", "much", "about"),
            "please" to listOf("let", "send", "help", "and", "confirm"),
            "to" to listOf("the", "be", "get", "do", "make"),
            "good" to listOf("morning", "afternoon", "evening", "night", "luck"),
            "not" to listOf("sure", "available", "working", "ready", "yet")
        )

        @Volatile private var instance: DictionaryEngine? = null
        fun get(context: Context): DictionaryEngine = instance ?: synchronized(this) {
            instance ?: DictionaryEngine(context).also { instance = it }
        }
    }
}
