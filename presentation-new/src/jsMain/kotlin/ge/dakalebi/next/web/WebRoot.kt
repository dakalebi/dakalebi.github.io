package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ge.dakalebi.di.catalog
import ge.dakalebi.di.router
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.S
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.gallery.Gallery
import ge.dakalebi.next.showGallery
import ge.dakalebi.next.components.ToastRegion
import ge.dakalebi.presentation.Route
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div

/**
 * Which build is running, for the foot of the Settings tab.
 *
 * Handed in by the entry point, which is the only module that sees the generated
 * `BuildInfo`. [commitUrl] is null when the build does not know its repository.
 */
class AppBuild(
    val number: String,
    val commit: String,
    val publishedAtMillis: Double,
    val commitUrl: String?,
) {
    /** A bundle built on someone's machine, never to be mistaken for what CI shipped. */
    val isDev: Boolean get() = number == "dev"
}

/**
 * The web root, drawn on `/`.
 *
 * The session decides the screen: a spinner until it has reported, the sign-in form
 * for nobody, and the app (tabs, screens) for an account or a guest. The rest is for
 * the whole page: the sign-in route follows the session, the tab names the open
 * episode, `lang` follows the language, and the admin check runs once per account.
 *
 * `?gallery` draws the component gallery instead.
 */
@Composable
fun WebRoot(build: AppBuild) {
    // The components on their own, for review: `/?gallery`.
    if (showGallery) {
        Gallery(shell = "web")
        return
    }

    val router = router()
    val session = session()
    val state = sessionState()
    val catalog = catalog()
    val route = router.current
    val viewer = state.viewer

    // Replaced, not pushed, so Back does not bounce between the form and the app.
    LaunchedEffect(state.loading, viewer, route) {
        if (state.loading) return@LaunchedEffect
        if (viewer == null) {
            if (route !is Route.Login) router.replace(Route.Login)
        } else if (route is Route.Login) {
            router.replace(Route.Dashboard)
        }
    }

    val title = (route as? Route.Watch)
        ?.let { catalog.byId(it.episodeId) }
        ?.let { S.episodeDocumentTitle(it.seasonNumber, it.episodeNumber) }
        ?: S.documentTitle
    LaunchedEffect(title) { document.title = title }

    // What a screen reader picks its voice from.
    LaunchedEffect(S.tag) { document.documentElement?.setAttribute("lang", S.tag) }

    // The account's uid, not the viewer's: a guest has no admin entry.
    LaunchedEffect(state.account?.uid) { session.refreshAdminRights() }

    when {
        state.loading -> {
            Div({ classes("web-starting") }) { Spinner() }
            ToastRegion(toasts().items)
        }
        viewer == null -> {
            SignInScreen()
            ToastRegion(toasts().items)
        }
        // The app draws its own toasts, above its tab bar.
        else -> AppShell(route, build)
    }
}
