package app.dapk.state

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions

fun interface Router<R, C> {
    fun route(route: R, content: C): Any
}

fun <R, C> createPageReducer(initialRoute: R, router: Router<R, C>, reducerFactory: CombinedReducerFactory<C>): ReducerFactory<Page<C, R>> {
    val (routeContainerReducer, getState) = createReducer(RouteContainer(initialRoute)) {
        updateRoute { _, updateRoute -> update { route(updateRoute.route as R) } }
    }.share()

    return CombinePage.fromReducers(
        content = reducerFactory
            .intercept { state, childState, _ -> (router.route(getState().route, state)::class != childState::class) },
        routeContainer = routeContainerReducer
    )
}

@CombinedState
data class Page<C, R>(
    val content: C,
    val routeContainer: RouteContainer<R>,
)

@State(actions = [RouteContainer.Actions::class])
data class RouteContainer<R>(val route: R) {

    @StateActions
    interface Actions {
        fun updateRoute(route: Any)
    }
}

