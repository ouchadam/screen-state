package app.dapk.examples

import app.dapk.annotation.State
import app.dapk.state.createReducer

@State
@JvmInline
value class IdContainer(val value: Id) {

}

sealed interface Id {
    object First: Id
    object Second: Id
}


val valueClassReducer = createReducer(IdContainer(Id.First))