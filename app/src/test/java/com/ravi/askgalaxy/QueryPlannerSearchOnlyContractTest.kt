package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class QueryPlannerSearchOnlyContractTest {
    @Test
    fun qp_validator_failure_exposes_exact_e2b_output_and_rejection() {
        val rawOutput = "PLAN: [person == Ramani] && [unsupported == Goa]"
        val rejection = "Unsupported QP field: unsupported"
        val error = RuntimeException(
            "search failed",
            QueryPlannerValidationException(
                rawPlannerOutput = rawOutput,
                validatorRejection = rejection,
                cause = IllegalArgumentException(rejection),
            ),
        )

        val parsed = QueryPlannerFailureDiagnostics.from(error)
        assertNotNull(parsed)
        val diagnostic = requireNotNull(parsed).render()

        assertTrue(diagnostic.contains(rawOutput))
        assertTrue(diagnostic.contains(rejection))
        assertTrue(diagnostic.contains("E2B QP output:"))
        assertTrue(diagnostic.contains("QP validator rejected:"))
    }

    @Test
    fun person_location_photo_queries_compile_to_metadata_scopes_only() {
        listOf("Ramani photo at Goa", "ramani photos at goa").forEach { query ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse(
                    "[person == Ramani] && [location == goa] && [mime type == photos] && " +
                        "[semantic == photo] && [keyword == {Ramani} && {Goa} && {was}]",
                ),
                query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
            )

            QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
            assertEquals(listOf("Ramani"), plan.personNames)
            assertTrue(plan.locationHint.equals("Goa", ignoreCase = true))
            assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
            assertTrue(plan.semanticQueries.isEmpty())
            assertTrue(plan.keywordTerms.isEmpty())
        }
    }

    @Test
    fun photos_without_me_canonicalizes_self_to_a_subtracted_face_label() {
        val query = "Ramani photos without me"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[person == Ramani] && [mime type == photos] && [keyword == {Ramani}]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(normalized)

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
        assertEquals(listOf("Ramani"), plan.personNames)
        assertEquals(listOf("Ravi"), plan.excludedPersonNames)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertTrue(plan.keywordTerms.isEmpty())
    }

    @Test
    fun misspelled_without_named_person_is_normalized_to_face_subtraction() {
        val query = QueryPlannerRuntime.normalizePlannerQuery(
            "Ramani photos at Goa withour Ravi",
        )
        assertEquals("Ramani photos at Goa without Ravi", query)
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[person == Ramani] && [location == Goa] && [mime type == photos]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )

        QueryPlannerRuntime.validateCompiledPlan(
            query,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
        assertEquals(listOf("Ramani"), plan.personNames)
        assertEquals(listOf("Ravi"), plan.excludedPersonNames)
        assertEquals("Goa", plan.locationHint)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertTrue(plan.semanticQueries.isEmpty())
        assertTrue(plan.keywordTerms.isEmpty())
    }

    @Test
    fun contextual_passport_follow_up_requires_e2b_to_make_the_subject_explicit() {
        val previous = PreviousQueryTurn("Ravi passport number", "L1234567")
        assertTrue(
            FollowUpQueryContextPolicy.shouldResolve(
                "When does passport expire?",
                previous.query,
            ),
        )
        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: When does Ravi passport expire?\n" +
                "PLAN: [semantic == passport identity document] && " +
                "[keyword == {Ravi} && {passport}]",
            currentQuery = "When does passport expire?",
            previousTurn = previous,
            knownPersonLabels = listOf("Ravi", "Ramani"),
        )
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(output.expression),
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(output.resolvedQuery),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(output.resolvedQuery),
        )

        assertEquals("When does Ravi passport expire?", output.resolvedQuery)
        assertTrue(plan.personNames.isEmpty())
        assertEquals(listOf("Ravi passport"), plan.keywordTerms)
        assertEquals(listOf("passport travel identity document"), plan.semanticQueries)
        assertFalse(
            FollowUpQueryContextPolicy.shouldResolve(
                currentQuery = "Ravi passport number",
                previousQuery = "Ramani passport number",
                knownPersonLabels = listOf("Ravi", "Ramani"),
            ),
        )
    }

    @Test
    fun flat_e2b_identity_plan_is_canonicalized_without_changing_values() {
        val flat = "person == Ravi && semantic == passport identity document && " +
            "keyword == {Ravi} && {passport}"
        val canonical = PlannerExpressionSyntaxPolicy.canonicalize(flat)

        assertEquals(
            "[person == Ravi] && [semantic == passport identity document] && " +
                "[keyword == {Ravi} && {passport}]",
            canonical,
        )
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(canonical),
            "Ravi passport number",
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategory.DOC,
            derivedAnswerNeeded = true,
        )
        QueryPlannerRuntime.validateCompiledPlan(
            "Ravi passport number",
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
        assertTrue(plan.personNames.isEmpty())
        assertEquals(listOf("Ravi passport"), plan.keywordTerms)
    }

    @Test
    fun malformed_visual_intersection_is_canonicalized_one_field_per_bracket() {
        assertEquals(
            "[person == Ramani] && [location == Goa]",
            PlannerExpressionSyntaxPolicy.canonicalize("[person == Ramani && location == Goa]"),
        )
        assertEquals(
            "[person == Ramani] && [semantic == dancing] && [time == morning]",
            PlannerExpressionSyntaxPolicy.canonicalize(
                "person == Ramani && semantic == dancing && time == morning",
            ),
        )
    }

    @Test
    fun independent_planner_output_uses_the_same_resolved_query_and_plan_contract() {
        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: Ramani dancing in the morning\n" +
                "PLAN: [person == Ramani] && [semantic == dancing] && [time == morning]",
            currentQuery = "Ramani dancing in the morning",
            previousTurn = null,
            knownPersonLabels = listOf("Ravi", "Ramani"),
        )

        assertEquals("Ramani dancing in the morning", output.resolvedQuery)
        assertEquals(
            "[person == Ramani] && [semantic == dancing] && [time == morning]",
            output.expression,
        )
    }

    @Test
    fun independent_my_photos_keeps_self_scope_even_if_e2b_drops_my_in_its_rewrite() {
        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: photos\nPLAN: [mime type == photos]",
            currentQuery = "my photos",
            previousTurn = null,
            knownPersonLabels = listOf("Ravi", "Ramani"),
            selfPersonLabel = "Ravi",
        )
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(output.expression),
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(output.resolvedQuery),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(output.resolvedQuery),
        )

        assertEquals("my photos", output.resolvedQuery)
        assertEquals(listOf("Ravi"), plan.personNames)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertTrue(plan.keywordTerms.isEmpty())
        QueryPlannerRuntime.validateCompiledPlan(
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
    }

    @Test
    fun short_document_field_follow_ups_inherit_the_previous_document() {
        val previous = PreviousQueryTurn(
            query = "my driving licence expiry",
            answer = "15 March 2032",
        )
        listOf(
            "What is the address?",
            "What is the expiry date?",
            "What is the number?",
        ).forEach { query ->
            assertTrue(
                FollowUpQueryContextPolicy.shouldResolve(
                    currentQuery = query,
                    previousQuery = previous.query,
                    knownPersonLabels = listOf("Ravi", "Ramani"),
                ),
            )
        }
        assertFalse(
            FollowUpQueryContextPolicy.shouldResolve(
                currentQuery = "Where is Goa?",
                previousQuery = previous.query,
                knownPersonLabels = listOf("Ravi", "Ramani"),
            ),
        )

        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: What is the address on my driving licence?\n" +
                "PLAN: [semantic == driving licence identity document] && " +
                "[keyword == {driving} && {licence}]",
            currentQuery = "What is the address?",
            previousTurn = previous,
            knownPersonLabels = listOf("Ravi", "Ramani"),
            selfPersonLabel = "Ravi",
        )
        assertEquals("What is the address on my driving licence?", output.resolvedQuery)
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(output.expression),
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(output.resolvedQuery),
            derivedAnswerNeeded = true,
        )

        QueryPlannerRuntime.validateCompiledPlan(
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
        assertEquals(listOf("Ravi driving licence"), plan.keywordTerms)
        assertEquals(listOf("driving licence government identity document"), plan.semanticQueries)
    }

    @Test
    fun expiry_follow_up_cannot_inherit_today_as_a_time_filter() {
        val current = "What is the expiry date?"
        val previous = PreviousQueryTurn(
            query = "Ravi driving licence number",
            answer = "KA01 12345",
        )
        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: What is Ravi driving licence expiry date?\n" +
                "PLAN: [semantic == driving licence identity document] && " +
                "[keyword == {Ravi} && {driving} && {licence}] && [time == today]",
            currentQuery = current,
            previousTurn = previous,
            knownPersonLabels = listOf("Ravi", "Ramani"),
            selfPersonLabel = "Ravi",
        )
        assertEquals("What is the expiry date on Ravi driving licence?", output.resolvedQuery)
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(output.expression),
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(output.resolvedQuery),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(output.resolvedQuery),
        )

        assertTrue(plan.timeHint.isBlank())
        QueryPlannerRuntime.validateCompiledPlan(
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            QueryPlannerRuntime.validateCompiledPlan(
                output.resolvedQuery,
                listOf("Ravi", "Ramani"),
                plan.copy(timeHint = "today"),
                "Ravi",
            )
        }
        assertTrue(error.message.orEmpty().contains("no explicit temporal constraint"))
    }

    @Test
    fun driving_licence_number_query_removes_answer_field_and_invented_today() {
        val query = "What is the driving licence number?"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[semantic == driving licence number] && " +
                    "[keyword == {driving} && {licence} && {number}] && [time == today]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
        assertEquals(listOf("driving licence government identity document"), plan.semanticQueries)
        assertEquals(listOf("driving licence"), plan.keywordTerms)
        assertTrue(plan.timeHint.isBlank())
    }

    @Test
    fun misspelled_driving_licence_issue_date_is_a_document_field_not_time() {
        val query = QueryPlannerRuntime.normalizePlannerQuery(
            "What is the issue date of driving licennce number?",
        )
        assertEquals("What is the issue date of driving licence number?", query)
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[semantic == driving licence issue date number] && " +
                    "[keyword == {driving} && {licence} && {issue} && {date} && {number}] && " +
                    "[time == today]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertEquals(listOf("driving licence government identity document"), plan.semanticQueries)
        assertEquals(listOf("driving licence"), plan.keywordTerms)
        assertTrue(plan.timeHint.isBlank())
    }

    @Test
    fun person_photo_at_place_keeps_sunset_as_visual_semantic() {
        val query = "Ramani photos at Goa sunset"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[person == Ramani] && [location == Goa] && [mime type == photos]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
        assertEquals(listOf("Ramani"), plan.personNames)
        assertEquals("Goa", plan.locationHint)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertEquals(listOf("sunset"), plan.semanticQueries)
        assertTrue(plan.keywordTerms.isEmpty())
    }

    @Test
    fun contextual_plan_only_output_keeps_a_valid_qp_instead_of_failing_format_validation() {
        val output = ContextualPlannerOutputPolicy.parse(
            output = "PLAN: [semantic == driving licence identity document] && " +
                "[keyword == {Ravi} && {driving} && {licence}]",
            currentQuery = "What is the address?",
            previousTurn = PreviousQueryTurn("Ravi driving licence number", "KA01 12345"),
            knownPersonLabels = listOf("Ravi", "Ramani"),
            selfPersonLabel = "Ravi",
        )
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(output.expression),
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(output.resolvedQuery),
            derivedAnswerNeeded = true,
        )

        assertEquals("What is the address on Ravi driving licence?", output.resolvedQuery)
        QueryPlannerRuntime.validateCompiledPlan(
            output.resolvedQuery,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
        assertEquals(listOf("Ravi driving licence"), plan.keywordTerms)
    }

    @Test
    fun self_driving_licence_expiry_wording_variants_compile_identically() {
        listOf(
            "my driving licence expiry",
            "my driving licence expire date",
            "my driving licence expiration date",
            "when does my driving licence expire",
        ).forEach { query ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse("[semantic == driving licence identity document]"),
                query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                query,
                listOf("Ravi", "Ramani"),
                plan,
                "Ravi",
            )
            assertEquals(QueryCategory.DOC, plan.queryCategory)
            assertEquals(listOf("driving licence government identity document"), plan.semanticQueries)
            assertEquals(listOf("Ravi driving licence"), plan.keywordTerms)
            assertTrue(plan.personNames.isEmpty())
        }
    }

    @Test
    fun named_visual_queries_use_structured_scope_and_semantic_without_keywords() {
        data class Case(
            val query: String,
            val emitted: String,
            val expectedLocation: String = "",
            val expectedSemantic: String = "",
            val expectedTime: String = "",
        )
        val cases = listOf(
            Case("Ramani at Goa", "[person == Ramani && location == Goa]", expectedLocation = "Goa"),
            Case("Ramani dancing", "person == Ramani && semantic == dancing", expectedSemantic = "dancing"),
            Case(
                "Ramani dancing in the morning",
                "person == Ramani && semantic == dancing && time == morning",
                expectedSemantic = "dancing",
                expectedTime = "morning",
            ),
        )

        cases.forEach { case ->
            val spec = QueryExecutionSpec.parse(
                PlannerExpressionSyntaxPolicy.canonicalize(case.emitted),
            )
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                spec,
                case.query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(case.query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(case.query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                case.query,
                listOf("Ravi", "Ramani"),
                plan,
                "Ravi",
            )
            assertEquals(listOf("Ramani"), plan.personNames)
            assertEquals(case.expectedLocation, plan.locationHint)
            assertEquals(listOfNotNull(case.expectedSemantic.takeIf(String::isNotBlank)), plan.semanticQueries)
            assertEquals(case.expectedTime, plan.timeHint)
            assertTrue(plan.keywordTerms.isEmpty())
        }
    }

    @Test
    fun basic_named_identity_queries_remove_face_scope_and_rebuild_stable_keywords() {
        data class Case(
            val query: String,
            val emitted: String,
            val expectedSemantic: String,
            val expectedKeywords: String,
        )
        val cases = listOf(
            Case(
                "ravi passport number",
                "[person == Ravi] && [semantic == passport number] && " +
                    "[keyword == {Ravi} && {passport} && {number}]",
                "passport travel identity document",
                "Ravi passport",
            ),
            Case(
                "ravi aadhar number",
                "[person == Ravi] && [semantic == aadhar number]",
                "aadhaar government identity card document",
                "Ravi aadhaar",
            ),
            Case(
                "ravi ssn number",
                "[person == Ravi] && [semantic == ssn number]",
                "social security card identity document",
                "Ravi ssn",
            ),
        )

        cases.forEach { case ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse(case.emitted),
                case.query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(case.query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(case.query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                case.query,
                listOf("Ravi", "Ramani"),
                plan,
                "Ravi",
            )
            assertEquals(QueryCategory.DOC, plan.queryCategory)
            assertTrue(plan.needsAnswer)
            assertTrue(plan.personNames.isEmpty())
            assertEquals(listOf(case.expectedSemantic), plan.semanticQueries)
            assertEquals(listOf(case.expectedKeywords), plan.keywordTerms)
            assertFalse(plan.keywordTerms.single().contains("number", ignoreCase = true))
        }
    }

    @Test
    fun document_queries_never_bypass_keywords_but_visual_queries_can() {
        assertFalse(GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(QueryCategory.DOC))
        assertTrue(GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(QueryCategory.SCENARY))
        assertTrue(GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(QueryCategory.PERSON))
        assertTrue(GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(QueryCategory.LOCATION))
        assertTrue(GalleryKeywordIntersectionPolicy.allowsOcrlessPhotoBypass(QueryCategory.TIME))
    }

    @Test
    fun machine_config_and_source_files_are_never_search_eligible() {
        listOf(
            "layout.xml",
            "data.json",
            "config.yaml",
            "config.yml",
            "page.html",
            "Main.kt",
            "Main.java",
            "app.js",
            "app.ts",
            "schema.sql",
            "settings.ini",
            "app.properties",
            "runtime.log",
        ).forEach { name ->
            assertFalse("$name must be excluded", PersonalFileSearchPolicy.isEligibleName(name))
        }
        listOf(
            "passport.pdf",
            "licence.docx",
            "letter.odt",
            "notes.txt",
            "readme.md",
            "expenses.csv",
            "letter.rtf",
        ).forEach { name ->
            assertTrue("$name must remain searchable", PersonalFileSearchPolicy.isEligibleName(name))
        }
    }

    @Test
    fun identity_document_fallback_supports_other_and_untagged_user_names() {
        data class Case(
            val query: String,
            val knownPeople: List<String>,
            val selfPerson: String?,
            val expectedKeywords: String,
        )
        val cases = listOf(
            Case("meera passport number", listOf("Meera"), "Meera", "Meera passport"),
            Case("what is Asha Menon aadhar number", emptyList(), null, "Asha Menon aadhaar"),
            Case("what is my passport number", listOf("Ravi"), "Ravi", "Ravi passport"),
        )

        cases.forEach { case ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse("[person == wrong] && [semantic == identity number]"),
                case.query,
                case.knownPeople,
                case.selfPerson,
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategory.DOC,
                derivedAnswerNeeded = true,
            )

            QueryPlannerRuntime.validateCompiledPlan(
                case.query,
                case.knownPeople,
                plan,
                case.selfPerson,
            )
            assertTrue(plan.personNames.isEmpty())
            assertEquals(listOf(case.expectedKeywords), plan.keywordTerms)
        }
    }

    @Test
    fun keyword_scaffolding_is_removed_and_cannot_reach_retrieval() {
        val query = "When was Ravi birthday party and where does it happen?"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[person == Ravi] && [semantic == birthday party] && " +
                    "[keyword == {when} && {was} && {birthday} && {party} && {does}]",
            ),
            query,
            listOf("Ravi"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
        )

        assertTrue(plan.keywordTerms.isEmpty())
        assertEquals(listOf("birthday", "party"), OcrKeywordPolicy.keywords("does birthday was party"))
        assertTrue(
            plan.keywordTerms.flatMap(OcrKeywordPolicy::keywords).none {
                it in SearchKeywordPolicy.forbiddenScaffoldingWords
            },
        )
    }

    @Test
    fun file_lifecycle_language_never_becomes_semantic_keyword_or_self_face_scope() {
        data class Case(
            val query: String,
            val emitted: String,
            val expectedSemantic: List<String> = emptyList(),
            val expectedPeople: List<String> = emptyList(),
            val expectedMedia: QueryMediaType? = null,
            val expectsTime: Boolean = false,
        )
        val cases = listOf(
            Case(
                query = "what I saved last week",
                emitted = "[semantic == saved] && [keyword == {saved}] && [time == last week]",
                expectsTime = true,
            ),
            Case(
                query = "I downloaded",
                emitted = "[semantic == downloaded] && [keyword == {downloaded}]",
                expectedSemantic = listOf("all"),
            ),
            Case(
                query = "beach photos I downloaded and edited",
                emitted = "[semantic == beach downloaded edited] && " +
                    "[keyword == {beach} && {downloaded} && {edited}] && [mime type == photos]",
                expectedSemantic = listOf("beach"),
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            Case(
                query = "I took screenshots last week",
                emitted = "[semantic == took screenshots] && [keyword == {took} && {screenshots}] && " +
                    "[time == last week]",
                expectsTime = true,
            ),
            Case(
                query = "received from Ramani",
                emitted = "[person == Ramani] && [semantic == received from Ramani] && " +
                    "[keyword == {received} && {Ramani}]",
                expectedPeople = listOf("Ramani"),
            ),
        )

        cases.forEach { case ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse(case.emitted),
                case.query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(case.query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(case.query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                case.query,
                listOf("Ravi", "Ramani"),
                plan,
                "Ravi",
            )
            assertEquals("semantic for ${case.query}", case.expectedSemantic, plan.semanticQueries)
            assertEquals("people for ${case.query}", case.expectedPeople, plan.personNames)
            assertEquals("media for ${case.query}", case.expectedMedia, plan.mediaType)
            assertTrue("keywords for ${case.query}", plan.keywordTerms.isEmpty())
            assertFalse("self face for ${case.query}", plan.personNames.contains("Ravi"))
            if (case.expectsTime) {
                assertTrue(plan.fromDate.isNotBlank())
                assertTrue(plan.toDate.isNotBlank())
            }
        }
    }

    @Test
    fun lifecycle_policy_removes_acquisition_phrases_but_keeps_real_content() {
        assertEquals("beach", QueryLifecycleScaffoldingPolicy.strip("downloaded and edited beach"))
        assertEquals("", QueryLifecycleScaffoldingPolicy.strip("I took screenshots").removePrefix("I").trim())
        assertEquals("Odyssey", QueryLifecycleScaffoldingPolicy.strip("received Odyssey"))
        assertTrue(QueryLifecycleScaffoldingPolicy.forbiddenWords.contains("saved"))
        assertTrue(QueryLifecycleScaffoldingPolicy.forbiddenWords.contains("downloaded"))
        assertTrue(QueryLifecycleScaffoldingPolicy.forbiddenWords.contains("edited"))
        assertTrue(QueryLifecycleScaffoldingPolicy.forbiddenWords.contains("received"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyword_scaffolding_is_rejected_if_it_survives_normalization() {
        QueryPlannerRuntime.validateCompiledPlan(
            "When was the birthday party?",
            emptyList(),
            QueryPlan(
                semanticQueries = listOf("birthday party"),
                keywordTerms = listOf("was birthday"),
                metadataQueries = emptyList(),
                personNames = emptyList(),
                ocrTerms = emptyList(),
                queryCategory = QueryCategory.TIME,
            ),
        )
    }

    @Test
    fun misspelled_spending_query_is_corrected_without_scaffolding_keywords() {
        val query = "how much I spent on odyssy movie ticket"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[semantic == movie ticket] && " +
                    "[keyword == {Odyssey} && {movie} && {was}]",
            ),
            query,
            listOf("Ravi"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = true,
        )

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi"), plan, "Ravi")
        assertEquals(QueryCategory.DOC, plan.queryCategory)
        assertTrue(plan.personNames.isEmpty())
        assertEquals(listOf("Odyssey movie"), plan.keywordTerms)
        assertTrue(QueryPlannerRuntime.plannerSystemInstruction().contains("odyssy"))
        assertTrue(QueryPlannerRuntime.plannerSystemInstruction().contains("{Odyssey}"))
        assertTrue(QueryPlannerRuntime.plannerSystemInstruction().contains("{does} or {was}"))
    }

    @Test
    fun movie_ticket_cost_keeps_record_terms_and_drops_requested_cost() {
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[semantic == spider man movie ticket] && " +
                    "[keyword == {spiderman} && {movie} && {ticket}]",
            ),
            "spyde man move cost",
            emptyList(),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(normalized)

        assertEquals(listOf("spider man movie ticket"), plan.semanticQueries)
        assertEquals(listOf("spiderman movie"), plan.keywordTerms)
        assertFalse(plan.semanticQueries.any { it.contains("cost", ignoreCase = true) })
        assertFalse(plan.keywordTerms.any { it.contains("cost", ignoreCase = true) })
    }

    @Test
    fun my_photos_resolves_to_tagged_self_and_photos_mime() {
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse("[person == Ravi] && [mime type == photos]"),
            "my photos",
            emptyList(),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(normalized)

        assertEquals(listOf("Ravi"), plan.personNames)
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
        assertTrue(plan.keywordTerms.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun number_reference_is_rejected_from_keyword_list() {
        QueryPlannerRuntime.validateCompiledPlan(
            "what is my passport number",
            emptyList(),
            QueryPlan(
                semanticQueries = listOf("passport identity document"),
                keywordTerms = listOf("passport number"),
                metadataQueries = emptyList(),
                personNames = emptyList(),
                ocrTerms = emptyList(),
            ),
            "Ravi",
        )
    }

    @Test
    fun expiry_field_words_are_removed_from_all_keyword_lists() {
        data class Case(
            val query: String,
            val emitted: String,
            val people: List<String>,
            val expectedSemantic: String,
            val expectedKeywords: String,
        )
        val cases = listOf(
            Case(
                "What is Ravi passport expiry date?",
                "[semantic == passport expiry date] && " +
                    "[keyword == {Ravi} && {passport} && {expiry} && {date}]",
                listOf("Ravi"),
                "passport travel identity document",
                "Ravi passport",
            ),
            Case(
                "When does Ravi's driving licence expire?",
                "[semantic == driving licence expiration] && " +
                    "[keyword == {Ravi} && {driving} && {licence} && {expire}]",
                listOf("Ravi"),
                "driving licence government identity document",
                "Ravi driving licence",
            ),
            Case(
                "Tata AIG policy expiration date",
                "[semantic == insurance policy expiration] && " +
                    "[keyword == {Tata} && {AIG} && {policy} && {expiration}]",
                emptyList(),
                "insurance policy",
                "Tata AIG policy",
            ),
            Case(
                "What date is the passport valid until?",
                "[semantic == passport validity] && " +
                    "[keyword == {passport} && {valid} && {until}]",
                emptyList(),
                "passport travel identity document",
                "passport",
            ),
        )

        cases.forEach { case ->
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse(case.emitted),
                case.query,
                case.people,
                case.people.firstOrNull(),
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(case.query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(case.query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                case.query,
                case.people,
                plan,
                case.people.firstOrNull(),
            )
            assertEquals(case.expectedSemantic, plan.semanticQueries.single())
            assertEquals(case.expectedKeywords, plan.keywordTerms.single())
            assertTrue(
                plan.keywordTerms.flatMap(OcrKeywordPolicy::keywords).none {
                    it in ExpiryKeywordPolicy.forbiddenWordsFor(case.query)
                },
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun expiry_field_word_is_rejected_if_it_survives_normalization() {
        QueryPlannerRuntime.validateCompiledPlan(
            "What is the passport expiry date?",
            emptyList(),
            QueryPlan(
                semanticQueries = listOf("passport"),
                keywordTerms = listOf("passport expiry"),
                metadataQueries = emptyList(),
                personNames = emptyList(),
                ocrTerms = emptyList(),
                queryCategory = QueryCategory.DOC,
            ),
        )
    }

    @Test
    fun planner_prompt_contains_universal_output_and_visual_document_examples() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction()

        assertTrue(prompt.contains("Every PLANNER_TASK and PLANNER_TASK_REPAIR"))
        assertTrue(prompt.contains("exactly two lines"))
        assertTrue(prompt.contains("`[person == Ramani && location == Goa]` is invalid"))
        assertTrue(prompt.contains("Do not emit keyword for visual intent"))
        assertTrue(prompt.contains("File lifecycle wording has no searchable meaning"))
        assertTrue(prompt.contains("what I saved last week"))
        assertTrue(prompt.contains("beach photos I downloaded and edited"))
        assertTrue(prompt.contains("Ramani at Goa"))
        assertTrue(prompt.contains("Ramani dancing"))
        assertTrue(prompt.contains("Ramani dancing in the morning"))
        assertTrue(prompt.contains("when is Ravi birthday"))
        assertTrue(prompt.contains("Ravi passport number"))
        assertTrue(prompt.contains("[keyword == {Ravi} && {passport}]"))
    }

    @Test
    fun named_birthday_queries_get_person_scope_and_deterministic_date_sort() {
        listOf("Ramani", "Ravi").forEach { name ->
            val query = "when is $name birthday"
            val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
                QueryExecutionSpec.parse(
                    "[person == $name] && [semantic == birthday] && " +
                        "[keyword == {$name} && {birthday}]",
                ),
                query,
                listOf("Ravi", "Ramani"),
                "Ravi",
            )
            val plan = ExecutionSpecCompiler.compile(
                normalized,
                derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
                derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
            )

            QueryPlannerRuntime.validateCompiledPlan(
                query,
                listOf("Ravi", "Ramani"),
                plan,
                "Ravi",
            )
            assertEquals(QueryCategory.TIME, plan.queryCategory)
            assertEquals(listOf(name), plan.personNames)
            assertEquals(listOf("birthday"), plan.semanticQueries)
            assertTrue(plan.keywordTerms.isEmpty())
            assertTrue(plan.recentFirst)
            assertTrue(plan.needsAnswer)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun named_birthday_query_rejects_a_plan_that_omits_birthday_semantic() {
        val query = "when is Ramani birthday"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse("[person == Ramani]"),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategory.TIME,
            derivedAnswerNeeded = true,
        )

        QueryPlannerRuntime.validateCompiledPlan(
            query,
            listOf("Ravi", "Ramani"),
            plan,
            "Ravi",
        )
    }

    @Test
    fun named_birthday_party_uses_person_scope_without_requiring_name_in_ocr() {
        val query = "Ravi birthday party"
        val normalized = QueryPlannerRuntime.normalizeFiniteConstraints(
            QueryExecutionSpec.parse(
                "[person == Ravi] && [semantic == birthday party] && " +
                    "[keyword == {Ravi} && {birthday} && {party}]",
            ),
            query,
            listOf("Ravi", "Ramani"),
            "Ravi",
        )
        val plan = ExecutionSpecCompiler.compile(
            normalized,
            derivedCategory = QueryCategoryConstraintPolicy.expectedCategory(query),
            derivedAnswerNeeded = AnswerIntentPolicy.expected(query),
        )

        QueryPlannerRuntime.validateCompiledPlan(query, listOf("Ravi", "Ramani"), plan, "Ravi")
        assertEquals(listOf("Ravi"), plan.personNames)
        assertEquals(listOf("birthday party"), plan.semanticQueries)
        assertTrue(plan.keywordTerms.isEmpty())
    }

    @Test
    fun driving_licence_follow_ups_stay_on_document_fields() {
        val original = "what is the number on Ravi's driving licence document"

        assertTrue(
            FollowUpSuggestionPolicy.isCompatible(
                "When does Ravi's driving licence expire?",
                original,
            ),
        )
        assertEquals(
            "What is the expiry date?",
            FollowUpSuggestionPolicy.compactForDisplay(
                "When does Ravi's driving licence expire?",
                original,
            ),
        )
        assertEquals(
            "What is the address?",
            FollowUpSuggestionPolicy.compactForDisplay(
                "What is Ravi's driving licence address?",
                original,
            ),
        )
        assertFalse(
            FollowUpSuggestionPolicy.isCompatible(
                "Who else was there on 5 October 2025?",
                original,
            ),
        )
        assertFalse(
            FollowUpSuggestionPolicy.isCompatible(
                "What else did Ravi do on that day?",
                original,
            ),
        )
        assertTrue(
            FollowUpSuggestionPolicy.isCompatible(
                "Who else was at Ramani's birthday?",
                "when is Ramani birthday",
            ),
        )
    }
}
