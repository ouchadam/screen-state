package app.dapk.internal

import app.dapk.state.Action
import app.dapk.state.Environment
import app.dapk.state.Execution
import app.dapk.state.Reducer
import app.dapk.state.ReducerBuilder
import app.dapk.state.ReducerFactory
import app.dapk.state.ReducerRegistrar
import app.dapk.state.ReducerScope
import kotlin.reflect.KClass

internal fun <S> createReducerFactory(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
) = object : ReducerFactory<S> {
    @Suppress("UNCHECKED_CAST")
    override fun create(scope: ReducerScope<S>, environment: Environment): Reducer<S> {
        val actionHandlers = environment.defaultActionHandlers.toMutableMap() as MutableMap<KClass<*>, (Action) -> Execution<S>?>
        val registrar = ReducerRegistrar { key, update -> actionHandlers[key] = update }
        val builderImpl = ReducerBuilderImpl(scope, registrar)
        builder(builderImpl)

        return Reducer { action ->
            actionHandlers
                .filterKeys { it == action::class }
                .values
                .mapNotNull { it.invoke(action) }
                .fold(scope.getState()) { acc, execution ->
                    execution.execute(acc, environment)
                }
        }
    }

    override fun initialState() = initialState
}

private class ReducerBuilderImpl<S>(
    scope: ReducerScope<S>,
    registrar: ReducerRegistrar<S>
) : ReducerScope<S> by scope, ReducerRegistrar<S> by registrar, ReducerBuilder<S>

