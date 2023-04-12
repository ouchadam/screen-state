package app.dapk.thunk

import app.dapk.internal.Update
import app.dapk.internal.UpdateExec
import app.dapk.internal.UpdateRegistrar
import app.dapk.state.Action
import app.dapk.state.Execution
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ReducerScope
import app.dapk.state.StoreExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

fun <S> thunk(coroutineScope: CoroutineScope) = StoreExtension.Factory { scope ->
    object : StoreExtension<S> {
        override fun registerHandlers(): Map<KClass<*>, (Action) -> Execution<*>> = mapOf(
            ThunkUpdate::class to { UpdateExec((it as ThunkUpdate<S>).update) }
        )

        override fun extendEnvironment(): Map<String, Any> = mapOf(
            "thunk" to ThunkExecutor(
                coroutineScope,
                createThunkContext(coroutineScope, scope),
            )
        )
    }
}

fun <S> ExecutionRegistrar<S>.thunk(key: String? = null, block: suspend ThunkExecutionContext<S>.() -> Unit) {
    register(ThunkExec(key ?: name, block))
}

private data class ThunkUpdate<S>(val update: Update<S>) : Action

interface ThunkExecutionContext<S> : UpdateRegistrar<S>, ReducerScope<S> {
    fun <T> Flow<T>.launchInThunk()
}

private class ThunkExecutor<S>(
    private val coroutineScope: CoroutineScope,
    private val thunkExecutionContext: ThunkExecutionContext<S>
) {
    fun execute(key: String, value: suspend ThunkExecutionContext<S>.() -> Unit) {
        //register
        coroutineScope.launch {
            value.invoke(thunkExecutionContext)
        }
    }
}

private fun <S> createThunkContext(coroutineScope: CoroutineScope, scope: ReducerScope<S>) = object : ThunkExecutionContext<S> {
    override fun register(update: Update<S>) = dispatch(ThunkUpdate(update))
    override fun dispatch(action: Action) = scope.dispatch(action)
    override fun getState(): S = scope.getState()
    override fun <T> Flow<T>.launchInThunk() {
        launchIn(coroutineScope)
    }
}

private data class ThunkExec<S>(private val key: String, private val value: suspend ThunkExecutionContext<S>.() -> Unit) : Execution<S> {
    @Suppress("UNCHECKED_CAST")
    override fun execute(state: S, context: Execution.Context): S {
        return state.also {
            val thunk = (context.extensionProperties["thunk"] as ThunkExecutor<S>)
            thunk.execute(key, value)
        }
    }
}