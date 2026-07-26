package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelInstallerContractTest {
    @Test
    fun parsesContentRangeStart() {
        assertEquals(3_656_000_000L, ModelInstaller.contentRangeStart("bytes 3656000000-3656000100/3659530240"))
    }

    @Test
    fun rejectsMissingOrMalformedContentRange() {
        assertNull(ModelInstaller.contentRangeStart(null))
        assertNull(ModelInstaller.contentRangeStart("bytes 3656000000-3656000100/*"))
        assertNull(ModelInstaller.contentRangeStart("3656000000-3656000100/3659530240"))
    }
}
