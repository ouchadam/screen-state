package app.dapk.thunk

import app.dapk.state.Environment
import app.dapk.state.Execution
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ThunkContext
import kotlinx.coroutines.launch

fun <S> ExecutionRegistrar<S>.thunk(key: String? = null, block: suspend ThunkContext<S>.() -> Unit) {
    register(ThunkExec(key ?: name, block))
}

private data class ThunkExec<S>(private val key: String, private val value: suspend ThunkContext<S>.() -> Unit) : Execution<S> {

    @Suppress("UNCHECKED_CAST")
    override fun execute(state: S, environment: Environment): S {
        return state.also {
            environment.register(key, environment.coroutineScope.launch {
                val context = (environment.dynamic["thunk"] as ThunkContext<S>)
                value.invoke(context)
            })
        }
    }
}