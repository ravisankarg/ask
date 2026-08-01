package com.ravi.askgalaxy

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Event photos are the source of truth for an event-date question. This is
 * deliberately different from a birth-date document question: when someone
 * asks when a birthday, wedding, anniversary, or party happened, the capture
 * day of the matched event photos is the requested answer.
 */
object EventPhotoDateGrounding {
    private val eventTerm = Regex(
        "\\b(?:birthday|anniversary|wedding|marriage|engagement|party|celebration|" +
            "festival|ceremony|reception|baby\\s+shower)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val asksForDate = Regex(
        "\\b(?:when|date|dated|day|time|year)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM uuuu")

    fun applies(query: String): Boolean =
        asksForDate.containsMatchIn(query) && eventTerm.containsMatchIn(query)

    fun constrainAnswer(query: String, draft: String, records: List<GalleryMedia>): String {
        if (!applies(query)) return draft
        val dates = records.asSequence()
            .mapNotNull { media ->
                media.dateTakenMs ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1_000L)
            }
            .map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            .distinct()
            .sorted()
            .map(dateFormat::format)
            .toList()
        if (dates.isEmpty()) return draft
        return if (dates.size == 1) {
            "The matching event photos were taken on ${dates.single()}."
        } else {
            "The matching event photos were taken on ${dates.joinToString(", ")}."
        }
    }
}
