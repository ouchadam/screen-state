package app.dapk

import app.dapk.gen.AllState
import app.dapk.gen.StateOneActions
import app.dapk.gen.StateTwoActions
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

@State(actions = [StateOne.Actions::class])
data class StateOne(
    val id: String
) {
    interface Actions {
        fun updateId(id: String)
    }
}

@State(actions = [StateTwo.Actions::class])
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
                    update {
                        stateOne(allState.stateOne.copy(id = allState.stateOne.id.toList().shuffled().toString()))
                        stateTwo(allState.stateTwo.copy(name = allState.stateTwo.name.toList().shuffled().toString()))
                    }
                }
            }

    val store = createStore(reducer)

    store.subscribe {
        println("result: $it")
    }

    store.run {
//        store.actions.stateOne
//
//        data class Actions(
//            val stateOne: app.dapk.StateOne.Actions,
//            val stateTwo: app.dapk.StateTwo.Actions,
//        )
//        val app.dapk.state.Store<app.dapk.AllState>.actions: AllState.Actions by app.dapk.internal.StoreProperty {
//            AllState.Actions(
//                it.stateOne,
//                it.stateTwo
//            )
//        }

        (store as app.dapk.state.Store<StateOne>).stateOne.updateId("id 1")

//        dispatch(StateOneActions.UpdateId("id 1"))
        dispatch(StateTwoActions.UpdateName("name 1"))
        dispatch(StateOneActions.UpdateId("id 2"))
        dispatch(StateTwoActions.UpdateName("name 2"))
        allState.randomize()
    }
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

