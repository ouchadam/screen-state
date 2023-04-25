package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.state.ObjectFactory
import app.dapk.state.ReducerFactory
import app.dapk.state.StoreScope
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName

internal class CombinedStateVisitor(
    private val kspContext: KspContext,
    private val logger: KSPLogger,
    private val plugins: List<Plugin>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        when (classDeclaration.classKind) {
            ClassKind.CLASS -> {
                val parameters = classDeclaration.parseConstructor()
                val className = classDeclaration.simpleName.asString()

                kspContext.createFile(fileName = "${className}CombinedGenerated") {
                    val actionClasses = parameters.map { param ->
                        (param.type.declaration as KSClassDeclaration).parseStateAnnotation()
                    }

                    val domainType = classDeclaration.toClassName()
                    listOf(
                        generateCombinedObject(domainType.simpleName, domainType, parameters, actionClasses),
                        generateActionExtensions(
                            domainType,
                            ClassName(PACKAGE, className),
                            parameters,
                        )
                    )
                }

                processStateLike(
                    kspContext,
                    classDeclaration,
                    classDeclaration.parseCombinedStateAnnotation(),
                    logger,
                    plugins
                )
            }

            ClassKind.INTERFACE -> {
                logger.warn("!!!!!!!")
                val sealedSubclasses = classDeclaration.getSealedSubclasses()

                if (!sealedSubclasses.iterator().hasNext()) {
                    logger.error("Expected sealed interface with subclasses")
                } else {
                    val className = classDeclaration.simpleName.asString()

                    val proxy = ClassName(PACKAGE, "${className}Proxy")
                    val toList = sealedSubclasses.toList()
                    val actionClasses = toList.map { it.parseStateAnnotation() }

                    val parameters = toList.map {
                        Prop(it.simpleName, it.asStarProjectedType())
                    }

                    kspContext.createFile(fileName = "${className}CombinedGenerated") {
                        listOf(
                            generateProxy(proxy, parameters),
                            generateCombinedObject(className, proxy, parameters, actionClasses),
                            generateActionExtensions(
                                proxy,
                                ClassName(PACKAGE, className),
                                parameters,
                            )
                        )
                    }

                    processStateLike(
                        kspContext,
                        parameters,
                        classDeclaration.parseCombinedStateAnnotation().copy(
                            domainClass = proxy
                        ),
                        logger,
                        plugins
                    )
                }
            }

            else -> {
                logger.error("Unexpected annotation class: ${classDeclaration.classKind}")
            }
        }
    }

}

private fun generateProxy(proxyName: ClassName, parameters: List<Prop>): Writeable {
    val proxyClass = createDataClass(proxyName.simpleName, parameters.map {
        ClassProperty(it.name.asString(), it.type.toClassName())
    }).build()
    return Writeable { it += proxyClass.toString()}
}

private fun generateActionExtensions(
    stateType: ClassName,
    objectNamespace: ClassName,
    parameters: List<Prop>,
): Writeable {
    return if (parameters.isEmpty()) {
        Writeable { }
    } else {
        val actionsType = objectNamespace.nestedClass("Actions")
        val prop = PropertySpec
            .builder("actions", actionsType)
            .receiver(StoreScope::class.asTypeName().parameterizedBy(stateType))
            .delegate(
                CodeBlock.Builder()
                    .beginControlFlow("app.dapk.internal.StoreProperty")
                    .add(
                        """
                            |${actionsType.canonicalName}(
                            |${
                            parameters.joinToString(",\n") {
                                "(it as ${
                                    StoreScope::class.asTypeName()
                                        .parameterizedBy(it.type.toClassName())
                                }).${it.simpleName().decapitalize()}"
                            }
                        }
                           |)
                        """.trimMargin()
                    )
                    .endControlFlow()
                    .build()
            ).build()
        Writeable { it += prop.toString() }
    }
}

private fun Prop.simpleName(): String {
    return this.type.declaration.parentDeclaration?.let {
        "${it.simpleName.asString()}${this.name.asString()}"
    } ?: this.name.asString()
}

private fun generateCombinedObject(
    name: String,
    domainType: ClassName,
    parameters: List<Prop>,
    actionClasses: List<AnnotationRep>,
): Writeable {
    val helperObject = TypeSpec.objectBuilder(name)
        .addFunction(
            FunSpec.builder("fromReducers")
                .addParameters(
                    parameters.map {
                        ParameterSpec.builder(
                            it.name.getShortName().decapitalize(),
                            ReducerFactory::class.asTypeName()
                                .parameterizedBy(it.type.toClassName())
                        ).build()
                    }
                )
                .returns(
                    ReducerFactory::class.asTypeName()
                        .parameterizedBy(domainType)
                )
                .addCode(
                    """
                        return app.dapk.state.combineReducers(factory(), ${
                        parameters.joinToString(",") { it.name.getShortName().decapitalize() }
                    })
                    """.trimIndent()
                )
                .build()
        )
        .addFunction(
            FunSpec.builder("factory")
                .addModifiers(KModifier.PRIVATE)
                .returns(ObjectFactory::class.asTypeName().parameterizedBy(domainType))
                .addStatement("return " +
                        TypeSpec.anonymousClassBuilder()
                            .addSuperinterface(
                                ObjectFactory::class.asTypeName().parameterizedBy(domainType)
                            )
                            .addFunction(
                                FunSpec.builder("construct")
                                    .addModifiers(KModifier.OVERRIDE)
                                    .addParameter(
                                        "content",
                                        List::class.asTypeName()
                                            .parameterizedBy(Any::class.asTypeName())
                                    )
                                    .addCode(
                                        """
                                    |return ${domainType.canonicalName}(
                                    |  ${
                                            parameters.mapIndexed { index, param -> "content[$index] as ${param.type.toClassName().canonicalName}" }
                                                .joinToString(",")
                                        }
                                    |)
                                    """.trimMargin()
                                    )
                                    .returns(domainType)
                                    .build()

                            )
                            .addFunction(
                                FunSpec.builder("destruct")
                                    .addModifiers(KModifier.OVERRIDE)
                                    .addTypeVariable(TypeVariableName("T"))
                                    .returns(TypeVariableName("T"))
                                    .receiver(domainType)
                                    .addParameter("index", Int::class)
                                    .addCode(
                                        """
                                        |return when(index) {
                                        ${
                                            List(parameters.size) { index ->
                                                "$index -> component${index + 1}()"
                                            }.joinToString("\n")
                                        }
                                        |  else -> error("Unexpected index: ${'$'}index")
                                        |} as T
                                    """.trimMargin()
                                    )
                                    .build()
                            )
                            .build()
                            .toString()
                )
                .build()
        )
        .run {
            val actions = actionClasses.mapNotNull { domain ->
                domain.actions?.let {
                    ClassProperty(
                        domain.domainClass.simpleName,
                        ClassName(
                            PACKAGE, "${domain.simpleName()}AllActions"
                        )
                    )
                }
            }.takeIf { it.isNotEmpty() }

            actions?.let {
                addType(
                    createDataClass("Actions", actions).build()
                )
            } ?: this
        }
        .build()

    return Writeable { it += helperObject.toString() }
}
