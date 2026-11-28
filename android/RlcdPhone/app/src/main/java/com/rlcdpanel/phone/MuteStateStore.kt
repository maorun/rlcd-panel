package com.rlcdpanel.phone

import android.content.Context

/** Mute flag of the last good `STATUS` reply, kept in `SharedPreferences` for the Quick Settings tile. */
object MuteStateStore {
    private const val PREFS = "mute_tile"
    private const val KEY = "muted"

    /** Null without a cached reply. */
    fun load(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }

    fun save(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, muted).apply()
    }
}
