package app.dapk.state.plugin

import java.io.OutputStream

fun interface Writeable {
    fun writeTo(outputStream: OutputStream)
}