package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    }

    @Test
    fun structural_gate_preserves_model_intent_without_query_based_rewrites() {
        val modelSpec = QueryExecutionSpec.parse(
            "[query_category == location] && [answer_needed == false] && " +
                "[person == ModelChosenPerson] && [location == ModelChosenPlace] && " +
                "[semantic == model chosen phrase] && [time == 6 pm]",
        )

        val plan = ModelAuthoredPlanStructure.compile(modelSpec)

        assertEquals(QueryCategory.LOCATION, plan.queryCategory)
        assertFalse(plan.needsAnswer)
        assertEquals(listOf("ModelChosenPerson"), plan.personNames)
        assertEquals("ModelChosenPlace", plan.locationHint)
        assertEquals(listOf("model chosen phrase"), plan.semanticQueries)
        assertEquals("6 pm", plan.timeHint)
        assertEquals(modelSpec.canonicalizeCategoryEnvelope().render(), plan.executionSpecString())
    }

    @Test
    fun structural_gate_rejects_incomplete_or_unsupported_model_protocol() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(
                QueryExecutionSpec.parse("[answer_needed == false] && [semantic == beach]"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(
                QueryExecutionSpec.parse(
                    "[query_category == doc] && [answer_needed == true] && [ocr == passport]",
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            QueryExecutionSpec.parse(
                "query_category == scenary && answer_needed == false && semantic == beach",
            )
        }
    }

    @Test
    fun envelope_fields_cannot_be_hidden_inside_retrieval_operators() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(
                QueryExecutionSpec.parse(
                    "[[query_category == scenary] && [answer_needed == false] && " +
                        "[semantic == beach]] - [query_category == person]",
                ),
            )
        }
    }

    @Test
    fun reported_gallery_examples_are_complete_model_authored_plans() {
        val cases = listOf(
            "[query_category == scenary] && [answer_needed == false] && " +
                "[person == Ravi] && [mime type == photos] && " +
                "[from_date == 2023-08-22] && [to_date == 2026-08-22]",
            "[query_category == scenary] && [answer_needed == false] && " +
                "[mime type == photos] && [semantic == birthday] && " +
                "[from_date == 2026-05-22] && [to_date == 2026-08-22]",
            "[query_category == scenary] && [answer_needed == false] && " +
                "[semantic == cycling in rain]",
            "[query_category == scenary] && [answer_needed == false] && " +
                "[person == Ravi] && [mime type == photos] && [location == BR Hills]",
        )

        val plans = cases.map { ModelAuthoredPlanStructure.compile(QueryExecutionSpec.parse(it)) }

        assertEquals(listOf("Ravi"), plans[0].personNames)
        assertEquals(QueryMediaType.PHOTOS, plans[0].mediaType)
        assertEquals("2023-08-22", plans[0].fromDate)
        assertEquals("2026-08-22", plans[0].toDate)
        assertEquals(listOf("birthday"), plans[1].semanticQueries)
        assertEquals("2026-05-22", plans[1].fromDate)
        assertEquals("2026-08-22", plans[1].toDate)
        assertEquals(listOf("cycling in rain"), plans[2].semanticQueries)
        assertTrue(plans[2].keywordTerms.isEmpty())
        assertEquals(null, plans[2].mediaType)
        assertTrue(plans[2].locationHint.isBlank())
        assertEquals(listOf("Ravi"), plans[3].personNames)
        assertEquals("BR Hills", plans[3].locationHint)
    }

    @Test
    fun relative_date_in_time_is_rejected_for_model_repair() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAuthoredPlanStructure.compile(
                QueryExecutionSpec.parse(
                    "[query_category == scenary] && [answer_needed == false] && " +
                        "[semantic == trip] && [time == last 3 years]",
                ),
            )
        }

        val explicit = ModelAuthoredPlanStructure.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [answer_needed == false] && " +
                    "[semantic == trip] && [from_date == 2023-08-22] && [to_date == 2026-08-22]",
            ),
        )
        assertEquals("2023-08-22", explicit.fromDate)
        assertEquals("2026-08-22", explicit.toDate)
    }

    @Test
    fun recent_trip_plan_has_no_implicit_self_person() {
        val plan = ModelAuthoredPlanStructure.compile(
            QueryExecutionSpec.parse(
                "[query_category == scenary] && [answer_needed == false] && [semantic == trip] SORT_DATE",
            ),
        )

        assertTrue(plan.personNames.isEmpty())
        assertEquals(listOf("trip"), plan.semanticQueries)
        assertTrue(plan.recentFirst)
    }

    @Test
    fun two_line_protocol_keeps_e2b_resolved_query_and_plan_verbatim() {
        val output = ContextualPlannerOutputPolicy.parse(
            output = "RESOLVED_QUERY: photos\n" +
                "PLAN: [query_category == scenary] && [answer_needed == false] && " +
                "[mime type == photos]",
            currentQuery = "my photos",
            knownPersonLabels = listOf("Ravi", "Ramani"),
            selfPersonLabel = "Ravi",
        )
        val plan = ModelAuthoredPlanStructure.compile(QueryExecutionSpec.parse(output.expression))

        assertEquals("photos", output.resolvedQuery)
        assertTrue(plan.personNames.isEmpty())
        assertEquals(QueryMediaType.PHOTOS, plan.mediaType)
    }

    @Test
    fun plan_only_or_extra_line_output_is_rejected_for_e2b_repair() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextualPlannerOutputPolicy.parse(
                "PLAN: [query_category == scenary] && [answer_needed == false] && " +
                    "[semantic == beach]",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextualPlannerOutputPolicy.parse(
                "RESOLVED_QUERY: beach\nPLAN: [query_category == scenary] && " +
                    "[answer_needed == false] && [semantic == beach]\nEXTRA: no",
            )
        }
    }

    @Test
    fun prompt_declares_model_ownership_and_covers_reported_queries() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction(QueryPlannerProtocol.V1)

        assertTrue(prompt.contains("only query planner"))
        assertTrue(prompt.contains("runtime does not infer, add, remove, or rewrite query intent"))
        assertTrue(prompt.contains("exactly one [query_category == value]"))
        assertTrue(prompt.contains("exactly one [answer_needed == true|false]"))
        assertTrue(prompt.contains("at least one retrieval predicate"))
        assertTrue(prompt.contains("Do not emit ocr"))
        assertTrue(prompt.contains("my photos last 3 years"))
        assertTrue(prompt.contains("birthday photos since 3 months"))
        assertTrue(prompt.contains("cycling in rain"))
        assertTrue(prompt.contains("Ravi photos at BR Hills"))
        assertTrue(prompt.contains("current_query=recent trip"))
        assertTrue(prompt.contains("are vocabulary, never default filters"))
        assertTrue(prompt.contains("self_person_lookup_only=Ravi"))
        assertTrue(prompt.contains("Never copy relative date words into time"))
        assertTrue(prompt.contains("when is Ravi birthday"))
        assertTrue(prompt.contains("Ravi passport number"))
        assertTrue(prompt.length < 8_000)
    }
}
