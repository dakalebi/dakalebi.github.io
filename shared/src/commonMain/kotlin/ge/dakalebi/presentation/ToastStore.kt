package ge.dakalebi.presentation

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ToastKind { Plain, Ok, Error }

data class Toast(val id: Int, val message: String, val kind: ToastKind)

/**
 * Minimal replacement for sonner: a list of transient messages.
 *
 * The dismissal timer is a coroutine rather than `window.setTimeout`, which is
 * the only thing that tied this file to a browser. [scope] is supplied rather
 * than created here so it lives exactly as long as the graph that owns it, the
 * same arrangement [SettingsStore] already uses for its writes.
 */
class ToastStore(private val scope: CoroutineScope) {
    val items = mutableStateListOf<Toast>()
    private var nextId = 0

    fun show(message: String, kind: ToastKind = ToastKind.Plain, durationMs: Int = 3600) {
        val id = nextId++
        items.add(Toast(id, message, kind))
        scope.launch {
            delay(durationMs.toLong())
            items.removeAll { it.id == id }
        }
    }

    fun ok(message: String) = show(message, ToastKind.Ok)

    fun error(message: String) = show(message, ToastKind.Error)
}
