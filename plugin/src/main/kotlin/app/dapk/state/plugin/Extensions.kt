package app.dapk.state.plugin

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.ksp.toClassName
import java.io.OutputStream

fun KSClassDeclaration.parseStateAnnotation(): AnnotationRep {
    val annotation = this.annotations.first {
        it.shortName.asString() == State::class.simpleName ||
            it.shortName.asString() == CombinedState::class.simpleName
    }
    val domainType = this.toClassName()
    val parentDeclaration = this.parentDeclaration?.let { it as? KSClassDeclaration }

    return AnnotationRep(
        domainClass = this.typedName(),
        domainName = domainType,
        types = this.typeParameters,
        visibility = this.getVisibility(),
        parentDeclaration = parentDeclaration,
        isObject = this.classKind == ClassKind.OBJECT,
        actions = annotation.parseActionsArgument("actions")?.plus(
            parentDeclaration?.parseCombinedAnnotation()?.commonActions ?: emptyList()
        ),
        isProxy = this.getSealedSubclasses().iterator().hasNext()
    )
}

private fun KSClassDeclaration.typedName(): TypeName {
    val domainType = this.toClassName()
    return when(this.typeParameters.isEmpty()) {
        true -> domainType
        false -> domainType.parameterizedBy(this.typeParameters.map { TypeVariableName(it.simpleName.asString()) })
    }
}


fun KSClassDeclaration.parseCombinedAnnotation(): CombinedRep {
    val annotation = this.annotations.first { it.shortName.asString() == CombinedState::class.simpleName }
    val domainType = this.toClassName()
    return CombinedRep(
        AnnotationRep(
            domainClass = this.typedName(),
            domainName = domainType,
            types = this.typeParameters,
            visibility = this.getVisibility(),
            parentDeclaration = this.parentDeclaration?.let { it as? KSClassDeclaration },
            isObject = this.classKind == ClassKind.OBJECT,
            actions = annotation.parseActionsArgument("actions"),
            isProxy = this.getSealedSubclasses().iterator().hasNext()
        ),
        commonActions = annotation.parseActionsArgument("commonActions")
    )
}

private fun KSAnnotation.parseActionsArgument(argumentName: String) = this.arguments
    .firstOrNull { it.name?.getShortName() == argumentName }?.let { firstOrNull ->
        (firstOrNull.value as ArrayList<KSType>).toList().map {
            (it.declaration as KSClassDeclaration).parseStateActionAnnotation()
        }
    }

fun KSClassDeclaration.parseStateActionAnnotation(): ActionRep {
    val actionClassName = this.toClassName()
    return ActionRep(
        domainClass = actionClassName,
        parentClass = this.parentDeclaration?.let { it as? KSClassDeclaration }?.toClassName(),
        functions = this.getActionFunctions(),
    )
}

fun KSClassDeclaration.getActionFunctions() = this.getDeclaredFunctions()
    .map { ActionFunction(it.simpleName.getShortName(), it.parameters) }
    .toList()

fun KSClassDeclaration.parseConstructor(): List<Prop> {
    return this.primaryConstructor?.parameters?.mapNotNull {
        it.takeIf { it.name != null }?.let {
            val resolvedType = it.type.resolve()
            Prop(it.name!!, resolvedType)
        }
    }.orEmpty()
}

data class ClassProperty(
    val name: String,
    val type: ClassName,
)

fun createDataClass(name: String, properties: List<ClassProperty>) = TypeSpec.classBuilder(name)
    .addModifiers(KModifier.DATA)
    .primaryConstructor(
        FunSpec.constructorBuilder()
            .addParameters(
                properties.map {
                    ParameterSpec.builder(
                        it.name.decapitalize(),
                        it.type
                    ).build()
                }
            ).build()
    )
    .addProperties(
        properties.map {
            val propName = it.name.decapitalize()
            PropertySpec.builder(propName, it.type)
                .initializer(propName)
                .build()
        }
    )

operator fun OutputStream.plusAssign(str: String) {
    this.write(str.toByteArray())
}

class KspContext(
    val codeGenerator: CodeGenerator,
    val resolver: Resolver,
)

fun KspContext.createFile(fileName: String, packageName: String, block: () -> List<Writeable>) {
    val file = codeGenerator.createNewFile(
        dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
        packageName = packageName,
        fileName = fileName
    )

    file += "package $packageName\n"
    file.use { block().forEach { it.writeTo(file) } }
}

fun PropertySpec.Builder.addVisibility(annotationRep: AnnotationRep) = this.apply {
    annotationRep.withVisibility { addModifiers(it) }
}

fun AnnotationRep.createInterface(name: String): TypeSpec.Builder {
    return TypeSpec.interfaceBuilder(name)
        .apply {
            withVisibility { addModifiers(it) }
            withTypes { addTypeVariables(it) }
        }
        .addModifiers(this.visibilityModifier())
}

fun AnnotationRep.createClass(name: String): TypeSpec.Builder {
    return TypeSpec.classBuilder(name)
        .apply {
            withVisibility { addModifiers(it) }
            withTypes { addTypeVariables(it) }
        }
}

fun AnnotationRep.createFunction(name: String): FunSpec.Builder {
    return FunSpec.builder(name)
        .apply {
            withVisibility { addModifiers(it) }
            withTypes { addTypeVariables(it) }
        }
}

fun TypeSpec.nameWithTypes(): String {
    return if (typeVariables.isEmpty()) {
        this.name!!
    } else {
        "${this.name}${this.typeVariables.joinToString(prefix = "<", separator = ",", postfix = ">")}"
    }
}

fun CombinedRep.createObject(name: String, inheritType: Boolean = false): TypeSpec.Builder {
    return TypeSpec.objectBuilder(name)
        .apply {
            annotationRep.withVisibility { addModifiers(it) }
            if (inheritType) annotationRep.withTypes { addTypeVariables(it) }
        }
}

fun CombinedRep.createFunction(name: String, inheritType: Boolean = false): FunSpec.Builder {
    return FunSpec.builder(name)
        .apply {
            annotationRep.withVisibility { addModifiers(it) }
            if (inheritType) annotationRep.withTypes { addTypeVariables(it) }
        }
}

fun KSType.toQualifiedName(): String {
    return when(val actual = this.declaration) {
        is KSTypeParameter -> actual.simpleName.getShortName()
        else -> actual.qualifiedNameWithTypes()
    }
}

private fun KSDeclaration.qualifiedNameWithTypes(): String {
    val name = this.qualifiedName!!.asString()
    return if (typeParameters.isEmpty()) {
        name
    } else {
        "${name}${this.typeParameters.joinToString(prefix = "<", separator = ",", postfix = ">") { it.simpleName.asString()} }"
    }
}