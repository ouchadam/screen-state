package app.dapk.state

import app.dapk.internal.UpdateRegistrar
import app.dapk.internal.createReducerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
annotation class State(val actions: Array<KClass<*>> = [])

fun interface Reducer<S> {
    suspend fun reduce(action: Action): S
}

interface Action

fun <S : Any> createReducer(
    initialState: S,
    builder: ReducerBuilder<S>.() -> Unit,
): ReducerFactory<S> = createReducerFactory(initialState, builder)

interface ReducerFactory<S> {

    fun create(scope: ReducerScope<S>, environment: Environment): Reducer<S>
    fun initialState(): S
}

interface Environment {
    val dynamic: Map<String, Any>
    val coroutineScope: CoroutineScope
    val defaultActionHandlers: Map<KClass<*>, (Action) -> Execution<*>?>
    fun register(key: String, job: Job)
    fun cancel(key: String)
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
    fun execute(state: S, environment: Environment): S
}


interface ThunkContext<S> : UpdateRegistrar<S>, ReducerScope<S> {
    fun <T> Flow<T>.launchInThunk()
}

interface ExecutionRegistrar<S> {
    val name: String
    fun register(execution: Execution<S>)
}
