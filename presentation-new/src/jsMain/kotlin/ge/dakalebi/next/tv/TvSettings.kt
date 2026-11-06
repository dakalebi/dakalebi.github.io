package ge.dakalebi.next.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.session
import ge.dakalebi.di.sessionState
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.domain.model.InterfaceScale
import ge.dakalebi.domain.model.Setting
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.next.components.Badge
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.ProgressBar
import ge.dakalebi.next.components.SegmentOption
import ge.dakalebi.next.components.Segmented
import ge.dakalebi.next.components.Switch
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.focus.focusItem
import ge.dakalebi.next.web.AppBuild
import ge.dakalebi.presentation.DashboardViewModel
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Settings on TV: who is watching and how far they are, the language, the interface
 * size, autoplay, and which build this is.
 *
 * One column of D-pad stops. The interface size applies on each press, so the choice
 * can be seen before it is kept; it is this television's alone, while the language and
 * autoplay follow the account.
 */
@Composable
internal fun TvSettings(dashboard: DashboardViewModel, build: AppBuild) {
    val catalog = catalog()
    val settings = settings()
    val preferences = preferences()
    val toasts = toasts()
    val stats = catalog.stats

    TvHead(S.settings)

    Div({ classes("tv-settings") }) {
        TvAccount(dashboard)

        Div({ classes("tv-settings-progress") }) {
            Div({ classes("tv-stats") }) {
                TvStat("${stats.watched} / ${stats.total}", S.statWatched)
                TvStat("${stats.started}", S.statStarted)
                TvStat("${stats.percent}%", S.statProgress)
            }
            ProgressBar(value = stats.percent / 100.0, label = S.statProgress, valueText = "${stats.percent}%")
        }

        Segmented(
            label = S.language,
            options = I18n.available.map { language ->
                SegmentOption(language.tag, language.caps(language.endonym), lang = language.tag)
            },
            selected = I18n.current.tag,
            onSelect = { tag -> settings.set(Setting.Language, tag) { toasts.error(S.settingNotSynced) } },
            tvKey = "lang",
        )

        Segmented(
            label = S.interfaceSize,
            options = InterfaceScale.steps.map { step -> SegmentOption(step, "$step%") },
            selected = InterfaceScale.normalise(preferences.interfaceScale),
            onSelect = { step ->
                preferences.setInterfaceScale(step)
                applyInterfaceScale(step)
            },
            tvKey = "scale",
        )

        Div({ focusGroup("settings-autoplay", FocusAxis.Y) }) {
            Switch(
                label = S.autoplayTitle,
                checked = settings[Setting.AutoplayNext],
                onCheckedChange = {
                    settings.toggle(Setting.AutoplayNext) { toasts.error(S.settingNotSynced) }
                },
                attrs = { focusItem("autoplay") },
            )
        }

        Footer({ classes("tv-settings-foot") }) {
            P { Text(S.lastRefreshed(formatDateTime(catalog.meta?.lastRefreshAtMillis))) }
            TvBuildStamp(build)
        }
    }
}

/**
 * The account, or a guest's way to one. Signing out asks first: undoing it costs a
 * password typed with a remote.
 */
@Composable
private fun TvAccount(dashboard: DashboardViewModel) {
    val session = session()
    val state = sessionState()
    var confirmSignOut by remember { mutableStateOf(false) }

    Div({ classes("tv-account") }) {
        if (state.isGuest) {
            Div({ classes("tv-account-who") }) {
                Badge(S.guest)
                P({ classes("tv-account-note") }) { Text(S.guestProgressNote) }
            }
        } else {
            P({ classes("tv-account-email") }) { Text(state.email.orEmpty()) }
        }
        Div({ classes("tv-account-actions"); focusGroup("settings-account", FocusAxis.X) }) {
            if (state.isGuest) {
                Button(
                    S.signIn,
                    onClick = session::leaveGuestMode,
                    variant = ButtonVariant.Primary,
                    attrs = { focusItem("sign-in", entry = true) },
                )
            } else {
                Button(
                    S.signOut,
                    onClick = { confirmSignOut = true },
                    attrs = { focusItem("sign-out", entry = true) },
                )
                Button(
                    S.continueAsGuest,
                    onClick = dashboard::switchToGuest,
                    variant = ButtonVariant.Quiet,
                    attrs = { focusItem("to-guest") },
                )
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

@Composable
private fun TvStat(value: String, label: String) {
    Div({ classes("tv-stat") }) {
        Span({ classes("tv-stat-value") }) { Text(value) }
        Span({ classes("tv-stat-label") }) { Text(label) }
    }
}

/** Which build is running and when it went out, with its commit. Not a link: a remote cannot follow one. */
@Composable
private fun TvBuildStamp(build: AppBuild) {
    val whenText = formatDateTime(build.publishedAtMillis.takeIf { it > 0 })
    P({ classes("tv-build") }) {
        Span { Text(if (build.isDev) whenText else S.appVersion(build.number, whenText)) }
        Span({ classes("tv-build-commit") }) { Text(build.commit) }
        if (build.isDev) Badge("dev")
    }
}
