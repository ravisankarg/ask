package com.ravi.askgalaxy

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryPlannerV2ContractTest {
    private val context = V2PlannerContext(
        currentQuery = "recent trip",
        previousQuery = "Ravi passport number",
        knownPeople = listOf("Ravi", "Ramani"),
        selfPerson = "Ravi",
        currentDate = LocalDate.of(2026, 8, 22),
    )

    @Test
    fun recent_trip_uses_location_novelty_without_inventing_self_or_trip_semantics() {
        val output = compile(
            """{"v":2,"q":"recent trip","intent":"browse","ops":[["travel","outside_normal"],["sort","newest"]]}""",
        )
        assertEquals("outside_normal", output.plan.travelScope)
        assertTrue(output.plan.semanticQueries.isEmpty())
        assertTrue(output.plan.personNames.isEmpty())
        assertTrue(output.plan.recentFirst)
    }

    @Test
    fun literal_catalog_exposes_exact_values_as_opaque_references() {
        val prompt = QueryPlannerV2.userPrompt(
            currentDate = context.currentDate,
            query = "photos between 2025-01-02 and 2026-03-04 after 6:30 pm for 3 years",
            knownPeople = context.knownPeople,
            selfPerson = context.selfPerson,
            previousQuery = "before 2024-05-06",
        )
        assertTrue(prompt.contains("face_refs=F0=Ravi|F1=Ramani"))
        assertTrue(prompt.contains("D0=2025-01-02"))
        assertTrue(prompt.contains("D1=2026-03-04"))
        assertTrue(prompt.contains("C0=6:30 pm"))
        assertTrue(prompt.contains("N0=3"))
        assertTrue(prompt.contains("previous_literal_refs=PD0=2024-05-06"))
    }

    @Test
    fun self_photo_and_relative_number_reference_compile_to_executable_range() {
        val output = compile(
            raw = """{"v":2,"q":"my photos last 3 years","intent":"browse","ops":[["self"],["media","photos"],["relative_date","last","N0","years"]]}""",
            currentQuery = "my photos last 3 years",
        )
        assertEquals(listOf("Ravi"), output.plan.personNames)
        assertEquals(QueryMediaType.PHOTOS, output.plan.mediaType)
        assertEquals("2023-08-22", output.plan.fromDate)
        assertEquals("2026-08-22", output.plan.toDate)
        assertTrue(output.plannerJson.contains("[\"date\",\"2023-08-22\",\"2026-08-22\",\"last 3 years\"]"))
    }

    @Test
    fun face_reference_and_compound_scene_remain_separate_constraints() {
        val output = compile(
            raw = """{"v":2,"q":"Ramani cycling in rain photos","intent":"browse","ops":[["person_ref","F1"],["term","cycling in rain","semantic"],["media","photos"]]}""",
            currentQuery = "Ramani cycling in rain photos",
        )
        assertEquals(listOf("Ramani"), output.plan.personNames)
        assertEquals(listOf("cycling in rain"), output.plan.semanticQueries)
        assertEquals(QueryMediaType.PHOTOS, output.plan.mediaType)
        assertTrue(output.plannerJson.contains("[\"person\",\"Ramani\"]"))
        assertFalse(output.plannerJson.contains("person_ref"))
    }

    @Test
    fun birthday_semantics_survive_person_media_and_relative_date() {
        val output = compile(
            raw = """{"v":2,"q":"Ramani birthday photos since 2 years","intent":"browse","ops":[["person_ref","F1"],["media","photos"],["term","birthday","semantic"],["relative_date","since","N0","years"]]}""",
            currentQuery = "Ramani birthday photos since 2 years",
        )
        assertEquals(listOf("Ramani"), output.plan.personNames)
        assertEquals(listOf("birthday"), output.plan.semanticQueries)
        assertEquals("2024-08-22", output.plan.fromDate)
        assertEquals("2026-08-22", output.plan.toDate)
    }

    @Test
    fun passport_answer_is_cross_source_semantic_and_lexical_not_a_face_filter() {
        val output = compile(
            raw = """{"v":2,"q":"Ravi passport number","intent":"answer:text","ops":[["term","Ravi","all"],["term","passport","semantic+all"]]}""",
            currentQuery = "Ravi passport number",
        )
        assertEquals(QueryCategory.DOC, output.plan.queryCategory)
        assertTrue(output.plan.needsAnswer)
        assertEquals(listOf("passport"), output.plan.semanticQueries)
        assertEquals(listOf("{Ravi} && {passport}"), output.plan.keywordTerms)
        assertTrue(output.plan.personNames.isEmpty())
        assertNull(output.plan.mediaType)
    }

    @Test
    fun prompt_removes_requested_answer_slots_from_retrieval_terms() {
        val instruction = QueryPlannerV2.systemInstruction

        assertTrue(instruction.contains("Ask Galaxy Query Planner V2.10"))
        assertTrue(instruction.contains("OTHER SOURCES"))
        assertTrue(instruction.contains("remove answer-slot words"))
        assertTrue(instruction.contains("written me/my uses self_term"))
    }

    @Test
    fun prompt_routes_phone_source_intent_without_searching_routing_verbs() {
        val instruction = QueryPlannerV2.systemInstruction

        assertTrue(instruction.contains("OTHER SOURCES"))
        assertTrue(instruction.contains("call/dialed -> media call_logs"))
        assertTrue(instruction.contains("text/SMS -> media sms"))
        assertTrue(instruction.contains("message/chat -> media messages"))
        assertTrue(instruction.contains("appointment/meeting/calendar -> media calendar"))
        assertFalse(instruction.contains("[\"term\",\"called\""))
    }

    @Test
    fun message_source_keeps_requested_sender_content_and_date() {
        val output = compile(
            raw = """{"v":2,"q":"messages from Ramani about cycling yesterday","intent":"browse","ops":[["media","messages"],["term","Ramani","all"],["term","cycling","semantic"],["calendar_date","yesterday"]]}""",
            currentQuery = "messages from Ramani about cycling yesterday",
        )
        assertEquals(QueryMediaType.MESSAGES, output.plan.mediaType)
        assertEquals(listOf("cycling"), output.plan.semanticQueries)
        assertEquals(listOf("{Ramani}"), output.plan.keywordTerms)
        assertEquals("2026-08-21", output.plan.fromDate)
        assertEquals("2026-08-21", output.plan.toDate)
        assertTrue(output.plan.personNames.isEmpty())
    }

    @Test
    fun future_calendar_plan_compiles_without_generic_nearest_result_fallback() {
        val output = compile(
            raw = """{"v":2,"q":"next calendar appointment","intent":"browse","ops":[["media","calendar"],["term","appointment","semantic"],["calendar_date","future"],["sort","newest"]]}""",
            currentQuery = "next calendar appointment",
        )
        assertEquals(QueryMediaType.CALENDAR, output.plan.mediaType)
        assertEquals(listOf("appointment"), output.plan.semanticQueries)
        assertEquals("2026-08-22", output.plan.fromDate)
        assertTrue(output.plan.toDate.isBlank())
    }

    @Test
    fun written_self_calendar_period_daypart_and_oldest_are_executable() {
        val selfDocument = compile(
            raw = """{"v":2,"q":"my passport number","intent":"answer:text","ops":[["self_term"],["term","passport","semantic+all"]]}""",
            currentQuery = "my passport number",
        )
        assertEquals(listOf("{Ravi} && {passport}"), selfDocument.plan.keywordTerms)
        assertTrue(selfDocument.plan.personNames.isEmpty())

        val lastYear = compile(
            raw = """{"v":2,"q":"snow videos last year","intent":"browse","ops":[["term","snow","semantic"],["media","videos"],["calendar_period","last","year"]]}""",
            currentQuery = "snow videos last year",
        )
        assertEquals("2025-01-01", lastYear.plan.fromDate)
        assertEquals("2025-12-31", lastYear.plan.toDate)

        val timeAndOrder = compile(
            raw = """{"v":2,"q":"oldest morning photos","intent":"browse","ops":[["media","photos"],["daypart","morning"],["sort","oldest"]]}""",
            currentQuery = "oldest morning photos",
        )
        assertEquals(listOf("morning"), timeAndOrder.plan.semanticQueries)
        assertTrue(timeAndOrder.plan.oldestFirst)
        assertTrue(timeAndOrder.plan.executionSpecString().endsWith("SORT_OLDEST"))
    }

    @Test
    fun explicit_dates_and_clock_are_resolved_only_from_supplied_references() {
        val output = compile(
            raw = """{"v":2,"q":"photos between 2025-01-01 and 2025-12-31 at 6 pm","intent":"browse","ops":[["media","photos"],["date_between","D0","D1"],["clock_ref","C0"]]}""",
            currentQuery = "photos between 2025-01-01 and 2025-12-31 at 6 pm",
        )
        assertEquals("2025-01-01", output.plan.fromDate)
        assertEquals("2025-12-31", output.plan.toDate)
        assertEquals("6 pm", output.plan.timeHint)
    }

    @Test
    fun previous_literal_requires_prev_and_valid_reference_kind() {
        val withoutPrev = """{"v":2,"q":"photos after 2025-01-01","intent":"browse","ops":[["media","photos"],["date_ref","after","PD0"]]}"""
        assertThrows(IllegalArgumentException::class.java) {
            compile(withoutPrev, currentQuery = "show those photos", previousQuery = "after 2025-01-01")
        }
        val output = compile(
            raw = """{"v":2,"q":"photos after 2025-01-01","intent":"browse","ops":[["media","photos"],["date_ref","after","PD0"]],"prev":true}""",
            currentQuery = "show those photos",
            previousQuery = "after 2025-01-01",
        )
        assertEquals("2025-01-01", output.plan.fromDate)
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                """{"v":2,"q":"photos","intent":"browse","ops":[["media","photos"],["clock_ref","PD0"]],"prev":true}""",
                currentQuery = "show those",
                previousQuery = "after 2025-01-01",
            )
        }
    }

    @Test
    fun repair_prompt_omits_malformed_answer_and_repeats_only_structural_rejection() {
        val bad = """{"v":2,"q":"cycling","intent":"browse","ops":[["term","cycling"]]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { compile(bad, "cycling") }
        val repair = QueryPlannerV2.repairPrompt(
            currentDate = context.currentDate,
            context = context.copy(currentQuery = "cycling"),
            validatorError = error.message.orEmpty(),
            attempt = 1,
        )
        assertFalse(repair.contains(bad))
        assertFalse(repair.contains("invalid_output="))
        assertTrue(repair.contains("prior answer was rejected and omitted"))
        assertTrue(repair.contains("validator_error="))
    }

    @Test
    fun malformed_legacy_shapes_unknown_faces_duplicates_and_extra_keys_are_rejected() {
        val samples = listOf(
            """{"v":2,"q":"trip","intent":"browse","ops":[["travel","outside_normal","trip"]]}""",
            """{"v":2,"q":"Ramani photos","intent":"browse","ops":[["person_ref","F9"],["media","photos"]]}""",
            """{"v":2,"q":"passport","intent":"browse","ops":[["term","passport","semantic"],["term","passport","all"]]}""",
            """{"v":2,"q":"trip","intent":"browse","ops":[["travel","outside_normal"]],"people":[]}""",
        )
        samples.forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { compile(raw) }
        }
    }

    @Test
    fun gallery_prompt_encodes_live_failure_learnings_without_local_plan_authoring() {
        val instruction = QueryPlannerV2.systemInstruction

        assertTrue(instruction.contains("You alone author the plan"))
        assertTrue(instruction.contains("executes your operations unchanged"))
        assertTrue(instruction.contains("SILENTLY BUILD A COVERAGE LEDGER BEFORE JSON"))
        assertTrue(instruction.contains("preserve all remaining visual meaning in exactly one compound"))
        assertTrue(instruction.contains("only/alone/just fills PEOPLE_ONLY"))
        assertTrue(instruction.contains("last/past/since NUMBER units"))
        assertTrue(instruction.contains("no meaningful requested content may be missing"))
    }

    @Test
    fun validator_rejects_wrong_face_invented_self_lost_media_and_scene_ocr() {
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"Ramani photos","intent":"browse","ops":[["person_ref","F0"],["media","photos"]]}""",
                currentQuery = "Ramani photos",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"photos last 3 years","intent":"browse","ops":[["self"],["media","photos"],["relative_date","last","N0","years"]]}""",
                currentQuery = "photos last 3 years",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"sunset pictures","intent":"browse","ops":[["term","sunset","semantic"]]}""",
                currentQuery = "sunset pictures",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"cycling in rain","intent":"browse","ops":[["term","cycling in rain","semantic+all"]]}""",
                currentQuery = "cycling in rain",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"cycling in rain","intent":"browse","ops":[["term","cycling","semantic"],["term","rain","semantic"]]}""",
                currentQuery = "cycling in rain",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"red car","intent":"answer:text","ops":[["term","red car","semantic+all"]]}""",
                currentQuery = "red car",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"beach photos","intent":"browse","ops":[["media","photos"],["location","beach"]]}""",
                currentQuery = "beach photos",
            )
        }
    }

    @Test
    fun validator_rejects_corrupted_digits_and_missing_date_or_sort_operations() {
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"photos before January 20","intent":"browse","ops":[["media","photos"]]}""",
                currentQuery = "photos before January 2025",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"photos this week","intent":"browse","ops":[["media","photos"]]}""",
                currentQuery = "photos this week",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            compile(
                raw = """{"v":2,"q":"latest vacation pictures","intent":"browse","ops":[["media","photos"],["travel","outside_normal"]]}""",
                currentQuery = "latest vacation pictures",
            )
        }
    }

    @Test
    fun valid_e2b_gallery_operations_pass_validation_unchanged() {
        val sunset = compile(
            raw = """{"v":2,"q":"sunset pictures","intent":"browse","ops":[["media","photos"],["term","sunset","semantic"]]}""",
            currentQuery = "sunset pictures",
        )
        assertEquals(listOf("sunset"), sunset.plan.semanticQueries)
        assertEquals(QueryMediaType.PHOTOS, sunset.plan.mediaType)

        val beachInChennai = compile(
            raw = """{"v":2,"q":"beach photos in Chennai","intent":"browse","ops":[["media","photos"],["location","Chennai"],["term","beach","semantic"]]}""",
            currentQuery = "beach photos in Chennai",
        )
        assertEquals("Chennai", beachInChennai.plan.locationHint)
        assertEquals(listOf("beach"), beachInChennai.plan.semanticQueries)

        val thisWeek = compile(
            raw = """{"v":2,"q":"photos this week","intent":"browse","ops":[["media","photos"],["calendar_period","this","week"]]}""",
            currentQuery = "photos this week",
        )
        assertEquals("2026-08-17", thisWeek.plan.fromDate)
        assertEquals("2026-08-22", thisWeek.plan.toDate)

        val excludedPerson = compile(
            raw = """{"v":2,"q":"photos of Ramani without Ravi","intent":"browse","ops":[["media","photos"],["person_ref","F1"],["not_person_ref","F0"]]}""",
            currentQuery = "photos of Ramani without Ravi",
        )
        assertEquals(listOf("Ramani"), excludedPerson.plan.personNames)
        assertEquals(listOf("Ravi"), excludedPerson.plan.excludedPersonNames)

        val excludedScene = compile(
            raw = """{"v":2,"q":"photos at Goa excluding beach","intent":"browse","ops":[["media","photos"],["location","Goa"],["not_term","beach"]]}""",
            currentQuery = "photos at Goa excluding beach",
        )
        assertEquals(listOf("beach"), excludedScene.plan.negativeSemanticQueries)
    }

    @Test
    fun v1_rollback_remains_available_beside_v210() {
        val v1 = QueryPlannerRuntime.plannerSystemInstruction(QueryPlannerProtocol.V1)
        val v2 = QueryPlannerRuntime.plannerSystemInstruction(QueryPlannerProtocol.V2)
        assertTrue(v1.contains("RESOLVED_QUERY:"))
        assertTrue(v2.contains("Query Planner V2.10"))
        assertTrue(v2.contains("EXACT OPERATION FORMS"))
        assertTrue(v2.contains("semantic+all"))
        assertTrue(v2.length < 8_000)
    }

    @Test
    fun communication_router_cannot_replace_either_model_authored_plan() {
        assertNull(CallLogQueryPolicy.legacyPlanOverride(QueryPlannerProtocol.V2, "messages about cycling"))
        assertNull(CallLogQueryPolicy.legacyPlanOverride(QueryPlannerProtocol.V1, "messages about cycling"))
    }

    private fun compile(
        raw: String,
        currentQuery: String = context.currentQuery,
        previousQuery: String = context.previousQuery,
    ): V2CompiledPlannerOutput = QueryPlannerV2.parseAndCompile(
        raw = raw,
        context = context.copy(currentQuery = currentQuery, previousQuery = previousQuery),
    )
}
