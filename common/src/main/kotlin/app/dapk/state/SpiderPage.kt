package app.dapk.state

data class SpiderPage<T>(
    val route: Route<T>,
    val label: String,
    val parent: Route<*>?,
    val state: T,
    val hasToolbar: Boolean = true,
)

@JvmInline
value class Route<out S>(val value: String)