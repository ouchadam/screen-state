package app.dapk.state.plugin

import app.dapk.extension.Plugin
import app.dapk.internal.StoreProperty
import app.dapk.internal.Update
import app.dapk.internal.UpdateExec
import app.dapk.internal.registerAction
import app.dapk.state.Action
import app.dapk.state.CombinedState
import app.dapk.state.ExecutionRegistrar
import app.dapk.state.ReducerBuilder
import app.dapk.state.State
import app.dapk.state.StoreScope
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSReferenceElement
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
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import java.util.ServiceLoader
import kotlin.reflect.KClass
import kotlin.reflect.jvm.javaMethod

const val PACKAGE = "app.dapk.gen"

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
    val className = classDeclaration.simpleName.asString()
    kspContext.createFile(fileName = "${className}Generated") {
        val parameters = classDeclaration.parseConstructor()
        logger.warn(parameters.map { it.name.getShortName() to it.type.toString() }
            .toString())

        buildList {
            if (!stateLike.isObject) {
                addAll(generateUpdateFunctions(classDeclaration, parameters, logger))
            }
            addAll(generateActions(classDeclaration, stateLike))
            addAll(generateExtensions(classDeclaration, stateLike, logger))
            addAll(plugins.map { it.run(logger, stateLike) })
        }
    }
}

private fun generateActions(
    ksClass: KSClassDeclaration,
    annotation: AnnotationRep
): List<Writeable> {
    val domainType = ksClass.toClassName()
    val domainName = domainType.simpleName
    val interfaceName = ClassName(PACKAGE, "${domainName}Actions")
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
                ClassName(PACKAGE, "${stateType.simpleName}Actions.${it.name.capitalize()}")

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

    val dispatcher = annotation.actions?.let {
        val type = "${annotation.domainClass.simpleName}AllActions"
        val allActionsType = TypeSpec.interfaceBuilder(type)
            .addSuperinterfaces(it.keys.map { it.toClassName() })
            .build()

        val allActionsImpl = TypeSpec.anonymousClassBuilder()
            .addSuperinterface(ClassName(PACKAGE, type))

        it.values.flatten().forEach { action ->
            val actionType = ClassName(
                PACKAGE,
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
                            action.arguments.joinToString(separator = ",") { 
                                it.name!!.getShortName() 
                            }
                        }))"
                    )
                }
            }
            allActionsImpl.addFunction(funcBuilder.build())
        }

        val extension = PropertySpec
            .builder(stateType.simpleName.decapitalize(), ClassName(PACKAGE, type))
            .receiver(StoreScope::class.asTypeName().parameterizedBy(stateType))
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
    }.map { extension ->
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

    val receiver = ClassName("", "${domainType.simpleName}Updater")
    val executionExtensions = listOf(
        FunSpec.builder("update")
            .receiver(ExecutionRegistrar::class.asTypeName().parameterizedBy(domainType))
            .addParameter(
                "block",
                LambdaTypeName.get(receiver, emptyList(), Unit::class.asTypeName())
            )
            .addStatement("register(${UpdateExec::class.qualifiedName}(${receiver.simpleName}Impl().apply(block).collect()))")
            .build(),
    )

    return buildList {
        add(publicUpdateApi.build())
        add(internalBuild)
        add(updateFun)
        addAll(executionExtensions)
    }.map { content ->
        Writeable { it += content.toString() }
    }
}
