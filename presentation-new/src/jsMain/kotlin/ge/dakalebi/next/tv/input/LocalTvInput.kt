package ge.dakalebi.next.tv.input

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The TV shell's one [TvInput], or null on the web and in drive.
 *
 * Nullable on purpose. The redesign's components are shared by all three shells, and a
 * component asks this to decide how it takes input: on TV a dialog or a menu pushes its
 * own [TvLayer] and leaves the keys to the input stack; elsewhere it handles Escape,
 * Tab and the arrows itself. A TV root that forgot to provide it would get the web
 * behaviour, which still works with a keyboard, instead of a crash.
 *
 * The old UI has its own non-null one (`ge.dakalebi.ui.tv.LocalTvInput`). They are kept
 * apart so the old UI goes away without touching this.
 */
val LocalTvInput = staticCompositionLocalOf<TvInput?> { null }
