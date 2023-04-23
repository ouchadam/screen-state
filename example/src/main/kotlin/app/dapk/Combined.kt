package app.dapk

import app.dapk.gen.AllState
import app.dapk.gen.actions
import app.dapk.gen.allState
import app.dapk.gen.randomize
import app.dapk.gen.update
import app.dapk.gen.updateId
import app.dapk.gen.updateName
import app.dapk.state.CombinedState
import app.dapk.state.State
import app.dapk.state.createReducer
import app.dapk.state.outer

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


val combinedReducer = AllState
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
            actions.stateOne.updateId(allState.stateOne.id.toList().shuffled().toString())
            actions.stateTwo.updateName(allState.stateTwo.name.toList().shuffled().toString())
        }
    }
