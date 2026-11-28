package com.rlcdpanel.phone.protocol

/** State of the mute Quick Settings tile. */
enum class MuteTileState { UNAVAILABLE, MUTED, UNMUTED }

/** What a tap on the tile does: send `MUTE 1` (true) or `MUTE 0` (false), then read `STATUS`. */
data class MuteTileAction(val muted: Boolean)

/** Text and state of the tile. [subtitle] holds an error text or null. No Android types, so a unit test runs it on the JVM. */
data class MuteTileModel(val state: MuteTileState, val label: String, val subtitle: String?) {

    /** The action of a tap; null while unavailable (Android does not deliver a tap on an unavailable tile; the app fills the cache). */
    val tapAction: MuteTileAction?
        get() = when (state) {
            MuteTileState.UNAVAILABLE -> null
            MuteTileState.MUTED -> MuteTileAction(false)
            MuteTileState.UNMUTED -> MuteTileAction(true)
        }

    companion object {
        const val LABEL = "Panel stumm"
        const val MUTED_TEXT = "Stumm"
        const val UNMUTED_TEXT = "Ton an"
        const val UNAVAILABLE_TEXT = "Nicht verfügbar"
        const val UNREADABLE_TEXT = "Antwort nicht lesbar"

        /** [cachedMuted] is `muted` of the last `STATUS` reply, null without one. [error] is the text of the last failed tap. */
        fun of(cachedMuted: Boolean?, error: String? = null): MuteTileModel = when (cachedMuted) {
            null -> MuteTileModel(MuteTileState.UNAVAILABLE, LABEL, error ?: UNAVAILABLE_TEXT)
            true -> MuteTileModel(MuteTileState.MUTED, LABEL, error ?: MUTED_TEXT)
            false -> MuteTileModel(MuteTileState.UNMUTED, LABEL, error ?: UNMUTED_TEXT)
        }

        /** Error text of a reply to `MUTE` or `STATUS` (never empty); null for an `OK ...` line. */
        fun failureText(reply: ConsoleReply, fallback: (ConsoleReply) -> String): String? =
            ChimeCommand.tileFailureText(reply, fallback)?.ifEmpty { UNREADABLE_TEXT }

        /** True only for `OK MUTE 1` ([muted] true) or `OK MUTE 0` ([muted] false). */
        fun isMuteAck(reply: ConsoleReply, muted: Boolean): Boolean =
            (reply as? ConsoleReply.Status)?.line == ChimeCommand.mute(muted).let { "OK $it" }

        /** Label text: below SDK 29 the tile has no subtitle, so the error text joins the label. */
        fun labelWith(model: MuteTileModel, hasSubtitle: Boolean): String =
            if (hasSubtitle || model.subtitle == null) model.label else "${model.label}: ${model.subtitle}"

        /** `muted` of a `STATUS` reply; null for a reply without a good frame or without the field. */
        fun mutedFrom(reply: ConsoleReply): Boolean? =
            try { StatusReply.parseReply(reply).muted } catch (_: ParseException) { null }
    }
}
