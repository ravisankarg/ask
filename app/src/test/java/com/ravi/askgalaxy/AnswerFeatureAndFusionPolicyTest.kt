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
    fun overall_fusion_is_bounded_to_24_and_featured_is_its_first_eight() {
        val gallery = (1L..30L).map(::media)
        val documents = (1..16).map { index ->
            document(index, fusionScore = 1f - index * 0.02f)
        }

        val overall = CrossEngineFusionPolicy.rank(gallery, documents)
        val featured = overall.take(CrossEngineFusionPolicy.FEATURED_RESULT_LIMIT)

        assertEquals(24, overall.size)
        assertEquals(overall.subList(0, 8), featured)
        assertTrue(overall.any { it is HybridSearchResult.Gallery })
        assertTrue(overall.any { it is HybridSearchResult.Document })
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
