package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.EmptyState
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.presentation.DashboardViewModel
import ge.dakalebi.presentation.ErrorMessages
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** A tab's title row: the title in the display role, and [end] on the right. */
@Composable
internal fun PageHead(title: String, end: (@Composable () -> Unit)? = null) {
    Div({ classes("page-head") }) {
        H1({ classes("page-title") }) { Text(title.caps) }
        if (end != null) Div({ classes("page-head-end") }) { end() }
    }
}

/**
 * One titled group on a screen. [meta] is a short figure beside the title ("20 / 96
 * watched"); [end] is a link or a menu on the right.
 */
@Composable
internal fun PageSection(
    title: String,
    meta: String? = null,
    end: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Section({ classes("page-section") }) {
        Div({ classes("section-head") }) {
            H2({ classes("section-title") }) { Text(title.caps) }
            if (meta != null) Span({ classes("section-meta") }) { Text(meta) }
            if (end != null) Div({ classes("section-end") }) { end() }
        }
        content()
    }
}

/**
 * The catalog's three ways of having nothing to show, then [content] once it has
 * episodes: [loading] while it loads, a retry when it failed, and an empty state when
 * the catalog has no episodes yet (with the download for an admin).
 *
 * The retry and the download are the old dashboard's, with the same words.
 */
@Composable
internal fun CatalogGate(
    dashboard: DashboardViewModel,
    loading: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val catalog = catalog()
    val session = session()
    val state = sessionState()

    when {
        catalog.loading -> Div({
            classes("page-loading")
            // One "busy" for the whole placeholder: each shape is hidden from a screen
            // reader, because twenty of them announcing themselves says less.
            attr("aria-busy", "true")
        }) {
            Span({ classes("visually-hidden") }) { Text(S.loading) }
            loading()
        }

        catalog.loadError != null -> EmptyState(
            title = S.loadFailedEyebrow,
            body = ErrorMessages.catalogLoad(catalog.loadError),
            actionLabel = S.retry,
            onAction = session::retryCatalogLoad,
            glyph = Glyph.CircleAlert,
        )

        catalog.episodes.isEmpty() -> EmptyState(
            title = S.emptyEyebrow,
            body = when {
                !state.isAdmin -> "${S.emptyBody} ${S.waitForAdmin}"
                catalog.refreshing -> catalog.refreshNote ?: S.refreshing
                else -> S.emptyBody
            },
            actionLabel = S.downloadEpisodes.takeIf { state.isAdmin && !catalog.refreshing },
            onAction = dashboard::refreshCatalog,
            glyph = Glyph.LayoutGrid,
        )

        else -> content()
    }
}
