package com.ravi.askgalaxy

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-only end-to-end audit for the installed Gemma planner, structured
 * executor, persisted category index, and Context Picker.
 *
 * Run with `adb install -r` for both APKs so the indexed gallery and installed
 * model remain intact. The QP10 log lines are deliberately machine-readable.
 */
class QueryPlannerContextBenchmarkTest {
    @Test
    fun misspelledMovieTicketQueryPlansAndSearches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        try {
            val latch = CountDownLatch(1)
            var searchResult: Result<SearchResponse>? = null
            indexer.searchAsync(
                query = "how much did I spend on the odyssy movie ticket",
                onProgress = {},
                onFinished = {
                    searchResult = it
                    latch.countDown()
                },
            )
            assertTrue(
                "Timed out planning the misspelled movie-ticket query",
                latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val response = requireNotNull(searchResult).getOrThrow()
            val compiled = ExecutionSpecCompiler.compile(
                QueryExecutionSpec.parse(response.effectivePlanJson),
            )
            Log.i(
                TAG,
                "MOVIE_TICKET_QP|category=${compiled.queryCategory.wireName}|" +
                    "from=${compiled.fromDate}|to=${compiled.toDate}|" +
                    "mime=${compiled.mediaType?.label().orEmpty()}|" +
                    "results=${response.totalGalleryMatches}|spec=${response.effectivePlanJson}",
            )
            assertEquals(QueryCategory.DOC, compiled.queryCategory)
            assertEquals("", compiled.fromDate)
            assertEquals("", compiled.toDate)
            assertTrue(
                "Movie ticket must remain searchable semantic content",
                compiled.semanticQueries.any {
                    it.contains("movie", ignoreCase = true) &&
                        it.contains("ticket", ignoreCase = true)
                },
            )
            assertTrue("Movie-ticket search returned no gallery results", response.totalGalleryMatches > 0)
        } finally {
            indexer.close()
        }
    }

    @Test
    fun specificDateQueryClosesBothDateBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        try {
            val latch = CountDownLatch(1)
            var searchResult: Result<SearchResponse>? = null
            indexer.searchAsync(
                query = "Show photos taken on 5 October 2025",
                onProgress = {},
                onFinished = {
                    searchResult = it
                    latch.countDown()
                },
            )
            assertTrue(
                "Timed out planning a single-day photo query",
                latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val response = requireNotNull(searchResult).getOrThrow()
            val compiled = ExecutionSpecCompiler.compile(
                QueryExecutionSpec.parse(response.effectivePlanJson),
            )
            Log.i(
                TAG,
                "SINGLE_DAY_QP|from=${compiled.fromDate}|to=${compiled.toDate}|" +
                    "results=${response.totalGalleryMatches}|spec=${response.effectivePlanJson}",
            )
            assertEquals("2025-10-05", compiled.fromDate)
            assertEquals("2025-10-05", compiled.toDate)
        } finally {
            indexer.close()
        }
    }

    @Test
    fun strictSemanticThresholdAuditBypassesFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = GalleryDatabase(context)
        val semanticIndexer = GallerySemanticIndexer(context, database)
        try {
            val photos = database.mediaStoreIdsForMediaType(QueryMediaType.PHOTOS)
            val scopes = mapOf(
                QueryCategory.SCENARY to
                    database.mediaStoreIdsForQueryCategory(QueryCategory.SCENARY).intersect(photos),
                QueryCategory.DOC to
                    database.mediaStoreIdsForQueryCategory(QueryCategory.DOC).intersect(photos),
            )
            val cases = listOf(
                QueryCategory.SCENARY to "beach",
                QueryCategory.SCENARY to "sunset",
                QueryCategory.SCENARY to "car",
                QueryCategory.SCENARY to "swimming",
                QueryCategory.DOC to "receipt",
                QueryCategory.DOC to "restaurant bill",
            )

            cases.forEach { (category, query) ->
                val allowlist = requireNotNull(scopes[category])
                assertTrue("$category photo scope is empty", allowlist.isNotEmpty())
                val nearest = semanticIndexer.searchNearestScoredBlocking(
                    queries = listOf(query),
                    limit = STRICT_AUDIT_LIMIT,
                    allowlist = allowlist.toLongArray(),
                )
                val strict = semanticIndexer.searchScoredBlocking(
                    queries = listOf(query),
                    limit = STRICT_AUDIT_LIMIT,
                    allowlist = allowlist.toLongArray(),
                )
                val expectedStrict = nearest.filter {
                    GallerySemanticIndexer.isAcceptedSemanticScore(it.score)
                }
                Log.i(
                    TAG,
                    "STRICT_GATE|query=$query|category=${category.wireName}|" +
                        "scope=${allowlist.size}|top=${nearest.firstOrNull()?.scoreText() ?: "none"}|" +
                        "ge10=${nearest.count { it.score >= 0.10f }}|" +
                        "ge12=${nearest.count { it.score >= 0.12f }}|" +
                        "ge14=${nearest.count { it.score >= 0.14f }}|" +
                        "ge16=${nearest.count { it.score >= 0.16f }}|" +
                        "ge18=${nearest.count { it.score >= 0.18f }}|" +
                        "ge20=${nearest.count { it.score >= 0.20f }}|" +
                        "strict=${strict.size}|raw=" +
                        nearest.take(5).joinToString(",") { it.scoreText() },
                )
                assertTrue(
                    "Raw nearest-neighbor search returned nothing for '$query'",
                    nearest.isNotEmpty(),
                )
                assertEquals(
                    "The strict API did not apply the configured inclusive gate for '$query'",
                    expectedStrict,
                    strict,
                )
                assertTrue(
                    "A below-threshold match escaped strict retrieval for '$query'",
                    strict.all { GallerySemanticIndexer.isAcceptedSemanticScore(it.score) },
                )
            }
        } finally {
            semanticIndexer.close()
            database.close()
        }
    }

    @Test
    fun beachTypoQueryPublishesBrowsableResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        try {
            val latch = CountDownLatch(1)
            var searchResult: Result<SearchResponse>? = null
            indexer.searchAsync(
                query = "beach phootos",
                onProgress = {},
                onFinished = {
                    searchResult = it
                    latch.countDown()
                },
            )
            assertTrue(
                "Timed out running the misspelled beach query",
                latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val response = requireNotNull(searchResult).getOrThrow()
            Log.i(
                TAG,
                "BEACH_QP|category=${response.queryCategory.wireName}|" +
                    "results=${response.totalGalleryMatches}|shown=${response.gallery.size}|" +
                    "context=${response.answerContext?.items?.size ?: 0}|" +
                    "spec=${response.effectivePlanJson}",
            )
            assertEquals(QueryCategory.SCENARY, response.queryCategory)
            assertTrue(
                "The photo typo must be canonicalized to the structured MIME predicate",
                response.effectivePlanJson.contains("[mime type == photos]"),
            )
            assertTrue(
                "The misspelled MIME word must not leak into the executable spec",
                !response.effectivePlanJson.contains("phootos", ignoreCase = true),
            )
            assertTrue(
                "A valid semantic query must publish relevant gallery results",
                response.totalGalleryMatches > 0,
            )
            assertEquals(minOf(200, response.totalGalleryMatches), response.gallery.size)
            assertTrue(
                response.gallery.all {
                    QueryCategoryContextPolicy.accepts(QueryCategory.SCENARY, it)
                },
            )
        } finally {
            indexer.close()
        }
    }

    @Test
    fun undatedCarRepairQueryHasNoDatePredicates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        try {
            val latch = CountDownLatch(1)
            var searchResult: Result<SearchResponse>? = null
            indexer.searchAsync(
                query = "How much I spend on car repair",
                onProgress = {},
                onFinished = {
                    searchResult = it
                    latch.countDown()
                },
            )
            assertTrue(
                "Timed out planning undated car repair query",
                latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val response = requireNotNull(searchResult).getOrThrow()
            val spec = QueryExecutionSpec.parse(response.effectivePlanJson)
            val plan = ExecutionSpecCompiler.compile(spec)
            Log.i(
                TAG,
                "UNDATED_QP|category=${response.queryCategory.wireName}|" +
                    "from=${plan.fromDate}|to=${plan.toDate}|spec=${response.effectivePlanJson}",
            )
            assertEquals(QueryCategory.DOC, response.queryCategory)
            assertEquals("", plan.fromDate)
            assertEquals("", plan.toDate)
            assertTrue(plan.semanticQueries.isNotEmpty())
        } finally {
            indexer.close()
        }
    }

    @Test
    fun persistedCategoryIndexPartitionsGallery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val docCount = database.mediaStoreIdsForQueryCategory(QueryCategory.DOC).size
            val scenaryCount = database.mediaStoreIdsForQueryCategory(QueryCategory.SCENARY).size
            val totalCount = database.count()
            Log.i(
                TAG,
                "CATEGORY_INDEX|doc=$docCount|scenary=$scenaryCount|total=$totalCount",
            )
            assertTrue("The installed gallery index is empty", totalCount > 0)
            assertEquals(
                "OCR-derived doc/scenary classes must partition every indexed media row",
                totalCount,
                (docCount + scenaryCount).toLong(),
            )
        }
    }

    @Test
    fun locationEnrichmentStateIsAuditable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GalleryDatabase(context).use { database ->
            val db = database.readableDatabase
            val stateCounts = LinkedHashMap<Int, Int>()
            db.rawQuery(
                "SELECT location_enrichment_state, COUNT(*) FROM media_items " +
                    "GROUP BY location_enrichment_state ORDER BY location_enrichment_state",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) stateCounts[cursor.getInt(0)] = cursor.getInt(1)
            }
            fun count(where: String): Int = db.rawQuery(
                "SELECT COUNT(*) FROM media_items WHERE $where",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val raw = count("TRIM(COALESCE(location_raw, '')) <> ''")
            val named = count("TRIM(COALESCE(location_name, '')) <> ''")
            val cache = db.rawQuery(
                "SELECT COUNT(*) FROM location_cache",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val namedByMime = LinkedHashMap<String, Int>()
            db.rawQuery(
                "SELECT mime_type, COUNT(*) FROM media_items " +
                    "WHERE TRIM(COALESCE(location_name, '')) <> '' " +
                    "GROUP BY mime_type ORDER BY COUNT(*) DESC",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) namedByMime[cursor.getString(0)] = cursor.getInt(1)
            }
            Log.i(
                TAG,
                "LOCATION_INDEX|states=$stateCounts|raw=$raw|named=$named|cache=$cache|" +
                    "namedByMime=$namedByMime|" +
                    "pending=${database.pendingLocationMetadata().size}",
            )
            assertEquals(database.count(), stateCounts.values.sum().toLong())
        }
    }

    @Test
    fun structuredSearchMatrixPreservesHardScopesAndSetOperators() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = GalleryDatabase(context)
        val semanticIndexer = GallerySemanticIndexer(context, database)
        val executor = StructuredSearchExecutor(
            database = database,
            semanticIndexer = semanticIndexer,
            metadataReader = GalleryMetadataReader(context),
        )
        try {
            val photos = database.mediaStoreIdsForMediaType(QueryMediaType.PHOTOS)
            val hardCases = listOf(
                QueryCategory.SCENARY to
                    "[query_category == scenary] && [mime type == photos]",
                QueryCategory.DOC to
                    "[query_category == doc] && [mime type == photos]",
                QueryCategory.PERSON to
                    "[query_category == person] && [mime type == photos]",
                QueryCategory.LOCATION to
                    "[query_category == location] && [mime type == photos]",
                QueryCategory.TIME to
                    "[query_category == time] && [mime type == photos] SORT_DATE",
            )
            hardCases.forEach { (category, renderedSpec) ->
                val expected = database.mediaStoreIdsForQueryCategory(category).intersect(photos)
                val actual = executor.execute(QueryExecutionSpec.parse(renderedSpec))
                Log.i(
                    TAG,
                    "SEARCH_MATRIX|kind=hard|category=${category.wireName}|" +
                        "expected=${expected.size}|actual=${actual.size}",
                )
                assertEquals(
                    "Hard category/MIME scope lost or widened records for ${category.wireName}",
                    expected,
                    actual.mapTo(LinkedHashSet(), GalleryMedia::mediaStoreId),
                )
                if (category == QueryCategory.SCENARY ||
                    category == QueryCategory.DOC ||
                    category == QueryCategory.TIME
                ) {
                    assertTrue("$category photo scope is unexpectedly empty", actual.isNotEmpty())
                }
                if (category == QueryCategory.TIME) {
                    val timestamps = actual.map {
                        it.dateTakenMs ?: it.dateModifiedSeconds * 1_000L
                    }
                    assertEquals(
                        "SORT_DATE did not produce newest-first retrieval order",
                        timestamps.sortedDescending(),
                        timestamps,
                    )
                }
            }

            val beach = execute(
                executor,
                "[query_category == scenary] && [[mime type == photos] && [semantic == beach]]",
            )
            val sunset = execute(
                executor,
                "[query_category == scenary] && [[mime type == photos] && [semantic == sunset]]",
            )
            val beachOrSunset = execute(
                executor,
                "[query_category == scenary] && " +
                    "[[mime type == photos] && [[semantic == beach], [semantic == sunset]]]",
            )
            val beachWithoutGlasses = execute(
                executor,
                "[query_category == scenary] && " +
                    "[[mime type == photos] && [[semantic == beach] - [semantic == glasses]]]",
            )
            val receipt = execute(
                executor,
                "[query_category == doc] && [[mime type == photos] && [semantic == receipt]]",
            )
            val indexedPlace = database.readableDatabase.rawQuery(
                """
                SELECT location_name
                FROM media_items
                WHERE mime_type LIKE 'image/%'
                  AND TRIM(COALESCE(location_name, '')) <> ''
                GROUP BY location_name
                ORDER BY COUNT(*) DESC
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
            val placeSpec = QueryExecutionSpec(
                ExecutionNode.Binary(
                    ExecutionNode.Predicate(ExecutionField.QUERY_CATEGORY, "location"),
                    ExecutionBinaryOperator.INTERSECT,
                    ExecutionNode.Predicate(ExecutionField.LOCATION, indexedPlace),
                ),
            )
            val placeMatches = executor.execute(placeSpec)
            val locationsSorted = executor.execute(
                QueryExecutionSpec.parse(
                    "[query_category == location] && [mime type == photos] SORT_LOC",
                ),
            )

            assertTrue("Simple beach search returned no records", beach.isNotEmpty())
            assertTrue("Simple sunset search returned no records", sunset.isNotEmpty())
            assertTrue("Simple receipt search returned no records", receipt.isNotEmpty())
            assertTrue("An indexed readable place returned no location matches", placeMatches.isNotEmpty())
            assertTrue(
                "Resolved place predicate escaped the requested indexed place",
                placeMatches.all {
                    it.locationName.orEmpty().contains(indexedPlace, ignoreCase = true)
                },
            )
            val locationKeys = locationsSorted.map {
                (it.locationName ?: it.location).orEmpty().lowercase()
            }
            assertEquals(
                "SORT_LOC did not order resolved place names",
                locationKeys.sorted(),
                locationKeys,
            )
            assertTrue(
                "Semantic union lost the complete beach branch",
                beach.keys.all(beachOrSunset.keys::contains),
            )
            assertTrue(
                "Semantic union lost the complete sunset branch",
                sunset.keys.all(beachOrSunset.keys::contains),
            )
            assertTrue(
                "Semantic subtraction introduced records outside its positive branch",
                beachWithoutGlasses.keys.all(beach.keys::contains),
            )
            Log.i(
                TAG,
                "SEARCH_MATRIX|kind=semantic|beach=${beach.size}|sunset=${sunset.size}|" +
                    "union=${beachOrSunset.size}|beachWithoutGlasses=${beachWithoutGlasses.size}|" +
                    "receipt=${receipt.size}|place=${placeMatches.size}|" +
                    "locationSorted=${locationsSorted.size}",
            )
        } finally {
            semanticIndexer.close()
            database.close()
        }
    }

    @Test
    fun broadScenaryQueryExercisesPopulatedPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        try {
            val latch = CountDownLatch(1)
            var searchResult: Result<SearchResponse>? = null
            indexer.searchAsync(
                query = "Show photos",
                onProgress = {},
                onFinished = {
                    searchResult = it
                    latch.countDown()
                },
            )
            assertTrue(
                "Timed out running broad scenery smoke query",
                latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            val response = requireNotNull(searchResult).getOrThrow()
            val contextCount = response.answerContext?.items?.size ?: 0
            Log.i(
                TAG,
                "PICKER_SMOKE|category=${response.queryCategory.wireName}|" +
                    "results=${response.totalGalleryMatches}|shown=${response.gallery.size}|" +
                    "context=$contextCount|spec=${response.effectivePlanJson}",
            )
            assertEquals(QueryCategory.SCENARY, response.queryCategory)
            assertTrue("Broad scenery search returned no indexed photos", response.totalGalleryMatches > 0)
            assertEquals(minOf(200, response.totalGalleryMatches), response.gallery.size)
            assertTrue("Context Picker returned no visual records", contextCount in 1..8)
            assertTrue(response.answerContext?.includeVisuals == true)
            assertTrue(
                response.gallery.all {
                    QueryCategoryContextPolicy.accepts(QueryCategory.SCENARY, it)
                },
            )
        } finally {
            indexer.close()
        }
    }

    @Test
    fun tenComplexQueriesRespectCategoryAndContextContracts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val indexer = GalleryIndexer(context)
        val completed = ArrayList<AuditResult>()
        val requestedOrdinals = InstrumentationRegistry.getArguments()
            .getString("qpCases")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()
        val selectedCases = CASES.withIndex().filter {
            requestedOrdinals.isEmpty() || it.index + 1 in requestedOrdinals
        }
        try {
            selectedCases.forEach { indexedCase ->
                val index = indexedCase.index
                val case = indexedCase.value
                val latch = CountDownLatch(1)
                var searchResult: Result<SearchResponse>? = null
                indexer.searchAsync(
                    query = case.query,
                    onProgress = {},
                    onFinished = {
                        searchResult = it
                        latch.countDown()
                    },
                )
                assertTrue(
                    "Timed out planning query ${index + 1}: ${case.query}",
                    latch.await(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                val queryResult = requireNotNull(searchResult)
                val response = queryResult.getOrNull()
                if (response == null) {
                    val audit = AuditResult.failure(
                        ordinal = index + 1,
                        expected = case.expected,
                        query = case.query,
                        error = queryResult.exceptionOrNull()?.stackTraceToString().orEmpty(),
                    )
                    completed += audit
                    Log.e(TAG, audit.toLogLine())
                    return@forEach
                }
                val invalidResults = response.gallery.count {
                    !QueryCategoryContextPolicy.accepts(response.queryCategory, it)
                }
                val invalidContext = response.answerContext?.items.orEmpty().count {
                    !QueryCategoryContextPolicy.accepts(response.queryCategory, it.media)
                }
                val audit = AuditResult(
                    ordinal = index + 1,
                    expected = case.expected,
                    actual = response.queryCategory,
                    resultCount = response.totalGalleryMatches,
                    shownCount = response.gallery.size,
                    contextCount = response.answerContext?.items?.size ?: 0,
                    visuals = response.answerContext?.includeVisuals ?: false,
                    invalidResults = invalidResults,
                    invalidContext = invalidContext,
                    planningMs = response.timings.queryPlanningMs,
                    spec = response.effectivePlanJson,
                    query = case.query,
                )
                completed += audit
                Log.i(TAG, audit.toLogLine())
            }
        } finally {
            indexer.close()
        }

        assertEquals(selectedCases.size, completed.size)
        completed.forEach { audit ->
            assertTrue(
                "Query ${audit.ordinal} failed: ${audit.error}",
                audit.error == null,
            )
            assertEquals(
                "Unexpected category for query ${audit.ordinal}: ${audit.query}",
                audit.expected,
                audit.actual,
            )
            assertEquals("Unscoped search results for query ${audit.ordinal}", 0, audit.invalidResults)
            assertEquals("Unscoped context for query ${audit.ordinal}", 0, audit.invalidContext)
            val expectsVisuals =
                audit.actual == QueryCategory.DOC || audit.actual == QueryCategory.SCENARY
            assertEquals(expectsVisuals, audit.visuals)
            assertTrue("Context exceeded 8 for query ${audit.ordinal}", audit.contextCount <= 8)
        }
    }

    private data class AuditCase(
        val query: String,
        val expected: QueryCategory,
    )

    private data class AuditResult(
        val ordinal: Int,
        val expected: QueryCategory,
        val actual: QueryCategory?,
        val resultCount: Int,
        val shownCount: Int,
        val contextCount: Int,
        val visuals: Boolean?,
        val invalidResults: Int,
        val invalidContext: Int,
        val planningMs: Long,
        val spec: String,
        val query: String,
        val error: String? = null,
    ) {
        fun toLogLine(): String = listOf(
            "n=$ordinal",
            "expected=${expected.wireName}",
            "actual=${actual?.wireName ?: "ERROR"}",
            "results=$resultCount",
            "shown=$shownCount",
            "context=$contextCount",
            "visuals=$visuals",
            "invalidResults=$invalidResults",
            "invalidContext=$invalidContext",
            "planningMs=$planningMs",
            "query=${query.replace('|', ' ')}",
            "spec=${spec.replace('|', ' ').replace(Regex("\\s+"), " ").trim()}",
            "error=${error.orEmpty().lineSequence().firstOrNull().orEmpty().replace('|', ' ')}",
        ).joinToString("|")

        companion object {
            fun failure(
                ordinal: Int,
                expected: QueryCategory,
                query: String,
                error: String,
            ) = AuditResult(
                ordinal = ordinal,
                expected = expected,
                actual = null,
                resultCount = 0,
                shownCount = 0,
                contextCount = 0,
                visuals = null,
                invalidResults = 0,
                invalidContext = 0,
                planningMs = 0,
                spec = "",
                query = query,
                error = error,
            )
        }
    }

    private companion object {
        const val TAG = "QP10"
        const val QUERY_TIMEOUT_SECONDS = 180L
        const val STRICT_AUDIT_LIMIT = 512

        fun SemanticMatch.scoreText(): String =
            String.format(Locale.US, "%.6f", score)

        fun execute(
            executor: StructuredSearchExecutor,
            renderedSpec: String,
        ): LinkedHashMap<Long, GalleryMedia> =
            executor.execute(QueryExecutionSpec.parse(renderedSpec))
                .associateByTo(LinkedHashMap(), GalleryMedia::mediaStoreId)

        val CASES = listOf(
            AuditCase(
                "How much did I spend on food today from my screenshots?",
                QueryCategory.DOC,
            ),
            AuditCase(
                "What was the restaurant name on the bill from last week?",
                QueryCategory.DOC,
            ),
            AuditCase(
                "Show Ravi swimming in Goa last summer without glasses.",
                QueryCategory.SCENARY,
            ),
            AuditCase(
                "Who else was with Meghana during the latest team outing?",
                QueryCategory.PERSON,
            ),
            AuditCase(
                "Which people appeared with Ravi but not Ramani at the wedding?",
                QueryCategory.PERSON,
            ),
            AuditCase(
                "Where did I go with Ravi last month?",
                QueryCategory.LOCATION,
            ),
            AuditCase(
                "What all places did I visit during 2025 without Meghana?",
                QueryCategory.LOCATION,
            ),
            AuditCase(
                "When did Ravi go swimming in Goa?",
                QueryCategory.TIME,
            ),
            AuditCase(
                "When was the last birthday party with Meghana?",
                QueryCategory.TIME,
            ),
            AuditCase(
                "Show videos of mountain trekking at sunset, excluding Ravi.",
                QueryCategory.SCENARY,
            ),
        )
    }
}
