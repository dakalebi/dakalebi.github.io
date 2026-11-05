package ge.dakalebi.domain.model

/**
 * One setting that follows the account to every device it signs in on.
 *
 * **Adding a setting is one line in the companion below.** Declaring it is what
 * registers it in [all], and everything that moves settings around iterates [all]:
 * the account document, this device's copy, the first-sync upload of a value set
 * before the account had one, and the offline bookkeeping. None of them names a
 * setting, so none of them needs touching.
 *
 * The one other edit is deliberate: `firebase/firestore.rules` allowlists the
 * fields of `users/{uid}` by name and type, because those rules are the whole
 * security boundary and a field nobody reviewed should not be writable. Until the
 * key is there, writes of it are rejected and the app says "not synced". Publish
 * the rules before the bundle that uses the key.
 *
 * A preference that describes a *screen* rather than a *person* does not belong
 * here — see [ge.dakalebi.domain.repository.PreferencesRepository] and
 * [InterfaceScale] for why the television's interface size stays on the device.
 */
sealed class Setting<T : Any>(
    /** The field name on `users/{uid}`. Never rename one: it is stored data. */
    val key: String,
    /** What an account that has never chosen gets, on every device alike. */
    val default: T,
) {
    class Flag internal constructor(key: String, default: Boolean) : Setting<Boolean>(key, default)

    class Text internal constructor(key: String, default: String) : Setting<String>(key, default)

    /** [into] plus this setting's value from [from], when [from] has one. */
    internal fun copy(from: UserSettings, into: UserSettings): UserSettings =
        from[this]?.let { into.with(this, it) } ?: into

    override fun toString(): String = key

    companion object {
        // Declared before the settings so it exists when they register.
        private val registered = mutableListOf<Setting<*>>()

        /** Every setting that syncs, in declaration order. */
        val all: List<Setting<*>> get() = registered

        /**
         * The interface language, as a BCP-47 tag. Applied through the string
         * catalogue, which is also where an unknown tag is ignored. The default
         * is the catalogue's own starting language.
         */
        val Language = text("language", default = "ka")

        /**
         * Whether a finished episode rolls into the next one. It describes how
         * someone watches, not which screen they are holding.
         */
        val AutoplayNext = flag("autoplayNext", default = false)

        /**
         * Whether iPhone and iPad play in the system player instead of the web
         * one. Only those devices offer the choice or read it, so syncing it
         * costs every other device nothing. Default on, which is what they did
         * before there was a choice.
         */
        val UseNativePlayer = flag("useNativePlayer", default = true)

        /**
         * The rendition last picked from a quality menu, as its label ("720p").
         * Empty means no choice, and so does a label an episode does not offer:
         * either way the player starts on the best rendition there is. A screen
         * reads it once per episode, so a pick made on another device applies
         * from the next episode rather than switching the stream mid-play.
         */
        val PreferredQuality = text("preferredQuality", default = "")

        private fun flag(key: String, default: Boolean) = Flag(key, default).also(::register)

        private fun text(key: String, default: String) = Text(key, default).also(::register)

        private fun register(setting: Setting<*>) {
            check(registered.none { it.key == setting.key }) { "two settings share the key ${setting.key}" }
            registered += setting
        }
    }
}

/**
 * Values for some of the [Setting]s: what an account, or this device, has said.
 *
 * A setting with no value here means "no opinion", not "off" or "default". That
 * difference carries the sync: a write leaves the account's other settings
 * alone, a fresh account does not read as "autoplay off", and a device that
 * never chose does not push its default over a choice made elsewhere.
 *
 * Immutable, so it can sit in a single Compose state and be compared in tests.
 */
class UserSettings private constructor(private val values: Map<Setting<*>, Any>) {

    constructor() : this(emptyMap())

    operator fun <T : Any> get(setting: Setting<T>): T? {
        // Safe: [with] is the only way in, and it takes a T for a Setting<T>.
        @Suppress("UNCHECKED_CAST")
        return values[setting] as T?
    }

    /** The untyped value, for storage that holds every type alike. */
    fun valueOf(setting: Setting<*>): Any? = values[setting]

    fun <T : Any> with(setting: Setting<T>, value: T): UserSettings = UserSettings(values + (setting to value))

    /** These values with [other]'s laid over them. */
    operator fun plus(other: UserSettings): UserSettings = UserSettings(values + other.values)

    operator fun contains(setting: Setting<*>): Boolean = setting in values

    /** The settings this holds a value for. */
    val present: Set<Setting<*>> get() = values.keys

    fun isEmpty(): Boolean = values.isEmpty()

    override fun equals(other: Any?): Boolean = other is UserSettings && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String =
        values.entries.joinToString(prefix = "UserSettings(", postfix = ")") { "${it.key}=${it.value}" }
}

/**
 * What a read of the settings document found.
 *
 * "Absent" and "could not read" must not collapse into one answer: treating a
 * failed read as an empty account would let a device with a stale local value
 * overwrite the real setting on the next network blip.
 */
sealed interface SettingsLoad {
    data class Found(val settings: UserSettings) : SettingsLoad
    data object Missing : SettingsLoad
    data object Failed : SettingsLoad
}
