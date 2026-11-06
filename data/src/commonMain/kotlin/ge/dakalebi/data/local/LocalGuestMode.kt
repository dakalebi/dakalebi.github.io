package ge.dakalebi.data.local

import ge.dakalebi.core.Log
import ge.dakalebi.domain.repository.GuestModeRepository

/**
 * The guest choice, on this device.
 *
 * A storage failure reads as "not a guest" and writes as a no-op. The cost is
 * landing on the sign-in form after a reload, which is exactly where a device
 * that cannot remember anything should land.
 */
class LocalGuestMode(private val store: KeyValueStore) : GuestModeRepository {

    override fun isGuest(): Boolean = runCatching { store.get(KEY) == ON }
        .onFailure { Log.w("guest", "device storage unreadable", it) }
        .getOrDefault(false)

    override fun setGuest(value: Boolean) {
        runCatching { if (value) store.set(KEY, ON) else store.remove(KEY) }
            .onFailure { Log.w("guest", "could not remember the guest choice", it) }
    }

    private companion object {
        const val KEY = "guest_mode_v1"
        const val ON = "1"
    }
}
