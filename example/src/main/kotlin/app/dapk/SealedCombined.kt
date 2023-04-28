package app.dapk

import app.dapk.gen.actions
import app.dapk.gen.randomize
import app.dapk.gen.update
import app.dapk.gen.updateContent
import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.state.createReducer
import app.dapk.state.outer

@CombinedState(actions = [SealedAllState.Actions::class])
sealed interface SealedAllState {
    @State
    object StateThree : SealedAllState
    @State(actions = [StateFour.Actions::class]) data class StateFour(val content: String): SealedAllState {
        @StateActions interface Actions {
            fun updateContent(content: String)
        }
    }

    @StateActions interface Actions {
        fun randomize()
    }
}

val sealedCombinedReducer = app.dapk.gen.SealedAllState
    .fromReducers(
        stateThree = createReducer(initialState = SealedAllState.StateThree),
        stateFour = createReducer(initialState = SealedAllState.StateFour(content = "")) {
            updateContent { _, updateContent -> update { content(updateContent.content) } }
        }
    )
    .outer {
        randomize { state, _ ->
            actions.stateFour.updateContent(state.stateFour.content.toList().shuffled().toString())
        }
    }
