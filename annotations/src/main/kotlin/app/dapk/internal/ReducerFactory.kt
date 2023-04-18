package app.dapk.internal

import app.dapk.state.Action
import app.dapk.state.Execution
import app.dapk.state.Reducer
import app.dapk.state.ReducerBuilder
import app.dapk.state.ReducerFactory
import app.dapk.state.ReducerRegistrar
import app.dapk.state.ReducerScope
import app.dapk.state.StoreExtension
import kotlin.reflect.KClass

internal fun <S> createReducerFactory(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
) = object : ReducerFactory<S> {
    override fun create(scope: ReducerScope<S>, extensions: List<StoreExtension>): Reducer<S> {
        val actionHandlers = extensions.createActionHandlers<S>()
        val executionContext = extensions.createExecutionContext()

        val registrar = ReducerRegistrar { key, update -> actionHandlers[key] = update }
        val builderImpl = ReducerBuilderImpl(scope, registrar)
        builder(builderImpl)

        return Reducer { action ->
            actionHandlers
                .filterKeys { it == action::class }
                .values
                .mapNotNull { it.invoke(action) }
                .fold(scope.getState()) { acc, execution ->
                    execution.execute(acc, executionContext)
                }
        }
    }

    override fun initialState() = initialState
}

@Suppress("UNCHECKED_CAST")
private fun <S> List<StoreExtension>.createActionHandlers(): MutableMap<KClass<*>, (Action) -> Execution<S>?> {
    return fold(mutableMapOf()) { acc, curr ->
        acc.putAll(curr.registerHandlers() as Map<KClass<*>, (Action) -> Execution<S>>)
        acc
    }
}

private fun List<StoreExtension>.createExecutionContext() = object : Execution.Context {
    override val extensionProperties: Map<String, Any> = fold(mutableMapOf()) { acc, curr ->
        acc.putAll(curr.extendEnvironment())
        acc
    }
}

private class ReducerBuilderImpl<S>(
    scope: ReducerScope<S>,
    registrar: ReducerRegistrar<S>
) : ReducerScope<S> by scope, ReducerRegistrar<S> by registrar, ReducerBuilder<S>

