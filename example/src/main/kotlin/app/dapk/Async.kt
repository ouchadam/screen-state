package app.dapk

import app.dapk.gen.asyncState
import app.dapk.gen.observeChanges
import app.dapk.gen.update
import app.dapk.gen.updateContent
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.state.createReducer
import app.dapk.thunk.thunk
import kotlinx.coroutines.flow.onEach

private val database = Database()

@State(actions = [AsyncState.Actions::class])
data class AsyncState(
    val content: List<String>
) {
    @StateActions interface Actions {
        fun observeChanges()
        fun updateContent(content: List<String>)
    }
}

val asyncReducer = createReducer(initialState = AsyncState(content = emptyList())) {
    observeChanges { _, _ ->
        thunk {
            database.observe()
                .onEach { asyncState.updateContent(it) }
                .launchInThunk()
        }
    }

    updateContent { _, payload ->
        update { content(payload.content) }
    }
}
