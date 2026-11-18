package com.rlcdpanel.phone.finder

import com.rlcdpanel.phone.protocol.ConsoleReply
import com.rlcdpanel.phone.protocol.PanelConfig

/** Assigns a found sensor to a registry slot. Same rules as `SlotAssignment.swift` of the macOS app. */
object SlotAssignment {
    val SLOTS = 1..10
    const val MAX_NAME_CHARS = 12
    /** The console takes a line of at most 40 bytes. */
    const val MAX_LINE_BYTES = 40
    const val READ_ONLY_TEXT = "Set ble_console=2 to apply"

    private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** True for `AA:BB:CC:DD:EE:FF` in either case, except the zero MAC. */
    fun isValidMac(mac: String): Boolean = MAC.matches(mac) && mac.any { it != '0' && it != ':' }

    /**
     * The commands that bind a sensor to [slot]: `SET slotN_mac=<MAC>`, then `SET slotN_name=<NAME>` for a name that
     * is not empty. The name is upper-cased. Null for a bad slot or MAC, a name above 12 characters, a name with a
     * space or `=`, a name outside `A-Z`, `0-9` and `-`, or a line above 40 bytes.
     */
    fun batch(slot: Int, mac: String, name: String?): List<String>? {
        if (slot !in SLOTS || !isValidMac(mac)) return null
        val commands = mutableListOf("SET slot${slot}_mac=${mac.uppercase()}")
        if (!name.isNullOrEmpty()) {
            val value = name.uppercase()
            if (value.length > MAX_NAME_CHARS || ' ' in value || '=' in value) return null
            if (!value.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' }) return null
            commands.add("SET slot${slot}_name=$value")
        }
        if (commands.any { it.toByteArray().size > MAX_LINE_BYTES }) return null
        return commands
    }

    /** Slots other than [slot] that hold [mac] as a fixed MAC. The firmware rejects this `SET`. */
    fun conflicts(slot: Int, mac: String, config: PanelConfig): List<Int> {
        val wanted = mac.uppercase()
        return SLOTS.filter { it != slot && config.slotMac(it) == wanted }
    }

    /** Warning for a MAC that another slot holds, null without a conflict. */
    fun warning(slot: Int, mac: String, config: PanelConfig): String? {
        val other = conflicts(slot, mac, config)
        if (other.isEmpty()) return null
        return "Slot ${other.joinToString(", ")} hält ${mac.uppercase()} schon. Die Firmware lehnt eine doppelte MAC ab."
    }

    /** Slot of a fixed [mac] in the cached config, null for an unknown MAC. */
    fun slotOf(mac: String, config: PanelConfig): Int? = SLOTS.firstOrNull { config.slotMac(it) == mac.uppercase() }

    /** Result of [run]: [applied] holds the `SET` commands with an `OK`, [failure] the first `ERR` text. */
    data class Result(
        val applied: List<String>,
        val failure: String?,
        val config: ConsoleReply.Frame?,
        val configFailed: Boolean
    ) {
        val ok: Boolean get() = failure == null && !configFailed
        val summary: String
            get() = when {
                failure != null && applied.isNotEmpty() -> "$failure. Übernommen: ${applied.size}. Zustand des Panels unbekannt, CONFIG lesen"
                failure != null -> "$failure. Übernommen: 0"
                configFailed -> "Übernommen: ${applied.size}. CONFIG lesen fehlgeschlagen"
                else -> "Übernommen: ${applied.size}"
            }
    }

    /** Text of a failed reply. `ERR READ_ONLY` needs the console level 2. */
    fun failureText(reply: ConsoleReply): String = when {
        reply is ConsoleReply.Status && reply.line.startsWith("ERR READ_ONLY") -> READ_ONLY_TEXT
        reply is ConsoleReply.Status -> reply.line.take(80)
        else -> "Keine gültige Antwort vom Panel"
    }

    /**
     * Sends [commands] one by one through [send] (`BleConsoleClient::send`), then `CONFIG`. The first `ERR` or
     * unusable reply stops the batch before `CONFIG`.
     */
    fun run(commands: List<String>, send: (String, (ConsoleReply) -> Unit) -> Unit, done: (Result) -> Unit) {
        val applied = mutableListOf<String>()
        fun config() {
            send("CONFIG") { reply ->
                done(Result(applied, null, reply as? ConsoleReply.Frame, reply !is ConsoleReply.Frame))
            }
        }
        fun step(i: Int) {
            if (i >= commands.size) return config()
            send(commands[i]) { reply ->
                if (reply is ConsoleReply.Status && reply.line.startsWith("OK")) {
                    applied.add(commands[i])
                    step(i + 1)
                } else {
                    done(Result(applied, failureText(reply), null, false))
                }
            }
        }
        step(0)
    }
}
