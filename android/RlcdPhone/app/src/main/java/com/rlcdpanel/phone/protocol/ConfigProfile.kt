package com.rlcdpanel.phone.protocol

/**
 * A named set of `config.ini` keys, for example `Winter` and `Summer`. It holds alert limits and similar settings
 * only, never MACs, names, offsets or gains: those describe one sensor or one unit and not a season.
 * Same rules as the macOS `ConfigProfile`.
 */
data class ConfigProfile(val name: String, val values: Map<String, String>) {

    /** What applying a profile does to a panel. */
    data class Plan(
        /** Differences against the current values, in schema order. Keys that already match are not in it. */
        val changes: List<ConfigChange>,
        /** Keys of the profile that are not sent: a key that this app does not know, or an excluded key. */
        val skipped: List<String>,
        /** Problem texts by key for values that the schema rejects. A problem blocks the whole apply. */
        val problems: Map<String, String>
    ) {
        val canApply: Boolean get() = problems.isEmpty() && changes.isNotEmpty()
        /** `SET` lines in the fixed schema order for `ConfigSchema.run`. */
        val commands: List<String> get() = ConfigSchema.batch(changes)
    }

    /** Result of [make]: the profile, or a text that names the reason. */
    sealed class Made {
        data class Ok(val profile: ConfigProfile) : Made()
        data class Failed(val text: String) : Made()
    }

    /** Plan against the values of the last `CONFIG` reply. */
    fun plan(current: Map<String, String>): Plan {
        val usable = linkedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        for (key in values.keys.sorted()) {
            if (ConfigKeys.entry(key) == null || isExcluded(key)) skipped.add(key) else usable[key] = values.getValue(key)
        }
        val changes = ConfigSchema.diff(current, current + usable)
        return Plan(changes, skipped, ConfigSchema.problems(changes, current))
    }

    companion object {
        const val MAX_NAME_LENGTH = 24

        /** True for a key that a profile must not hold: names, MACs, offsets and gains. */
        fun isExcluded(key: String): Boolean =
            key.endsWith("_name") || key.endsWith("_mac") || key.contains("_offset_") || key.endsWith("_gain")

        fun isValidName(name: String): Boolean =
            // Same rule as the macOS `isValidName`: no blank (U+0020) at an edge, 1 to 24 code points, no control character.
            name.isNotEmpty() && name.first() != ' ' && name.last() != ' ' &&
                name.codePointCount(0, name.length) <= MAX_NAME_LENGTH && name.none { it.isISOControl() }

        /**
         * Profile from the changed keys of the editor ([changes] hold the new values). Keys that the schema does not
         * know and the excluded keys drop out. Fails for a bad name or when no key is left.
         */
        fun make(name: String, changes: List<ConfigChange>): Made {
            if (!isValidName(name)) return Made.Failed("Name: 1 bis $MAX_NAME_LENGTH Zeichen, kein Tabulator, kein Zeilenumbruch")
            val values = linkedMapOf<String, String>()
            for (c in changes) {
                if (ConfigKeys.entry(c.key) == null || isExcluded(c.key)) continue
                ConfigKeys.validate(c.key, c.new)?.let { return Made.Failed("${c.key}: $it. Wert vor dem Speichern korrigieren") }
                // `ConfigKeys.validate` accepts no tab and no line break in a value, so the text format holds.
                values[c.key] = c.new
            }
            if (values.isEmpty()) return Made.Failed("Kein Key zu speichern. Offsets, Gains, Namen und MACs gehören nicht in ein Profil")
            return Made.Ok(ConfigProfile(name, values))
        }
    }
}

/** One text slot that holds the stored profiles. The app backs it with `SharedPreferences`. */
interface ConfigProfileStorage {
    fun read(): String?
    fun write(text: String)
}

/**
 * Stored profiles. Text format, shared with the macOS app (`tests/fixtures/config_profiles_sample.txt`):
 * `P<TAB>name` starts a profile, `K<TAB>key<TAB>value` adds one key. A line of another form is skipped.
 */
class ConfigProfileStore(private val storage: ConfigProfileStorage) {

    /** Profiles sorted by name. */
    fun profiles(): List<ConfigProfile> = decode(storage.read().orEmpty()).sortedBy { it.name }

    fun profile(name: String): ConfigProfile? = profiles().firstOrNull { it.name == name }

    /** Saves the profile and replaces one of the same name. Returns false at the limit of [MAX_PROFILES]. */
    fun save(profile: ConfigProfile): Boolean {
        val all = profiles().filter { it.name != profile.name }
        if (all.size >= MAX_PROFILES) return false
        storage.write(encode(all + profile))
        return true
    }

    fun remove(name: String) {
        storage.write(encode(profiles().filter { it.name != name }))
    }

    companion object {
        const val MAX_PROFILES = 20

        fun encode(profiles: List<ConfigProfile>): String = buildString {
            for (p in profiles) {
                append("P\t").append(p.name).append('\n')
                for (key in p.values.keys.sorted()) append("K\t").append(key).append('\t').append(p.values.getValue(key)).append('\n')
            }
        }

        /** A line that breaks the format drops itself only. A later profile of the same name replaces the earlier one. */
        fun decode(text: String): List<ConfigProfile> {
            val order = mutableListOf<String>()
            val byName = linkedMapOf<String, MutableMap<String, String>>()
            var current: String? = null
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val parts = line.split('\t', limit = 3)
                if (parts.size == 2 && parts[0] == "P") {
                    if (ConfigProfile.isValidName(parts[1])) {
                        current = parts[1]
                        if (parts[1] !in byName) order.add(parts[1])
                        byName[parts[1]] = linkedMapOf()
                    } else current = null
                } else if (parts.size == 3 && parts[0] == "K" && parts[1].isNotEmpty()) {
                    current?.let { byName[it]?.put(parts[1], parts[2]) }
                }
            }
            return order.map { ConfigProfile(it, byName.getValue(it)) }
        }
    }
}
