package com.ravi.askgalaxy

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answer-only device audit.
 *
 * Every case constructs a canonical execution spec and candidate set without
 * invoking QueryPlannerRuntime. This keeps the accepted Gemma QP frozen while
 * exercising Context Picker -> board/metadata joins -> Gemma answer -> public
 * answer sanitizer on the phone's real indexed gallery.
 *
 * Logs contain aggregate measurements only. Person labels, location names,
 * OCR text, query-specific answers, and media IDs intentionally stay private.
 */
class AnswerGenerationDeviceAuditTest {
    @Test
    fun docAnswerIsOcrGroundedAndNatural() = withHarness { harness ->
        val query = "What written details appear in these receipts?"
        val spec = semanticPhotoSpec(QueryCategory.DOC, "receipt")
        val candidates = harness.executor.execute(spec)
        assertTrue("The document answer audit needs receipt candidates", candidates.isNotEmpty())

        val audit = harness.answer(query, QueryCategory.DOC, spec, candidates)
        assertTrue(audit.context.includeVisuals)
        assertTrue(audit.context.includeOcr)
        assertTrue(audit.context.images.all(QueryCategoryContextPolicy::isDocumentLike))
        assertAnswerContract(audit)
        assertTokenOverlap(
            answer = audit.result.text,
            context = audit.context.images.joinToString(" ") { it.ocrText },
            message = "The document answer did not use any meaningful OCR token",
        )
    }

    @Test
    fun sceneryAnswerUsesOnlyNonDocumentVisuals() = withHarness { harness ->
        val query = "What is happening in these beach photos?"
        val spec = semanticPhotoSpec(QueryCategory.SCENARY, "beach")
        val candidates = harness.executor.execute(spec)
        assertTrue("The scenery answer audit needs beach candidates", candidates.isNotEmpty())

        val audit = harness.answer(query, QueryCategory.SCENARY, spec, candidates)
        assertTrue(audit.context.includeVisuals)
        assertFalse(audit.context.includeOcr)
        assertTrue(audit.context.images.none(QueryCategoryContextPolicy::isDocumentLike))
        assertAnswerContract(audit)
    }

    @Test
    fun personAnswerUsesNamedMetadataWithoutVisualTiles() = withHarness { harness ->
        val person = harness.mostCommonPerson()
        val candidates = harness.mediaForPerson(person)
        assertTrue("The person answer audit needs named photos", candidates.isNotEmpty())
        val spec = structuredPhotoSpec(
            QueryCategory.PERSON,
            ExecutionNode.Predicate(ExecutionField.PERSON, person),
        )

        val audit = harness.answer(
            query = "Who appears in these photos?",
            category = QueryCategory.PERSON,
            spec = spec,
            candidates = candidates,
        )
        assertFalse(audit.context.includeVisuals)
        assertTrue(audit.context.images.all { !it.personLabel.isNullOrBlank() })
        assertAnswerContract(audit)
        assertContainsPrivateValue(
            audit.result.text,
            person,
            "The person answer did not name the selected local person tag",
        )
    }

    @Test
    fun locationAnswerExplicitlyNamesTheIndexedPlace() = withHarness { harness ->
        val location = harness.mostCommonLocation()
        val candidates = harness.mediaForExactColumn("location_name", location)
        assertTrue("The location answer audit needs geocoded photos", candidates.isNotEmpty())
        val spec = structuredPhotoSpec(
            QueryCategory.LOCATION,
            ExecutionNode.Predicate(ExecutionField.LOCATION, location),
        )

        val audit = harness.answer(
            query = "Where were these photos taken?",
            category = QueryCategory.LOCATION,
            spec = spec,
            candidates = candidates,
        )
        assertFalse(audit.context.includeVisuals)
        assertTrue(audit.context.images.all { !it.locationName.isNullOrBlank() })
        assertAnswerContract(audit)
        assertContainsPrivateValue(
            audit.result.text,
            location,
            "The location answer did not explicitly name its indexed place",
        )
    }

    @Test
    fun timeAnswerStatesTheSelectedCaptureDay() = withHarness { harness ->
        val day = harness.mostCommonCaptureDay()
        val candidates = harness.mediaForCaptureDay(day)
        assertTrue("The time answer audit needs dated photos", candidates.isNotEmpty())
        val spec = structuredPhotoSpec(
            QueryCategory.TIME,
            ExecutionNode.Binary(
                ExecutionNode.Predicate(ExecutionField.FROM_DATE, day),
                ExecutionBinaryOperator.INTERSECT,
                ExecutionNode.Predicate(ExecutionField.TO_DATE, day),
            ),
        )

        val audit = harness.answer(
            query = "When were these photos taken?",
            category = QueryCategory.TIME,
            spec = spec,
            candidates = candidates,
        )
        assertFalse(audit.context.includeVisuals)
        assertTrue(audit.context.images.all {
            it.dateTakenMs != null || it.dateModifiedSeconds > 0L
        })
        assertAnswerContract(audit)
        val dateTokens = day.split('-')
        assertTrue(
            "The time answer did not state the selected capture date",
            dateTokens.any { token -> token.length == 4 && audit.result.text.contains(token) } ||
                audit.result.text.contains(day),
        )
    }

    private fun assertAnswerContract(audit: AnswerAudit) {
        val answer = audit.result.text.trim()
        val sentenceCount = sentenceCount(answer)
        assertTrue("Answer is blank", answer.isNotBlank())
        assertTrue("Context Picker exceeded 8 records", audit.context.items.size in 1..8)
        val gallerySourceCount = audit.result.sources.count {
            it.type == AnswerSourceType.GALLERY_IMAGE
        }
        assertTrue("Answer exposed more than 8 gallery sources", gallerySourceCount in 1..8)
        assertTrue("Answer must be 2-3 sentences, got $sentenceCount", sentenceCount in 2..3)
        assertFalse(
            "Answer leaked a private source ID",
            Regex("(?i)(?<![\\p{L}\\p{N}])[GCEF]\\d+(?![\\p{L}\\p{N}])")
                .containsMatchIn(answer),
        )
        FORBIDDEN_PUBLIC_TEXT.forEach { forbidden ->
            assertFalse(
                "Answer leaked private or robotic text '$forbidden'",
                answer.contains(forbidden, ignoreCase = true),
            )
        }
        Log.i(
            TAG,
            "ANSWER_AUDIT|category=${audit.category.wireName}|" +
                "candidates=${audit.candidateCount}|context=${audit.context.items.size}|" +
                "sources=${audit.result.sources.size}|sentences=$sentenceCount|" +
                "chars=${answer.length}|generationMs=${audit.result.timings.answerGenerationMs}|" +
                "visuals=${audit.context.includeVisuals}|ocr=${audit.context.includeOcr}|grounded=true",
        )
    }

    private fun assertContainsPrivateValue(answer: String, value: String, message: String) {
        val usefulTokens = tokens(value)
        assertTrue(message, usefulTokens.any { answer.contains(it, ignoreCase = true) })
    }

    private fun assertTokenOverlap(answer: String, context: String, message: String) {
        val answerTokens = tokens(answer).filterNot { it in STOP_WORDS }.toSet()
        val contextTokens = tokens(context).filterNot { it in STOP_WORDS }.toSet()
        assertTrue(message, answerTokens.intersect(contextTokens).isNotEmpty())
    }

    private fun tokens(value: String): List<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 4 }

    private fun sentenceCount(value: String): Int {
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(value)
        var count = 0
        var start = iterator.first()
        while (true) {
            val end = iterator.next()
            if (end == BreakIterator.DONE) break
            if (value.substring(start, end).isNotBlank()) count += 1
            start = end
        }
        return count
    }

    private fun semanticPhotoSpec(category: QueryCategory, semantic: String): QueryExecutionSpec =
        structuredPhotoSpec(
            category,
            ExecutionNode.Predicate(ExecutionField.SEMANTIC, semantic),
        )

    private fun structuredPhotoSpec(
        category: QueryCategory,
        predicate: ExecutionNode,
    ): QueryExecutionSpec = QueryExecutionSpec(
        ExecutionNode.Binary(
            ExecutionNode.Predicate(ExecutionField.QUERY_CATEGORY, category.wireName),
            ExecutionBinaryOperator.INTERSECT,
            ExecutionNode.Binary(
                ExecutionNode.Predicate(ExecutionField.MIME_TYPE, "photos"),
                ExecutionBinaryOperator.INTERSECT,
                predicate,
            ),
        ),
    )

    private inline fun withHarness(block: (AnswerHarness) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val harness = AnswerHarness(context)
        try {
            assertTrue("Gemma 4 must be installed for answer audit", GemmaRuntime.isModelInstalled(context))
            block(harness)
        } finally {
            harness.close()
        }
    }

    private data class AnswerAudit(
        val category: QueryCategory,
        val candidateCount: Int,
        val context: AnswerContextBundle,
        val result: AnswerResult,
    )

    private class AnswerHarness(
        context: android.content.Context,
    ) : AutoCloseable {
        private val database = GalleryDatabase(context)
        private val semanticIndexer = GallerySemanticIndexer(context, database)
        private val metadataReader = GalleryMetadataReader(context)
        val executor = StructuredSearchExecutor(database, semanticIndexer, metadataReader)
        private val evidenceBuilder = EvidenceBuilder(database)
        private val contextPicker = AnswerContextPicker(semanticIndexer)
        private val galleryIndexer = GalleryIndexer(context)

        fun answer(
            query: String,
            category: QueryCategory,
            spec: QueryExecutionSpec,
            candidates: List<GalleryMedia>,
        ): AnswerAudit {
            val ranked = candidates
                .distinctBy { it.mediaStoreId }
                .sortedByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1_000L }
            val evidence = evidenceBuilder.build(query, ranked)
            val scope = category.answerEvidenceScope()
            val pickerInput = (evidence.representativeCandidates + ranked)
                .distinctBy { it.mediaStoreId }
            val context = contextPicker.pick(
                query = query,
                rankedCandidates = pickerInput,
                evidenceGroups = evidence.contextGroups,
                evidenceScope = scope,
                queryCategory = category,
                maxImages = 8,
            )
            assertEquals(category, context.queryCategory)
            assertTrue(
                "Context Picker returned a category-ineligible record",
                context.images.all { QueryCategoryContextPolicy.accepts(category, it) },
            )
            val response = SearchResponse(
                gallery = ranked.take(200),
                totalGalleryMatches = ranked.size,
                personalContext = emptyList(),
                answerGallery = context.images,
                answerContext = context,
                evidenceGroups = evidence.evidenceGroups.filter { group ->
                    context.items.any {
                        it.media.mediaStoreId == group.representative.mediaStoreId
                    }
                },
                evidenceGroupingMode = evidence.groupingMode,
                effectivePlanJson = spec.render(),
                queryCategory = category,
                answerEvidenceScope = scope,
            )
            // Match the production handoff exactly: the semantic text encoder
            // has finished retrieval/diversity work and must not remain
            // resident beside the substantially larger Gemma vision graph.
            SigLipTextEncoder.releaseResident()
            val latch = CountDownLatch(1)
            var callback: Result<AnswerResult>? = null
            galleryIndexer.answerAsync(query, response) {
                callback = it
                latch.countDown()
            }
            assertTrue(
                "Timed out waiting for the ${category.wireName} answer",
                latch.await(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
            return AnswerAudit(
                category = category,
                candidateCount = ranked.size,
                context = context,
                result = requireNotNull(callback).getOrThrow(),
            )
        }

        fun mostCommonPerson(): String = database.namedPersonLabelsForPlanning()
            .maxByOrNull { database.mediaStoreIdsForPersonLabels(listOf(it)).size }
            ?: error("No indexed person label is available")

        fun mostCommonLocation(): String = mostCommonValue("location_name")

        fun mediaForPerson(person: String): List<GalleryMedia> =
            database.findByMediaStoreIds(
                database.mediaStoreIdsForPersonLabels(listOf(person)).toLongArray(),
            ).filter { it.mimeType.startsWith("image/", ignoreCase = true) }
                .sortedByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1_000L }

        fun mediaForExactColumn(column: String, value: String): List<GalleryMedia> {
            require(column == "location_name")
            val ids = database.readableDatabase.rawQuery(
                "SELECT media_store_id FROM media_items " +
                    "WHERE mime_type LIKE 'image/%' AND $column = ? " +
                    "ORDER BY COALESCE(date_taken_ms, date_modified_seconds * 1000) DESC",
                arrayOf(value),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
            return database.findByMediaStoreIds(ids.toLongArray())
                .sortedByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1_000L }
        }

        fun mostCommonCaptureDay(): String = database.readableDatabase.rawQuery(
            """
            SELECT strftime('%Y-%m-%d', date_taken_ms / 1000, 'unixepoch', 'localtime') AS capture_day,
                   COUNT(*) AS item_count
            FROM media_items
            WHERE mime_type LIKE 'image/%' AND date_taken_ms IS NOT NULL
            GROUP BY capture_day
            HAVING capture_day IS NOT NULL
            ORDER BY item_count DESC, capture_day DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            check(cursor.moveToFirst()) { "No indexed image capture day is available" }
            cursor.getString(0)
        }

        fun mediaForCaptureDay(day: String): List<GalleryMedia> {
            val ids = database.readableDatabase.rawQuery(
                """
                SELECT media_store_id
                FROM media_items
                WHERE mime_type LIKE 'image/%'
                  AND strftime('%Y-%m-%d', date_taken_ms / 1000, 'unixepoch', 'localtime') = ?
                ORDER BY date_taken_ms DESC
                """.trimIndent(),
                arrayOf(day),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
            return database.findByMediaStoreIds(ids.toLongArray())
                .sortedByDescending { it.dateTakenMs ?: it.dateModifiedSeconds * 1_000L }
        }

        private fun mostCommonValue(column: String): String {
            require(column == "location_name")
            return database.readableDatabase.rawQuery(
                """
                SELECT $column, COUNT(*) AS item_count
                FROM media_items
                WHERE mime_type LIKE 'image/%'
                  AND $column IS NOT NULL
                  AND TRIM($column) <> ''
                GROUP BY $column
                ORDER BY item_count DESC
                LIMIT 1
                """.trimIndent(),
                emptyArray(),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "No indexed $column value is available" }
                cursor.getString(0)
            }
        }

        override fun close() {
            galleryIndexer.close()
            semanticIndexer.close()
            database.close()
        }
    }

    private companion object {
        const val TAG = "AskGalaxyAnswerAudit"
        const val ANSWER_TIMEOUT_SECONDS = 240L
        val FORBIDDEN_PUBLIC_TEXT = listOf(
            "provided evidence",
            "supplied records",
            "browse the matching moments",
            "newest matches are ready",
            "ANSWER_TASK",
            "EVIDENCE_BUILDER",
            "<think",
            "</think>",
            "query_category",
            "semantic_queries",
        )
        val STOP_WORDS = setOf(
            "about", "after", "appear", "appears", "been", "being", "contains",
            "could", "details", "from", "have", "into", "matching", "photo",
            "photos", "receipt", "receipts", "several", "shows", "taken", "that",
            "their", "these", "this", "those", "what", "when", "where", "which",
            "with", "written", "your",
        )
    }
}
