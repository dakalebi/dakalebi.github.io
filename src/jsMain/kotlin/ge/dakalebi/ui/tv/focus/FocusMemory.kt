package ge.dakalebi.ui.tv.focus

/**
 * Where focus was, per group and per screen.
 *
 * Keyed by the strings the screen declared, never by element references: Compose
 * throws nodes away and builds new ones on any structural change, so a remembered
 * `Element` would be a leak that also stops matching.
 *
 * This is the difference between a D-pad UI that feels built and one that feels
 * generated. Coming back from an episode lands on the tile you launched from;
 * sweeping down past a rail and back up returns to where you left it, not to its
 * first item.
 */
internal object FocusMemory {
    private val inGroup = mutableMapOf<String, String>()
    private val inScreen = mutableMapOf<String, Pair<String, String>>()

    fun remember(groupKey: String, itemKey: String) {
        inGroup[groupKey] = itemKey
    }

    fun recall(groupKey: String): String? = inGroup[groupKey]

    /** Where a screen was left, so arriving back lands where you were. */
    fun rememberScreen(screenKey: String, groupKey: String, itemKey: String) {
        inScreen[screenKey] = groupKey to itemKey
    }

    fun recallScreen(screenKey: String): Pair<String, String>? = inScreen[screenKey]

    /** For tests and for a sign-out, which should not carry one account's place into another's. */
    fun clear() {
        inGroup.clear()
        inScreen.clear()
    }
}
