package app.dapk.internal

import app.dapk.state.Environment
import app.dapk.state.Execution
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ReducerBuilder
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
fun <S, A : Any> registerAction(builder: ReducerBuilder<S>, klass: KClass<A>, block: ExecutionRegistrar<S>.(S, A) -> Unit) {
    var collector: Execution<S>? = null
    val context = createContext(name = klass.simpleName!!) { collector = it }
    builder.register(klass) { payload ->
        block(context, builder.getState(), payload as A)
        collector
    }
}

private fun <S> createContext(name: String, collector: (Execution<S>) -> Unit) = object : ExecutionRegistrar<S> {
    override val name: String = name
    override fun register(execution: Execution<S>) = collector.invoke(execution)
}

data class UpdateExec<S>(private val update: Update<S>) : Execution<S> {
    override fun execute(state: S, environment: Environment) = update.update(state)
}
