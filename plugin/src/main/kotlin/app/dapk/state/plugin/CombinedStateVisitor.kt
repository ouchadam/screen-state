package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.state.ObjectFactory
import app.dapk.state.ReducerFactory
import app.dapk.state.Store
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
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
    private val codeGenerator: CodeGenerator,
    private val resolver: Resolver,
    private val logger: KSPLogger,
    private val plugins: List<Plugin>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        when (classDeclaration.classKind) {
            ClassKind.CLASS -> {
                val parameters = classDeclaration.parseConstructor()
                logger.warn(parameters.map { it.name.getShortName() to it.type.toString() }
                    .toString())

                val className = classDeclaration.simpleName.asString()
                val file = codeGenerator.createNewFile(
                    dependencies = Dependencies(
                        false,
                        *resolver.getAllFiles().toList().toTypedArray()
                    ),
                    packageName = "app.dapk.gen",
                    fileName = "${className}CombinedGenerated"
                )

                file += "package app.dapk.gen\n"

                logger.warn("!!!!!!!!!! combined")

                val actionClasses = parameters.mapNotNull { param ->
                    (param.type.declaration as KSClassDeclaration).parseStateAnnotation()
                }

                generateCombinedObject(
                    classDeclaration,
                    parameters,
                    actionClasses,
                    resolver,
                    logger
                ).writeTo(file)
                generateActionExtensions(classDeclaration, parameters, actionClasses, resolver).writeTo(file)
                file.close()

                processStateLike(
                    codeGenerator,
                    resolver,
                    classDeclaration,
                    classDeclaration.parseCombinedStateAnnotation(),
                    logger,
                    plugins
                )
            }
            else -> {
                logger.error("Unexpected annotation class: ${classDeclaration.classKind}")
            }
        }
    }

}

private fun generateActionExtensions(
    classDeclaration: KSClassDeclaration,
    parameters: List<Prop>,
    actionClasses: List<AnnotationRep>,
    resolver: Resolver,
): Writeable {
    return if (parameters.isEmpty()) {
        Writeable { }
    } else {
        val domainType = classDeclaration.toClassName()
        val actionsType =
            ClassName.bestGuess("app.dapk.gen.${domainType.simpleName}").nestedClass("Actions")
        val prop = PropertySpec
            .builder("actions", actionsType)
            .receiver(Store::class.asTypeName().parameterizedBy(domainType))
            .delegate(
                CodeBlock.Builder()
                    .beginControlFlow("app.dapk.internal.StoreProperty")
                    .add(
                        """
                            |${actionsType.canonicalName}(
                            |${
                            parameters.joinToString(",\n") {
                                "(it as ${
                                    Store::class.asTypeName().parameterizedBy(it.type.toClassName())
                                }).${it.name.getShortName()}"
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
    classDeclaration: KSClassDeclaration,
    parameters: List<Prop>,
    actionClasses: List<AnnotationRep>,
    resolver: Resolver,
    logger: KSPLogger
): Writeable {
    val domainType = classDeclaration.toClassName()
    val helperObject = TypeSpec.objectBuilder(domainType)
        .addFunction(
            FunSpec.builder("fromReducers")
                .addParameters(
                    parameters.map {
                        ParameterSpec.builder(
                            it.name.getShortName(),
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
                        parameters.map { it.name.getShortName() }.joinToString(",")
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
                                            parameters.mapIndexed { index, _ ->
                                                "|  $index -> component${index + 1}()"
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
                domain.actions?.map {
                    Prop(resolver.getKSNameFromString(domain.domainClass.canonicalName), it.key)
                }
            }.takeIf { it.isNotEmpty() }?.flatten()

            actions?.let {
                addType(
                    createDataClass("Actions", actions).build()
                )
            } ?: this
        }
        .build()

    return Writeable { it += helperObject.toString() }
}
