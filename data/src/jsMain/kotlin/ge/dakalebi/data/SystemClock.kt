package ge.dakalebi.data

import ge.dakalebi.domain.Clock
import kotlin.js.Date

/** The real clock — the only implementation that reads the machine's time. */
object SystemClock : Clock {
    override fun nowMillis(): Double = Date.now()
}
