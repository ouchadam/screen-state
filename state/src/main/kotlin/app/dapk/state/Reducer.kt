package app.dapk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

sealed interface ActionHandler<S, A : Action> {
    val key: KClass<A>
}

fun <S : Any, A : Action> createReducer(
    initialState: S,
    vararg reducers: (ReducerScope<S>) -> ActionHandler<S, out A>,
): ReducerFactory<S> {
    return object : ReducerFactory<S> {
        override fun create(scope: ReducerScope<S>): Reducer<S> {
            val reducersMap = reducers
                .map { it.invoke(scope) as ActionHandler<S, Action> }
                .groupBy { it.key }

            return Reducer { action ->
                val result = reducersMap.keys
                    .filter { it.java.isAssignableFrom(action::class.java) }
                    .fold(scope.getState()) { outerAccumulator, key ->
                        val actionHandlers = reducersMap[key]!!
                        actionHandlers.fold(outerAccumulator) { acc, handler ->
                            when (handler) {
                                is Async -> {
                                    scope.coroutineScope.launch {
                                        handler.handler.invoke(scope, action)
                                    }
                                    acc
                                }

                                is Sync -> handler.handler.invoke(action, acc)
                                is Delegate -> when (val next = handler.handler.invoke(scope, action)) {
                                    is Async -> {
                                        scope.coroutineScope.launch {
                                            next.handler.invoke(scope, action)
                                        }
                                        acc
                                    }

                                    is Sync -> next.handler.invoke(action, acc)
                                    is Delegate -> error("is not possible")
                                    else -> error("is not possible")
                                }

                                else -> error("is not possible")
                            }
                        }
                    }
                result
            }
        }

        override fun initialState(): S = initialState

    }
}

fun interface SharedStateScope<C> {
    fun getSharedState(): C
}

fun <S> shareState(block: SharedStateScope<S>.() -> ReducerFactory<S>): ReducerFactory<S> {
    var internalScope: ReducerScope<S>? = null
    val scope = SharedStateScope { internalScope!!.getState() }
    val combinedFactory = block(scope)
    return object : ReducerFactory<S> {
        override fun create(scope: ReducerScope<S>) = combinedFactory.create(scope).also { internalScope = scope }
        override fun initialState() = combinedFactory.initialState()
    }
}

fun <S1 : Any, S2 : Any> combineReducers(r1: ReducerFactory<S1>, r2: ReducerFactory<S2>): ReducerFactory<Combined2<S1, S2>> {
    return combineReducers(
        to = { Combined2(state1 = it.inner(r1), state2 = it.inner(r2)) },
        from = { DynamicReducers(mapOf(r1.toKey() to it.state1, r2.toKey() to it.state2)) },
        initial = { Combined2(r1.initialState(), r2.initialState()) },
        r1,
        r2
    )
}

private fun <R> combineReducers(to: (DynamicReducers) -> R, from: (R) -> DynamicReducers, initial: () -> R, vararg reducers: ReducerFactory<*>): ReducerFactory<R> {
    val factory = combineReducers(reducers.toList() as List<ReducerFactory<Any>>)
    return object : ReducerFactory<R> {
        override fun create(scope: ReducerScope<R>): Reducer<R> {
            val delegateScope = createReducerScope(scope) { from(scope.getState()) }
            return Reducer { to(factory.create(delegateScope).reduce(it)) }
        }

        override fun initialState(): R = initial()
    }
}

fun combineReducers(reducers: List<ReducerFactory<Any>>): ReducerFactory<DynamicReducers> {
    return object : ReducerFactory<DynamicReducers> {
        override fun create(scope: ReducerScope<DynamicReducers>): Reducer<DynamicReducers> {
            val scoped = reducers.map {
                val innerScope = createReducerScope(scope) { scope.getState().inner<Any>(it) }
                it.toKey() to it.create(innerScope)
            }

            return Reducer { action ->
                val states = scoped.map { (key, reducer) ->
                    key to reducer.reduce(action)
                }.toMap()
                DynamicReducers(states)
            }
        }

        override fun initialState(): DynamicReducers = DynamicReducers(
            reducers.associate { it.hashCode() to it.initialState() }
        )
    }
}

data class Combined2<S1, S2>(val state1: S1, val state2: S2)

private typealias ReducerKey = Int

data class DynamicReducers(private var state: Map<ReducerKey, Any>) {
    internal fun <S> inner(reducer: ReducerFactory<*>) = state[reducer.toKey()] as S
}

private fun ReducerFactory<*>.toKey() = this.hashCode()

private fun <S> createReducerScope(scope: ReducerScope<*>, state: () -> S) = object : ReducerScope<S> {
    override val coroutineScope: CoroutineScope = scope.coroutineScope
    override fun dispatch(action: Action) = scope.dispatch(action)
    override fun getState() = state.invoke()
}
