package app.dapk

import app.dapk.gen.asyncState
import app.dapk.gen.observeChanges
import app.dapk.gen.update
import app.dapk.gen.updateContent
import app.dapk.state.State
import app.dapk.state.createReducer
import app.dapk.thunk.thunk
import kotlinx.coroutines.flow.onEach

private val database = Database()

@State(actions = [AsyncState.Actions::class])
data class AsyncState(
    val content: List<String>
) {
    interface Actions {
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
