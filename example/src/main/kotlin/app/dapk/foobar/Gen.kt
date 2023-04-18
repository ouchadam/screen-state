package app.dapk.foobar

import app.dapk.AllState
import app.dapk.StateOne
import app.dapk.StateTwo
import app.dapk.state.ObjectFactory

// generated under a separate package with same names
object AllState {
    fun factory() = object : ObjectFactory<StateOne, StateTwo, AllState> {
        override fun AllState.getS1() = stateOne
        override fun AllState.getS2() = stateTwo
        override fun build(s1: StateOne, s2: StateTwo) = AllState(s1, s2)
    }
}
