package app.dapk.state

import kotlin.reflect.KClass

interface Store<S> {
    fun dispatch(action: Action)
    fun getState(): S
    fun subscribe(subscriber: (S) -> Unit)
}

fun <S> createStore(
    reducerFactory: ReducerFactory<S>,
    vararg extensions: StoreExtension.Factory<S>
): Store<S> {
    val subscribers = mutableListOf<(S) -> Unit>()
    var state: S = reducerFactory.initialState()
    return object : Store<S> {
        private val scope = createScope(this)
        private val reducer = reducerFactory.create(scope, extensions.map { it.create(scope) })

        override fun dispatch(action: Action) {
            state = reducer.reduce(action).also { nextState ->
                if (nextState != state) {
                    subscribers.forEach { it.invoke(nextState) }
                }
            }
        }

        override fun getState() = state

        override fun subscribe(subscriber: (S) -> Unit) {
            subscribers.add(subscriber)
        }
    }
}

interface StoreExtension<S> {

    fun registerHandlers(): Map<KClass<*>, (Action) -> Execution<*>> = emptyMap()
    fun extendEnvironment(): Map<String, Any>

    fun interface Factory<S> {
        fun create(reducerScope: ReducerScope<S>): StoreExtension<S>
    }
}

private fun <S> createScope(store: Store<S>) = object : ReducerScope<S> {
    override fun dispatch(action: Action) = store.dispatch(action)
    override fun getState(): S = store.getState()
}
