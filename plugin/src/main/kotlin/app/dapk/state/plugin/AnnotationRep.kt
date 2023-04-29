package app.dapk.state.plugin

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import java.lang.annotation.Inherited
import kotlin.reflect.KClass

data class AnnotationRep(
    val domainClass: TypeName,
    private val domainName: ClassName,
    val types: List<KSTypeParameter>,
    private val visibility: Visibility,
    val parentDeclaration: KSClassDeclaration?,
    val actions: List<ActionRep>?,
    val isObject: Boolean,
    val isProxy: Boolean,
) {

    val packageName: String = domainName.packageName
    val qualifiedName: String = domainName.canonicalName

    private val parentClass = parentDeclaration?.toClassName()

    fun withVisibility(block: (KModifier) -> Unit) {
        visibility.toKModifier().takeIf { it != PUBLIC }?.let { block(it) }
    }

    fun withTypes(block: (List<TypeVariableName>) -> Unit) {
        block(types.map { TypeVariableName(it.simpleName.asString()) })
    }

    private fun isTyped() = types.isNotEmpty()

    fun visibilityModifier() = visibility.toKModifier()!!

    fun simpleName() = (parentClass?.let {
        "${it.simpleName}${domainName.simpleName}"
    } ?: domainName.simpleName)

    fun shortName() = domainName.simpleName

    fun resolveClass(starProjected: Boolean = false) = when (isProxy) {
        true -> ClassName(packageName, "${domainName.simpleName}Proxy").withType(starProjected)
        false -> domainName.withType(starProjected)
    }

    private fun ClassName.withType(starProjected: Boolean) = when (isTyped()) {
        true -> this.parameterizedBy(types.map {
            if (starProjected) STAR else TypeVariableName(it.simpleName.asString())
        })
        false -> this
    }

    fun resolveSimpleName(): String {
        val simpleName = simpleName()
        return when (isProxy) {
            true -> "${simpleName}Proxy"
            false -> simpleName
        }
    }

    fun createTypeName(name: String, inheritType: Boolean = true, starProjected: Boolean = false): TypeName = when (inheritType) {
        true -> ClassName(packageName, name).withType(starProjected)
        false -> ClassName(packageName, name)
    }

    fun resolveType(type: KSType): TypeName {
        return type.toTypeName(types.toTypeParameterResolver())
    }

    fun asParameterOf(kClass: KClass<*>, starProjected: Boolean = false) = when (starProjected) {
        true -> kClass.asTypeName().parameterizedBy(starProjected())
        false -> kClass.asTypeName().parameterizedBy(domainClass)
    }

    private fun starProjected() = when (isTyped()) {
        true -> domainName.parameterizedBy(types.map { STAR })
        false -> domainName
    }
}

data class CombinedRep(
    val annotationRep: AnnotationRep,
    val commonActions: List<ActionRep>?,
)

data class ActionFunction(val name: String, val arguments: List<KSValueParameter>)

data class ActionRep(
    val domainClass: ClassName,
    val parentClass: ClassName?,
    val functions: List<ActionFunction>,
) {

    fun simpleName() = "Gen${
        (parentClass?.let {
            "${it.simpleName}${domainClass.simpleName}"
        } ?: domainClass.simpleName)
    }"

    fun resolveGeneratedAction(function: ActionFunction): ClassName {
        return ClassName(domainClass.packageName, simpleName()).nestedClass(function.name.capitalize())
    }
}