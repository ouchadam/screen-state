package app.dapk

import app.dapk.PageMap.*
import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.gen.PageMapProxy
import app.dapk.gen.changePage
import app.dapk.gen.start
import app.dapk.gen.update
import app.dapk.state.ReducerFactory
import app.dapk.state.createReducer
import app.dapk.state.outer

@CombinedState(actions = [Actions::class], commonActions = [LifecycleActions::class])
sealed interface PageMap {
    @State data class PageOne(val content: String) : PageMap
    @State data class PageTwo(val content: String) : PageMap

    @StateActions interface Actions {
        fun changePage(key: String)
    }
}

@StateActions interface LifecycleActions {
    fun start()
}

fun createPageReducer(): ReducerFactory<PageMapProxy> {
    var currentPage = "pageOne"
    return app.dapk.gen.PageMap.fromReducers(
        pageOne = createReducer(PageOne("")) {
            accept { currentPage == "pageOne" }
            start { _, _ -> update { content("started!") } }
        },
        pageTwo = createReducer(PageTwo("")) {
            accept { currentPage == "pageTwo" }
            start { _, _ -> update { content("started!") } }
        }
    ).outer {
        changePage { _, changePage ->
            currentPage = changePage.key
        }
    }
}
