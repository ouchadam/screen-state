package app.dapk.example

import app.dapk.examples.AsyncState
import app.dapk.examples.asyncReducer
import app.dapk.examples.asyncState
import org.junit.*
import test.testReducer

class AsyncReducerTest {

    private val runReducerTest = testReducer { asyncReducer }

    @Test
    fun `initial state`() = runReducerTest {
        assertInitialState(AsyncState(emptyList()))
    }

    @Test
    fun `observes changes`() = runReducerTest {
        reduce { asyncState.observeChanges() }

        assertNoStateChange()
        assertUpdateActions(
            { it.copy(content = listOf("1", "0", "0", "0")) },
            { it.copy(content = listOf("1", "2", "0", "0")) },
            { it.copy(content = listOf("1", "2", "3", "0")) },
            { it.copy(content = listOf("1", "2", "3", "4")) },
        )
    }
}