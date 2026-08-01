package ge.dakalebi.presentation

import androidx.compose.runtime.mutableStateListOf
import kotlinx.browser.window

enum class ToastKind { Plain, Ok, Error }

data class Toast(val id: Int, val message: String, val kind: ToastKind)

/** Minimal replacement for sonner: a list of transient messages. */
class ToastStore {
    val items = mutableStateListOf<Toast>()
    private var nextId = 0

    fun show(message: String, kind: ToastKind = ToastKind.Plain, durationMs: Int = 3600) {
        val id = nextId++
        items.add(Toast(id, message, kind))
        window.setTimeout({ items.removeAll { it.id == id } }, durationMs)
    }

    fun ok(message: String) = show(message, ToastKind.Ok)

    fun error(message: String) = show(message, ToastKind.Error)
}
