package test

import app.dapk.internal.UpdateAction
import app.dapk.state.Action
import app.dapk.state.Reducer
import app.dapk.state.ReducerFactory
import app.dapk.state.StoreExtension
import app.dapk.state.StoreScope
import app.dapk.thunk.thunk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import state.ExpectTest
import state.ExpectTestScope
import java.lang.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

fun interface ReducerTest<S> {
    operator fun invoke(block: suspend ReducerTestScope<S>.() -> Unit)
}

fun <S> testReducer(
    extensions: List<StoreExtension.Factory<S>> = listOf(thunk(blockingScope())),
    factory: () -> ReducerFactory<S>
): ReducerTest<S> {
    val reducerFactory = factory()
    return ReducerTest { block -> runReducerTest(reducerFactory, extensions, block) }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <S> runReducerTest(
    reducerFactory: ReducerFactory<S>,
    extensions: List<StoreExtension.Factory<S>>,
    block: suspend ReducerTestScope<S>.() -> Unit
) {
    runTest(context = UnconfinedTestDispatcher()) {
        val expectTestScope = ExpectTest(coroutineContext)
        block(ReducerTestScope(reducerFactory, expectTestScope, extensions))
        expectTestScope.verifyExpects()
    }
}

class ReducerTestScope<S>(
    private val reducerFactory: ReducerFactory<S>,
    private val expectTestScope: ExpectTestScope,
    extensions: List<StoreExtension.Factory<S>>
) : ExpectTestScope by expectTestScope {

    private var manualState: S? = null
    private var capturedResult: S? = null

    private val actionCaptures = mutableListOf<Action>()

    private val reducerScope = object : StoreScope<S> {
        override fun dispatch(action: Action) {
            actionCaptures.add(action)
        }

        override fun getState() = manualState ?: reducerFactory.initialState()
    }

    private val reducer: Reducer<S> = reducerFactory.create(
        reducerScope,
        extensions.map { it.create(reducerScope) }
    )

    fun reduce(actionProvider: StoreScope<S>.() -> Unit): S {
        val actionToExecute: Action = action(actionProvider)
        return reduce(actionToExecute)
    }

    fun reduce(action: Action) = reducer.reduce(reducerScope.getState(), action).also {
        capturedResult = it
    }

    fun setState(state: S) {
        manualState = state
    }

    fun setState(block: (S) -> S) {
        setState(block(reducerScope.getState()))
    }

    fun assertInitialState(expected: S) {
        reducerFactory.initialState() shouldBeEqualTo expected
    }

    fun assertOnlyStateChange(expected: S) {
        assertStateChange(expected)
        assertNoDispatches()
    }

    fun assertOnlyStateChange(block: (S) -> S) {
        val expected = block(reducerScope.getState())
        assertStateChange(expected)
        assertNoDispatches()
    }

    fun assertStateChange(expected: S) {
        capturedResult shouldBeEqualTo expected
    }

    fun assertStateUpdate(block: (S) -> S) {
        val expected = block(reducerScope.getState())
        assertStateChange(expected)
    }

    fun assertDispatches(expected: List<Action>) {
        assertEquals(expected, actionCaptures)
    }

    fun assertNoDispatches() {
        assertEquals(emptyList(), actionCaptures)
    }

    fun assertNoStateChange() {
        assertEquals(reducerScope.getState(), capturedResult)
    }

    fun assertOnlyDispatches(expected: List<Action>) {
        assertDispatches(expected)
        assertNoStateChange()
    }

    fun assertNoChanges() {
        assertNoStateChange()
        assertNoDispatches()
    }

    fun action(block: StoreScope<S>.() -> Unit): Action {
        var actionToExecute: Action? = null
        val collectingScope = object : StoreScope<S> {
            override fun dispatch(action: Action) {
                when (actionToExecute) {
                    null -> actionToExecute = action
                    else -> error("Can only be called once")
                }
            }

            override fun getState() = error("should not be used!")
        }
        block(collectingScope)
        return actionToExecute!!
    }

    fun assertUpdateActions(vararg expected: (S) -> S) {
        actionCaptures.filterIsInstance<UpdateAction<S>>().resolve() shouldBeEqualTo expected.toList().accumulateState()
    }

    private fun List<(S) -> S>.accumulateState(): List<S> {
        return this.fold(reducerScope.getState() to mutableListOf<S>()) { acc, current ->
            current.invoke(acc.first).let {
                it to (acc.second.plus(it).toMutableList())
            }
        }.second
    }

    @Suppress("SuspiciousCallableReferenceInLambda")
    private fun List<UpdateAction<S>>.resolve(): List<S> {
        return this.map { it.update::update }.accumulateState()
    }
}

fun <S> ReducerTestScope<S>.assertOnlyDispatches(vararg action: Action) {
    this.assertOnlyDispatches(action.toList())
}

fun <S> ReducerTestScope<S>.assertDispatches(vararg action: Action) {
    this.assertDispatches(action.toList())
}

@OptIn(InternalCoroutinesApi::class)
private fun blockingScope() = CoroutineScope(object : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) = continuation.resume(Unit)
})
