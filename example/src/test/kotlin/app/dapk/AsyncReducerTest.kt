package app.dapk

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import org.junit.*
import test.testReducer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

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
