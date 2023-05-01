package app.dapk.st.state

import app.dapk.annotation.StateActions

@StateActions interface ComponentLifecycle {
    fun onVisible()
}