package ge.dakalebi.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] into a store's Compose state, handing each value over in the
 * call that produced it rather than on a later turn of the event loop.
 *
 * Undispatched and unconfined, so a store behaves as it did when the repositories
 * took callbacks. Two things depend on that. A screen that changes a setting reads
 * the new value back in the same event. And the fixture graph reports its account
 * synchronously, so `Main.kt` knows the viewer before the first paint, which is
 * the only paint a headless browser draws.
 *
 * Every store follows from the app's scope, which lives as long as the page.
 */
internal fun <T> CoroutineScope.follow(flow: Flow<T>, onEach: (T) -> Unit): Job =
    launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        flow.collect { onEach(it) }
    }
