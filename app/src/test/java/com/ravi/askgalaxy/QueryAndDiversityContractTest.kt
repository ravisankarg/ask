package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryAndDiversityContractTest {
    @Test
    fun fallback_preserves_hard_location_media_sort_and_negation() {
        val plan = QueryPlan.fallback("last Goa trip photos without Ramani")

        assertEquals("Goa", plan.locationHint)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertTrue(plan.recentFirst)
        assertTrue(plan.negativeSemanticQueries.isNotEmpty())
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.INTERSECT && it.field == "location" })
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.SUBTRACT })
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.SORT })
    }

    @Test
    fun formal_execution_spec_round_trips_requested_person_subtraction() {
        val raw = "[[person == ravi] && [mime type == photos]] - [semantic == glasses]"

        val spec = QueryExecutionSpec.parse(raw)

        assertEquals(raw, spec.render())
        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        val compiled = ExecutionSpecCompiler.compile(spec, AnswerEvidenceScope.infer("Ravi without glasses"))
        assertEquals(listOf("ravi"), compiled.personNames)
        assertEquals(listOf("glasses"), compiled.negativeSemanticQueries)
        assertEquals(QueryMediaType.PHOTOS, compiled.mediaType)
    }

    @Test
    fun formal_execution_spec_preserves_c_style_precedence() {
        val spec = QueryExecutionSpec.parse(
            "[semantic == a], [semantic == b] && [semantic == c] + [semantic == d] - [semantic == e]",
        )
        val root = spec.root as ExecutionNode.Binary

        assertEquals(ExecutionBinaryOperator.UNION, root.operator)
        val intersection = root.right as ExecutionNode.Binary
        assertEquals(ExecutionBinaryOperator.INTERSECT, intersection.operator)
        assertEquals(
            ExecutionBinaryOperator.SUBTRACT,
            (intersection.right as ExecutionNode.Binary).operator,
        )
        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
    }

    @Test
    fun fallback_keeps_last_team_outing_as_an_event_not_a_location() {
        val plan = QueryPlan.fallback("Meghana photos from last team outing")

        assertEquals("", plan.locationHint)
        assertTrue(plan.recentFirst)
        assertTrue(plan.semanticQueries.any { it.contains("team outing", ignoreCase = true) })
        assertTrue(plan.executionSpecString().endsWith("SORT_DATE"))
    }

    @Test
    fun fallback_turns_visual_without_glasses_into_a_visual_subtraction() {
        val plan = QueryPlan.fallback("Ravi without glasses")

        assertEquals(listOf("glasses"), plan.negativeSemanticQueries)
        assertTrue(plan.answerEvidenceScope.needsVisual)
        assertFalse(plan.answerEvidenceScope.needsOcr)
    }

    @Test
    fun fallback_preserves_location_after_infix_negation() {
        val plan = QueryPlan.fallback("Ravi without glasses in Goa")

        assertEquals("Goa", plan.locationHint)
        assertEquals(listOf("glasses"), plan.negativeSemanticQueries)
        assertTrue(plan.semanticQueries.any { it.equals("Ravi", ignoreCase = true) })
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.INTERSECT && it.field == "location" })
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.SUBTRACT && it.field == "semantic" })
    }

    @Test
    fun fallback_adds_a_bounded_positive_universe_for_pure_subtraction() {
        val plan = QueryPlan.fallback("photos without Ramani")

        assertEquals(listOf("photo"), plan.semanticQueries)
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.ADD && it.field == "semantic" })
        assertTrue(plan.negativeSemanticQueries.isNotEmpty())
    }

    @Test
    fun fallback_keeps_electricity_bill_as_one_ocr_branch_or_semantic_branch() {
        val plan = QueryPlan.fallback("last month electricity bill")

        assertEquals(listOf("electricity bill"), plan.ocrTerms)
        assertEquals(listOf("electricity bill"), plan.semanticQueries)
        assertTrue(plan.operations().count { it.field == "ocr" } == 1)
        assertTrue(plan.operations().any { it.kind == QueryOperationKind.UNION && it.field == "ocr" })
        assertEquals("last month", plan.timeHint)
    }

    @Test
    fun explicit_time_parser_does_not_promote_a_rank_number_to_a_clock() {
        assertEquals("", QueryScopeParser.explicitTimeHintFromQuery("top 3 photos"))
        assertEquals("last month", QueryScopeParser.explicitTimeHintFromQuery("last month electricity bill"))
    }

    @Test
    fun diversity_keeps_relevance_anchor_and_splits_near_duplicates() {
        val candidates = listOf(
            media(1) to floatArrayOf(1f, 0f),
            media(2) to floatArrayOf(.995f, .1f),
            media(3) to floatArrayOf(0f, 1f),
        )

        val selected = DiverseEvidenceSelector.select(candidates, maxCount = 2)

        assertEquals(listOf(1L, 3L), selected.map { it.mediaStoreId })
    }

    @Test
    fun answer_sanitizer_removes_internal_grounding_language() {
        val cleaned = AnswerTextSanitizer.clean(
            "Based on the provided evidence, the gallery records show that Ravi was there.",
        )

        assertTrue(cleaned.contains("photos and details", ignoreCase = true))
        assertFalse(cleaned.contains("provided evidence", ignoreCase = true))
        assertFalse(cleaned.contains("gallery records", ignoreCase = true))
        assertFalse(cleaned.startsWith("Based on", ignoreCase = true))
    }

    @Test
    fun answer_sanitizer_removes_robotic_missing_evidence_phrases() {
        val cleaned = AnswerTextSanitizer.clean(
            "As your evidence is not there, evidence is like this: the outing was in Goa.",
        )

        assertFalse(cleaned.contains("as your evidence", ignoreCase = true))
        assertFalse(cleaned.contains("evidence is like this", ignoreCase = true))
        assertTrue(cleaned.contains("Goa"))
    }

    @Test
    fun answer_sanitizer_removes_private_source_ids_and_limits_summary_length() {
        val cleaned = AnswerTextSanitizer.clean(
            "Ravi was at the team outing in Goa [G1, G2]. Meghana was there too [C1]. " +
                "They spent the afternoon near the beach E1. This fourth sentence must not appear.",
        )

        assertFalse(Regex("(?i)\\b[GCEF]\\d+\\b").containsMatchIn(cleaned))
        assertTrue(cleaned.contains("Goa"))
        assertFalse(cleaned.contains("fourth sentence", ignoreCase = true))
    }

    @Test
    fun spelling_matcher_accepts_close_known_names_without_inventing_mentions() {
        assertTrue(QuerySpellingMatcher.isPlausibleCorrection("Rvai phots from Goa", "Ravi"))
        assertTrue(QuerySpellingMatcher.isPlausibleCorrection("photos of Meghnaa", "Meghana"))
        assertFalse(QuerySpellingMatcher.isPlausibleCorrection("vacation photos in Goa", "Ravi"))
        assertFalse(QuerySpellingMatcher.isPlausibleCorrection("photos of Ann", "Ana"))
        assertTrue(QuerySpellingMatcher.areClosePhrases("tem outng", "team outing"))
        assertTrue(QuerySpellingMatcher.looksLikeEventPhrase("team outing"))
        assertFalse(QuerySpellingMatcher.looksLikeEventPhrase("Goa"))
    }

    @Test
    fun canonical_planner_output_is_the_formal_execution_language() {
        val longText = "semantic-" + "x".repeat(120)
        val plan = QueryPlan(
            semanticQueries = listOf(longText),
            metadataQueries = listOf(longText),
            personNames = listOf(longText),
            ocrTerms = listOf(longText),
            excludedPersonNames = listOf("excluded-$longText"),
            excludedOcrTerms = listOf("excluded-$longText"),
            negativeSemanticQueries = listOf("negative-$longText"),
            assertions = listOf("assertion-$longText"),
            timeHint = longText,
            locationHint = longText,
            recentFirst = true,
            needsPersonalContext = true,
            mediaType = QueryMediaType.PHOTOS,
            answerEvidenceScope = AnswerEvidenceScope.all(),
        )

        val expression = QueryPlannerRuntime.effectivePlanJson(plan)

        assertTrue(expression.startsWith("["))
        assertTrue(expression.endsWith("SORT_DATE"))
        assertFalse(expression.contains('{'))
        assertFalse(expression.contains('\n'))
        assertEquals(expression, QueryExecutionSpec.parse(expression).render())
    }

    @Test
    fun context_picker_balances_episode_coverage_before_duplicate_views() {
        val candidates = (1L..12L).map { id ->
            media(id).copy(
                dateTakenMs = 1_700_000_000_000L + id * 10_000L,
                locationName = if (id <= 4L) "Goa" else if (id <= 8L) "Bengaluru" else "Mysuru",
            )
        }
        val groups = listOf(
            episode("e1", candidates.subList(0, 4)),
            episode("e2", candidates.subList(4, 8)),
            episode("e3", candidates.subList(8, 12)),
        )
        val picker = AnswerContextPicker { values, maxCount -> values.take(maxCount) }

        val context = picker.pick(
            query = "photos from different outings",
            rankedCandidates = candidates,
            evidenceGroups = groups,
            evidenceScope = AnswerEvidenceScope.infer("photos from different outings"),
            maxImages = 4,
        )

        assertEquals(4, context.items.size)
        val selectedIds = context.images.map { it.mediaStoreId }.toSet()
        assertTrue(groups.count { group -> group.matchedMediaStoreIds.any(selectedIds::contains) } >= 3)
        assertTrue(AnswerMetadataField.LOCATION in context.metadataFields)
        assertTrue(context.items.any { AnswerCoverageFacet.EPISODE in it.coverage })
    }

    @Test
    fun multimodal_diversity_rewards_new_location_ocr_and_timeline() {
        val first = media(1).copy(
            dateTakenMs = 1_700_000_000_000L,
            locationName = "Goa",
            ocrText = "team outing banner",
        )
        val duplicate = media(2).copy(
            dateTakenMs = 1_700_000_100_000L,
            locationName = "Goa",
            ocrText = "team outing banner",
        )
        val novel = media(3).copy(
            dateTakenMs = 1_500_000_000_000L,
            locationName = "Mysuru",
            ocrText = "conference schedule hall",
        )

        val selected = MultimodalDiversitySelector.select(
            listOf(first, duplicate, novel),
            longArrayOf(1, 2, 3),
            maxCount = 2,
        )

        assertEquals(listOf(1L, 3L), selected.map { it.mediaStoreId })
    }

    @Test
    fun episode_preprocessing_uses_time_place_and_anonymous_people() {
        val base = 1_700_000_000_000L
        fun seed(
            id: Long,
            hours: Long,
            place: String,
            people: Set<String>,
        ) = EpisodeMediaSeed(
            media(id).copy(
                dateTakenMs = base + hours * 60L * 60L * 1_000L,
                locationName = place,
            ),
            people,
        )

        val episodes = EpisodeIndexer.groupSeeds(
            listOf(
                seed(1, 0, "Goa", setOf("person-a")),
                seed(2, 2, "Goa", setOf("person-a")),
                seed(3, 3, "Delhi", setOf("person-b")),
                seed(4, 48, "Goa", setOf("person-a")),
            ),
        )

        assertEquals(2, episodes.size)
        assertEquals(listOf(1L, 2L, 4L), episodes.first().memberMediaStoreIds)
        assertEquals(listOf(3L), episodes.last().memberMediaStoreIds)
    }

    @Test
    fun gps_parser_accepts_display_and_iso_6709_metadata_forms() {
        assertEquals(
            12.345678 to 77.123456,
            GalleryMetadataReader.parseCoordinates("GPS 12.345678, 77.123456"),
        )
        assertEquals(
            12.345678 to 77.123456,
            GalleryMetadataReader.parseCoordinates("+12.345678+077.123456/"),
        )
        assertEquals(
            -33.9 to 151.2,
            GalleryMetadataReader.parseCoordinates("-33.900000+151.200000+25.0/"),
        )
        assertEquals(null, GalleryMetadataReader.parseCoordinates("+91.0+077.0/"))
    }

    private fun episode(id: String, members: List<GalleryMedia>): EvidenceGroup = EvidenceGroup(
        episodeId = id,
        representative = members.first(),
        memberCount = members.size,
        matchedMemberCount = members.size,
        matchedMediaStoreIds = members.mapTo(linkedSetOf()) { it.mediaStoreId },
        startTimeMs = members.first().dateTakenMs,
        endTimeMs = members.last().dateTakenMs,
        location = members.first().locationName,
    )

    private fun media(id: Long): GalleryMedia = GalleryMedia(
        mediaStoreId = id,
        contentUri = "content://media/$id",
        mimeType = "image/jpeg",
        displayName = "image-$id.jpg",
        dateModifiedSeconds = id,
        sizeBytes = 1L,
        width = 100,
        height = 100,
        durationMs = 0L,
    )
}
