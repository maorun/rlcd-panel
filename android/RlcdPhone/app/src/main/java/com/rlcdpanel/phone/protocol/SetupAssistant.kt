package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.FoundSensor
import com.rlcdpanel.phone.finder.SlotAssignment

/**
 * Guided setup of one new sensor: scan, choose, assign, verify, calibrate, done.
 *
 * Same step order as `SetupAssistant.swift` of the macOS app. A pure state machine without timer, link or clock.
 * The scan runs on the phone (`SensorFinderScanner`), so it needs no console command. The app calls one action
 * method for each user click and sends the returned command over the BLE console. [receive] takes the reply.
 * After a reply in the assign step and in the apply batch, [nextCommand] gives the next command of the same click.
 * The first `ERR` reply, a lost link or a sensor that stays absent in `STATUS` stops the assistant. A stopped
 * assistant sends no command and does not undo a `SET` that the panel already applied. [restart] begins again.
 */
class SetupAssistant {
    enum class Step { SCAN, CHOOSE, ASSIGN, VERIFY, CALIBRATE, DONE }

    sealed class Stop(val text: String) {
        /** `ERR READ_ONLY`: the panel runs with `ble_console=1`. */
        object ReadOnly : Stop(READ_ONLY_TEXT)
        /** `ERR FIXED_MAC`: the slot holds a fixed MAC. */
        object FixedMac : Stop(FIXED_MAC_TEXT)
        /** The first other `ERR` reply, or a reply that the assistant cannot use. */
        class Reply(text: String) : Stop(text)
        class LinkLost(reason: String) : Stop("Verbindung verloren: $reason")
        /** The slot shows no packet of the chosen sensor after [MAX_VERIFY_TRIES] checks. */
        class Absent(val slot: Int, val mac: String) :
            Stop("Slot $slot meldet den Sensor $mac nach $MAX_VERIFY_TRIES Prüfungen nicht. Sensor und Reichweite prüfen")

        override fun equals(other: Any?): Boolean = other is Stop && other::class == this::class && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = "Stop($text)"
    }

    var step: Step = Step.SCAN
        private set
    var stop: Stop? = null
        private set
    /** Hint for the user, null after an action without a hint. A hint is not a stop. */
    var notice: String? = null
        private set
    var found: List<FoundSensor> = emptyList()
        private set
    var chosenMac: String? = null
        private set
    var chosenName: String = ""
        private set
    /** Registry slot 1 to 10 that the assign step picked. */
    var slot: Int? = null
        private set
    /** The `SALT N` result of the calibrate step; null before the check. */
    var salt: SaltReply? = null
        private set
    /** The command that waits for a reply. */
    var pending: String? = null
        private set
    /** Failed verify checks in a row. */
    var verifyTries: Int = 0
        private set

    private val queue = ArrayDeque<String>()
    private var assigning = false
    private var applying = false

    val isStopped: Boolean get() = stop != null

    /** Sensors of the last scan that no slot holds. The strongest signal comes first. */
    fun candidates(config: PanelConfig): List<FoundSensor> =
        found.sortedByDescending { it.rssi }.filter { SlotAssignment.slotOf(it.advertisement.mac, config) == null }

    /** The `SALT` proposal of the new slot, or null. */
    val proposal: CalibrationProposal? get() = salt?.proposal

    // MARK: User actions

    /** Takes the rows of the phone scan. Allowed in the steps `SCAN` and `CHOOSE` (a new scan). No command. */
    fun scanned(rows: List<FoundSensor>, config: PanelConfig): Boolean {
        if (!begin() || (step != Step.SCAN && step != Step.CHOOSE)) return false
        found = rows
        step = Step.CHOOSE
        if (candidates(config).isEmpty()) notice = "Kein neuer Sensor gefunden. Neu suchen."
        return true
    }

    /** Takes one sensor of the `CHOOSE` list and its optional name. No command. */
    fun choose(mac: String, name: String, config: PanelConfig): Boolean {
        if (!begin() || step != Step.CHOOSE) return false
        val row = candidates(config).firstOrNull { it.advertisement.mac.equals(mac, ignoreCase = true) }
        if (row == null) { notice = "Einen Sensor der Liste wählen"; return false }
        if (SlotAssignment.batch(1, row.advertisement.mac, name) == null) {
            notice = "Name ungültig (bis ${SlotAssignment.MAX_NAME_CHARS} Zeichen: A-Z, 0-9, -)"
            return false
        }
        chosenMac = row.advertisement.mac.uppercase()
        chosenName = name.uppercase()
        step = Step.ASSIGN
        return true
    }

    /**
     * Picks the first free slot and returns `SET slotN_mac=<MAC>`. `SET slotN_name=<NAME>` and `CONFIG` follow
     * through [nextCommand]. [status] and [config] are the last replies of the panel; both must be known.
     */
    fun beginAssign(status: StatusReply?, config: PanelConfig?): String? {
        val mac = chosenMac
        if (!begin() || step != Step.ASSIGN || assigning || mac == null) return null
        if (status == null || config == null) { notice = "Erst STATUS und CONFIG lesen"; return null }
        if (!config.canWrite) { stop = Stop.ReadOnly; return null }
        val n = freeSlot(status, config)
        if (n == null) { notice = "Kein freier Slot"; return null }
        SlotAssignment.warning(n, mac, config)?.let { notice = it; return null }
        val commands = SlotAssignment.batch(n, mac, chosenName)
        if (commands == null) { notice = "Slot, MAC oder Name ungültig"; return null }
        slot = n
        assigning = true
        queue.clear()
        queue.addAll(commands)
        queue.addLast(CONFIG)
        return nextCommand()
    }

    /** `STATUS`, on each click of the check button. Allowed in the step `VERIFY`. */
    fun checkReception(): String? {
        if (!begin() || step != Step.VERIFY) return null
        return send(STATUS)
    }

    /** `SALT <slot>`: the salt check of the new sensor in a closed box. Allowed in the step `CALIBRATE`. */
    fun beginCalibrate(): String? {
        val n = slot
        if (!begin() || step != Step.CALIBRATE || applying || n == null) return null
        salt = null
        return send(SaltCommand.line(n, magnesium = false))
    }

    /** Sends the proposal as `SET <key>=<value>`, then `CONFIG` through [nextCommand]. */
    fun beginApply(): String? {
        val p = proposal
        if (!begin() || step != Step.CALIBRATE || applying) return null
        if (p == null) { notice = "Kein Vorschlag vorhanden"; return null }
        applying = true
        queue.clear()
        queue.add("SET ${p.key}=${p.value}")
        queue.add(CONFIG)
        return nextCommand()
    }

    /** Ends the assistant without a calibration. */
    fun finish() {
        if (!begin() || step != Step.CALIBRATE || applying) return
        step = Step.DONE
    }

    /** Starts again at the step `SCAN` after a stop or at the end. Clears each choice. */
    fun restart() {
        step = Step.SCAN
        stop = null
        notice = null
        found = emptyList()
        chosenMac = null
        chosenName = ""
        slot = null
        salt = null
        pending = null
        verifyTries = 0
        queue.clear()
        assigning = false
        applying = false
    }

    // MARK: Replies and link

    /** The next command of a running batch, null when the batch is done or the assistant waits for the user. */
    fun nextCommand(): String? {
        if (stop != null || pending != null) return null
        val command = queue.removeFirstOrNull() ?: return null
        pending = command
        return command
    }

    /** Takes the reply of the pending command. The first `ERR` stops the assistant before the next command. */
    fun receive(reply: ConsoleReply) {
        if (stop != null) return
        val command = pending ?: return
        pending = null
        when (reply) {
            is ConsoleReply.Corrupt ->
                if (reply.error == ReplyError.Timeout) linkLost("Keine Antwort in 10 s") else fail("Antwort verworfen: ${reply.error}")
            is ConsoleReply.Status -> when {
                reply.line.startsWith("ERR") -> failErr(reply.line)
                command.startsWith("SET ") && reply.line.startsWith("OK") -> Unit
                else -> fail("Unerwartete Antwort: ${reply.line.take(80)}")
            }
            is ConsoleReply.Frame -> try {
                receiveFrame(reply.lines, command)
            } catch (e: ParseException) {
                fail("Antwort nicht lesbar: ${e.line.take(80)}")
            }
        }
    }

    /** A lost link or a timeout ends the assistant. The panel state stays as it is. */
    fun linkLost(reason: String) {
        if (stop != null || step == Step.DONE) return
        stop = Stop.LinkLost(reason)
        clearWork()
    }

    /** A reply to a read outside the command queue (`STATUS` or `CONFIG` before the assign step) did not help. */
    fun failure(reply: ConsoleReply) {
        if (stop != null) return
        when (reply) {
            is ConsoleReply.Status -> failErr(reply.line)
            is ConsoleReply.Corrupt ->
                if (reply.error == ReplyError.Timeout) linkLost("Keine Antwort in 10 s") else fail("Antwort verworfen: ${reply.error}")
            is ConsoleReply.Frame -> fail("Antwort nicht lesbar")
        }
    }

    /** The command could not go out. Ends the assistant, so it does not wait for a reply. */
    fun commandNotSent() = linkLost("Befehl nicht gesendet")

    // MARK: Private

    private fun begin(): Boolean {
        notice = null
        return stop == null && pending == null
    }

    private fun send(command: String): String {
        pending = command
        return command
    }

    private fun clearWork() {
        pending = null
        queue.clear()
    }

    private fun fail(text: String) {
        stop = Stop.Reply(text)
        clearWork()
    }

    private fun failErr(line: String) {
        stop = when {
            line.startsWith("ERR READ_ONLY") -> Stop.ReadOnly
            line.startsWith("ERR FIXED_MAC") -> Stop.FixedMac
            else -> Stop.Reply(line.take(80))
        }
        clearWork()
    }

    private fun receiveFrame(lines: List<String>, command: String) {
        when {
            command == CONFIG -> {
                if (!assigning && !applying) { fail("Unerwartete Antwort auf CONFIG"); return }
                if (queue.isNotEmpty()) return
                if (assigning) { assigning = false; step = Step.VERIFY } else { applying = false; step = Step.DONE }
            }
            command == STATUS -> verify(StatusReply.parse(lines))
            command.startsWith("SALT ") -> {
                val parsed = SaltReply.parse(lines)
                salt = parsed
                notice = when (val o = parsed.outcome) {
                    is SaltReply.Outcome.Measured -> if (o.proposal == null) "Kein Vorschlag nötig" else null
                    else -> SaltText.outcome(parsed)
                }
            }
            else -> fail("Unerwartete Antwort")
        }
    }

    /** Passes when the `SENSOR` line of the slot has the chosen `mac=` and `state=OK` or `BATLOW` (both count as present). The third miss stops. */
    private fun verify(status: StatusReply) {
        val n = slot ?: return
        val mac = chosenMac ?: return
        if (step != Step.VERIFY) return
        val sensor = status.sensors.firstOrNull { it.index == n }
        val seenMac = sensorMac(status, n)
        if (sensor != null && (sensor.state == "OK" || sensor.state == "BATLOW") && seenMac == mac) {
            step = Step.CALIBRATE
            return
        }
        verifyTries++
        if (verifyTries >= MAX_VERIFY_TRIES) {
            stop = Stop.Absent(n, mac)
            clearWork()
        } else {
            notice = "Slot $n wartet: state=${sensor?.state ?: "--"} mac=${seenMac ?: "--"} (Versuch $verifyTries von $MAX_VERIFY_TRIES)"
        }
    }

    companion object {
        const val STATUS = "STATUS"
        const val CONFIG = "CONFIG"
        const val MAX_VERIFY_TRIES = 3
        const val READ_ONLY_TEXT = "Set ble_console=2 to apply"
        const val FIXED_MAC_TEXT = "Der Slot hat eine feste MAC. Slot leeren oder einen anderen Slot wählen"

        private val MAC_FIELD = Regex("""\bmac=([0-9A-Fa-f:]{17})""")

        /** `mac=` of the `SENSOR` line of [slot]; null for `--` and a missing field. */
        fun sensorMac(status: StatusReply, slot: Int): String? =
            status.sensorLines.firstOrNull { it.split(' ').filter(String::isNotEmpty).getOrNull(1)?.toIntOrNull() == slot }
                ?.let { MAC_FIELD.find(it)?.groupValues?.get(1)?.uppercase() }

        /** The first slot without a fixed MAC, without a reading and not disabled (`slotN_enabled=0`). */
        fun freeSlot(status: StatusReply, config: PanelConfig): Int? =
            SlotAssignment.SLOTS.firstOrNull { n ->
                if (config.slotMac(n) != null || !config.slotEnabled(n)) return@firstOrNull false
                val sensor = status.sensors.firstOrNull { it.index == n }
                sensor == null || sensor.state == "EMPTY"
            }
    }
}
