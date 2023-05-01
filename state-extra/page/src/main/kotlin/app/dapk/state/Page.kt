package app.dapk.state

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions

fun interface Router<R, C, P: Any> {
    fun route(route: R, content: C): P
}

fun <R, C, P: Any> createPageReducer(initialRoute: R, router: Router<R, C, P>, contentReducer: CombinedReducerFactory<C>): ReducerFactory<Page<C, R>> {
    val (routeContainerReducer, routeState) = createReducer(RouteContainer(initialRoute)) {
        updateRoute { _, updateRoute -> update { route(updateRoute.route as R) } }
    }.share()

    return CombinePage.fromReducers(
        content = contentReducer
            .intercept { state, childState, _ -> (router.route(routeState().getState().route, state)::class != childState::class) },
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

fun <C, R> Store<Page<C, R>>.asPageContent() = this as Store<C>
fun <C, R> Store<Page<C, R>>.asPage() = this as Store<Page<*, *>>