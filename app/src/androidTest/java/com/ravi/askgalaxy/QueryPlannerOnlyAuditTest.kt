package com.ravi.askgalaxy

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test

/**
 * Isolated device audit of Gemma 4 E4B query planning and the production plan
 * validators. It deliberately does not construct GalleryIndexer,
 * StructuredSearchExecutor, AnswerContextPicker, or any answer-generation
 * component.
 *
 * Each result is emitted as one machine-readable QP_ONLY row. A category test
 * always attempts all selected cases before failing, so one bad plan cannot
 * hide later results.
 */
class QueryPlannerOnlyAuditTest {
    @Test
    fun docPlans() = runCategory(QueryCategory.DOC)

    @Test
    fun scenaryPlans() = runCategory(QueryCategory.SCENARY)

    @Test
    fun personPlans() = runCategory(QueryCategory.PERSON)

    @Test
    fun locationPlans() = runCategory(QueryCategory.LOCATION)

    @Test
    fun timePlans() = runCategory(QueryCategory.TIME)

    private fun runCategory(category: QueryCategory) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val requestedIds = InstrumentationRegistry.getArguments()
            .getString("qpOnlyCases")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        val cases = CASES.filter {
            it.expectedCategory == category &&
                (requestedIds.isEmpty() || it.id in requestedIds)
        }
        check(cases.isNotEmpty()) { "No QP-only audit cases selected for ${category.wireName}" }
        val results = cases.map { case ->
            val startedAt = SystemClock.elapsedRealtime()
            var planned: QueryPlannerRuntime.PlannedQuery? = null
            try {
                planned = QueryPlannerRuntime.planWithSession(
                    context = context,
                    query = case.query,
                    knownPersonLabels = KNOWN_PEOPLE,
                    selfPersonLabel = SELF_PERSON,
                )
                planned.session?.close()

                // Exercise the complete validator chain again on the exact
                // canonical expression handed to downstream execution.
                val parsed = QueryExecutionSpec.parse(planned.plannerJson)
                check(parsed == QueryExecutionSpec.parse(parsed.render())) {
                    "execution spec did not round-trip"
                }
                check(parsed.requiredQueryCategory() == case.expectedCategory) {
                    "expected category ${case.expectedCategory.wireName}, got " +
                        parsed.requiredQueryCategory().wireName
                }
                check(parsed.requiredAnswerNeeded() == case.expectedAnswerNeeded) {
                    "expected answer_needed=${case.expectedAnswerNeeded}"
                }
                val compiled = ExecutionSpecCompiler.compile(parsed)
                QueryPlannerRuntime.validateCompiledPlan(
                    case.query,
                    KNOWN_PEOPLE,
                    compiled,
                    SELF_PERSON,
                )
                check(compiled.executionSpecString() == planned.plannerJson) {
                    "compiled canonical expression changed"
                }
                case.validateQuality(compiled)

                AuditResult(
                    case = case,
                    actualCategory = compiled.queryCategory,
                    repairUsed = planned.repairUsed,
                    latencyMs = SystemClock.elapsedRealtime() - startedAt,
                    spec = planned.plannerJson,
                    status = "PASS",
                )
            } catch (error: Throwable) {
                planned?.session?.close()
                AuditResult(
                    case = case,
                    actualCategory = planned?.plan?.queryCategory,
                    repairUsed = planned?.repairUsed ?: error.isFailedRepair(),
                    latencyMs = SystemClock.elapsedRealtime() - startedAt,
                    spec = planned?.plannerJson.orEmpty(),
                    status = "FAIL",
                    error = rootMessage(error),
                )
            }
        }

        results.forEach { result ->
            if (result.status == "PASS") {
                Log.i(TAG, result.toLogLine())
            } else {
                Log.e(TAG, result.toLogLine())
            }
        }
        Log.i(
            TAG,
            listOf(
                "QP_ONLY_SUMMARY",
                "category=${category.wireName}",
                "passed=${results.count { it.status == "PASS" }}",
                "failed=${results.count { it.status != "PASS" }}",
                "repairs=${results.count { it.repairUsed == true }}",
                "totalMs=${results.sumOf(AuditResult::latencyMs)}",
            ).joinToString("|"),
        )

        val failures = results.filter { it.status != "PASS" }
        if (failures.isNotEmpty()) {
            fail(
                failures.joinToString(
                    prefix = "${failures.size}/${results.size} ${category.wireName} plans failed: ",
                    separator = "; ",
                ) { "${it.case.id}=${it.error}" },
            )
        }
    }

    private fun AuditCase.validateQuality(plan: QueryPlan) {
        check(plan.queryCategory == expectedCategory) {
            "category contract failed"
        }
        when (dateExpectation) {
            DateExpectation.NONE -> check(plan.fromDate.isBlank() && plan.toDate.isBlank()) {
                "undated query gained ${plan.fromDate}..${plan.toDate}"
            }
            DateExpectation.ANY_BOUNDED -> check(
                plan.fromDate.isNotBlank() && plan.toDate.isNotBlank(),
            ) {
                "expected a bounded date range"
            }
            DateExpectation.EXACT_DAY -> check(
                plan.fromDate.isNotBlank() && plan.fromDate == plan.toDate,
            ) {
                "expected equal from_date and to_date"
            }
            DateExpectation.OPEN_ENDED -> check(
                plan.fromDate.isNotBlank() || plan.toDate.isNotBlank(),
            ) {
                "expected at least one date boundary"
            }
        }
        expectedFromDate?.let { expected ->
            check(plan.fromDate == expected) {
                "expected from_date '$expected', got '${plan.fromDate}'"
            }
        }
        expectedToDate?.let { expected ->
            check(plan.toDate == expected) {
                "expected to_date '$expected', got '${plan.toDate}'"
            }
        }
        if (requiresBlankToDate) {
            check(plan.toDate.isBlank()) {
                "expected an open-ended range, got to_date '${plan.toDate}'"
            }
        }
        expectedMedia?.let { expected ->
            check(plan.mediaType == expected) {
                "expected MIME ${expected.label()}, got ${plan.mediaType?.label() ?: "none"}"
            }
        }
        requiredPeople.forEach { expected ->
            check(plan.personNames.any { it.equals(expected, ignoreCase = true) }) {
                "missing person '$expected'"
            }
        }
        excludedPeople.forEach { expected ->
            check(plan.excludedPersonNames.any { it.equals(expected, ignoreCase = true) }) {
                "missing excluded person '$expected'"
            }
        }
        expectedLocation?.let { expected ->
            check(plan.locationHint.equals(expected, ignoreCase = true)) {
                "expected location '$expected', got '${plan.locationHint}'"
            }
        }
        if (requiresSemantic) {
            check(plan.semanticQueries.isNotEmpty()) { "missing positive semantic retrieval phrase" }
        }
        if (forbidsSemantic) {
            check(plan.semanticQueries.isEmpty()) {
                "metadata-only query gained positive semantic filter '${plan.semanticQueries.joinToString()}'"
            }
        }
        if (requiresNegativeSemantic) {
            check(plan.negativeSemanticQueries.isNotEmpty()) {
                "missing semantic subtraction"
            }
        }
        if (requiresDateSort) {
            check(plan.recentFirst) { "missing SORT_DATE" }
        }
        if (requiresLocationSort) {
            check(plan.sortByLocation) { "missing SORT_LOC" }
        }
        if (forbidsLocationSort) {
            check(!plan.sortByLocation) { "query gained unrequested SORT_LOC" }
        }
        check(plan.answerIntentExplicit) { "missing explicit answer_needed predicate" }
        check(plan.needsAnswer == expectedAnswerNeeded) {
            "expected answer_needed=$expectedAnswerNeeded, got ${plan.needsAnswer}"
        }
        if (expectedOnlyPeople.isNotEmpty()) {
            check(plan.onlyPersonNames.map(String::lowercase).toSet() ==
                expectedOnlyPeople.map(String::lowercase).toSet()) {
                "expected people_only=$expectedOnlyPeople, got ${plan.onlyPersonNames}"
            }
        } else {
            check(plan.onlyPersonNames.isEmpty()) {
                "unexpected people_only=${plan.onlyPersonNames}"
            }
        }
    }

    private data class AuditCase(
        val id: String,
        val expectedCategory: QueryCategory,
        val complexity: String,
        val ambiguity: String,
        val query: String,
        val dateExpectation: DateExpectation = DateExpectation.NONE,
        val expectedFromDate: String? = null,
        val expectedToDate: String? = null,
        val requiresBlankToDate: Boolean = false,
        val expectedMedia: QueryMediaType? = null,
        val requiredPeople: Set<String> = emptySet(),
        val excludedPeople: Set<String> = emptySet(),
        val expectedLocation: String? = null,
        val expectedAnswerNeeded: Boolean = expectedCategory != QueryCategory.SCENARY,
        val expectedOnlyPeople: Set<String> = emptySet(),
        val requiresSemantic: Boolean = true,
        val forbidsSemantic: Boolean = false,
        val requiresNegativeSemantic: Boolean = false,
        val requiresDateSort: Boolean = false,
        val requiresLocationSort: Boolean = false,
        val forbidsLocationSort: Boolean = false,
    )

    private enum class DateExpectation {
        NONE,
        ANY_BOUNDED,
        EXACT_DAY,
        OPEN_ENDED,
    }

    private data class AuditResult(
        val case: AuditCase,
        val actualCategory: QueryCategory?,
        val repairUsed: Boolean?,
        val latencyMs: Long,
        val spec: String,
        val status: String,
        val error: String = "",
    ) {
        fun toLogLine(): String = listOf(
            "QP_ONLY",
            "id=${case.id}",
            "complexity=${case.complexity}",
            "ambiguity=${case.ambiguity}",
            "expected=${case.expectedCategory.wireName}",
            "actual=${actualCategory?.wireName ?: "ERROR"}",
            "validator=$status",
            "repair=${repairUsed ?: "unknown"}",
            "latencyMs=$latencyMs",
            "query=${clean(case.query)}",
            "spec=${clean(spec)}",
            "error=${clean(error)}",
        ).joinToString("|")

        private fun clean(value: String): String =
            value.replace('|', ' ')
                .replace(Regex("[\\r\\n]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }

    private companion object {
        const val TAG = "QP_ONLY_57"
        val KNOWN_PEOPLE = listOf("Ravi", "Meghana", "Ramani")
        const val SELF_PERSON = "Ravi"

        val CASES = listOf(
            AuditCase(
                "D01", QueryCategory.DOC, "simple", "clear",
                "What is the total on my hotel bill?",
            ),
            AuditCase(
                "D02", QueryCategory.DOC, "simple", "clear",
                "How much did I pay for fuel?",
            ),
            AuditCase(
                "D03", QueryCategory.DOC, "medium", "clear",
                "What is my passport number in the passport photo?",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "D04", QueryCategory.DOC, "medium", "clear",
                "When does my driving licence expire?",
            ),
            AuditCase(
                "D05", QueryCategory.DOC, "medium", "clear",
                "What coupon code is shown, and when does it expire?",
            ),
            AuditCase(
                "D06", QueryCategory.DOC, "medium", "typo",
                "How much did I spend on the odyssy movie ticket?",
            ),
            AuditCase(
                "D07", QueryCategory.DOC, "medium", "clear",
                "What was the Wi-Fi password on the hotel card?",
            ),
            AuditCase(
                "D08", QueryCategory.DOC, "complex", "clear",
                "Find the booking reference and departure gate on my flight ticket.",
            ),
            AuditCase(
                "D09", QueryCategory.DOC, "complex", "moderate",
                "Which restaurant receipt shows the highest total from last month?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2026-06-01",
                expectedToDate = "2026-06-30",
            ),
            AuditCase(
                "D10", QueryCategory.DOC, "complex", "clear",
                "From the electricity bills photographed between June and August 2025, " +
                    "which account number and amount appear on the latest bill?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2025-06-01",
                expectedToDate = "2025-08-31",
                requiresDateSort = true,
            ),
            AuditCase(
                "D11", QueryCategory.DOC, "simple", "clear",
                "Ravi passport number",
            ),

            AuditCase(
                "S01", QueryCategory.SCENARY, "simple", "typo",
                "beach phootos",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "S02", QueryCategory.SCENARY, "simple", "clear",
                "Show dogs playing in the snow.",
            ),
            AuditCase(
                "S03", QueryCategory.SCENARY, "medium", "clear",
                "Find photos with a red car at night.",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "S04", QueryCategory.SCENARY, "medium", "clear",
                "Show videos of fireworks over water.",
                expectedMedia = QueryMediaType.VIDEOS,
            ),
            AuditCase(
                "S05", QueryCategory.SCENARY, "medium", "ambiguous",
                "Photos that would make great phone backgrounds.",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "S06", QueryCategory.SCENARY, "complex", "clear",
                "Show Ravi hiking without sunglasses.",
                requiredPeople = setOf("Ravi"),
                requiresNegativeSemantic = true,
            ),
            AuditCase(
                "S07", QueryCategory.SCENARY, "complex", "moderate",
                "Find the best sunset from each mountain trip.",
            ),
            AuditCase(
                "S08", QueryCategory.SCENARY, "complex", "clear",
                "Show birthday-cake photos with blue decorations but exclude indoor scenes.",
                expectedMedia = QueryMediaType.PHOTOS,
                requiresNegativeSemantic = true,
            ),
            AuditCase(
                "S09", QueryCategory.SCENARY, "complex", "moderate",
                "What did I eat on my trip to Barcelona?",
                expectedLocation = "Barcelona",
                expectedAnswerNeeded = true,
            ),
            AuditCase(
                "S10", QueryCategory.SCENARY, "complex", "ambiguous",
                "Show the clearest photo from every national park I visited, excluding selfies.",
                expectedMedia = QueryMediaType.PHOTOS,
                requiresNegativeSemantic = true,
            ),
            AuditCase(
                "S11", QueryCategory.SCENARY, "simple", "clear",
                "me alone",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi"),
                expectedOnlyPeople = setOf("Ravi"),
                requiresSemantic = false,
                expectedAnswerNeeded = false,
            ),
            AuditCase(
                "S12", QueryCategory.SCENARY, "medium", "clear",
                "me with Ramani only",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi", "Ramani"),
                expectedOnlyPeople = setOf("Ravi", "Ramani"),
                requiresSemantic = false,
                expectedAnswerNeeded = false,
            ),
            AuditCase(
                "S13", QueryCategory.SCENARY, "medium", "clear",
                "Ravi and Ramani alone at the beach",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi", "Ramani"),
                expectedOnlyPeople = setOf("Ravi", "Ramani"),
                expectedAnswerNeeded = false,
            ),

            AuditCase(
                "P01", QueryCategory.PERSON, "simple", "clear",
                "Who is in these beach photos?",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "P02", QueryCategory.PERSON, "medium", "clear",
                "Who else was with Meghana at the team outing?",
                requiredPeople = setOf("Meghana"),
            ),
            AuditCase(
                "P03", QueryCategory.PERSON, "complex", "clear",
                "Which people were at the wedding but not Ravi?",
                excludedPeople = setOf("Ravi"),
            ),
            AuditCase(
                "P04", QueryCategory.PERSON, "medium", "moderate",
                "Who appears most often with Ramani?",
                requiredPeople = setOf("Ramani"),
                requiresSemantic = false,
                forbidsSemantic = true,
            ),
            AuditCase(
                "P05", QueryCategory.PERSON, "medium", "moderate",
                "Who was holding the birthday cake?",
            ),
            AuditCase(
                "P06", QueryCategory.PERSON, "complex", "clear",
                "Which people joined both the Goa trip and the mountain trek?",
                expectedLocation = "Goa",
            ),
            AuditCase(
                "P07", QueryCategory.PERSON, "complex", "clear",
                "Who was with me at dinner on 5 October 2025?",
                dateExpectation = DateExpectation.EXACT_DAY,
                expectedFromDate = "2025-10-05",
                expectedToDate = "2025-10-05",
            ),
            AuditCase(
                "P08", QueryCategory.PERSON, "complex", "typo",
                "Who appears with Meghna in Bengaluru without Ravi?",
                requiredPeople = setOf("Meghana"),
                excludedPeople = setOf("Ravi"),
                expectedLocation = "Bengaluru",
                requiresSemantic = false,
                forbidsSemantic = true,
            ),
            AuditCase(
                "P09", QueryCategory.PERSON, "complex", "ambiguous",
                "Which person is wearing a red jacket beside the dog?",
            ),
            AuditCase(
                "P10", QueryCategory.PERSON, "complex", "moderate",
                "Who else was present across Ravi's whole birthday celebration, " +
                    "excluding restaurant screenshots?",
                requiredPeople = setOf("Ravi"),
                requiresNegativeSemantic = true,
            ),
            AuditCase(
                "P11", QueryCategory.PERSON, "medium", "clear",
                "Who is with me in beach sunset photos?",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi"),
            ),

            AuditCase(
                "L01", QueryCategory.LOCATION, "simple", "clear",
                "Where was this beach photo taken?",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "L02", QueryCategory.LOCATION, "simple", "moderate",
                "Where did I park the car?",
            ),
            AuditCase(
                "L03", QueryCategory.LOCATION, "medium", "clear",
                "What places did I visit last year?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2025-01-01",
                expectedToDate = "2025-12-31",
                requiresSemantic = false,
                forbidsSemantic = true,
                requiresLocationSort = true,
            ),
            AuditCase(
                "L04", QueryCategory.LOCATION, "medium", "moderate",
                "Where did Ravi and I go for dinner?",
                requiredPeople = setOf("Ravi"),
            ),
            AuditCase(
                "L05", QueryCategory.LOCATION, "medium", "clear",
                "Which national parks have I visited?",
            ),
            AuditCase(
                "L06", QueryCategory.LOCATION, "medium", "moderate",
                "Where was the sunset photo with the lighthouse taken?",
                expectedMedia = QueryMediaType.PHOTOS,
            ),
            AuditCase(
                "L07", QueryCategory.LOCATION, "complex", "clear",
                "Which cities did I visit with Meghana but without Ravi?",
                requiredPeople = setOf("Meghana"),
                excludedPeople = setOf("Ravi"),
                requiresSemantic = false,
                forbidsSemantic = true,
                requiresLocationSort = true,
            ),
            AuditCase(
                "L08", QueryCategory.LOCATION, "complex", "moderate",
                "Where did we camp during the latest mountain trip?",
                requiresDateSort = true,
            ),
            AuditCase(
                "L09", QueryCategory.LOCATION, "complex", "ambiguous",
                "Where did my Bengaluru-to-Goa road trip stop for lunch?",
                forbidsLocationSort = true,
            ),
            AuditCase(
                "L10", QueryCategory.LOCATION, "complex", "clear",
                "Which places did I visit in July 2025, from north to south, " +
                    "excluding airport layovers?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2025-07-01",
                expectedToDate = "2025-07-31",
                requiresSemantic = false,
                forbidsSemantic = true,
                requiresNegativeSemantic = true,
                requiresLocationSort = true,
            ),
            AuditCase(
                "L11", QueryCategory.LOCATION, "medium", "clear",
                "Where I took beach sunset photos",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi"),
            ),
            AuditCase(
                "L12", QueryCategory.LOCATION, "complex", "clear",
                "Where did I take beach sunset photos with Ramani?",
                expectedMedia = QueryMediaType.PHOTOS,
                requiredPeople = setOf("Ravi", "Ramani"),
            ),

            AuditCase(
                "T01", QueryCategory.TIME, "simple", "clear",
                "When did I visit Goa?",
                expectedLocation = "Goa",
                requiresSemantic = false,
                forbidsSemantic = true,
                requiresDateSort = true,
            ),
            AuditCase(
                "T02", QueryCategory.TIME, "simple", "clear",
                "When was Ravi's birthday party?",
                requiredPeople = setOf("Ravi"),
                requiresDateSort = true,
            ),
            AuditCase(
                "T03", QueryCategory.TIME, "simple", "moderate",
                "What date was the beach picnic?",
                requiresDateSort = true,
            ),
            AuditCase(
                "T04", QueryCategory.TIME, "medium", "clear",
                "When did Meghana and I go hiking?",
                requiredPeople = setOf("Meghana"),
                requiresDateSort = true,
            ),
            AuditCase(
                "T05", QueryCategory.TIME, "medium", "clear",
                "When was the last time I photographed the red car?",
                requiresDateSort = true,
            ),
            AuditCase(
                "T06", QueryCategory.TIME, "complex", "clear",
                "On which dates did we visit national parks last year?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2025-01-01",
                expectedToDate = "2025-12-31",
                requiresDateSort = true,
            ),
            AuditCase(
                "T07", QueryCategory.TIME, "complex", "clear",
                "When did Ravi appear with Ramani but not Meghana?",
                requiredPeople = setOf("Ravi", "Ramani"),
                excludedPeople = setOf("Meghana"),
                requiresSemantic = false,
                forbidsSemantic = true,
                requiresDateSort = true,
            ),
            AuditCase(
                "T08", QueryCategory.TIME, "medium", "ambiguous",
                "What time of day did we reach the mountain campsite?",
                requiresDateSort = true,
            ),
            AuditCase(
                "T09", QueryCategory.TIME, "complex", "clear",
                "When was the earliest sunset photo in Goa after June 2025?",
                dateExpectation = DateExpectation.OPEN_ENDED,
                expectedFromDate = "2025-07-01",
                requiresBlankToDate = true,
                expectedMedia = QueryMediaType.PHOTOS,
                expectedLocation = "Goa",
                requiresDateSort = true,
            ),
            AuditCase(
                "T10", QueryCategory.TIME, "complex", "clear",
                "Between January and March 2025, on which day did the whole team " +
                    "meet at the Hyderabad offsite without Ravi?",
                dateExpectation = DateExpectation.ANY_BOUNDED,
                expectedFromDate = "2025-01-01",
                expectedToDate = "2025-03-31",
                excludedPeople = setOf("Ravi"),
                expectedLocation = "Hyderabad",
                requiresDateSort = true,
            ),
        )

        fun rootMessage(error: Throwable): String {
            var current = error
            while (current.cause != null && current.cause !== current) {
                current = current.cause!!
            }
            return current.message ?: current::class.java.simpleName
        }

        fun Throwable.isFailedRepair(): Boolean =
            this is IllegalStateException &&
                message.orEmpty().contains("Gemma 4 E4B could not compile this query")
    }
}
