package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerFeatureAndFusionPolicyTest {
    @Test
    fun answer_setting_can_disable_generation_without_changing_planner_intent() {
        assertFalse(AnswerFeaturePolicy.effectiveNeedsAnswer(true, false))
        assertFalse(AnswerFeaturePolicy.effectiveNeedsAnswer(false, true))
        assertTrue(AnswerFeaturePolicy.effectiveNeedsAnswer(true, true))
    }

    @Test
    fun overall_fusion_is_bounded_to_100() {
        val gallery = (1L..120L).map(::media)
        val documents = (1..40).map { index ->
            document(index, fusionScore = 1f - index * 0.02f)
        }

        val overall = CrossEngineFusionPolicy.rank(gallery, documents)

        assertEquals(100, overall.size)
        assertTrue(overall.any { it is HybridSearchResult.Gallery })
        assertTrue(overall.any { it is HybridSearchResult.Document })
    }

    @Test
    fun standout_eight_can_promote_a_distinct_moment_from_beyond_rank_24() {
        val overall = (1L..100L).map { HybridSearchResult.Gallery(media(it)) }
        val groups = buildList {
            add(evidenceGroup("repeated", overall.take(25).map { (it as HybridSearchResult.Gallery).media }))
            overall.drop(25).forEachIndexed { index, result ->
                add(evidenceGroup("moment-${index + 1}", listOf((result as HybridSearchResult.Gallery).media)))
            }
        }

        val selected = StandoutResultPolicy.select(
            overall = overall,
            profile = StandoutIntentProfile(primary = StandoutIntent.SCENERY),
            episodeGroups = groups,
        )

        assertEquals(8, selected.size)
        assertEquals(overall.first(), selected.first().result)
        assertTrue(selected.all { it.result in overall })
        assertTrue(selected.any { overall.indexOf(it.result) >= 24 })
    }

    @Test
    fun document_intent_promotes_distinct_records_instead_of_repeated_chunks() {
        val repeated = (0 until 20).map { chunk ->
            HybridSearchResult.Document(
                document(index = chunk + 1, fusionScore = 1f).copy(
                    chunk = document(chunk + 1, 1f).chunk.copy(
                        recordKey = "same-record",
                        chunkNumber = chunk,
                    ),
                ),
            )
        }
        val distinct = (21..100).map { index ->
            HybridSearchResult.Document(document(index, fusionScore = 0.8f))
        }
        val overall = repeated + distinct

        val selected = StandoutResultPolicy.select(
            overall = overall,
            profile = StandoutIntentProfile(primary = StandoutIntent.DOCUMENT),
        )

        assertEquals(overall.first(), selected.first().result)
        assertTrue(selected.any { overall.indexOf(it.result) >= 20 })
        assertTrue(
            selected.map { (it.result as HybridSearchResult.Document).match.chunk.recordKey }
                .distinct().size > 1,
        )
    }

    @Test
    fun people_intent_can_explain_companion_variety_from_face_labels() {
        val overall = listOf(
            HybridSearchResult.Gallery(media(1).copy(personLabel = "Ravi")),
            HybridSearchResult.Gallery(media(2).copy(personLabel = "Ravi, Asha")),
            HybridSearchResult.Gallery(media(3).copy(personLabel = "Ravi, Arun")),
        )

        val selected = StandoutResultPolicy.select(
            overall = overall,
            profile = StandoutIntentProfile(
                primary = StandoutIntent.PEOPLE,
                hasPeopleConstraint = true,
            ),
        )

        assertTrue(selected.drop(1).any { it.badge == "Different companions" })
    }

    @Test
    fun standout_philosophy_is_derived_from_validated_query_plan() {
        assertEquals(StandoutIntent.SCENERY, profile().primary)
        assertEquals(
            StandoutIntent.PEOPLE,
            profile(category = QueryCategory.PERSON, people = listOf("Ravi")).primary,
        )
        assertEquals(
            StandoutIntent.DOCUMENT,
            profile(category = QueryCategory.DOC, keywords = listOf("invoice total")).primary,
        )
        assertEquals(
            StandoutIntent.LOCATION,
            profile(category = QueryCategory.LOCATION, location = "Mysore").primary,
        )
        assertEquals(
            StandoutIntent.TIME,
            profile(category = QueryCategory.TIME, fromDate = "2025-01-01").primary,
        )
    }

    @Test
    fun fusion_drops_non_personal_files_before_document_score_normalization() {
        val machineFile = document(
            index = 1,
            fusionScore = 100f,
            title = "build.gradle",
        )
        val personalFile = document(
            index = 2,
            fusionScore = 0.8f,
            title = "passport.pdf",
        )

        val ranked = CrossEngineFusionPolicy.rank(listOf(media(1)), listOf(machineFile, personalFile))

        assertFalse(ranked.any {
            it is HybridSearchResult.Document && it.match.chunk.title == "build.gradle"
        })
        assertTrue(ranked.any {
            it is HybridSearchResult.Document && it.match.chunk.title == "passport.pdf"
        })
    }

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

    private fun evidenceGroup(id: String, members: List<GalleryMedia>): EvidenceGroup = EvidenceGroup(
        episodeId = id,
        representative = members.first(),
        memberCount = members.size,
        matchedMemberCount = members.size,
        matchedMediaStoreIds = members.mapTo(linkedSetOf(), GalleryMedia::mediaStoreId),
        startTimeMs = null,
        endTimeMs = null,
        location = null,
    )

    private fun profile(
        category: QueryCategory = QueryCategory.SCENARY,
        people: List<String> = emptyList(),
        keywords: List<String> = emptyList(),
        location: String = "",
        fromDate: String = "",
    ): StandoutIntentProfile = StandoutIntentProfile.fromPlan(
        QueryPlan(
            semanticQueries = listOf("query"),
            keywordTerms = keywords,
            metadataQueries = emptyList(),
            personNames = people,
            ocrTerms = emptyList(),
            locationHint = location,
            fromDate = fromDate,
            queryCategory = category,
        ),
    )

    private fun document(
        index: Int,
        fusionScore: Float,
        title: String = "record-$index.pdf",
    ): DocumentMatch = DocumentMatch(
        chunk = DocumentChunk(
            source = DocumentSource.FILES,
            recordKey = "record-$index",
            chunkNumber = 0,
            title = title,
            text = "record $index",
        ),
        score = fusionScore,
        rank = index,
        fusionScore = fusionScore,
        cosineScore = fusionScore,
    )
}
