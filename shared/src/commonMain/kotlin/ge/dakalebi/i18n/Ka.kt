package ge.dakalebi.i18n

/** Georgian — the original and default language. */
object Ka : Strings {
    override val tag = "ka"
    override val endonym = "ქართული"

    /**
     * Mkhedruli → Mtavruli. `uppercase()` performs the Unicode mapping that
     * CSS `text-transform` refuses to, which is the whole reason chrome casing
     * lives in the string layer.
     */
    override fun caps(text: String): String = text.uppercase()

    // ------------------------------------------------------------- shell
    override val appName = "დაქალები"
    override val seriesTitle = "ჩემი ცოლის დაქალები"
    override val seriesInitials = "ჩცდ"
    override val documentTitle = "ჩემი ცოლის დაქალები — სანახავი დაფა"

    // Mkhedruli, not Mtavruli: a tab title is not the UI chrome the caps rule
    // is about, and index.html has always carried the plain form.
    override fun episodeDocumentTitle(season: Int, episode: Int) =
        "სეზონი $season ⋅ სერია $episode - $seriesInitials"

    override val loading = "იტვირთება..."
    override val menu = "მენიუ"
    override val home = "მთავარი"
    override val settings = "პარამეტრები"
    override val signOut = "გასვლა"
    override val retry = "თავიდან ცდა"
    override val cancel = "გაუქმება"

    // -------------------------------------------------------------- auth
    override val signInEyebrow = "სანახავი დაფა"
    override val emailPlaceholder = "ელფოსტა"
    override val passwordPlaceholder = "პაროლი"
    override val signIn = "შესვლა"
    override val signUp = "რეგისტრაცია"
    override val promptSignUp = "არ გაქვს ანგარიში? რეგისტრაცია"
    override val promptSignIn = "უკვე გაქვს ანგარიში? შესვლა"
    override val forgotPassword = "პაროლი დაგავიწყდა?"
    override val accountCreated = "ანგარიში შეიქმნა"
    override val resetLinkSent = "პაროლის აღდგენის ბმული გაიგზავნა"
    override val enterEmailFirst = "ჯერ შეიყვანე ელფოსტა"

    override val errWrongCredentials = "ელფოსტა ან პაროლი არასწორია"
    override val errInvalidEmail = "ელფოსტა არასწორია"
    override val errEmailInUse = "ეს ელფოსტა უკვე რეგისტრირებულია"
    override val errWeakPassword = "პაროლი ძალიან მოკლეა — მინიმუმ 6 სიმბოლო"
    override val errTooManyRequests = "ბევრი მცდელობა იყო — სცადე ცოტა ხანში"
    override val errUnauthorizedDomain =
        "ეს დომენი Firebase-ში ნებადართული არაა (Authentication → Settings → Authorized domains)"
    override val errNetwork = "ქსელთან კავშირი ვერ მოხერხდა"
    override val errSignInFailed = "შესვლა ვერ მოხერხდა"

    // ------------------------------------------------------- setup notice
    override val setupEyebrow = "კონფიგურაცია"
    override val setupTitle = "Firebase არ არის დაკავშირებული"
    override val setupBody = "შეავსე FirebaseConfig.kt პროექტის მონაცემებით " +
        "(Firebase console → Project settings → Your apps → Web app), " +
        "შემდეგ თავიდან ააწყვე აპლიკაცია."

    // --------------------------------------------------------- dashboard
    override val seasons = "სეზონები"
    override val episodes = "სერიები"
    override val continueLabel = "გაგრძელება"
    override val lastWatched = "ბოლო ნანახი"
    override val beginning = "დასაწყისი"
    override val watch = "ყურება"
    override val resume = "გაგრძელება"
    override val videoUnavailableForEpisode = "ვიდეო ამ სერიისთვის მიუწვდომელია"
    override val seasonActions = "სეზონის მოქმედებები"
    override val markSeasonWatched = "სეზონის ნანახად მონიშვნა"
    override val resetSeasonProgress = "სეზონის პროგრესის წაშლა"
    override val statWatched = "ნანახი"
    override val statStarted = "დაწყებული"
    override val statProgress = "პროგრესი"
    override val autoplayTitle = "შემდეგი სერიის ავტომატურად ჩართვა"
    override val autoplayBody = "სერიის დასრულებისას შემდეგი ავტომატურად ჩაირთვება."
    override val autoplayShort = "ავტომ. დაკვრა"
    override val nativePlayerTitle = "სისტემური ფლეიერი"
    override val nativePlayerBody =
        "iPhone-სა და iPad-ზე Apple-ის ფლეიერი გამოიყენება. გამორთე, თუ გინდა " +
            "იგივე ფლეიერი, რაც კომპიუტერზეა."
    override val language = "ენა"
    override val settingNotSynced = "პარამეტრი შეიცვალა მხოლოდ ამ მოწყობილობაზე"
    override val refreshEpisodes = "სერიების განახლება"
    override val downloadEpisodes = "სერიების ჩამოტვირთვა"
    override val inProgress = "მიმდინარეობს..."
    override val emptyEyebrow = "ცარიელია"
    override val emptyBody = "ბაზაში სერიები ჯერ არ არის."
    override val waitForAdmin = "დაელოდე, სანამ ადმინი ჩამოტვირთავს სერიებს."
    override val loadFailedEyebrow = "ჩატვირთვა ვერ მოხერხდა"
    override val resetAllProgress = "მთლიანი პროგრესის წაშლა"
    override val backToTop = "ზემოთ დაბრუნება"

    override fun season(number: Int) = "სეზონი $number"
    override fun episode(number: Int) = "სერია $number"
    override fun seasonAndEpisode(season: Int, episode: Int) = "სეზონი $season · სერია $episode"
    override fun episodeCount(total: Int, watched: Int) = "$total სერია · $watched ნანახი"
    override fun minutesLeft(minutes: Int, position: String, duration: String) =
        "დარჩა $minutes წუთი · $position / $duration"
    override fun lastRefreshed(whenText: String) = "ბოლო განახლება: $whenText"
    override fun appVersion(build: String, whenText: String) = "ვერსია $build · $whenText"

    // -------------------------------------------------------------- tiles
    override val watchedLabel = "ნანახია"
    override val startedLabel = "დაწყებულია"
    override val noVideo = "ვიდეო არაა"
    override val copyEpisodeLink = "სერიის ლინკის კოპირება"
    override val copyMp4Link = "MP4 ლინკის კოპირება"
    override val episodeLinkCopied = "სერიის ლინკი დაკოპირდა"
    override val mp4LinkCopied = "MP4 ლინკი დაკოპირდა"
    override val copyFailed = "კოპირება ვერ მოხერხდა"

    // -------------------------------------------------------------- watch
    override val back = "უკან"
    override val previousEpisodes = "წინა სერიები"
    override val nextEpisodes = "შემდეგი სერიები"
    override val nextEpisode = "შემდეგი სერია"
    override val nextEpisodeAction = "შემდეგი სერია"
    override val episodeFinished = "სერია დასრულდა"
    override val watchFromStart = "თავიდან ყურება"
    override val markAsWatched = "ნანახად მონიშვნა"
    override val watchedTick = "✓  ნანახია"
    override val openOnFormula = "ფორმულაზე გახსნა"
    override val episodeNotFound = "სერია ვერ მოიძებნა"
    override val videoLinkFailed = "ვიდეოს ლინკის მიღება ვერ მოხერხდა"
    override val videoLoadFailed = "ვიდეოს ჩატვირთვა ვერ მოხერხდა"
    override val retryingVideo = "ვცდი თავიდან..."
    override val markedAsWatched = "მონიშნულია, როგორც ნანახი"
    override val episodeMarkedWatched = "სერია მონიშნულია ნანახად"
    override val progressCleared = "პროგრესი წაიშალა"
    override val clearFailed = "ვერ მოხერხდა წაშლა"
    override val clearProgressTitle = "პროგრესის წაშლა?"
    override val clearProgressBody = "ეს სერია გამოჩნდება ისე, თითქოს საერთოდ არ გაქვს ნანახი."
    override val delete = "წაშლა"
    override val dismiss = "დახურვა"

    override fun nextUp(season: Int, episode: Int) = "შემდეგი: სეზონი $season · სერია $episode"

    // ---------------------------------------------------------- dialogs
    override val resetAllTitle = "მთლიანი პროგრესის წაშლა?"
    override val resetAllBody =
        "ყველა სეზონისა და სერიის პროგრესი წაიშლება. სერიალი გამოჩნდება ისე, თითქოს თავიდან იწყებ ყურებას."
    override val resetAllConfirm = "ყველაფრის წაშლა"
    override val resetAllDone = "მთლიანი პროგრესი წაიშალა"
    override val markSeasonTitle = "სეზონის ნანახად მონიშვნა?"
    override val markSeasonConfirm = "მონიშვნა"
    override val markSeasonDone = "სეზონი მოინიშნა ნანახად"
    override val resetSeasonTitle = "სეზონის პროგრესის წაშლა?"
    override val resetSeasonDone = "სეზონის პროგრესი წაიშალა"
    override val actionFailed = "მოქმედება ვერ შესრულდა"
    override val signOutConfirmTitle = "გასვლა?"
    override val signOutConfirmBody = "ყურების გასაგრძელებლად თავიდან უნდა შეხვიდე."

    override fun markSeasonBody(season: String) = "მოინიშნოს სეზონი $season-ის ყველა სერია ნანახად?"
    override fun resetSeasonBody(season: String) = "წაიშალოს სეზონი $season-ის ყველა სერიის პროგრესი?"

    // ---------------------------------------------------------- catalog
    override val refreshing = "მიმდინარეობს..."
    override val refreshingEpisodes = "სერიების განახლება..."
    override val refreshNoPermission = "განახლების უფლება არ გაქვს — ეს ანგარიში ადმინების სიაში არაა"
    override val refreshQuota = "Firestore-ის ლიმიტი ამოიწურა"
    override val refreshNetwork = "ქსელთან კავშირი ვერ მოხერხდა — სცადე თავიდან"
    override val refreshFailed = "განახლება ვერ მოხერხდა"
    override val catalogUnavailable = "სერიების სია ვერ ჩაიტვირთა — შეამოწმე ინტერნეტი"
    override val dataLoadFailed = "მონაცემები ვერ ჩაიტვირთა"

    override fun refreshSeasonProgress(done: Int, total: Int) = "სეზონი $done / $total"
    override fun refreshed(episodes: Int, changed: Int, withoutVideo: Int): String {
        val missing = if (withoutVideo > 0) " · $withoutVideo ვიდეოს გარეშე" else ""
        return "განახლდა: $episodes სერია, $changed შეიცვალა$missing"
    }

    // ----------------------------------------------------------- player
    override val play = "დაკვრა"
    override val pause = "პაუზა"
    override val back10 = "10 წამით უკან"
    override val forward10 = "10 წამით წინ"
    override val mute = "ხმის გათიშვა"
    override val unmute = "ხმის ჩართვა"
    override val volume = "ხმა"
    override val quality = "ხარისხი"
    override val fullscreen = "სრულ ეკრანზე"
    override val timeline = "დროის ხაზი"

    override fun volumeFeedback(percent: Int) = "ხმა: $percent%"
    override fun seekForward(seconds: Int) = "$seconds წმ ▸"
    override fun seekBackward(seconds: Int) = "◂ $seconds წმ"
}
