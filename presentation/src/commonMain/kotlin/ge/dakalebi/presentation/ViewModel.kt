package ge.dakalebi.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A state holder whose whole state is one immutable value.
 *
 * The pattern the stores move to, one at a time. [state] is a `StateFlow` of a
 * plain data class, so a test reads it without Compose, a second front end
 * collects it however it draws, and a Compose screen reads it with
 * `collectAsState()`. Every change goes through [setState], one whole value to
 * the next, so a reader never sees half of a change.
 *
 * Our own rather than a library's: this is all of it, and the ViewModels so far
 * live as long as the page, so there is no lifecycle to manage. A subclass takes
 * the scope its work runs in.
 */
abstract class ViewModel<S>(initialState: S) {
    private val mutableState = MutableStateFlow(initialState)

    val state: StateFlow<S> = mutableState.asStateFlow()

    protected fun setState(reduce: S.() -> S) = mutableState.update(reduce)
}
