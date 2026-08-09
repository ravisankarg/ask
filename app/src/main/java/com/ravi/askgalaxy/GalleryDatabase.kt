package com.ravi.askgalaxy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.sqrt

enum class MediaContentClass(val wireName: String) {
    DOC("doc"),
    SCENARY("scenary"),
    ;

    companion object {
        fun fromWireName(value: String?): MediaContentClass =
            entries.firstOrNull { it.wireName == value } ?: SCENARY

        fun fromIndexedOcr(mimeType: String, ocrText: String): MediaContentClass =
            if (
                mimeType.startsWith("image/", ignoreCase = true) &&
                ocrText.isNotBlank()
            ) {
                DOC
            } else {
                SCENARY
            }
    }
}

data class GalleryMedia(
    val mediaStoreId: Long,
    val contentUri: String,
    val mimeType: String,
    val displayName: String,
    val dateModifiedSeconds: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val personClusterId: String? = null,
    val personLabel: String? = null,
    val ocrText: String = "",
    val contentClass: MediaContentClass = MediaContentClass.SCENARY,
    // Capture and location metadata are persisted during preprocessing. Lazy
    // reads remain as a compatibility fallback for rows indexed before v8.
    val dateTakenMs: Long? = null,
    val location: String? = null,
    val locationName: String? = null,
    val locationEnrichmentState: Int = GalleryDatabase.LOCATION_PENDING,
)

data class MetadataMatch(
    val media: GalleryMedia,
    val score: Float,
)

class GalleryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val faceEmbeddingCrypto = FaceEmbeddingCrypto()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE media_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_store_id INTEGER NOT NULL,
                content_uri TEXT NOT NULL UNIQUE,
                mime_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                date_modified_seconds INTEGER NOT NULL,
                size_bytes INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                ocr_text TEXT NOT NULL DEFAULT '',
                content_class TEXT NOT NULL DEFAULT 'scenary',
                ocr_indexed INTEGER NOT NULL DEFAULT 0,
                ocr_signature TEXT NOT NULL DEFAULT '',
                person_cluster_id TEXT,
                image_embedding_indexed INTEGER NOT NULL DEFAULT 0,
                face_embedding_indexed INTEGER NOT NULL DEFAULT 0,
                embedding_signature TEXT NOT NULL DEFAULT '',
                date_taken_ms INTEGER,
                location_raw TEXT,
                location_name TEXT,
                location_enrichment_state INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX media_items_modified ON media_items(date_modified_seconds)")
        db.execSQL("CREATE INDEX media_items_mime ON media_items(mime_type)")
        db.execSQL("CREATE INDEX media_items_cluster ON media_items(person_cluster_id)")
        db.execSQL("CREATE INDEX media_items_date_taken ON media_items(date_taken_ms)")
        db.execSQL("CREATE INDEX media_items_location_name ON media_items(location_name)")
        db.execSQL("CREATE INDEX media_items_content_class ON media_items(content_class)")
        createAnswerabilityFactsTable(db)
        createFaceTables(db)
        createLocationCache(db)
        createEpisodeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN embedding_signature TEXT NOT NULL DEFAULT ''",
            )
        }
        if (oldVersion < 3) {
            // The embedding space changed from the old 1152-D SO400M export
            // to the direct 768-D SigLIP2 artifact. Force a full rebuild.
            db.execSQL("UPDATE media_items SET image_embedding_indexed = 0")
        }
        if (oldVersion < 4) {
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN face_embedding_indexed INTEGER NOT NULL DEFAULT 0",
            )
            createFaceTables(db)
        }
        if (oldVersion >= 4 && oldVersion < 5) {
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN face_left REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN face_top REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN face_right REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE face_embeddings ADD COLUMN face_bottom REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE face_clusters ADD COLUMN representative_face_id INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN ocr_indexed INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("CREATE INDEX IF NOT EXISTS media_items_mime ON media_items(mime_type)")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN date_taken_ms INTEGER")
            db.execSQL("ALTER TABLE media_items ADD COLUMN location_raw TEXT")
            db.execSQL("ALTER TABLE media_items ADD COLUMN location_name TEXT")
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN location_enrichment_state INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS media_items_date_taken ON media_items(date_taken_ms)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_items_location_name ON media_items(location_name)")
            createLocationCache(db)
        }
        if (oldVersion < 9) {
            createEpisodeTables(db)
        }
        if (oldVersion < 10) {
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN content_class TEXT NOT NULL DEFAULT 'scenary'",
            )
            db.execSQL(
                """
                UPDATE media_items
                SET content_class = CASE
                    WHEN mime_type LIKE 'image/%' AND TRIM(ocr_text) <> '' THEN 'doc'
                    ELSE 'scenary'
                END
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS media_items_content_class ON media_items(content_class)",
            )
        }
        if (oldVersion < 11) {
            db.execSQL(
                "ALTER TABLE media_items ADD COLUMN ocr_signature TEXT NOT NULL DEFAULT ''",
            )
            // OCR is an image classification contract. Discard stale frame
            // text from the retired PP-OCR pass without touching any other
            // index state.
            val values = ContentValues().apply {
                put("ocr_text", "")
                put("content_class", MediaContentClass.SCENARY.wireName)
                put("ocr_indexed", 1)
                put("ocr_signature", OcrIndexContract.SIGNATURE)
            }
            db.update(
                TABLE_MEDIA,
                values,
                "mime_type NOT LIKE 'image/%'",
                null,
            )
        }
        if (oldVersion < 12) {
            // Earlier builds opened ordinary MediaStore photo URIs, whose EXIF
            // GPS is redacted on Android 10+. Revisit only image location
            // metadata after explicit ACCESS_MEDIA_LOCATION consent; retain
            // capture dates, OCR, vectors, faces, and every other index.
            val values = ContentValues().apply {
                putNull("location_raw")
                putNull("location_name")
                put("location_enrichment_state", LOCATION_PENDING)
            }
            db.update(
                TABLE_MEDIA,
                values,
                "mime_type LIKE 'image/%'",
                null,
            )
        }
        if (oldVersion >= 4 && oldVersion < 13) {
            // Add one exclusive local self-identity marker without changing
            // any face vectors, cluster IDs, labels, or gallery index state.
            db.execSQL(
                "ALTER TABLE face_clusters ADD COLUMN is_self INTEGER NOT NULL DEFAULT 0",
            )
        }
        if (oldVersion < 14) {
            // Older schemas may not have had the retired columns yet.
            db.execSQL("ALTER TABLE media_items ADD COLUMN kv_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE media_items ADD COLUMN kv_indexed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE media_items ADD COLUMN kv_signature TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 15) {
            // The retired KV document index is intentionally discarded on upgrade.
            runCatching { db.execSQL("UPDATE media_items SET kv_text = '', kv_indexed = 0, kv_signature = ''") }
        }
        if (oldVersion < 16) {
            // Candidate answer facts are derived data. Creating this table and
            // lazily filling it must never rewrite OCR, vectors, faces, or
            // metadata rows.
            createAnswerabilityFactsTable(db)
        }
    }

    fun upsert(media: GalleryMedia) {
        val signature = "${media.dateModifiedSeconds}:${media.sizeBytes}:${media.width}:${media.height}"
        val previousSignature = writableDatabase.query(
            TABLE_MEDIA,
            arrayOf("embedding_signature"),
            "content_uri = ?",
            arrayOf(media.contentUri),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else null
        }
        val values = ContentValues().apply {
            put("media_store_id", media.mediaStoreId)
            put("content_uri", media.contentUri)
            put("mime_type", media.mimeType)
            put("display_name", media.displayName)
            put("date_modified_seconds", media.dateModifiedSeconds)
            put("size_bytes", media.sizeBytes)
            put("width", media.width)
            put("height", media.height)
            put("duration_ms", media.durationMs)
            put("embedding_signature", signature)
            put("updated_at_ms", System.currentTimeMillis())
            if (!media.mimeType.startsWith("image/")) {
                put("ocr_text", "")
                put("content_class", MediaContentClass.SCENARY.wireName)
                put("ocr_indexed", 1)
                put("ocr_signature", OcrIndexContract.SIGNATURE)
            }
            if (previousSignature != null && previousSignature != signature) {
                put("image_embedding_indexed", 0)
                if (media.mimeType.startsWith("image/")) {
                    put("ocr_indexed", 0)
                    put("ocr_signature", "")
                    put("ocr_text", "")
                    put("content_class", MediaContentClass.SCENARY.wireName)
                }
                put("face_embedding_indexed", 0)
                putNull("date_taken_ms")
                putNull("location_raw")
                putNull("location_name")
                put("location_enrichment_state", LOCATION_PENDING)
            }
            media.dateTakenMs?.let { put("date_taken_ms", it) }
            media.location?.let { put("location_raw", it) }
            media.locationName?.let { put("location_name", it) }
            if (media.locationEnrichmentState != LOCATION_PENDING) {
                put("location_enrichment_state", media.locationEnrichmentState)
            }
        }
        val changed = writableDatabase.update(
            TABLE_MEDIA,
            values,
            "content_uri = ?",
            arrayOf(media.contentUri),
        )
        if (changed == 0) {
            writableDatabase.insertOrThrow(TABLE_MEDIA, null, values)
        } else if (previousSignature != null && previousSignature != signature) {
            writableDatabase.delete(
                TABLE_FACE_EMBEDDINGS,
                "media_store_id = ?",
                arrayOf(media.mediaStoreId.toString()),
            )
        }
    }

    fun count(): Long = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE_MEDIA",
    ).simpleQueryForLong()

    /** Removes MediaStore rows that disappeared since the completed gallery scan. */
    fun removeMissingMediaStoreIds(activeMediaStoreIds: Set<Long>): LongArray {
        val staleIds = allMediaStoreIds()
            .filterNot(activeMediaStoreIds::contains)
            .distinct()
        if (staleIds.isEmpty()) return LongArray(0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            staleIds.chunked(SQLITE_ID_CHUNK).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map(Long::toString).toTypedArray()
                db.delete(TABLE_FACE_EMBEDDINGS, "media_store_id IN ($placeholders)", args)
                db.delete(TABLE_EPISODE_MEMBERS, "media_store_id IN ($placeholders)", args)
                db.delete(TABLE_ANSWERABILITY_FACTS, "media_store_id IN ($placeholders)", args)
                db.delete(TABLE_MEDIA, "media_store_id IN ($placeholders)", args)
            }
            db.delete(
                TABLE_EPISODES,
                "NOT EXISTS (SELECT 1 FROM $TABLE_EPISODE_MEMBERS m WHERE m.episode_id = $TABLE_EPISODES.episode_id)",
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return staleIds.toLongArray()
    }

    /** All gallery rows are eligible until the QP contributes an explicit filter. */
    fun allMediaStoreIds(): Set<Long> {
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            TABLE_MEDIA,
            arrayOf("media_store_id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    fun pendingEmbeddings(): List<GalleryMedia> = queryMedia(
        selection = "image_embedding_indexed = 0",
        orderBy = "date_modified_seconds DESC",
        projection = INDEXING_PROJECTION,
    )

    /** Makes every gallery row eligible for a clean visual-index rebuild. */
    fun invalidateImageEmbeddingIndex(): Int {
        val values = ContentValues().apply { put("image_embedding_indexed", 0) }
        return writableDatabase.update(TABLE_MEDIA, values, null, null)
    }

    /** Rows needing capture-time/GPS extraction or an online reverse-geocode retry. */
    fun pendingLocationMetadata(): List<GalleryMedia> = queryMedia(
        selection = "location_enrichment_state = ?",
        selectionArgs = arrayOf(LOCATION_PENDING.toString()),
        orderBy = "date_modified_seconds DESC",
        projection = INDEXING_PROJECTION,
    )

    fun pendingLocationCount(): Int = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE_MEDIA WHERE location_enrichment_state = $LOCATION_PENDING",
    ).simpleQueryForLong().toInt()

    fun resolvedLocationCount(): Int = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE_MEDIA WHERE location_enrichment_state = $LOCATION_RESOLVED",
    ).simpleQueryForLong().toInt()

    /** Invalidates only persisted photo GPS/place fields. */
    fun invalidatePhotoLocationIndex(): Int {
        val values = ContentValues().apply {
            putNull("location_raw")
            putNull("location_name")
            put("location_enrichment_state", LOCATION_PENDING)
            put("updated_at_ms", System.currentTimeMillis())
        }
        return writableDatabase.update(
            TABLE_MEDIA,
            values,
            "mime_type LIKE 'image/%'",
            null,
        )
    }

    fun saveEnrichedMetadata(
        mediaStoreId: Long,
        dateTakenMs: Long?,
        rawLocation: String?,
        locationName: String?,
    ) {
        val raw = rawLocation?.trim()?.takeIf(String::isNotBlank)
        val readable = locationName?.trim()?.takeIf(String::isNotBlank)
        val state = when {
            raw == null -> LOCATION_COMPLETE_NO_GPS
            readable != null -> LOCATION_RESOLVED
            else -> LOCATION_PENDING
        }
        val values = ContentValues().apply {
            if (dateTakenMs == null) putNull("date_taken_ms") else put("date_taken_ms", dateTakenMs)
            if (raw == null) putNull("location_raw") else put("location_raw", raw)
            if (readable == null) putNull("location_name") else put("location_name", readable)
            put("location_enrichment_state", state)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE_MEDIA,
            values,
            "media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
        )
    }

    /** Returns persisted candidate label/value cards for the requested gallery rows. */
    fun answerabilityFacts(mediaStoreIds: LongArray): Map<Long, List<AnswerFactGrounding.IndexedFact>> {
        if (mediaStoreIds.isEmpty()) return emptyMap()
        val placeholders = mediaStoreIds.joinToString(",") { "?" }
        val result = LinkedHashMap<Long, MutableList<AnswerFactGrounding.IndexedFact>>()
        readableDatabase.query(
            TABLE_ANSWERABILITY_FACTS,
            arrayOf("media_store_id", "label", "value"),
            "media_store_id IN ($placeholders)",
            mediaStoreIds.map(Long::toString).toTypedArray(),
            null,
            null,
            "media_store_id ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.getOrPut(cursor.getLong(0)) { ArrayList() } +=
                    AnswerFactGrounding.IndexedFact(cursor.getString(1), cursor.getString(2))
            }
        }
        return result
    }

    /** Backfills only missing derived cards for selected evidence rows. */
    fun ensureAnswerabilityFacts(mediaStoreIds: LongArray): Map<Long, List<AnswerFactGrounding.IndexedFact>> {
        val existing = answerabilityFacts(mediaStoreIds)
        mediaStoreIds.filterNot(existing::containsKey).forEach { mediaStoreId ->
            val sourceText = readableDatabase.query(
                TABLE_MEDIA,
                arrayOf("display_name", "ocr_text"),
                "media_store_id = ?",
                arrayOf(mediaStoreId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else listOf(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            }
            sourceText?.let { replaceAnswerabilityFacts(mediaStoreId, it) }
        }
        return answerabilityFacts(mediaStoreIds)
    }

    /** Fills one derived row set after OCR completes; source data is unchanged. */
    private fun replaceAnswerabilityFacts(mediaStoreId: Long, ocrText: String) {
        val db = writableDatabase
        val title = db.query(
            TABLE_MEDIA,
            arrayOf("display_name"),
            "media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
        val facts = AnswerFactGrounding.extractFactsForIndex(
            listOf(title, ocrText).filter(String::isNotBlank).joinToString("\n"),
        )
        db.beginTransaction()
        try {
            db.delete(TABLE_ANSWERABILITY_FACTS, "media_store_id = ?", arrayOf(mediaStoreId.toString()))
            facts.forEach { fact ->
                db.execSQL(
                    "INSERT OR IGNORE INTO $TABLE_ANSWERABILITY_FACTS(media_store_id,label,value) VALUES(?,?,?)",
                    arrayOf(mediaStoreId, fact.label, fact.value),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cachedLocationName(coordinateKey: String): String? =
        readableDatabase.query(
            TABLE_LOCATION_CACHE,
            arrayOf("location_name"),
            "coordinate_key = ?",
            arrayOf(coordinateKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
        }

    fun saveLocationName(coordinateKey: String, locationName: String) {
        val cleanName = locationName.trim()
        if (coordinateKey.isBlank() || cleanName.isBlank()) return
        val values = ContentValues().apply {
            put("coordinate_key", coordinateKey)
            put("location_name", cleanName)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_LOCATION_CACHE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Snapshot used by the final preprocessing stage to rebuild episodes. */
    fun episodeMediaSeeds(): List<EpisodeMediaSeed> {
        val media = queryMedia(
            orderBy = "COALESCE(date_taken_ms, date_modified_seconds * 1000) ASC, media_store_id ASC",
            projection = INDEXING_PROJECTION,
        )
        if (media.isEmpty()) return emptyList()
        val peopleByMedia = HashMap<Long, MutableSet<String>>()
        readableDatabase.query(
            TABLE_FACE_EMBEDDINGS,
            arrayOf("media_store_id", "cluster_id"),
            "TRIM(cluster_id) <> ''",
            null,
            null,
            null,
            "media_store_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                peopleByMedia.getOrPut(cursor.getLong(0), ::linkedSetOf).add(cursor.getString(1))
            }
        }
        return media.map { item ->
            EpisodeMediaSeed(item, peopleByMedia[item.mediaStoreId].orEmpty())
        }
    }

    /** Atomically replaces the derived episode headers and membership index. */
    fun replacePhotoEpisodes(episodes: List<IndexedPhotoEpisode>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_EPISODE_MEMBERS, null, null)
            db.delete(TABLE_EPISODES, null, null)
            episodes.forEach { episode ->
                db.insertOrThrow(
                    TABLE_EPISODES,
                    null,
                    ContentValues().apply {
                        put("episode_id", episode.episodeId)
                        put("start_time_ms", episode.startTimeMs)
                        put("end_time_ms", episode.endTimeMs)
                        if (episode.location == null) putNull("location_name")
                        else put("location_name", episode.location)
                        put("representative_media_store_id", episode.representativeMediaStoreId)
                        put("member_count", episode.memberMediaStoreIds.size)
                        put("person_cluster_ids", episode.personClusterIds.sorted().joinToString(","))
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                )
                episode.memberMediaStoreIds.forEachIndexed { ordinal, mediaStoreId ->
                    db.insertOrThrow(
                        TABLE_EPISODE_MEMBERS,
                        null,
                        ContentValues().apply {
                            put("episode_id", episode.episodeId)
                            put("media_store_id", mediaStoreId)
                            put("member_ordinal", ordinal)
                        },
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Resolves persisted episode membership for a bounded ranked result set.
     * Chunks stay below SQLite's bind limit and the returned map is keyed by
     * media ID for O(1) Context Picker grouping.
     */
    fun episodeMemberships(mediaStoreIds: LongArray): Map<Long, EpisodeMembership> {
        if (mediaStoreIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, EpisodeMembership>()
        mediaStoreIds.asList().chunked(SQLITE_ID_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.query(
                "$TABLE_EPISODE_MEMBERS m JOIN $TABLE_EPISODES e ON e.episode_id = m.episode_id",
                arrayOf(
                    "m.media_store_id",
                    "e.episode_id",
                    "e.start_time_ms",
                    "e.end_time_ms",
                    "e.location_name",
                    "e.representative_media_store_id",
                    "e.member_count",
                ),
                "m.media_store_id IN ($placeholders)",
                chunk.map(Long::toString).toTypedArray(),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getLong(0)] = EpisodeMembership(
                        episodeId = cursor.getString(1),
                        startTimeMs = cursor.getLong(2),
                        endTimeMs = cursor.getLong(3),
                        location = cursor.getString(4)?.takeIf(String::isNotBlank),
                        representativeMediaStoreId = cursor.getLong(5),
                        memberCount = cursor.getInt(6),
                    )
                }
            }
        }
        return result
    }

    fun markEmbeddingIndexed(mediaStoreId: Long) {
        val values = ContentValues().apply { put("image_embedding_indexed", 1) }
        writableDatabase.update(
            TABLE_MEDIA,
            values,
            "media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
        )
    }

    fun pendingFaceEmbeddings(): List<GalleryMedia> = queryMedia(
        selection = "face_embedding_indexed = 0",
        orderBy = "date_modified_seconds DESC",
        projection = INDEXING_PROJECTION,
    )

    /** Makes every gallery row eligible for face detection and embedding again. */
    fun invalidateFaceEmbeddingIndex(): Int {
        val values = ContentValues().apply { put("face_embedding_indexed", 0) }
        return writableDatabase.update(TABLE_MEDIA, values, null, null)
    }

    fun pendingOcr(
        signature: String = OcrIndexContract.SIGNATURE,
    ): List<GalleryMedia> = queryMedia(
        selection = "mime_type LIKE 'image/%' AND (ocr_indexed = 0 OR ocr_signature <> ?)",
        selectionArgs = arrayOf(signature),
        orderBy = "date_modified_seconds DESC",
        projection = INDEXING_PROJECTION,
    )

    fun pendingOcrCount(
        signature: String = OcrIndexContract.SIGNATURE,
    ): Int = readableDatabase.rawQuery(
        """
        SELECT COUNT(*)
        FROM $TABLE_MEDIA
        WHERE mime_type LIKE 'image/%'
          AND (ocr_indexed = 0 OR ocr_signature <> ?)
        """.trimIndent(),
        arrayOf(signature),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun replaceOcrResult(
        mediaStoreId: Long,
        mimeType: String,
        text: String,
        signature: String = OcrIndexContract.SIGNATURE,
    ) {
        val cleanText = text.trim()
        val values = ContentValues().apply {
            put("ocr_text", cleanText)
            put(
                "content_class",
                MediaContentClass.fromIndexedOcr(mimeType, cleanText).wireName,
            )
            put("ocr_indexed", 1)
            put("ocr_signature", signature)
        }
        writableDatabase.update(
            TABLE_MEDIA,
            values,
            "media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
        )
        replaceAnswerabilityFacts(mediaStoreId, cleanText)
    }

    /**
     * Makes only image OCR rows pending. Existing text remains searchable
     * until each replacement is committed atomically by the OCR-only worker.
     */
    fun invalidateOcrIndex(): Int {
        val values = ContentValues().apply {
            put("ocr_indexed", 0)
            put("ocr_signature", "")
        }
        return writableDatabase.update(
            TABLE_MEDIA,
            values,
            "mime_type LIKE 'image/%'",
            null,
        )
    }

    fun markFaceEmbeddingIndexed(mediaStoreId: Long) {
        val values = ContentValues().apply { put("face_embedding_indexed", 1) }
        writableDatabase.update(
            TABLE_MEDIA,
            values,
            "media_store_id = ?",
            arrayOf(mediaStoreId.toString()),
        )
    }

    fun replaceFaceEmbeddings(mediaStoreId: Long, embeddings: List<FaceEmbeddingInput>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                TABLE_FACE_EMBEDDINGS,
                "media_store_id = ?",
                arrayOf(mediaStoreId.toString()),
            )
            embeddings.forEach { face ->
                val values = ContentValues().apply {
                    put("media_store_id", mediaStoreId)
                    put("face_index", face.faceIndex)
                    put("embedding_blob", faceEmbeddingCrypto.encrypt(face.embedding))
                    put("detection_score", face.detectionScore)
                    put("face_left", face.box.left)
                    put("face_top", face.box.top)
                    put("face_right", face.box.right)
                    put("face_bottom", face.box.bottom)
                    put("cluster_id", "")
                }
                db.insertOrThrow(TABLE_FACE_EMBEDDINGS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun allFaceEmbeddings(): List<FaceEmbeddingRecord> {
        return queryFaceEmbeddings()
    }

    /** Returns only named face occurrences in a small answer-evidence set. */
    fun taggedFaceOccurrencesForMedia(mediaStoreIds: LongArray): List<TaggedFaceOccurrence> {
        if (mediaStoreIds.isEmpty()) return emptyList()
        val placeholders = mediaStoreIds.joinToString(",") { "?" }
        val result = ArrayList<TaggedFaceOccurrence>()
        readableDatabase.query(
            "$TABLE_FACE_EMBEDDINGS f JOIN $TABLE_FACE_CLUSTERS c ON c.cluster_id = f.cluster_id",
            arrayOf(
                "f.media_store_id",
                "f.face_index",
                "c.label",
                "f.detection_score",
                "f.face_left",
                "f.face_top",
                "f.face_right",
                "f.face_bottom",
            ),
            "f.media_store_id IN ($placeholders) AND TRIM(c.label) <> ''",
            mediaStoreIds.map(Long::toString).toTypedArray(),
            null,
            null,
            "f.detection_score DESC, f.media_store_id ASC, f.face_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TaggedFaceOccurrence(
                    mediaStoreId = cursor.getLong(0),
                    faceIndex = cursor.getInt(1),
                    label = cursor.getString(2).trim(),
                    detectionScore = cursor.getFloat(3),
                    box = FaceBox(
                        left = cursor.getFloat(4),
                        top = cursor.getFloat(5),
                        right = cursor.getFloat(6),
                        bottom = cursor.getFloat(7),
                    ),
                )
            }
        }
        return result
    }

    private fun queryFaceEmbeddings(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
    ): List<FaceEmbeddingRecord> {
        val result = ArrayList<FaceEmbeddingRecord>()
        readableDatabase.query(
            TABLE_FACE_EMBEDDINGS,
            arrayOf(
                "id",
                "media_store_id",
                "face_index",
                "embedding_blob",
                "detection_score",
                "face_left",
                "face_top",
                "face_right",
                "face_bottom",
                "cluster_id",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "media_store_id ASC, face_index ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += FaceEmbeddingRecord(
                    id = cursor.getLong(0),
                    mediaStoreId = cursor.getLong(1),
                    faceIndex = cursor.getInt(2),
                    embedding = faceEmbeddingCrypto.decrypt(cursor.getBlob(3)),
                    detectionScore = cursor.getFloat(4),
                    box = FaceBox(
                        left = cursor.getFloat(5),
                        top = cursor.getFloat(6),
                        right = cursor.getFloat(7),
                        bottom = cursor.getFloat(8),
                    ),
                    clusterId = cursor.getString(9).orEmpty(),
                )
            }
        }
        return result
    }

    private fun faceEmbeddingsForClusters(clusterIds: List<String>): List<FaceEmbeddingRecord> {
        val ids = clusterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return queryFaceEmbeddings(
            selection = "cluster_id IN ($placeholders)",
            selectionArgs = ids.toTypedArray(),
        )
    }

    fun existingFaceClusters(): List<StoredFaceCluster> {
        val result = ArrayList<StoredFaceCluster>()
        readableDatabase.query(
            TABLE_FACE_CLUSTERS,
            arrayOf(
                "cluster_id",
                "label",
                "is_self",
                "centroid_blob",
                "representative_media_store_id",
                "face_count",
                "representative_face_id",
            ),
            null,
            null,
            null,
            null,
            "cluster_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredFaceCluster(
                    clusterId = cursor.getString(0),
                    label = cursor.getString(1),
                    isSelf = cursor.getInt(2) != 0,
                    centroid = faceEmbeddingCrypto.decrypt(cursor.getBlob(3)),
                    representativeMediaStoreId = cursor.getLong(4),
                    faceCount = cursor.getInt(5),
                    representativeFaceId = cursor.getLong(6),
                )
            }
        }
        return result
    }

    /**
     * A metadata-only preparation pass must not recluster every face. New or
     * replaced face embeddings are stored with an empty cluster ID, so this
     * inexpensive relational check is also the invalidation signal.
     */
    fun faceClustersAreCurrent(): Boolean {
        val db = readableDatabase
        val embeddingCount = db.compileStatement(
            "SELECT COUNT(*) FROM $TABLE_FACE_EMBEDDINGS",
        ).simpleQueryForLong()
        val clusterCount = db.compileStatement(
            "SELECT COUNT(*) FROM $TABLE_FACE_CLUSTERS",
        ).simpleQueryForLong()
        if (embeddingCount == 0L) return clusterCount == 0L
        if (clusterCount == 0L) return false
        val unassigned = db.compileStatement(
            "SELECT COUNT(*) FROM $TABLE_FACE_EMBEDDINGS WHERE TRIM(cluster_id) = ''",
        ).simpleQueryForLong()
        if (unassigned > 0L) return false
        val dangling = db.compileStatement(
            """
            SELECT COUNT(*)
            FROM $TABLE_FACE_EMBEDDINGS f
            LEFT JOIN $TABLE_FACE_CLUSTERS c ON c.cluster_id = f.cluster_id
            WHERE c.cluster_id IS NULL
            """.trimIndent(),
        ).simpleQueryForLong()
        return dangling == 0L
    }

    fun faceClusterCount(): Int = readableDatabase.compileStatement(
        "SELECT COUNT(*) FROM $TABLE_FACE_CLUSTERS",
    ).simpleQueryForLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun replaceFaceClusters(assignments: List<FaceClusterAssignment>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE $TABLE_FACE_EMBEDDINGS SET cluster_id = ''")
            db.execSQL("UPDATE $TABLE_MEDIA SET person_cluster_id = NULL")
            assignments.forEach { assignment ->
                val clusterValues = ContentValues().apply {
                    put("cluster_id", assignment.clusterId)
                    put("label", assignment.label)
                    put("is_self", if (assignment.isSelf) 1 else 0)
                    put("centroid_blob", faceEmbeddingCrypto.encrypt(assignment.centroid))
                    put("representative_media_store_id", assignment.representativeMediaStoreId)
                    put("face_count", assignment.memberFaceIds.size)
                    put("representative_face_id", assignment.representativeFaceId)
                    put("updated_at_ms", System.currentTimeMillis())
                }
                val updated = db.update(
                    TABLE_FACE_CLUSTERS,
                    clusterValues,
                    "cluster_id = ?",
                    arrayOf(assignment.clusterId),
                )
                if (updated == 0) db.insertOrThrow(TABLE_FACE_CLUSTERS, null, clusterValues)

                assignment.memberFaceIds.forEach { faceId ->
                    val faceValues = ContentValues().apply {
                        put("cluster_id", assignment.clusterId)
                    }
                    db.update(
                        TABLE_FACE_EMBEDDINGS,
                        faceValues,
                        "id = ?",
                        arrayOf(faceId.toString()),
                    )
                }
                assignment.memberMediaStoreIds.distinct().forEach { mediaStoreId ->
                    val mediaValues = ContentValues().apply {
                        put("person_cluster_id", assignment.clusterId)
                    }
                    db.update(
                        TABLE_MEDIA,
                        mediaValues,
                        "media_store_id = ? AND NOT EXISTS (SELECT 1 FROM $TABLE_FACE_EMBEDDINGS other WHERE other.media_store_id = media_items.media_store_id AND other.cluster_id <> ?)",
                        arrayOf(mediaStoreId.toString(), assignment.clusterId),
                    )
                }
            }
            val activeIds = assignments.map { it.clusterId }
            if (activeIds.isEmpty()) {
                db.delete(TABLE_FACE_CLUSTERS, null, null)
            } else {
                val placeholders = activeIds.joinToString(",") { "?" }
                db.delete(
                    TABLE_FACE_CLUSTERS,
                    "cluster_id NOT IN ($placeholders)",
                    activeIds.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Returns the complete review inventory unless an explicit diagnostic limit is supplied. */
    fun faceClusters(limit: Int? = null): List<FaceCluster> {
        val result = ArrayList<FaceCluster>()
        val safeLimit = limit?.coerceAtLeast(1)?.toString()
        val projection = arrayOf(
            "c.cluster_id AS cluster_id",
            "c.label AS cluster_label",
            "c.is_self AS cluster_is_self",
            "c.face_count AS cluster_face_count",
            "c.representative_face_id AS representative_face_id",
            "m.media_store_id",
            "m.content_uri",
            "m.mime_type",
            "'' AS display_name",
            "0 AS date_modified_seconds",
            "0 AS size_bytes",
            "m.width",
            "m.height",
            "0 AS duration_ms",
            "NULL AS person_cluster_id",
            "'' AS ocr_text",
            "'scenary' AS content_class",
            "NULL AS date_taken_ms",
            "NULL AS location_raw",
            "NULL AS location_name",
            "0 AS location_enrichment_state",
            "NULL AS person_label",
            "f.face_left AS face_left",
            "f.face_top AS face_top",
            "f.face_right AS face_right",
            "f.face_bottom AS face_bottom",
        )
        readableDatabase.query(
            "$TABLE_FACE_CLUSTERS c JOIN $TABLE_MEDIA m ON m.media_store_id = c.representative_media_store_id LEFT JOIN $TABLE_FACE_EMBEDDINGS f ON f.id = c.representative_face_id",
            projection,
            "c.face_count > 0",
            null,
            null,
            null,
            "c.face_count DESC, c.cluster_id ASC",
            safeLimit,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += FaceCluster(
                    clusterId = cursor.getString(cursor.getColumnIndexOrThrow("cluster_id")),
                    label = cursor.getString(cursor.getColumnIndexOrThrow("cluster_label")),
                    isSelf = cursor.getInt(cursor.getColumnIndexOrThrow("cluster_is_self")) != 0,
                    faceCount = cursor.getInt(cursor.getColumnIndexOrThrow("cluster_face_count")),
                    representative = cursor.toGalleryMedia(),
                    representativeBox = cursor.faceBoxOrNull(),
                )
            }
        }
        return result
    }

    /** Merges selected anonymous groups into the first selected group. */
    fun mergeFaceClusters(clusterIds: List<String>) {
        val ids = clusterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(ids.size >= 2) { "Select at least two face groups to merge" }
        val existing = existingFaceClusters().associateBy { it.clusterId }
        require(ids.all(existing::containsKey)) { "One or more selected face groups no longer exist" }

        val targetId = ids.first()
        val sourceIds = ids.drop(1)
        val placeholders = sourceIds.joinToString(",") { "?" }
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.update(
                TABLE_FACE_EMBEDDINGS,
                ContentValues().apply { put("cluster_id", targetId) },
                "cluster_id IN ($placeholders)",
                sourceIds.toTypedArray(),
            )

            // Do not decrypt every face in the gallery here. A merge only needs
            // the embeddings that belong to the selected groups; the full table
            // can contain tens of thousands of encrypted records.
            val mergedRecords = faceEmbeddingsForClusters(listOf(targetId))
            require(mergedRecords.isNotEmpty()) { "Selected face groups contain no embeddings" }
            val centroid = averageEmbedding(mergedRecords)
            val representative = mergedRecords.maxByOrNull { it.detectionScore }
                ?: mergedRecords.first()
            val label = ids.asSequence()
                .mapNotNull { existing[it]?.label?.trim()?.takeIf(String::isNotEmpty) }
                .firstOrNull()
                .orEmpty()
            val isSelf = ids.any { existing[it]?.isSelf == true }
            if (isSelf) {
                db.update(
                    TABLE_FACE_CLUSTERS,
                    ContentValues().apply { put("is_self", 0) },
                    null,
                    null,
                )
            }
            val clusterValues = ContentValues().apply {
                put("label", label)
                put("is_self", if (isSelf) 1 else 0)
                put("centroid_blob", faceEmbeddingCrypto.encrypt(centroid))
                put("representative_media_store_id", representative.mediaStoreId)
                put("face_count", mergedRecords.size)
                put("representative_face_id", representative.id)
                put("updated_at_ms", System.currentTimeMillis())
            }
            val updated = db.update(
                TABLE_FACE_CLUSTERS,
                clusterValues,
                "cluster_id = ?",
                arrayOf(targetId),
            )
            require(updated == 1) { "Could not update merged face group" }
            db.delete(
                TABLE_FACE_CLUSTERS,
                "cluster_id IN ($placeholders)",
                sourceIds.toTypedArray(),
            )

            val mediaIds = mergedRecords.map { it.mediaStoreId }.distinct()
            val mediaPlaceholders = mediaIds.joinToString(",") { "?" }
            db.update(
                TABLE_MEDIA,
                ContentValues().apply { putNull("person_cluster_id") },
                "media_store_id IN ($mediaPlaceholders)",
                mediaIds.map(Long::toString).toTypedArray(),
            )
            mediaIds.forEach { mediaStoreId ->
                db.update(
                    TABLE_MEDIA,
                    ContentValues().apply { put("person_cluster_id", targetId) },
                    "media_store_id = ? AND NOT EXISTS (SELECT 1 FROM $TABLE_FACE_EMBEDDINGS other WHERE other.media_store_id = media_items.media_store_id AND other.cluster_id <> ?)",
                    arrayOf(mediaStoreId.toString(), targetId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveFaceClusterIdentity(clusterId: String, label: String, isSelf: Boolean) {
        val cleanLabel = label.trim()
        require(cleanLabel.isNotBlank()) { "Face label cannot be blank" }
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            if (isSelf) {
                db.update(
                    TABLE_FACE_CLUSTERS,
                    ContentValues().apply { put("is_self", 0) },
                    null,
                    null,
                )
            }
            val updated = db.update(
                TABLE_FACE_CLUSTERS,
                ContentValues().apply {
                    put("label", cleanLabel)
                    put("is_self", if (isSelf) 1 else 0)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "cluster_id = ?",
                arrayOf(clusterId),
            )
            require(updated == 1) { "Face group no longer exists" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Returns named face labels explicitly mentioned in a user query. */
    fun namedPersonLabelsMentioned(query: String): List<String> {
        val normalizedQuery = normalizePersonText(query)
        if (normalizedQuery.isBlank()) return emptyList()
        return namedPersonLabels().filter { label ->
            val normalizedLabel = normalizePersonText(label)
            normalizedLabel.isNotBlank() &&
                containsPersonMention(normalizedQuery, normalizedLabel) &&
                !isNegatedPersonMention(normalizedQuery, normalizedLabel)
        }
    }

    /** Returns labels mentioned in a negative clause such as "without Ramani". */
    fun namedPersonLabelsNegated(query: String): List<String> {
        val normalizedQuery = normalizePersonText(query)
        if (normalizedQuery.isBlank()) return emptyList()
        return namedPersonLabels().filter { label ->
            val normalizedLabel = normalizePersonText(label)
            normalizedLabel.isNotBlank() &&
                containsPersonMention(normalizedQuery, normalizedLabel) &&
                isNegatedPersonMention(normalizedQuery, normalizedLabel)
        }
    }

    /** Resolves planner-produced names to labels that really exist locally. */
    fun resolveNamedPersonLabels(names: List<String>): List<String> {
        val requested = names.map(::normalizePersonText).filter(String::isNotBlank).toSet()
        if (requested.isEmpty()) return emptyList()
        return namedPersonLabels().filter { normalizePersonText(it) in requested }
    }

    /** Small trusted vocabulary supplied to the on-device LLM planner for typo correction. */
    fun namedPersonLabelsForPlanning(): List<String> = namedPersonLabels()

    /** The one face label explicitly identified by the user as themselves. */
    fun selfPersonLabelForPlanning(): String? = readableDatabase.query(
        TABLE_FACE_CLUSTERS,
        arrayOf("label"),
        "is_self = 1 AND TRIM(label) <> ''",
        null,
        null,
        null,
        "updated_at_ms DESC",
        "1",
    ).use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)
        } else {
            null
        }
    }

    /** Returns indexed media containing at least one requested tagged face. */
    fun mediaStoreIdsForPersonLabels(labels: List<String>): Set<Long> {
        val normalizedLabels = labels.map(::normalizePersonText)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedLabels.isEmpty()) return emptySet()
        val placeholders = normalizedLabels.joinToString(",") { "?" }
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            true,
            "$TABLE_FACE_EMBEDDINGS f JOIN $TABLE_FACE_CLUSTERS c ON c.cluster_id = f.cluster_id",
            arrayOf("f.media_store_id"),
            "LOWER(TRIM(c.label)) IN ($placeholders)",
            normalizedLabels.toTypedArray(),
            null,
            null,
            "f.media_store_id ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    /** Returns media whose detected faces are exactly the requested named people. */
    fun mediaStoreIdsForOnlyPersonLabels(labels: List<String>): Set<Long> {
        val normalizedLabels = labels.map(::normalizePersonText)
            .filter(String::isNotBlank)
            .distinct()
        if (normalizedLabels.isEmpty()) return emptySet()
        val placeholders = normalizedLabels.joinToString(",") { "?" }
        val args = normalizedLabels.toTypedArray()
        val ids = LinkedHashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT f.media_store_id FROM $TABLE_FACE_EMBEDDINGS f " +
                "WHERE EXISTS (SELECT 1 FROM $TABLE_FACE_EMBEDDINGS wanted " +
                "JOIN $TABLE_FACE_CLUSTERS wc ON wc.cluster_id = wanted.cluster_id " +
                "WHERE wanted.media_store_id = f.media_store_id " +
                "AND LOWER(TRIM(wc.label)) IN ($placeholders)) " +
                "AND NOT EXISTS (SELECT 1 FROM $TABLE_FACE_EMBEDDINGS other " +
                "LEFT JOIN $TABLE_FACE_CLUSTERS oc ON oc.cluster_id = other.cluster_id " +
                "WHERE other.media_store_id = f.media_store_id " +
                "AND (oc.label IS NULL OR LOWER(TRIM(oc.label)) NOT IN ($placeholders)))",
            args + args,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun namedPersonLabels(): List<String> {
        val labels = LinkedHashSet<String>()
        readableDatabase.query(
            TABLE_FACE_CLUSTERS,
            arrayOf("label"),
            "TRIM(label) <> ''",
            null,
            null,
            null,
            "updated_at_ms DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.trim()?.takeIf(String::isNotBlank)?.let(labels::add)
            }
        }
        return labels.toList()
    }

    private fun isNegatedPersonMention(query: String, label: String): Boolean {
        val directPattern = Regex(
            "(?i)\\b(?:without|with\\s+out|excluding|exclude|except|but\\s+not|not|no)\\s+(?:the\\s+)?" +
                Regex.escape(label) + "(?:\\b|$)",
        )
        if (directPattern.containsMatchIn(query)) return true
        val scopedPattern = Regex(
            "(?i)\\b(?:no|not)\\s+(?:photos?|pictures?|images?|with|containing|including|of)\\s+" +
                "(?:the\\s+)?" + Regex.escape(label) + "(?:\\b|$)",
        )
        if (scopedPattern.containsMatchIn(query)) return true
        val absentPattern = Regex(
            "(?i)\\b" + Regex.escape(label) +
                "\\s+(?:is\\s+|are\\s+)?(?:absent|excluded|missing|not\\s+present)\\b",
        )
        return absentPattern.containsMatchIn(query)
    }

    private fun containsPersonMention(query: String, label: String): Boolean =
        query == label ||
            query.startsWith("$label ") ||
            query.endsWith(" $label") ||
            query.contains(" $label ")

    private fun normalizePersonText(value: String): String = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun hasUnnamedFaceClusters(): Boolean =
        readableDatabase.compileStatement(
            "SELECT EXISTS(SELECT 1 FROM $TABLE_FACE_CLUSTERS WHERE TRIM(label) = '' AND face_count > 0)",
        ).simpleQueryForLong() != 0L

    private fun averageEmbedding(records: List<FaceEmbeddingRecord>): FloatArray {
        val sum = FloatArray(records.first().embedding.size)
        records.forEach { record ->
            require(record.embedding.size == sum.size) { "Face embeddings have inconsistent dimensions" }
            record.embedding.forEachIndexed { index, value -> sum[index] += value }
        }
        var normSquared = 0.0
        sum.forEach { value -> normSquared += value.toDouble() * value.toDouble() }
        val norm = sqrt(normSquared).toFloat().coerceAtLeast(1.0e-12f)
        return FloatArray(sum.size) { index -> sum[index] / norm }
    }

    fun searchMetadata(query: String, limit: Int = 8): List<GalleryMedia> {
        return searchMetadataRanked(query, limit).map { it.media }
    }

    /** Returns the existing indexed rows for a hard MIME intersection. */
    fun mediaStoreIdsForMediaType(type: QueryMediaType): Set<Long> {
        // Messages, SMS, files, and the other private-source selectors are
        // not Gallery rows. Their scope is applied by DocumentVectorIndex;
        // returning an empty gallery set prevents cross-source leakage.
        val prefix = type.mimePrefix() ?: return emptySet()
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            TABLE_MEDIA,
            arrayOf("media_store_id"),
            "mime_type LIKE ?",
            arrayOf("$prefix%"),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    /**
     * Resolves the planner category into a persisted, query-time allowlist.
     *
     * DOC and SCENARY are assigned once by the OCR indexing pass. The other
     * categories use their authoritative indexed metadata so category routing
     * scopes the full result browser as well as the private answer context.
     */
    fun mediaStoreIdsForQueryCategory(category: QueryCategory): Set<Long> {
        val selection = when (category) {
            QueryCategory.DOC -> "content_class = '${MediaContentClass.DOC.wireName}'"
            QueryCategory.SCENARY -> "content_class = '${MediaContentClass.SCENARY.wireName}'"
            QueryCategory.PERSON ->
                """
                EXISTS (
                    SELECT 1
                    FROM $TABLE_FACE_EMBEDDINGS f
                    JOIN $TABLE_FACE_CLUSTERS c ON c.cluster_id = f.cluster_id
                    WHERE f.media_store_id = media_items.media_store_id
                      AND TRIM(c.label) <> ''
                )
                """.trimIndent()
            QueryCategory.LOCATION -> "TRIM(COALESCE(location_name, '')) <> ''"
            QueryCategory.TIME -> "date_taken_ms IS NOT NULL OR date_modified_seconds > 0"
        }
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            TABLE_MEDIA,
            arrayOf("media_store_id"),
            selection,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    /**
     * Executes a date predicate against persisted capture metadata. A non-null
     * empty set is authoritative; null means the v8 enrichment pass has not
     * produced any usable timestamps yet.
     */
    fun mediaStoreIdsMatchingTime(scope: QueryTimeScope): Set<Long>? {
        var sawTimestamp = false
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            TABLE_MEDIA,
            arrayOf("media_store_id", "date_taken_ms", "date_modified_seconds"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val capture = if (cursor.isNull(1)) null else cursor.getLong(1)
                val modified = cursor.getLong(2).takeIf { it > 0L }?.times(1000L)
                val timestamp = capture ?: modified ?: continue
                sawTimestamp = true
                if (scope.matches(timestamp)) ids += cursor.getLong(0)
            }
        }
        return ids.takeIf { sawTimestamp }
    }

    /**
     * Executes a human-readable place predicate directly against the search
     * metadata stored during indexing.
     */
    fun mediaStoreIdsMatchingLocation(requested: String): Set<Long>? {
        val terms = requested.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .distinct()
            .take(6)
        if (terms.isEmpty()) return null
        var sawLocation = false
        val ids = LinkedHashSet<Long>()
        readableDatabase.query(
            TABLE_MEDIA,
            arrayOf("media_store_id", "location_name", "location_raw"),
            "TRIM(COALESCE(location_name, '')) <> '' OR TRIM(COALESCE(location_raw, '')) <> ''",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sawLocation = true
                val actual = "${cursor.getString(1).orEmpty()} ${cursor.getString(2).orEmpty()}"
                    .lowercase()
                if (terms.all(actual::contains)) ids += cursor.getLong(0)
            }
        }
        if (ids.isNotEmpty()) return ids
        val hasPendingLocationRows = readableDatabase.compileStatement(
            "SELECT EXISTS(SELECT 1 FROM $TABLE_MEDIA WHERE location_enrichment_state = ? LIMIT 1)",
        ).apply {
            bindLong(1, LOCATION_PENDING.toLong())
        }.simpleQueryForLong() != 0L
        return emptySet<Long>().takeIf { sawLocation && !hasPendingLocationRows }
    }

    fun searchMetadataRanked(
        query: String,
        limit: Int = 16,
        allowedMediaStoreIds: Set<Long>? = null,
    ): List<MetadataMatch> = searchMetadataRanked(listOf(query), limit, allowedMediaStoreIds)

    /**
     * Matches planner-selected document keywords against OCR text only.
     * Rows containing partial keyword coverage are retained so hybrid ranking
     * can reduce their keyword contribution proportionally.
     */
    fun searchOcrKeywordsRanked(
        keywords: List<String>,
        limit: Int = 512,
        allowedMediaStoreIds: Set<Long>? = null,
    ): List<MetadataMatch> {
        val terms = keywords.asSequence()
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
            .distinct()
            .take(OcrKeywordPolicy.MAX_KEYWORDS)
            .toList()
        if (terms.isEmpty()) return emptyList()
        // A keyword predicate is an AND group: all requested words must be
        // present in the same OCR record. Semantic retrieval remains the
        // separate OR branch of gallery hybrid search.
        val selection = terms.joinToString(" AND ") {
            "LOWER(ocr_text) LIKE ? ESCAPE '\\'"
        }
        val args = terms.map { term ->
            "%${escapeSqlLike(term)}%"
        }.toTypedArray()
        return queryMediaScoped(
            selection = selection,
            selectionArgs = args,
            orderBy = "date_modified_seconds DESC",
            allowedMediaStoreIds = allowedMediaStoreIds,
        ).asSequence()
            .map { media ->
                MetadataMatch(media, OcrKeywordPolicy.score(media.ocrText, terms))
            }
            .sortedByDescending { it.media.dateModifiedSeconds }
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
            .toList()
    }

    /** Searches several planner-produced keyword forms and keeps the best row score. */
    fun searchMetadataRanked(
        queries: List<String>,
        limit: Int = 16,
        allowedMediaStoreIds: Set<Long>? = null,
    ): List<MetadataMatch> {
        val bestByMediaId = LinkedHashMap<Long, MetadataMatch>()
        queries.filter { it.isNotBlank() }.take(6).forEach { query ->
            searchMetadataRankedSingle(query, limit, allowedMediaStoreIds).forEach { match ->
                val previous = bestByMediaId[match.media.mediaStoreId]
                if (previous == null || match.score > previous.score) {
                    bestByMediaId[match.media.mediaStoreId] = match
                }
            }
        }
        return bestByMediaId.values
            .sortedWith(compareByDescending<MetadataMatch> { it.score }.thenByDescending { it.media.dateModifiedSeconds })
            .take(limit.coerceIn(1, MAX_SEARCH_RESULTS))
    }

    private fun searchMetadataRankedSingle(
        query: String,
        limit: Int,
        allowedMediaStoreIds: Set<Long>? = null,
    ): List<MetadataMatch> {
        val terms = metadataTerms(query)
        if (terms.isEmpty()) return emptyList()
        val selection = terms.joinToString(" AND ") {
            "(display_name LIKE ? OR ocr_text LIKE ? OR location_name LIKE ? OR location_raw LIKE ? OR EXISTS (SELECT 1 FROM $TABLE_FACE_EMBEDDINGS f JOIN $TABLE_FACE_CLUSTERS c ON c.cluster_id = f.cluster_id WHERE f.media_store_id = media_items.media_store_id AND c.label LIKE ?))"
        }
        val args = terms.flatMap { term ->
            val pattern = "%$term%"
            listOf(pattern, pattern, pattern, pattern, pattern)
        }.toTypedArray()
        return queryMediaScoped(
            selection = selection,
            selectionArgs = args,
            orderBy = "date_modified_seconds DESC",
            limit = limit.coerceIn(1, MAX_SEARCH_RESULTS).toString(),
            allowedMediaStoreIds = allowedMediaStoreIds,
        ).map { media ->
            MetadataMatch(media, metadataScore(media, terms))
        }.sortedWith(compareByDescending<MetadataMatch> { it.score }.thenByDescending { it.media.dateModifiedSeconds })
    }

    private fun metadataTerms(query: String): List<String> = query.trim()
            .split(Regex("\\s+"))
            .map { it.trim().replace("%", "").lowercase() }
            .filter { it.isNotEmpty() }
            .take(6)

    private fun escapeSqlLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun metadataScore(media: GalleryMedia, terms: List<String>): Float {
        val displayName = media.displayName.lowercase()
        val ocrText = media.ocrText.lowercase()
        val personLabel = media.personLabel.orEmpty().lowercase()
        val location = "${media.locationName.orEmpty()} ${media.location.orEmpty()}".lowercase()
        var score = 0f
        terms.forEach { term ->
            if (personLabel.contains(term)) score += 1.00f
            if (location.contains(term)) score += 0.95f
            if (displayName.contains(term)) score += 0.85f
            if (ocrText.contains(term)) score += 0.75f
        }
        return score / terms.size.coerceAtLeast(1)
    }

    fun findByMediaStoreIds(ids: LongArray): List<GalleryMedia> {
        if (ids.isEmpty()) return emptyList()
        // Android SQLite builds commonly cap a statement at 999 bind
        // variables. A MIME/date predicate can legitimately match thousands
        // of indexed rows, so query in bounded chunks and restore the
        // caller's order afterwards.
        val rows = ids.asList()
            .chunked(SQLITE_ID_CHUNK)
            .flatMap { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                queryMedia(
                    selection = "media_store_id IN ($placeholders)",
                    selectionArgs = chunk.map(Long::toString).toTypedArray(),
                )
            }
        val byId = rows.associateBy { it.mediaStoreId }
        val ordered = ArrayList<GalleryMedia>(ids.size)
        for (id in ids) byId[id]?.let(ordered::add)
        return ordered
    }

    /** Applies a large ID allowlist without relying on SQLite's bind limit. */
    private fun queryMediaScoped(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String? = null,
        limit: String? = null,
        allowedMediaStoreIds: Set<Long>?,
    ): List<GalleryMedia> {
        if (allowedMediaStoreIds == null) {
            return queryMedia(selection, selectionArgs, orderBy, limit)
        }
        if (allowedMediaStoreIds.isEmpty()) return emptyList()
        val rows = allowedMediaStoreIds.toList().chunked(SQLITE_ID_CHUNK).flatMap { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val scopedSelection = listOfNotNull(
                selection?.takeIf(String::isNotBlank)?.let { "($it)" },
                "media_store_id IN ($placeholders)",
            ).joinToString(" AND ")
            queryMedia(
                selection = scopedSelection,
                selectionArgs = (selectionArgs.orEmpty().toList() + chunk.map(Long::toString)).toTypedArray(),
                orderBy = orderBy,
            )
        }
        return rows
            .distinctBy { it.mediaStoreId }
            .sortedWith(
                when (orderBy) {
                    "date_modified_seconds DESC" -> compareByDescending<GalleryMedia> { it.dateModifiedSeconds }
                    else -> compareBy { it.mediaStoreId }
                },
            )
            .let { if (limit == null) it else it.take(limit.toInt()) }
    }

    private fun queryMedia(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        orderBy: String? = null,
        limit: String? = null,
        projection: Array<String> = MEDIA_PROJECTION,
    ): List<GalleryMedia> {
        val result = ArrayList<GalleryMedia>()
        readableDatabase.query(
            TABLE_MEDIA,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            orderBy,
            limit,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toGalleryMedia()
        }
        return result
    }

    private fun android.database.Cursor.toGalleryMedia(): GalleryMedia = GalleryMedia(
        mediaStoreId = getLong(getColumnIndexOrThrow("media_store_id")),
        contentUri = getString(getColumnIndexOrThrow("content_uri")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        dateModifiedSeconds = getLong(getColumnIndexOrThrow("date_modified_seconds")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        personClusterId = getStringOrNull("person_cluster_id"),
        personLabel = getStringOrNull("person_label"),
        ocrText = getString(getColumnIndexOrThrow("ocr_text")).orEmpty(),
        contentClass = MediaContentClass.fromWireName(
            getString(getColumnIndexOrThrow("content_class")),
        ),
        dateTakenMs = getLongOrNull("date_taken_ms"),
        location = getStringOrNull("location_raw"),
        locationName = getStringOrNull("location_name"),
        locationEnrichmentState = getInt(getColumnIndexOrThrow("location_enrichment_state")),
    )

    private fun android.database.Cursor.getStringOrNull(column: String): String? =
        getString(getColumnIndexOrThrow(column))?.takeIf { it.isNotBlank() }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun android.database.Cursor.faceBoxOrNull(): FaceBox? {
        val left = getFloat(getColumnIndexOrThrow("face_left"))
        val top = getFloat(getColumnIndexOrThrow("face_top"))
        val right = getFloat(getColumnIndexOrThrow("face_right"))
        val bottom = getFloat(getColumnIndexOrThrow("face_bottom"))
        return FaceBox(left, top, right, bottom).takeIf { it.right > it.left && it.bottom > it.top }
    }

    private fun createFaceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS face_embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_store_id INTEGER NOT NULL,
                face_index INTEGER NOT NULL,
                embedding_blob BLOB NOT NULL,
                detection_score REAL NOT NULL,
                face_left REAL NOT NULL DEFAULT 0,
                face_top REAL NOT NULL DEFAULT 0,
                face_right REAL NOT NULL DEFAULT 0,
                face_bottom REAL NOT NULL DEFAULT 0,
                cluster_id TEXT NOT NULL DEFAULT '',
                UNIQUE(media_store_id, face_index)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS face_embeddings_media ON face_embeddings(media_store_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS face_embeddings_cluster ON face_embeddings(cluster_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS face_clusters (
                cluster_id TEXT PRIMARY KEY,
                label TEXT NOT NULL DEFAULT '',
                is_self INTEGER NOT NULL DEFAULT 0,
                centroid_blob BLOB NOT NULL,
                representative_media_store_id INTEGER NOT NULL,
                face_count INTEGER NOT NULL,
                representative_face_id INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createLocationCache(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS location_cache (
                coordinate_key TEXT PRIMARY KEY,
                location_name TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createEpisodeTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_episodes (
                episode_id TEXT PRIMARY KEY,
                start_time_ms INTEGER NOT NULL,
                end_time_ms INTEGER NOT NULL,
                location_name TEXT,
                representative_media_store_id INTEGER NOT NULL,
                member_count INTEGER NOT NULL,
                person_cluster_ids TEXT NOT NULL DEFAULT '',
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_episode_members (
                episode_id TEXT NOT NULL,
                media_store_id INTEGER NOT NULL UNIQUE,
                member_ordinal INTEGER NOT NULL,
                PRIMARY KEY(episode_id, media_store_id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS photo_episode_members_media ON photo_episode_members(media_store_id)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS photo_episodes_time ON photo_episodes(start_time_ms, end_time_ms)",
        )
    }

    private fun createAnswerabilityFactsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS answerability_facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_store_id INTEGER NOT NULL,
                label TEXT NOT NULL,
                value TEXT NOT NULL,
                UNIQUE(media_store_id, label, value)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS answerability_facts_media ON answerability_facts(media_store_id)",
        )
    }

    companion object {
        private const val DATABASE_NAME = "gallery.db"
        private const val DATABASE_VERSION = 16
        private const val SQLITE_ID_CHUNK = 900
        private const val TABLE_MEDIA = "media_items"
        private const val TABLE_FACE_EMBEDDINGS = "face_embeddings"
        private const val TABLE_FACE_CLUSTERS = "face_clusters"
        private const val TABLE_LOCATION_CACHE = "location_cache"
        private const val TABLE_EPISODES = "photo_episodes"
        private const val TABLE_EPISODE_MEMBERS = "photo_episode_members"
        private const val TABLE_ANSWERABILITY_FACTS = "answerability_facts"
        const val LOCATION_PENDING = 0
        const val LOCATION_COMPLETE_NO_GPS = 1
        const val LOCATION_RESOLVED = 2
        /** Full-text relevance budget; exact structured set queries are not capped. */
        private const val MAX_SEARCH_RESULTS = 512
        private val MEDIA_PROJECTION = arrayOf(
            "media_store_id",
            "content_uri",
            "mime_type",
            "display_name",
            "date_modified_seconds",
            "size_bytes",
            "width",
            "height",
            "duration_ms",
            "person_cluster_id",
            "ocr_text",
            "content_class",
            "date_taken_ms",
            "location_raw",
            "location_name",
            "location_enrichment_state",
            "(SELECT group_concat(DISTINCT c.label) FROM face_embeddings f JOIN face_clusters c ON c.cluster_id = f.cluster_id WHERE f.media_store_id = media_items.media_store_id AND TRIM(c.label) <> '') AS person_label",
        )
        /**
         * Indexing queues do not need joined face labels. Keeping the alias in
         * the projection preserves GalleryMedia's row decoder while avoiding
         * one correlated face-cluster aggregation per pending media row.
         */
        private val INDEXING_PROJECTION = arrayOf(
            "media_store_id",
            "content_uri",
            "mime_type",
            "display_name",
            "date_modified_seconds",
            "size_bytes",
            "width",
            "height",
            "duration_ms",
            "person_cluster_id",
            "ocr_text",
            "content_class",
            "date_taken_ms",
            "location_raw",
            "location_name",
            "location_enrichment_state",
            "NULL AS person_label",
        )
    }
}

data class FaceEmbeddingInput(
    val faceIndex: Int,
    val embedding: FloatArray,
    val detectionScore: Float,
    val box: FaceBox,
)
