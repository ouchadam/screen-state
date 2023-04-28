package app.dapk.state

import java.util.*
import kotlin.reflect.KClass

interface Store<S> : StoreScope<S> {
    fun subscribe(subscriber: (S) -> Unit)
}

fun <S> createStore(
    reducerFactory: ReducerFactory<S>,
    vararg extensions: StoreExtension.Factory<S>
): Store<S> {
    val subscribers = mutableListOf<(S) -> Unit>()
    var state: S = reducerFactory.initialState()
    return object : Store<S> {

        private var idle = true
        private val queue = Stack<Action>()
        private val scope = createScope(this) { addToQueue(it) }
        private val reducer = reducerFactory.create(scope, extensions.map { it.create(scope) })

        override fun dispatch(action: Action) {
            idle = false
            state = reducer.reduce(getState(), action).also { nextState ->
                if (nextState != state) {
                    subscribers.forEach { it.invoke(nextState) }
                }
            }
            idle = true
            onIdle()
        }

        private fun addToQueue(action: Action) {
            when (idle) {
                true -> dispatch(action)
                false -> queue.add(action)
            }
        }

        private fun onIdle() {
            while (queue.isNotEmpty()) {
                dispatch(queue.pop())
            }
        }

        override fun getState() = state

        override fun subscribe(subscriber: (S) -> Unit) {
            subscribers.add(subscriber)
        }
    }
}

interface StoreExtension {

    fun registerHandlers(): Map<KClass<*>, (Action) -> Execution<*>> = emptyMap()
    fun extendEnvironment(): Map<String, Any>

    fun interface Factory<S> {
        fun create(storeScope: StoreScope<S>): StoreExtension
    }
}

private fun <S> createScope(store: Store<S>, subDispatch: (Action) -> Unit) =
    object : StoreScope<S> {
        override fun dispatch(action: Action) = subDispatch.invoke(action)
        override fun getState(): S = store.getState()
    }
