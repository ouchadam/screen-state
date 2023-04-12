package app.dapk

import app.dapk.gen.todoState
import app.dapk.state.createStore
import app.dapk.thunk.thunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

val database = Database()

fun main() {
}

private fun combineExample() {
}

private fun todoExample() {
    val store = createStore(todoReducer, thunk(CoroutineScope(Dispatchers.Default)))

    store.subscribe {
        println("result: $it")
    }

    store.todoState.run {
        add("hello")
        add("world")
        add("another todo")
        edit("1", "hello (edit)")
        delete("2")
        markCompleted("3")
    }
}

