package com.rlcdpanel.phone.protocol

import com.rlcdpanel.phone.finder.SlotAssignment

/** Commands of the slot editor: define a slot, clear a slot, toggle `slot_auto_assign`. */
object SlotConfig {
    /** A checked command list or the reason why the input is refused. */
    sealed class Plan {
        data class Commands(val commands: List<String>) : Plan()
        data class Invalid(val reason: String) : Plan()
    }

    /** Error text for a name, null when [name] is empty or valid (`A-Z`, `0-9`, `-`, at most 12 characters). */
    fun nameError(name: String): String? {
        val value = name.uppercase()
        return when {
            value.length > SlotAssignment.MAX_NAME_CHARS -> "Name: höchstens ${SlotAssignment.MAX_NAME_CHARS} Zeichen"
            !value.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' } -> "Name: nur A-Z, 0-9 und -"
            else -> null
        }
    }

    /**
     * `SET slotN_mac=<MAC>` then `SET slotN_name=<NAME>`. The name is upper-cased. A key whose value equals the cached
     * [config] is not sent; nothing changed gives an empty command list. An empty MAC with a name sends the
     * name only (`slotN_name` needs no fixed MAC) and first clears a stored fixed MAC. An empty MAC and an empty name
     * clear the fixed MAC. An empty name with a MAC leaves the stored name as it is, because the firmware rejects an
     * empty name (`ERR BAD_VALUE`).
     * [config] is the cached panel config for the duplicate check.
     */
    fun define(slot: Int, mac: String, name: String, config: PanelConfig): Plan {
        if (slot !in SlotAssignment.SLOTS) return Plan.Invalid("Slot $slot gibt es nicht")
        if (mac.isEmpty()) {
            if (name.isEmpty()) return clear(slot)
            nameError(name)?.let { return Plan.Invalid(it) }
            val nameCommands =
                if (name.equals(config.slotName(slot), ignoreCase = true)) emptyList()
                else listOf("SET slot${slot}_name=${name.uppercase()}")
            // An erased MAC field clears the fixed MAC that the slot holds.
            return Plan.Commands(
                if (config.slotMac(slot) != null) listOf("SET slot${slot}_mac=") + nameCommands else nameCommands
            )
        }
        if (!SlotAssignment.isValidMac(mac)) return Plan.Invalid("MAC: Format AA:BB:CC:DD:EE:FF")
        nameError(name)?.let { return Plan.Invalid(it) }
        SlotAssignment.warning(slot, mac, config)?.let { return Plan.Invalid(it) }
        val batch = SlotAssignment.batch(slot, mac, name) ?: return Plan.Invalid("Befehl zu lang")
        // Send only the keys that differ from the cached config, so a rename leaves the fixed MAC alone.
        val macChanged = config.slotMac(slot) != mac.uppercase()
        val nameChanged = name.isNotEmpty() && !name.equals(config.slotName(slot), ignoreCase = true)
        return Plan.Commands(batch.filter { if (it.startsWith("SET slot${slot}_mac=")) macChanged else nameChanged })
    }

    /**
     * An empty `SET slotN_mac=` clears the fixed MAC. `SLOT CLEAR <N>` is no choice here: it answers
     * `ERR FIXED_MAC` for a slot with a fixed MAC, which is the slot the user wants to clear. The name stays,
     * because the firmware accepts no empty `slotN_name`; in the mobile apps it shows as the fallback tile name.
     */
    fun clear(slot: Int): Plan =
        if (slot !in SlotAssignment.SLOTS) Plan.Invalid("Slot $slot gibt es nicht")
        else Plan.Commands(listOf("SET slot${slot}_mac="))

    fun autoAssign(on: Boolean): List<String> = listOf("SET slot_auto_assign=${if (on) 1 else 0}")
}
