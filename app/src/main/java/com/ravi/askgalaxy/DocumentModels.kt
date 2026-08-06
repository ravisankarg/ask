package com.ravi.askgalaxy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

enum class DocumentSource(val wire: String, val displayName: String, val indexFile: String) {
    MESSAGES("messages", "Messages", "documents-messages-768-4bit.tvim"),
    CALENDAR("calendar", "Calendar", "documents-calendar-768-4bit.tvim"),
    FILES("files", "My Files", "documents-files-768-4bit.tvim"),
    CALL_LOGS("call_logs", "Call logs", "documents-call-logs-768-4bit.tvim"),
    CONTACTS("contacts", "Contacts", "documents-contacts-768-4bit.tvim"),
    ;

    companion object {
        fun fromWire(value: String): DocumentSource? = entries.firstOrNull { it.wire == value }
    }
}

data class DocumentChunk(
    val source: DocumentSource,
    val recordKey: String,
    val chunkNumber: Int,
    val title: String,
    val text: String,
    val uri: String? = null,
    val page: Int? = null,
    val timestampMs: Long? = null,
    val metadata: String = "",
) {
    val stableId: Long
        get() = stableLong("${source.wire}|$recordKey|$chunkNumber")
}

data class DocumentMatch(
    val chunk: DocumentChunk,
    val score: Float,
    val rank: Int,
    /** Reciprocal-rank score; raw cosine values are not comparable across sources. */
    val fusionScore: Float = 0f,
)

/** Conservative sentence-aware chunks for the 512-token application contract. */
object DocumentChunker {
    private const val MAX_CHARS = 1_900
    private const val OVERLAP_CHARS = 240

    fun chunk(text: String): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return emptyList()
        val sentences = Regex("(?<=[.!?。！？])\\s+").split(normalized)
        val output = ArrayList<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                output += current.toString().trim()
                current = StringBuilder(current.toString().takeLast(OVERLAP_CHARS))
            }
        }
        sentences.forEach { sentence ->
            if (sentence.length > MAX_CHARS) {
                var start = 0
                while (start < sentence.length) {
                    val end = min(sentence.length, start + MAX_CHARS)
                    if (current.isNotEmpty()) flush()
                    output += sentence.substring(start, end).trim()
                    start = (end - OVERLAP_CHARS).coerceAtLeast(end)
                }
            } else if (current.length + sentence.length + 1 > MAX_CHARS) {
                flush()
                current.append(sentence)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        flush()
        return output.filter { it.isNotBlank() }
    }
}

class DocumentDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "document_context.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chunks(
                stable_id INTEGER PRIMARY KEY,
                source TEXT NOT NULL,
                record_key TEXT NOT NULL,
                chunk_number INTEGER NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                uri TEXT,
                page INTEGER,
                timestamp_ms INTEGER,
                metadata TEXT NOT NULL DEFAULT '',
                content_hash TEXT NOT NULL,
                model_revision TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX chunks_source_record ON chunks(source, record_key)")
        db.execSQL("CREATE INDEX chunks_source_time ON chunks(source, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chunks")
        onCreate(db)
    }

    fun replaceRecord(source: DocumentSource, recordKey: String, chunks: List<DocumentChunk>) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("chunks", "source=? AND record_key=?", arrayOf(source.wire, recordKey))
            chunks.forEach { chunk ->
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO chunks(stable_id,source,record_key,chunk_number,title,text,uri,page,timestamp_ms,metadata,content_hash,model_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        chunk.stableId, chunk.source.wire, chunk.recordKey, chunk.chunkNumber,
                        chunk.title, chunk.text, chunk.uri, chunk.page, chunk.timestampMs,
                        chunk.metadata, sha256(chunk.text), EMBEDDING_MODEL_REVISION,
                    ),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun upsertChunk(chunk: DocumentChunk) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO chunks(stable_id,source,record_key,chunk_number,title,text,uri,page,timestamp_ms,metadata,content_hash,model_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                chunk.stableId, chunk.source.wire, chunk.recordKey, chunk.chunkNumber,
                chunk.title, chunk.text, chunk.uri, chunk.page, chunk.timestampMs,
                chunk.metadata, sha256(chunk.text), EMBEDDING_MODEL_REVISION,
            ),
        )
    }

    fun deleteMissingRecords(source: DocumentSource, activeRecordKeys: Set<String>) {
        if (activeRecordKeys.isEmpty()) {
            writableDatabase.delete("chunks", "source=?", arrayOf(source.wire))
            return
        }
        writableDatabase.query("chunks", arrayOf("record_key"), "source=?", arrayOf(source.wire), null, null, null).use { cursor ->
            val stale = ArrayList<String>()
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                if (key !in activeRecordKeys) stale += key
            }
            stale.forEach { key -> writableDatabase.delete("chunks", "source=? AND record_key=?", arrayOf(source.wire, key)) }
        }
    }

    fun chunks(ids: LongArray): List<DocumentChunk> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val byId = ids.withIndex().associate { it.value to it.index }
        val output = ArrayList<Pair<Int, DocumentChunk>>()
        readableDatabase.query("chunks", null, "stable_id IN ($placeholders)", ids.map(Long::toString).toTypedArray(), null, null, null).use { cursor ->
            val stable = cursor.getColumnIndexOrThrow("stable_id")
            while (cursor.moveToNext()) {
                val source = DocumentSource.fromWire(cursor.getString(cursor.getColumnIndexOrThrow("source"))) ?: continue
                val chunk = DocumentChunk(
                    source, cursor.getString(cursor.getColumnIndexOrThrow("record_key")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("chunk_number")),
                    cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("page")).takeIf { !cursor.isNull(cursor.getColumnIndexOrThrow("page")) },
                    cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")).takeIf { !cursor.isNull(cursor.getColumnIndexOrThrow("timestamp_ms")) },
                    cursor.getString(cursor.getColumnIndexOrThrow("metadata")),
                )
                output += (byId[cursor.getLong(stable)] ?: Int.MAX_VALUE) to chunk
            }
        }
        return output.sortedBy { it.first }.map { it.second }
    }

    companion object {
        const val EMBEDDING_DIMENSION = 768
        const val EMBEDDING_MODEL_REVISION = "embeddinggemma-300M-Q8_0@0f741b5a6585bd53aeb15cd1372c56f2a0f65e12"
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private fun stableLong(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    var result = 0L
    repeat(8) { result = (result shl 8) or (digest[it].toLong() and 0xff) }
    return if (result == 0L) 1L else result
}
