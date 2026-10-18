package com.ioskeyboard.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ioskeyboard.app.util.Prefs
import java.util.concurrent.atomic.AtomicBoolean

/** Local, offline SQLite history. Pinned items survive automatic trimming and bulk clear. */
class ClipStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val opened = AtomicBoolean(false)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TEXT TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_PINNED INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED INTEGER NOT NULL,
                $COL_UPDATED INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clip_updated ON $TABLE($COL_PINNED DESC, $COL_UPDATED DESC)")
        db.execSQL("CREATE INDEX idx_clip_category ON $TABLE($COL_CATEGORY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 table already held the durable history; future migrations can be added here.
        }
    }

    /** Save/deduplicate text. Pinned items are never removed by automatic retention. */
    fun save(text: String, category: String = ClipItem.classify(text)): Long? {
        val value = text.trim().take(MAX_CLIP_CHARS)
        if (value.isBlank()) return null
        return synchronized(lock) {
            val db = writableDatabase
            val existing = db.query(
                TABLE, arrayOf(COL_ID, COL_PINNED, COL_CATEGORY), "$COL_TEXT = ?",
                arrayOf(value), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getInt(1) != 0, cursor.getString(2)) else null
            }
            val now = System.currentTimeMillis()
            val id = if (existing != null) {
                val cv = ContentValues().apply {
                    put(COL_UPDATED, now)
                    // Keep the user's manually chosen category; auto-classify only generic items.
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
            clauses += "$COL_TEXT LIKE ? ESCAPE '!'"
            args += "%${escapeLike(query.trim())}%"
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = ArrayList<ClipItem>()
        readableDatabase.query(
            TABLE,
            arrayOf(COL_ID, COL_TEXT, COL_CATEGORY, COL_PINNED, COL_CREATED, COL_UPDATED),
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
                updatedAt = cursor.getLong(5)
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
        writableDatabase.update(TABLE, cv, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    /** Clear non-pinned clips, or all clips when [includePinned] is true. */
    fun clear(includePinned: Boolean = false): Int = synchronized(lock) {
        if (includePinned || !Prefs.get(appContext).keepClipboardOnClear) {
            writableDatabase.delete(TABLE, null, null)
        } else {
            writableDatabase.delete(TABLE, "$COL_PINNED = 0", null)
        }
    }

    private fun getPinned(id: Long): Boolean? = synchronized(lock) {
        readableDatabase.query(TABLE, arrayOf(COL_PINNED), "$COL_ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }
    }

    private fun trimToLimit(db: SQLiteDatabase) {
        val max = Prefs.get(appContext).clipboardMaxItems
        db.execSQL(
            "DELETE FROM $TABLE WHERE $COL_PINNED = 0 AND $COL_ID NOT IN " +
                "(SELECT $COL_ID FROM $TABLE WHERE $COL_PINNED = 0 ORDER BY $COL_UPDATED DESC LIMIT ?)",
            arrayOf(max)
        )
    }

    private fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    companion object {
        private const val DB_NAME = "clipboard_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "clips"
        private const val COL_ID = "id"
        private const val COL_TEXT = "text"
        private const val COL_CATEGORY = "category"
        private const val COL_PINNED = "pinned"
        private const val COL_CREATED = "created_at"
        private const val COL_UPDATED = "updated_at"
        private const val MAX_CLIP_CHARS = 20_000
        const val FILTER_PINNED = "__pinned__"

        @Volatile private var instance: ClipStore? = null
        fun get(context: Context): ClipStore = instance ?: synchronized(this) {
            instance ?: ClipStore(context).also { instance = it }
        }
    }
}
