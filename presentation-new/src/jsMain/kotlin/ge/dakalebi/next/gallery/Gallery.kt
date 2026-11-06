package ge.dakalebi.next.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ge.dakalebi.domain.model.InterfaceScale
import ge.dakalebi.i18n.I18n
import ge.dakalebi.i18n.S
import ge.dakalebi.i18n.caps
import ge.dakalebi.next.components.Badge
import ge.dakalebi.next.components.Button
import ge.dakalebi.next.components.ButtonVariant
import ge.dakalebi.next.components.Chip
import ge.dakalebi.next.components.ConfirmDialog
import ge.dakalebi.next.components.EmptyState
import ge.dakalebi.next.components.Glyph
import ge.dakalebi.next.components.Icon
import ge.dakalebi.next.components.IconButton
import ge.dakalebi.next.components.IconButtonVariant
import ge.dakalebi.next.components.MenuButton
import ge.dakalebi.next.components.MenuEntry
import ge.dakalebi.next.components.MenuPlacement
import ge.dakalebi.next.components.Notice
import ge.dakalebi.next.components.Scrubber
import ge.dakalebi.next.components.ProgressBar
import ge.dakalebi.next.components.SegmentOption
import ge.dakalebi.next.components.Segmented
import ge.dakalebi.next.components.Sheet
import ge.dakalebi.next.components.Skeleton
import ge.dakalebi.next.components.SkeletonShape
import ge.dakalebi.next.components.Spinner
import ge.dakalebi.next.components.Switch
import ge.dakalebi.next.components.TextField
import ge.dakalebi.next.components.TextFieldKind
import ge.dakalebi.next.components.ToastRegion
import ge.dakalebi.next.components.Transport
import ge.dakalebi.next.components.tvItem
import ge.dakalebi.next.foundations.FoundationsGrid
import ge.dakalebi.next.tv.applyInterfaceScale
import ge.dakalebi.next.tv.focus.FocusAxis
import ge.dakalebi.next.tv.focus.focusGroup
import ge.dakalebi.next.tv.input.LocalTvInput
import ge.dakalebi.presentation.ToastKind
import ge.dakalebi.presentation.ToastStore
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement

/** The gallery's tabs. The gallery is a tool for us, so its own words are English. */
private enum class Tab(val label: String) {
    Base("Base"),
    Actions("Actions"),
    Inputs("Inputs"),
    Status("Status"),
    Overlays("Overlays"),
}

/**
 * Every component in every state, on one page per shell: what each page shows at
 * `?gallery`.
 *
 * It is the fixture the components are checked against. The same code runs on `/`,
 * `/drive/` (larger targets) and `/tv/` (the D-pad, the ring and Back), so a
 * change to a component or a token is seen on all three at once. The words on the
 * components come from `S`; the controls only show toasts and change local state. Nothing
 * here signs in, saves a setting or calls the network.
 *
 * [shell] is the name printed in the badge: "web", "tv" or "drive".
 */
@Composable
fun Gallery(shell: String) {
    val tv = LocalTvInput.current
    val scope = rememberCoroutineScope()
    val toasts = remember { ToastStore(scope) }
    var tab by remember { mutableStateOf(Tab.Actions) }
    // TV only: the interface size, previewed here and not saved.
    var scale by remember { mutableStateOf(currentInterfaceScale()) }

    // On TV, Back at the top of the app has nowhere to go in a browser; say so.
    DisposableEffect(tv) {
        tv?.onTopLevelBack = { toasts.show(S.backAtStart) }
        onDispose { tv?.onTopLevelBack = null }
    }

    Main({ classes("gl") }) {
        GalleryHeader(shell, scale)

        Segmented(
            label = "Gallery section",
            options = Tab.entries.map { SegmentOption(it, it.label, lang = "en") },
            selected = tab,
            onSelect = { tab = it },
            tvKey = "gallery-tabs",
            showLabel = false,
        )

        when (tab) {
            Tab.Base -> FoundationsGrid()
            Tab.Actions -> ActionsPanel(toasts)
            Tab.Inputs -> InputsPanel(toasts, scale, onScale = { scale = it; applyInterfaceScale(it) })
            Tab.Status -> StatusPanel(toasts)
            Tab.Overlays -> OverlaysPanel(toasts)
        }

        ToastRegion(toasts.items)
    }
}

@Composable
private fun GalleryHeader(shell: String, scale: Int) {
    // What 1rem is on this page, to one decimal: 16px on the web and in drive, and on TV
    // whatever the panel and the interface size make it. Measured again when the size
    // changes; the new value is already on the root element by then.
    val rootSize = remember(scale) {
        val px = document.documentElement
            ?.let { window.getComputedStyle(it).fontSize.removeSuffix("px").toDoubleOrNull() }
        px?.let { "${(it * 10).roundToInt() / 10.0}".removeSuffix(".0") + "px" }.orEmpty()
    }

    Header({ classes("gl-head") }) {
        H1({ classes("gl-title") }) { Text(S.seriesTitle.caps) }
        Div({ classes("gl-head-side") }) {
            P({ classes("gl-eyebrow"); attr("lang", "en") }) { Text("Presentation-New · components") }
            Span({ classes("gl-badge"); attr("lang", "en") }) {
                Text(shell.uppercase())
                Span({ classes("gl-badge-figure") }) { Text("1rem = $rootSize") }
            }
        }
    }
}

/** The interface size the page was opened with, read back from the root element. */
private fun currentInterfaceScale(): Int {
    val multiplier = (document.documentElement as? HTMLElement)
        ?.style
        ?.getPropertyValue("--tv-scale")
        ?.trim()
        ?.toDoubleOrNull()
    return InterfaceScale.normalise(multiplier?.let { (it * 100).roundToInt() })
}

// ----- layout helpers ------------------------------------------------------------------

/** One titled block of the gallery. [wide] spans both columns. */
@Composable
private fun Block(title: String, wide: Boolean = false, content: @Composable () -> Unit) {
    Section({
        classes("gl-section")
        if (wide) classes("gl-section--wide")
    }) {
        H2({ classes("gl-section-title"); attr("lang", "en") }) { Text(title) }
        content()
    }
}

/** A wrapping row of controls. On TV it is one D-pad row, filed under [tvKey]. */
@Composable
private fun Row(tvKey: String? = null, content: @Composable () -> Unit) {
    val tv = LocalTvInput.current != null
    Div({
        classes("gl-row")
        if (tv && tvKey != null) focusGroup(tvKey, FocusAxis.X)
    }) {
        content()
    }
}

/** A vertical list of controls. On TV it is one D-pad column, filed under [tvKey]. */
@Composable
private fun Stack(tvKey: String, content: @Composable () -> Unit) {
    val tv = LocalTvInput.current != null
    Div({
        classes("gl-stack")
        if (tv) focusGroup(tvKey, FocusAxis.Y)
    }) {
        content()
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Div({ classes("gl-panel") }) { content() }
}

// ----- actions -------------------------------------------------------------------------

@Composable
private fun ActionsPanel(toasts: ToastStore) {
    val tv = LocalTvInput.current != null
    var busy by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("720p") }

    LaunchedEffect(busy) {
        if (busy) {
            delay(2000)
            busy = false
        }
    }

    Panel {
        Block("Icons", wide = true) {
            Ul({ classes("gl-icons") }) {
                Glyph.entries.forEach { glyph ->
                    Li({ classes("gl-icon") }) {
                        Icon(glyph)
                        Span({ classes("gl-icon-name"); attr("lang", "en") }) { Text(glyph.name) }
                    }
                }
            }
        }

        Block("Buttons") {
            Row("buttons") {
                Button(
                    S.resume,
                    onClick = { toasts.show(S.resume) },
                    variant = ButtonVariant.Primary,
                    icon = Glyph.Play,
                    attrs = tvItem("button-primary"),
                )
                Button(S.watchFromStart, onClick = { toasts.show(S.watchFromStart) }, attrs = tvItem("button-secondary"))
                Button(S.cancel, variant = ButtonVariant.Quiet, attrs = tvItem("button-quiet"))
                Button(
                    S.resetAllConfirm,
                    variant = ButtonVariant.Danger,
                    icon = Glyph.Trash2,
                    attrs = tvItem("button-danger"),
                )
            }
            Row("button-states") {
                Button(S.signIn, variant = ButtonVariant.Primary, disabled = true)
                Button(
                    S.refreshEpisodes,
                    onClick = { busy = true },
                    icon = Glyph.Download,
                    busy = busy,
                    attrs = tvItem("button-busy"),
                )
                Button(
                    S.openOnFormula,
                    variant = ButtonVariant.Quiet,
                    icon = Glyph.ExternalLink,
                    href = "https://formula.ge",
                    external = true,
                    attrs = tvItem("button-link"),
                )
            }
        }

        Block("Icon buttons") {
            Row("icon-buttons") {
                IconButton(Glyph.Settings, S.settings, onClick = {}, attrs = tvItem("icon-quiet"))
                IconButton(
                    Glyph.Maximize,
                    S.fullscreen,
                    onClick = {},
                    variant = IconButtonVariant.Secondary,
                    attrs = tvItem("icon-secondary"),
                )
                IconButton(
                    Glyph.Play,
                    S.play,
                    onClick = {},
                    variant = IconButtonVariant.Primary,
                    attrs = tvItem("icon-primary"),
                )
                IconButton(
                    if (muted) Glyph.VolumeX else Glyph.Volume2,
                    S.mute,
                    onClick = { muted = !muted },
                    pressed = muted,
                    attrs = tvItem("icon-toggle"),
                )
                IconButton(Glyph.Trash2, S.delete, onClick = {}, disabled = true)
            }
        }

        Block("Over media", wide = true) {
            Div({ classes("gl-stage") }) {
                Div({
                    classes("gl-stage-center")
                    if (tv) focusGroup("stage-center", FocusAxis.X)
                }) {
                    IconButton(
                        Glyph.Back10,
                        S.back10,
                        onClick = {},
                        variant = IconButtonVariant.OnMedia,
                        attrs = tvItem("stage-back"),
                    )
                    IconButton(
                        if (playing) Glyph.Pause else Glyph.Play,
                        if (playing) S.pause else S.play,
                        onClick = { playing = !playing },
                        variant = IconButtonVariant.OnMedia,
                        attrs = tvItem("stage-play", entry = true),
                    )
                    IconButton(
                        Glyph.Forward10,
                        S.forward10,
                        onClick = {},
                        variant = IconButtonVariant.OnMedia,
                        attrs = tvItem("stage-forward"),
                    )
                }
                Div({ classes("gl-stage-bar") }) {
                    ProgressBar(0.38, S.timeline, thin = true)
                    Row("stage-bar") {
                        MenuButton(
                            label = S.quality,
                            entries = listOf("1080p", "720p", "480p").map { option ->
                                MenuEntry(option, onSelect = { quality = option }, checked = option == quality)
                            },
                            value = quality,
                            placement = MenuPlacement.Above,
                            attrs = tvItem("stage-quality"),
                        )
                        IconButton(
                            Glyph.Maximize,
                            S.fullscreen,
                            onClick = {},
                            variant = IconButtonVariant.OnMedia,
                            attrs = tvItem("stage-fullscreen"),
                        )
                    }
                }
            }
        }

        Block("Chips and badges", wide = true) {
            Row {
                Badge(S.guest)
                Badge(S.inProgress)
            }
            Row("chips") {
                Chip(
                    S.copyEpisodeLink,
                    onClick = { toasts.show(S.episodeLinkCopied, ToastKind.Ok) },
                    icon = Glyph.Link,
                    attrs = tvItem("chip-link"),
                )
                Chip(
                    S.markAsWatched,
                    onClick = { toasts.show(S.episodeMarkedWatched, ToastKind.Ok) },
                    icon = Glyph.Check,
                    attrs = tvItem("chip-watched"),
                )
                Chip(S.openInVlc, icon = Glyph.ExternalLink, attrs = tvItem("chip-vlc"))
                Chip(
                    S.copyMp4Link,
                    onClick = { toasts.show(S.mp4LinkCopied, ToastKind.Ok) },
                    icon = Glyph.Download,
                    attrs = tvItem("chip-mp4"),
                )
            }
        }
    }
}

// ----- inputs --------------------------------------------------------------------------

@Composable
private fun InputsPanel(toasts: ToastStore, scale: Int, onScale: (Int) -> Unit) {
    val tv = LocalTvInput.current != null
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var autoplay by remember { mutableStateOf(true) }
    var drive by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(I18n.current.tag) }

    Panel {
        Block("Text fields") {
            Stack("sign-in") {
                TextField(
                    label = S.emailPlaceholder,
                    value = email,
                    onValueChange = { email = it },
                    kind = TextFieldKind.Email,
                    error = S.errInvalidEmail.takeIf { email.isNotEmpty() && '@' !in email },
                    attrs = tvItem("field-email", entry = true),
                )
                TextField(
                    label = S.passwordPlaceholder,
                    value = password,
                    onValueChange = { password = it },
                    kind = TextFieldKind.Password,
                    attrs = tvItem("field-password"),
                )
                Row("sign-in-actions") {
                    Button(
                        S.signIn,
                        onClick = { toasts.show(S.errSignInFailed, ToastKind.Error) },
                        variant = ButtonVariant.Primary,
                        attrs = tvItem("sign-in"),
                    )
                    Button(S.continueAsGuest, variant = ButtonVariant.Quiet, attrs = tvItem("guest"))
                }
            }
        }

        Block("Switches and choices") {
            Stack("switches") {
                Switch(
                    S.autoplayShort,
                    checked = autoplay,
                    onCheckedChange = { autoplay = it },
                    description = S.autoplayTitle,
                    attrs = tvItem("switch-autoplay"),
                )
                Switch(S.driveMode, checked = drive, onCheckedChange = { drive = it }, attrs = tvItem("switch-drive"))
                Switch(S.nativePlayerTitle, checked = false, onCheckedChange = {}, description = S.settingNotSynced, disabled = true)
            }
            Segmented(
                label = S.language,
                options = I18n.available.map { SegmentOption(it.tag, it.endonym, lang = it.tag) },
                selected = language,
                onSelect = { language = it },
                tvKey = "language",
            )
            if (tv) {
                Segmented(
                    label = S.interfaceSize,
                    options = InterfaceScale.steps.map { SegmentOption(it, "$it%") },
                    selected = scale,
                    onSelect = onScale,
                    tvKey = "interface-size",
                )
            }
            P({ classes("gl-note"); attr("lang", "en") }) { Text("Local state only. Nothing is saved.") }
        }
    }
}

// ----- status --------------------------------------------------------------------------

@Composable
private fun StatusPanel(toasts: ToastStore) {
    val tv = LocalTvInput.current != null

    Panel {
        Block("Progress") {
            Div({ classes("gl-progress-row") }) {
                ProgressBar(0.62, S.statProgress)
                Span({ classes("gl-figure") }) { Text("62%") }
            }
            Div({ classes("gl-thumb") }) {
                ProgressBar(0.38, S.statProgress, thin = true)
            }
        }

        Block("Loading") {
            Div({ classes("gl-stack"); attr("aria-busy", "true") }) {
                Span({ classes("visually-hidden") }) { Text(S.loading) }
                Skeleton(SkeletonShape.Row)
                Skeleton(SkeletonShape.Row)
                Div({ classes("gl-skeleton-tiles") }) {
                    repeat(3) { Skeleton(SkeletonShape.Tile) }
                }
                Row {
                    repeat(4) { Skeleton(SkeletonShape.Ring) }
                    Spinner()
                    Span({ classes("gl-media-swatch") }) { Spinner(onMedia = true) }
                }
            }
        }

        Block("Notice") {
            Div({ if (tv) focusGroup("notice", FocusAxis.X) }) {
                Notice(
                    title = S.loadFailedEyebrow,
                    body = S.catalogUnavailable,
                    actionLabel = S.retry,
                    onAction = { toasts.show(S.dataLoadFailed, ToastKind.Error) },
                    actionAttrs = tvItem("notice-retry"),
                )
            }
        }

        Block("Toasts") {
            Row("toasts") {
                Button("Plain", onClick = { toasts.show(S.backAtStart) }, attrs = tvItem("toast-plain"))
                Button(
                    "OK",
                    onClick = { toasts.show(S.episodeMarkedWatched, ToastKind.Ok) },
                    attrs = tvItem("toast-ok"),
                )
                Button(
                    "Error",
                    onClick = { toasts.show(S.dataLoadFailed, ToastKind.Error) },
                    attrs = tvItem("toast-error"),
                )
            }
        }

        // The web player's parts. TV gets its own player in phase 7.
        if (!tv) {
            Block("Player", wide = true) {
                var position by remember { mutableStateOf(620.0) }
                var playing by remember { mutableStateOf(false) }
                Div({ classes("gl-stack") }) {
                    Scrubber(position, duration = 1648.0, onSeek = { position = it })
                    Transport(
                        playing = playing,
                        onToggle = { playing = !playing },
                        onBack = { position = (position - 10).coerceAtLeast(0.0) },
                        onForward = { position = (position + 10).coerceAtMost(1648.0) },
                        onPrevious = { position = 0.0 },
                        onNext = null,
                    )
                    Div({ classes("gl-media-panel") }) {
                        Scrubber(position, duration = 1648.0, onSeek = { position = it }, onMedia = true)
                        Transport(
                            playing = playing,
                            onToggle = { playing = !playing },
                            onBack = { position = (position - 10).coerceAtLeast(0.0) },
                            onForward = { position = (position + 10).coerceAtMost(1648.0) },
                            onPrevious = { position = 0.0 },
                            onNext = { position = 0.0 },
                            compact = true,
                        )
                    }
                }
            }
        }

        Block("Empty state", wide = true) {
            Div({ if (tv) focusGroup("empty", FocusAxis.X) }) {
                EmptyState(
                    title = S.emptyEyebrow,
                    body = S.emptyBody,
                    actionLabel = S.refreshEpisodes,
                    onAction = { toasts.show(S.refreshingEpisodes) },
                    glyph = Glyph.LayoutGrid,
                    actionAttrs = tvItem("empty-action"),
                )
            }
        }
    }
}

// ----- overlays ------------------------------------------------------------------------

@Composable
private fun OverlaysPanel(toasts: ToastStore) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var autoplay by remember { mutableStateOf(true) }
    var language by remember { mutableStateOf(I18n.current.tag) }

    Panel {
        Block("Dialogs") {
            Row("dialogs") {
                Button(
                    S.resetAllProgress,
                    onClick = { confirmReset = true },
                    variant = ButtonVariant.Danger,
                    icon = Glyph.Trash2,
                    attrs = tvItem("open-reset"),
                )
                Button(S.signOut, onClick = { confirmSignOut = true }, attrs = tvItem("open-sign-out"))
            }
        }

        Block("Sheet and menu") {
            Row("sheet-menu") {
                Button(
                    S.settings,
                    onClick = { sheetOpen = true },
                    icon = Glyph.Settings,
                    attrs = tvItem("open-sheet"),
                )
                MenuButton(
                    label = S.seasonActions,
                    entries = listOf(
                        MenuEntry(
                            S.markSeasonWatched,
                            onSelect = { toasts.show(S.markSeasonDone, ToastKind.Ok) },
                            icon = Glyph.Check,
                        ),
                        MenuEntry(
                            S.resetSeasonProgress,
                            onSelect = { toasts.show(S.resetSeasonDone) },
                            icon = Glyph.Trash2,
                            danger = true,
                        ),
                    ),
                    variant = IconButtonVariant.Secondary,
                    attrs = tvItem("open-menu"),
                )
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = S.resetAllTitle,
            body = S.resetAllBody,
            confirmLabel = S.resetAllConfirm,
            onConfirm = {
                confirmReset = false
                toasts.show(S.resetAllDone, ToastKind.Ok)
            },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmSignOut) {
        ConfirmDialog(
            title = S.signOutConfirmTitle,
            body = S.signOutConfirmBody,
            confirmLabel = S.signOut,
            onConfirm = { confirmSignOut = false },
            onDismiss = { confirmSignOut = false },
            danger = false,
        )
    }
    if (sheetOpen) {
        Sheet(title = S.settings, onDismiss = { sheetOpen = false }) {
            Switch(
                S.autoplayShort,
                checked = autoplay,
                onCheckedChange = { autoplay = it },
                description = S.autoplayTitle,
                attrs = tvItem("sheet-autoplay", entry = true),
            )
            Segmented(
                label = S.language,
                options = I18n.available.map { SegmentOption(it.tag, it.endonym, lang = it.tag) },
                selected = language,
                onSelect = { language = it },
                tvKey = "sheet-language",
            )
        }
    }
}
