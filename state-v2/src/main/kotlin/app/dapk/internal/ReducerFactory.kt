package app.dapk.internal

import app.dapk.state.Action
import app.dapk.state.Execution
import app.dapk.state.Reducer
import app.dapk.state.ReducerBuilder
import app.dapk.state.ReducerFactory
import app.dapk.state.ReducerFuncs
import app.dapk.state.ReducerRegistrar
import app.dapk.state.StoreScope
import app.dapk.state.StoreExtension
import kotlin.reflect.KClass

internal fun <S> createReducerFactory(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
) = object : ReducerFactory<S> {
    override fun create(scope: StoreScope<S>, extensions: List<StoreExtension>): Reducer<S> {
        val config = buildConfig(builder, scope, extensions)
        val executionContext = extensions.createExecutionContext()
        return Reducer { state, action ->
            config
                .actionHandlers.filterKeys { it == action::class }
                .filterTakeIf { config.accept.invoke(action) }
                .values
                .mapNotNull { it.invoke(action) }
                .fold(state) { acc, execution ->
                    execution.execute(acc, executionContext)
                }
        }
    }

    override fun initialState() = initialState
}

private fun <K, V> Map<K, V>.filterTakeIf(predicate: () -> Boolean): Map<K, V> {
    return when (predicate()) {
        true -> this
        else -> emptyMap()
    }
}

private class ReducerConfiguration<S>(
    val actionHandlers: Map<KClass<*>, (Action) -> Execution<S>?>,
    val accept: (Action) -> Boolean
)


private fun <S> buildConfig(
    builder: ReducerBuilder<S>.() -> Unit,
    scope: StoreScope<S>,
    extensions: List<StoreExtension>
): ReducerConfiguration<S> {
    val actionHandlers = extensions.createActionHandlers<S>()
    var accept: (Action) -> Boolean = { true }
    val registrar = ReducerRegistrar { key, update -> actionHandlers[key] = update }

    val funcRegistrar = object : ReducerFuncs {
        override fun accept(predicate: (Action) -> Boolean) {
            accept = predicate
        }
    }

    val builderImpl = ReducerBuilderImpl(scope, registrar, funcRegistrar)
    builder(builderImpl)
    return ReducerConfiguration(actionHandlers, accept)
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
    scope: StoreScope<S>,
    registrar: ReducerRegistrar<S>,
    funcs: ReducerFuncs,
) : StoreScope<S> by scope, ReducerRegistrar<S> by registrar, ReducerBuilder<S>, ReducerFuncs by funcs

