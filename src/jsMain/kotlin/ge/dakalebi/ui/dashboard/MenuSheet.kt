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
import ge.dakalebi.ui.DismissOnEscape
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.player.isAppleMobile
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

    DismissOnEscape(onClose)
    Div({ classes("scrim"); onClick { onClose() } })
    Div({ classes("sheet") }) {
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

        Div({ classes("hero-bar"); style { property("max-width", "none") } }) {
            Div({ style { property("width", "${stats.percent}%") } })
        }

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

    Div {
        Div({ classes("eyebrow-mut"); style { property("margin-bottom", "8px") } }) {
            Text(S.settings.caps)
        }
        Div({ classes("settings-list") }) {
            Button({
                classes("toggle-row")
                onClick { prefs.setAutoplayNext(!prefs.autoplayNext) }
            }) {
                Div({ classes("lab") }) {
                    Div { Text(S.autoplayTitle.caps) }
                    Span { Text(S.autoplayBody) }
                }
                Div({ classNames("switch", if (prefs.autoplayNext) "on" else null) }) { Div() }
            }

            // Offered only where there are two players to choose between.
            // Everywhere else the custom one is the only one there is, so the
            // switch would be a control that does nothing.
            if (isAppleMobile) {
                Button({
                    classes("toggle-row")
                    onClick { prefs.setUseNativePlayer(!prefs.useNativePlayer) }
                }) {
                    Div({ classes("lab") }) {
                        Div { Text(S.nativePlayerTitle.caps) }
                        Span { Text(S.nativePlayerBody) }
                    }
                    Div({ classNames("switch", if (prefs.useNativePlayer) "on" else null) }) { Div() }
                }
            }

            LanguagePicker()
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
        Div({ classes("seg") }) {
            I18n.available.forEach { language ->
                val selected = language.tag == active
                Button({
                    classNames("seg-item", if (selected) "on" else null)
                    attr("aria-pressed", selected.toString())
                    attr("lang", language.tag)
                    onClick {
                        if (!selected) {
                            settings.setLanguage(scope, language.tag) {
                                toasts.error(S.languageNotSynced)
                            }
                        }
                    }
                }) {
                    Text(language.caps(language.endonym))
                }
            }
        }
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
