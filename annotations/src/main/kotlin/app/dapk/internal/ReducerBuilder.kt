package app.dapk.internal

import app.dapk.state.Execution
import app.dapk.state.Execution.*
import app.dapk.state.ExecutionCollector
import app.dapk.state.ReducerBuilder
import app.dapk.state.ThunkContext
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
fun <S, A : Any> registerAction(builder: ReducerBuilder<S>, klass: KClass<A>, block: ExecutionCollector<S>.(S, A) -> Unit) {
    var collector: Execution<S>? = null
    val context = createContext(defaultKey = klass.simpleName!!) { collector = it }
    builder.register(klass) { payload ->
        block(context, builder.getState(), payload as A)
        collector
    }
}

private fun <S> createContext(defaultKey: String, collector: (Execution<S>) -> Unit) = object : ExecutionCollector<S> {
    override fun register(update: Update<S>) {
        collector.invoke(UpdateExec(update))
    }

    override fun thunk(key: String?, block: suspend ThunkContext<S>.() -> Unit) {
        collector.invoke(ThunkExec(key ?: defaultKey, block))
    }
}
