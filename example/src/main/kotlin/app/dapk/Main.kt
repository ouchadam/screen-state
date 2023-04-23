package app.dapk

import app.dapk.gen.AllState
import app.dapk.gen.StateOneActions
import app.dapk.gen.StateTwoActions
import app.dapk.gen.actions
import app.dapk.gen.allState
import app.dapk.gen.randomize
import app.dapk.gen.stateOne
import app.dapk.gen.todoState
import app.dapk.gen.update
import app.dapk.gen.updateId
import app.dapk.gen.updateName
import app.dapk.state.CombinedState
import app.dapk.state.State
import app.dapk.state.createReducer
import app.dapk.state.createStore
import app.dapk.state.outer

val database = Database()

fun main() {
    combineExample()
}

interface CommonActions {
    fun destroy()
}

@State(actions = [StateOne.Actions::class, CommonActions::class])
data class StateOne(
    val id: String
) {
    interface Actions {
        fun updateId(id: String)
    }
}

@State(actions = [StateTwo.Actions::class, CommonActions::class])
data class StateTwo(
    val name: String
) {
    interface Actions {
        fun updateName(name: String)
    }
}

@CombinedState(actions = [app.dapk.AllState.Actions::class])
data class AllState(
    val stateOne: StateOne,
    val stateTwo: StateTwo,
) {
    interface Actions {
        fun randomize()
    }
}

private fun combineExample() {
    val stateOneReducer = createReducer(initialState = StateOne(id = "")) {
        updateId { _, updateId -> update { id(updateId.id) } }
    }

    val stateTwoReducer = createReducer(initialState = StateTwo(name = "")) {
        updateName { _, updateName -> update { name(updateName.name) } }
    }

    val reducer =
        AllState
            .fromReducers(stateOneReducer, stateTwoReducer)
            .outer {
                randomize { allState, _ ->
                    actions.stateOne.updateId(allState.stateOne.id.toList().shuffled().toString())
                    actions.stateTwo.updateName(allState.stateTwo.name.toList().shuffled().toString())
                }
            }

    val store = createStore(reducer)

    store.subscribe {
        println("result: $it")
    }

    store.actions.run {
        stateOne.updateId("id 1")
        stateTwo.updateName("name 1")
        stateOne.updateId("id 2")
        stateTwo.updateName("name 2")
    }
    store.allState.randomize()

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

