package app.dapk.state.plugin

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toKModifier

data class AnnotationRep(
    val domainClass: ClassName,
    val visibility: Visibility,
    val parentDeclaration: KSClassDeclaration?,
    val actions: List<ActionRep>?,
    val isObject: Boolean,
    val isProxy: Boolean,
) {

    private val parentClass = parentDeclaration?.toClassName()

    fun visibilityModifier() = visibility.toKModifier()!!

    fun simpleName() = (parentClass?.let {
        "${it.simpleName}${domainClass.simpleName}"
    } ?: domainClass.simpleName)

    fun resolveClass(): ClassName {
        return when (isProxy) {
            true -> ClassName(PACKAGE, "${domainClass.simpleName}Proxy")
            false -> domainClass
        }
    }

    fun resolveSimpleName(): String {
        val simpleName = simpleName()
        return when (isProxy) {
            true -> "${simpleName}Proxy"
            false -> simpleName
        }
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