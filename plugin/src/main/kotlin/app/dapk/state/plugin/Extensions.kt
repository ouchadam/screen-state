package app.dapk.state.plugin

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.io.OutputStream

fun KSClassDeclaration.parseStateAnnotation(): AnnotationRep {
    val annotation = this.annotations.first {
        it.shortName.asString() == "State"
    }
    val domainType = this.toClassName()
    return annotation.arguments.fold(AnnotationRep(domainType, null)) { acc, curr ->
        when (curr.name?.getShortName()) {
            "actions" -> {
                acc.copy(actions = (curr.value as ArrayList<KSType>).toList().toActionFunctions())
            }

            else -> acc
        }
    }
}

fun KSClassDeclaration.parseCombinedStateAnnotation(): AnnotationRep {
    val annotation = this.annotations.first {
        it.shortName.asString() == "CombinedState"
    }
    val domainType = this.toClassName()
    return annotation.arguments.fold(AnnotationRep(domainType, null)) { acc, curr ->
        when (curr.name?.getShortName()) {
            "actions" -> {
                acc.copy(actions = (curr.value as ArrayList<KSType>).toList().toActionFunctions())
            }

            else -> acc
        }
    }
}

private fun List<KSType>.toActionFunctions() = this.associateWith {
    val declaration = it.declaration as KSClassDeclaration
    declaration.getDeclaredFunctions()
        .map { ActionFunction(it.simpleName.getShortName(), it.parameters) }
        .toList()
}

fun KSClassDeclaration.parseConstructor(): List<Prop> {
    return this.primaryConstructor?.parameters?.mapNotNull {
        it.takeIf { it.name != null }?.let {
            val resolvedType = it.type.resolve()
            Prop(it.name!!, resolvedType)
        }
    }.orEmpty()
}

fun createDataClass(name: String, properties: List<Prop>) = TypeSpec.classBuilder(name)
    .addModifiers(KModifier.DATA)
    .primaryConstructor(
        FunSpec.constructorBuilder()
            .addParameters(
                properties.map {
                    ParameterSpec.builder(
                        it.name.getShortName().decapitalize(),
                        it.type.toTypeName()
                    ).build()
                }
            ).build()
    )
    .addProperties(
        properties.map {
            val propName = it.name.getShortName().decapitalize()
            PropertySpec.builder(propName, it.type.toTypeName())
                .initializer(propName)
                .build()
        }
    )

operator fun OutputStream.plusAssign(str: String) {
    this.write(str.toByteArray())
}