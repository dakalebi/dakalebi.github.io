package ge.dakalebi.presentation

import ge.dakalebi.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.emptyFlow

/*
 * What a player supplies to its PlaybackViewModel, as fakes, for the tests of the
 * ViewModels that play episodes.
 */

/** A media element the test moves by hand. */
class FakePlayhead(
    override var position: Double = 0.0,
    override var duration: Double? = 1_500.0,
) : Playhead

/** Every route asked for, pushed or replaced. [current] is the last of either. */
class RecordingRouter(start: Route = Route.Dashboard) : Router {
    val went = mutableListOf<Route>()
    val replaced = mutableListOf<Route>()

    override var current: Route = start
        private set

    override fun go(route: Route) {
        went += route
        current = route
    }

    override fun replace(route: Route) {
        replaced += route
        current = route
    }

    override fun absolute(route: Route) = Router.href(route)
}

class FakePreferences : PreferencesRepository {
    val intents = mutableMapOf<String, String>()
    override fun interfaceScale() = 100
    override fun setInterfaceScale(percent: Int) = Unit
    override fun playIntent(episodeId: String) = intents[episodeId]
    override fun setPlayIntent(episodeId: String, intent: String) {
        intents[episodeId] = intent
    }
    override val externalChanges = emptyFlow<Unit>()
}
