package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMetadataFusionTest {
    @Test
    fun metadata_only_rows_cannot_enter_semantic_results() {
        val reranked = SemanticMetadataFusion.rerank(
            semanticScores = linkedMapOf(11L to 0.15f, 12L to 0.12f),
            metadataScores = mapOf(12L to 2f, 99L to 10f),
            semanticWeight = 0.68f,
            metadataWeight = 0.32f,
        )

        assertEquals(setOf(11L, 12L), reranked.keys)
        assertFalse(reranked.containsKey(99L))
        assertEquals(0.15f, reranked.getValue(11L), 0.0001f)
        assertEquals(0.4016f, reranked.getValue(12L), 0.0001f)
    }

    @Test
    fun metadata_without_a_vector_hit_produces_no_semantic_results() {
        val reranked = SemanticMetadataFusion.rerank(
            semanticScores = emptyMap(),
            metadataScores = mapOf(99L to 10f),
            semanticWeight = 0.68f,
            metadataWeight = 0.32f,
        )

        assertTrue(reranked.isEmpty())
    }
}
