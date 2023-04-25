package app.dapk.state.plugin

import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName

data class AnnotationRep(
    val domainClass: ClassName,
    val parentClass: ClassName?,
    val actions: Map<KSType, List<ActionFunction>>?,
    val isObject: Boolean
) {

    fun simpleName() = parentClass?.let {
        "${it.simpleName}${domainClass.simpleName}"
    } ?: domainClass.simpleName

}
data class ActionFunction(val name: String, val arguments: List<KSValueParameter>)
