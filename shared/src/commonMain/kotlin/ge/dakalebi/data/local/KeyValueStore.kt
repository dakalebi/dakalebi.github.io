package ge.dakalebi.data.local

/**
 * A string keyed by a string, on this device.
 *
 * The smallest surface [LocalCatalogCache] needs, so the rule that decides
 * whether a cached catalog is still valid does not have to know whether it is
 * sitting in `localStorage`, a preferences file or a plain file.
 *
 * Every method is expected to swallow nothing: a store that cannot be read or
 * written should throw, and the caller decides what that costs.
 */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}
