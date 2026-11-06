package ge.dakalebi.next.drive

import ge.dakalebi.next.useFixtures
import ge.dakalebi.presentation.Route
import ge.dakalebi.presentation.Router

/**
 * Driving mode, as a link from the web page: its home, or [route] inside it.
 *
 * Relative, so a pull request's copy at `/pr/<n>/` opens its own car mode rather than
 * production's.
 * A fixture page links to the fixture, so following the link while checking a change
 * never lands on the real sign-in.
 */
fun driveHref(route: Route? = null): String =
    (if (useFixtures) "drive/?ui=drive-demo" else "drive/") + (route?.let { Router.href(it) } ?: "")
