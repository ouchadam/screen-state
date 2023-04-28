package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.internal.StoreProperty
import app.dapk.state.ObjectFactory
import app.dapk.state.ReducerFactory
import app.dapk.state.StoreScope
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
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
                val combinedAnnotation = classDeclaration.parseCombinedAnnotation()

                kspContext.createFile(packageName = classDeclaration.packageName.asString(), fileName = "${className}CombinedGenerated") {
                    val actionClasses = parameters.map { param ->
                        logger.warn("!!!! : ${param.type}")
                        (param.type.declaration as KSClassDeclaration).parseStateAnnotation()
                    }

                    val domainType = classDeclaration.toClassName()
                    val objectNamespace = "Combine${domainType.simpleName}"
                    listOf(
                        generateCombinedObject(objectNamespace, domainType, combinedAnnotation, actionClasses),
                        generateActionExtensions(
                            domainType,
                            ClassName(classDeclaration.packageName.asString(), objectNamespace),
                            combinedAnnotation,
                            actionClasses,
                        )
                    )
                }

                processStateLike(
                    kspContext,
                    classDeclaration,
                    combinedAnnotation.let {
                        it.annotationRep.copy(actions = it.annotationRep.actions?.plus(it.commonActions ?: emptyList()))
                    },
                    logger,
                    plugins
                )
            }

            ClassKind.INTERFACE -> {
                val sealedSubclasses = classDeclaration.getSealedSubclasses()
                val combinedAnnotation = classDeclaration.parseCombinedAnnotation()

                if (!sealedSubclasses.iterator().hasNext()) {
                    logger.error("Expected sealed interface with subclasses")
                } else {
                    val className = classDeclaration.simpleName.asString()

                    val proxy = ClassName(classDeclaration.packageName.asString(), "${className}Proxy")
                    val toList = sealedSubclasses.toList()
                    val actionClasses = toList.map { it.parseStateAnnotation() }

                    val parameters = toList.map {
                        Prop(it.simpleName, it.asStarProjectedType())
                    }

                    kspContext.createFile(
                        packageName = classDeclaration.packageName.asString(),
                        fileName = "${className}CombinedGenerated"
                    ) {
                        val objectNamespace = "Combine${className}"
                        listOf(
                            generateProxy(proxy, parameters),
                            generateCombinedObject(objectNamespace, proxy, combinedAnnotation, actionClasses),
                            generateActionExtensions(
                                proxy,
                                ClassName(classDeclaration.packageName.asString(), objectNamespace),
                                combinedAnnotation,
                                actionClasses,
                            )
                        )
                    }

                    processStateLike(
                        kspContext,
                        parameters,
                        combinedAnnotation.let {
                            it.annotationRep.copy(
                                domainClass = proxy,
                                domainName = proxy,
                                actions = it.annotationRep.actions?.plus(it.commonActions ?: emptyList())
                            )
                        },
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
    return Writeable { it += proxyClass.toString() }
}

private fun generateActionExtensions(
    stateType: ClassName,
    objectNamespace: ClassName,
    combinedRep: CombinedRep,
    parameters: List<AnnotationRep>,
): Writeable {
    return if (parameters.isEmpty()) {
        Writeable { }
    } else {
        val actionsType = objectNamespace.nestedClass("Actions")
        val prop = PropertySpec
            .builder("actions", actionsType)
            .addVisibility(combinedRep.annotationRep.visibility)
            .receiver(StoreScope::class.asTypeName().parameterizedBy(stateType))
            .delegate(
                CodeBlock.Builder()
                    .beginControlFlow(StoreProperty::class.qualifiedName.toString())
                    .add(
                        """
                            |${actionsType.canonicalName}(
                            |${
                            parameters.joinToString(",\n") {
                                "(it as ${
                                    StoreScope::class.asTypeName()
                                        .parameterizedBy(it.resolveClass())
                                }).${it.resolveSimpleName().decapitalize()}"
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

private fun generateCombinedObject(
    name: String,
    domainType: ClassName,
    combinedRep: CombinedRep,
    actionClasses: List<AnnotationRep>,
): Writeable {
    val helperObject = TypeSpec.objectBuilder(name)
        .addModifiers(combinedRep.annotationRep.visibilityModifier())
        .addFunction(
            FunSpec.builder("fromReducers")
                .addParameters(
                    actionClasses.map {
                        ParameterSpec.builder(
                            it.domainName.simpleName.decapitalize(),
                            ReducerFactory::class.asTypeName().parameterizedBy(it.resolveClass())
                        ).build()
                    }
                )
                .returns(
                    ReducerFactory::class.asTypeName().parameterizedBy(domainType)
                )
                .addCode(
                    """
                        return app.dapk.state.combineReducers(factory(), ${
                        actionClasses.joinToString(",") { it.domainName.simpleName.decapitalize() }
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
                                        actionClasses.mapIndexed { index, param -> "content[$index] as ${param.domainName.canonicalName}" }
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
                                        List(actionClasses.size) { index ->
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
                        domain.domainName.simpleName,
                        ClassName(
                            domain.domainName.packageName, "${domain.resolveSimpleName()}AllActions"
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
