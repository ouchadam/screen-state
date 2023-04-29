package app.dapk.state

import app.dapk.internal.createReducerFactory
import kotlin.reflect.KClass

fun interface Reducer<S> {
    fun reduce(state: S, action: Action): S
}

interface Action

fun <S : Any> createReducer(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit = {},
): ReducerFactory<S> = createReducerFactory(initialState, builder)

interface ReducerFactory<S> {
    fun create(scope: StoreScope<S>, extensions: List<StoreExtension>): Reducer<S>
    fun initialState(): S
}

interface CombinedReducerFactory<S>: ReducerFactory<S> {
    fun intercept(interceptor: (state: S, childState: Any, Action) -> Boolean): ReducerFactory<S>
}

interface ReducerBuilder<S> : StoreScope<S>, ReducerRegistrar<S>, ReducerFuncs

interface ReducerFuncs {
    fun accept(predicate: (Action) -> Boolean)
}

interface StoreScope<S> {
    fun dispatch(action: Action)
    fun getState(): S
}

fun interface ReducerRegistrar<S> {
    fun register(key: KClass<*>, update: (Action) -> Execution<S>?)
}

fun interface Execution<S> {
    fun execute(state: S, context: Context): S

    interface Context {
        val extensionProperties: Map<String, Any>
    }
}

interface ExecutionRegistrar<S> {
    val name: String
    fun register(execution: Execution<S>)
}
