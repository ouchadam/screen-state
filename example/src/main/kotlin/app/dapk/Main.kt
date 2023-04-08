package app.dapk

import app.dapk.MyOtherState.*
import app.dapk.MyState.*
import app.dapk.gen.refresh
import app.dapk.gen.thunkUpdate
import app.dapk.gen.update
import app.dapk.gen.updateName
import app.dapk.state.State
import app.dapk.state.createReducer
import app.dapk.state.createStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach

@State(actions = [MyStateAction::class])
data class MyState(
    val name: String,
    val age: Int,
    val list: List<String>,
    val nullable: String?,
    val isLoading: Boolean,
    val lastRefresh: String
) {
    @Suppress("unused")
    interface MyStateAction {
        fun updateName(name: String)
        fun refresh()
    }
}

@State(actions = [MyStateAction2::class])
data class MyOtherState(
    val name: String,
    val lastRefresh: String,
) {
    @Suppress("unused")
    interface MyStateAction2 {
        fun updateName(name: String)
        fun refresh()
    }
}

fun main() {
    val database = Database()

    val reducer = createReducer(initialState = MyState("", 0, emptyList(), null, false, "never")) {

        updateName { _, payload ->
            update { name(payload.name) }
        }

        refresh { _, _ ->
            thunk {
                thunkUpdate { lastRefresh(System.currentTimeMillis().toString()) }

                database.observe()
                    .onEach { thunkUpdate { list(it) } }
                    .launchInThunk()
            }
        }
    }

    val store = createStore(reducer, CoroutineScope(Dispatchers.Default))

    store.subscribe {
        println("result: $it")
    }

    store.refresh()

    while (true) {
        Thread.sleep(10)
    }
}

