package app.dapk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun <S, A : Action> createReducer(
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
                                is ActionHandler.Async -> {
                                    scope.coroutineScope.launch {
                                        handler.handler.invoke(scope, action)
                                    }
                                    acc
                                }

                                is ActionHandler.Sync -> handler.handler.invoke(action, acc)
                                is ActionHandler.Delegate -> when (val next = handler.handler.invoke(scope, action)) {
                                    is ActionHandler.Async -> {
                                        scope.coroutineScope.launch {
                                            next.handler.invoke(scope, action)
                                        }
                                        acc
                                    }

                                    is ActionHandler.Sync -> next.handler.invoke(action, acc)
                                    is ActionHandler.Delegate -> error("is not possible")
                                }
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

fun <S1, S2> combineReducers(r1: ReducerFactory<S1>, r2: ReducerFactory<S2>): ReducerFactory<Combined2<S1, S2>> {
    return object : ReducerFactory<Combined2<S1, S2>> {
        override fun create(scope: ReducerScope<Combined2<S1, S2>>): Reducer<Combined2<S1, S2>> {
            val r1Scope = createReducerScope(scope) { scope.getState().state1 }
            val r2Scope = createReducerScope(scope) { scope.getState().state2 }

            val r1Reducer = r1.create(r1Scope)
            val r2Reducer = r2.create(r2Scope)
            return Reducer {
                Combined2(r1Reducer.reduce(it), r2Reducer.reduce(it))
            }
        }

        override fun initialState(): Combined2<S1, S2> = Combined2(r1.initialState(), r2.initialState())
    }
}

data class Combined2<S1, S2>(val state1: S1, val state2: S2)

private fun <S> createReducerScope(scope: ReducerScope<*>, state: () -> S) = object : ReducerScope<S> {
    override val coroutineScope: CoroutineScope = scope.coroutineScope
    override fun dispatch(action: Action) = scope.dispatch(action)
    override fun getState() = state.invoke()
}
