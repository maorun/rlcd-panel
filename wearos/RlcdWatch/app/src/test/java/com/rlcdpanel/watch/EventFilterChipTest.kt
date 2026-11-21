package com.rlcdpanel.watch

import com.rlcdpanel.watch.protocol.EventFilter.Category
import com.rlcdpanel.watch.protocol.EventRow
import org.junit.Assert.assertEquals
import org.junit.Test

class EventFilterChipTest {
    @Test
    fun chipOrderIsAllAlertsSensorsClimatePowerConfigOther() {
        val seen = mutableListOf<Category?>()
        var current: Category? = null
        repeat(7) { seen += current; current = EventFilterChip.next(current) }
        assertEquals(
            listOf(null, Category.ALERTS, Category.SENSORS, Category.CLIMATE, Category.POWER, Category.CONFIG, Category.OTHER),
            seen
        )
        assertEquals(null, current)
    }

    @Test
    fun labelShowsTitleAndCount() {
        assertEquals("All (16)", EventFilterChip.label(null, 16))
        assertEquals("Alerts (3)", EventFilterChip.label(Category.ALERTS, 3))
    }

    @Test
    fun visibleRowsKeepOrderNewestFirstAndLimit() {
        val rows = (1..10).map { EventRow("2026-11-18 09:00:00", if (it % 2 == 0) "RESET" else "SLOT_ASSIGN", it) }
        val state = EventLogState(rows = rows, loaded = true)
        assertEquals(listOf(10, 9, 8, 7, 6, 5, 4, 3), state.visibleRows(null).map { it.detail })
        assertEquals(listOf(10, 8, 6, 4, 2), state.visibleRows(Category.POWER).map { it.detail })
    }
}
