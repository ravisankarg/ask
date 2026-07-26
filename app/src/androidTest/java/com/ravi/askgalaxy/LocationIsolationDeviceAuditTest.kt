package com.ravi.askgalaxy

import android.database.Cursor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Installed-device proof that location-only migration/reindex changes no OCR,
 * visual, face, cluster, or episode payload.
 */
class LocationIsolationDeviceAuditTest {
    @Test
    fun captureLocationIsolationBaseline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "ACCESS_MEDIA_LOCATION must be granted before the location-only audit",
            MediaLocationAccess.hasPermission(context),
        )
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            val images = count(db, "mime_type LIKE 'image/%'")
            val pendingImages = count(
                db,
                "mime_type LIKE 'image/%' AND location_enrichment_state = 0",
            )
            Log.i(
                TAG,
                "LOCATION_MIGRATION|images=$images|pendingImages=$pendingImages|" +
                    "pendingTotal=${database.pendingLocationCount()}",
            )
            assertEquals(
                "The v12 migration did not invalidate every formerly redacted photo location",
                images,
                pendingImages,
            )
        }
        val snapshot = snapshot()
        context.getSharedPreferences(PREFS, 0).edit().apply {
            snapshot.forEach { (key, value) -> putString(key, value) }
        }.commit()
        Log.i(TAG, "LOCATION_ISOLATION_BASELINE|${snapshot.toLogFields()}")
    }

    @Test
    fun enqueueAndAwaitLocationOnlyWorker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(MediaLocationAccess.hasPermission(context))
        val pendingBefore = GalleryDatabase(context).use { it.pendingLocationCount() }
        assertTrue("No pending photo locations were available to rebuild", pendingBefore > 0)
        assertTrue(
            "Location-only work was not enqueued",
            LocationReindexScheduler.enqueueIfNeeded(context),
        )
        val workManager = WorkManager.getInstance(context)
        val deadline = SystemClock.elapsedRealtime() + WORK_TIMEOUT_MS
        var lastState = ""
        var terminal: WorkInfo? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val infos = workManager
                .getWorkInfosForUniqueWork(LocationReindexScheduler.UNIQUE_WORK_NAME)
                .get(15L, TimeUnit.SECONDS)
            val current = infos.maxByOrNull { it.runAttemptCount }
            if (current != null) {
                val progress = current.progress
                val state = "${current.state}|attempt=${current.runAttemptCount}|" +
                    "completed=${progress.getInt(LocationReindexWorker.KEY_COMPLETED, 0)}|" +
                    "total=${progress.getInt(LocationReindexWorker.KEY_TOTAL, pendingBefore)}|" +
                    "gps=${progress.getInt(LocationReindexWorker.KEY_WITH_GPS, 0)}|" +
                    "resolved=${progress.getInt(LocationReindexWorker.KEY_RESOLVED, 0)}"
                if (state != lastState) {
                    lastState = state
                    Log.i(TAG, "LOCATION_WORK|$state")
                }
                if (current.state.isFinished) {
                    terminal = current
                    break
                }
            }
            SystemClock.sleep(WORK_POLL_MS)
        }
        val finished = requireNotNull(terminal) {
            "Location-only worker timed out: $lastState"
        }
        assertEquals("Location-only worker did not succeed", WorkInfo.State.SUCCEEDED, finished.state)
        val pendingAfter = GalleryDatabase(context).use { it.pendingLocationCount() }
        Log.i(
            TAG,
            "LOCATION_WORK_DONE|before=$pendingBefore|after=$pendingAfter|" +
                "resolved=${finished.outputData.getInt(LocationReindexWorker.KEY_RESOLVED, 0)}",
        )
        assertEquals("Location-only worker left pending rows", 0, pendingAfter)
    }

    @Test
    fun unresolvedLocationShapeIsAuditable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            var pending = 0
            val distinctRaw = LinkedHashSet<String>()
            var nullIsland = 0
            var parseable = 0
            db.rawQuery(
                """
                SELECT location_raw
                FROM media_items
                WHERE location_enrichment_state = 0
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    pending += 1
                    val raw = cursor.getString(0).orEmpty()
                    distinctRaw += raw
                    val point = GalleryMetadataReader.parseCoordinates(raw)
                    if (point != null) {
                        parseable += 1
                        if (
                            kotlin.math.abs(point.first) < NULL_ISLAND_EPSILON &&
                            kotlin.math.abs(point.second) < NULL_ISLAND_EPSILON
                        ) {
                            nullIsland += 1
                        }
                    }
                }
            }
            Log.i(
                TAG,
                "LOCATION_UNRESOLVED_SHAPE|pending=$pending|" +
                    "distinctRaw=${distinctRaw.size}|parseable=$parseable|nullIsland=$nullIsland",
            )
        }
    }

    @Test
    fun assertLocationIsolationAndFinalCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(PREFS, 0)
        val expected = KEYS.associateWith {
            requireNotNull(preferences.getString(it, null)) {
                "Run captureLocationIsolationBaseline before location reindex"
            }
        }
        val actual = snapshot()
        Log.i(TAG, "LOCATION_ISOLATION_AFTER|${actual.toLogFields()}")
        KEYS.forEach { key ->
            assertEquals("$key changed during location-only reindex", expected[key], actual[key])
        }

        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            val imageRaw = count(
                db,
                "mime_type LIKE 'image/%' AND TRIM(COALESCE(location_raw, '')) <> ''",
            )
            val imageNamed = count(
                db,
                "mime_type LIKE 'image/%' AND TRIM(COALESCE(location_name, '')) <> ''",
            )
            val videoNamed = count(
                db,
                "mime_type LIKE 'video/%' AND TRIM(COALESCE(location_name, '')) <> ''",
            )
            val cache = db.rawQuery("SELECT COUNT(*) FROM location_cache", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val pending = database.pendingLocationCount()
            Log.i(
                TAG,
                "LOCATION_CORPUS_FINAL|imageRaw=$imageRaw|imageNamed=$imageNamed|" +
                    "videoNamed=$videoNamed|cache=$cache|pending=$pending",
            )
            assertTrue("No photo GPS survived the unredacted EXIF pass", imageRaw > 0)
            assertTrue("No photo GPS was resolved to a readable place", imageNamed > 0)
            assertEquals("Every discovered photo GPS should have a readable indexed place", imageRaw, imageNamed)
            assertTrue("Previously indexed video locations were lost", videoNamed > 0)
            assertEquals("Location-only reindex did not finish", 0, pending)
            assertTrue(
                "Location-category photo retrieval remains empty",
                database.mediaStoreIdsForQueryCategory(QueryCategory.LOCATION)
                    .intersect(database.mediaStoreIdsForMediaType(QueryMediaType.PHOTOS))
                    .isNotEmpty(),
            )
        }
    }

    private fun snapshot(): Map<String, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = LinkedHashMap<String, String>()
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            result["media_non_location"] = queryDigest(
                db.rawQuery(
                    """
                    SELECT media_store_id, content_uri, mime_type, display_name,
                           date_modified_seconds, size_bytes, width, height,
                           duration_ms, ocr_text, content_class, ocr_indexed,
                           ocr_signature, person_cluster_id,
                           image_embedding_indexed, face_embedding_indexed,
                           embedding_signature, date_taken_ms
                    FROM media_items
                    ORDER BY media_store_id
                    """.trimIndent(),
                    null,
                ),
            )
            result["faces"] = tableDigest(db, "face_embeddings", "id")
            result["clusters"] = tableDigest(db, "face_clusters", "cluster_id")
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

    private fun count(
        database: android.database.sqlite.SQLiteDatabase,
        where: String,
    ): Int = database.rawQuery(
        "SELECT COUNT(*) FROM media_items WHERE $where",
        null,
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
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

    private companion object {
        const val TAG = "AskGalaxyLocationIsolation"
        const val PREFS = "location_isolation_audit"
        val KEYS = listOf(
            "media_non_location",
            "faces",
            "clusters",
            "episodes",
            "episode_members",
            "visual_vector",
        )
        const val WORK_POLL_MS = 1_000L
        const val WORK_TIMEOUT_MS = 30L * 60L * 1_000L
        const val NULL_ISLAND_EPSILON = 1.0e-5
    }
}
