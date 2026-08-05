package ge.dakalebi.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Every user-visible string in the app.
 *
 * The equivalent of Android's `strings.xml`, expressed as an interface so the
 * compiler enforces what a `.xml` file cannot: a new locale that forgets a
 * string does not build, and a string that no longer exists cannot be left
 * behind in a translation. Anything with a number or a name in it is a
 * function rather than a template, so a translator can move the placeholder to
 * wherever their grammar needs it instead of being stuck with our word order.
 *
 * Add strings here first, then to every implementation.
 */
interface Strings {
    /** BCP-47 tag, used for the `lang` attribute. */
    val tag: String

    /** This language's own name, for a future language picker. */
    val endonym: String

    /**
     * Uppercase form used for UI chrome — headings, buttons, labels.
     *
     * Georgian has a real uppercase alphabet, Mtavruli, and setting chrome in
     * it is a normal typographic choice there. It cannot be done in CSS:
     * `text-transform: uppercase` deliberately skips the Mkhedruli → Mtavruli
     * mapping (measured — CSS leaves `ქართული` at its Mkhedruli width, while
     * `"ქართული".uppercase()` produces `ᲥᲐᲠᲗᲣᲚᲘ`), so it happens here instead.
     *
     * Languages where all-caps chrome would just read as shouting leave this
     * alone, which is why the default is identity rather than `uppercase()`.
     */
    fun caps(text: String): String = text

    // ------------------------------------------------------------- shell
    val appName: String

    /** The series itself. A proper noun — translations transliterate, not translate. */
    val seriesTitle: String

    /**
     * The series reduced to its initials, for places with no room for the full
     * name. A tab strip gives a title maybe fifteen characters before it
     * truncates, and the full name spends all of them before saying anything.
     */
    val seriesInitials: String

    /** Tab title everywhere except an open episode. Mirrors `index.html`. */
    val documentTitle: String

    /** Tab title while an episode is open, e.g. "ჩცდ - სეზონი 3 ⋅ სერია 12". */
    fun episodeDocumentTitle(season: Int, episode: Int): String

    val loading: String
    val menu: String

    /** The first destination in the TV navigation rail. */
    val home: String
    val settings: String
    val signOut: String
    val retry: String
    val cancel: String

    // -------------------------------------------------------------- auth
    val signInEyebrow: String
    val signInWithGoogle: String
    val or: String
    val emailPlaceholder: String
    val passwordPlaceholder: String
    val signIn: String
    val signUp: String
    val promptSignUp: String
    val promptSignIn: String
    val forgotPassword: String
    val accountCreated: String
    val resetLinkSent: String
    val enterEmailFirst: String

    val errWrongCredentials: String
    val errInvalidEmail: String
    val errEmailInUse: String
    val errWeakPassword: String
    val errTooManyRequests: String
    val errPopupClosed: String
    val errPopupBlocked: String
    val errUnauthorizedDomain: String
    val errNetwork: String
    val errSignInFailed: String

    // ------------------------------------------------------- setup notice
    val setupEyebrow: String
    val setupTitle: String
    val setupBody: String

    // --------------------------------------------------------- dashboard
    val seasons: String
    val episodes: String
    val continueLabel: String
    val lastWatched: String
    val beginning: String
    val watch: String
    val resume: String
    val videoUnavailableForEpisode: String
    val seasonActions: String
    val markSeasonWatched: String
    val resetSeasonProgress: String
    val statWatched: String
    val statStarted: String
    val statProgress: String
    val autoplayTitle: String
    val autoplayBody: String

    /** Only offered on iPhone and iPad, which are the only devices with two. */
    val nativePlayerTitle: String
    val nativePlayerBody: String

    val language: String

    /** A setting changed here but could not be recorded for other devices. */
    val settingNotSynced: String

    val refreshEpisodes: String
    val downloadEpisodes: String
    val inProgress: String
    val emptyEyebrow: String
    val emptyBody: String
    val waitForAdmin: String
    val loadFailedEyebrow: String
    val resetAllProgress: String

    /** The control at the foot of the season grid that jumps the ring back to the top. */
    val backToTop: String

    fun season(number: Int): String
    fun episode(number: Int): String
    fun seasonAndEpisode(season: Int, episode: Int): String
    fun episodeCount(total: Int, watched: Int): String
    fun minutesLeft(minutes: Int, position: String, duration: String): String
    fun lastRefreshed(whenText: String): String

    /** Build identity in the drawer footer, e.g. "Version 47 · 02.08.2026, 11:30". */
    fun appVersion(build: String, whenText: String): String

    // -------------------------------------------------------------- tiles
    val watchedLabel: String
    val startedLabel: String
    val noVideo: String
    val copyEpisodeLink: String
    val copyMp4Link: String
    val episodeLinkCopied: String
    val mp4LinkCopied: String
    val copyFailed: String

    // -------------------------------------------------------------- watch
    val back: String
    val previousEpisodes: String
    val nextEpisodes: String
    val nextEpisode: String
    val nextEpisodeAction: String
    val episodeFinished: String
    val watchFromStart: String
    val markAsWatched: String
    val watchedTick: String
    val openOnFormula: String
    val episodeNotFound: String
    val videoLinkFailed: String
    val videoLoadFailed: String
    val retryingVideo: String
    val markedAsWatched: String
    val episodeMarkedWatched: String
    val progressCleared: String
    val clearFailed: String
    val clearProgressTitle: String
    val clearProgressBody: String
    val delete: String
    val dismiss: String

    fun nextUp(season: Int, episode: Int): String

    // ---------------------------------------------------------- dialogs
    val resetAllTitle: String
    val resetAllBody: String
    val resetAllConfirm: String
    val resetAllDone: String
    val markSeasonTitle: String
    val markSeasonConfirm: String
    val markSeasonDone: String
    val resetSeasonTitle: String
    val resetSeasonDone: String
    val actionFailed: String

    /** Confirmation before signing out, since it costs a re-login to undo. */
    val signOutConfirmTitle: String
    val signOutConfirmBody: String

    fun markSeasonBody(season: String): String
    fun resetSeasonBody(season: String): String

    // ---------------------------------------------------------- catalog
    val refreshing: String
    val refreshingEpisodes: String
    val refreshNoPermission: String
    val refreshQuota: String
    val refreshNetwork: String
    val refreshFailed: String
    val catalogUnavailable: String
    val dataLoadFailed: String

    fun refreshSeasonProgress(done: Int, total: Int): String
    fun refreshed(episodes: Int, changed: Int, withoutVideo: Int): String

    // ----------------------------------------------------------- player
    val play: String
    val pause: String
    val back10: String
    val forward10: String
    val mute: String
    val unmute: String
    val volume: String
    val quality: String
    val fullscreen: String
    val timeline: String

    fun volumeFeedback(percent: Int): String
    fun seekForward(seconds: Int): String
    fun seekBackward(seconds: Int): String
}

/**
 * The active language.
 *
 * Compose state, so switching it re-renders everything without a reload. There
 * is no picker in the UI yet — this is the seam a picker would plug into.
 */
object I18n {
    val available: List<Strings> = listOf(Ka, En)

    var current: Strings by mutableStateOf(Ka)
        private set

    fun use(tag: String) {
        current = available.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: return
    }
}

/** Shorthand for the active language: `S.signIn`. */
val S: Strings get() = I18n.current

/** Chrome casing for the active language. See [Strings.caps]. */
val String.caps: String get() = I18n.current.caps(this)
