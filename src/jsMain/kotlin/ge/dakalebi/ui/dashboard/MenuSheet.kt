package ge.dakalebi.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.core.BuildInfo
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.player.isAppleMobile
import io.github.bchmsl.keel.components.Drawer
import io.github.bchmsl.keel.components.DrawerEdge
import io.github.bchmsl.keel.components.ProgressBar
import io.github.bchmsl.keel.components.ProgressBarSize
import io.github.bchmsl.keel.components.Segment
import io.github.bchmsl.keel.components.SegmentedControl
import io.github.bchmsl.keel.dom.classNames
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
fun MenuSheet(
    onClose: () -> Unit,
    onResetAll: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    busy: Boolean,
) {
    val session = session()
    val catalog = catalog()
    val stats = catalog.stats

    // The scrim, the Escape key, the slide, the panel and - new here - moving focus
    // into the drawer on open and back out on close are all keel's. Nothing of the
    // old `.sheet` rule survives as layout: every one of its twelve declarations was
    // the component.
    Drawer(onDismiss = onClose, ariaLabel = S.menu, edge = DrawerEdge.Left) {
        Div {
            Div({ classes("eyebrow-mut") }) { Text(S.menu.caps) }
            Div({ style { property("font-size", "13px"); property("color", "var(--tx-dim)") } }) {
                Text(session.email ?: "")
            }
        }

        Div({ classes("sheet-stats") }) {
            Stat("${stats.watched}/${stats.total}", S.statWatched.caps)
            Stat("${stats.started}", S.statStarted.caps)
            Stat("${stats.percent}%", S.statProgress.caps)
        }

        // `onMedia` even though there is no media here: the variant is really "track
        // against something whose colour this component cannot know", and on the
        // sheet's own dark fill keel's page-coloured track would be invisible. Same
        // two colours this bar already had.
        ProgressBar(
            fraction = stats.percent / 100.0,
            ariaLabel = S.statProgress,
            size = ProgressBarSize.Large,
            onMedia = true,
            attrs = { classes("hero-bar"); style { property("max-width", "none") } },
        )

        Div({ classes("sheet-list") }) {
            if (session.isAdmin) {
                Button({
                    classes("sheet-item")
                    if (catalog.refreshing) attr("disabled", "")
                    onClick { onRefresh() }
                }) {
                    Text(
                        if (catalog.refreshing) catalog.refreshNote ?: S.refreshing
                        else S.refreshEpisodes.caps,
                    )
                }
            }
            Button({
                classes("sheet-item", "danger")
                if (busy) attr("disabled", "")
                onClick { onResetAll() }
            }) { Text(S.resetAllProgress.caps) }
            Button({ classes("sheet-item"); onClick { onSignOut() } }) { Text(S.signOut.caps) }
        }

        SettingsSection()

        Div({ classes("sheet-foot") }) {
            Div { Text(S.lastRefreshed(formatDateTime(catalog.meta?.lastRefreshAtMillis))) }
            BuildStamp()
        }
    }
}

@Composable
private fun SettingsSection() {
    val prefs = preferences()
    val settings = settings()
    val toasts = toasts()
    val scope = rememberCoroutineScope()

    Div {
        Div({ classes("eyebrow-mut"); style { property("margin-bottom", "8px") } }) {
            Text(S.settings.caps)
        }
        Div({ classes("settings-list") }) {
            // Autoplay follows the account, not the device: it describes how
            // someone watches, not which screen they are holding.
            ToggleRow(
                title = S.autoplayTitle.caps,
                body = S.autoplayBody,
                checked = settings.autoplayNext,
                onToggle = {
                    settings.setAutoplayNext(scope, !settings.autoplayNext) {
                        toasts.error(S.settingNotSynced)
                    }
                },
            )

            // Offered only where there are two players to choose between.
            // Everywhere else the custom one is the only one there is, so the
            // switch would be a control that does nothing.
            if (isAppleMobile) {
                ToggleRow(
                    title = S.nativePlayerTitle.caps,
                    body = S.nativePlayerBody,
                    checked = prefs.useNativePlayer,
                    onToggle = { prefs.setUseNativePlayer(!prefs.useNativePlayer) },
                )
            }

            LanguagePicker()
        }
    }
}

/**
 * A setting whose row toggles a [io.github.bchmsl.keel.components.Switch]-styled
 * control.
 *
 * Not keel's actual `Switch` composable: that renders its own `<button
 * role="switch">`, and nesting one inside the row's own button - kept for its
 * bigger, easier tap target - would be a button inside a button. So the real
 * `role="switch"`/`aria-checked` pair lives on the row, which is what is
 * actually operable, and the same `aria-checked` is duplicated onto the inner
 * span purely so keel's `.switch[aria-checked='true']` rule paints it - that
 * copy carries no semantics of its own, since assistive technology only reads
 * `aria-checked` off an element that itself has a widget role.
 */
@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onToggle: () -> Unit) {
    Button({
        classes("toggle-row")
        attr("role", "switch")
        attr("aria-checked", checked.toString())
        onClick { onToggle() }
    }) {
        Div({ classes("lab") }) {
            Div { Text(title) }
            Span { Text(body) }
        }
        Span({ classNames("switch"); attr("aria-checked", checked.toString()) }) {
            Span({ classNames("switch__knob") })
        }
    }
}

/**
 * Language, as a segmented control rather than a dropdown.
 *
 * With two languages a select is more taps than choices. Each option is
 * written in its own language and cased by its own rules — Georgian in
 * Mtavruli like the rest of the chrome, English left alone — so the label you
 * are looking for reads correctly whichever language is currently active.
 */
@Composable
private fun LanguagePicker() {
    val settings = settings()
    val toasts = toasts()
    val scope = rememberCoroutineScope()
    val active = I18n.current.tag

    Div({ classes("setting-row") }) {
        Div({ classes("lab") }) { Div { Text(S.language.caps) } }
        SegmentedControl(
            segments = I18n.available.map { language ->
                Segment(
                    value = language.tag,
                    label = language.caps(language.endonym),
                    // Each option is written in its own language, so each carries its
                    // own `lang`. Without it a screen reader says "English" in the
                    // page's Georgian voice.
                    attrs = { attr("lang", language.tag) },
                )
            },
            selected = active,
            // No "is it already selected" guard any more, and none is needed: these
            // are real radios, and clicking the checked one fires no change event.
            onSelect = { tag ->
                settings.setLanguage(scope, tag) { toasts.error(S.settingNotSynced) }
            },
            ariaLabel = S.language,
            attrs = { classes("seg") },
        )
    }
}

/**
 * Which build is running, and when it went out.
 *
 * Two separate facts sit in this footer and they are easy to confuse: the line
 * above is when the *catalog* was last pulled from Formula, this one is when
 * the *app* was deployed. The commit hash is the part worth having when
 * something looks wrong — it says exactly what code is live, which a version
 * number invented for the occasion would not.
 */
@Composable
private fun BuildStamp() {
    val whenText = formatDateTime(BuildInfo.PUBLISHED_AT_MILLIS.takeIf { it > 0 })
    // "Version dev" alongside a DEV badge says the same thing twice, so a local
    // build shows only its timestamp and lets the badge carry the meaning.
    val label = if (BuildInfo.isDevBuild) whenText
    else S.appVersion(BuildInfo.BUILD_NUMBER, whenText)
    val url = BuildInfo.commitUrl

    Div({ classes("build-stamp") }) {
        if (url == null) {
            Span { Text(label) }
        } else {
            A(href = url, attrs = { target(ATarget.Blank); attr("rel", "noreferrer") }) {
                Text(label)
            }
        }
        Span({ classes("mono") }) { Text(BuildInfo.COMMIT) }
        // A locally-built bundle should never be mistaken for what CI shipped.
        if (BuildInfo.isDevBuild) Span({ classes("build-dev") }) { Text("dev") }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Div({ classes("stat") }) {
        Div({ classes("mono") }) { Text(value) }
        Span { Text(label) }
    }
}
