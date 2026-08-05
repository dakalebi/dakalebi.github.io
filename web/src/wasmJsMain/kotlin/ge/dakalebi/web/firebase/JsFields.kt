package ge.dakalebi.web.firebase

import kotlin.js.get

/**
 * Reading and writing untyped Firestore document data on wasmJs.
 *
 * The wasm twin of the root app's `Dyn.kt`, with the same job: Firestore hands back plain
 * JS objects where every number is a double and any field may be absent, so every read
 * goes through here rather than a raw cast.
 *
 * The difference is where the check happens. `dynamic` let the js(IR) app test the type in
 * Kotlin; wasm has no such type, so each helper asks JavaScript the question and only an
 * already-narrowed value crosses the boundary. A field of the wrong type therefore reads as
 * absent rather than as a cast that fails somewhere later.
 *
 * Nullable numbers and booleans come back as [JsNumber] / [JsBoolean] and are unwrapped on
 * this side, because wasm interop cannot return a nullable `Double` or `Boolean`.
 */

/** A non-blank string field. Absent, wrong type and empty all read as null. */
internal fun readString(data: JsAny, key: String): String? =
    js("(typeof data[key] === 'string' && data[key].length > 0 ? data[key] : null)")

private fun rawNumber(data: JsAny, key: String): JsNumber? =
    js("(typeof data[key] === 'number' && isFinite(data[key]) ? data[key] : null)")

/** A finite number field. `NaN` and `Infinity` read as null, as they are never valid here. */
internal fun readDouble(data: JsAny, key: String): Double? = rawNumber(data, key)?.toDouble()

internal fun readInt(data: JsAny, key: String): Int? = readDouble(data, key)?.toInt()

private fun rawBool(data: JsAny, key: String): JsBoolean? =
    js("(typeof data[key] === 'boolean' ? data[key] : null)")

/** True only for a stored `true`. Absent reads as false. */
internal fun readBool(data: JsAny, key: String): Boolean = rawBool(data, key)?.toBoolean() == true

/** Distinguishes a stored `false` from an absent field, which [readBool] deliberately does not. */
internal fun readBoolOrNull(data: JsAny, key: String): Boolean? = rawBool(data, key)?.toBoolean()

private fun readObject(data: JsAny, key: String): JsAny? =
    js("(typeof data[key] === 'object' && data[key] !== null ? data[key] : null)")

private fun keysOf(target: JsAny): JsArray<JsString> = js("Object.keys(target)")

/** Reads a flat `{ "1080p": "https://..." }` map, preserving Firestore's key order. */
internal fun readStringMap(data: JsAny, key: String): Map<String, String> {
    val nested = readObject(data, key) ?: return emptyMap()
    val names = keysOf(nested)
    val out = LinkedHashMap<String, String>(names.length)
    for (index in 0 until names.length) {
        val name = names[index]?.toString() ?: continue
        val value = readString(nested, name) ?: continue
        out[name] = value
    }
    return out
}

// ------------------------------------------------------------------------ writing

/** An empty JS object literal to populate for a Firestore write. */
internal fun newObject(): JsAny = js("({})")

private fun putNull(target: JsAny, key: String) {
    js("target[key] = null")
}

internal fun putString(target: JsAny, key: String, value: String) {
    js("target[key] = value")
}

internal fun putStringOrNull(target: JsAny, key: String, value: String?) {
    if (value == null) putNull(target, key) else putString(target, key, value)
}

internal fun putInt(target: JsAny, key: String, value: Int) {
    js("target[key] = value")
}

internal fun putIntOrNull(target: JsAny, key: String, value: Int?) {
    if (value == null) putNull(target, key) else putInt(target, key, value)
}

internal fun putDouble(target: JsAny, key: String, value: Double) {
    js("target[key] = value")
}

internal fun putDoubleOrNull(target: JsAny, key: String, value: Double?) {
    if (value == null) putNull(target, key) else putDouble(target, key, value)
}

internal fun putBool(target: JsAny, key: String, value: Boolean) {
    js("target[key] = value")
}

internal fun putObject(target: JsAny, key: String, value: JsAny) {
    js("target[key] = value")
}

/**
 * `{ merge: true }` or `{}` for `setDoc`.
 *
 * The js(IR) externals had a `setDoc` overload without options; wasm resolves one JS import
 * per external declaration, so there is a single three-argument form and the caller always
 * says which it means. `{}` is a valid `SetOptions` and means "overwrite", exactly as
 * omitting the argument did.
 */
internal fun setOptions(merge: Boolean): JsAny = js("(merge ? { merge: true } : {})")
