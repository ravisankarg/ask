package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryPlannerSearchOnlyContractTest {
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
        assertEquals(listOf("spiderman movie ticket"), plan.keywordTerms)
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
    fun planner_prompt_contains_the_two_regression_examples() {
        val prompt = QueryPlannerRuntime.plannerSystemInstruction()

        assertTrue(prompt.contains("semantic == spider man movie ticket"))
        assertTrue(prompt.contains("{spiderman} && {movie} && {ticket}"))
        assertTrue(prompt.contains("my photos (self_person=Ravi)"))
        assertTrue(prompt.contains("[person == Ravi] && [mime type == photos]"))
        assertTrue(prompt.contains("Do not put requested answer attributes such as cost"))
    }
}
