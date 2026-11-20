package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EventFilterTest {

    private fun fixtureLines(): List<String> =
        Fixtures.file("events_reply_sample.txt").readLines().filter { !it.startsWith("CRC32") }

    private fun swiftFile(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "macos/RlcdCompanion/Sources/RlcdProtocol/EventFilter.swift")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("EventFilter.swift not found")
    }

    private fun parseSwiftNames(): Map<EventFilter.Category, Set<String>> {
        val text = swiftFile().readText()
        val regex = Regex("""\.([a-z]+):\s*\[(.*?)\]""")
        val result = mutableMapOf<EventFilter.Category, Set<String>>()
        for (match in regex.findAll(text)) {
            val catName = match.groupValues[1].uppercase()
            val category = EventFilter.Category.valueOf(catName)
            val names = match.groupValues[2]
                .split(",")
                .map { it.trim().trim('"', ' ') }
                .filter { it.isNotEmpty() }
                .toSet()
            result[category] = names
        }
        return result
    }

    @Test
    fun filtersFixtureByCategories() {
        val log = EventsReply.parse(fixtureLines())
        assertEquals(18, log.rows.size)
        assertEquals(0, log.dropped)

        val alerts = EventFilter.apply(log.rows, setOf(EventFilter.Category.ALERTS))
        assertEquals(3, alerts.size)

        val sensors = EventFilter.apply(log.rows, setOf(EventFilter.Category.SENSORS))
        assertEquals(6, sensors.size)

        val power = EventFilter.apply(log.rows, setOf(EventFilter.Category.POWER))
        assertEquals(4, power.size)

        val config = EventFilter.apply(log.rows, setOf(EventFilter.Category.CONFIG))
        assertEquals(2, config.size)

        val other = EventFilter.apply(log.rows, setOf(EventFilter.Category.OTHER))
        assertEquals(3, other.size)

        val alertsAndPower = EventFilter.apply(log.rows, setOf(EventFilter.Category.ALERTS, EventFilter.Category.POWER))
        assertEquals(7, alertsAndPower.size)
    }

    @Test
    fun unlistedEventsAreOther() {
        assertEquals(EventFilter.Category.OTHER, EventFilter.category("CLOCK_SET"))
        assertEquals(EventFilter.Category.OTHER, EventFilter.category("FUTURE_EVENT"))
        assertEquals(EventFilter.Category.OTHER, EventFilter.category("UNKNOWN_CODE"))
    }

    @Test
    fun emptySetKeepsAllRowsAndPreservesOrder() {
        val log = EventsReply.parse(fixtureLines())
        val filtered = EventFilter.apply(log.rows, emptySet())
        assertEquals(18, filtered.size)
        assertEquals(log.rows, filtered)
    }

    @Test
    fun kotlinNamesMatchSwiftNames() {
        val swiftNames = parseSwiftNames()
        assertEquals(swiftNames, EventFilter.NAMES)
    }
}
