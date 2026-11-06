package ge.dakalebi.data.local

/**
 * A [KeyValueStore] that forgets everything with the page.
 *
 * For the fixture graph, which must start from the same state on every load: a
 * guest's progress written to `localStorage` there would leak into the next run.
 */
class InMemoryKeyValueStore : KeyValueStore {
    private val entries = mutableMapOf<String, String>()

    override fun get(key: String): String? = entries[key]

    override fun set(key: String, value: String) {
        entries[key] = value
    }

    override fun remove(key: String) {
        entries.remove(key)
    }
}
