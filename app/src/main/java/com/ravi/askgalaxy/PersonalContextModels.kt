package com.ravi.askgalaxy

/** A deliberately small set of useful, non-secret notification categories. */
enum class PersonalContextKind(
    val wire: String,
    val displayName: String,
    val icon: String,
) {
    TRAVEL("travel", "Travel", "✈"),
    RECEIPT("receipt", "Receipt", "▤"),
    DELIVERY("delivery", "Delivery", "⌁"),
    APPOINTMENT("appointment", "Appointment", "◷"),
    ;

    companion object {
        fun fromWire(value: String): PersonalContextKind =
            entries.firstOrNull { it.wire == value } ?: RECEIPT
    }
}

data class PersonalContextDraft(
    val sourcePackage: String,
    val sourceLabel: String,
    val title: String,
    val body: String,
    val kind: PersonalContextKind,
    val eventTimeMs: Long,
    val merchant: String? = null,
    val amount: String? = null,
    val reference: String? = null,
    val route: String? = null,
    val dateHint: String? = null,
)

data class PersonalContextItem(
    val id: Long,
    val sourcePackage: String,
    val sourceLabel: String,
    val title: String,
    val body: String,
    val kind: PersonalContextKind,
    val eventTimeMs: Long,
    val receivedAtMs: Long,
    val merchant: String? = null,
    val amount: String? = null,
    val reference: String? = null,
    val route: String? = null,
    val dateHint: String? = null,
)

data class PersonalContextMatch(
    val item: PersonalContextItem,
    val score: Float,
)
