package app.dapk.st.state

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
inline fun <reified S, E> ComponentActivity.state(
    noinline factory: () -> State<S, E>
): Lazy<State<S, E>> {
    val factoryPromise = object : Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return when (modelClass) {
                StateViewModel::class.java -> factory() as T
                else -> throw Error()
            }
        }
    }
    return KeyedViewModelLazy(
        key = S::class.java.canonicalName!!,
        StateViewModel::class,
        { viewModelStore },
        { factoryPromise }
    ) as Lazy<State<S, E>>
}

class KeyedViewModelLazy<VM : ViewModel> constructor(
    private val key: String,
    private val viewModelClass: KClass<VM>,
    private val storeProducer: () -> ViewModelStore,
    private val factoryProducer: () -> Factory,
) : Lazy<VM> {

    private var cached: VM? = null

    override val value: VM
        get() {
            val viewModel = cached
            return if (viewModel == null) {
                val factory = factoryProducer()
                val store = storeProducer()
                ViewModelProvider(
                    store,
                    factory,
                    CreationExtras.Empty
                ).get(key, viewModelClass.java).also {
                    cached = it
                }
            } else {
                viewModel
            }
        }

    override fun isInitialized(): Boolean = cached != null
}