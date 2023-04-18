package app.dapk.state

interface ObjectFactory<S1, S2, R> {
    fun R.getS1(): S1
    fun R.getS2(): S2
    fun build(s1: S1, s2: S2): R
}

fun <S1 : Any, S2 : Any, R> combineReducers(
    r1: ReducerFactory<S1>,
    r2: ReducerFactory<S2>,
    factory: ObjectFactory<S1, S2, R>,
) = object : ReducerFactory<R> {
    override fun create(scope: ReducerScope<R>, extensions: List<StoreExtension>): Reducer<R> {
        val fullState = scope.getState()
        val r1Reducer = with(factory) { r1.create(scope.downScope { fullState.getS1() }, extensions) }
        val r2Reducer = with(factory) { r2.create(scope.downScope { fullState.getS2() }, extensions) }
        return Reducer { factory.build(r1Reducer.reduce(it), r2Reducer.reduce(it)) }
    }

    override fun initialState(): R = factory.build(r1.initialState(), r2.initialState())
}

private fun <S, R> ReducerScope<S>.downScope(reader: () -> R) = object : ReducerScope<R> {
    override fun dispatch(action: Action) {
        this@downScope.dispatch(action)
    }

    override fun getState(): R = reader()
}