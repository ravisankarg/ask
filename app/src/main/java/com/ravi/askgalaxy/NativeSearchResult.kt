package com.ravi.askgalaxy

/** One native TurboQuant search result, returned in descending score order. */
data class NativeSearchResult(
    val ids: LongArray,
    val scores: FloatArray,
)
