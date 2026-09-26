package coredevices.coreapp.di

import io.rebble.libpebblecommon.connection.LibPebble3
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.flatten

class AndroidAppModulesTest {
    // watchModule passes LibPebble3.create its arguments with positional get() calls, which only
    // fail at app startup when a definition is missing (e.g. dropped while rebasing).
    @Test
    fun declaresEveryLibPebble3CreateDependency() {
        val create = LibPebble3.Companion::class.java.declaredMethods.filter { it.name == "create" }
        assertEquals(1, create.size, "expected a single LibPebble3.create overload")
        // The proxy token flow is built inline in watchModule, not resolved from Koin.
        val required = create.single().parameterTypes.filterNot { it == StateFlow::class.java }
        assertTrue(required.isNotEmpty())

        val declared = declaredTypes()
        val missing = required.filterNot { it in declared }
        assertTrue(missing.isEmpty(), "no Koin definition for ${missing.map { it.simpleName }}")
    }

    @OptIn(KoinInternalApi::class)
    private fun declaredTypes(): Set<Class<*>> =
        flatten(androidAppModules)
            .flatMap { it.mappings.values }
            .flatMap { factory ->
                val definition = factory.beanDefinition
                listOf(definition.primaryType) + definition.secondaryTypes
            }
            .map { it.java }
            .toSet()
}
