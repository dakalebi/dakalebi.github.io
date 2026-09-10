package ge.dakalebi.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.core.BuildInfo
import ge.dakalebi.core.formatDateTime
import ge.dakalebi.domain.model.InterfaceScale
import ge.dakalebi.di.catalog
import ge.dakalebi.di.preferences
import ge.dakalebi.di.session
import ge.dakalebi.di.settings
import ge.dakalebi.di.toasts
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.ui.tv.focus.FocusAxis
import ge.dakalebi.ui.tv.focus.focusGroup
import ge.dakalebi.ui.tv.focus.focusItem
import io.github.bchmsl.keel.components.ButtonVariant
import io.github.bchmsl.keel.components.SegmentedStyle
import io.github.bchmsl.keel.dom.buttonClasses
import io.github.bchmsl.keel.dom.classNames
import io.github.bchmsl.keel.dom.segmentedClasses
import io.github.bchmsl.keel.dom.segmentedItemClasses
import io.github.bchmsl.keel.dom.segmentedLabelClasses
import io.github.bchmsl.keel.dom.switchClasses
import io.github.bchmsl.keel.dom.switchKnobClasses
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Language, interface size, autoplay, who is signed in, and which build this is.
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
    val prefs = preferences()
    val session = session()
    val catalog = catalog()
    val toasts = toasts()
    val scope = rememberCoroutineScope()

    var confirmSignOut by remember { mutableStateOf(false) }

    Div({ classes("tv-settings"); focusGroup("settings", FocusAxis.Y) }) {
        H1({ classes("tv-h") }) { Text(S.settings.caps) }

        // Language. A row of choices rather than a toggle, because there will be
        // more than two: the string catalogue is an interface, so a third locale
        // is a file, not a redesign.
        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.language.caps) }
            // keel's segmented control, built from its class names rather than by
            // calling `SegmentedControl`. The composable renders a real radio input per
            // choice, and this shell cannot use one: focus here is a cursor the app
            // moves itself, and a native input brings its own activation behaviour to
            // fight it. So the chips are flat divs carrying `aria-checked`, which is the
            // second branch keel's selected-state rules read - the same state assistive
            // technology reports for `role="radio"`, so nothing can drift.
            //
            // What it gives up by not being the composable is arrow-key navigation, and
            // that costs nothing here: the D-pad engine below already owns Left and
            // Right on this row.
            Div({
                classNames("tv-seg", segmentedClasses(SegmentedStyle.Rail))
                focusGroup("language", FocusAxis.X)
                actsAsOptionGroup(S.language)
            }) {
                I18n.available.forEach { language ->
                    val selected = language.tag == I18n.current.tag
                    Div({ classNames(segmentedItemClasses()) }) {
                        Div({
                            classNames("tv-seg-item", segmentedLabelClasses())
                            focusItem("lang-${language.tag}", entry = selected)
                            // `radio`, not a pressed button. `aria-pressed` was here and
                            // was inert: it only has meaning on `role="button"`, which a
                            // bare `Div` is not, so nothing announced the selection at
                            // all. It is now also what paints the chip.
                            actsAsOption(selected)
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
        }

        // Interface size. Reads from PreferencesStore, not SettingsStore, and that is
        // the whole distinction between this row and the two around it: language and
        // autoplay describe a person and follow the account to every device, while this
        // describes a screen. A phone and a television signed in to the same account
        // need different answers, so syncing it would make choosing on one wrong on the
        // other. See `domain/model/InterfaceScale.kt`.
        //
        // Same construction as the language row above, for the same reasons. The change
        // applies on the press rather than on leaving the row, so the viewer is choosing
        // by looking at the result instead of at a number — which is the only honest way
        // to pick a size, since the whole point is that no measurement the page can take
        // predicts what is comfortable.
        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.interfaceSize.caps) }
            Div({
                classNames("tv-seg", segmentedClasses(SegmentedStyle.Rail))
                focusGroup("scale", FocusAxis.X)
                actsAsOptionGroup(S.interfaceSize)
            }) {
                InterfaceScale.steps.forEach { step ->
                    val selected = step == prefs.interfaceScale
                    Div({ classNames(segmentedItemClasses()) }) {
                        Div({
                            classNames("tv-seg-item", "mono", segmentedLabelClasses())
                            focusItem("scale-$step", entry = selected)
                            actsAsOption(selected)
                            onClick {
                                if (!selected) {
                                    prefs.setInterfaceScale(step)
                                    applyInterfaceScale(step)
                                }
                            }
                        }) { Text("$step%") }
                    }
                }
            }
        }

        // Autoplay. Reads from SettingsStore, not PreferencesStore: this one syncs
        // between devices, which is why it lives on the account document.
        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.autoplayTitle.caps) }
            // keel's switch, again by class name rather than by composable: `Switch`
            // renders a real `<button>`, and the argument against one here is the same
            // as for the chips above. `role="switch"` and `aria-checked` were already
            // set, and `aria-checked` is what keel keys the "on" colour off - so the
            // local `.on` class it used to need has simply gone.
            Div({
                classNames(switchClasses())
                focusItem("autoplay")
                attr("role", "switch")
                attr("aria-checked", settings.autoplayNext.toString())
                onClick {
                    settings.setAutoplayNext(scope, !settings.autoplayNext) {
                        toasts.error(S.settingNotSynced)
                    }
                }
            }) { Div({ classNames(switchKnobClasses()) }) }
        }

        Div({ classes("tv-row") }) {
            Span({ classes("tv-row-label") }) { Text(S.signOut.caps) }
            Div({
                classNames("tv-btn", buttonClasses(ButtonVariant.Outline))
                focusItem("sign-out")
                // The visible text is the account, not the action, so the name has to
                // carry both — "Sign out" alone would hide which account it signs out.
                actsAsButton(session.email?.let { "${S.signOut}: $it" } ?: S.signOut)
                // Asks first rather than signing out on the press: on a shared television
                // a stray OK on this row should not drop the household back to a login.
                onClick { confirmSignOut = true }
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

    if (confirmSignOut) {
        TvConfirmDialog(
            title = S.signOutConfirmTitle.caps,
            body = S.signOutConfirmBody,
            confirmLabel = S.signOut.caps,
            onConfirm = {
                confirmSignOut = false
                scope.launch { session.signOut() }
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}
