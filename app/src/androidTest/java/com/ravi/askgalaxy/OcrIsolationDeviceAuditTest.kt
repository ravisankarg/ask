package com.ravi.askgalaxy

import android.database.Cursor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that an OCR-only rebuild leaves every other persisted index byte
 * logically unchanged. Run capture before the worker and assert afterward.
 */
class OcrIsolationDeviceAuditTest {
    @Test
    fun captureIsolationBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = snapshot()
        context.getSharedPreferences(PREFS, 0).edit().apply {
            snapshot.forEach { (key, value) -> putString(key, value) }
        }.commit()
        Log.i(TAG, "OCR_ISOLATION_BASELINE|${snapshot.toLogFields()}")
    }

    @Test
    fun assertIsolationBaselineUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFS, 0)
        val expected = KEYS.associateWith {
            requireNotNull(preferences.getString(it, null)) {
                "Run captureIsolationBaseline before OCR reindex"
            }
        }
        val actual = snapshot()
        Log.i(TAG, "OCR_ISOLATION_AFTER|${actual.toLogFields()}")
        KEYS.forEach { key ->
            assertEquals("$key changed during OCR-only reindex", expected[key], actual[key])
        }
    }

    @Test
    fun assertOcrMigrationCompleteAndReportCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            val summary = db.rawQuery(
                """
                SELECT
                    SUM(CASE WHEN mime_type LIKE 'image/%' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN mime_type LIKE 'image/%'
                                  AND ocr_signature = ?
                                  AND ocr_indexed = 1 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN mime_type LIKE 'image/%'
                                  AND TRIM(ocr_text) <> '' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN content_class = 'doc' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN mime_type NOT LIKE 'image/%'
                                  AND TRIM(ocr_text) <> '' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN mime_type LIKE 'image/%'
                                  AND length(TRIM(ocr_text)) BETWEEN 1 AND 3
                             THEN 1 ELSE 0 END),
                    COALESCE(AVG(CASE WHEN mime_type LIKE 'image/%'
                                          AND TRIM(ocr_text) <> ''
                                     THEN length(ocr_text) END), 0)
                FROM media_items
                """.trimIndent(),
                arrayOf(OcrIndexContract.SIGNATURE),
            ).use { cursor ->
                check(cursor.moveToFirst())
                CorpusSummary(
                    images = cursor.getInt(0),
                    current = cursor.getInt(1),
                    textImages = cursor.getInt(2),
                    docs = cursor.getInt(3),
                    videosWithText = cursor.getInt(4),
                    tinyTextImages = cursor.getInt(5),
                    averageTextCharacters = cursor.getDouble(6),
                )
            }
            val pending = database.pendingOcrCount()
            val categoryMismatches = db.rawQuery(
                """
                SELECT COUNT(*)
                FROM media_items
                WHERE content_class <>
                    CASE
                        WHEN mime_type LIKE 'image/%' AND TRIM(ocr_text) <> ''
                            THEN 'doc'
                        ELSE 'scenary'
                    END
                """.trimIndent(),
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            Log.i(
                TAG,
                "OCR_CORPUS_FINAL|images=${summary.images}|" +
                    "current=${summary.current}|pending=$pending|" +
                    "docs=${summary.docs}|textImages=${summary.textImages}|" +
                    "videosWithText=${summary.videosWithText}|" +
                    "tinyTextImages=${summary.tinyTextImages}|" +
                    "avgTextChars=${"%.2f".format(summary.averageTextCharacters)}|" +
                    "categoryMismatches=$categoryMismatches",
            )
            assertEquals(summary.images, summary.current)
            assertEquals(0, pending)
            assertEquals(summary.textImages, summary.docs)
            assertEquals(0, summary.videosWithText)
            assertEquals(0, categoryMismatches)
        }
    }

    private fun snapshot(): Map<String, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = LinkedHashMap<String, String>()
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            result["media_non_ocr"] = queryDigest(
                db.rawQuery(
                    """
                    SELECT media_store_id, content_uri, mime_type, display_name,
                           date_modified_seconds, size_bytes, width, height,
                           duration_ms, person_cluster_id,
                           image_embedding_indexed, face_embedding_indexed,
                           embedding_signature, date_taken_ms, location_raw,
                           location_name, location_enrichment_state, updated_at_ms
                    FROM media_items
                    ORDER BY media_store_id
                    """.trimIndent(),
                    null,
                ),
            )
            result["faces"] = tableDigest(db, "face_embeddings", "id")
            result["clusters"] = tableDigest(db, "face_clusters", "cluster_id")
            result["locations"] = tableDigest(db, "location_cache", "coordinate_key")
            result["episodes"] = tableDigest(db, "photo_episodes", "episode_id")
            result["episode_members"] = tableDigest(
                db,
                "photo_episode_members",
                "episode_id, media_store_id",
            )
        }
        val vector = File(context.filesDir, "indexes/siglip2-768-4bit.tvim")
        assertTrue("Visual vector index is missing", vector.isFile)
        result["visual_vector"] = fileDigest(vector)
        return result
    }

    private fun tableDigest(
        database: android.database.sqlite.SQLiteDatabase,
        table: String,
        orderBy: String,
    ): String = queryDigest(
        database.rawQuery("SELECT * FROM $table ORDER BY $orderBy", null),
    )

    private fun queryDigest(cursor: Cursor): String = cursor.use {
        val digest = MessageDigest.getInstance("SHA-256")
        while (it.moveToNext()) {
            for (column in 0 until it.columnCount) {
                digest.update(it.getType(column).toByte())
                when (it.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> Unit
                    Cursor.FIELD_TYPE_INTEGER -> digest.update(it.getLong(column).toString().toByteArray())
                    Cursor.FIELD_TYPE_FLOAT -> digest.update(it.getDouble(column).toString().toByteArray())
                    Cursor.FIELD_TYPE_STRING -> digest.update(it.getString(column).toByteArray())
                    Cursor.FIELD_TYPE_BLOB -> digest.update(it.getBlob(column))
                }
                digest.update(0.toByte())
            }
            digest.update(1.toByte())
        }
        digest.digest().toHex()
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun Map<String, String>.toLogFields(): String =
        entries.joinToString("|") { (key, value) -> "$key=$value" }

    private data class CorpusSummary(
        val images: Int,
        val current: Int,
        val textImages: Int,
        val docs: Int,
        val videosWithText: Int,
        val tinyTextImages: Int,
        val averageTextCharacters: Double,
    )

    private companion object {
        const val TAG = "AskGalaxyOcrIsolation"
        const val PREFS = "ocr_isolation_audit"
        val KEYS = listOf(
            "media_non_ocr",
            "faces",
            "clusters",
            "locations",
            "episodes",
            "episode_members",
            "visual_vector",
        )
    }
}
