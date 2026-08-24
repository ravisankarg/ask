package com.ravi.askgalaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexSearchScopePolicyTest {
    @Test
    fun preference_keys_map_to_exact_selected_indexes() {
        val scope = IndexSearchScopePolicy.fromPreferenceKeys(
            setOf("gallery", "calendar", "call_logs"),
        )

        assertTrue(scope.galleryEnabled)
        assertEquals(setOf(DocumentSource.CALENDAR, DocumentSource.CALL_LOGS), scope.documentSources)
        assertFalse(scope.contains(SearchIndexSource.FILES))
        assertFalse(scope.contains(SearchIndexSource.MESSAGES))
    }

    @Test
    fun explicit_phone_source_intent_searches_only_that_selected_index() {
        val scope = IndexSearchScope(
            galleryEnabled = true,
            documentSources = DocumentSource.entries.toSet(),
        )

        assertFalse(IndexSearchScopePolicy.galleryEnabledFor(QueryMediaType.CALENDAR, scope))
        assertEquals(
            setOf(DocumentSource.CALENDAR),
            IndexSearchScopePolicy.documentSourcesFor(QueryMediaType.CALENDAR, scope),
        )
        assertFalse(IndexSearchScopePolicy.galleryEnabledFor(QueryMediaType.CALL_LOGS, scope))
        assertEquals(
            setOf(DocumentSource.CALL_LOGS),
            IndexSearchScopePolicy.documentSourcesFor(QueryMediaType.CALL_LOGS, scope),
        )
    }

    @Test
    fun generic_search_uses_only_user_selected_indexes() {
        val scope = IndexSearchScope(
            galleryEnabled = false,
            documentSources = setOf(DocumentSource.FILES, DocumentSource.MESSAGES),
        )

        assertFalse(IndexSearchScopePolicy.galleryEnabledFor(null, scope))
        assertEquals(
            setOf(DocumentSource.FILES, DocumentSource.MESSAGES),
            IndexSearchScopePolicy.documentSourcesFor(null, scope),
        )
    }

    @Test
    fun explicit_gallery_and_file_browse_respect_their_own_toggles() {
        val scope = IndexSearchScope(
            galleryEnabled = true,
            documentSources = setOf(DocumentSource.FILES),
        )

        assertTrue(IndexSearchScopePolicy.galleryEnabledFor(QueryMediaType.PHOTOS, scope))
        assertTrue(IndexSearchScopePolicy.documentSourcesFor(QueryMediaType.PHOTOS, scope).isEmpty())
        assertFalse(IndexSearchScopePolicy.galleryEnabledFor(QueryMediaType.FILES, scope))
        assertEquals(
            setOf(DocumentSource.FILES),
            IndexSearchScopePolicy.documentSourcesFor(QueryMediaType.FILES, scope),
        )
    }
}
