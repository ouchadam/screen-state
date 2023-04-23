package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.internal.Update
import app.dapk.state.Action
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ObjectFactory
import app.dapk.state.ReducerBuilder
import app.dapk.state.ReducerFactory
import app.dapk.state.Store
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.io.OutputStream
import java.util.ServiceLoader

class StateProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val plugins =
        ServiceLoader.load(Plugin::class.java, StateProcessor::class.java.classLoader).toList()


    override fun process(resolver: Resolver): List<KSAnnotated> {
        return processStateAnnotation(resolver, plugins) + processCombinedStateAnnotation(resolver, plugins)
    }

    private fun processCombinedStateAnnotation(resolver: Resolver, plugins: List<Plugin>): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("app.dapk.state.CombinedState")
            .filterIsInstance<KSClassDeclaration>()

        val isEmpty = !symbols.iterator().hasNext()
        return if (isEmpty) {
            emptyList()
        } else {
            symbols.forEach {
                it.accept(
                    CombinedStateVisitor(codeGenerator, resolver, logger, plugins),
                    Unit
                )
            }
            symbols.filterNot { it.validate() }.toList()
        }
    }

    internal class CombinedStateVisitor(
        private val codeGenerator: CodeGenerator,
        private val resolver: Resolver,
        private val logger: KSPLogger,
        private val plugins: List<Plugin>,
    ) : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            when (classDeclaration.classKind) {
                CLASS -> {
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
                    generateCombinedObject(classDeclaration, parameters, logger).writeTo(file)
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

    private fun processStateAnnotation(resolver: Resolver, plugins: List<Plugin>): List<KSAnnotated> {
        val symbols = resolver
            .getSymbolsWithAnnotation("app.dapk.state.State")
            .filterIsInstance<KSClassDeclaration>()

        val isEmpty = !symbols.iterator().hasNext()

        return if (isEmpty) {
            emptyList()
        } else {

            // Make sure to associate the generated file with sources to keep/maintain it across incremental builds.
            // Learn more about incremental processing in KSP from the official docs:
            // https://kotlinlang.org/docs/ksp-incremental.html
            symbols.forEach {
                it.accept(
                    StateVisitor(codeGenerator, resolver, logger, plugins),
                    Unit
                )
            }

            return symbols.filterNot { it.validate() }.toList()
        }
    }
}

private fun generateCombinedObject(
    classDeclaration: KSClassDeclaration,
    parameters: List<Prop>,
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
                .addModifiers(PRIVATE)
                .returns(ObjectFactory::class.asTypeName().parameterizedBy(domainType))
                .addStatement("return " +
                        TypeSpec.anonymousClassBuilder()
                            .addSuperinterface(
                                ObjectFactory::class.asTypeName().parameterizedBy(domainType)
                            )
                            .addFunction(
                                FunSpec.builder("construct")
                                    .addModifiers(OVERRIDE)
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
                                    .addModifiers(OVERRIDE)
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
        .build()

    return Writeable { it += helperObject.toString() }
}

operator fun OutputStream.plusAssign(str: String) {
    this.write(str.toByteArray())
}

data class Prop(
    val name: KSName,
    val type: KSType,
)

internal class StateVisitor(
    private val codeGenerator: CodeGenerator,
    private val resolver: Resolver,
    private val logger: KSPLogger,
    private val plugins: List<Plugin>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        when (classDeclaration.classKind) {
            CLASS -> {
                processStateLike(
                    codeGenerator,
                    resolver,
                    classDeclaration,
                    classDeclaration.parseStateAnnotation(),
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

private fun processStateLike(
    codeGenerator: CodeGenerator,
    resolver: Resolver,
    classDeclaration: KSClassDeclaration,
    stateLike: AnnotationRep,
    logger: KSPLogger,
    plugins: List<Plugin>
) {
    val className = classDeclaration.simpleName.asString()
    val file = codeGenerator.createNewFile(
        dependencies = Dependencies(
            false,
            *resolver.getAllFiles().toList().toTypedArray()
        ),
        packageName = "app.dapk.gen",
        fileName = "${className}Generated"
    )

    file += "package app.dapk.gen\n"

    val parameters = classDeclaration.parseConstructor()
    logger.warn(parameters.map { it.name.getShortName() to it.type.toString() }
        .toString())

    buildList {
        addAll(generateUpdateFunctions(classDeclaration, parameters, logger))
        addAll(generateActions(classDeclaration, stateLike))
        addAll(generateExtensions(classDeclaration, stateLike, logger))
        addAll(plugins.map { it.run(logger, stateLike) })
    }.forEach {
        it.writeTo(file)
    }

    file.close()
}

private fun generateActions(
    ksClass: KSClassDeclaration,
    annotation: AnnotationRep
): List<Writeable> {
    val domainType = ksClass.toClassName()
    val domainName = domainType.simpleName
    val interfaceName = ClassName("app.dapk.gen", "${domainName}Actions")
    val generatedActionsInterface = TypeSpec.interfaceBuilder(interfaceName)
        .addModifiers(SEALED)
        .addSuperinterface(Action::class)

    // should separate action interface be name spaced?

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
                                    it.name?.getShortName() ?: "value",
                                    it.type.toTypeName()
                                ).build()
                            }
                        ).build()
                )
                .addProperties(
                    it.arguments.map {
                        val propName = it.name?.getShortName() ?: "value"
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

private fun generateExtensions(
    ksClass: KSClassDeclaration,
    annotation: AnnotationRep,
    logger: KSPLogger
): List<Writeable> {
    val stateType = ksClass.toClassName()

    val actionExtensions = annotation.actions?.map { (key, values) ->
        values.map {
            val actionType =
                ClassName("app.dapk.gen", "${stateType.simpleName}Actions.${it.name.capitalize()}")

            val listOf: List<ParameterSpec> = listOf(
                ParameterSpec("", stateType),
                ParameterSpec("", actionType.topLevelClassName())
            )
            FunSpec.builder(it.name)
                .receiver(ReducerBuilder::class.asTypeName().parameterizedBy(stateType))
                .addParameter(
                    "block",
                    LambdaTypeName.get(
                        receiver = ExecutionRegistrar::class.asTypeName()
                            .parameterizedBy(stateType),
                        parameters = listOf,
                        returnType = Unit::class.asTypeName()
                    ),
                )
                .addStatement("app.dapk.internal.registerAction(this, ${actionType}::class, block)")
                .build()

        }
    }?.flatten().orEmpty()

    val receiver = ClassName("", "${stateType.simpleName}Updater")
    val executionExtensions = listOf(
        FunSpec.builder("update")
            .receiver(ExecutionRegistrar::class.asTypeName().parameterizedBy(stateType))
            .addParameter(
                "block",
                LambdaTypeName.get(receiver, emptyList(), Unit::class.asTypeName())
            )
            .addStatement("register(app.dapk.internal.UpdateExec(${receiver.simpleName}Impl().apply(block).collect()))")
            .build(),
    )

    val dispatcher = annotation.actions?.let {
        it.map { (key, values) ->
            val implBuilder = TypeSpec.anonymousClassBuilder()
                .addSuperinterface(key.toClassName())

            values.forEach { action ->
                val actionType = ClassName(
                    "app.dapk.gen",
                    "${stateType.simpleName}Actions.${action.name.capitalize()}"
                )

                val funcBuilder = FunSpec.builder(action.name)
                    .addModifiers(OVERRIDE)

                when {
                    action.arguments.isEmpty() -> funcBuilder.addStatement("it.dispatch($actionType)")
                    else -> {
                        action.arguments.forEach {
                            funcBuilder.addParameter(it.name!!.getShortName(), it.type.toTypeName())
                        }
                        funcBuilder.addStatement(
                            "it.dispatch($actionType(${
                                action.arguments.joinToString(
                                    separator = ","
                                ) { it.name!!.getShortName() }
                            }))"
                        )
                    }
                }
                implBuilder.addFunction(funcBuilder.build())
            }

            PropertySpec
                .builder(stateType.simpleName.decapitalize(), key.toClassName())
                .receiver(Store::class.asTypeName().parameterizedBy(stateType))
                .delegate(
                    CodeBlock.Builder()
                        .beginControlFlow("app.dapk.internal.StoreProperty")
                        .add(implBuilder.build().toString())
                        .endControlFlow()
                        .build()
                ).build()
        }
    }.orEmpty()

    return (actionExtensions + executionExtensions + dispatcher).map { extension ->
        Writeable { it += extension.toString() }
    }
}

private fun generateUpdateFunctions(
    ksClass: KSClassDeclaration,
    prop: List<Prop>,
    logger: KSPLogger
): List<Writeable> {
    val domainType = ksClass.toClassName()
    val domainName = domainType.simpleName
    val updaterName = "${domainName.replaceFirstChar { it.titlecase() }}Updater"
    val publicUpdateApi = TypeSpec.interfaceBuilder(updaterName)

    val publicApiType = ClassName("", updaterName)
    val internalUpdateApi =
        TypeSpec.classBuilder("${domainName.replaceFirstChar { it.titlecase() }}UpdaterImpl")
            .addSuperinterface(publicApiType)
            .addSuperinterface(
                ClassName("app.dapk.internal", "Collectable").parameterizedBy(
                    domainType
                )
            )

    val collectBuilder = FunSpec
        .builder("collect")
        .returns(Update::class.asTypeName().parameterizedBy(domainType))
        .addModifiers(OVERRIDE)

    val collectCopyBlock = CodeBlock.builder()
        .add("return ${Update::class.asTypeName().canonicalName} { from ->\n")
        .indent()
        .add("from.copy(")
        .indent()

    prop.forEach {
        val propertyName = it.name.getShortName()
        val propertyType = it.type.toTypeName()
        val funBuilder = FunSpec
            .builder(propertyName)
            .addParameter(
                ParameterSpec.builder(
                    propertyName,
                    propertyType,
                ).build()
            ).build()

        publicUpdateApi.addFunction(funBuilder.toBuilder().addModifiers(ABSTRACT).build())

        if (it.type.isMarkedNullable) {
            val nullableSpec =
                PropertySpec.builder("nullable_${propertyName}_set", Boolean::class, PRIVATE)
                    .mutable()
                    .initializer("false")
            internalUpdateApi.addProperty(nullableSpec.build())
        }

        val propertySpec =
            PropertySpec.builder("_$propertyName", propertyType.copy(nullable = true), PRIVATE)
                .mutable()
                .initializer("null")
        internalUpdateApi.addProperty(propertySpec.build())

        val implFun = funBuilder.toBuilder()
            .addModifiers(OVERRIDE)
            .apply {
                if (it.type.isMarkedNullable) {
                    addCode("nullable_${propertyName}_set = true")
                }
            }
            .addCode("_${propertyName} = $propertyName")

        internalUpdateApi.addFunction(implFun.build())

        if (it.type.isMarkedNullable) {
            collectCopyBlock.add("\n")
            collectCopyBlock.add("$propertyName = if (nullable_${propertyName}_set) _$propertyName else from.$propertyName,")
        } else {
            collectCopyBlock.add("\n")
            collectCopyBlock.add("$propertyName = _$propertyName ?: from.$propertyName,")
        }
    }

    collectCopyBlock.unindent()
    collectCopyBlock.add("\n)\n")
    collectCopyBlock.unindent()
    collectCopyBlock.add("}")

    collectBuilder.addCode(collectCopyBlock.build())
    internalUpdateApi.addFunction(collectBuilder.build())

    val internalBuild = internalUpdateApi.build()

    val updateFun = FunSpec.builder("update")
        .returns(Update::class.asTypeName().parameterizedBy(domainType))
        .addParameter(
            "block",
            LambdaTypeName.get(publicApiType, emptyList(), Unit::class.asTypeName())
        )
        .addCode(
            """
                return ${internalBuild.name!!}().apply(block).collect()
            """.trimIndent()
        )
        .build()

    logger.warn(internalBuild.toString())


    return listOf(
        Writeable { it += publicUpdateApi.build().toString() },
        Writeable { it += internalBuild.toString() },
        Writeable { it += updateFun.toString() }
    )
}

fun interface Writeable {

    fun writeTo(outputStream: OutputStream)
}
