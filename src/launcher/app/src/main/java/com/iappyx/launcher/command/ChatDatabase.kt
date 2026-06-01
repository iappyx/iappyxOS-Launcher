/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.command

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite-backed persistence for the AI Command Bar's visible chat history.
 *
 * Why SQLite over JSON-on-disk:
 *  - Atomic per-message append (no full-file rewrite per send).
 *  - Easy "load last N" via ORDER BY id DESC LIMIT.
 *  - Single DELETE FROM for the Settings "Clear chat history" button.
 *  - Image bytes inline as base64 TEXT — for the cap of 200 messages each
 *    ≤300 KB, total DB stays well under 60 MB. Filesystem-side image storage
 *    would save ~25% on disk but adds an orphan-cleanup pass; not worth it.
 *
 * What we persist (visible chat only):
 *  - `user`        — typed text + optional attached image
 *  - `assistant`   — text replies the AI emitted as content blocks
 *  - `tool`        — tool-call summaries (one chat row per fired tool)
 *  - `error`       — error rows shown to the user
 *
 * What we do NOT persist:
 *  - Anthropic tool_use / tool_result content blocks (rebuilt from scratch
 *    on next turn — the user-visible assistant summary usually carries enough
 *    context for the AI to follow up).
 *  - The `Working` sentinel line — it's a transient pill, not history.
 *
 * Capped at [MAX_ROWS] entries; oldest rows fall off via a trim after each
 * append. The cap matches the 200-message ask in the spec.
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DB_NAME, null, DB_VERSION,
) {
    /**
     * One persisted chat row. Maps cleanly onto [CommandSession.Line] but
     * carries enough metadata (role, image fields) to round-trip through the
     * DB without losing fidelity. The [id] is monotonic — used only for
     * ordering.
     */
    data class Record(
        val id: Long,
        val role: String,
        val text: String,
        val toolName: String?,
        val imageBase64: String?,
        val imageMime: String?,
        val timestamp: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
              $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
              $COL_ROLE TEXT NOT NULL,
              $COL_TEXT TEXT NOT NULL,
              $COL_TOOL_NAME TEXT,
              $COL_IMAGE_B64 TEXT,
              $COL_IMAGE_MIME TEXT,
              $COL_TS INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_ts ON $TABLE($COL_TS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No old versions in the field yet — drop and rebuild on any schema
        // bump. Chat history is a soft-loss artefact: the user can recreate
        // it by chatting again. If the schema ever gains anything precious
        // we'll add ALTER paths.
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Append a single chat row. Trims to [MAX_ROWS] in the same transaction
     *  so the row count never grows unbounded. Returns the new row id, or
     *  -1 on error. */
    fun append(
        role: String,
        text: String,
        toolName: String? = null,
        imageBase64: String? = null,
        imageMime: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put(COL_ROLE, role)
                    put(COL_TEXT, text)
                    put(COL_TOOL_NAME, toolName)
                    put(COL_IMAGE_B64, imageBase64)
                    put(COL_IMAGE_MIME, imageMime)
                    put(COL_TS, timestamp)
                }
                val rowId = db.insert(TABLE, null, cv)
                trimToCap(db)
                db.setTransactionSuccessful()
                rowId
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "append failed: ${t.message}")
            -1L
        }
    }

    /** Load up to [limit] most-recent rows in chronological order (oldest
     *  first). Caller iterates in order to replay the conversation into the
     *  UI list and the API messages array. */
    fun loadRecent(limit: Int = MAX_ROWS): List<Record> {
        val out = mutableListOf<Record>()
        try {
            val db = readableDatabase
            // Two-step: pick the IDs of the newest [limit] rows, then return
            // those rows in ASC order. Cleaner than DESC + reverse() in caller.
            db.rawQuery(
                """
                SELECT $COL_ID, $COL_ROLE, $COL_TEXT, $COL_TOOL_NAME,
                       $COL_IMAGE_B64, $COL_IMAGE_MIME, $COL_TS
                FROM $TABLE
                WHERE $COL_ID IN (
                  SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT ?
                )
                ORDER BY $COL_ID ASC
                """.trimIndent(),
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        Record(
                            id = c.getLong(0),
                            role = c.getString(1),
                            text = c.getString(2) ?: "",
                            toolName = if (c.isNull(3)) null else c.getString(3),
                            imageBase64 = if (c.isNull(4)) null else c.getString(4),
                            imageMime = if (c.isNull(5)) null else c.getString(5),
                            timestamp = c.getLong(6),
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadRecent failed: ${t.message}")
        }
        return out
    }

    /** Wipe every row. Used by the Settings "Clear chat history" button.
     *  Returns the number of rows deleted. */
    fun clearAll(): Int {
        return try {
            writableDatabase.delete(TABLE, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "clearAll failed: ${t.message}")
            0
        }
    }

    /** Current row count. For Settings subtitle ("142 messages • 7.3 MB"). */
    fun count(): Int {
        return try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (t: Throwable) {
            Log.w(TAG, "count failed: ${t.message}")
            0
        }
    }

    /** Approximate on-disk size in bytes. Reads the DB file's length — close
     *  enough for a Settings subtitle; SQLite page-aligns so the actual
     *  storage may be slightly higher than the sum of row payloads. */
    fun sizeBytes(): Long {
        return try {
            val path = readableDatabase.path ?: return 0L
            java.io.File(path).length()
        } catch (t: Throwable) {
            0L
        }
    }

    /** Drop oldest rows to bring count to [MAX_ROWS]. Called from [append]
     *  inside the same transaction so the cap is enforced atomically. */
    private fun trimToCap(db: SQLiteDatabase) {
        // SQLite supports `DELETE … ORDER BY … LIMIT` only when built with
        // SQLITE_ENABLE_UPDATE_DELETE_LIMIT; Android does NOT enable that
        // flag. So we use a subquery to identify rows to keep, and delete
        // the complement.
        db.execSQL(
            """
            DELETE FROM $TABLE
            WHERE $COL_ID NOT IN (
              SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(MAX_ROWS),
        )
    }

    companion object {
        private const val DB_NAME = "iappyx_chat.db"
        private const val DB_VERSION = 1
        private const val TABLE = "messages"
        private const val COL_ID = "id"
        private const val COL_ROLE = "role"
        private const val COL_TEXT = "text"
        private const val COL_TOOL_NAME = "tool_name"
        private const val COL_IMAGE_B64 = "image_b64"
        private const val COL_IMAGE_MIME = "image_mime"
        private const val COL_TS = "timestamp"
        /** Hard cap on rows. Chosen to match the spec ("hard-cap at 200,
         *  oldest auto-trimmed"). Each row is at most ~250 KB (a base64-
         *  encoded ~1568px JPEG), so worst-case DB size is ~50 MB; typical
         *  is far less because most rows are pure text. */
        const val MAX_ROWS = 200
        private const val TAG = "iappyxChatDb"
    }
}
