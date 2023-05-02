package app.dapk.examples

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.examples.PageMap.*
import app.dapk.state.Router
import app.dapk.state.createPageReducer
import app.dapk.state.createReducer

@CombinedState(commonActions = [LifecycleActions::class])
sealed interface PageMap {
    @State data class PageOne(val content: String) : PageMap
    @State data class PageTwo(val content: String) : PageMap
}

sealed interface Route {
    object PageOne : Route
    data class PageTwo(val argument: String) : Route

    companion object {
        val router = Router<Route, PageMapProxy, PageMap> { route, state ->
            when (route) {
                PageOne -> state.pageOne
                is PageTwo -> state.pageTwo
            }
        }
    }
}

@StateActions interface LifecycleActions {
    fun start()
}

val pageReducer = createPageReducer(
    initialRoute = Route.PageOne,
    router = Route.router,
    contentReducer = {
        CombinePageMap.fromReducers(
            pageOne = createReducer(PageOne("")) {
                start { _, _ -> update { content("started!") } }
            },
            pageTwo = createReducer(PageTwo("")) {
                start { _, _ -> update { content("started!") } }
            }
        )
    }
)
