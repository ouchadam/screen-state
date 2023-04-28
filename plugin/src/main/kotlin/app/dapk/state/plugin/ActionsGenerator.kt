package app.dapk.state.plugin

import app.dapk.state.Action
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toTypeName

fun generateActions(annotation: AnnotationRep): List<Writeable> {
    val interfaceName = ClassName(PACKAGE, "${annotation.simpleName()}Actions")
    val generatedActionsInterface = TypeSpec.interfaceBuilder(interfaceName)
        .addModifiers(SEALED)
        .addSuperinterface(Action::class)

    annotation.actions?.values?.flatten()?.map {
        val name = it.name.capitalize()
        when {
            it.arguments.isEmpty() -> TypeSpec.objectBuilder(name)
            else -> TypeSpec.classBuilder(name)
                .addModifiers(DATA)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameters(
                            it.arguments.map {
                                ParameterSpec.builder(
                                    it.name?.getShortName()?.decapitalize() ?: "value",
                                    it.type.toTypeName()
                                ).build()
                            }
                        ).build()
                )
                .addProperties(
                    it.arguments.map {
                        val propName = it.name?.getShortName()?.decapitalize() ?: "value"
                        PropertySpec.builder(propName, it.type.toTypeName())
                            .initializer(propName)
                            .build()
                    }
                )
        }.addSuperinterface(interfaceName).build()
    }?.forEach {
        generatedActionsInterface.addType(it)
    }

    return listOf(
        Writeable { it += generatedActionsInterface.build().toString() }
    )
}