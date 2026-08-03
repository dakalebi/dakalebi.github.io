package ge.dakalebi.ui.player

import org.w3c.dom.events.KeyboardEvent

/** What a press should do. Decided here, applied by the player. */
enum class PlayerKeyAction {
    None,
    TogglePlay,
    SeekForward,
    SeekBack,
    VolumeUp,
    VolumeDown,
    ToggleMute,
    ToggleFullscreen,
}

/**
 * Which keys a player is entitled to claim.
 *
 * The web player claims all four arrows for its whole lifetime. That is right with
 * a keyboard and fatal with a D-pad: a `preventDefault`ed ArrowRight is a remote
 * that cannot move focus, so on a television the player would freeze navigation
 * for as long as it is on screen.
 *
 * Making it a policy rather than a device check keeps the decision at the call
 * site, where the reason is visible, instead of hiding it behind another
 * `isAppleMobile`-style predicate that the next reader has to go and look up.
 */
class PlayerKeyPolicy(
    val arrowsSeek: Boolean,
    val arrowsVolume: Boolean,
    val allowFullscreen: Boolean,
) {
    companion object {
        /** A keyboard is present and nothing else wants the arrows. */
        val Keyboard = PlayerKeyPolicy(arrowsSeek = true, arrowsVolume = true, allowFullscreen = true)

        /**
         * A remote. The player claims no arrows at all: they belong to focus, and
         * the TV player asks for them through its own input layer only while its
         * controls are hidden. Fullscreen is meaningless when the page already is.
         */
        val Remote = PlayerKeyPolicy(arrowsSeek = false, arrowsVolume = false, allowFullscreen = false)
    }
}

/**
 * The press-to-action mapping, with no side effects.
 *
 * Extracted from the player so the same rules can serve a keyboard and a remote,
 * and so the two cannot drift apart. Returns [PlayerKeyAction.None] for anything
 * the policy does not claim, and the caller should not `preventDefault` in that
 * case — swallowing a key it did not act on is how the web player made every
 * control on the page unreachable by keyboard.
 */
fun mapPlayerKey(event: KeyboardEvent, policy: PlayerKeyPolicy): PlayerKeyAction {
    val target = event.target.asDynamic()
    val tag = (target?.tagName as? String)?.uppercase()
    if (tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT" ||
        target?.isContentEditable == true
    ) {
        return PlayerKeyAction.None
    }

    // Browser and OS shortcuts stay theirs. Without this, Cmd/Alt+Left is a
    // ten-second seek instead of history-back, and Cmd+F fullscreens the video
    // while the find bar opens.
    if (event.ctrlKey || event.metaKey || event.altKey) return PlayerKeyAction.None

    // Space and Enter are how a keyboard user presses whatever is focused.
    // Claiming them unconditionally also calls preventDefault, so the button never
    // fires and the video toggles instead. Only take them when focus is inert.
    val role = runCatching { target?.getAttribute("role") as? String }.getOrNull()
    val focusIsActivatable = tag == "BUTTON" || tag == "A" || tag == "SUMMARY" || !role.isNullOrBlank()

    return when {
        event.code == "Space" || event.key == "Enter" ->
            if (focusIsActivatable) PlayerKeyAction.None else PlayerKeyAction.TogglePlay

        event.code == "ArrowRight" -> if (policy.arrowsSeek) PlayerKeyAction.SeekForward else PlayerKeyAction.None
        event.code == "ArrowLeft" -> if (policy.arrowsSeek) PlayerKeyAction.SeekBack else PlayerKeyAction.None
        event.code == "ArrowUp" -> if (policy.arrowsVolume) PlayerKeyAction.VolumeUp else PlayerKeyAction.None
        event.code == "ArrowDown" -> if (policy.arrowsVolume) PlayerKeyAction.VolumeDown else PlayerKeyAction.None

        event.key == "f" || event.key == "F" ->
            if (policy.allowFullscreen) PlayerKeyAction.ToggleFullscreen else PlayerKeyAction.None

        event.key == "m" || event.key == "M" -> PlayerKeyAction.ToggleMute
        else -> PlayerKeyAction.None
    }
}
