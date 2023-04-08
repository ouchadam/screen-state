package app.dapk.state

import app.dapk.internal.Update
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
    val coroutineScope: CoroutineScope
    fun register(key: String, job: Job)
    fun cancel(key: String)
}

interface ReducerBuilder<S> : ReducerScope<S>, ReducerRegistrar<S>

interface ReducerScope<S> {
    fun dispatch(action: Action)
    fun getState(): S
    fun cancel(key: String)
}

fun interface ReducerRegistrar<S> {
    fun register(key: KClass<*>, update: (Action) -> Execution<S>?)
}

sealed interface Execution<S> {
    data class ThunkExec<S>(val key: String, val value: suspend ThunkContext<S>.() -> Unit) : Execution<S>
    data class UpdateExec<S>(val value: Update<S>) : Execution<S>
}

interface ThunkContext<S> : UpdateRegistrar<S>, ReducerScope<S> {
    fun <T> Flow<T>.launchInThunk()
}

interface ExecutionCollector<S> : UpdateRegistrar<S> {
    fun thunk(key: String? = null, block: suspend ThunkContext<S>.() -> Unit)
}