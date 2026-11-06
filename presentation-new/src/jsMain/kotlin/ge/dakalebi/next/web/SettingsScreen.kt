package ge.dakalebi.next.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Badge
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.ProgressBar
import ge.dakalebi.next.components.SegmentOption
import ge.dakalebi.next.components.Segmented
import ge.dakalebi.next.components.Switch
import ge.dakalebi.next.drive.driveHref
import ge.dakalebi.next.player.isAppleMobile
import ge.dakalebi.presentation.DashboardConfirm
import ge.dakalebi.presentation.DashboardViewModel
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Settings: who is watching, how far they are, the two settings the web has, the
 * other ways to watch, and what is run on the whole catalog.
 *
 * Everything the old menu drawer held, with the same actions and the same words.
 * Not drawn by the design pass, so it is built from the tokens and components: plain
 * groups on the page, the amber button only for a guest's sign-in, and the danger
 * styling only on the reset.
 *
 * One addition: signing out asks first, as it does on TV, since undoing it costs a
 * password.
 */
@Composable
internal fun SettingsScreen(dashboard: DashboardViewModel, build: AppBuild) {
    val catalog = catalog()
    val ui by dashboard.state.collectAsState()

    PageHead(S.settings)

    AccountGroup(dashboard)
    ProgressGroup()
    PreferencesGroup()

    Div({ classes("settings-group", "settings-actions") }) {
        // A link to another page, not a route: driving mode is its own shell with its
        // own stylesheet, the way `/tv/` is.
        Button(S.driveMode, href = driveHref())
        if (sessionState().isAdmin) {
            Button(
                label = if (catalog.refreshing) catalog.refreshNote ?: S.refreshing else S.refreshEpisodes,
                onClick = dashboard::refreshCatalog,
                icon = Glyph.Download,
                busy = catalog.refreshing,
            )
        }
        Button(
            S.resetAllProgress,
            onClick = { dashboard.ask(DashboardConfirm.ResetAll) },
            variant = ButtonVariant.Danger,
            icon = Glyph.Trash2,
            disabled = ui.busy,
        )
    }

    Footer({ classes("settings-foot") }) {
        P { Text(S.lastRefreshed(formatDateTime(catalog.meta?.lastRefreshAtMillis))) }
        BuildStamp(build)
    }
}

/**
 * The account, or the guest's way to one. A signed-in viewer can also carry on as a
 * guest, which signs them out first.
 */
@Composable
private fun AccountGroup(dashboard: DashboardViewModel) {
    val session = session()
    val state = sessionState()
    var confirmSignOut by remember { mutableStateOf(false) }

    Div({ classes("settings-group") }) {
        if (state.isGuest) {
            Badge(S.guest)
            P({ classes("settings-note") }) { Text(S.guestProgressNote) }
            Div({ classes("settings-buttons") }) {
                Button(S.signIn, onClick = session::leaveGuestMode, variant = ButtonVariant.Primary)
            }
        } else {
            P({ classes("settings-email") }) { Text(state.email.orEmpty()) }
            Div({ classes("settings-buttons") }) {
                Button(S.signOut, onClick = { confirmSignOut = true })
                Button(S.continueAsGuest, onClick = dashboard::switchToGuest, variant = ButtonVariant.Quiet)
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = S.signOutConfirmTitle,
            body = S.signOutConfirmBody,
            confirmLabel = S.signOut,
            onConfirm = {
                confirmSignOut = false
                dashboard.signOut()
            },
            onDismiss = { confirmSignOut = false },
            danger = false,
        )
    }
}

/** The three figures the old menu showed, and the bar under them. */
@Composable
private fun ProgressGroup() {
    val stats = catalog().stats
    // No heading: the third figure is already called "progress".
    Div({ classes("settings-progress") }) {
        Div({ classes("stat-row") }) {
            Stat("${stats.watched} / ${stats.total}", S.statWatched)
            Stat("${stats.started}", S.statStarted)
            Stat("${stats.percent}%", S.statProgress)
        }
        ProgressBar(value = stats.percent / 100.0, label = S.statProgress, valueText = "${stats.percent}%")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Div({ classes("stat") }) {
        Span({ classes("stat-value") }) { Text(value) }
        Span({ classes("stat-label") }) { Text(label) }
    }
}

/**
 * The settings that follow the account to every device. No autoplay: the web has none
 * for now (the TV does).
 */
@Composable
private fun PreferencesGroup() {
    val settings = settings()
    val toasts = toasts()

    Div({ classes("settings-group") }) {
        // Offered only where there are two players to choose between. Everywhere else
        // the custom one is the only one, so the switch would do nothing. It still
        // syncs, so an iPhone and an iPad on one account agree.
        if (isAppleMobile) {
            Switch(
                label = S.nativePlayerTitle,
                description = S.nativePlayerBody,
                checked = settings[Setting.UseNativePlayer],
                onCheckedChange = {
                    settings.toggle(Setting.UseNativePlayer) { toasts.error(S.settingNotSynced) }
                },
            )
        }

        // Each option in its own language and cased by its own rules, so the one you
        // look for reads right whichever is active.
        Segmented(
            label = S.language,
            options = I18n.available.map { language ->
                SegmentOption(language.tag, language.caps(language.endonym), lang = language.tag)
            },
            selected = I18n.current.tag,
            onSelect = { tag -> settings.set(Setting.Language, tag) { toasts.error(S.settingNotSynced) } },
        )
    }
}

/**
 * Which build is running and when it went out, with its commit. Under the line that
 * says when the catalog was last pulled, which is a different date and easy to
 * confuse with this one.
 */
@Composable
private fun BuildStamp(build: AppBuild) {
    val whenText = formatDateTime(build.publishedAtMillis.takeIf { it > 0 })
    // "Version dev" next to a dev badge says it twice; a local build shows its time.
    val label = if (build.isDev) whenText else S.appVersion(build.number, whenText)
    val url = build.commitUrl

    P({ classes("build-stamp") }) {
        if (url == null) {
            Span { Text(label) }
        } else {
            A(url, { target(ATarget.Blank); attr("rel", "noreferrer") }) { Text(label) }
        }
        Span({ classes("build-commit") }) { Text(build.commit) }
        if (build.isDev) Badge("dev")
    }
}
