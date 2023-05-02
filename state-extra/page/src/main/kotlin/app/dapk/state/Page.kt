package app.dapk.state

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions

fun interface Router<R, C, P: Any> {
    fun route(route: R, content: C): P
}

fun <R: Any, C, P: Any> createPageReducer(
    initialRoute: R,
    router: Router<R, C, P>,
    contentReducer: Routeable<R>.() -> CombinedReducerFactory<C>
): ReducerFactory<Page<C, R>> {
    val (routeContainerReducer, routeState) = createReducer(RouteContainer(initialRoute)) {
        updateRoute { _, updateRoute ->
            update { route(updateRoute.route as R) }
        }
    }.share()

    val routeable = object : Routeable<R> {
        private val lazyRouteState by lazy { routeState() }

        override fun changeRoute(route: R) {
            lazyRouteState.dispatch(GenRouteContainerActions.UpdateRoute(route as Any))
        }

        override fun currentRoute() = lazyRouteState.getState().route
    }

    return CombinePage.fromReducers(
        content = contentReducer(routeable)
            .intercept { state, childState, _ -> (router.route(routeable.currentRoute(), state)::class != childState::class) },
        routeContainer = routeContainerReducer
    )
}

interface Routeable<R: Any> {
    fun changeRoute(route: R)
    fun currentRoute(): R
}

@CombinedState
data class Page<C, R>(
    val content: C,
    val routeContainer: RouteContainer<R>,
)

@State(actions = [RouteContainer.Actions::class])
@JvmInline
value class RouteContainer<R>(val route: R) {
    @StateActions
    interface Actions {
        fun updateRoute(route: Any)
    }
}

fun <C, R> Store<Page<C, R>>.asPageContent() = this as Store<C>
fun <C, R> Store<Page<C, R>>.asPage() = this as Store<Page<*, *>>