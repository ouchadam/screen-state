package app.dapk.state.plugin

import app.dapk.annotation.CombinedState
import app.dapk.annotation.State
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
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
        domainClass = domainType,
        parentDeclaration = parentDeclaration,
        isObject = this.classKind == ClassKind.OBJECT,
        actions = annotation.parseActionsArgument("actions")?.plus(
            parentDeclaration?.parseCombinedAnnotation()?.commonActions ?: emptyList()
        ),
        isProxy = this.getSealedSubclasses().iterator().hasNext()
    )
}

fun KSClassDeclaration.parseCombinedAnnotation(): CombinedRep {
    val annotation = this.annotations.first { it.shortName.asString() == CombinedState::class.simpleName }
    val domainType = this.toClassName()
    return CombinedRep(
        AnnotationRep(
            domainClass = domainType,
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

fun KspContext.createFile(fileName: String, packageName: String = PACKAGE, block: () -> List<Writeable>) {
    val file = codeGenerator.createNewFile(
        dependencies = Dependencies(false, *resolver.getAllFiles().toList().toTypedArray()),
        packageName = packageName,
        fileName = fileName
    )

    file += "package $packageName\n"
    file.use { block().forEach { it.writeTo(file) } }
}