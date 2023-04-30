package app.dapk

import app.dapk.thunk.thunk
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

    private val runReducerTest = testReducer(thunk(blockingScope())) { asyncReducer }

    @Test
    fun `initial state`() = runReducerTest {
        assertInitialState(AsyncState(emptyList()))
    }

    @Test
    fun `observes changes`() = runReducerTest {
        reduce { asyncState.observeChanges() }

        assertOnlyDispatches(
            listOf(
                action { asyncState.updateContent(listOf("1", "0", "0", "0")) },
                action { asyncState.updateContent(listOf("1", "2", "0", "0")) },
                action { asyncState.updateContent(listOf("1", "2", "3", "0")) },
                action { asyncState.updateContent(listOf("1", "2", "3", "4")) },
            )
        )
    }
}

@OptIn(InternalCoroutinesApi::class)
private fun blockingScope() = CoroutineScope(object : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) = continuation.resume(Unit)
})
