package ge.dakalebi.domain.model

/** How far one viewer has got through one episode. */
data class WatchProgress(
    val episodeId: String,
    val progressSeconds: Int,
    val durationSeconds: Int?,
    val isWatched: Boolean,
    val lastWatchedAtMillis: Double,
) {
    val isStarted: Boolean get() = !isWatched && progressSeconds > 0

    /** 0..100. A watched episode always reads as full. */
    val percent: Double
        get() = when {
            isWatched -> 100.0
            durationSeconds != null && durationSeconds > 0 ->
                (progressSeconds.toDouble() / durationSeconds * 100).coerceIn(0.0, 100.0)

            else -> 0.0
        }
}
