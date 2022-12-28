package app.dapk.state

import kotlin.reflect.KClass

fun <A : Action, S> sideEffect(klass: KClass<A>, block: suspend (A, S) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A> {
    return {
        Async(key = klass) { action -> block(action, getState()) }
    }
}

fun <A : Action, S> change(klass: KClass<A>, block: (A, S) -> S): (ReducerScope<S>) -> ActionHandler<S, A> {
    return {
        Sync(key = klass, block)
    }
}

fun <A : Action, S> async(klass: KClass<A>, block: suspend ReducerScope<S>.(A) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A> {
    return {
        Async(key = klass, block)
    }
}

fun <A : Action, S> multi(klass: KClass<A>, block: Multi<A, S>.(A) -> (ReducerScope<S>) -> ActionHandler<S, A>): (ReducerScope<S>) -> ActionHandler<S, A> {
    val multiScope = object : Multi<A, S> {
        override fun sideEffect(block: suspend (S) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A> = sideEffect(klass) { _, state -> block(state) }
        override fun change(block: (A, S) -> S): (ReducerScope<S>) -> ActionHandler<S, A> = change(klass, block)
        override fun async(block: suspend ReducerScope<S>.(A) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A> = async(klass, block)
        override fun nothing() = sideEffect { }
    }

    return {
        Delegate(key = klass) { action ->
            block(multiScope, action).invoke(this)
        }
    }
}

internal class Async<S, A : Action>(override val key: KClass<A>, val handler: suspend ReducerScope<S>.(A) -> Unit) : ActionHandler<S, A>
internal class Sync<S, A : Action>(override val key: KClass<A>, val handler: (A, S) -> S) : ActionHandler<S, A>
internal class Delegate<S, A : Action>(override val key: KClass<A>, val handler: ReducerScope<S>.(A) -> ActionHandler<S, A>) : ActionHandler<S, A>

interface Multi<A : Action, S> {
    fun sideEffect(block: suspend (S) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A>
    fun nothing(): (ReducerScope<S>) -> ActionHandler<S, A>
    fun change(block: (A, S) -> S): (ReducerScope<S>) -> ActionHandler<S, A>
    fun async(block: suspend ReducerScope<S>.(A) -> Unit): (ReducerScope<S>) -> ActionHandler<S, A>
}

interface Action