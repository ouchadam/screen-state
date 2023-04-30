package test

import app.dapk.state.Action
import app.dapk.state.Reducer
import app.dapk.state.ReducerFactory
import app.dapk.state.StoreExtension
import app.dapk.state.StoreScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeEqualTo
import state.ExpectTest
import state.ExpectTestScope

fun interface ReducerTest<S> {
    operator fun invoke(block: suspend ReducerTestScope<S>.() -> Unit)
}

fun <S> testReducer(
    vararg extensions: StoreExtension.Factory<S>,
    factory: () -> ReducerFactory<S>
): ReducerTest<S> {
    val reducerFactory = factory()
    return ReducerTest { block -> runReducerTest(reducerFactory, extensions.toList(), block) }
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
}

fun <S, E> ReducerTestScope<S>.assertOnlyDispatches(vararg action: Action) {
    this.assertOnlyDispatches(action.toList())
}

fun <S> ReducerTestScope<S>.assertDispatches(vararg action: Action) {
    this.assertDispatches(action.toList())
}
