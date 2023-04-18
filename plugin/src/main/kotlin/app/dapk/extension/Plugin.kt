package app.dapk.extension

import app.dapk.state.plugin.AnnotationRep
import app.dapk.state.plugin.Writeable
import com.google.devtools.ksp.processing.KSPLogger

interface Plugin {
    fun run(logger: KSPLogger, representation: AnnotationRep): Writeable
}