package com.ravi.askgalaxy

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Greedy top-k selection across persisted visual order and indexed metadata.
 *
 * The native TurboQuant selector supplies a max-min visual ordering. This
 * second bounded pass balances that order with relevance, OCR vocabulary,
 * human-readable locations, people/media metadata, and chronological spread.
 */
object MultimodalDiversitySelector {
    fun select(
        candidates: List<GalleryMedia>,
        visualOrderIds: LongArray,
        maxCount: Int,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<GalleryMedia> {
        if (maxCount <= 0 || candidates.isEmpty()) return emptyList()
        val unique = candidates.distinctBy { it.mediaStoreId }
        if (unique.size <= maxCount) return unique

        val relevanceRank = unique.mapIndexed { index, media -> media.mediaStoreId to index }.toMap()
        val visualRank = visualOrderIds.mapIndexed { index, id -> id to index }.toMap()
        val remaining = unique.toMutableList()
        val selected = ArrayList<GalleryMedia>(maxCount)
        selected += remaining.removeAt(0)

        while (selected.size < maxCount && remaining.isNotEmpty()) {
            val next = remaining.maxWithOrNull(
                compareBy<GalleryMedia> { media ->
                    val relevance = rankScore(relevanceRank[media.mediaStoreId], unique.size)
                    val visual = rankScore(visualRank[media.mediaStoreId], visualOrderIds.size)
                    val ocr = textNovelty(media.ocrText, selected.map { it.ocrText })
                    val location = categoricalNovelty(locationKey(media), selected.map(::locationKey))
                    val metadata = metadataNovelty(media, selected)
                    val timeline = timelineNovelty(media, selected, zoneId)
                    relevance * RELEVANCE_WEIGHT +
                        visual * VISUAL_WEIGHT +
                        ocr * OCR_WEIGHT +
                        location * LOCATION_WEIGHT +
                        metadata * METADATA_WEIGHT +
                        timeline * TIMELINE_WEIGHT
                }.thenBy { media ->
                    -(relevanceRank[media.mediaStoreId] ?: Int.MAX_VALUE)
                },
            ) ?: break
            selected += next
            remaining.remove(next)
        }
        return selected
    }

    private fun rankScore(rank: Int?, count: Int): Float {
        if (rank == null || count <= 0) return 0f
        return (1f - rank.toFloat() / count.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private fun textNovelty(value: String, selected: List<String>): Float {
        val tokens = tokens(value)
        if (tokens.isEmpty()) return 0.35f
        val selectedTokens = selected.flatMapTo(linkedSetOf(), ::tokens)
        if (selectedTokens.isEmpty()) return 1f
        val overlap = tokens.intersect(selectedTokens).size.toFloat()
        val union = (tokens + selectedTokens).size.coerceAtLeast(1).toFloat()
        return (1f - overlap / union).coerceIn(0f, 1f)
    }

    private fun tokens(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 }
        .take(MAX_OCR_TOKENS)
        .toSet()

    private fun categoricalNovelty(value: String, selected: List<String>): Float = when {
        value.isBlank() -> 0.25f
        selected.none { it == value } -> 1f
        else -> 0f
    }

    private fun locationKey(media: GalleryMedia): String =
        (media.locationName ?: media.location).orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun metadataNovelty(media: GalleryMedia, selected: List<GalleryMedia>): Float {
        val people = media.personLabel.orEmpty().lowercase(Locale.ROOT)
        val type = media.mimeType.substringBefore('/')
        val dimensions = when {
            media.width <= 0 || media.height <= 0 -> "unknown"
            media.width > media.height -> "landscape"
            media.height > media.width -> "portrait"
            else -> "square"
        }
        val signature = "$people|$type|$dimensions"
        val selectedSignatures = selected.map {
            val selectedDimensions = when {
                it.width <= 0 || it.height <= 0 -> "unknown"
                it.width > it.height -> "landscape"
                it.height > it.width -> "portrait"
                else -> "square"
            }
            "${it.personLabel.orEmpty().lowercase(Locale.ROOT)}|${it.mimeType.substringBefore('/')}|$selectedDimensions"
        }
        return categoricalNovelty(signature, selectedSignatures)
    }

    private fun timelineNovelty(
        media: GalleryMedia,
        selected: List<GalleryMedia>,
        zoneId: ZoneId,
    ): Float {
        val timestamp = timestamp(media) ?: return 0.25f
        val date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
        val minimumDays = selected.mapNotNull(::timestamp)
            .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
            .minOfOrNull { selectedDate ->
                kotlin.math.abs(ChronoUnit.DAYS.between(date, selectedDate))
            } ?: return 1f
        return (minimumDays.toFloat() / TIMELINE_FULL_NOVELTY_DAYS).coerceIn(0f, 1f)
    }

    private fun timestamp(media: GalleryMedia): Long? =
        media.dateTakenMs ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1000L)

    private const val MAX_OCR_TOKENS = 48
    private const val TIMELINE_FULL_NOVELTY_DAYS = 180f
    private const val VISUAL_WEIGHT = 0.30f
    private const val RELEVANCE_WEIGHT = 0.25f
    private const val OCR_WEIGHT = 0.13f
    private const val LOCATION_WEIGHT = 0.12f
    private const val METADATA_WEIGHT = 0.10f
    private const val TIMELINE_WEIGHT = 0.10f
}
