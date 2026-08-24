package com.ravi.askgalaxy

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QueryAndDiversityContractTest {
    @Test
    fun incremental_index_selection_is_append_only() {
        val existing = linkedSetOf(11L, 12L, 13L)
        val discovered = listOf(10L, 11L, 12L, 14L, 14L, 15L)

        assertEquals(setOf(10L, 14L, 15L), IncrementalIndexPolicy.additions(discovered, existing))
        assertEquals(setOf(11L, 12L, 13L), existing)
        assertFalse(IncrementalIndexPolicy.shouldAdd(11L, existing))
        assertTrue(IncrementalIndexPolicy.shouldAdd(16L, existing))
    }

    @Test
    fun answer_output_guard_rejects_query_protocol_and_repeated_question() {
        assertTrue(
            AnswerOutputGuard.needsRetry(
                "QUERY: What is Ramani's location in the captured photos?",
                "What is Ramani's location in the captured photos?",
            ),
        )
        assertTrue(
            AnswerOutputGuard.needsRetry(
                "What is Ramani's location in the captured photos?",
                "What is Ramani's location in the captured photos?",
            ),
        )
        assertFalse(
            AnswerOutputGuard.needsRetry(
                "The matching photos were taken in Bengaluru.",
                "Where were the photos taken?",
            ),
        )
    }

    @Test
    fun event_photo_date_grounding_answers_from_matched_capture_days() {
        val eventDay = LocalDate.of(2025, 4, 3)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val answer = EventPhotoDateGrounding.constrainAnswer(
            "when is Ramani birthday?",
            "I could not confirm it.",
            listOf(media(1).copy(dateTakenMs = eventDay), media(2).copy(dateTakenMs = eventDay)),
        )

        assertEquals("The matching event photos were taken on 3 April 2025.", answer)
        assertFalse(EventPhotoDateGrounding.applies("what is Ramani passport number"))
    }

    @Test
    fun expression_parser_can_read_but_model_gate_rejects_a_missing_envelope() {
        val emitted =
            "[semantic == passport identity document] && " +
                "[keyword == {Ravi} && {passport}]"

        val parsed = QueryExecutionSpec.parse(emitted)

        assertEquals(null, parsed.queryCategoryOrNull())
        assertEquals(emitted, parsed.render())
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(parsed)
        }
    }

    @Test
    fun complete_model_authored_envelope_compiles_without_intent_derivation() {
        val onlyLastWeek = ModelAuthoredPlanStructure.compile(
            QueryExecutionSpec.parse(
                "[answer_needed == true] && [query_category == person] && " +
                    "[people_only == Ramani] && [mime type == photos] && " +
                    "[person == Ramani] && [from_date == 2026-08-10] && [to_date == 2026-08-16]",
            ),
        )

        assertTrue(onlyLastWeek.needsAnswer)
        assertEquals(QueryCategory.PERSON, onlyLastWeek.queryCategory)
        assertEquals(listOf("Ramani"), onlyLastWeek.personNames)
        assertEquals(listOf("Ramani"), onlyLastWeek.onlyPersonNames)
        assertEquals(QueryMediaType.PHOTOS, onlyLastWeek.mediaType)
        assertTrue(onlyLastWeek.timeHint.isBlank())
        assertEquals("2026-08-10", onlyLastWeek.fromDate)
        assertEquals("2026-08-16", onlyLastWeek.toDate)
    }

    @Test
    fun formal_execution_spec_round_trips_requested_person_subtraction() {
        val raw =
            "[query_category == scenary] && [answer_needed == false] && " +
                "[[person == ravi] && [mime type == photos]] - [semantic == glasses]"

        val spec = QueryExecutionSpec.parse(raw)

        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        val compiled = ModelAuthoredPlanStructure.compile(spec)
        assertEquals(QueryCategory.SCENARY, compiled.queryCategory)
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
    fun explicit_time_parser_does_not_promote_a_rank_number_to_a_clock() {
        assertEquals("", QueryScopeParser.explicitTimeHintFromQuery("top 3 photos"))
        assertEquals("last month", QueryScopeParser.explicitTimeHintFromQuery("last month electricity bill"))
    }

    @Test
    fun model_authored_category_and_answer_envelope_are_required() {
        val valid = QueryExecutionSpec.parse(
            "[query_category == doc] && [answer_needed == true] && " +
                "[semantic == food receipt total] && [mime type == photos]",
        )

        assertEquals(QueryCategory.DOC, valid.requiredQueryCategory())
        val compiled = ModelAuthoredPlanStructure.compile(valid)
        assertTrue(compiled.answerEvidenceScope.needsOcr)
        assertFalse(compiled.answerEvidenceScope.needsVisual)

        val compact = QueryExecutionSpec.parse("[semantic == receipt]")
        assertEquals(null, compact.queryCategoryOrNull())
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(compact)
        }
    }

    @Test
    fun planner_envelope_is_rejected_inside_subtraction() {
        val spec = QueryExecutionSpec.parse(
            "[[[query_category == scenary] && [answer_needed == false] && " +
                "[semantic == swimming]] - [semantic == glasses]]",
        )

        assertEquals(QueryCategory.SCENARY, spec.requiredQueryCategory())
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(spec)
        }
    }

    @Test
    fun query_categories_route_the_expected_answer_coverage() {
        assertTrue(QueryCategory.DOC.answerEvidenceScope().needsOcr)
        assertTrue(QueryCategory.PERSON.answerEvidenceScope().needsPeopleMetadata)
        assertTrue(QueryCategory.LOCATION.answerEvidenceScope().needsLocationMetadata)
        assertTrue(QueryCategory.TIME.answerEvidenceScope().needsTimeMetadata)
        assertTrue(QueryCategory.SCENARY.answerEvidenceScope().needsVisual)
        assertFalse(QueryCategory.DOC.answerEvidenceScope().needsVisual)
        assertFalse(QueryCategory.PERSON.answerEvidenceScope().needsVisual)
        assertFalse(QueryCategory.LOCATION.answerEvidenceScope().needsVisual)
        assertFalse(QueryCategory.TIME.answerEvidenceScope().needsVisual)
        assertEquals(8, QueryCategoryContextPolicy.answerImageLimit(QueryCategory.SCENARY))
        assertEquals(8, QueryCategoryContextPolicy.answerImageLimit(QueryCategory.DOC))
        assertTrue(QueryCategoryContextPolicy.includesVisuals(QueryCategory.DOC))
    }

    @Test
    fun mime_type_accepts_only_canonical_photos_or_videos() {
        QueryExecutionSpec.parse(
            "[query_category == scenary] && [mime type == photos]",
        )
        QueryExecutionSpec.parse(
            "[query_category == scenary] && [mime type == videos]",
        )

        assertInvalidExecutionSpec(
            "[query_category == doc] && [mime type == documents]",
        )
        assertInvalidExecutionSpec(
            "[query_category == scenary] && [mime type == images]",
        )
        assertEquals(null, QueryMediaType.fromToken("documents"))
    }

    @Test
    fun planner_dates_must_be_real_iso_dates() {
        QueryExecutionSpec.parse(
            "[query_category == time] && [from_date == 2026-07-24]",
        )

        assertInvalidExecutionSpec(
            "[query_category == time] && [from_date == 24-07-2026]",
        )
        assertInvalidExecutionSpec(
            "[query_category == time] && [to_date == 2026-02-30]",
        )
    }

    @Test
    fun planner_prompt_requires_complete_model_authored_plan_and_semantics() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction(QueryPlannerProtocol.V1)

        assertTrue(prompt.contains("only query planner"))
        assertTrue(prompt.contains("runtime does not infer, add, remove, or rewrite query intent"))
        assertTrue(prompt.contains("exactly one [query_category == value]"))
        assertTrue(prompt.contains("exactly one [answer_needed == true|false]"))
        assertTrue(prompt.contains("Do not emit ocr"))
        assertTrue(prompt.contains("Visual/gallery intent never uses keyword"))
        assertTrue(prompt.contains("Phone/file lifecycle words"))
        assertTrue(prompt.contains("Every predicate is exactly [field == value]"))
        assertTrue(prompt.contains("cycling in rain"))
        assertTrue(prompt.contains("BR Hills"))
        assertTrue(prompt.contains("Ravi passport number"))
        assertTrue(prompt.contains("[keyword == {Ravi} && {passport}]"))
        assertTrue(prompt.length < 8_000)
        assertTrue(prompt.lineSequence().count { it.startsWith("RESOLVED_QUERY:") } >= 2)
    }

    @Test
    fun broad_monthly_spending_plan_uses_model_authored_doc_route_without_legacy_ocr() {
        val raw =
            "[query_category == doc] && [answer_needed == true] && " +
                "[from_date == 2026-06-01] && [to_date == 2026-06-30] && " +
                "[semantic == purchase receipts bills invoices payment confirmations]"

        val spec = QueryExecutionSpec.parse(raw)
        val plan = ModelAuthoredPlanStructure.compile(spec)

        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertEquals("2026-06-01", plan.fromDate)
        assertEquals("2026-06-30", plan.toDate)
        assertEquals(
            listOf("purchase receipts bills invoices payment confirmations"),
            plan.semanticQueries,
        )
        assertTrue(plan.ocrTerms.isEmpty())
        assertTrue(plan.answerEvidenceScope.needsOcr)
        assertFalse(plan.answerEvidenceScope.needsVisual)
    }

    @Test
    fun same_record_keyword_coverage_and_compact_syntax_are_strict() {
        val keywords = OcrKeywordPolicy.keywords("Ravi passport Ravi")

        assertEquals(listOf("ravi", "passport"), keywords)
        assertEquals(0.5f, OcrKeywordPolicy.score("PASSPORT", keywords))
        assertEquals(0.5f, OcrKeywordPolicy.score("Ravi", keywords))
        assertEquals(1.0f, OcrKeywordPolicy.score("Ravi Passport Number", keywords))
        assertFalse(OcrKeywordPolicy.matchesAll("PASSPORT", keywords))
        assertTrue(OcrKeywordPolicy.matchesAll("Ravi Passport Number", keywords))

        val semanticOnly = RetrievalScoreFusion.merge(null, 0.42f, fused = true)
        val ocrOnly = RetrievalScoreFusion.merge(
            null,
            OcrKeywordPolicy.PERFECT_MATCH_SCORE,
            fused = true,
        )
        val both = RetrievalScoreFusion.merge(
            semanticOnly,
            OcrKeywordPolicy.PERFECT_MATCH_SCORE,
            fused = true,
        )
        assertTrue(ocrOnly > semanticOnly)
        assertTrue(both > semanticOnly)
        assertTrue(both > ocrOnly)
        assertEquals(2.47f, both, 0.0001f)

        val rendered = QueryExecutionSpec.parse(
            "[semantic == passport identity document] && " +
                "[keyword == {Ravi} && {passport}]",
        ).render()
        assertTrue(rendered.contains("[keyword == {Ravi} && {passport}]"))
        assertEquals(rendered, QueryExecutionSpec.parse(rendered).render())
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(
                QueryExecutionSpec.parse(
                    "[query_category == doc] && [answer_needed == true] && " +
                        "[semantic == passport identity document] && [ocr == Ravi passport]",
                ),
            )
        }
    }

    @Test
    fun planner_prompt_covers_uniform_output_visual_time_and_exclusion_contracts() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction(QueryPlannerProtocol.V1)

        assertTrue(prompt.contains("RESOLVED_QUERY: <the standalone"))
        assertTrue(prompt.contains("[from_date == 2023-08-22]"))
        assertTrue(prompt.contains("current_query=recent trip"))
        assertTrue(prompt.contains("Every without/excluding/except/but not/not/no clause"))

        val groupedExclusion = ModelAuthoredPlanStructure.compile(
            QueryExecutionSpec.parse(
                "[answer_needed == true] && [query_category == person] && " +
                    "[[[person == Ravi] && [semantic == birthday celebration] && " +
                    "[keyword == {birthday} && {celebration}]] - " +
                    "[semantic == restaurant screenshot]]",
            ),
        )
        assertEquals(listOf("restaurant screenshot"), groupedExclusion.negativeSemanticQueries)
    }

    @Test
    fun movie_ticket_remains_semantic_content_instead_of_mime() {
        val plan = ModelAuthoredPlanStructure.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [answer_needed == true] && " +
                    "[semantic == Odyssey movie ticket] && [keyword == {Odyssey} && {movie}]",
            ),
        )

        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertEquals(null, plan.mediaType)
        assertEquals(listOf("Odyssey movie ticket"), plan.semanticQueries)
        assertEquals(listOf("Odyssey movie"), plan.keywordTerms)
        assertTrue(plan.ocrTerms.isEmpty())
        assertEquals("", plan.fromDate)
        assertEquals("", plan.toDate)
    }

    @Test
    fun semantic_vector_cutoff_is_inclusive_at_point_ten() {
        assertFalse(GallerySemanticIndexer.isAcceptedSemanticScore(0.0999f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.10f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.126672f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.20f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.81f))
        assertFalse(GallerySemanticIndexer.isAcceptedSemanticScore(Float.NaN))
    }

    @Test
    fun blank_ocr_photos_do_not_require_keyword_and_but_text_bearing_records_do() {
        val blankPhoto = media(1).copy(ocrText = "")
        val textPhoto = media(2).copy(ocrText = "Ravi birthday")
        val blankVideo = media(3).copy(mimeType = "video/mp4", ocrText = "")

        assertEquals(
            setOf(1L),
            GalleryKeywordIntersectionPolicy.eligibleWithoutKeywordMatch(
                semanticCandidateIds = setOf(1L, 2L, 3L),
                records = listOf(blankPhoto, textPhoto, blankVideo),
            ),
        )
        assertTrue(
            GalleryKeywordIntersectionPolicy.eligibleWithoutKeywordMatch(
                semanticCandidateIds = setOf(2L),
                records = listOf(blankPhoto),
            ).isEmpty(),
        )
    }

    @Test
    fun private_semantic_gate_rejects_nearest_but_irrelevant_records() {
        assertFalse(DocumentSemanticAcceptancePolicy.accepts(0.3220f))
        assertFalse(DocumentSemanticAcceptancePolicy.accepts(0.4664f))
        assertFalse(DocumentSemanticAcceptancePolicy.accepts(0.6199f))
        assertTrue(DocumentSemanticAcceptancePolicy.accepts(0.62f))
        assertTrue(DocumentSemanticAcceptancePolicy.accepts(0.7390f))
        assertFalse(DocumentSemanticAcceptancePolicy.accepts(Float.NaN))
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
    fun answer_sanitizer_does_not_pad_a_direct_single_sentence_answer() {
        val cleaned = AnswerTextSanitizer.clean("The receipts show several car-repair charges.")

        assertEquals("The receipts show several car-repair charges.", cleaned)
        assertFalse(cleaned.contains("browse", ignoreCase = true))
    }

    @Test
    fun answer_sanitizer_discards_reasoning_and_prompt_echo_before_final_answer() {
        val cleaned = AnswerTextSanitizer.clean(
            """
            <think>I should cite G1 and repeat the task.</think>
            ANSWER_TASK:
            ANSWER: The ticket shows a cinema purchase in Bengaluru. It was captured on 2026-07-20.
            """.trimIndent(),
        )

        assertEquals(
            "The ticket shows a cinema purchase in Bengaluru. It was captured on 2026-07-20.",
            cleaned,
        )
        assertFalse(cleaned.contains("think", ignoreCase = true))
        assertFalse(cleaned.contains("ANSWER_TASK", ignoreCase = true))
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
        assertTrue(QuerySpellingMatcher.isPlausibleLocationCorrection("photos in Goaa", "Goa"))
    }

    @Test
    fun canonical_planner_output_is_the_formal_execution_language() {
        val longText = "semantic-" + "x".repeat(120)
        val plan = QueryPlan(
            semanticQueries = listOf(longText),
            keywordTerms = listOf("keyword"),
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
            mediaType = QueryMediaType.PHOTOS,
            answerEvidenceScope = AnswerEvidenceScope.all(),
        )

        val expression = QueryPlannerRuntime.effectivePlanJson(plan)

        assertTrue(expression.startsWith("["))
        assertTrue(expression.endsWith("SORT_DATE"))
        assertTrue(expression.contains('{'))
        assertTrue(expression.contains("&&"))
        assertFalse(expression.contains('\n'))
        assertEquals(expression, QueryExecutionSpec.parse(expression).render())
    }

    @Test
    fun context_picker_preserves_public_rank_order_across_episodes() {
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
        val picker = AnswerContextPicker()

        val context = picker.pick(
            query = "photos from different outings",
            rankedCandidates = candidates,
            evidenceScope = AnswerEvidenceScope.infer("photos from different outings"),
            queryCategory = QueryCategory.SCENARY,
            maxRecords = 4,
        )

        assertEquals(4, context.items.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), context.records.map { it.mediaStoreId })
        assertTrue(AnswerMetadataField.LOCATION in context.metadataFields)
        assertTrue(context.items.all { AnswerCoverageFacet.RELEVANCE in it.coverage })
    }

    @Test
    fun answer_evidence_is_exactly_the_displayed_first_eight_without_private_reranking() {
        val displayed = (1L..16L).map { HybridSearchResult.Gallery(media(it)) }
        val response = SearchResponse(
            gallery = displayed.map { (it as HybridSearchResult.Gallery).media },
            mergedResults = displayed,
        )

        val answerEvidence = AnswerEvidencePolicy.displayedTopEight(response)

        assertEquals(
            (1L..8L).toList(),
            answerEvidence.map { (it as HybridSearchResult.Gallery).media.mediaStoreId },
        )
    }

    @Test
    fun answer_context_preserves_the_already_filtered_public_rank() {
        val screenshotWithoutOcr = media(1).copy(displayName = "Screenshot_20260724.png")
        val photographedReceipt = media(2).copy(
            displayName = "IMG_2002.jpg",
            ocrText = "Restaurant total amount 1540 rupees paid today",
            contentClass = MediaContentClass.DOC,
        )
        val sceneWithOneOcrToken = media(3).copy(
            displayName = "IMG_2003.jpg",
            ocrText = "Goa",
            contentClass = MediaContentClass.DOC,
        )
        val videoScene = media(4).copy(
            displayName = "VID_2004.mp4",
            mimeType = "video/mp4",
            ocrText = "title frame",
        )
        val candidates = listOf(
            screenshotWithoutOcr,
            photographedReceipt,
            sceneWithOneOcrToken,
            videoScene,
        )
        val picker = AnswerContextPicker()

        val docContext = picker.pick(
            query = "how much did I spend",
            rankedCandidates = candidates,
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
        )
        val scenaryContext = picker.pick(
            query = "show the trip",
            rankedCandidates = candidates,
            evidenceScope = QueryCategory.SCENARY.answerEvidenceScope(),
            queryCategory = QueryCategory.SCENARY,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), docContext.records.map { it.mediaStoreId })
        assertEquals(listOf(1L, 2L, 3L, 4L), scenaryContext.records.map { it.mediaStoreId })
        assertTrue(docContext.includeVisuals)
        assertTrue(scenaryContext.includeVisuals)
        assertEquals(4, docContext.eligibleCandidateCount)
        assertEquals(4, docContext.inputCandidateCount)
        assertEquals(
            MediaContentClass.DOC,
            MediaContentClass.fromIndexedOcr("image/jpeg", "x"),
        )
        assertEquals(
            MediaContentClass.SCENARY,
            MediaContentClass.fromIndexedOcr("image/jpeg", ""),
        )
        assertEquals(
            MediaContentClass.SCENARY,
            MediaContentClass.fromIndexedOcr("video/mp4", "title frame"),
        )
    }

    @Test
    fun document_context_keeps_exact_keyword_matches_in_public_order_with_visuals() {
        val visuallyRankedFirst = media(1).copy(
            displayName = "IMG_TV_OFFER.HEIC",
            contentClass = MediaContentClass.DOC,
            ocrText = "Samsung television employee offer dealer price 27900",
        )
        val odysseyTicket = media(2).copy(
            displayName = "Screenshot_BookMyShow.jpg",
            contentClass = MediaContentClass.DOC,
            ocrText = "Total Amount The Odyssey Ticket Price 792.04",
        )
        val picker = AnswerContextPicker()

        val context = picker.pick(
            query = "how much did I spend on the Odyssey movie ticket",
            rankedCandidates = listOf(visuallyRankedFirst, odysseyTicket),
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
            ocrKeywords = listOf("odyssey", "ticket"),
            maxRecords = 2,
        )

        assertEquals(2L, context.records.first().mediaStoreId)
        assertTrue(context.includeVisuals)
    }

    @Test
    fun document_context_does_not_secretly_rerank_same_record_keyword_matches() {
        val incidentalTicket = media(1).copy(
            ocrText = "Ravi Kumar\nBooking reference number ZX91\nLocalization: passport",
            contentClass = MediaContentClass.DOC,
        )
        val incidentalInsurance = media(2).copy(
            ocrText = "Ravi Kumar\nPolicy number AB22\nClassification: passport document",
            contentClass = MediaContentClass.DOC,
        )
        val oldPassport = media(3).copy(
            ocrText = "REPUBLIC OF INDIA\nRavi Kumar\nPassport No. OLD1234",
            contentClass = MediaContentClass.DOC,
        )
        val newPassport = media(4).copy(
            ocrText = "REPUBLIC OF INDIA\nRavi Kumar\nPassport No. NEW5678",
            contentClass = MediaContentClass.DOC,
        )
        val picker = AnswerContextPicker()

        val context = picker.pick(
            query = "Ravi passport number",
            rankedCandidates = listOf(incidentalTicket, incidentalInsurance, oldPassport, newPassport),
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
            ocrKeywords = listOf("ravi", "passport"),
            maxRecords = 2,
        )

        assertEquals(listOf(1L, 2L), context.records.map { it.mediaStoreId })
    }

    @Test
    fun document_context_never_fills_top_eight_with_semantic_only_records() {
        val odysseyOne = media(1).copy(
            ocrText = "ODYSSEY\nMovie ticket\nTotal Amount ₹354",
            contentClass = MediaContentClass.DOC,
        )
        val flightTicket = media(2).copy(
            ocrText = "Flight ticket\nTotal Amount ₹3400",
            contentClass = MediaContentClass.DOC,
        )
        val odysseyTwo = media(3).copy(
            ocrText = "ODYSSEY\nTicket booking\nGrand Total ₹354",
            contentClass = MediaContentClass.DOC,
        )
        val picker = AnswerContextPicker()

        val context = picker.pick(
            query = "How much was the Odyssey movie ticket?",
            rankedCandidates = listOf(odysseyOne, flightTicket, odysseyTwo),
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
            ocrKeywords = listOf("odyssey", "ticket"),
            maxRecords = 4,
        )

        assertEquals(listOf(1L, 3L), context.records.map { it.mediaStoreId })
        assertEquals(2, context.eligibleCandidateCount)
    }

    @Test
    fun document_follow_up_keeps_original_ocr_anchor_when_its_new_word_is_not_printed() {
        val odysseyTicket = media(1).copy(
            ocrText = "ODYSSEY\nMovie ticket\n12 May 2026\nTotal Amount ₹354",
            contentClass = MediaContentClass.DOC,
        )
        val unrelatedTicket = media(2).copy(
            ocrText = "Flight ticket\n12 May 2026\nTotal Amount ₹3400",
            contentClass = MediaContentClass.DOC,
        )
        val picker = AnswerContextPicker()

        val context = picker.pick(
            query = "What date is on it?",
            rankedCandidates = listOf(odysseyTicket, unrelatedTicket),
            evidenceScope = AnswerEvidenceScope.all(),
            queryCategory = QueryCategory.DOC,
            // The original Odyssey search, not the follow-up text, provides
            // this pronoun's document anchor.
            ocrKeywords = listOf("odyssey", "ticket"),
            maxRecords = 4,
        )

        assertEquals(listOf(1L), context.records.map { it.mediaStoreId })
    }

    @Test
    fun ocr_packer_matches_no_abbreviation_to_a_number_query() {
        val packed = OcrAnswerContextPacker.pack(
            query = "Ravi passport number",
            ocrText = """
                P<INDRAVI<<SANKAR
                15/03/2022
                INDIAN NEW5678
                Nationality
                Passport No.
            """.trimIndent(),
        )

        assertTrue(packed.proofLines.contains("INDIAN NEW5678"))
        assertTrue(packed.proofLines.contains("Passport No."))
    }

    @Test
    fun document_amount_grounding_rejects_an_unrelated_plausible_total() {
        val odysseyTicket = media(1).copy(
            ocrText = """
                ODYSSEY
                Movie Ticket
                Base Price: ₹ 300
                GST: ₹ 54
                Total Amount: ₹ 354
            """.trimIndent(),
            contentClass = MediaContentClass.DOC,
        )
        val unrelatedReceipt = media(2).copy(
            ocrText = """
                Flight booking
                Total Amount: ₹ 3400
            """.trimIndent(),
            contentClass = MediaContentClass.DOC,
        )
        val query = "How much did I spend on the Odyssey movie ticket?"

        val decision = DocumentAmountGrounding.analyse(query, listOf(odysseyTicket, unrelatedReceipt))

        assertEquals("₹ 354", decision.selected?.value)
        assertEquals(
            "The amount is ₹ 354.",
            DocumentAmountGrounding.constrainAnswer(
                query,
                "The Odyssey movie ticket cost ₹ 3400.",
                listOf(odysseyTicket, unrelatedReceipt),
            ),
        )
    }

    @Test
    fun document_amount_grounding_leaves_equally_credible_distinct_totals_for_review() {
        val first = media(1).copy(ocrText = "ODYSSEY\nTotal Amount: ₹ 354")
        val second = media(2).copy(ocrText = "ODYSSEY\nTotal Amount: ₹ 400")
        val query = "What was the Odyssey ticket total?"

        assertEquals(
            null,
            DocumentAmountGrounding.analyse(query, listOf(first, second)).selected,
        )
    }

    @Test
    fun answer_review_gate_skips_routine_scene_answers_but_reviews_direct_fields_and_ocr_conflicts() {
        val ordinaryScene = media(1).copy(ocrText = "")
        assertEquals(
            null,
            AnswerReviewGate.reason(
                query = "What is happening at the beach?",
                draft = "People are walking beside the sea.",
                records = listOf(ordinaryScene),
            ),
        )
        assertEquals(
            AnswerReviewGate.Reason.DIRECT_FIELD,
            AnswerReviewGate.reason(
                query = "What is the passport number?",
                draft = "The passport number is A1234567.",
                records = listOf(ordinaryScene.copy(ocrText = "Passport No. A1234567")),
            ),
        )
        assertEquals(
            AnswerReviewGate.Reason.CONFLICTING_OCR,
            AnswerReviewGate.reason(
                query = "Which date is shown?",
                draft = "The date is 12/03/2025.",
                records = listOf(
                    ordinaryScene.copy(ocrText = "Date 12/03/2025"),
                    media(2).copy(ocrText = "Date 14/03/2025"),
                ),
            ),
        )
    }

    @Test
    fun passport_expiry_requires_a_date_and_rejects_long_identifiers() {
        listOf(
            "What is Ravi passport expiry date?",
            "What is Ravi passport expiration date?",
            "Ravi passport exporty date",
            "Until when is Ravi passport valid?",
        ).forEach { query ->
            assertTrue(AnswerValueGrounding.isIdentityExpiryDateQuestion(query))
            assertTrue(AnswerValueGrounding.fieldLabelInstruction(query).contains("date only"))
            assertFalse(
                AnswerValueGrounding.matchesRequestedValueType(
                    query,
                    "Passport expiry date: 98765432101234567890",
                ),
            )
            assertTrue(
                AnswerValueGrounding.matchesRequestedValueType(
                    query,
                    "Passport expiry date: 15 March 2032",
                ),
            )
        }
        assertFalse(AnswerValueGrounding.isIdentityExpiryDateQuestion("What is Ravi passport number?"))
    }

    @Test
    fun public_result_window_preserves_executor_overall_relevance_order() {
        val semanticBestButOldest = media(3).copy(dateModifiedSeconds = 10)
        val middle = media(1).copy(dateModifiedSeconds = 30)
        val newestButLowestScore = media(2).copy(dateModifiedSeconds = 50)

        val shown = SearchResultPresentationPolicy.top(
            rankedCandidates = listOf(semanticBestButOldest, middle, newestButLowestScore),
            limit = 2,
        )

        assertEquals(listOf(3L, 1L), shown.map { it.mediaStoreId })
    }

    @Test
    fun metadata_answer_context_preserves_public_rank_and_reports_available_fields() {
        val plain = media(1)
        val person = media(2).copy(personLabel = "Ravi")
        val location = media(3).copy(locationName = "Goa")
        val captured = media(4).copy(dateTakenMs = 1_700_000_000_000L)
        val picker = AnswerContextPicker()

        val personContext = picker.pick(
            query = "who was there",
            rankedCandidates = listOf(plain, person, location, captured),
            evidenceScope = QueryCategory.PERSON.answerEvidenceScope(),
            queryCategory = QueryCategory.PERSON,
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), personContext.records.map { it.mediaStoreId })
        assertTrue(personContext.includeVisuals)
        assertTrue(AnswerMetadataField.PEOPLE in personContext.metadataFields)
        assertTrue(QueryCategoryContextPolicy.accepts(QueryCategory.LOCATION, location))
        assertFalse(QueryCategoryContextPolicy.accepts(QueryCategory.LOCATION, plain))
        assertTrue(QueryCategoryContextPolicy.accepts(QueryCategory.TIME, captured))
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
    fun travel_scope_uses_modal_episode_locality_and_keeps_every_outside_episode() {
        val selection = TravelLocationPolicy.select(
            listOf(
                TravelEpisodeCandidate("home-1", "Bengaluru, Karnataka, India", 12),
                TravelEpisodeCandidate("home-2", "Bengaluru, Karnataka, India", 3),
                TravelEpisodeCandidate("home-3", "Bengaluru Urban, Karnataka, India", 2),
                TravelEpisodeCandidate("goa", "Panaji, Goa, India", 30),
                TravelEpisodeCandidate("ooty", "Ooty, Tamil Nadu, India", 4),
                TravelEpisodeCandidate("unknown", null, 20),
            ),
        )

        assertEquals("bengaluru", selection.normalLocationKey)
        assertEquals(2, selection.normalEpisodeCount)
        assertEquals(5, selection.locatedEpisodeCount)
        assertEquals(setOf("goa", "ooty"), selection.travelEpisodeIds)
    }

    private fun assertInvalidExecutionSpec(raw: String) {
        try {
            QueryExecutionSpec.parse(raw)
            fail("Expected invalid execution spec: $raw")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun assertInvalidPlannerSpec(raw: String) {
        try {
            QueryExecutionSpec.parse(raw).requiredQueryCategory()
            fail("Expected invalid planner contract: $raw")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
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
        assertEquals(null, GalleryMetadataReader.parseCoordinates("GPS 0.000000, 0.000000"))
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
