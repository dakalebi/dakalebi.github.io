package ge.dakalebi.domain

import ge.dakalebi.presentation.ToastKind
import ge.dakalebi.presentation.ToastStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dismissal timer is the only logic in the store, and it moved from
 * `window.setTimeout` to a coroutine on a scope the store no longer owns. These
 * pin the behaviour that change could plausibly break.
 *
 * `delay` inside `runTest` runs on virtual time, so these are instant. Waits are
 * one millisecond past the duration under test, which keeps the assertion from
 * depending on the order two timers scheduled for the same instant resume in.
 */
class ToastStoreTest {

    @Test
    fun a_message_is_on_screen_before_its_timer_runs() = runTest {
        val toasts = ToastStore(this)

        toasts.show("hello", durationMs = 1000)

        assertEquals(listOf("hello"), toasts.items.map { it.message })
    }

    @Test
    fun a_message_clears_itself_when_its_time_is_up() = runTest {
        val toasts = ToastStore(this)
        toasts.show("hello", durationMs = 1000)

        delay(1001)

        assertTrue(toasts.items.isEmpty())
    }

    /**
     * One scope now drives every dismissal, so the risk the old
     * one-`setTimeout`-per-toast shape did not have is a timer removing the
     * wrong message. Two overlapping toasts with different lifetimes is the
     * cheapest way to catch that.
     */
    @Test
    fun a_short_message_does_not_take_a_longer_one_with_it() = runTest {
        val toasts = ToastStore(this)
        toasts.show("short", durationMs = 1000)
        toasts.show("long", durationMs = 5000)

        delay(1001)

        assertEquals(listOf("long"), toasts.items.map { it.message })

        delay(4000)

        assertTrue(toasts.items.isEmpty())
    }

    @Test
    fun ok_and_error_carry_their_kind() = runTest {
        val toasts = ToastStore(this)

        toasts.ok("saved")
        toasts.error("rejected")

        assertEquals(listOf(ToastKind.Ok, ToastKind.Error), toasts.items.map { it.kind })
    }
}
