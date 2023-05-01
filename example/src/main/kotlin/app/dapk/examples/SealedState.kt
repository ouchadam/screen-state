package app.dapk.examples

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.examples.Payload.*
import app.dapk.state.createReducer

@CombinedState
data class CombinedDirect(
    val payload: Payload
)

@State(actions = [Actions::class])
sealed interface Payload {
    object Content : Payload

    @StateActions interface Actions {
        fun load()
    }
}

val sealedStateReducer = CombineCombinedDirect.fromReducers(
    createReducer(Payload.Content) {
        load { _, _ ->
            println("sealed state action!")
        }
    }
)
