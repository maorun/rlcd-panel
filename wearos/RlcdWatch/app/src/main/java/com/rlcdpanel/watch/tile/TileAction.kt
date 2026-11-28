package com.rlcdpanel.watch.tile

import com.rlcdpanel.watch.protocol.ChimeCommand
import com.rlcdpanel.watch.protocol.ConsoleReply

/**
 * Silence actions of the tile. A tap opens `MainActivity` with [EXTRA_KEY] set to [id]; the activity
 * then sends [command] once over the BLE console.
 */
enum class TileAction(val id: String, val label: String, val command: String) {
    MUTE("mute", "Mute", ChimeCommand.mute(true)),
    SNOOZE_60("snooze_60", "Snooze 60", ChimeCommand.snooze(SNOOZE_MINUTES));

    companion object {
        const val EXTRA_KEY = "com.rlcdpanel.watch.extra.TILE_ACTION"
        const val SNOOZE_MINUTES = 60

        /** The action of an intent extra; null for a missing or unknown id. */
        fun fromId(id: String?): TileAction? = values().firstOrNull { it.id == id }

        /** Toast text of a reply: the error text of [ChimeCommand.failureText], `Stumm` for `OK MUTE 1`, else the `OK` text. */
        fun replyText(reply: ConsoleReply, fallback: (ConsoleReply) -> String): String {
            ChimeCommand.failureText(reply, fallback)?.let { return it }
            val line = (reply as? ConsoleReply.Status)?.line?.trim() ?: return "OK"
            return if (line == "OK MUTE 1") "Stumm" else ChimeCommand.okText(line)
        }
    }
}
