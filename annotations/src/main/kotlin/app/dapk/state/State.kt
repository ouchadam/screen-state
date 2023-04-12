package app.dapk.state

import app.dapk.internal.createReducerFactory
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
annotation class State(val actions: Array<KClass<*>> = [])

fun interface Reducer<S> {
    fun reduce(action: Action): S
}

interface Action

fun <S : Any> createReducer(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
): ReducerFactory<S> = createReducerFactory(initialState, builder)

interface ReducerFactory<S> {
    fun create(scope: ReducerScope<S>, extensions: List<StoreExtension<S>>): Reducer<S>
    fun initialState(): S
}

interface ReducerBuilder<S> : ReducerScope<S>, ReducerRegistrar<S>

interface ReducerScope<S> {
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
