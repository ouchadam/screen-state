package app.dapk.state

import app.dapk.internal.Update
import app.dapk.internal.UpdateExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

interface Store<S> {
    fun dispatch(action: Action)
    fun getState(): S
    fun subscribe(subscriber: (S) -> Unit)
}

fun <S> createStore(reducerFactory: ReducerFactory<S>, coroutineScope: CoroutineScope): Store<S> {
    val subscribers = mutableListOf<(S) -> Unit>()
    var state: S = reducerFactory.initialState()
    return object : Store<S> {
        private val scope = createScope(this)
        private val environment = createEnvironment(coroutineScope, scope)
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

private fun <S> createEnvironment(coroutineScope: CoroutineScope, reducerScope: ReducerScope<S>) = object : Environment {

    private val jobBag = mutableMapOf<String, Job>()
    override val dynamic: Map<String, Any> = mapOf("thunk" to createThunkContext(this, reducerScope))
    override val coroutineScope: CoroutineScope = coroutineScope
    override val defaultActionHandlers: Map<KClass<*>, (Action) -> Execution<*>?> = mapOf(
        ThunkUpdate::class to { execution -> UpdateExec((execution as ThunkUpdate<S>).update) }
    )

    override fun register(key: String, job: Job) {
        cancel(key)
        jobBag[key] = job
    }

    override fun cancel(key: String) {
        jobBag[key]?.cancel()
    }
}

private data class ThunkUpdate<S>(val update: Update<S>) : Action

private fun <S> createThunkContext(environment: Environment, scope: ReducerScope<S>) = object : ThunkContext<S> {
    override fun register(update: Update<S>) = dispatch(ThunkUpdate(update))
    override fun dispatch(action: Action) = scope.dispatch(action)
    override fun getState(): S = scope.getState()

    override fun <T> Flow<T>.launchInThunk() {
        launchIn(environment.coroutineScope)
    }
}

private fun <S> createScope(store: Store<S>) = object : ReducerScope<S> {
    override fun dispatch(action: Action) = store.dispatch(action)
    override fun getState(): S = store.getState()
}
