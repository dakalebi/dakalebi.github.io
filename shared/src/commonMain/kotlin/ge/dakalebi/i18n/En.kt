package ge.dakalebi.i18n

/**
 * English.
 *
 * Not reachable from the UI yet — there is no language picker. It exists so the
 * seam is a working one rather than a promise: adding a locale is a file, and
 * the compiler will refuse it if a string is missing.
 *
 * `caps` is deliberately not overridden. Georgian chrome is set in Mtavruli
 * because that is a normal typographic register there; the same treatment in
 * English is just shouting.
 */
object En : Strings {
    override val tag = "en"
    override val endonym = "English"

    // ------------------------------------------------------------- shell
    override val appName = "Dakalebi"
    override val seriesTitle = "Chemi Tsolis Dakalebi"
    override val seriesInitials = "CTD"
    override val documentTitle = "Chemi Tsolis Dakalebi — Watch dashboard"

    override fun episodeDocumentTitle(season: Int, episode: Int) =
        "Season $season ⋅ Episode $episode - $seriesInitials"

    override val loading = "Loading…"
    override val menu = "Menu"
    override val home = "Home"
    override val settings = "Settings"
    override val signOut = "Sign out"
    override val retry = "Try again"
    override val cancel = "Cancel"

    // -------------------------------------------------------------- auth
    override val signInEyebrow = "Watch dashboard"
    override val emailPlaceholder = "Email"
    override val passwordPlaceholder = "Password"
    override val signIn = "Sign in"
    override val signUp = "Sign up"
    override val promptSignUp = "No account? Sign up"
    override val promptSignIn = "Already have an account? Sign in"
    override val forgotPassword = "Forgot your password?"
    override val accountCreated = "Account created"
    override val resetLinkSent = "Password reset link sent"
    override val enterEmailFirst = "Enter your email first"

    override val errWrongCredentials = "Wrong email or password"
    override val errInvalidEmail = "That email is not valid"
    override val errEmailInUse = "That email is already registered"
    override val errWeakPassword = "Password is too short — at least 6 characters"
    override val errTooManyRequests = "Too many attempts — try again shortly"
    override val errUnauthorizedDomain =
        "This domain is not allowed in Firebase (Authentication → Settings → Authorized domains)"
    override val errNetwork = "Could not reach the network"
    override val errSignInFailed = "Sign-in failed"

    // ------------------------------------------------------- setup notice
    override val setupEyebrow = "Configuration"
    override val setupTitle = "Firebase is not connected"
    override val setupBody = "Fill in FirebaseConfig.kt with your project details " +
        "(Firebase console → Project settings → Your apps → Web app), then rebuild the app."

    // --------------------------------------------------------- dashboard
    override val seasons = "Seasons"
    override val episodes = "Episodes"
    override val continueLabel = "Continue"
    override val lastWatched = "Last watched"
    override val beginning = "Start here"
    override val watch = "Watch"
    override val resume = "Resume"
    override val videoUnavailableForEpisode = "No video available for this episode"
    override val seasonActions = "Season actions"
    override val markSeasonWatched = "Mark season as watched"
    override val resetSeasonProgress = "Clear season progress"
    override val statWatched = "Watched"
    override val statStarted = "Started"
    override val statProgress = "Progress"
    override val autoplayTitle = "Autoplay the next episode"
    override val autoplayBody = "When an episode ends, the next one starts automatically."
    override val autoplayShort = "Autoplay"
    override val nativePlayerTitle = "System player"
    override val nativePlayerBody =
        "iPhone and iPad use Apple's own player. Turn this off for the same " +
            "player the desktop site uses."
    override val language = "Language"
    override val settingNotSynced = "Changed on this device only"
    override val refreshEpisodes = "Refresh episodes"
    override val downloadEpisodes = "Download episodes"
    override val inProgress = "Working…"
    override val emptyEyebrow = "Empty"
    override val emptyBody = "There are no episodes in the database yet."
    override val waitForAdmin = "Wait for an admin to download the episodes."
    override val loadFailedEyebrow = "Could not load"
    override val resetAllProgress = "Clear all progress"
    override val backToTop = "Back to top"

    override fun season(number: Int) = "Season $number"
    override fun episode(number: Int) = "Episode $number"
    override fun seasonAndEpisode(season: Int, episode: Int) = "Season $season · Episode $episode"
    override fun episodeCount(total: Int, watched: Int) = "$total episodes · $watched watched"
    override fun minutesLeft(minutes: Int, position: String, duration: String) =
        "$minutes min left · $position / $duration"
    override fun lastRefreshed(whenText: String) = "Last refreshed: $whenText"
    override fun appVersion(build: String, whenText: String) = "Version $build · $whenText"

    // -------------------------------------------------------------- tiles
    override val watchedLabel = "Watched"
    override val startedLabel = "Started"
    override val noVideo = "No video"
    override val copyEpisodeLink = "Copy episode link"
    override val copyMp4Link = "Copy MP4 link"
    override val episodeLinkCopied = "Episode link copied"
    override val mp4LinkCopied = "MP4 link copied"
    override val copyFailed = "Could not copy"

    // -------------------------------------------------------------- watch
    override val back = "Back"
    override val previousEpisodes = "Previously"
    override val nextEpisodes = "Up next"
    override val nextEpisode = "Next episode"
    override val nextEpisodeAction = "Next episode"
    override val episodeFinished = "Episode finished"
    override val watchFromStart = "Watch from the start"
    override val markAsWatched = "Mark as watched"
    override val watchedTick = "✓  Watched"
    override val openOnFormula = "Open on Formula"
    override val episodeNotFound = "Episode not found"
    override val videoLinkFailed = "Could not get the video link"
    override val videoLoadFailed = "Could not load the video"
    override val retryingVideo = "Retrying…"
    override val markedAsWatched = "Marked as watched"
    override val episodeMarkedWatched = "Episode marked as watched"
    override val progressCleared = "Progress cleared"
    override val clearFailed = "Could not clear it"
    override val clearProgressTitle = "Clear progress?"
    override val clearProgressBody = "This episode will look as though you never watched it."
    override val delete = "Clear"
    override val dismiss = "Dismiss"

    override fun nextUp(season: Int, episode: Int) = "Next: Season $season · Episode $episode"

    // ---------------------------------------------------------- dialogs
    override val resetAllTitle = "Clear all progress?"
    override val resetAllBody =
        "Progress for every season and episode will be cleared. The series will look as though you are starting over."
    override val resetAllConfirm = "Clear everything"
    override val resetAllDone = "All progress cleared"
    override val markSeasonTitle = "Mark the season as watched?"
    override val markSeasonConfirm = "Mark"
    override val markSeasonDone = "Season marked as watched"
    override val resetSeasonTitle = "Clear the season's progress?"
    override val resetSeasonDone = "Season progress cleared"
    override val actionFailed = "That did not work"
    override val signOutConfirmTitle = "Sign out?"
    override val signOutConfirmBody = "You will need to sign in again to keep watching."

    override fun markSeasonBody(season: String) = "Mark every episode of season $season as watched?"
    override fun resetSeasonBody(season: String) = "Clear progress for every episode of season $season?"

    // ---------------------------------------------------------- catalog
    override val refreshing = "Working…"
    override val refreshingEpisodes = "Refreshing episodes…"
    override val refreshNoPermission = "You cannot refresh — this account is not on the admin list"
    override val refreshQuota = "The Firestore quota is used up"
    override val refreshNetwork = "Could not reach the network — try again"
    override val refreshFailed = "Refresh failed"
    override val catalogUnavailable = "Could not load the episode list — check your connection"
    override val dataLoadFailed = "Could not load the data"

    override fun refreshSeasonProgress(done: Int, total: Int) = "Season $done / $total"
    override fun refreshed(episodes: Int, changed: Int, withoutVideo: Int): String {
        val missing = if (withoutVideo > 0) " · $withoutVideo without video" else ""
        return "Refreshed: $episodes episodes, $changed changed$missing"
    }

    // ----------------------------------------------------------- player
    override val play = "Play"
    override val pause = "Pause"
    override val back10 = "Back 10 seconds"
    override val forward10 = "Forward 10 seconds"
    override val mute = "Mute"
    override val unmute = "Unmute"
    override val volume = "Volume"
    override val quality = "Quality"
    override val fullscreen = "Fullscreen"
    override val timeline = "Timeline"

    override fun volumeFeedback(percent: Int) = "Volume: $percent%"
    override fun seekForward(seconds: Int) = "$seconds s ▸"
    override fun seekBackward(seconds: Int) = "◂ $seconds s"
}
