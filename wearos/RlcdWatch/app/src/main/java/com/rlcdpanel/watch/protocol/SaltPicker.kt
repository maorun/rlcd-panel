package com.rlcdpanel.watch.protocol

/** Sensors the salt screen offers: the onboard sensor and each slot 1 to 10 with a fixed MAC or a name. Watch only. */
object SaltPicker {
    /** Sensor numbers in ascending order; [selected] stays in the list, so the focused room is always a choice. */
    fun sensors(config: PanelConfig, selected: Int? = null): List<Int> =
        (0..SaltCommand.MAX_SENSOR).filter {
            it == 0 || it == selected || config.slotMac(it) != null || config.slotName(it) != null
        }
}
