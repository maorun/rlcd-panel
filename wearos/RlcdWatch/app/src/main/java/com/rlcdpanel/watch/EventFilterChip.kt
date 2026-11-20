package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.EventFilter

/** Pure helpers of the filter chip of the event log: the order of the categories and the chip text. */
object EventFilterChip {
    /** The selection order: `null` shows all rows, then each category of [EventFilter.Category]. */
    val ORDER: List<EventFilter.Category?> = listOf(null) + EventFilter.Category.values().toList()

    /** The category after [category]; the last one wraps to `null` (all). */
    fun next(category: EventFilter.Category?): EventFilter.Category? =
        ORDER[(ORDER.indexOf(category) + 1) % ORDER.size]

    /** The chip text: the category title (`All` for `null`) and the visible row count. */
    fun label(category: EventFilter.Category?, count: Int): String =
        "${category?.title ?: "All"} ($count)"
}
