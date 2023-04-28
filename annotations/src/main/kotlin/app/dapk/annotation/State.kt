package app.dapk.annotation

import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.KClass

@Target(CLASS)
annotation class State(val actions: Array<KClass<*>> = [])