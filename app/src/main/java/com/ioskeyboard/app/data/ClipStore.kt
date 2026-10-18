package com.ioskeyboard.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.content.FileProvider
import com.ioskeyboard.app.util.Prefs
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Local, offline SQLite history. Image files live in app-private storage and are never backed up. */
class ClipStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_PINNED INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED INTEGER NOT NULL,
                $COL_UPDATED INTEGER NOT NULL,
                $COL_IMAGE_PATH TEXT,
                $COL_MIME_TYPE TEXT,
                $COL_IMAGE_HASH TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clip_updated ON $TABLE($COL_PINNED DESC, $COL_UPDATED DESC)")
        db.execSQL("CREATE INDEX idx_clip_category ON $TABLE($COL_CATEGORY)")
        db.execSQL("CREATE INDEX idx_clip_image_hash ON $TABLE($COL_IMAGE_HASH)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_IMAGE_PATH TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_MIME_TYPE TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_IMAGE_HASH TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_clip_image_hash ON $TABLE($COL_IMAGE_HASH)")
        }
    }

    /** Save/deduplicate text. Pinned items are never removed by automatic retention. */
    fun save(text: String, category: String = ClipItem.classify(text)): Long? {
        val value = text.trim().take(MAX_CLIP_CHARS)
        if (value.isBlank()) return null
        return synchronized(lock) {
            val db = writableDatabase
            val existing = db.query(
                TABLE, arrayOf(COL_ID, COL_PINNED, COL_CATEGORY), "$COL_TEXT = ? AND $COL_IMAGE_PATH IS NULL",
                arrayOf(value), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getInt(1) != 0, cursor.getString(2)) else null
            }
            val now = System.currentTimeMillis()
            val id = if (existing != null) {
                val cv = ContentValues().apply {
                    put(COL_UPDATED, now)
                    // Keep a manually chosen category; auto-classify only generic items.
                    if (existing.third == ClipItem.CATEGORY_TEXT && category != ClipItem.CATEGORY_TEXT) put(COL_CATEGORY, category)
                }
                db.update(TABLE, cv, "$COL_ID = ?", arrayOf(existing.first.toString()))
                existing.first
            } else {
                val cv = ContentValues().apply {
                    put(COL_TEXT, value)
                    put(COL_CATEGORY, category)
                    put(COL_PINNED, 0)
                    put(COL_CREATED, now)
                    put(COL_UPDATED, now)
                }
                db.insert(TABLE, null, cv).takeIf { it >= 0L }
            }
            trimToLimit(db)
            id
        }
    }

    /** Copy a clipboard image into the app's private directory, enforce byte limits, and deduplicate. */
    fun saveImage(source: Uri, requestedMimeType: String?): Long? {
        val requested = requestedMimeType?.substringBefore(';')?.lowercase().orEmpty()
        val resolved = requested.takeIf { it in ALLOWED_IMAGE_MIME_TYPES }
            ?: appContext.contentResolver.getType(source)?.substringBefore(';')?.lowercase().orEmpty()
        val mimeType = resolved
        if (mimeType !in ALLOWED_IMAGE_MIME_TYPES) return null
        val directory = imageDirectory().apply { if (!exists() && !mkdirs()) return null }
        val extension = IMAGE_EXTENSIONS[mimeType] ?: return null
        val file = File(directory, "${java.util.UUID.randomUUID()}.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        val input = try { appContext.contentResolver.openInputStream(source) } catch (_: Exception) { null }
        if (input == null) {
            file.delete()
            return null
        }
        val copied = try {
            input.use { stream ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SINGLE_IMAGE_BYTES) throw ImageTooLargeException()
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    total > 0L
                }
            }
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if (!copied) {
            file.delete()
            return null
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val relativePath = "clipboard-images/${file.name}"
        return synchronized(lock) {
            val db = writableDatabase
            val existingId = db.query(
                TABLE, arrayOf(COL_ID), "$COL_IMAGE_HASH = ?", arrayOf(hash), null, null, null, "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            val now = System.currentTimeMillis()
            val id = if (existingId != null) {
                db.update(TABLE, ContentValues().apply { put(COL_UPDATED, now) }, "$COL_ID = ?", arrayOf(existingId.toString()))
                file.delete()
                existingId
            } else {
                val values = ContentValues().apply {
                    put(COL_TEXT, "")
                    put(COL_CATEGORY, ClipItem.CATEGORY_IMAGE)
                    put(COL_PINNED, 0)
                    put(COL_CREATED, now)
                    put(COL_UPDATED, now)
                    put(COL_IMAGE_PATH, relativePath)
                    put(COL_MIME_TYPE, mimeType)
                    put(COL_IMAGE_HASH, hash)
                }
                val insertedId = db.insert(TABLE, null, values)
                if (insertedId < 0L) {
                    file.delete()
                    null
                } else {
                    trimToLimit(db)
                    trimImageStorage(db)
                    val retained = db.query(TABLE, arrayOf(COL_ID), "$COL_ID = ?", arrayOf(insertedId.toString()), null, null, null, "1")
                        .use { cursor -> cursor.moveToFirst() }
                    insertedId.takeIf { retained }
                }
            }
            id
        }
    }

    fun imageUri(item: ClipItem): Uri? {
        val relativePath = item.imagePath ?: return null
        return try {
            val root = imageDirectory().canonicalFile
            val file = File(appContext.filesDir, relativePath).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }

    fun list(category: String? = null, query: String? = null, limit: Int = 500): List<ClipItem> = synchronized(lock) {
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (!category.isNullOrBlank()) {
            if (category == FILTER_PINNED) clauses += "$COL_PINNED = 1"
            else {
                clauses += "$COL_CATEGORY = ?"
                args += category
            }
        }
        if (!query.isNullOrBlank()) {
            val needle = query.trim()
            val pattern = "%${escapeLike(needle)}%"
            clauses += "($COL_TEXT LIKE ? ESCAPE '!' OR ($COL_CATEGORY = ? AND ($COL_MIME_TYPE LIKE ? ESCAPE '!' OR ? = 'gambar' OR ? = 'image')))"
            args += pattern
            args += ClipItem.CATEGORY_IMAGE
            args += pattern
            args += needle.lowercase()
            args += needle.lowercase()
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = ArrayList<ClipItem>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_TEXT, COL_CATEGORY, COL_PINNED, COL_CREATED, COL_UPDATED, COL_IMAGE_PATH, COL_MIME_TYPE),
            where,
            if (args.isEmpty()) null else args.toTypedArray(),
            null,
            null,
            "$COL_PINNED DESC, $COL_UPDATED DESC",
            limit.coerceIn(1, 5000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) out += ClipItem(
                id = cursor.getLong(0),
                text = cursor.getString(1),
                category = cursor.getString(2),
                pinned = cursor.getInt(3) != 0,
                createdAt = cursor.getLong(4),
                updatedAt = cursor.getLong(5),
                imagePath = cursor.getString(6),
                mimeType = cursor.getString(7)
            )
        }
        out
    }

    fun get(id: Long): ClipItem? = list(limit = 5000).firstOrNull { it.id == id }

    fun count(): Int = synchronized(lock) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun togglePinned(id: Long): Boolean = synchronized(lock) {
        val current = getPinned(id) ?: return@synchronized false
        val cv = ContentValues().apply {
            put(COL_PINNED, if (current) 0 else 1)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    fun setPinned(id: Long, pinned: Boolean): Boolean = synchronized(lock) {
        val cv = ContentValues().apply {
            put(COL_PINNED, if (pinned) 1 else 0)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    fun setCategory(id: Long, category: String): Boolean = synchronized(lock) {
        val allowed = setOf(ClipItem.CATEGORY_TEXT, ClipItem.CATEGORY_LINK, ClipItem.CATEGORY_EMAIL, ClipItem.CATEGORY_CODE, ClipItem.CATEGORY_NOTE)
        if (category !in allowed) return@synchronized false
        val cv = ContentValues().apply { put(COL_CATEGORY, category) }
        writableDatabase.update(TABLE, cv, "$COL_ID = ? AND $COL_IMAGE_PATH IS NULL", arrayOf(id.toString())) > 0
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        val path = readableDatabase.query(TABLE, arrayOf(COL_IMAGE_PATH), "$COL_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val deleted = writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString())) > 0
        if (deleted && path != null) deleteImageFile(path)
        deleted
    }

    /** Clear non-pinned clips, or all clips when [includePinned] is true. */
    fun clear(includePinned: Boolean = false): Int = synchronized(lock) {
        val deleteAll = includePinned || !Prefs.get(appContext).keepClipboardOnClear
        val where = if (deleteAll) null else "$COL_PINNED = 0"
        val paths = ArrayList<String>()
        readableDatabase.query(TABLE, arrayOf(COL_IMAGE_PATH), where, null, null, null, null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(paths::add)
        }
        val removed = writableDatabase.delete(TABLE, where, null)
        paths.forEach(::deleteImageFile)
        removed
    }

    private fun getPinned(id: Long): Boolean? = synchronized(lock) {
        readableDatabase.query(TABLE, arrayOf(COL_PINNED), "$COL_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }
    }

    private fun trimToLimit(db: SQLiteDatabase) {
        val max = Prefs.get(appContext).clipboardMaxItems
        val deleteWhere = "$COL_PINNED = 0 AND $COL_ID NOT IN (SELECT $COL_ID FROM $TABLE WHERE $COL_PINNED = 0 ORDER BY $COL_UPDATED DESC LIMIT ?)"
        val paths = ArrayList<String>()
        db.query(TABLE, arrayOf(COL_IMAGE_PATH), deleteWhere, arrayOf(max.toString()), null, null, null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let(paths::add)
        }
        db.delete(TABLE, deleteWhere, arrayOf(max.toString()))
        paths.forEach(::deleteImageFile)
    }

    private fun trimImageStorage(db: SQLiteDatabase) {
        data class StoredImage(val id: Long, val path: String, val pinned: Boolean, val bytes: Long)
        val images = ArrayList<StoredImage>()
        db.query(TABLE, arrayOf(COL_ID, COL_IMAGE_PATH, COL_PINNED), "$COL_IMAGE_PATH IS NOT NULL", null, null, null, "$COL_CREATED ASC").use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                images += StoredImage(c.getLong(0), path, c.getInt(2) != 0, imageFile(path)?.length() ?: 0L)
            }
        }
        var total = images.sumOf { it.bytes }
        for (image in images) {
            if (total <= MAX_IMAGE_STORAGE_BYTES) break
            if (image.pinned) continue
            db.delete(TABLE, "$COL_ID = ?", arrayOf(image.id.toString()))
            deleteImageFile(image.path)
            total -= image.bytes
        }
    }

    private fun imageDirectory(): File = File(appContext.filesDir, IMAGE_DIRECTORY)

    private fun imageFile(relativePath: String): File? = try {
        val root = imageDirectory().canonicalFile
        val file = File(appContext.filesDir, relativePath).canonicalFile
        if (file.path.startsWith(root.path + File.separator)) file else null
    } catch (_: IOException) {
        null
    }

    private fun deleteImageFile(relativePath: String) {
        imageFile(relativePath)?.delete()
    }

    private fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    companion object {
        private const val DB_NAME = "clipboard_history.db"
        private const val DB_VERSION = 2
        private const val TABLE = "clips"
        private const val COL_ID = "id"
        private const val COL_TEXT = "text"
        private const val COL_CATEGORY = "category"
        private const val COL_PINNED = "pinned"
        private const val COL_CREATED = "created_at"
        private const val COL_UPDATED = "updated_at"
        private const val COL_IMAGE_PATH = "image_path"
        private const val COL_MIME_TYPE = "mime_type"
        private const val COL_IMAGE_HASH = "image_hash"
        private const val IMAGE_DIRECTORY = "clipboard-images"
        private const val MAX_CLIP_CHARS = 20_000
        private const val MAX_SINGLE_IMAGE_BYTES = 6L * 1024L * 1024L
        private const val MAX_IMAGE_STORAGE_BYTES = 40L * 1024L * 1024L
        private val ALLOWED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic", "image/avif")
        private val IMAGE_EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif", "image/heic" to "heic", "image/avif" to "avif")
        const val FILTER_PINNED = "__pinned__"

        @Volatile private var instance: ClipStore? = null
        fun get(context: Context): ClipStore = instance ?: synchronized(this) {
            instance ?: ClipStore(context).also { instance = it }
        }
    }
}

private class ImageTooLargeException : RuntimeException()
