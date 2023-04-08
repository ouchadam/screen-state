package app.dapk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface Store<S> {

    fun dispatch(action: Action)
    fun getState(): S
    fun subscribe(subscriber: (S) -> Unit)
}

fun <S> createStore(reducerFactory: ReducerFactory<S>, coroutineScope: CoroutineScope): Store<S> {
    val subscribers = mutableListOf<(S) -> Unit>()
    var state: S = reducerFactory.initialState()
    val environment = createEnvironment(coroutineScope)
    return object : Store<S> {
        private val scope = createScope(this, environment)
        private val reducer = reducerFactory.create(scope, environment)

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

private fun createEnvironment(coroutineScope: CoroutineScope) = object : Environment {

    private val jobBag = mutableMapOf<String, Job>()
    override val coroutineScope: CoroutineScope = coroutineScope

    override fun register(key: String, job: Job) {
        cancel(key)
        jobBag[key] = job
    }

    override fun cancel(key: String) {
        jobBag[key]?.cancel()
    }
}

private fun <S> createScope(store: Store<S>, environment: Environment) = object : ReducerScope<S> {
    override fun dispatch(action: Action) = store.dispatch(action)
    override fun getState(): S = store.getState()
    override fun cancel(key: String) = environment.cancel(key)
}
