package ge.dakalebi.presentation

import ge.dakalebi.core.Log
import ge.dakalebi.i18n.S
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A progress change the dashboard asks about before it makes it. */
sealed interface DashboardConfirm {
    data object ResetAll : DashboardConfirm

    data class MarkSeason(val season: Int) : DashboardConfirm

    data class ResetSeason(val season: Int) : DashboardConfirm
}

data class DashboardUiState(
    /** The season the viewer picked, or null for the one the catalog suggests. */
    val pickedSeason: Int? = null,
    /** The question on screen, if any. */
    val confirm: DashboardConfirm? = null,
    /** A progress change is running. The controls that start one are off until it ends. */
    val busy: Boolean = false,
)

/**
 * The dashboard: which season is on screen, the confirmation it shows, and the
 * changes behind them. Also the account actions in its menu that can fail.
 *
 * One per visit to the screen, as the remembered state it replaces was, so
 * coming back shows the suggested season again. Its work runs in the app's
 * [scope]: leaving the screen does not stop a reset or a rebuild halfway, and
 * its toast still appears.
 *
 * It says what happened itself, through [toasts]. Only the web shell draws a
 * dashboard, so there is one wording. [PlaybackViewModel] leaves that to the
 * players, because each one words it differently.
 *
 * The catalog and the progress stay in [CatalogStore], which every screen
 * reads. What this adds is the dashboard's own state.
 */
class DashboardViewModel(
    private val catalog: CatalogStore,
    private val session: SessionViewModel,
    private val toasts: ToastStore,
    private val scope: CoroutineScope,
) : ViewModel<DashboardUiState>(DashboardUiState()) {

    /** The season on screen: the one picked, or the one the catalog suggests. */
    fun season(state: DashboardUiState): Int? = state.pickedSeason ?: catalog.defaultSeason

    fun pickSeason(season: Int) = setState { copy(pickedSeason = season) }

    /** Shows the question before [confirm] makes the change. */
    fun ask(confirm: DashboardConfirm) = setState { copy(confirm = confirm) }

    fun dismiss() = setState { copy(confirm = null) }

    /**
     * Makes the change the question on screen asked about, and says how it went.
     *
     * Does nothing while another change runs, or with nobody watching, and the
     * question then stays on screen.
     */
    fun confirm() {
        val confirm = state.value.confirm ?: return
        val viewerId = session.state.value.viewerId ?: return
        if (state.value.busy) return
        setState { copy(busy = true, confirm = null) }
        scope.launch {
            try {
                when (confirm) {
                    DashboardConfirm.ResetAll -> catalog.resetAll(viewerId)
                    is DashboardConfirm.MarkSeason -> catalog.markSeasonWatched(viewerId, confirm.season)
                    is DashboardConfirm.ResetSeason -> catalog.resetSeason(viewerId, confirm.season)
                }
                toasts.ok(
                    when (confirm) {
                        DashboardConfirm.ResetAll -> S.resetAllDone
                        is DashboardConfirm.MarkSeason -> S.markSeasonDone
                        is DashboardConfirm.ResetSeason -> S.resetSeasonDone
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("dashboard", "action failed: $confirm", e)
                toasts.error(S.actionFailed)
            } finally {
                setState { copy(busy = false) }
            }
        }
    }

    /**
     * Rebuilds the catalog from the provider and says how it went.
     *
     * The store runs the work and owns the in-flight state. The words, the
     * progress counter and both outcomes, are chosen here.
     */
    fun refreshCatalog() {
        scope.launch {
            catalog.refreshCatalog { done, total ->
                if (total == 0) S.refreshingEpisodes else S.refreshSeasonProgress(done, total)
            }
                .onSuccess { toasts.ok(S.refreshed(it.episodes, it.written, it.withoutVideo, it.caveaEpisodes, it.caveaOnly)) }
                .onFailure { toasts.error(ErrorMessages.catalogRefresh(it)) }
        }
    }

    fun signOut() = account("sign-out failed") { session.signOut() }

    /** Signs out and continues as a guest. See [SessionViewModel.switchToGuest]. */
    fun switchToGuest() = account("switch to guest failed") { session.switchToGuest() }

    private fun account(failure: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("auth", failure, e)
                toasts.error(S.actionFailed)
            }
        }
    }
}

/** Makes the dashboard's ViewModel, one per visit to the screen. */
fun interface DashboardViewModelFactory {
    fun create(): DashboardViewModel
}
