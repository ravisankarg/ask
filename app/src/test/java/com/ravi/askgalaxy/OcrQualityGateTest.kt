package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityGateTest {
    @Test
    fun isolatedShortNumericHallucinationIsRejected() {
        val result = OcrQualityGate.select(
            listOf(RecognizedOcrLine("20", 0.99f)),
        )

        assertEquals("", result.text)
        assertEquals(0, result.lineCount)
        assertEquals(1, result.candidateLineCount)
    }

    @Test
    fun isolatedMixedNoiseIsRejected() {
        val result = OcrQualityGate.select(
            listOf(RecognizedOcrLine("0 a", 0.95f)),
        )

        assertEquals("", result.text)
    }

    @Test
    fun confidentStandaloneWordIsSearchable() {
        val result = OcrQualityGate.select(
            listOf(RecognizedOcrLine("PHARMACY", 0.91f)),
        )

        assertEquals("PHARMACY", result.text)
        assertEquals(1, result.lineCount)
    }

    @Test
    fun structuredDocumentKeepsShortPricesAndPunctuation() {
        val result = OcrQualityGate.select(
            listOf(
                RecognizedOcrLine("Food total", 0.86f),
                RecognizedOcrLine("₹5", 0.81f),
            ),
        )

        assertEquals("Food total\n₹5", result.text)
        assertEquals(2, result.lineCount)
    }

    @Test
    fun lowConfidenceTextIsRejected() {
        val result = OcrQualityGate.select(
            listOf(
                RecognizedOcrLine("Restaurant", 0.40f),
                RecognizedOcrLine("Total 900", 0.51f),
            ),
        )

        assertEquals("", result.text)
        assertEquals(0, result.candidateLineCount)
    }

    @Test
    fun repeatedDocumentValuesAreNotCollapsed() {
        val result = OcrQualityGate.select(
            listOf(
                RecognizedOcrLine("Item", 0.90f),
                RecognizedOcrLine("₹100", 0.88f),
                RecognizedOcrLine("₹100", 0.87f),
            ),
        )

        assertTrue(result.text.lines().count { it == "₹100" } == 2)
    }
}
