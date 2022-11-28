package app.dapk.state.page

import app.dapk.state.*
import app.dapk.state.page.PageAction.*
import app.dapk.state.page.PageStateChange.*
import kotlin.reflect.KClass

sealed interface PageAction<out P> : Action {
    data class GoTo<P : Any>(val page: SpiderPage<P>) : PageAction<P>
}

sealed interface PageStateChange : Action {
    data class ChangePage<P : Any>(val previous: SpiderPage<out P>, val newPage: SpiderPage<out P>) : PageAction<P>
    data class UpdatePage<P : Any>(val pageContent: P) : PageAction<P>
}

data class PageContainer<P>(
    val page: SpiderPage<out P>
)

interface PageReducerScope<P> {

    fun <PC : Any> withPageContent(page: KClass<PC>, block: PageDispatchScope<PC>.() -> Unit)
    fun rawPage(): SpiderPage<out P>
}

interface PageDispatchScope<PC> {

    fun ReducerScope<*>.pageDispatch(action: PageAction<PC>)
    fun getPageState(): PC?
}

fun <P : Any, S : Any> createPageReducer(
    initialPage: SpiderPage<out P>,
    factory: PageReducerScope<P>.() -> ReducerFactory<S>,
): ReducerFactory<Combined2<PageContainer<P>, S>> = shareState {
    combineReducers(createPageReducer(initialPage), factory(pageReducerScope()))
}

private fun <P : Any, S : Any> SharedStateScope<Combined2<PageContainer<P>, S>>.pageReducerScope() = object : PageReducerScope<P> {
    override fun <PC : Any> withPageContent(page: KClass<PC>, block: PageDispatchScope<PC>.() -> Unit) {
        val currentPage = getSharedState().state1.page.state
        if (currentPage::class == page) {
            val pageDispatchScope = object : PageDispatchScope<PC> {
                override fun ReducerScope<*>.pageDispatch(action: PageAction<PC>) {
                    val currentPageGuard = getSharedState().state1.page.state
                    if (currentPageGuard::class == page) {
                        dispatch(action)
                    }
                }

                override fun getPageState() = getSharedState().state1.page.state as? PC
            }
            block(pageDispatchScope)
        }
    }

    override fun rawPage() = getSharedState().state1.page
}

@Suppress("UNCHECKED_CAST")
private fun <P : Any> createPageReducer(
    initialPage: SpiderPage<out P>
): ReducerFactory<PageContainer<P>> {
    return createReducer(
        initialState = PageContainer(
            page = initialPage
        ),

        async(GoTo::class) { action ->
            val state = getState()
            if (state.page.state::class != action.page.state::class) {
                dispatch(ChangePage(previous = state.page, newPage = action.page))
            } else {
                dispatch(UpdatePage(action.page.state))
            }
        },

        change(ChangePage::class) { action, state ->
            state.copy(page = action.newPage as SpiderPage<out P>)
        },

        change(UpdatePage::class) { action, state ->
            val isSamePage = state.page.state::class == action.pageContent::class
            if (isSamePage) {
                val updatedPageContent = (state.page as SpiderPage<Any>).copy(state = action.pageContent)
                state.copy(page = updatedPageContent as SpiderPage<out P>)
            } else {
                state
            }
        },
    )
}

inline fun <reified PC : Any> PageReducerScope<*>.withPageContext(crossinline block: PageDispatchScope<PC>.(PC) -> Unit) {
    withPageContent(PC::class) { getPageState()?.let { block(it) } }
}

