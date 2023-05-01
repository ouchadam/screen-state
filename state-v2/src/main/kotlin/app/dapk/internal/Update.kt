package app.dapk.internal

import app.dapk.state.Action

interface UpdateAction<S>: Action {
    val update: Update<S>
}

fun interface Update<T> {
    fun update(from: T): T
}

fun interface UpdateRegistrar<T> {
    fun register(update: Update<T>)
}

fun interface Collectable<T> {
    fun collect(): Update<T>
}
