package ge.dakalebi.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.domain.model.UserSettings
import ge.dakalebi.domain.usecase.ChangeUserSetting
import ge.dakalebi.domain.usecase.ObserveUserSettings
import ge.dakalebi.domain.usecase.SyncUserSettings
import ge.dakalebi.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Account settings as Compose state. Every [Setting] goes through here, and
 * nothing here names one except the language, which has to reach the string
 * catalogue.
 *
 * The sync itself — this device's copy against the account, offline changes,
 * one write per setting — is the settings repository's, in `:data`. This holds
 * what it reports, so a screen that reads a setting recomposes when it changes.
 */
class SettingsStore(
    observeUserSettings: ObserveUserSettings,
    private val syncUserSettings: SyncUserSettings,
    private val changeUserSetting: ChangeUserSetting,
    /**
     * The app's own scope, never a screen's: [set] runs in it, so closing the
     * screen that made a change cannot cancel the report of a failed sync.
     */
    private val scope: CoroutineScope,
) {
    private val settings = observeUserSettings()

    /**
     * The values someone actually chose, here or on another device. One Compose
     * state for all of them: a screen that reads any setting recomposes when it
     * changes, and settings change rarely enough that finer state buys nothing.
     */
    var current: UserSettings by mutableStateOf(settings.value)
        private set

    init {
        scope.follow(settings) { next ->
            val language = next[Setting.Language]
            if (language != null && language != current[Setting.Language]) I18n.use(language)
            current = next
        }
    }

    /** The setting's value, or its default when nobody has chosen one. */
    operator fun <T : Any> get(setting: Setting<T>): T = current[setting] ?: setting.default

    /** Applies the language this device last saw. Safe before sign-in. */
    fun applyCachedLanguage() {
        current[Setting.Language]?.let(I18n::use)
    }

    /** Attaches to the signed-in account. */
    fun start(uid: String) = syncUserSettings(uid)

    /** Detaches. This device's unconfirmed changes stay marked for their account. */
    fun stop() = syncUserSettings(null)

    /**
     * Changes [setting] now and records it for the account.
     *
     * [onSyncFailed] fires when the write was rejected. The local change is not
     * conditional on it — a rules problem or a dead network should cost the
     * syncing, not the setting — but it must be visible, because failing
     * silently here looks exactly like "my other phone never updates". The
     * change stays marked as unconfirmed, so the next sync uploads it.
     *
     * Undispatched, so the change is on screen before this returns: the
     * repository applies it before it first suspends. Takes no scope on purpose
     * (see [scope]).
     */
    fun <T : Any> set(setting: Setting<T>, value: T, onSyncFailed: () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!changeUserSetting(setting, value)) onSyncFailed()
        }
    }

    fun toggle(setting: Setting<Boolean>, onSyncFailed: () -> Unit) =
        set(setting, !get(setting), onSyncFailed)
}
