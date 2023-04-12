package app.dapk.state.plugin

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter

data class AnnotationRep(
    val actions: Map<KSType, List<ActionFunction>>?
)
data class ActionFunction(val name: String, val arguments: List<KSValueParameter>)

fun KSClassDeclaration.parseStateAnnotation(): AnnotationRep {
    val annotation = this.annotations.first {
        it.shortName.asString() == "State"
    }

    return annotation.arguments.fold(AnnotationRep(null)) { acc, curr ->
        when (curr.name?.getShortName()) {
            "actions" -> {
                acc.copy(actions = (curr.value as ArrayList<KSType>).toList().toActionFunctions())
            }

            else -> acc
        }
    }
}

private fun List<KSType>.toActionFunctions() = this.associateWith {
    val declaration = it.declaration as KSClassDeclaration
    declaration.getDeclaredFunctions()
        .map { ActionFunction(it.simpleName.getShortName(), it.parameters) }
        .toList()
}

fun KSClassDeclaration.parseConstructor(): List<Prop> {
    return this.primaryConstructor?.parameters?.mapNotNull {
        it.takeIf { it.name != null }?.let {
            val resolvedType = it.type.resolve()
            Prop(it.name!!, resolvedType)
        }
    }.orEmpty()
}

