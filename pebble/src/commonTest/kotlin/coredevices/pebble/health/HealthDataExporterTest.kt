package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.TransportType
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class HealthDataExporterTest {
    @Test
    fun exporterResolvesFromApplicationKoin() {
        val application = koinApplication {
            modules(
                module {
                    single<LibPebble> { FakeLibPebble() }
                    singleOf(::HealthDataExporter)
                },
            )
        }

        assertNotNull(application.koin.get<HealthDataExporter>())
        application.close()
    }

    @Test
    fun exportIsOrderedAndIncludesRawContext() {
        val output = HealthDataExportFormatter.format(
            minutes = listOf(
                HealthDataEntity(120, 2, 0, 0, 0, 0, 0, 0, 0, pluggedIn = 1, sleepIntentHint = 1, timezoneOffset15Minutes = -4),
                HealthDataEntity(60, 1, 0, 0, 0, 0, 0, 0, 0),
            ),
            overlays = listOf(
                OverlayDataEntity(120, 30, 2, 0, 0, 0, 0, 0),
                OverlayDataEntity(120, 10, 1, 0, 0, 0, 0, 0),
            ),
            watches = listOf(
                KnownWatchItem("socket", TransportType.Socket, "Watch \"One\"", "v4.3", "SERIAL", false),
                KnownWatchItem("bluetooth", TransportType.BluetoothLe, "Watch Two", "v4.2", "SERIAL", false),
            ),
        )

        assertTrue(output.contains("\"schemaVersion\": 1"))
        assertTrue(output.indexOf("\"timestamp\": 60") < output.indexOf("\"timestamp\": 120"))
        assertTrue(output.contains("\"pluggedIn\": 1"))
        assertTrue(output.contains("\"sleepIntentHint\": 1"))
        assertTrue(output.contains("\"timezoneOffset15Minutes\": -4"))
        assertTrue(output.indexOf("\"type\": 1") < output.indexOf("\"type\": 2"))
        assertTrue(output.contains("""Watch \"One\""""))
        assertTrue(output.indexOf("Watch \\\"One\\\"") < output.indexOf("Watch Two"))
    }
}
