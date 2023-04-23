package app.dapk.state

interface ObjectFactory<R: Any> {
    fun <T> R.destruct(index: Int): T
    fun construct(content: List<Any>): R
}

@Suppress("UNCHECKED_CAST")
fun <R: Any> combineReducers(
    factory: ObjectFactory<R>,
    vararg factories: ReducerFactory<out Any>,
) = object : ReducerFactory<R> {
    override fun create(scope: StoreScope<R>, extensions: List<StoreExtension>): Reducer<R> {
        val fullState = scope.getState()
        val reducers = with(factory) {
            factories.mapIndexed { index, reducerFactory ->
                val reducer = (reducerFactory as ReducerFactory<Any>).create(
                    scope.downScope { fullState.destruct(index) },
                    extensions
                )
                Reducer<Any> { state, action -> reducer.reduce((state as R).destruct(index), action) }
            }
        }
        return Reducer { state, action -> factory.construct(reducers.map { it.reduce(state, action) }) }
    }

    override fun initialState(): R = factory.construct(factories.map { it.initialState() })
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
            return Reducer { state, action -> parentScope.reduce(childScope.reduce(state, action), action) }
        }
        override fun initialState() = outer.initialState()
    }
}
