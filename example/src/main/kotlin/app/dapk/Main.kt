package app.dapk

import app.dapk.foobar.AllState
import app.dapk.gen.todoState
import app.dapk.state.CombinedState
import app.dapk.state.ObjectFactory
import app.dapk.state.State
import app.dapk.state.combineReducers
import app.dapk.state.createReducer
import app.dapk.state.createStore
import java.io.Serializable

val database = Database()

fun main() {
}

@State
data class StateOne(
    val id: String
)

@State
data class StateTwo(
    val name: String
)

@CombinedState
class AllState(
    val stateOne: StateOne,
    val stateTwo: StateTwo,
)

private fun combineExample() {

        val bar = "" to ""

    val (a, b) = bar
    val stateOneReducer = createReducer(initialState = StateOne(id = "")) {
    }

    val stateTwoReducer = createReducer(initialState = StateTwo(name = "")) {
    }


    val reducer = combineReducers(stateOneReducer, stateTwoReducer, AllState.factory())


//    val todoReducer = createReducer(initialState = StateOne(todo = emptyList(), completed = emptyList())) {
//        add { todoState, payload ->
//            val newTodo = Todo(id = (todoState.todo.size + 1).toString(), payload.content)
//            update { todo(todoState.todo + newTodo) }
//        }
//    }
//
//
//    val store = createStore(
//
//    )
}

private fun todoExample() {
//    val store = createStore(todoReducer, thunk(CoroutineScope(Dispatchers.Default)))
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

