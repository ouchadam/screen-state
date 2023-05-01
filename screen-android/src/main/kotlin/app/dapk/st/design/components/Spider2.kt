package app.dapk.st.design.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.dapk.state.Page
import app.dapk.state.Route
import app.dapk.state.Router
import app.dapk.state.SpiderPage
import java.util.Stack

@Composable
fun <R, C, P: Any> RenderPage(
    page: Page<C, R>,
    router: Router<R, C, P>,
    onNavigate: (R?) -> Unit = {},
    toolbar: @Composable (onNavigate: (() -> Unit)?, title: String?) -> Unit = { _, _ -> },
    block: @Composable RenderPageScope.(P) -> Unit
) {
    val pageCache = remember { Stack<R>() }
    pageCache.add(page.routeContainer.route)

    val navigateAndPopStack = {
        when (pageCache.empty()) {
            true -> onNavigate(null)
            false -> onNavigate(pageCache.pop())
        }
    }

    val itemScope = object : RenderPageItemScope {
        override fun goBack() {
            navigateAndPopStack.invoke()
        }
    }

    val pageScope = object : RenderPageScope {
        @Composable
        override fun page(hasToolbar: Boolean, title: String?, block: @Composable RenderPageItemScope.() -> Unit) {
            if (hasToolbar) {
                toolbar(navigateAndPopStack, title)
            }
            BackHandler(onBack = navigateAndPopStack)

            block(itemScope)
        }
    }

    Column {
        block.invoke(pageScope, router.route(page.routeContainer.route, page.content))
    }
}

interface RenderPageScope {
    @Composable
    fun page(
        hasToolbar: Boolean,
        title: String?,
        block: @Composable RenderPageItemScope.() -> Unit
    )
}

interface RenderPageItemScope {
    fun goBack()
}
