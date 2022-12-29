package app.dapk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

interface Store<S> {
    fun dispatch(action: Action)
    fun getState(): S
    fun subscribe(subscriber: (S) -> Unit)
}

interface ReducerFactory<S> {
    fun create(scope: ReducerScope<S>): Reducer<S>
    fun initialState(): S
}

fun interface Reducer<S> {
    fun reduce(action: Action): S
}

fun <S> createStore(reducerFactory: ReducerFactory<S>, coroutineScope: CoroutineScope): Store<S> {
    val subscribers = mutableListOf<(S) -> Unit>()
    var state: S = reducerFactory.initialState()
    return object : Store<S> {
        private val scope = createScope(coroutineScope, this)
        private val reducer = reducerFactory.create(scope)

        override fun dispatch(action: Action) {
            coroutineScope.launch {
                state = reducer.reduce(action).also { nextState ->
                    if (nextState != state) {
                        subscribers.forEach { it.invoke(nextState) }
                    }
                }
            }
        }

        override fun getState() = state

        override fun subscribe(subscriber: (S) -> Unit) {
            subscribers.add(subscriber)
        }
    }
}

private fun <S> createScope(coroutineScope: CoroutineScope, store: Store<S>) = object : ReducerScope<S> {
    override val coroutineScope = coroutineScope
    override fun dispatch(action: Action) = store.dispatch(action)
    override fun getState(): S = store.getState()
}

interface ReducerScope<S> {
    val coroutineScope: CoroutineScope
    fun dispatch(action: Action)
    fun getState(): S
}
