package ge.dakalebi.data.local

import kotlinx.browser.localStorage

/**
 * `localStorage`, which survives a reload and a browser restart.
 *
 * Throws rather than reporting failure, which is deliberate: private-mode
 * browsers and a full quota both raise here, and the caller is the only one that
 * knows whether losing the value costs correctness or only speed.
 */
class BrowserKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = localStorage.getItem(key)

    override fun set(key: String, value: String) = localStorage.setItem(key, value)

    override fun remove(key: String) = localStorage.removeItem(key)
}
