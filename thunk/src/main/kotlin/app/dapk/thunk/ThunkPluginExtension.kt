package app.dapk.thunk

import app.dapk.extension.Plugin
import app.dapk.state.plugin.AnnotationRep
import app.dapk.state.plugin.Writeable
import app.dapk.state.plugin.createFunction
import app.dapk.state.plugin.plusAssign
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.asTypeName

class ThunkPluginExtension : Plugin {

    override fun run(logger: KSPLogger, representation: AnnotationRep): Writeable {
        logger.warn("hello from extension")
        return if (representation.isObject) {
            Writeable {  }
        } else {
            val receiver = representation.createTypeName("${representation.simpleName()}Updater")
            val receiverImpl = representation.createTypeName("${representation.simpleName()}UpdaterImpl")
            Writeable {
                it += representation.createFunction("thunkUpdate")
                    .receiver(representation.asParameterOf(ThunkExecutionContext::class))
                    .addParameter(
                        "block",
                        LambdaTypeName.get(receiver, emptyList(), Unit::class.asTypeName())
                    )
                    .addStatement("register(${receiverImpl}().apply(block).collect())")
                    .build()
                    .toString()
            }
        }
    }
}