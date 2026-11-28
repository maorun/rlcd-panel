package com.rlcdpanel.phone.protocol

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.zip.CRC32

/**
 * The 400 x 300 1-bit framebuffer of the panel (`rlcd_panel/st7305_framebuffer.h`).
 *
 * The packing is the one of the `ST7305` controller: column-major blocks of 2 x 4 pixels with an inverted Y,
 * `byte_index = (x / 2) * 75 + ((299 - y) / 4)` and `bit = 7 - (((299 - y) % 4) * 2 + (x % 2))`.
 * A set bit is a black pixel. A position outside the image is white and a write there is ignored.
 */
class PanelFramebuffer private constructor(private val buffer: ByteArray) {

    constructor() : this(ByteArray(BYTE_COUNT))

    private fun index(x: Int, y: Int): Int {
        val inverted = HEIGHT - 1 - y
        return (x shr 1) * BLOCKS_PER_COL + (inverted shr 2)
    }

    private fun mask(x: Int, y: Int): Int {
        val inverted = HEIGHT - 1 - y
        return 1 shl (7 - (((inverted and 3) shl 1) or (x and 1)))
    }

    fun setPixel(x: Int, y: Int, black: Boolean = true) {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return
        val i = index(x, y)
        val m = mask(x, y)
        buffer[i] = if (black) (buffer[i].toInt() or m).toByte() else (buffer[i].toInt() and m.inv()).toByte()
    }

    fun isBlack(x: Int, y: Int): Boolean {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return false
        return (buffer[index(x, y)].toInt() and mask(x, y)) != 0
    }

    fun clear(black: Boolean = false) {
        buffer.fill(if (black) 0xFF.toByte() else 0)
    }

    fun drawHLine(x: Int, y: Int, w: Int, black: Boolean = true) {
        if (y !in 0 until HEIGHT || x >= WIDTH) return
        for (i in 0 until w) {
            if (x + i >= WIDTH) break
            setPixel(x + i, y, black)
        }
    }

    fun drawVLine(x: Int, y: Int, h: Int, black: Boolean = true) {
        if (x !in 0 until WIDTH || y >= HEIGHT) return
        for (i in 0 until h) {
            if (y + i >= HEIGHT) break
            setPixel(x, y + i, black)
        }
    }

    fun drawRect(x: Int, y: Int, w: Int, h: Int, black: Boolean = true) {
        if (w <= 0 || h <= 0 || x >= WIDTH || y >= HEIGHT) return
        drawHLine(x, y, w, black)
        drawHLine(x, y + h - 1, w, black)
        drawVLine(x, y, h, black)
        drawVLine(x + w - 1, y, h, black)
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, black: Boolean = true) {
        if (w <= 0 || h <= 0 || x >= WIDTH || y >= HEIGHT) return
        for (dy in 0 until h) {
            if (y + dy >= HEIGHT) break
            drawHLine(x, y + dy, w, black)
        }
    }

    /** One 5 x 7 glyph at [scale]; lower case draws like upper case, an unknown character is blank. */
    fun drawChar(x: Int, y: Int, c: Char, scale: Int = 1, black: Boolean = true) {
        if (scale <= 0 || x >= WIDTH || y >= HEIGHT) return
        val glyph = PanelFont.glyph(c)
        for (col in 0 until 5) {
            val bits = glyph[col]
            for (row in 0 until 7) {
                if (((bits shr row) and 1) != 0) {
                    fillRect(x + col * scale, y + row * scale, scale, scale, black)
                }
            }
        }
    }

    /** Text with a 6 px advance at scale 1 (5 px glyph plus 1 px gap). */
    fun drawText(x: Int, y: Int, text: String, scale: Int = 1, black: Boolean = true) {
        if (scale <= 0 || x >= WIDTH || y >= HEIGHT) return
        var cursor = x
        for (c in text) {
            if (cursor >= WIDTH) break
            drawChar(cursor, y, c, scale, black)
            cursor += 6 * scale
        }
    }

    /** The 15,000 bytes in the packing of the panel. */
    fun toBytes(): ByteArray = buffer.copyOf()

    /** CRC-32 (IEEE) of the 15,000 bytes, the value of `St7305Framebuffer::crc32()`. */
    fun crc32(): Long = CRC32().also { it.update(buffer) }.value

    /** Opaque ARGB pixels, row-major: black `0xFF000000` and white `0xFFFFFFFF`. */
    fun toArgb(): IntArray {
        val out = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                out[y * WIDTH + x] = if (isBlack(x, y)) BLACK else WHITE
            }
        }
        return out
    }

    companion object {
        const val WIDTH = 400
        const val HEIGHT = 300
        const val BYTE_COUNT = 15_000
        const val BLACK: Int = 0xFF000000.toInt()
        const val WHITE: Int = 0xFFFFFFFF.toInt()
        private const val BLOCKS_PER_COL = 75

        /** Throws [IllegalArgumentException] for a body that does not hold exactly 15,000 bytes. */
        fun decode(bytes: ByteArray): PanelFramebuffer {
            require(bytes.size == BYTE_COUNT) { "screen.bin ${bytes.size} bytes" }
            return PanelFramebuffer(bytes.copyOf())
        }
    }
}

/** The 5 x 7 font of `St7305Framebuffer::glyphForChar`: five column bytes, bit 0 is the top row. */
private object PanelFont {
    private val BLANK = intArrayOf(0, 0, 0, 0, 0)
    private val GLYPHS: Map<Char, IntArray> = mapOf(
        '0' to intArrayOf(0x3E, 0x51, 0x49, 0x45, 0x3E),
        '1' to intArrayOf(0x00, 0x42, 0x7F, 0x40, 0x00),
        '2' to intArrayOf(0x42, 0x61, 0x51, 0x49, 0x46),
        '3' to intArrayOf(0x21, 0x41, 0x45, 0x4B, 0x31),
        '4' to intArrayOf(0x18, 0x14, 0x12, 0x7F, 0x10),
        '5' to intArrayOf(0x27, 0x45, 0x45, 0x45, 0x39),
        '6' to intArrayOf(0x3C, 0x4A, 0x49, 0x49, 0x30),
        '7' to intArrayOf(0x01, 0x71, 0x09, 0x05, 0x03),
        '8' to intArrayOf(0x36, 0x49, 0x49, 0x49, 0x36),
        '9' to intArrayOf(0x06, 0x49, 0x49, 0x29, 0x1E),
        'A' to intArrayOf(0x7E, 0x11, 0x11, 0x11, 0x7E),
        'B' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x36),
        'C' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x22),
        'D' to intArrayOf(0x7F, 0x41, 0x41, 0x22, 0x1C),
        'E' to intArrayOf(0x7F, 0x49, 0x49, 0x49, 0x41),
        'F' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x01),
        'G' to intArrayOf(0x3E, 0x41, 0x49, 0x49, 0x7A),
        'H' to intArrayOf(0x7F, 0x08, 0x08, 0x08, 0x7F),
        'I' to intArrayOf(0x00, 0x41, 0x7F, 0x41, 0x00),
        'J' to intArrayOf(0x20, 0x40, 0x41, 0x3F, 0x01),
        'K' to intArrayOf(0x7F, 0x08, 0x14, 0x22, 0x41),
        'L' to intArrayOf(0x7F, 0x40, 0x40, 0x40, 0x40),
        'M' to intArrayOf(0x7F, 0x02, 0x0C, 0x02, 0x7F),
        'N' to intArrayOf(0x7F, 0x04, 0x08, 0x10, 0x7F),
        'O' to intArrayOf(0x3E, 0x41, 0x41, 0x41, 0x3E),
        'P' to intArrayOf(0x7F, 0x09, 0x09, 0x09, 0x06),
        'Q' to intArrayOf(0x3E, 0x41, 0x51, 0x21, 0x5E),
        'R' to intArrayOf(0x7F, 0x09, 0x19, 0x29, 0x46),
        'S' to intArrayOf(0x46, 0x49, 0x49, 0x49, 0x31),
        'T' to intArrayOf(0x01, 0x01, 0x7F, 0x01, 0x01),
        'U' to intArrayOf(0x3F, 0x40, 0x40, 0x40, 0x3F),
        'V' to intArrayOf(0x1F, 0x20, 0x40, 0x20, 0x1F),
        'W' to intArrayOf(0x3F, 0x40, 0x38, 0x40, 0x3F),
        'X' to intArrayOf(0x63, 0x14, 0x08, 0x14, 0x63),
        'Y' to intArrayOf(0x07, 0x08, 0x70, 0x08, 0x07),
        'Z' to intArrayOf(0x61, 0x51, 0x49, 0x45, 0x43),
        '!' to intArrayOf(0x00, 0x00, 0x5F, 0x00, 0x00),
        '-' to intArrayOf(0x08, 0x08, 0x08, 0x08, 0x08),
        '.' to intArrayOf(0x00, 0x60, 0x60, 0x00, 0x00),
        ':' to intArrayOf(0x00, 0x36, 0x36, 0x00, 0x00),
        '~' to intArrayOf(0x04, 0x02, 0x04, 0x08, 0x04),
        '(' to intArrayOf(0x00, 0x1C, 0x22, 0x41, 0x00),
        ')' to intArrayOf(0x00, 0x41, 0x22, 0x1C, 0x00),
        '/' to intArrayOf(0x40, 0x30, 0x08, 0x06, 0x01),
        '%' to intArrayOf(0x23, 0x13, 0x08, 0x64, 0x62)
    )

    fun glyph(c: Char): IntArray = GLYPHS[c.uppercaseChar().takeIf { c in 'a'..'z' } ?: c] ?: BLANK
}

/**
 * Draws the climate dashboard of the panel (`DashboardView::render`, up to 6 tiles) from one telemetry frame.
 *
 * The frame holds the temperature and humidity of each tile and the cell percentage of the panel, nothing
 * else: the 24-hour curves, the trend marks, the clock and the stale age of the real screen need
 * panel state that the broadcast does not carry, so the preview leaves them out. The badges `V`, `D`, `W`,
 * `C`, `R` and `S` come from the `LATCH` line of the last `STATUS` reply; without it no badge is drawn.
 */
object PanelRenderer {
    const val HEADER_HEIGHT = 28
    const val MAX_TILES = 6

    private const val GRID_X = 6
    private const val GRID_Y = 36
    private const val GRID_W = 388
    private const val GRID_H = 256
    private const val GRID_GAP = 8

    data class TileRect(val x: Int, val y: Int, val w: Int, val h: Int)

    enum class TileStyle { STANDARD, COMPACT, LARGE }

    /** LARGE from 200 px height, COMPACT below 100 px height or 160 px width. */
    fun tileStyle(w: Int, h: Int): TileStyle = when {
        h >= 200 -> TileStyle.LARGE
        h < 100 || w < 160 -> TileStyle.COMPACT
        else -> TileStyle.STANDARD
    }

    /** The tile rectangles of `DashboardView::layoutTiles` for [count] tiles (clamped to 1 to 6). */
    fun layoutTiles(count: Int): List<TileRect> {
        val n = count.coerceIn(1, MAX_TILES)
        val halfW = (GRID_W - GRID_GAP) / 2
        val thirdW = (GRID_W - 2 * GRID_GAP) / 3
        val halfH = (GRID_H - GRID_GAP) / 2
        val thirdH = (GRID_H - 2 * GRID_GAP) / 3
        val col2X = GRID_X + halfW + GRID_GAP
        val row2Y = GRID_Y + halfH + GRID_GAP
        return when (n) {
            1 -> listOf(TileRect(GRID_X, GRID_Y, GRID_W, GRID_H))
            2 -> listOf(TileRect(GRID_X, GRID_Y, halfW, GRID_H), TileRect(col2X, GRID_Y, halfW, GRID_H))
            3 -> listOf(
                TileRect(GRID_X, GRID_Y, GRID_W, halfH),
                TileRect(GRID_X, row2Y, halfW, halfH),
                TileRect(col2X, row2Y, halfW, halfH)
            )
            4 -> listOf(
                TileRect(GRID_X, GRID_Y, halfW, halfH),
                TileRect(col2X, GRID_Y, halfW, halfH),
                TileRect(GRID_X, row2Y, halfW, halfH),
                TileRect(col2X, row2Y, halfW, halfH)
            )
            5 -> listOf(
                TileRect(GRID_X, GRID_Y, halfW, halfH),
                TileRect(col2X, GRID_Y, halfW, halfH)
            ) + (0 until 3).map { TileRect(GRID_X + it * (thirdW + GRID_GAP), row2Y, thirdW, halfH) }
            else -> (0 until 6).map {
                TileRect(if (it % 2 == 0) GRID_X else col2X, GRID_Y + (it / 2) * (thirdH + GRID_GAP), halfW, thirdH)
            }
        }
    }

    /**
     * Tile indices of the dashboard (0 onboard, 1 to 10 a slot), first page only (at most 6). The onboard
     * tile always counts. A slot counts with a valid reading, or with a fixed MAC or a name; a disabled
     * slot never does (`DashboardView::slotActive`).
     */
    fun tileIndices(frame: TelemetryFrame, config: PanelConfig): List<Int> =
        allTileIndices(frame, config).take(MAX_TILES)

    private fun allTileIndices(frame: TelemetryFrame, config: PanelConfig): List<Int> {
        val out = mutableListOf(0)
        for (slot in 1..10) {
            if (!config.slotEnabled(slot)) continue
            val hasReading = frame.tiles.getOrNull(slot)?.isValid == true
            if (hasReading || config.slotMac(slot) != null || config.slotName(slot) != null) out.add(slot)
        }
        return out
    }

    /** `printf("%.<digits>f")` of a float: the exact binary value, ties to even, a negative zero keeps its sign. */
    private fun printf(value: Float, digits: Int): String {
        var text = BigDecimal(value.toDouble()).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        if (java.lang.Float.floatToRawIntBits(value) < 0 && !text.startsWith("-")) text = "-$text"
        return text
    }

    fun format1(value: Float): String = printf(value, 1)

    fun format0(value: Float): String = printf(value, 0)

    private fun toDisplay(tempC: Float, unit: Char): Float = if (unit == 'F') tempC * 9.0f / 5.0f + 32.0f else tempC

    private fun unitOf(config: PanelConfig): Char = if (config.values["temp_unit"]?.trim() == "F") 'F' else 'C'

    /** "DP 9.3C VPD 1.2K", or "DP -- VPD --" for an unusable reading (`ClimateMath::format`). */
    fun climateLine(usable: Boolean, tempC: Float, humidityPct: Float, unit: Char): String {
        val dew = if (usable) ClimateMath.dewPointC(tempC.toDouble(), humidityPct.toDouble()) else null
        if (dew == null) return "DP -- VPD --"
        val vpd = (ClimateMath.saturationPressureKpa(tempC.toDouble()) * (1.0 - humidityPct / 100.0)).toFloat()
        return "DP ${format1(toDisplay(dew.toFloat(), unit))}${if (unit == 'F') 'F' else 'C'} VPD ${format1(vpd)}K"
    }

    /** Largest scale (1 to [maxScale]) at which [text] fits [maxW] px. */
    private fun fitScale(text: String, maxW: Int, maxScale: Int): Int {
        for (sc in maxScale downTo 2) {
            if (text.length * 6 * sc <= maxW + sc) return sc
        }
        return 1
    }

    private const val BADGE_SIZE = 9
    private const val BADGE_STEP = 10

    /** Badge letters of one tile in the firmware order `V D W C R S`; empty without a `LATCH` line. */
    fun badgeLetters(latch: LatchState?, tile: Int): String {
        if (latch == null || tile !in 0 until LatchState.SENSOR_COUNT) return ""
        fun bit(mask: Int?) = mask != null && (mask shr tile) and 1 == 1
        val sb = StringBuilder()
        if (bit(latch.ventilate)) sb.append('V')
        if (bit(latch.dry)) sb.append('D')
        if (bit(latch.window)) sb.append('W')
        if (bit(latch.wall)) sb.append('C')
        if (tile >= 1 && bit(latch.receptionLow?.let { it shl 1 })) sb.append('R')
        if (bit(latch.drift)) sb.append('S')
        return sb.toString()
    }

    private fun drawBadges(fb: PanelFramebuffer, letters: String, firstX: Int, y: Int, direction: Int) {
        letters.forEachIndexed { n, c ->
            val bx = firstX + direction * n * BADGE_STEP
            fb.fillRect(bx, y, BADGE_SIZE, BADGE_SIZE, true)
            fb.drawText(bx + 2, y + 1, c.toString(), 1, false)
        }
    }

    private fun title(index: Int, config: PanelConfig): String {
        if (index == 0) return config.values["onboard_name"]?.takeIf { it.isNotBlank() } ?: "ONBOARD SHTC3"
        return config.nameForMac(config.slotMac(index)) ?: "SENSOR $index"
    }

    /** Renders the panel for [frame]; tile names, the temperature unit and the slot rules come from [config]. */
    fun render(frame: TelemetryFrame, config: PanelConfig, latch: LatchState? = null): PanelFramebuffer {
        val fb = PanelFramebuffer()
        val indices = allTileIndices(frame, config)
        val headerTitle = if (indices.size > MAX_TILES) "RLCD CLIMATE 1/2" else "RLCD CLIMATE PANEL"
        renderHeader(fb, headerTitle, frame.cellPercent)
        val shown = indices.take(MAX_TILES)
        val rects = layoutTiles(shown.size)
        val unit = unitOf(config)
        shown.forEachIndexed { position, index ->
            val tile = frame.tiles.getOrNull(index)
            val valid = tile?.isValid == true
            renderTile(
                fb, rects[position], title(index, config), valid,
                tile?.temperatureC?.toFloat() ?: 0f, tile?.humidityPct?.toFloat() ?: 0f,
                if (index == 0) frame.cellPercent else null, unit, if (index == 0) "I2C" else null,
                badgeLetters(latch, index)
            )
        }
        return fb
    }

    private fun renderHeader(fb: PanelFramebuffer, title: String, cellPercent: Int?) {
        fb.fillRect(0, 0, PanelFramebuffer.WIDTH, HEADER_HEIGHT, true)
        fb.drawText(10, 7, title, 2, false)
        if (cellPercent != null) {
            val text = "$cellPercent%"
            fb.drawText(364 - text.length * 12, 7, text, 2, false)
            renderBatteryIcon(fb, 368, 8, cellPercent)
        }
    }

    /** 22 x 12 px body, 2 x 4 px terminal and an 18 px fill, drawn in white on the black header. */
    private fun renderBatteryIcon(fb: PanelFramebuffer, x: Int, y: Int, percentage: Int) {
        fb.drawRect(x, y, 22, 12, false)
        fb.fillRect(x + 22, y + 4, 2, 4, false)
        if (percentage > 0) {
            fb.fillRect(x + 2, y + 2, (percentage * 18 / 100).coerceIn(1, 18), 8, false)
        }
    }

    private fun drawTileFrame(fb: PanelFramebuffer, r: TileRect, title: String, formatBadge: String?) {
        fb.drawRect(r.x, r.y, r.w, r.h, true)
        fb.drawHLine(r.x, r.y + 24, r.w, true)
        val sc = fitScale(title, r.w - 16, 2)
        fb.drawText(r.x + 8, r.y + if (sc == 2) 6 else 9, title, sc, true)
        if (!formatBadge.isNullOrEmpty()) {
            val badge = "[$formatBadge]"
            val bw = badge.length * 6 - 1
            val bx = r.x + r.w - bw - 6
            val titleEnd = 8 + title.length * 6 * sc - sc
            if (bx > r.x + titleEnd + 4) fb.drawText(bx, r.y + 9, badge, 1, true)
        }
    }

    private fun batteryText(percent: Int?): String? = percent?.let { "BAT: $it %" }

    private fun renderTile(
        fb: PanelFramebuffer, r: TileRect, title: String, valid: Boolean, tempC: Float, humidityPct: Float,
        batteryPct: Int?, unit: Char, formatBadge: String?, badges: String = ""
    ) {
        drawTileFrame(fb, r, title, formatBadge)
        val tempLine = "${format1(toDisplay(tempC, unit))} ${if (unit == 'F') 'F' else 'C'}"
        when (tileStyle(r.w, r.h)) {
            TileStyle.COMPACT -> renderCompact(fb, r, valid, tempLine, humidityPct, batteryPct, tempC, unit, badges)
            TileStyle.LARGE -> renderLarge(fb, r, valid, tempLine, tempC, humidityPct, batteryPct, unit, badges)
            TileStyle.STANDARD -> renderStandard(fb, r, valid, tempLine, tempC, humidityPct, batteryPct, unit, badges)
        }
    }

    private fun renderStandard(
        fb: PanelFramebuffer, r: TileRect, valid: Boolean, tempLine: String, tempC: Float, humidityPct: Float,
        batteryPct: Int?, unit: Char, badges: String
    ) {
        if (!valid) {
            fb.drawText(r.x + 12, r.y + 56, "NO SIGNAL", 2, true)
            drawClimateLine(fb, r, false, 0f, 0f, unit)
            return
        }
        fb.drawText(r.x + 12, r.y + 40, "T: $tempLine", 2, true)
        fb.drawText(r.x + 12, r.y + 68, "H: ${format0(humidityPct)} %", 2, true)
        drawClimateLine(fb, r, true, tempC, humidityPct, unit)
        drawBadges(fb, badges, r.x + 108, r.y + 56, 1)
        batteryText(batteryPct)?.let {
            val scale = fitScale(it, r.w - 16, 2)
            fb.drawText(r.x + 12, r.y + if (scale == 2) 96 else 99, it, scale, true)
        }
    }

    private fun drawClimateLine(fb: PanelFramebuffer, r: TileRect, usable: Boolean, tempC: Float, rh: Float, unit: Char) {
        if (r.h <= 86 + 7) return
        fb.drawText(r.x + 12, r.y + 86, climateLine(usable, tempC, rh, unit), 1, true)
    }

    private fun renderCompact(
        fb: PanelFramebuffer, r: TileRect, valid: Boolean, tempLine: String, humidityPct: Float,
        batteryPct: Int?, tempC: Float, unit: Char, badges: String
    ) {
        val narrow = r.w < 160
        val pad = if (narrow) 8 else 12
        if (!valid) {
            fb.drawText(r.x + pad, r.y + if (r.h > 100) 56 else 36, "NO SIGNAL", 2, true)
            return
        }
        fb.drawText(r.x + pad, r.y + 30, tempLine, 2, true)
        fb.drawText(r.x + pad, r.y + 48, "${format0(humidityPct)} %", 2, true)
        if (narrow) drawBadges(fb, badges, r.x + r.w - 22, r.y + 50, -1) else drawBadges(fb, badges, r.x + 92, r.y + 50, 1)
        if (narrow) {
            fb.drawText(r.x + 6, r.y + 106, climateLine(true, tempC, humidityPct, unit), 1, true)
        }
        batteryText(batteryPct)?.let { fb.drawText(r.x + pad, r.y + if (narrow) 92 else 66, it, 1, true) }
    }

    private fun renderLarge(
        fb: PanelFramebuffer, r: TileRect, valid: Boolean, tempLine: String, tempC: Float, humidityPct: Float,
        batteryPct: Int?, unit: Char, badges: String
    ) {
        if (!valid) {
            fb.drawText(r.x + 12, r.y + r.h / 2 - 7, "NO SIGNAL", 2, true)
            return
        }
        val pad = 12
        val ts = if (r.w >= 300) 4 else 3
        val textH = 7 * ts
        val gap = 6
        // Title band, two text lines, climate line (7), battery line (14) and 7 gaps; the curves are not drawn.
        val fixed = 24 + 2 * textH + 7 + 14 + 7 * gap
        val sparkH = ((r.h - fixed) / 2).coerceAtMost(60)
        var cy = r.y + 24 + gap
        fb.drawText(r.x + pad, cy, tempLine, ts, true)
        cy += textH + gap + sparkH + gap
        val humidityText = "${format0(humidityPct)} %"
        fb.drawText(r.x + pad, cy, humidityText, ts, true)
        drawBadges(fb, badges, r.x + pad + humidityText.length * 6 * ts + 4 * 4 + 7, cy + (textH - BADGE_SIZE) / 2, 1)
        cy += textH + gap + sparkH + gap
        fb.drawText(r.x + pad, cy, climateLine(true, tempC, humidityPct, unit), 1, true)
        cy += 7 + gap
        batteryText(batteryPct)?.let {
            fb.drawText(r.x + pad, cy, it, fitScale("BAT: 4.20V 100%", r.w - 2 * pad, 2), true)
        }
    }
}
