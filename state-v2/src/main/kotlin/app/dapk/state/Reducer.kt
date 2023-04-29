package app.dapk.state

interface ObjectFactory<R: Any> {
    fun <T> R.destruct(index: Int): T
    fun construct(content: List<Any>): R
}

@Suppress("UNCHECKED_CAST")
fun <R: Any> combineReducers(
    factory: ObjectFactory<R>,
    vararg factories: ReducerFactory<*>,
) = object : CombinedReducerFactory<R> {

    private var interceptor: (R, Any, Action) -> Boolean = { _, _, _ -> false }

    override fun create(scope: StoreScope<R>, extensions: List<StoreExtension>): Reducer<R> {
        val fullState = scope.getState()
        val reducers = with(factory) {
            factories.mapIndexed { index, reducerFactory ->
                val reducer = (reducerFactory as ReducerFactory<Any>).create(
                    scope.downScope { fullState.destruct(index) },
                    extensions
                )
                Reducer<Any> { state, action ->
                    val subState = (state as R).destruct<Any>(index)
                    if (interceptor.invoke(state, subState, action)) {
                        subState
                    } else {
                        reducer.reduce(subState, action)
                    }
                }
            }
        }
        return Reducer { state, action -> factory.construct(reducers.map { it.reduce(state, action) }) }
    }

    override fun intercept(interceptor: (R, Any, Action) -> Boolean): CombinedReducerFactory<R> {
        this.interceptor = interceptor
        return this
    }

    override fun initialState(): R = factory.construct(factories.map { it.initialState() as Any })
}

private fun <S, R> StoreScope<S>.downScope(reader: () -> R) = object : StoreScope<R> {
    override fun dispatch(action: Action) = this@downScope.dispatch(action)
    override fun getState(): R = reader()
}

fun <S: Any> ReducerFactory<S>.outer(builder: ReducerBuilder<S>.() -> Unit): ReducerFactory<S> {
    val outer = createReducer(this.initialState(), builder)
    return object : ReducerFactory<S> {
        override fun create(scope: StoreScope<S>, extensions: List<StoreExtension>): Reducer<S> {
            val parentScope = outer.create(scope, extensions)
            val childScope = this@outer.create(scope, extensions)
            return Reducer { state, action ->
                parentScope.reduce(childScope.reduce(state, action), action)
            }
        }
        override fun initialState() = outer.initialState()
    }
}

