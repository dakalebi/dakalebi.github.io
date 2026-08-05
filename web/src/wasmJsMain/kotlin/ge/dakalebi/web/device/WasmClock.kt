package ge.dakalebi.web.device

import ge.dakalebi.domain.Clock

/** The real clock — the only implementation that reads the machine's time. */
object WasmClock : Clock {
    override fun nowMillis(): Double = jsNowMillis()
}

private fun jsNowMillis(): Double = js("Date.now()")
