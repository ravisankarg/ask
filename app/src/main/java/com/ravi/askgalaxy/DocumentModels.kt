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
    /** Raw EmbeddingGemma cosine when this record had a semantic hit. */
    val cosineScore: Float? = null,
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

    /** True when this chunk was already embedded with the current model. */
    fun containsCurrentChunk(chunk: DocumentChunk): Boolean {
        readableDatabase.query(
            "chunks",
            arrayOf("stable_id"),
            "stable_id=? AND content_hash=? AND model_revision=?",
            arrayOf(chunk.stableId.toString(), sha256(chunk.text), EMBEDDING_MODEL_REVISION),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return cursor.moveToFirst() }
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

    /** Names already present in a private source, used only for typo-tolerant routing. */
    fun distinctTitles(source: DocumentSource): List<String> {
        val output = LinkedHashSet<String>()
        readableDatabase.query(
            "chunks",
            arrayOf("title"),
            "source=?",
            arrayOf(source.wire),
            null,
            null,
            "title COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)?.let(output::add)
            }
        }
        return output.toList()
    }

    /** Contact phone values allow message records indexed with only an address to resolve by name. */
    fun contactNumbersForName(name: String): List<String> {
        if (name.isBlank()) return emptyList()
        val output = LinkedHashSet<String>()
        readableDatabase.query(
            "chunks",
            arrayOf("record_key", "metadata"),
            "source=? AND lower(title)=lower(?)",
            arrayOf(DocumentSource.CONTACTS.wire, name.trim()),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val recordKey = cursor.getString(0).orEmpty()
                val metadata = cursor.getString(1).orEmpty()
                val phone = Regex("(?:^|\\s)phone=([^\\s]+)", RegexOption.IGNORE_CASE)
                    .find(metadata)?.groupValues?.getOrNull(1)
                    ?: recordKey.substringAfter('|', "")
                phone.takeIf(String::isNotBlank)?.let(output::add)
            }
        }
        return output.toList()
    }

    /** Newest matching records for conversational "last call/message" queries. */
    fun latestChunksMatching(
        source: DocumentSource,
        needles: List<String>,
        limit: Int,
        timeHint: String = "",
        fromDate: String = "",
        toDate: String = "",
        senderOnly: Boolean = false,
    ): List<DocumentChunk> {
        val normalizedNeedles = needles.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct()
        if (normalizedNeedles.isEmpty() || limit <= 0) return emptyList()
        val timeScope = QueryScopeParser.parse(
            timeHint = timeHint,
            locationHint = "",
            fromDate = fromDate,
            toDate = toDate,
        ).time
        val output = ArrayList<DocumentChunk>()
        readableDatabase.query(
            "chunks",
            null,
            "source=?",
            arrayOf(source.wire),
            null,
            null,
            "timestamp_ms DESC",
        ).use { cursor ->
            while (cursor.moveToNext() && output.size < limit) {
                val chunk = chunkFromCursor(cursor)
                if (timeScope != null && chunk.timestampMs != null && !timeScope.matches(chunk.timestampMs)) {
                    continue
                }
                val searchable = if (senderOnly) {
                    "${chunk.title} ${chunk.metadata}".lowercase()
                } else {
                    "${chunk.title} ${chunk.text} ${chunk.metadata}".lowercase()
                }
                if (normalizedNeedles.any { needle -> searchable.contains(needle) }) {
                    output += chunk
                }
            }
        }
        return output
    }

    /**
     * Returns the persisted vector IDs that satisfy hard document scopes.
     * These IDs are passed to native ANN search before ranking, so a MIME or
     * time predicate cannot be applied only after a source's top-K window.
     * Chunks without timestamps retain the existing behaviour and remain
     * eligible for a time-scoped search because their date is unknown.
     */
    fun stableIdsForSearchScope(
        sources: Set<DocumentSource>,
        timeScope: QueryTimeScope? = null,
        mediaType: QueryMediaType? = null,
        senderNeedles: List<String> = emptyList(),
    ): Map<DocumentSource, LongArray> {
        if (sources.isEmpty()) return emptyMap()
        val sourceArgs = sources.map { it.wire }.toTypedArray()
        val placeholders = sourceArgs.joinToString(",") { "?" }
        val ids = sources.associateWith { ArrayList<Long>() }.toMutableMap()
        readableDatabase.query(
            "chunks",
            arrayOf("stable_id", "source", "title", "metadata", "timestamp_ms"),
            "source IN ($placeholders)",
            sourceArgs,
            null,
            null,
            null,
        ).use { cursor ->
            val stableIdColumn = cursor.getColumnIndexOrThrow("stable_id")
            val sourceColumn = cursor.getColumnIndexOrThrow("source")
            val titleColumn = cursor.getColumnIndexOrThrow("title")
            val metadataColumn = cursor.getColumnIndexOrThrow("metadata")
            val timestampColumn = cursor.getColumnIndexOrThrow("timestamp_ms")
            while (cursor.moveToNext()) {
                val source = DocumentSource.fromWire(cursor.getString(sourceColumn)) ?: continue
                val timestamp = cursor.getLong(timestampColumn).takeIf { !cursor.isNull(timestampColumn) }
                if (source == DocumentSource.MESSAGES &&
                    senderNeedles.isNotEmpty() &&
                    senderNeedles.none { needle ->
                        val value = needle.trim().lowercase()
                        value.isNotBlank() && (
                            cursor.getString(titleColumn).orEmpty().lowercase().contains(value) ||
                                cursor.getString(metadataColumn).orEmpty().lowercase().contains(value)
                            )
                    }
                ) continue
                if (timeScope != null && timestamp != null && !timeScope.matches(timestamp)) continue
                if (mediaType != null && !mediaType.matchesDocumentChunk(
                        DocumentChunk(
                            source = source,
                            recordKey = "",
                            chunkNumber = 0,
                            title = cursor.getString(titleColumn),
                            text = "",
                            timestampMs = timestamp,
                        ),
                    )
                ) continue
                ids.getValue(source) += cursor.getLong(stableIdColumn)
            }
        }
        return ids.mapValues { (_, values) -> values.toLongArray() }
    }

    /** Returns private text chunks containing at least one keyword, with coverage. */
    fun searchKeywordChunks(
        keywordGroups: List<List<String>>,
        sources: Set<DocumentSource>,
        limitPerSource: Int,
        requireCompleteGroup: Boolean = false,
        timeScope: QueryTimeScope? = null,
        mediaType: QueryMediaType? = null,
        senderNeedles: List<String> = emptyList(),
    ): List<Pair<DocumentChunk, Float>> {
        val forbiddenKeywords = setOf("number", "numbers", "num")
        val normalizedGroups = keywordGroups.map { group ->
            group.map { it.lowercase() }
                .filter { it.isNotBlank() && it !in forbiddenKeywords }
                .distinct()
        }.filter { it.isNotEmpty() }
        if (normalizedGroups.isEmpty() || sources.isEmpty()) return emptyList()
        val sourceArgs = sources.map { it.wire }.toTypedArray()
        val placeholders = sourceArgs.joinToString(",") { "?" }
        val result = ArrayList<Pair<DocumentChunk, Float>>()
        readableDatabase.query(
            "chunks",
            null,
            "source IN ($placeholders)",
            sourceArgs,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = DocumentSource.fromWire(cursor.getString(cursor.getColumnIndexOrThrow("source")))
                    ?: continue
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val text = cursor.getString(cursor.getColumnIndexOrThrow("text"))
                val metadata = cursor.getString(cursor.getColumnIndexOrThrow("metadata"))
                if (source == DocumentSource.MESSAGES &&
                    senderNeedles.isNotEmpty() &&
                    senderNeedles.none { needle ->
                        val value = needle.trim().lowercase()
                        value.isNotBlank() &&
                            (title.lowercase().contains(value) || metadata.lowercase().contains(value))
                    }
                ) continue
                val timestampColumn = cursor.getColumnIndexOrThrow("timestamp_ms")
                val timestamp = cursor.getLong(timestampColumn).takeIf { !cursor.isNull(timestampColumn) }
                if (timeScope != null && timestamp != null && !timeScope.matches(timestamp)) continue
                if (mediaType != null && !mediaType.matchesDocumentChunk(
                        DocumentChunk(
                            source = source,
                            recordKey = "",
                            chunkNumber = 0,
                            title = title,
                            text = "",
                            timestampMs = timestamp,
                        ),
                    )
                ) continue
                val searchable = "$title $text $metadata".lowercase()
                val coverage = normalizedGroups.maxOfOrNull { group ->
                    group.count { keyword ->
                        searchable.contains(keyword) || when (keyword) {
                            "aadhar", "aadhaar" ->
                                searchable.contains("aadhar") || searchable.contains("aadhaar")
                            else -> false
                        }
                    }.toFloat() / group.size.toFloat()
                } ?: 0f
                if (coverage > 0f && (!requireCompleteGroup || coverage >= 1f)) {
                    result += DocumentChunk(
                        source,
                        cursor.getString(cursor.getColumnIndexOrThrow("record_key")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("chunk_number")),
                        title,
                        text,
                        cursor.getString(cursor.getColumnIndexOrThrow("uri")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("page")).takeIf {
                            !cursor.isNull(cursor.getColumnIndexOrThrow("page"))
                        },
                        timestamp,
                        metadata,
                    ) to coverage
                }
            }
        }
        return result
            .groupBy { it.first.source }
            .values
            .flatMap { it.sortedByDescending { pair -> pair.second }.take(limitPerSource) }
    }

    private fun chunkFromCursor(cursor: android.database.Cursor): DocumentChunk {
        val source = DocumentSource.fromWire(cursor.getString(cursor.getColumnIndexOrThrow("source")))
            ?: error("Unknown document source")
        val timestampColumn = cursor.getColumnIndexOrThrow("timestamp_ms")
        val pageColumn = cursor.getColumnIndexOrThrow("page")
        return DocumentChunk(
            source = source,
            recordKey = cursor.getString(cursor.getColumnIndexOrThrow("record_key")),
            chunkNumber = cursor.getInt(cursor.getColumnIndexOrThrow("chunk_number")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
            uri = cursor.getString(cursor.getColumnIndexOrThrow("uri")),
            page = cursor.getInt(pageColumn).takeIf { !cursor.isNull(pageColumn) },
            timestampMs = cursor.getLong(timestampColumn).takeIf { !cursor.isNull(timestampColumn) },
            metadata = cursor.getString(cursor.getColumnIndexOrThrow("metadata")),
        )
    }

    companion object {
        const val EMBEDDING_DIMENSION = 768
        const val EMBEDDING_MODEL_REVISION = "litert-community/embeddinggemma-300m@main-sm8750-seq512"
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private fun stableLong(value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    var result = 0L
    repeat(8) { result = (result shl 8) or (digest[it].toLong() and 0xff) }
    // TurboQuant uses a signed non-negative ID space.
    val positive = result and Long.MAX_VALUE
    return if (positive == 0L) 1L else positive
}
