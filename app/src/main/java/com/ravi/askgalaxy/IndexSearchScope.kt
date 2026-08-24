package com.ravi.askgalaxy

import android.content.Context

internal enum class SearchIndexSource(
    val preferenceKey: String,
    val displayName: String,
    val documentSource: DocumentSource? = null,
) {
    GALLERY("gallery", "Gallery photos & videos"),
    CALENDAR("calendar", "Calendar", DocumentSource.CALENDAR),
    FILES("files", "My Files", DocumentSource.FILES),
    CALL_LOGS("call_logs", "Call logs", DocumentSource.CALL_LOGS),
    MESSAGES("messages", "Messages & SMS", DocumentSource.MESSAGES),
    CONTACTS("contacts", "Contacts", DocumentSource.CONTACTS),
}

internal data class IndexSearchScope(
    val galleryEnabled: Boolean,
    val documentSources: Set<DocumentSource>,
) {
    fun contains(source: SearchIndexSource): Boolean = when (source) {
        SearchIndexSource.GALLERY -> galleryEnabled
        else -> source.documentSource in documentSources
    }
}

internal object IndexSearchScopePolicy {
    fun fromPreferenceKeys(keys: Set<String>): IndexSearchScope {
        val enabled = SearchIndexSource.entries.filter { it.preferenceKey in keys }
        return IndexSearchScope(
            galleryEnabled = SearchIndexSource.GALLERY in enabled,
            documentSources = enabled.mapNotNullTo(LinkedHashSet()) { it.documentSource },
        )
    }

    fun galleryEnabledFor(mediaType: QueryMediaType?, scope: IndexSearchScope): Boolean =
        scope.galleryEnabled && (mediaType == null || mediaType.isGalleryType())

    fun documentSourcesFor(
        mediaType: QueryMediaType?,
        scope: IndexSearchScope,
    ): Set<DocumentSource> {
        val requested = mediaType?.documentSources() ?: DocumentSource.entries.toSet()
        return requested.intersect(scope.documentSources)
    }
}

internal object IndexSearchScopePreferences {
    private const val PREFERENCES = "ask_galaxy_search_scope"
    private const val ENABLED_SOURCES = "enabled_sources"

    fun selected(context: Context): IndexSearchScope {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val enabledKeys = if (preferences.contains(ENABLED_SOURCES)) {
            preferences.getStringSet(ENABLED_SOURCES, emptySet()).orEmpty().toSet()
        } else {
            SearchIndexSource.entries.mapTo(LinkedHashSet()) { it.preferenceKey }
        }
        return IndexSearchScopePolicy.fromPreferenceKeys(enabledKeys)
    }

    fun setEnabled(context: Context, source: SearchIndexSource, enabled: Boolean) {
        val current = selectedKeys(context).toMutableSet()
        if (enabled) current += source.preferenceKey else current -= source.preferenceKey
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(ENABLED_SOURCES, current)
            .apply()
    }

    private fun selectedKeys(context: Context): Set<String> {
        val preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )
        return if (preferences.contains(ENABLED_SOURCES)) {
            preferences.getStringSet(ENABLED_SOURCES, emptySet()).orEmpty().toSet()
        } else {
            SearchIndexSource.entries.mapTo(LinkedHashSet()) { it.preferenceKey }
        }
    }
}
