package app.dapk.examples

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.examples.AllState.*
import app.dapk.state.createReducer
import app.dapk.state.outer

@StateActions interface CommonActions {
    fun destroy()
}

@State(actions = [StateOne.Actions::class, CommonActions::class])
data class StateOne(
    val id: String
) {
    @StateActions interface Actions {
        fun updateId(id: String)
    }
}

@State(actions = [StateTwo.Actions::class, CommonActions::class])
data class StateTwo(
    val name: String
) {
    @StateActions interface Actions {
        fun updateName(name: String)
    }
}

@CombinedState(actions = [Actions::class])
data class AllState(
    val stateOne: StateOne,
    val stateTwo: StateTwo,
) {
    @StateActions interface Actions {
        fun randomize()
    }
}

val combinedReducer = CombineAllState
    .fromReducers(
        stateOne = createReducer(initialState = StateOne(id = "")) {
            updateId { _, updateId -> update { id(updateId.id) } }
        },
        stateTwo = createReducer(initialState = StateTwo(name = "")) {
            updateName { _, updateName -> update { name(updateName.name) } }
        }
    )
    .outer {
        randomize { allState, _ ->
            actions.stateOne.updateId(getState().stateOne.id.toList().shuffled().toString())
            actions.stateTwo.updateName(getState().stateTwo.name.toList().shuffled().toString())
        }
    }
