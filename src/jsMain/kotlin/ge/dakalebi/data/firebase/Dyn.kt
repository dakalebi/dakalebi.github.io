package ge.dakalebi.data.firebase

/**
 * Small helpers for reading untyped Firestore document data.
 *
 * Firestore hands back plain JS objects where every number is a double and any
 * field may be absent, so every read goes through these rather than a raw cast.
 */

internal fun dynString(value: dynamic): String? {
    if (value == null || value == undefined) return null
    val s = value as? String ?: return null
    return s.ifBlank { null }
}

internal fun dynDouble(value: dynamic): Double? {
    if (value == null || value == undefined) return null
    val n = value as? Number ?: return null
    val d = n.toDouble()
    return if (d.isNaN()) null else d
}

internal fun dynInt(value: dynamic): Int? = dynDouble(value)?.toInt()

internal fun dynBool(value: dynamic): Boolean = value == true

/** Reads a flat `{ "1080p": "https://..." }` map, preserving insertion order. */
internal fun dynStringMap(value: dynamic): Map<String, String> {
    if (value == null || value == undefined) return emptyMap()
    val keys = js("Object").keys(value) as Array<String>
    val out = LinkedHashMap<String, String>(keys.size)
    for (key in keys) {
        val entry = dynString(value[key])
        if (entry != null) out[key] = entry
    }
    return out
}

/** Builds an empty JS object literal to populate for a Firestore write. */
internal fun jsObject(): dynamic = js("({})")

/** `{ merge: true }` for [ge.dakalebi.data.firebase.externals.setDoc]. */
internal fun mergeOption(): dynamic = js("({ merge: true })")
