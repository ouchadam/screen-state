package app.dapk

import app.dapk.gen.actions
import app.dapk.gen.allState
import app.dapk.gen.asyncState
import app.dapk.gen.sealedAllStateProxy
import app.dapk.gen.todoState
import app.dapk.state.createStore
import app.dapk.thunk.thunk
import kotlinx.coroutines.runBlocking

fun main() {
    asyncExample()
    combineExample()
    sealedCombineExample()
    todoExample()
}

private fun asyncExample() = runBlocking {
    val store = createStore(asyncReducer, thunk(coroutineScope = this))
    store.subscribe { println("result: $it") }
    store.asyncState.run {
        observeChanges()
    }
}

private fun combineExample() {
    val store = createStore(combinedReducer)

    store.subscribe { println("result: $it") }

    store.actions.run {
        stateOne.updateId("id 1")
        stateTwo.updateName("name 1")
        stateOne.updateId("id 2")
        stateTwo.updateName("name 2")
    }
    store.allState.randomize()
}

private fun sealedCombineExample() {
    val store = createStore(sealedCombinedReducer)

    store.subscribe { println("result: $it") }

    store.actions.run {
        stateFour.updateContent("content 1")
        stateFour.updateContent("content 2")
    }
    store.sealedAllStateProxy.randomize()
}

private fun todoExample() {
    val store = createStore(todoReducer)

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

