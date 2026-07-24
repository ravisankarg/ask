package com.ravi.askgalaxy

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Structured constraints that must be satisfied by every returned item. */
data class QueryScope(
    val time: QueryTimeScope? = null,
    val locationHint: String = "",
) {
    val isActive: Boolean
        get() = time != null || locationHint.isNotBlank()

    fun matches(
        media: GalleryMedia,
        locationMatcher: ((actual: String?, requested: String) -> Boolean)? = null,
    ): Boolean {
        val timestamp = media.dateTakenMs ?: media.dateModifiedSeconds.takeIf { it > 0L }?.times(1000L)
        if (time != null && (timestamp == null || !time.matches(timestamp))) return false
        if (locationHint.isNotBlank() && !(locationMatcher?.invoke(media.location, locationHint)
                ?: locationMatches(media.location, locationHint))) return false
        return true
    }

    private fun locationMatches(actual: String?, requested: String): Boolean {
        val normalizedActual = normalize(actual.orEmpty())
        val normalizedRequested = normalize(requested)
        if (normalizedActual.isBlank() || normalizedRequested.isBlank()) return false
        if (normalizedActual.contains(normalizedRequested)) return true
        val requestedTokens = normalizedRequested.split(' ').filter { it.length > 1 }
        return requestedTokens.isNotEmpty() && requestedTokens.all(normalizedActual::contains)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}.+-]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

enum class RelativeTimeWindow {
    TODAY,
    YESTERDAY,
    THIS_WEEK,
    LAST_WEEK,
    THIS_MONTH,
    LAST_MONTH,
    THIS_YEAR,
    LAST_YEAR,
}

data class QueryTimeScope(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val relativeWindow: RelativeTimeWindow? = null,
    val fromDateInclusive: LocalDate? = null,
    val toDateInclusive: LocalDate? = null,
) {
    fun matches(timestampMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): Boolean {
        val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
        val date = dateTime.toLocalDate()
        if (fromDateInclusive != null && date.isBefore(fromDateInclusive)) return false
        if (toDateInclusive != null && date.isAfter(toDateInclusive)) return false
        relativeWindow?.let { window ->
            val today = LocalDate.now(zoneId)
            val thisWeekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
            return when (window) {
                RelativeTimeWindow.TODAY -> date == today
                RelativeTimeWindow.YESTERDAY -> date == today.minusDays(1)
                RelativeTimeWindow.THIS_WEEK -> !date.isBefore(thisWeekStart) && !date.isAfter(today)
                RelativeTimeWindow.LAST_WEEK -> {
                    val lastWeekStart = thisWeekStart.minusWeeks(1)
                    !date.isBefore(lastWeekStart) && date.isBefore(thisWeekStart)
                }
                RelativeTimeWindow.THIS_MONTH -> date.year == today.year && date.monthValue == today.monthValue
                RelativeTimeWindow.LAST_MONTH -> {
                    val lastMonth = today.minusMonths(1)
                    date.year == lastMonth.year && date.monthValue == lastMonth.monthValue
                }
                RelativeTimeWindow.THIS_YEAR -> date.year == today.year
                RelativeTimeWindow.LAST_YEAR -> date.year == today.year - 1
            }
        }
        if (year != null && date.year != year) return false
        if (month != null && date.monthValue != month) return false
        if (day != null && date.dayOfMonth != day) return false
        if (hour != null && dateTime.hour != hour) return false
        if (minute != null && dateTime.minute != minute) return false
        return true
    }
}

object QueryScopeParser {
    private val ISO_DATE = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?(?!\\d)")
    private val YEAR = Regex("(?<!\\d)(\\d{4})(?!\\d)")
    private val MONTH_NAME = Regex(
        "(?i)\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b",
    )
    private val MONTH_DAY_YEAR = Regex(
        "(?i)\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?\\b",
    )
    // A bare integer is not a clock time: "top 3 photos" must not become a
    // 03:00 hard scope. Require either a colon or an explicit meridiem.
    private val CLOCK = Regex("(?i)(?<!\\d)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")

    fun parse(
        timeHint: String,
        locationHint: String,
        fromDate: String = "",
        toDate: String = "",
    ): QueryScope {
        val explicitFrom = parseIsoLocalDate(fromDate)
        val explicitTo = parseIsoLocalDate(toDate)
        val parsedTime = parseTime(timeHint)
        return QueryScope(
            time = if (explicitFrom != null || explicitTo != null) {
                (parsedTime ?: QueryTimeScope()).copy(
                    fromDateInclusive = explicitFrom,
                    toDateInclusive = explicitTo,
                )
            } else {
                parsedTime
            },
            locationHint = locationHint.trim(),
        )
    }

    /** Returns canonical ISO bounds for deterministic planner output. */
    fun dateBounds(
        value: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<String, String>? {
        val scope = parseTime(value) ?: return null
        val today = LocalDate.now(zoneId)
        val bounds = when (scope.relativeWindow) {
            RelativeTimeWindow.TODAY -> today to today
            RelativeTimeWindow.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
            RelativeTimeWindow.THIS_WEEK -> {
                val start = today.minusDays((today.dayOfWeek.value - 1).toLong())
                start to today
            }
            RelativeTimeWindow.LAST_WEEK -> {
                val thisStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
                thisStart.minusWeeks(1) to thisStart.minusDays(1)
            }
            RelativeTimeWindow.THIS_MONTH ->
                today.withDayOfMonth(1) to today
            RelativeTimeWindow.LAST_MONTH -> {
                val month = today.minusMonths(1)
                month.withDayOfMonth(1) to month.withDayOfMonth(month.lengthOfMonth())
            }
            RelativeTimeWindow.THIS_YEAR ->
                LocalDate.of(today.year, 1, 1) to today
            RelativeTimeWindow.LAST_YEAR ->
                LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
            null -> {
                val year = scope.year ?: today.year
                when {
                    scope.month != null && scope.day != null -> {
                        val date = runCatching {
                            LocalDate.of(year, scope.month, scope.day)
                        }.getOrNull() ?: return null
                        date to date
                    }
                    scope.month != null -> {
                        val start = LocalDate.of(year, scope.month, 1)
                        start to start.withDayOfMonth(start.lengthOfMonth())
                    }
                    scope.year != null ->
                        LocalDate.of(scope.year, 1, 1) to LocalDate.of(scope.year, 12, 31)
                    else -> return null
                }
            }
        }
        return bounds.first.toString() to bounds.second.toString()
    }

    /** Extracts an explicit from/to range before falling back to one date window. */
    fun explicitDateBoundsFromQuery(query: String): Pair<String, String>? {
        val isoDates = ISO_DATE.findAll(query).map { it.value.replace('/', '-').replace('.', '-') }.toList()
        if (isoDates.size >= 2 && Regex("(?i)\\b(from|between)\\b").containsMatchIn(query)) {
            val first = parseFlexibleIsoDate(isoDates[0])
            val second = parseFlexibleIsoDate(isoDates[1])
            if (first != null && second != null) {
                return minOf(first, second).toString() to maxOf(first, second).toString()
            }
        }
        val years = YEAR.findAll(query).mapNotNull { it.value.toIntOrNull() }.toList()
        if (years.size >= 2 && Regex("(?i)\\b(from|between)\\b").containsMatchIn(query)) {
            val first = years.min()
            val last = years.max()
            return LocalDate.of(first, 1, 1).toString() to LocalDate.of(last, 12, 31).toString()
        }
        return dateBounds(explicitTimeHintFromQuery(query))
    }

    /** Extracts only numeric or named date/time constraints; words like 'when' are ignored. */
    fun explicitTimeHintFromQuery(query: String): String {
        Regex("(?i)\\b(last|previous)\\s+month\\b").find(query)?.let { return "last month" }
        Regex("(?i)\\b(this|current)\\s+month\\b").find(query)?.let { return "this month" }
        Regex("(?i)\\b(last|previous)\\s+week\\b").find(query)?.let { return "last week" }
        Regex("(?i)\\b(this|current)\\s+week\\b").find(query)?.let { return "this week" }
        Regex("(?i)\\byesterday\\b").find(query)?.let { return "yesterday" }
        Regex("(?i)\\btoday\\b").find(query)?.let { return "today" }
        Regex("(?i)\\b(last|previous)\\s+year\\b").find(query)?.let { return "last year" }
        Regex("(?i)\\b(this|current)\\s+year\\b").find(query)?.let { return "this year" }
        ISO_DATE.find(query)?.value?.let { return it }
        MONTH_DAY_YEAR.find(query)?.value?.let { return it }
        MONTH_NAME.find(query)?.value?.let { month ->
            YEAR.find(query)?.value?.let { year -> return "$month $year" }
        }
        YEAR.find(query)?.value?.let { return it }
        CLOCK.find(query)
            ?.takeIf { match -> match.value.contains(':') || match.groupValues[3].isNotBlank() }
            ?.let { clock ->
                if (clock.groupValues[1].toIntOrNull()?.let { it <= 23 } == true) return clock.value
        }
        return ""
    }

    private fun parseTime(value: String): QueryTimeScope? {
        val hint = value.trim()
        if (hint.isBlank()) return null
        relativeTimeWindow(hint)?.let { return QueryTimeScope(relativeWindow = it) }
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null

        ISO_DATE.find(hint)?.let { match ->
            year = match.groupValues[1].toIntOrNull()
            month = match.groupValues[2].toIntOrNull()
            day = match.groupValues[3].takeIf(String::isNotBlank)?.toIntOrNull()
        } ?: MONTH_DAY_YEAR.find(hint)?.let { match ->
            month = monthNumber(match.groupValues[1])
            day = match.groupValues[2].toIntOrNull()
            year = match.groupValues[3].takeIf(String::isNotBlank)?.toIntOrNull()
        } ?: MONTH_NAME.find(hint)?.let { match ->
            month = monthNumber(match.groupValues[1])
        }

        if (year == null) year = YEAR.find(hint)?.groupValues?.get(1)?.toIntOrNull()
        val clock = CLOCK.find(hint)?.takeIf { match ->
            match.value.contains(':') || match.groupValues[3].isNotBlank()
        }
        var hour = clock?.groupValues?.get(1)?.toIntOrNull()
        val minute = clock?.groupValues?.get(2)?.takeIf(String::isNotBlank)?.toIntOrNull()
        val meridiem = clock?.groupValues?.get(3)?.lowercase(Locale.ROOT)
        if (hour != null && meridiem != null) {
            if (hour == 12) hour = 0
            if (meridiem == "pm") hour += 12
        }

        if (year == null && month == null && day == null && hour == null) return null
        if (year != null && (year !in 1970..2200)) return null
        if (month != null && month !in 1..12) return null
        if (day != null && day !in 1..31) return null
        if (hour != null && hour !in 0..23) return null
        if (minute != null && minute !in 0..59) return null
        return QueryTimeScope(year, month, day, hour, minute)
    }

    private fun parseIsoLocalDate(value: String): LocalDate? =
        value.trim().takeIf(String::isNotBlank)?.let(::parseFlexibleIsoDate)

    private fun parseFlexibleIsoDate(value: String): LocalDate? {
        val match = ISO_DATE.matchEntire(value.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].takeIf(String::isNotBlank)?.toIntOrNull() ?: 1
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun relativeTimeWindow(value: String): RelativeTimeWindow? = when {
        Regex("(?i)\\b(last|previous)\\s+month\\b").containsMatchIn(value) ->
            RelativeTimeWindow.LAST_MONTH
        Regex("(?i)\\b(this|current)\\s+month\\b").containsMatchIn(value) ->
            RelativeTimeWindow.THIS_MONTH
        Regex("(?i)\\b(last|previous)\\s+week\\b").containsMatchIn(value) ->
            RelativeTimeWindow.LAST_WEEK
        Regex("(?i)\\b(this|current)\\s+week\\b").containsMatchIn(value) ->
            RelativeTimeWindow.THIS_WEEK
        Regex("(?i)\\byesterday\\b").containsMatchIn(value) -> RelativeTimeWindow.YESTERDAY
        Regex("(?i)\\btoday\\b").containsMatchIn(value) -> RelativeTimeWindow.TODAY
        Regex("(?i)\\b(last|previous)\\s+year\\b").containsMatchIn(value) ->
            RelativeTimeWindow.LAST_YEAR
        Regex("(?i)\\b(this|current)\\s+year\\b").containsMatchIn(value) ->
            RelativeTimeWindow.THIS_YEAR
        else -> null
    }

    private fun monthNumber(name: String): Int? = when (name.lowercase(Locale.ROOT)) {
        "january" -> 1
        "february" -> 2
        "march" -> 3
        "april" -> 4
        "may" -> 5
        "june" -> 6
        "july" -> 7
        "august" -> 8
        "september" -> 9
        "october" -> 10
        "november" -> 11
        "december" -> 12
        else -> null
    }
}
