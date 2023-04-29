package app.dapk.state.plugin

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import app.dapk.annotation.StateActions
import app.dapk.extension.Plugin
import app.dapk.internal.Collectable
import app.dapk.internal.StoreProperty
import app.dapk.internal.Update
import app.dapk.internal.UpdateExec
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ReducerBuilder
import app.dapk.state.StoreScope
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.util.ServiceLoader

class StateProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val plugins =
        ServiceLoader.load(Plugin::class.java, StateProcessor::class.java.classLoader).toList()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val context = KspContext(codeGenerator, resolver)
        return buildList {
            addAll(processAnnotation<StateActions>(context) { StateActionsVisitor(context, logger, plugins) })
            addAll(processAnnotation<CombinedState>(context) { CombinedStateVisitor(context, logger, plugins) })
            addAll(processAnnotation<State>(context) { StateVisitor(context, logger, plugins) })
        }
    }

    private inline fun <reified T> processAnnotation(
        kspContext: KspContext,
        visitorProvider: () -> KSVisitor<Unit, Unit>
    ): List<KSAnnotated> {
        val symbols = kspContext.resolver
            .getSymbolsWithAnnotation(T::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()

        val isEmpty = !symbols.iterator().hasNext()
        return if (isEmpty) {
            emptyList()
        } else {
            symbols.forEach { it.accept(visitorProvider(), Unit) }
            symbols.filterNot { it.validate() }.toList()
        }
    }
}

data class Prop(
    val name: KSName,
    val type: KSType,
)

internal class StateActionsVisitor(
    private val kspContext: KspContext,
    private val logger: KSPLogger,
    private val plugins: List<Plugin>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        when (classDeclaration.classKind) {
            INTERFACE -> {
                val actionRep = classDeclaration.parseStateActionAnnotation()
                kspContext.createFile(
                    actionRep.simpleName(),
                    actionRep.domainClass.packageName,
                ) {
                    listOf(
                        Writeable { it += generateActionClassesFromInterface(actionRep).toString() }
                    )
                }
            }

            else -> {
                logger.error("Unexpected annotation class: ${classDeclaration.classKind}")
            }
        }
    }
}

internal class StateVisitor(
    private val kspContext: KspContext,
    private val logger: KSPLogger,
    private val plugins: List<Plugin>,
) : KSVisitorVoid() {

    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        when (classDeclaration.classKind) {
            CLASS, OBJECT -> {
                processStateLike(
                    kspContext,
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

fun processStateLike(
    kspContext: KspContext,
    classDeclaration: KSClassDeclaration,
    stateLike: AnnotationRep,
    logger: KSPLogger,
    plugins: List<Plugin>
) {
    val parameters = classDeclaration.parseConstructor()
    logger.warn(parameters.map { it.name.getShortName() to it.type.toString() }
        .toString())
    processStateLike(kspContext, parameters, stateLike, logger, plugins)
}

fun processStateLike(
    kspContext: KspContext,
    parameters: List<Prop>,
    stateLike: AnnotationRep,
    logger: KSPLogger,
    plugins: List<Plugin>
) {
    kspContext.createFile(packageName = stateLike.packageName, fileName = "${stateLike.simpleName()}Generated") {
        buildList {
            if (!stateLike.isObject) {
                addAll(generateUpdateFunctions(stateLike, parameters, logger))
            }
            addAll(generateExtensions(stateLike, logger))
            addAll(plugins.map { it.run(logger, stateLike) })
        }
    }
}

private fun generateExtensions(
    annotation: AnnotationRep,
    logger: KSPLogger
): List<Writeable> {
    val actionExtensions = annotation.actions?.map { actionRep ->
        actionRep.functions.map {
            val actionType = actionRep.resolveGeneratedAction(it)
            val listOf: List<ParameterSpec> = listOf(
                ParameterSpec("", annotation.domainClass),
                ParameterSpec("", actionRep.resolveGeneratedAction(it))
            )

            annotation.createFunction(it.name)
                .receiver(annotation.asParameterOf(ReducerBuilder::class))
                .addParameter(
                    "block",
                    LambdaTypeName.get(
                        receiver = annotation.asParameterOf(ExecutionRegistrar::class),
                        parameters = listOf,
                        returnType = Unit::class.asTypeName()
                    ),
                )
                .addStatement("app.dapk.internal.registerAction(this, ${actionType}::class, block)")
                .build()
        }
    }?.flatten().orEmpty()

    val dispatcher = annotation.actions?.let {
        val type = "${annotation.simpleName()}AllActions"
        val allActionsType = TypeSpec.interfaceBuilder(type)
            .addModifiers(annotation.visibilityModifier())
            .addSuperinterfaces(it.map { it.domainClass })
            .build()

        val allActionsImpl = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(ClassName(annotation.packageName, type))

        it.forEach { actionRep ->
            val implementationFunctions = actionRep.functions.map {
                val actionType = actionRep.resolveGeneratedAction(it)

                val funcBuilder = FunSpec.builder(it.name)
                    .addModifiers(OVERRIDE)

                when {
                    it.arguments.isEmpty() -> funcBuilder.addStatement("it.dispatch($actionType)")
                    else -> {
                        it.arguments.forEach {
                            funcBuilder.addParameter(it.name!!.getShortName(), it.type.toTypeName())
                        }
                        funcBuilder.addStatement(
                            "it.dispatch($actionType(${
                                it.arguments.joinToString(separator = ",") {
                                    it.name!!.getShortName()
                                }
                            }))"
                        )
                    }
                }
                funcBuilder.build()
            }
            allActionsImpl.addFunctions(implementationFunctions)
        }

        val extension = PropertySpec
            .builder(annotation.simpleName().decapitalize(), annotation.createTypeName(type, inheritType = false))
            .addVisibility(annotation)
            .receiver(annotation.asParameterOf(StoreScope::class, starProjected = true))
            .delegate(
                CodeBlock.Builder()
                    .beginControlFlow(StoreProperty::class.qualifiedName!!)
                    .add(allActionsImpl.build().toString())
                    .endControlFlow()
                    .build()
            ).build()

        listOf(extension, allActionsType)
    }.orEmpty()

    return buildList {
        addAll(actionExtensions)
        addAll(dispatcher)
    }.toWriteable()
}

private fun generateUpdateFunctions(
    stateLike: AnnotationRep,
    prop: List<Prop>,
    logger: KSPLogger
): List<Writeable> {
    val updaterName = "${stateLike.simpleName().replaceFirstChar { it.titlecase() }}Updater"
    val publicUpdateApi = stateLike.createInterface(updaterName)

    val publicApiType = stateLike.createTypeName(updaterName)
    val internalUpdateApi = stateLike.createClass("${stateLike.simpleName().replaceFirstChar { it.titlecase() }}UpdaterImpl")
            .addSuperinterface(publicApiType)
            .addSuperinterface(stateLike.asParameterOf(Collectable::class))

    val collectBuilder = FunSpec
        .builder("collect")
        .returns(stateLike.asParameterOf(Update::class))
        .addModifiers(OVERRIDE)

    val collectCopyBlock = CodeBlock.builder()
        .add("return ${Update::class.asTypeName().canonicalName} { from ->\n")
        .indent()
        .add("from.copy(")
        .indent()

    prop.forEach {
        val propertyName = it.name.getShortName().decapitalize()
        val type = stateLike.resolveType(it.type)
        val funBuilder = FunSpec
            .builder(propertyName)
            .addParameter(
                ParameterSpec.builder(
                    propertyName,
                    type,
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
            PropertySpec.builder("_$propertyName", type.copy(nullable = true), PRIVATE)
                .mutable()
                .initializer("null")
        internalUpdateApi.addProperty(propertySpec.build())

        val implFun = funBuilder.toBuilder()
            .addModifiers(OVERRIDE)
            .apply {
                if (it.type.isMarkedNullable) {
                    addCode("nullable_${propertyName}_set = true\n")
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

    val updateFun = stateLike.createFunction("update")
        .returns(stateLike.asParameterOf(Update::class))
        .addParameter(
            "block",
            LambdaTypeName.get(publicApiType, emptyList(), Unit::class.asTypeName())
        )
        .addCode(
            """
                return ${internalBuild.nameWithTypes()}().apply(block).collect()
            """.trimIndent()
        )
        .build()

    logger.warn(internalBuild.toString())

    val receiver = stateLike.createTypeName("${stateLike.simpleName()}Updater")
    val executionExtensions = listOf(
        stateLike.createFunction("update")
            .receiver(stateLike.asParameterOf(ExecutionRegistrar::class))
            .addParameter(
                "block",
                LambdaTypeName.get(receiver, emptyList(), Unit::class.asTypeName())
            )
            .addStatement("register(${UpdateExec::class.qualifiedName}(${internalBuild.nameWithTypes()}().apply(block).collect()))")
            .build(),
    )

    return buildList {
        add(publicUpdateApi.build())
        add(internalBuild)
        add(updateFun)
        addAll(executionExtensions)
    }.toWriteable()
}

private fun List<Any>.toWriteable() = this.map { content ->
    Writeable { it += content.toString() }
}