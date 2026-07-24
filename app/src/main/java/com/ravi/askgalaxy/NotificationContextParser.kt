package com.ravi.askgalaxy

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.util.Locale

/** Extracts only useful travel/order facts and rejects common authentication/bank secrets. */
object NotificationContextParser {
    fun parse(context: Context, status: StatusBarNotification): PersonalContextDraft? {
        if (status.packageName == context.packageName) return null
        if (status.isOngoing) return null
        val extras = status.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = buildString {
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()?.let(::append)
            if (isNotEmpty()) append('\n')
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()?.let(::append)
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
                if (isNotEmpty()) append('\n')
                append(line.toString().trim())
            }
        }.trim()
        if (title.isBlank() && body.isBlank()) return null
        val combined = "$title\n$body".take(MAX_TEXT_CHARS)
        if (SENSITIVE_PATTERN.containsMatchIn(combined)) return null
        val kind = classify(combined) ?: return null
        val sourceLabel = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(status.packageName, 0),
            ).toString()
        }.getOrDefault(status.packageName)
        if (FINANCIAL_SOURCE_PATTERN.containsMatchIn("${status.packageName} $sourceLabel")) {
            return null
        }
        val route = ROUTE_PATTERN.find(combined)?.let {
            "${it.groupValues[1].uppercase(Locale.ROOT)} → ${it.groupValues[2].uppercase(Locale.ROOT)}"
        }
        val amount = AMOUNT_PATTERN.find(combined)?.value?.trim()
        val reference = REFERENCE_PATTERN.find(combined)?.groupValues?.getOrNull(1)?.trim()
        val dateHint = DATE_PATTERN.find(combined)?.value?.trim()
        val merchant = title.takeIf { it.isNotBlank() && !GENERIC_TITLE_PATTERN.matches(it) }
            ?: sourceLabel
        return PersonalContextDraft(
            sourcePackage = status.packageName,
            sourceLabel = sourceLabel.take(MAX_SOURCE_CHARS),
            title = title.take(MAX_TITLE_CHARS),
            body = body.take(MAX_BODY_CHARS),
            kind = kind,
            eventTimeMs = status.postTime,
            merchant = merchant.take(MAX_SOURCE_CHARS),
            amount = amount?.take(MAX_FIELD_CHARS),
            reference = reference?.take(MAX_FIELD_CHARS),
            route = route?.take(MAX_FIELD_CHARS),
            dateHint = dateHint?.take(MAX_FIELD_CHARS),
        )
    }

    private fun classify(text: String): PersonalContextKind? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            TRAVEL_PATTERN.containsMatchIn(lower) -> PersonalContextKind.TRAVEL
            DELIVERY_PATTERN.containsMatchIn(lower) -> PersonalContextKind.DELIVERY
            APPOINTMENT_PATTERN.containsMatchIn(lower) -> PersonalContextKind.APPOINTMENT
            RECEIPT_PATTERN.containsMatchIn(lower) -> PersonalContextKind.RECEIPT
            else -> null
        }
    }

    private val TRAVEL_PATTERN = Regex(
        "\\b(flight|boarding pass|hotel|check[ -]?in|check[ -]?out|itinerary|reservation|airport|airline|train|pnr|gate|departure|arrival|trip)\\b",
    )
    private val RECEIPT_PATTERN = Regex(
        "\\b(receipt|invoice|bill|paid|payment|purchase|charged|order confirmed|total)\\b",
    )
    private val DELIVERY_PATTERN = Regex(
        "\\b(delivery|delivered|shipped|out for delivery|tracking|package)\\b",
    )
    private val APPOINTMENT_PATTERN = Regex(
        "\\b(appointment|consultation|reservation|scheduled|calendar|event|meeting)\\b",
    )
    private val SENSITIVE_PATTERN = Regex(
        "(?i)\\b(otp|one[ -]?time password|verification code|security code|passcode|password|cvv|cvv2|upi pin|pin)\\b|\\b(bank balance|account balance|debit card|credit card|account ending)\\b",
    )
    private val FINANCIAL_SOURCE_PATTERN = Regex(
        "(?i)\\b(bank|banking|finance|credit|debit)\\b",
    )
    private val AMOUNT_PATTERN = Regex(
        "(?i)(?:₹|rs\\.?|inr|\\$|€|£)\\s*[\\d,]+(?:\\.\\d{1,2})?",
    )
    private val REFERENCE_PATTERN = Regex(
        "(?i)\\b(?:pnr|booking|confirmation|reference|ref(?:erence)?)\\s*[:#-]?\\s*([a-z0-9]{5,})\\b",
    )
    private val ROUTE_PATTERN = Regex(
        "(?i)\\b([a-z]{3})\\s*(?:→|->|\\bto\\b|-)\\s*([a-z]{3})\\b",
    )
    private val DATE_PATTERN = Regex(
        "(?i)\\b(?:\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?|jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)[ ,/-]+\\d{1,2}(?:[ ,/-]+\\d{2,4})?\\b",
    )
    private val GENERIC_TITLE_PATTERN = Regex(
        "(?i)^(notification|new notification|payment|order|update|alert|reminder)$",
    )

    private const val MAX_TEXT_CHARS = 8_000
    private const val MAX_TITLE_CHARS = 240
    private const val MAX_BODY_CHARS = 2_000
    private const val MAX_SOURCE_CHARS = 160
    private const val MAX_FIELD_CHARS = 120
}
