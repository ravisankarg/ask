package com.ravi.askgalaxy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Small, separate store for opt-in notification facts. The payload is encrypted
 * with an Android Keystore key; only the category, timestamps, and a dedupe hash
 * remain queryable by SQLite.
 */
class PersonalContextDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val crypto = PersonalContextCrypto()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE context_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_package TEXT NOT NULL,
                kind TEXT NOT NULL,
                payload_blob BLOB NOT NULL,
                event_time_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                dedupe_key TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX context_items_time ON context_items(received_at_ms DESC)")
        db.execSQL("CREATE INDEX context_items_kind ON context_items(kind)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first release of this separate store. Keep the hook
        // explicit so later schema changes do not touch the gallery database.
    }

    @Synchronized
    fun insert(draft: PersonalContextDraft): Boolean {
        val receivedAt = System.currentTimeMillis()
        val payload = JSONObject()
            .put("sourceLabel", draft.sourceLabel)
            .put("title", draft.title)
            .put("body", draft.body)
            .putNullable("merchant", draft.merchant)
            .putNullable("amount", draft.amount)
            .putNullable("reference", draft.reference)
            .putNullable("route", draft.route)
            .putNullable("dateHint", draft.dateHint)
            .toString()
        val values = ContentValues().apply {
            put("source_package", draft.sourcePackage)
            put("kind", draft.kind.wire)
            put("payload_blob", crypto.encrypt(payload))
            put("event_time_ms", draft.eventTimeMs)
            put("received_at_ms", receivedAt)
            put("dedupe_key", dedupeKey(draft))
        }
        val inserted = writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        prune(receivedAt)
        return inserted
    }

    @Synchronized
    fun count(): Int = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE",
    ).simpleQueryForLong().toInt()

    /** Scores decrypted facts in memory so notification text is never SQL-searchable. */
    @Synchronized
    fun search(query: String, limit: Int = 6): List<PersonalContextMatch> {
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        return readItems().mapNotNull { item ->
            val title = item.title.lowercase(Locale.ROOT)
            val body = item.body.lowercase(Locale.ROOT)
            val merchant = item.merchant.orEmpty().lowercase(Locale.ROOT)
            val route = item.route.orEmpty().lowercase(Locale.ROOT)
            val structured = listOfNotNull(
                item.amount,
                item.reference,
                item.dateHint,
            ).joinToString(" ").lowercase(Locale.ROOT)
            val kindWords = when (item.kind) {
                PersonalContextKind.TRAVEL -> "travel trip flight hotel booking reservation itinerary"
                PersonalContextKind.RECEIPT -> "receipt bill payment paid purchase invoice"
                PersonalContextKind.DELIVERY -> "delivery shipped package order"
                PersonalContextKind.APPOINTMENT -> "appointment meeting schedule event"
            }
            var score = 0f
            terms.forEach { term ->
                if (title.contains(term)) score += 3f
                if (merchant.contains(term)) score += 2.5f
                if (route.contains(term) || structured.contains(term)) score += 2f
                if (body.contains(term)) score += 1f
                if (kindWords.split(' ').contains(term)) score += 1.5f
            }
            if (score <= 0f) null else PersonalContextMatch(item, score)
        }.sortedWith(
            compareByDescending<PersonalContextMatch> { it.score }
                .thenByDescending { it.item.eventTimeMs },
        ).take(limit.coerceIn(1, MAX_RESULTS))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun readItems(): List<PersonalContextItem> {
        val result = ArrayList<PersonalContextItem>()
        readableDatabase.query(
            TABLE,
            arrayOf("id", "source_package", "kind", "payload_blob", "event_time_ms", "received_at_ms"),
            null,
            null,
            null,
            null,
            "received_at_ms DESC",
            MAX_ROWS.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val payload = runCatching {
                    JSONObject(crypto.decrypt(cursor.getBlob(3)))
                }.getOrNull() ?: continue
                result += PersonalContextItem(
                    id = cursor.getLong(0),
                    sourcePackage = cursor.getString(1),
                    sourceLabel = payload.optString("sourceLabel").ifBlank { cursor.getString(1) },
                    title = payload.optString("title"),
                    body = payload.optString("body"),
                    kind = PersonalContextKind.fromWire(cursor.getString(2)),
                    eventTimeMs = cursor.getLong(4),
                    receivedAtMs = cursor.getLong(5),
                    merchant = payload.optNullableString("merchant"),
                    amount = payload.optNullableString("amount"),
                    reference = payload.optNullableString("reference"),
                    route = payload.optNullableString("route"),
                    dateHint = payload.optNullableString("dateHint"),
                )
            }
        }
        return result
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - RETENTION_DAYS * DAY_MS
        writableDatabase.delete(TABLE, "received_at_ms < ?", arrayOf(cutoff.toString()))
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY received_at_ms DESC LIMIT $MAX_ROWS)",
        )
    }

    private fun dedupeKey(draft: PersonalContextDraft): String {
        val raw = listOf(draft.sourcePackage, draft.kind.wire, draft.title, draft.body)
            .joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun queryTerms(query: String): List<String> {
        val stopWords = setOf(
            "a", "an", "and", "are", "did", "do", "for", "from", "i", "in", "is",
            "me", "my", "of", "on", "the", "to", "was", "what", "when", "where",
            "which", "who", "with", "you", "your",
        )
        return query.lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length > 1 && it !in stopWords }
            .distinct()
            .take(8)
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject =
        if (value.isNullOrBlank()) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.optNullableString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() }

    companion object {
        private const val DATABASE_NAME = "personal_context.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "context_items"
        private const val MAX_ROWS = 500
        private const val MAX_RESULTS = 8
        private const val RETENTION_DAYS = 180L
        private const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
