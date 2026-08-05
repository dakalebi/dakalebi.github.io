package ge.dakalebi.web.device

import ge.dakalebi.data.local.KeyValueStore

/**
 * `localStorage`, which survives a reload and a browser restart.
 *
 * Throws rather than reporting failure, which is deliberate: private-mode browsers and a full
 * quota both raise here, and the caller is the only one that knows whether losing the value
 * costs correctness or only speed. [ge.dakalebi.data.local.LocalCatalogCache], the one
 * caller, treats every failure as a lost cache.
 */
class WasmKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = localStorageGet(key)

    override fun set(key: String, value: String) = localStorageSet(key, value)

    override fun remove(key: String) = localStorageRemove(key)
}

internal fun localStorageGet(key: String): String? = js("localStorage.getItem(key)")

internal fun localStorageSet(key: String, value: String) {
    js("localStorage.setItem(key, value)")
}

internal fun localStorageRemove(key: String) {
    js("localStorage.removeItem(key)")
}

internal fun sessionStorageGet(key: String): String? = js("sessionStorage.getItem(key)")

internal fun sessionStorageSet(key: String, value: String) {
    js("sessionStorage.setItem(key, value)")
}
