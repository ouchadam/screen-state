package app.dapk.examples

import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.data.Database
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
    }
}

val asyncReducer = createReducer(initialState = AsyncState(content = emptyList())) {
    observeChanges { _, _ ->
        thunk {
            database.observe()
                .onEach { thunkUpdate { content(it) } }
                .launchInThunk()
        }
    }
}
