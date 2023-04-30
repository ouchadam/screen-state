package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.internal.StoreProperty
import app.dapk.state.CombinedReducerFactory
import app.dapk.state.ObjectFactory
import app.dapk.state.ReducerFactory
import app.dapk.state.StoreScope
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
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
import com.squareup.kotlinpoet.ksp.toTypeName

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

                kspContext.createFile(packageName = combinedAnnotation.annotationRep.packageName, fileName = "${className}CombinedGenerated") {
                    val actionClasses = parameters.mapNotNull { param ->
                        logger.warn("!!!! : ${param.type}")
                        when (val declaration = param.type.declaration) {
                            is KSClassDeclaration -> declaration.parseStateAnnotation()
                            else -> {
                                logger.warn("Unknown type: $declaration")
                                null
                            }
                        }
                    }

                    val domainType = classDeclaration.toClassName()
                    val objectNamespace = "Combine${domainType.simpleName}"
                    listOf(
                        generateCombinedObject(objectNamespace, combinedAnnotation, actionClasses, parameters),
                        generateActionExtensions(
                            ClassName(combinedAnnotation.annotationRep.packageName, objectNamespace),
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

                logger.warn("!!! annotation: ${classDeclaration.parentDeclaration}")

                if (!sealedSubclasses.iterator().hasNext()) {
                    logger.error("Expected sealed interface with subclasses")
                } else {
                    val className = classDeclaration.simpleName.asString()

                    val proxy = ClassName(classDeclaration.packageName.asString(), "${className}Proxy")
                    val toList = sealedSubclasses.toList()
                    val actionClasses = toList.mapNotNull { runCatching { it.parseStateAnnotation() }.getOrNull() }

                    val parameters = toList.map {
                        Prop(it.simpleName, it.asStarProjectedType())
                    }


                    val proxyCombined = combinedAnnotation.let {
                        it.copy(
                            it.annotationRep.copy(
                                domainClass = proxy,
                                domainName = proxy,
                                actions = it.annotationRep.actions?.plus(it.commonActions ?: emptyList())
                            )
                        )
                    }


                    kspContext.createFile(
                        packageName = combinedAnnotation.annotationRep.packageName,
                        fileName = "${className}CombinedGenerated"
                    ) {
                        val objectNamespace = "Combine${className}"

                        listOf(
                            generateProxy(proxy, parameters),
                            generateCombinedObject(objectNamespace, proxyCombined, actionClasses, parameters),
                            generateActionExtensions(
                                ClassName(classDeclaration.packageName.asString(), objectNamespace),
                                proxyCombined,
                                actionClasses,
                            )
                        )
                    }

                    processStateLike(
                        kspContext,
                        parameters,
                        proxyCombined.annotationRep,
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
            .addVisibility(combinedRep.annotationRep)
            .receiver(combinedRep.annotationRep.asParameterOf(StoreScope::class, starProjected = true))
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
                                        .parameterizedBy(it.resolveClass(starProjected = true))
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
    combinedRep: CombinedRep,
    actionClasses: List<AnnotationRep>,
    parameters: List<Prop>,
): Writeable {
    val helperObject = combinedRep.createObject(name)
        .addFunction(
            combinedRep.createFunction("fromReducers", inheritType = true)
                .addParameters(
                    parameters.map {
                        ParameterSpec.builder(
                            it.name.getShortName().decapitalize(),
                            ReducerFactory::class.asTypeName().parameterizedBy(combinedRep.annotationRep.resolveType(it.type))
                        ).build()
                    }
                )
                .returns(
                    combinedRep.annotationRep.asParameterOf(CombinedReducerFactory::class)
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
            combinedRep.createFunction("factory", inheritType = true)
                .addModifiers(KModifier.PRIVATE)
                .returns(combinedRep.annotationRep.asParameterOf(ObjectFactory::class))
                .addStatement("return " +
                    TypeSpec.anonymousClassBuilder()
                        .addSuperinterface(
                            combinedRep.annotationRep.asParameterOf(ObjectFactory::class)
                        )
                        .addFunction(
                            combinedRep.createFunction("construct")
                                .addModifiers(KModifier.OVERRIDE)
                                .addParameter(
                                    "content",
                                    List::class.asTypeName()
                                        .parameterizedBy(Any::class.asTypeName())
                                )
                                .addCode(
                                    """
                                    |return ${combinedRep.annotationRep.qualifiedName}(
                                    |  ${
                                        parameters.mapIndexed { index, param -> "content[$index] as ${param.type.toQualifiedName()}" }
                                            .joinToString(",")
                                    }
                                    |)
                                    """.trimMargin()
                                )
                                .returns(combinedRep.annotationRep.domainClass)
                                .build()

                        )
                        .addFunction(
                            FunSpec.builder("destruct")
                                .addModifiers(KModifier.OVERRIDE)
                                .addTypeVariable(TypeVariableName("T"))
                                .returns(TypeVariableName("T"))
                                .receiver(combinedRep.annotationRep.domainClass)
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
                        domain.shortName(),
                        ClassName(
                            domain.packageName, "${domain.resolveSimpleName()}AllActions"
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
