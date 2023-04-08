package app.dapk.internal

import app.dapk.state.Action
import app.dapk.state.Environment
import app.dapk.state.Execution
import app.dapk.state.Execution.*
import app.dapk.state.Reducer
import app.dapk.state.ReducerBuilder
import app.dapk.state.ReducerFactory
import app.dapk.state.ReducerRegistrar
import app.dapk.state.ReducerScope
import app.dapk.state.ThunkContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

internal fun <S> createReducerFactory(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
) = object : ReducerFactory<S> {
    @Suppress("UNCHECKED_CAST")
    override fun create(scope: ReducerScope<S>, environment: Environment): Reducer<S> {
        val map = mutableMapOf<KClass<*>, (Action) -> Execution<S>?>().also {
            it[ThunkUpdate::class] = { execution -> UpdateExec((execution as ThunkUpdate<S>).update) }
        }

        val registrar = ReducerRegistrar { key, update -> map[key] = update }
        val builderImpl = ReducerBuilderImpl(scope, registrar)
        builder(builderImpl)

        val thunkContext = createThunkContext(environment, scope)
        return Reducer { action ->
            map
                .filterKeys { it == action::class }
                .values
                .mapNotNull { it.invoke(action) }
                .fold(scope.getState()) { acc, execution ->
                    when (execution) {
                        is ThunkExec -> acc.also {
                            environment.register(execution.key, environment.coroutineScope.launch {
                                execution.value.invoke(thunkContext)
                            })
                        }

                        is UpdateExec -> execution.value.update(acc)
                    }
                }
        }
    }

    override fun initialState() = initialState
}

private class ReducerBuilderImpl<S>(
    scope: ReducerScope<S>,
    registrar: ReducerRegistrar<S>
) : ReducerScope<S> by scope, ReducerRegistrar<S> by registrar, ReducerBuilder<S>

private data class ThunkUpdate<S>(val update: Update<S>) : Action

private fun <S> createThunkContext(environment: Environment, scope: ReducerScope<S>) = object : ThunkContext<S> {
    override fun register(update: Update<S>) = dispatch(ThunkUpdate(update))
    override fun cancel(key: String) = scope.cancel(key)
    override fun dispatch(action: Action) = scope.dispatch(action)
    override fun getState(): S = scope.getState()

    override fun <T> Flow<T>.launchInThunk() {
        launchIn(environment.coroutineScope)
    }
}
