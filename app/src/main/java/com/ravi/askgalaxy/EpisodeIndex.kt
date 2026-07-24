package com.ravi.askgalaxy

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Minimal persisted inputs used to derive chronological gallery episodes. */
data class EpisodeMediaSeed(
    val media: GalleryMedia,
    val personClusterIds: Set<String>,
) {
    val timestampMs: Long
        get() = media.dateTakenMs
            ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)
            ?: 0L
}

/** Durable episode header plus its ordered members. */
data class IndexedPhotoEpisode(
    val episodeId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val location: String?,
    val representativeMediaStoreId: Long,
    val memberMediaStoreIds: List<Long>,
    val personClusterIds: Set<String>,
)

data class EpisodeMembership(
    val episodeId: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val location: String?,
    val representativeMediaStoreId: Long,
    val memberCount: Int,
)

/**
 * Rebuildable preprocessing-time episode index.
 *
 * Episodes are primarily chronological, with place and person overlap used to
 * bridge a longer outing or trip. Close bursts can group without identity
 * metadata, while multi-day grouping requires both a stable place and at
 * least one recurring anonymous face cluster. This keeps the derived index
 * useful before the user names any face group.
 */
class EpisodeIndexer(
    private val database: GalleryDatabase,
) {
    fun rebuildBlocking(): Int {
        val seeds = database.episodeMediaSeeds()
            .filter { it.timestampMs > 0L }
            .sortedWith(
                compareBy<EpisodeMediaSeed> { it.timestampMs }
                    .thenBy { it.media.mediaStoreId },
            )
        val frozen = groupSeeds(seeds)
        database.replacePhotoEpisodes(frozen)
        return frozen.size
    }

    private class MutableEpisode(first: EpisodeMediaSeed) {
        private val members = ArrayList<EpisodeMediaSeed>().apply { add(first) }
        private val people = LinkedHashSet(first.personClusterIds)
        private var representative = first
        private var representativeQuality = quality(first)

        val startTimeMs: Long
            get() = members.first().timestampMs
        val endTimeMs: Long
            get() = members.last().timestampMs

        fun compatibility(next: EpisodeMediaSeed): Float? {
            val gapMs = next.timestampMs - endTimeMs
            if (gapMs < 0L || gapMs > MAX_EPISODE_GAP_MS) return null
            if (next.timestampMs - startTimeMs > MAX_EPISODE_SPAN_MS) return null

            val samePlace = locationsCompatible(primaryLocation(), comparisonLocation(next))
            val placeConflict = locationsConflict(primaryLocation(), comparisonLocation(next))
            val personOverlap = people.isNotEmpty() &&
                next.personClusterIds.isNotEmpty() &&
                people.any(next.personClusterIds::contains)
            val eligible = when {
                gapMs <= BURST_GAP_MS -> !placeConflict || personOverlap
                gapMs <= OUTING_GAP_MS -> samePlace || personOverlap
                else -> samePlace && personOverlap
            }
            if (!eligible) return null

            val recency = 1f - gapMs.toFloat() / MAX_EPISODE_GAP_MS.toFloat()
            return recency * 0.55f +
                (if (samePlace) 0.25f else 0f) +
                (if (personOverlap) 0.20f else 0f)
        }

        fun add(seed: EpisodeMediaSeed) {
            members += seed
            people += seed.personClusterIds
            val seedQuality = quality(seed)
            if (seedQuality > representativeQuality) {
                representative = seed
                representativeQuality = seedQuality
            }
        }

        fun freeze(index: Int): IndexedPhotoEpisode {
            val firstId = members.first().media.mediaStoreId
            val episodeId = "episode-${startTimeMs}-$firstId-$index"
            return IndexedPhotoEpisode(
                episodeId = episodeId,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                location = members.asSequence().mapNotNull { location(it) }.firstOrNull(),
                representativeMediaStoreId = representative.media.mediaStoreId,
                memberMediaStoreIds = members.map { it.media.mediaStoreId },
                personClusterIds = people,
            )
        }

        private fun primaryLocation(): String? =
            members.asSequence().mapNotNull { comparisonLocation(it) }.firstOrNull()

        companion object {
            private fun quality(seed: EpisodeMediaSeed): Int =
                (if (seed.media.width > 0 && seed.media.height > 0) 1 else 0) +
                    (if (seed.personClusterIds.isNotEmpty()) 2 else 0) +
                    (if (!location(seed).isNullOrBlank()) 1 else 0)
        }
    }

    companion object {
        const val MAX_ACTIVE_EPISODES = 12
        const val BURST_GAP_MS = 8L * 60L * 60L * 1_000L
        const val OUTING_GAP_MS = 36L * 60L * 60L * 1_000L
        const val MAX_EPISODE_GAP_MS = 4L * 24L * 60L * 60L * 1_000L
        const val MAX_EPISODE_SPAN_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SAME_LOCATION_RADIUS_KM = 80.0
        val GPS_PATTERN = Regex(
            "(?i)(?:gps\\s*)?([+-]?\\d+(?:\\.\\d+)?)\\s*[,/ ]\\s*([+-]?\\d+(?:\\.\\d+)?)",
        )

        internal fun groupSeeds(
            seeds: List<EpisodeMediaSeed>,
        ): List<IndexedPhotoEpisode> {
            val episodes = ArrayList<MutableEpisode>()
            seeds.filter { it.timestampMs > 0L }
                .sortedWith(
                    compareBy<EpisodeMediaSeed> { it.timestampMs }
                        .thenBy { it.media.mediaStoreId },
                )
                .forEach { seed ->
                    val active = episodes.asReversed()
                        .asSequence()
                        .filter { seed.timestampMs - it.endTimeMs <= MAX_EPISODE_GAP_MS }
                        .take(MAX_ACTIVE_EPISODES)
                    val best = active
                        .mapNotNull { episode ->
                            episode.compatibility(seed)?.let { score -> episode to score }
                        }
                        .maxByOrNull { it.second }
                        ?.first
                    if (best == null) episodes += MutableEpisode(seed)
                    else best.add(seed)
                }
            return episodes.mapIndexed { index, episode -> episode.freeze(index) }
        }

        private fun location(seed: EpisodeMediaSeed): String? =
            (seed.media.locationName ?: seed.media.location)?.takeIf(String::isNotBlank)

        private fun comparisonLocation(seed: EpisodeMediaSeed): String? =
            listOfNotNull(seed.media.locationName, seed.media.location)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" | ")
                .takeIf(String::isNotBlank)

        private fun locationsCompatible(first: String?, second: String?): Boolean {
            val left = normalizeLocation(first.orEmpty())
            val right = normalizeLocation(second.orEmpty())
            if (left.isBlank() || right.isBlank()) return false
            if (left == right || left.contains(right) || right.contains(left)) return true
            val leftGps = parseGps(first.orEmpty())
            val rightGps = parseGps(second.orEmpty())
            return leftGps != null && rightGps != null &&
                distanceKm(leftGps.first, leftGps.second, rightGps.first, rightGps.second) <=
                SAME_LOCATION_RADIUS_KM
        }

        private fun locationsConflict(first: String?, second: String?): Boolean {
            if (first.isNullOrBlank() || second.isNullOrBlank()) return false
            return !locationsCompatible(first, second)
        }

        private fun normalizeLocation(value: String): String = value
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}.+-]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        private fun parseGps(value: String): Pair<Double, Double>? =
            GPS_PATTERN.find(value)?.let { match ->
                val latitude = match.groupValues[1].toDoubleOrNull() ?: return@let null
                val longitude = match.groupValues[2].toDoubleOrNull() ?: return@let null
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) null
                else latitude to longitude
            }

        private fun distanceKm(
            firstLatitude: Double,
            firstLongitude: Double,
            secondLatitude: Double,
            secondLongitude: Double,
        ): Double {
            val earthRadiusKm = 6_371.0
            val dLatitude = Math.toRadians(secondLatitude - firstLatitude)
            val dLongitude = Math.toRadians(secondLongitude - firstLongitude)
            val a = sin(dLatitude / 2) * sin(dLatitude / 2) +
                cos(Math.toRadians(firstLatitude)) * cos(Math.toRadians(secondLatitude)) *
                sin(dLongitude / 2) * sin(dLongitude / 2)
            return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
