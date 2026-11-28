package com.rlcdpanel.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PanelRendererTest {

    private companion object {
        const val CRC_OF_15000_ZERO_BYTES = 0xD17AFDBEL
    }

    private data class Vector(val name: String, val hex: String)

    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun fixtureFile(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            val candidate = File(dir, "tests/fixtures/ble_telemetry_vectors.txt")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("Could not locate tests/fixtures/ble_telemetry_vectors.txt")
    }

    private fun vector(name: String): TelemetryFrame {
        val line = fixtureFile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split("|").map { f -> f.trim() } }
            .first { it[0] == name }
        return TelemetryFrame.decode(hexBytes(line.last()))
    }

    private fun frameWithTiles(valid: List<Boolean>, cell: Int? = 80): TelemetryFrame =
        TelemetryFrame(
            sequence = 1,
            cellPercent = cell,
            alerts = AlertMask.NONE,
            tiles = List(TelemetryFrame.TOTAL_TILES) { i ->
                if (valid.getOrElse(i) { false }) TelemetryTile(20.0 + i, 50.0) else TelemetryTile(null, null)
            }
        )

    /** Same text drawn alone on a white buffer; a region of the full render must equal it. */
    private fun regionEquals(
        actual: PanelFramebuffer, text: String, x: Int, y: Int, scale: Int
    ): Boolean {
        val expected = PanelFramebuffer()
        expected.drawText(x, y, text, scale, true)
        val w = text.length * 6 * scale
        for (py in y until y + 7 * scale) {
            for (px in x until x + w) {
                if (actual.isBlack(px, py) != expected.isBlack(px, py)) return false
            }
        }
        return true
    }

    // --- Framebuffer packing and decode ---

    @Test
    fun decodeReadsOneSetPixelAtX3Y5() {
        val bytes = ByteArray(PanelFramebuffer.BYTE_COUNT)
        // byte_index = (3 / 2) * 75 + ((299 - 5) / 4) = 75 + 73 = 148
        // bit = 7 - (((299 - 5) % 4) * 2 + (3 % 2)) = 7 - (2 * 2 + 1) = 2
        bytes[148] = (1 shl 2).toByte()
        val image = PanelFramebuffer.decode(bytes)
        assertTrue(image.isBlack(3, 5))
        assertFalse(image.isBlack(2, 5))
        assertFalse(image.isBlack(3, 4))
        assertFalse(image.isBlack(4, 5))
        var black = 0
        for (y in 0 until PanelFramebuffer.HEIGHT) for (x in 0 until PanelFramebuffer.WIDTH) if (image.isBlack(x, y)) black++
        assertEquals(1, black)
    }

    @Test
    fun decodeRejects14999Bytes() {
        try {
            PanelFramebuffer.decode(ByteArray(14_999))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("14999"))
        }
    }

    @Test
    fun setPixelUsesTheSamePackingAsDecode() {
        val fb = PanelFramebuffer()
        fb.setPixel(3, 5, true)
        val bytes = fb.toBytes()
        assertEquals(PanelFramebuffer.BYTE_COUNT, bytes.size)
        assertEquals((1 shl 2).toByte(), bytes[148])
        assertTrue(PanelFramebuffer.decode(bytes).isBlack(3, 5))
    }

    @Test
    fun pixelOutsideTheImageIsWhiteAndIgnored() {
        val fb = PanelFramebuffer()
        fb.setPixel(400, 0, true)
        fb.setPixel(0, 300, true)
        fb.setPixel(-1, 0, true)
        assertTrue(fb.toBytes().all { it == 0.toByte() })
        assertFalse(fb.isBlack(400, 0))
    }

    // --- Font primitives ---

    @Test
    fun glyphAMatchesTheFirmwareBitmap() {
        val fb = PanelFramebuffer()
        fb.drawText(10, 20, "A", 1, true)
        // Column 0 of 'A' is 0x7E: rows 1 to 6 are set, row 0 is not.
        assertFalse(fb.isBlack(10, 20))
        for (row in 1..6) assertTrue("row $row", fb.isBlack(10, 20 + row))
        assertFalse(fb.isBlack(10, 27))
        // Column 5 is the 1 px gap.
        for (row in 0..7) assertFalse(fb.isBlack(15, 20 + row))
    }

    @Test
    fun textScaleTwoDoublesEachPixelAndAdvancesTwelve() {
        val fb = PanelFramebuffer()
        fb.drawText(0, 0, "II", 2, true)
        // 'I' column 2 is 0x7F: a full 7 row bar at x = 4..5.
        for (row in 0 until 14) assertTrue(fb.isBlack(4, row))
        // Second glyph starts at x = 12, so its bar sits at x = 16..17.
        assertTrue(fb.isBlack(16, 0))
        assertTrue(fb.isBlack(17, 13))
    }

    @Test
    fun lowercaseDrawsLikeUppercase() {
        val a = PanelFramebuffer().also { it.drawText(0, 0, "abc", 1, true) }
        val b = PanelFramebuffer().also { it.drawText(0, 0, "ABC", 1, true) }
        assertTrue(a.toBytes().contentEquals(b.toBytes()))
    }

    // --- Layout ---

    private fun rect(x: Int, y: Int, w: Int, h: Int) = PanelRenderer.TileRect(x, y, w, h)

    @Test
    fun layoutForOneTile() {
        assertEquals(listOf(rect(6, 36, 388, 256)), PanelRenderer.layoutTiles(1))
    }

    @Test
    fun layoutForFourTiles() {
        assertEquals(
            listOf(rect(6, 36, 190, 124), rect(204, 36, 190, 124), rect(6, 168, 190, 124), rect(204, 168, 190, 124)),
            PanelRenderer.layoutTiles(4)
        )
    }

    @Test
    fun layoutForSixTiles() {
        assertEquals(
            listOf(
                rect(6, 36, 190, 80), rect(204, 36, 190, 80),
                rect(6, 124, 190, 80), rect(204, 124, 190, 80),
                rect(6, 212, 190, 80), rect(204, 212, 190, 80)
            ),
            PanelRenderer.layoutTiles(6)
        )
    }

    @Test
    fun layoutForTwoThreeAndFiveTiles() {
        assertEquals(listOf(rect(6, 36, 190, 256), rect(204, 36, 190, 256)), PanelRenderer.layoutTiles(2))
        assertEquals(
            listOf(rect(6, 36, 388, 124), rect(6, 168, 190, 124), rect(204, 168, 190, 124)),
            PanelRenderer.layoutTiles(3)
        )
        assertEquals(
            listOf(
                rect(6, 36, 190, 124), rect(204, 36, 190, 124),
                rect(6, 168, 124, 124), rect(138, 168, 124, 124), rect(270, 168, 124, 124)
            ),
            PanelRenderer.layoutTiles(5)
        )
    }

    @Test
    fun layoutClampsTheCountToOneToSix() {
        assertEquals(1, PanelRenderer.layoutTiles(0).size)
        assertEquals(6, PanelRenderer.layoutTiles(9).size)
    }

    @Test
    fun tileStyleFollowsTheFirmwareRule() {
        assertEquals(PanelRenderer.TileStyle.LARGE, PanelRenderer.tileStyle(388, 256))
        assertEquals(PanelRenderer.TileStyle.STANDARD, PanelRenderer.tileStyle(190, 124))
        assertEquals(PanelRenderer.TileStyle.STANDARD, PanelRenderer.tileStyle(388, 124))
        assertEquals(PanelRenderer.TileStyle.COMPACT, PanelRenderer.tileStyle(190, 80))
        assertEquals(PanelRenderer.TileStyle.COMPACT, PanelRenderer.tileStyle(124, 124))
    }

    // --- Tile selection ---

    @Test
    fun onboardTileAlwaysCountsAndInvalidSlotsDoNot() {
        val frame = frameWithTiles(listOf(false, true, false, true))
        assertEquals(listOf(0, 1, 3), PanelRenderer.tileIndices(frame, PanelConfig()))
    }

    @Test
    fun configuredSlotWithoutReadingKeepsItsTile() {
        val frame = frameWithTiles(listOf(true))
        val config = PanelConfig(mapOf("slot2_mac" to "AA:BB:CC:DD:EE:02", "slot3_name" to "Bad"))
        assertEquals(listOf(0, 2, 3), PanelRenderer.tileIndices(frame, config))
    }

    @Test
    fun disabledSlotGetsNoTile() {
        val frame = frameWithTiles(listOf(true, true))
        val config = PanelConfig(mapOf("slot1_enabled" to "0"))
        assertEquals(listOf(0), PanelRenderer.tileIndices(frame, config))
    }

    @Test
    fun tileCountIsCappedAtSix() {
        val frame = frameWithTiles(List(11) { true })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), PanelRenderer.tileIndices(frame, PanelConfig()))
    }

    // --- Render against the telemetry vectors ---

    @Test
    fun oneTileVectorDrawsOneLargeTileWithBigText() {
        val fb = PanelRenderer.render(vector("one_tile_onboard"), PanelConfig())
        // 388 x 256 tile: text scale 4, first line at y = 36 + 24 + 6.
        assertTrue(regionEquals(fb, "20.0 C", 6 + 12, 36 + 30, 4))
        // Tile frame: left border pixel and the title band line.
        assertTrue(fb.isBlack(6, 100))
        assertTrue(fb.isBlack(100, 36 + 24))
        assertTrue(regionEquals(fb, "ONBOARD SHTC3", 6 + 8, 36 + 6, 2))
    }

    @Test
    fun sixValidVectorDrawsSixCompactTilesWithTheirValues() {
        val frame = vector("six_valid")
        val fb = PanelRenderer.render(frame, PanelConfig())
        val rects = PanelRenderer.layoutTiles(6)
        val expectedTemp = listOf("21.5 C", "-3.2 C", "30.0 C", "0.0 C", "18.2 C", "25.5 C")
        val expectedRh = listOf("45 %", "60 %", "100 %", "0 %", "55 %", "62 %")
        for (i in 0 until 6) {
            val r = rects[i]
            assertTrue("temp $i", regionEquals(fb, expectedTemp[i], r.x + 12, r.y + 30, 2))
            assertTrue("rh $i", regionEquals(fb, expectedRh[i], r.x + 12, r.y + 48, 2))
        }
    }

    @Test
    fun fourTileVectorDrawsStandardTilesWithFirmwareText() {
        val fb = PanelRenderer.render(vector("four_tiles"), PanelConfig())
        val rects = PanelRenderer.layoutTiles(4)
        val temps = listOf("21.0", "19.0", "18.0", "22.5")
        val rhs = listOf("45", "50", "55", "48")
        for (i in 0 until 4) {
            val r = rects[i]
            assertEquals(PanelRenderer.TileStyle.STANDARD, PanelRenderer.tileStyle(r.w, r.h))
            assertTrue("temp $i", regionEquals(fb, "T: ${temps[i]} C", r.x + 12, r.y + 40, 2))
            assertTrue("rh $i", regionEquals(fb, "H: ${rhs[i]} %", r.x + 12, r.y + 68, 2))
        }
        // Onboard tile: dew point line at y + 86 and the battery text at y + 96 (scale 2 fits).
        assertTrue(regionEquals(fb, PanelRenderer.climateLine(true, 21.0f, 45.0f, 'C'), rects[0].x + 12, rects[0].y + 86, 1))
        assertTrue(regionEquals(fb, "BAT: 90 %", rects[0].x + 12, rects[0].y + 96, 2))
    }

    @Test
    fun fiveTileVectorMixesStandardAndCompactTiles() {
        val frame = vector("five_tiles")
        val fb = PanelRenderer.render(frame, PanelConfig())
        val rects = PanelRenderer.layoutTiles(5)
        assertTrue(regionEquals(fb, "T: 21.0 C", rects[0].x + 12, rects[0].y + 40, 2))
        // 124 x 124 compact tile: pad 8, temperature at y + 30, humidity at y + 48, dew point at y + 106.
        assertTrue(regionEquals(fb, "17.5 C", rects[4].x + 8, rects[4].y + 30, 2))
        assertTrue(regionEquals(fb, "60 %", rects[4].x + 8, rects[4].y + 48, 2))
        assertTrue(regionEquals(fb, PanelRenderer.climateLine(true, 17.5f, 60.0f, 'C'), rects[4].x + 6, rects[4].y + 106, 1))
    }

    @Test
    fun largeTileDrawsHumidityClimateAndBatteryLines() {
        val frame = frameWithTiles(listOf(true, true))
        val fb = PanelRenderer.render(frame, PanelConfig())
        val r = PanelRenderer.layoutTiles(2)[0]
        assertEquals(PanelRenderer.TileStyle.LARGE, PanelRenderer.tileStyle(r.w, r.h))
        // 190 wide: text scale 3, text height 21, curve box 60, gap 6.
        assertTrue(regionEquals(fb, "20.0 C", r.x + 12, r.y + 30, 3))
        assertTrue(regionEquals(fb, "50 %", r.x + 12, r.y + 123, 3))
        assertTrue(regionEquals(fb, PanelRenderer.climateLine(true, 20.0f, 50.0f, 'C'), r.x + 12, r.y + 216, 1))
        // "BAT: 4.20V 100%" does not fit 166 px at scale 2, so the battery line drops to scale 1.
        assertTrue(regionEquals(fb, "BAT: 80 %", r.x + 12, r.y + 229, 1))
    }

    @Test
    fun slotTileDrawsNoBatteryLine() {
        val frame = vector("five_tiles")
        val fb = PanelRenderer.render(frame, PanelConfig())
        val r = PanelRenderer.layoutTiles(5)[1]
        // Standard slot tile: nothing at the battery line positions y + 96 and y + 99.
        for (py in r.y + 96 until r.y + 113) for (px in r.x + 12 until r.x + r.w - 4) {
            assertFalse("($px,$py)", fb.isBlack(px, py))
        }
    }

    @Test
    fun noSignalPositionFollowsTheTileStyle() {
        val config = PanelConfig(mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01"))
        // Two tiles: LARGE (190 x 256) draws NO SIGNAL at y + h / 2 - 7.
        val two = PanelRenderer.render(frameWithTiles(listOf(true, false)), config)
        val large = PanelRenderer.layoutTiles(2)[1]
        assertTrue(regionEquals(two, "NO SIGNAL", large.x + 12, large.y + 128 - 7, 2))
        // Four tiles: STANDARD draws it at y + 56.
        val cfg4 = PanelConfig(
            mapOf("slot1_mac" to "AA:BB:CC:DD:EE:01", "slot2_mac" to "AA:BB:CC:DD:EE:02", "slot3_mac" to "AA:BB:CC:DD:EE:03")
        )
        val four = PanelRenderer.render(frameWithTiles(listOf(true, false, true, true)), cfg4)
        val std = PanelRenderer.layoutTiles(4)[1]
        assertTrue(regionEquals(four, "NO SIGNAL", std.x + 12, std.y + 56, 2))
        // Six tiles: COMPACT 190 x 80 draws it at y + 36.
        val cfg6 = PanelConfig((1..5).associate { "slot${it}_mac" to "AA:BB:CC:DD:EE:0$it" })
        val six = PanelRenderer.render(frameWithTiles(listOf(true, true, false, true, true, true)), cfg6)
        val compact = PanelRenderer.layoutTiles(6)[2]
        assertTrue(regionEquals(six, "NO SIGNAL", compact.x + 12, compact.y + 36, 2))
    }

    @Test
    fun fahrenheitConfigConvertsOnlyTheText() {
        val frame = vector("one_tile_onboard")
        val fb = PanelRenderer.render(frame, PanelConfig(mapOf("temp_unit" to "F")))
        assertTrue(regionEquals(fb, "68.0 F", 6 + 12, 36 + 30, 4))
    }

    @Test
    fun tieValuesRoundHalfToEvenLikePrintf() {
        // 18.25 prints as 18.2 and a humidity of 60.5 prints as 60, like the firmware snprintf.
        assertEquals("18.2", PanelRenderer.format1(18.25f))
        assertEquals("-3.2", PanelRenderer.format1(-3.25f))
        assertEquals("-0.0", PanelRenderer.format1(-0.04f))
        assertEquals("60", PanelRenderer.format0(60.5f))
        assertEquals("100", PanelRenderer.format0(100.0f))
    }

    @Test
    fun climateLineMatchesTheFirmwareFormat() {
        assertEquals("DP -- VPD --", PanelRenderer.climateLine(false, 20.0f, 50.0f, 'C'))
        assertEquals("DP 9.3C VPD 1.2K", PanelRenderer.climateLine(true, 20.0f, 50.0f, 'C'))
        assertEquals("DP 48.7F VPD 1.2K", PanelRenderer.climateLine(true, 20.0f, 50.0f, 'F'))
    }

    @Test
    fun configuredNameReplacesTheDefaultTitle() {
        val frame = vector("one_tile_onboard")
        val fb = PanelRenderer.render(frame, PanelConfig(mapOf("onboard_name" to "Buero")))
        assertTrue(regionEquals(fb, "BUERO", 6 + 8, 36 + 6, 2))
    }

    @Test
    fun headerShowsTitleAndCellPercentage() {
        val fb = PanelRenderer.render(vector("six_valid"), PanelConfig())
        // Black bar, white title text.
        assertTrue(fb.isBlack(300, 2))
        val title = PanelFramebuffer().also { it.fillRect(0, 0, 400, 28, true); it.drawText(10, 7, "RLCD CLIMATE PANEL", 2, false) }
        for (py in 7 until 21) for (px in 10 until 10 + 18 * 12) {
            assertEquals("($px,$py)", title.isBlack(px, py), fb.isBlack(px, py))
        }
        // "80%" ends at x = 364, the icon starts at x = 368.
        val pct = PanelFramebuffer().also { it.fillRect(0, 0, 400, 28, true); it.drawText(364 - 3 * 12, 7, "80%", 2, false) }
        for (py in 7 until 21) for (px in 364 - 36 until 364) {
            assertEquals("($px,$py)", pct.isBlack(px, py), fb.isBlack(px, py))
        }
        // Icon outline is white on the black bar.
        assertFalse(fb.isBlack(368, 8))
    }

    @Test
    fun unknownCellPercentageDrawsNoBatteryText() {
        val fb = PanelRenderer.render(frameWithTiles(listOf(true), cell = null), PanelConfig())
        // Whole bar right of the title stays black.
        for (px in 250 until 400) assertTrue(fb.isBlack(px, 10))
    }

    @Test
    fun differentReadingsGiveDifferentImages() {
        val a = PanelRenderer.render(vector("one_tile_onboard"), PanelConfig())
        val b = PanelRenderer.render(vector("six_valid"), PanelConfig())
        assertNotEquals(a.crc32(), b.crc32())
    }

    @Test
    fun crc32OfAnEmptyImageHasTheFixedIeeeValue() {
        // Fixed CRC-32 (IEEE) of 15,000 zero bytes (zlib.crc32).
        assertEquals(CRC_OF_15000_ZERO_BYTES, PanelFramebuffer().crc32())
    }

    // --- Badges from the STATUS LATCH line ---

    /**
     * Reference from `DashboardView::renderTile` (tools/render_display_preview.cpp, same calls): the
     * 9 x 9 "V" badge of tile 1 in the 4-tile layout at x = 204 + 108, y = 36 + 56; '#' is black.
     */
    private val ventBadgeReference = listOf(
        "#########",
        "##.###.##",
        "##.###.##",
        "##.###.##",
        "##.###.##",
        "##.###.##",
        "###.#.###",
        "####.####",
        "#########"
    )

    private fun badgeRows(fb: PanelFramebuffer, x: Int, y: Int): List<String> =
        (0 until 9).map { dy -> (0 until 9).joinToString("") { dx -> if (fb.isBlack(x + dx, y + dy)) "#" else "." } }

    @Test
    fun ventBitOfTileOneDrawsTheInvertedBadgeOfTheFirmware() {
        val frame = frameWithTiles(listOf(true, true, true, true))
        val latch = LatchState(ventilate = 0b10, dry = 0, batteryLow = 0, stale = 0)
        val fb = PanelRenderer.render(frame, PanelConfig(), latch)
        val r = PanelRenderer.layoutTiles(4)[1]
        assertEquals(ventBadgeReference, badgeRows(fb, r.x + 108, r.y + 56))
        // Tile 0 has no bit: its badge area stays white.
        val r0 = PanelRenderer.layoutTiles(4)[0]
        assertTrue(badgeRows(fb, r0.x + 108, r0.y + 56).none { it.contains('#') })
    }

    @Test
    fun noStatusReplyDrawsNoBadge() {
        val frame = frameWithTiles(listOf(true, true, true, true))
        val plain = PanelRenderer.render(frame, PanelConfig())
        assertEquals(plain.crc32(), PanelRenderer.render(frame, PanelConfig(), null).crc32())
        val r = PanelRenderer.layoutTiles(4)[1]
        assertTrue(badgeRows(plain, r.x + 108, r.y + 56).none { it.contains('#') })
    }

    @Test
    fun badgeLettersFollowTheFirmwareOrderAndMaskRules() {
        val latch = LatchState(
            ventilate = 0b10, dry = 0b10, batteryLow = 0, stale = 0,
            wall = 0b10, window = 0b10, receptionLow = 0b1, drift = 0b10, heat = 0b10
        )
        assertEquals("VDWCRS", PanelRenderer.badgeLetters(latch, 1))
        assertEquals("", PanelRenderer.badgeLetters(latch, 0))
        assertEquals("", PanelRenderer.badgeLetters(null, 1))
        assertEquals("", PanelRenderer.badgeLetters(latch, 11))
    }

    @Test
    fun secondBadgeSitsOnePixelRightOfTheFirst() {
        val frame = frameWithTiles(listOf(true, true, true, true))
        val latch = LatchState(ventilate = 0b10, dry = 0b10, batteryLow = 0, stale = 0)
        val fb = PanelRenderer.render(frame, PanelConfig(), latch)
        val r = PanelRenderer.layoutTiles(4)[1]
        assertEquals(ventBadgeReference, badgeRows(fb, r.x + 108, r.y + 56))
        assertTrue(fb.isBlack(r.x + 118, r.y + 56))
        assertTrue(fb.isBlack(r.x + 126, r.y + 64))
    }
}
