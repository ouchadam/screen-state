package app.dapk.annotation

import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
annotation class CombinedState(val actions: Array<KClass<*>> = [])