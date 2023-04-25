package app.dapk.thunk

import app.dapk.extension.Plugin
import app.dapk.state.plugin.AnnotationRep
import app.dapk.state.plugin.Writeable
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
            val receiver = ClassName("", "${representation.simpleName()}Updater")
            Writeable {
                it += FunSpec.builder("thunkUpdate")
                    .receiver(
                        ThunkExecutionContext::class.asTypeName()
                            .parameterizedBy(representation.domainClass)
                    )
                    .addParameter(
                        "block",
                        LambdaTypeName.get(receiver, emptyList(), Unit::class.asTypeName())
                    )
                    .addStatement("register(${receiver.simpleName}Impl().apply(block).collect())")
                    .build()
                    .toString()
            }
        }
    }
}