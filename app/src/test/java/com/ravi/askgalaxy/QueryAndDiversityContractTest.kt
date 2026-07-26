package com.ravi.askgalaxy

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QueryAndDiversityContractTest {
    @Test
    fun formal_execution_spec_round_trips_requested_person_subtraction() {
        val raw =
            "[query_category == scenary] && [[person == ravi] && [mime type == photos]] - [semantic == glasses]"

        val spec = QueryExecutionSpec.parse(raw)

        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        val compiled = ExecutionSpecCompiler.compile(spec)
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
    fun planner_category_is_required_once_and_canonicalized_to_outer_intersection() {
        val valid = QueryExecutionSpec.parse(
            "[query_category == doc] && [[semantic == food receipt total] && [mime type == photos]]",
        )

        assertEquals(QueryCategory.DOC, valid.requiredQueryCategory())
        val compiled = ExecutionSpecCompiler.compile(valid)
        assertTrue(compiled.answerEvidenceScope.needsOcr)
        assertFalse(compiled.answerEvidenceScope.needsVisual)

        assertInvalidPlannerSpec("[semantic == receipt]")
        val relocated = QueryExecutionSpec.parse(
            "[semantic == receipt], [query_category == doc]",
        )
        assertEquals(
            "[query_category == doc] && [semantic == receipt]",
            relocated.canonicalizeCategoryEnvelope().render(),
        )
    }

    @Test
    fun planner_category_remains_hard_scoped_when_subtraction_wraps_intersection() {
        val spec = QueryExecutionSpec.parse(
            "[[[query_category == scenary] && [semantic == swimming]] - [semantic == glasses]]",
        )

        assertEquals(QueryCategory.SCENARY, spec.requiredQueryCategory())
        assertEquals(QueryCategory.SCENARY, ExecutionSpecCompiler.compile(spec).queryCategory)
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
        assertEquals(4, QueryCategoryContextPolicy.answerImageLimit(QueryCategory.SCENARY))
        assertEquals(0, QueryCategoryContextPolicy.answerImageLimit(QueryCategory.DOC))
        assertEquals(512, QueryCategoryContextPolicy.SCENARY_ANSWER_IMAGE_MAX_DIMENSION)
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
    fun planner_prompt_requires_gemma_category_and_searchable_semantics() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction()

        assertTrue(prompt.contains("Gemma 4 E4B is the only plan author"))
        assertTrue(prompt.contains("[query_category == CATEGORY]"))
        assertTrue(prompt.contains("doc, scenary, person, location, or time"))
        assertTrue(prompt.contains("Explicit photo/picture/image wording requires"))
        assertTrue(prompt.contains("semantic is one compact conceptual phrase"))
        assertTrue(prompt.contains("OCR HYBRID FOR EVERY DOC QUERY"))
        assertTrue(prompt.contains("BROAD DOCUMENT EXPANSION"))
        assertTrue(prompt.contains("Never use spending, expenses"))
        assertTrue(prompt.contains("SELF PERSON"))
        assertTrue(prompt.contains("DOC PERSON NAMES"))
        assertTrue(prompt.contains("NEVER put `+`, `-`, `&&`"))
        assertTrue(prompt.contains("Never infer today"))
        assertTrue(prompt.contains("who is in these beach photos"))
        assertTrue(prompt.contains("where was the lighthouse photo taken"))
        assertTrue(prompt.contains("when did I visit Goa"))
        assertTrue(prompt.contains("odyssy movie ticket"))
        assertTrue(prompt.contains("how much did I spend last month"))
        assertTrue(prompt.contains("what is Ravi passport number"))
        assertTrue(prompt.contains("what is Ravi Aadhaar number"))
        assertTrue(prompt.contains("what is the Wi-Fi password"))
        assertTrue(prompt.contains("when does my driving licence expire"))
    }

    @Test
    fun broad_monthly_spending_plan_keeps_ocr_alternatives_inside_date_scope() {
        val raw =
            "[query_category == doc] && [[from_date == 2026-06-01] && " +
                "[to_date == 2026-06-30] && " +
                "[[semantic == purchase receipts bills invoices payment confirmations] + " +
                "[ocr == receipt bill invoice payment total amount]]]"

        val spec = QueryExecutionSpec.parse(raw)
        val plan = ExecutionSpecCompiler.compile(spec)

        assertEquals(spec, QueryExecutionSpec.parse(spec.render()))
        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertEquals("2026-06-01", plan.fromDate)
        assertEquals("2026-06-30", plan.toDate)
        assertEquals(
            listOf("purchase receipts bills invoices payment confirmations"),
            plan.semanticQueries,
        )
        assertEquals(
            listOf("receipt bill invoice payment total amount"),
            plan.ocrTerms,
        )
        assertTrue(plan.answerEvidenceScope.needsOcr)
        assertFalse(plan.answerEvidenceScope.needsVisual)
        QueryPlannerRuntime.validateSemanticValues(plan)
        QueryPlannerRuntime.validateOcrKeywords(plan)
        QueryStructuredIntentPolicy.validate(
            "How much did I spend last month?",
            emptyList(),
            plan,
        )
    }

    @Test
    fun ocr_keywords_use_or_recall_and_dual_branch_matches_get_fusion_boost() {
        val keywords = OcrKeywordPolicy.keywords("Ravi passport Ravi")

        assertEquals(listOf("ravi", "passport"), keywords)
        assertEquals(0.5f, OcrKeywordPolicy.score("PASSPORT", keywords))
        assertEquals(0.5f, OcrKeywordPolicy.score("Ravi", keywords))
        assertEquals(1.0f, OcrKeywordPolicy.score("Ravi Passport Number", keywords))

        val semanticOnly = RetrievalScoreFusion.merge(null, 0.42f, fused = true)
        val ocrOnly = RetrievalScoreFusion.merge(null, 0.5f, fused = true)
        val both = RetrievalScoreFusion.merge(semanticOnly, 0.5f, fused = true)
        assertTrue(both > semanticOnly)
        assertTrue(both > ocrOnly)
        assertEquals(0.97f, both, 0.0001f)
    }

    @Test
    fun undated_queries_reject_model_invented_date_predicates() {
        val today = LocalDate.now().toString()
        val inventedDatePlan = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [[from_date == $today] && " +
                    "[to_date == $today] && [semantic == car repair invoice total]]",
            ),
        )

        try {
            QueryDateConstraintPolicy.validate(
                "How much I spend on car repair",
                inventedDatePlan,
            )
            fail("Expected invented date predicates to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        QueryDateConstraintPolicy.validate(
            "How much did I spend on car repair today",
            inventedDatePlan,
        )
        assertFalse(
            QueryDateConstraintPolicy.hasExplicitTemporalConstraint(
                "How much I spend on car repair",
            ),
        )
        assertTrue(QueryDateConstraintPolicy.hasExplicitTemporalConstraint("car repair last summer"))
        assertTrue(QueryDateConstraintPolicy.hasExplicitTemporalConstraint("car repair three weeks ago"))
        assertTrue(QueryDateConstraintPolicy.hasExplicitTemporalConstraint("car repair on Monday"))
    }

    @Test
    fun one_exact_day_requires_equal_from_and_to_boundaries() {
        assertEquals(
            "2026-07-24" to "2026-07-24",
            QueryScopeParser.explicitDateBoundsFromQuery("photos taken on 24 July 2026"),
        )
        assertEquals(
            "2026-07-24" to "2026-07-24",
            QueryScopeParser.explicitDateBoundsFromQuery("photos taken on 24/07/2026"),
        )
        assertEquals(
            "2026-07-24",
            QueryDateConstraintPolicy.requiredSingleDay("photos taken on 24 July 2026"),
        )

        val missingToDate = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [[from_date == 2026-07-24] && " +
                    "[mime type == photos]]",
            ),
        )
        try {
            QueryDateConstraintPolicy.validate(
                "photos taken on 24 July 2026",
                missingToDate,
            )
            fail("Expected a one-day plan without to_date to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: Gemma must repair this into a closed one-day interval.
        }

        val closedDay = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [[from_date == 2026-07-24] && " +
                    "[to_date == 2026-07-24] && [mime type == photos]]",
            ),
        )
        QueryDateConstraintPolicy.validate("photos taken on 24 July 2026", closedDay)

        assertEquals(
            null,
            QueryDateConstraintPolicy.requiredSingleDay("photos after 24 July 2026"),
        )
        val afterDay = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [[from_date == 2026-07-25] && " +
                    "[mime type == photos]]",
            ),
        )
        QueryDateConstraintPolicy.validate("photos after 24 July 2026", afterDay)
    }

    @Test
    fun category_validator_follows_requested_answer_over_prominent_nouns() {
        assertEquals(
            QueryCategory.PERSON,
            QueryCategoryConstraintPolicy.expectedCategory("Who is in these beach photos?"),
        )
        assertEquals(
            QueryCategory.LOCATION,
            QueryCategoryConstraintPolicy.expectedCategory(
                "Where was the sunset photo with the lighthouse taken?",
            ),
        )
        assertEquals(
            QueryCategory.TIME,
            QueryCategoryConstraintPolicy.expectedCategory("When did I visit Goa?"),
        )
        assertEquals(
            QueryCategory.TIME,
            QueryCategoryConstraintPolicy.expectedCategory(
                "On which dates did we visit national parks last year?",
            ),
        )
        assertEquals(
            QueryCategory.DOC,
            QueryCategoryConstraintPolicy.expectedCategory(
                "When does my driving licence expire?",
            ),
        )
        listOf(
            "What is Ravi passport number?",
            "What is Ravi DL number?",
            "What is Ravi driving license number?",
            "What is Ravi SSN?",
            "What is Ravi social security number?",
            "What is Ravi PAN card number?",
            "What is Ravi Aadhaar card number?",
            "What is Ravi Aadhar number?",
            "What is Ravi Aadhard number?",
            "What is Ravi ID number?",
            "What is the WiFi password?",
            "What is Ravi user ID?",
            "What is Ravi DOB?",
            "What is Ravi age?",
            "What are Ravi exam marks?",
        ).forEach { query ->
            assertEquals(
                "Expected doc for '$query'",
                QueryCategory.DOC,
                QueryCategoryConstraintPolicy.expectedCategory(query),
            )
        }
        assertEquals(
            QueryCategory.SCENARY,
            QueryCategoryConstraintPolicy.expectedCategory(
                "What did I eat on my trip to Barcelona?",
            ),
        )
    }

    @Test
    fun self_pronouns_resolve_only_when_they_refer_to_person_presence() {
        assertTrue(SelfPersonQueryPolicy.referencesSelfAsPerson("Show photos of me at the beach"))
        assertTrue(SelfPersonQueryPolicy.referencesSelfAsPerson("Who was with me at dinner?"))
        assertTrue(SelfPersonQueryPolicy.referencesSelfAsPerson("Where did I go last month?"))
        assertTrue(SelfPersonQueryPolicy.referencesSelfAsPerson("What was I wearing?"))
        assertTrue(SelfPersonQueryPolicy.referencesSelfAsPerson("Show my birthday photos"))
        assertFalse(SelfPersonQueryPolicy.referencesSelfAsPerson("What is my passport number?"))
        assertFalse(SelfPersonQueryPolicy.referencesSelfAsPerson("How much did I spend last month?"))

        val presencePlan = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [[mime type == photos] && " +
                    "[person == Ravi] && [semantic == beach]]",
            ),
        )
        QueryPlannerRuntime.validateCompiledPlan(
            "Show photos of me at the beach",
            listOf("Ravi"),
            presencePlan,
            selfPersonLabel = "Ravi",
        )

        val documentPlan = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [[semantic == passport identity document] + " +
                    "[ocr == Ravi passport]]",
            ),
        )
        QueryPlannerRuntime.validateCompiledPlan(
            "What is my passport number?",
            listOf("Ravi"),
            documentPlan,
            selfPersonLabel = "Ravi",
        )
    }

    @Test
    fun named_document_subject_stays_in_ocr_semantic_instead_of_face_scope() {
        val correct = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [[semantic == passport identity document] + " +
                    "[ocr == Ravi passport]]",
            ),
        )
        QueryStructuredIntentPolicy.validate(
            "What is Ravi passport number?",
            listOf("Ravi"),
            correct,
        )

        val faceScoped = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [[person == Ravi] && " +
                    "[[semantic == passport identity document] + [ocr == Ravi passport]]]",
            ),
        )
        try {
            QueryStructuredIntentPolicy.validate(
                "What is Ravi passport number?",
                listOf("Ravi"),
                faceScoped,
            )
            fail("Expected a printed document owner to be rejected as a face scope")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun date_validator_requires_full_ranges_and_open_boundaries() {
        assertEquals(
            "2025-01-01" to "2025-12-31",
            QueryScopeParser.explicitDateBoundsFromQuery("places visited last year"),
        )
        assertEquals(
            "2025-06-01" to "2025-08-31",
            QueryScopeParser.explicitDateBoundsFromQuery(
                "bills photographed between June and August 2025",
            ),
        )
        assertEquals(
            "2025-07-01" to "",
            QueryScopeParser.explicitDateBoundsFromQuery("sunset after June 2025"),
        )

        val anniversaryOnly = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == location] && [[from_date == 2025-07-25] && " +
                    "[to_date == 2025-07-25]] SORT_LOC",
            ),
        )
        try {
            QueryDateConstraintPolicy.validate("What places did I visit last year?", anniversaryOnly)
            fail("Expected an anniversary-only plan to be rejected for last year")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun structured_intent_validator_preserves_media_people_and_single_places() {
        assertEquals(
            QueryMediaType.PHOTOS,
            QueryStructuredIntentPolicy.expectedMediaType("beach phootos"),
        )
        assertEquals(
            QueryMediaType.VIDEOS,
            QueryStructuredIntentPolicy.expectedMediaType("fireworks vidoes"),
        )
        assertEquals(
            listOf("Barcelona"),
            QueryStructuredIntentPolicy.explicitLocationCandidates(
                "What did I eat on my trip to Barcelona?",
            ),
        )
        assertEquals(
            listOf("Goa"),
            QueryStructuredIntentPolicy.explicitLocationCandidates(
                "Which people joined both the Goa trip and the mountain trek?",
            ),
        )
        assertEquals(
            listOf("Hyderabad"),
            QueryStructuredIntentPolicy.explicitLocationCandidates(
                "On which day did the team meet at the Hyderabad offsite?",
            ),
        )
        assertTrue(
            QueryStructuredIntentPolicy.explicitLocationCandidates(
                "What places did I visit last year?",
            ).isEmpty(),
        )

        val complete = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == person] && [[person == Meghana] && " +
                    "[location == Bengaluru]] - [person == Ravi]",
            ),
        )
        QueryStructuredIntentPolicy.validate(
            "Who appears with Meghna in Bengaluru without Ravi?",
            listOf("Ravi", "Meghana", "Ramani"),
            complete,
        )
        assertTrue(
            QueryStructuredIntentPolicy.isMetadataOnlyIntent(
                "Who appears most often with Ramani?",
            ),
        )
        assertTrue(
            QueryStructuredIntentPolicy.isMetadataOnlyIntent(
                "Which cities did I visit with Meghana but without Ravi?",
            ),
        )
        assertFalse(
            QueryStructuredIntentPolicy.isMetadataOnlyIntent(
                "Who else was with Meghana at the team outing?",
            ),
        )

        val relationalSemantic = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == person] && [[person == Meghana] && " +
                    "[location == Bengaluru] && [semantic == appears with]] - " +
                    "[person == Ravi]",
            ),
        )
        try {
            QueryStructuredIntentPolicy.validate(
                "Who appears with Meghna in Bengaluru without Ravi?",
                listOf("Ravi", "Meghana", "Ramani"),
                relationalSemantic,
            )
            fail("Expected a metadata-only relation with a semantic filter to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val missingSubtraction = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == location] && [semantic == airport layovers]",
            ),
        )
        try {
            QueryStructuredIntentPolicy.validate(
                "Which places did I visit excluding airport layovers?",
                emptyList(),
                missingSubtraction,
            )
            fail("Expected an explicit exclusion without subtraction to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val invertedAnchor = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == person] && " +
                    "[semantic == team outing] - [person == Meghana]",
            ),
        )
        try {
            QueryStructuredIntentPolicy.validate(
                "Who else was with Meghana at the team outing?",
                listOf("Meghana"),
                invertedAnchor,
            )
            fail("Expected a named positive person anchor in subtraction to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val missingEvent = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == person] && [person == Meghana]",
            ),
        )
        try {
            QueryStructuredIntentPolicy.validate(
                "Who else was with Meghana at the team outing?",
                listOf("Meghana"),
                missingEvent,
            )
            fail("Expected an explicitly named event without semantic retrieval to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun planner_prompt_covers_residual_who_where_and_exact_day_contracts() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction()

        assertTrue(prompt.contains("who else was with Meghana at the team outing"))
        assertTrue(
            prompt.contains(
                "who appears most often with Ramani => " +
                    "[query_category == person] && [person == Ramani]",
            ),
        )
        assertTrue(
            prompt.contains(
                "which people joined both the Goa trip and the mountain trek => " +
                    "[query_category == person] && " +
                    "[[location == Goa] && [semantic == mountain trek]]",
            ),
        )
        assertTrue(
            prompt.contains(
                "[[from_date == 2025-10-05] && [to_date == 2025-10-05] && " +
                    "[semantic == dinner]]",
            ),
        )
        assertTrue(
            prompt.contains(
                "where did we camp during the latest mountain trip => " +
                    "[query_category == location] && [semantic == mountain campsite] SORT_DATE",
            ),
        )
        assertTrue(
            prompt.contains(
                "where did my Bengaluru-to-Goa road trip stop for lunch => " +
                    "[query_category == location] && " +
                    "[semantic == lunch stop on Bengaluru-to-Goa road trip]",
            ),
        )
        assertTrue(
            prompt.contains(
                "excluding restaurant screenshots => [query_category == person] && " +
                    "[[[person == Ravi] && [semantic == birthday celebration]] - " +
                    "[semantic == restaurant screenshot]]",
            ),
        )

        val groupedExclusion = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == person] && " +
                    "[[[person == Ravi] && [semantic == birthday celebration]] - " +
                    "[semantic == restaurant screenshot]]",
            ),
        )
        QueryStructuredIntentPolicy.validate(
            "Who else was present across Ravi's whole birthday celebration, " +
                "excluding restaurant screenshots?",
            listOf("Ravi"),
            groupedExclusion,
        )
        assertEquals(listOf("restaurant screenshot"), groupedExclusion.negativeSemanticQueries)
    }

    @Test
    fun sort_validator_requires_time_and_plural_location_ordering() {
        val unsortedTime = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == time] && [semantic == birthday party]",
            ),
        )
        try {
            QuerySortConstraintPolicy.validate("When was the birthday party?", unsortedTime)
            fail("Expected a time plan without SORT_DATE to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val sortedPlaces = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == location] && [semantic == national park] SORT_LOC",
            ),
        )
        QuerySortConstraintPolicy.validate(
            "Which national parks have I visited?",
            sortedPlaces,
        )

        val incorrectlySortedRoute = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == location] && " +
                    "[semantic == lunch stop on Bengaluru-to-Goa road trip] SORT_LOC",
            ),
        )
        try {
            QuerySortConstraintPolicy.validate(
                "Where did my Bengaluru-to-Goa road trip stop for lunch?",
                incorrectlySortedRoute,
            )
            fail("Expected unrequested SORT_LOC to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun movie_ticket_remains_semantic_content_instead_of_mime() {
        val plan = ExecutionSpecCompiler.compile(
            QueryExecutionSpec.parse(
                "[query_category == doc] && [[semantic == Odyssey movie ticket price] + " +
                    "[ocr == Odyssey ticket price total]]",
            ),
        )

        QueryPlannerRuntime.validateSemanticValues(plan)
        QueryPlannerRuntime.validateOcrKeywords(plan)
        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertEquals(null, plan.mediaType)
        assertEquals(listOf("Odyssey movie ticket price"), plan.semanticQueries)
        assertEquals(listOf("Odyssey ticket price total"), plan.ocrTerms)
        assertEquals("", plan.fromDate)
        assertEquals("", plan.toDate)
    }

    @Test
    fun semantic_vector_cutoff_is_inclusive_at_point_one() {
        assertFalse(GallerySemanticIndexer.isAcceptedSemanticScore(0.0999f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.10f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.20f))
        assertTrue(GallerySemanticIndexer.isAcceptedSemanticScore(0.81f))
        assertFalse(GallerySemanticIndexer.isAcceptedSemanticScore(Float.NaN))
    }

    @Test
    fun semantic_fallback_preserves_strict_results_and_only_fills_positive_empty_searches() {
        val strict = listOf(SemanticMatch(1L, 0.31f))
        val nearest = listOf(
            SemanticMatch(2L, 0.19f),
            SemanticMatch(3L, 0.17f),
            SemanticMatch(2L, 0.16f),
        )

        assertEquals(
            strict,
            SemanticFallbackPolicy.select(
                strictMatches = strict,
                nearestMatches = nearest,
                hasMetadataMatches = false,
                allowFallback = true,
                limit = 200,
            ),
        )
        assertEquals(
            listOf(2L, 3L),
            SemanticFallbackPolicy.select(
                strictMatches = emptyList(),
                nearestMatches = nearest,
                hasMetadataMatches = false,
                allowFallback = true,
                limit = 200,
            ).map { it.mediaStoreId },
        )
        assertTrue(
            SemanticFallbackPolicy.select(
                strictMatches = emptyList(),
                nearestMatches = nearest,
                hasMetadataMatches = false,
                allowFallback = false,
                limit = 200,
            ).isEmpty(),
        )
        assertTrue(
            SemanticFallbackPolicy.select(
                strictMatches = emptyList(),
                nearestMatches = nearest,
                hasMetadataMatches = true,
                allowFallback = true,
                limit = 200,
            ).isEmpty(),
        )
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
            queryCategory = QueryCategory.SCENARY,
            maxRecords = 4,
        )

        assertEquals(4, context.items.size)
        val selectedIds = context.records.map { it.mediaStoreId }.toSet()
        assertTrue(groups.count { group -> group.matchedMediaStoreIds.any(selectedIds::contains) } >= 3)
        assertTrue(AnswerMetadataField.LOCATION in context.metadataFields)
        assertTrue(context.items.any { AnswerCoverageFacet.EPISODE in it.coverage })
    }

    @Test
    fun indexed_ocr_strictly_separates_document_and_scenary_media() {
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
        var embeddingDiversityCalls = 0
        val picker = AnswerContextPicker { values, maxCount ->
            embeddingDiversityCalls += 1
            values.take(maxCount)
        }

        val docContext = picker.pick(
            query = "how much did I spend",
            rankedCandidates = candidates,
            evidenceGroups = emptyList(),
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
        )
        assertEquals(0, embeddingDiversityCalls)
        val scenaryContext = picker.pick(
            query = "show the trip",
            rankedCandidates = candidates,
            evidenceGroups = emptyList(),
            evidenceScope = QueryCategory.SCENARY.answerEvidenceScope(),
            queryCategory = QueryCategory.SCENARY,
        )
        assertTrue(embeddingDiversityCalls > 0)

        assertEquals(listOf(2L, 3L), docContext.records.map { it.mediaStoreId })
        assertEquals(listOf(1L, 4L), scenaryContext.records.map { it.mediaStoreId })
        assertFalse(docContext.includeVisuals)
        assertTrue(scenaryContext.includeVisuals)
        assertEquals(2, docContext.eligibleCandidateCount)
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
    fun document_context_prioritizes_exact_ocr_amount_match_without_visual_selector() {
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
        var embeddingDiversityCalls = 0
        val picker = AnswerContextPicker { values, maxCount ->
            embeddingDiversityCalls += 1
            values.take(maxCount)
        }

        val context = picker.pick(
            query = "how much did I spend on the Odyssey movie ticket",
            rankedCandidates = listOf(visuallyRankedFirst, odysseyTicket),
            evidenceGroups = emptyList(),
            evidenceScope = QueryCategory.DOC.answerEvidenceScope(),
            queryCategory = QueryCategory.DOC,
            maxRecords = 2,
        )

        assertEquals(2L, context.records.first().mediaStoreId)
        assertFalse(context.includeVisuals)
        assertEquals(0, embeddingDiversityCalls)
    }

    @Test
    fun metadata_categories_require_their_field_and_skip_visual_diversity() {
        val plain = media(1)
        val person = media(2).copy(personLabel = "Ravi")
        val location = media(3).copy(locationName = "Goa")
        val captured = media(4).copy(dateTakenMs = 1_700_000_000_000L)
        var visualSelectorCalls = 0
        val picker = AnswerContextPicker { values, maxCount ->
            visualSelectorCalls += 1
            values.take(maxCount)
        }

        val personContext = picker.pick(
            query = "who was there",
            rankedCandidates = listOf(plain, person, location, captured),
            evidenceGroups = emptyList(),
            evidenceScope = QueryCategory.PERSON.answerEvidenceScope(),
            queryCategory = QueryCategory.PERSON,
        )

        assertEquals(listOf(2L), personContext.records.map { it.mediaStoreId })
        assertFalse(personContext.includeVisuals)
        assertEquals(0, visualSelectorCalls)
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
