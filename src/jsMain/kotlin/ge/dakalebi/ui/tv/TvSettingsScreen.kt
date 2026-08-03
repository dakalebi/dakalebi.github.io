package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import ge.dakalebi.core.BuildInfo
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.di.catalog
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.classNames
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Language, autoplay, who is signed in, and which build this is.
 *
 * A screen rather than the web app's bottom sheet. A sheet on a television wastes
 * most of the panel and puts a scrim between the viewer and everything else, and
 * the focus stack would have to treat it as a trap for no gain — going somewhere
 * and coming back is what a remote is good at.
 *
 * Absent by design: the catalog refresh, mark-season-watched, reset-season,
 * reset-all, and the Apple native-player switch. The first four are admin or
 * destructive work that belongs on a device with a keyboard; the last is
 * meaningless here.
 */
@Composable
fun TvSettingsScreen() {
    val settings = settings()
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val scope = rememberCoroutineScope()

    Div({ classes("tv-settings"); focusGroup("settings", FocusAxis.Y) }) {
        H1({ classes("tv-h") }) { Text(S.settings.caps) }

        // Language. A row of choices rather than a toggle, because there will be
        // more than two: the string catalogue is an interface, so a third locale
        // is a file, not a redesign.
        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.language.caps) }
            Div({ classes("tv-seg"); focusGroup("language", FocusAxis.X) }) {
                I18n.available.forEach { language ->
                    val selected = language.tag == I18n.current.tag
                    Div({
                        classNames("tv-seg-item", if (selected) "on" else null)
                        focusItem("lang-${language.tag}", entry = selected)
                        attr("aria-pressed", selected.toString())
                        // Its own tag, so the label picks the right face and a
                        // screen reader picks the right voice for it.
                        attr("lang", language.tag)
                        onClick {
                            if (!selected) {
                                settings.setLanguage(scope, language.tag) {
                                    toasts.error(S.settingNotSynced)
                                }
                            }
                        }
                        // Cased by its own language, so "ქართული" is Mtavruli while
                        // "English" is left alone.
                    }) { Text(language.caps(language.endonym)) }
                }
            }
        }

        // Autoplay. Reads from SettingsStore, not PreferencesStore: this one syncs
        // between devices, which is why it lives on the account document.
        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.autoplayTitle.caps) }
            Div({
                classNames("tv-switch", if (settings.autoplayNext) "on" else null)
                focusItem("autoplay")
                attr("role", "switch")
                attr("aria-checked", settings.autoplayNext.toString())
                onClick {
                    settings.setAutoplayNext(scope, !settings.autoplayNext) {
                        toasts.error(S.settingNotSynced)
                    }
                }
            }) { Div() }
        }

        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.signOut.caps) }
            Div({
                classes("tv-btn")
                focusItem("sign-out")
                onClick { scope.launch { session.signOut() } }
            }) { Text(session.email ?: S.signOut.caps) }
        }

        // Build identity, which is the whole reason it is in the drawer on the web
        // too: on a device you cannot open devtools on, this is how you find out
        // which version is actually running.
        Div({ classes("tv-foot", "mono") }) {
            Text("${BuildInfo.BUILD_NUMBER} · ${BuildInfo.COMMIT}")
            Text(" · ${formatDateTime(BuildInfo.PUBLISHED_AT_MILLIS)}")
        }
        Div({ classes("tv-foot") }) {
            Text(S.lastRefreshed(formatDateTime(catalog.meta?.lastRefreshAtMillis)))
        }
    }
}
