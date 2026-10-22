package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.database.entity.TransportType
import io.rebble.libpebblecommon.health.SleepDiagnosticFlags
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class HealthDataExporterTest {
    @Test
    fun streamingExportMatchesExistingJsonExactly() {
        val minutes = listOf(
            HealthDataEntity(120, 2, 0, 0, 0, 0, 0, 0, 0),
            HealthDataEntity(
                60, 1, 0, 0, 0, 0, 0, 0, 0,
                pluggedIn = 1, sleepIntentHint = 1, timezoneOffset15Minutes = -4,
                sleepScore = 4_294_967_295L,
                sleepFlags = SleepDiagnosticFlags.SCORE_VALID or SleepDiagnosticFlags.FRAGMENT_ACCEPTED,
            ),
        )
        val overlays = listOf(
            OverlayDataEntity(120, 30, 2, 0, 0, 0, 0, 0),
            OverlayDataEntity(120, 10, 1, 0, 0, 0, 0, 0),
        )
        val watches = listOf(
            KnownWatchItem("socket", TransportType.Socket, "Watch \"One\"\n雪", "v4.3", "SERIAL", false),
            KnownWatchItem("bluetooth", TransportType.BluetoothLe, "Watch Two", "v4.2", "SERIAL", false),
        )
        val sink = Buffer()

        HealthDataExportFormatter.write(minutes, overlays, watches, sink)

        assertEquals(HealthDataExportFormatter.format(minutes, overlays, watches), sink.readString())
        HealthDataExportFormatter.write(emptyList(), emptyList(), emptyList(), sink)
        assertEquals(HealthDataExportFormatter.format(emptyList(), emptyList(), emptyList()), sink.readString())
    }

    @Test
    fun exporterStreamsLargeHistoryInBoundedChunks() = runTest {
        val minutes = List(80_470) { index ->
            HealthDataEntity(
                index * 60L, 0, 0, 0, 0, 0, 0, 0, 0,
                sleepScore = 50,
                sleepFlags = SleepDiagnosticFlags.SCORE_VALID,
            )
        }
        val exporter = HealthDataExporter(object : LibPebble by FakeLibPebble() {
            override suspend fun getAllHealthData() = minutes
        })
        val rawSink = CountingSink()

        rawSink.buffered().use { exporter.export(it) }

        assertTrue(rawSink.bytesWritten > 90_000_000, "All expanded diagnostic records must be exported")
        assertTrue(rawSink.largestWrite < 65_536, "JSON must reach the file incrementally")
    }

    @Test
    fun streamingExportPropagatesFileWriteFailure() {
        val sink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long): Unit = throw IOException("Disk full")
            override fun flush() = Unit
            override fun close() = Unit
        }.buffered()

        assertFailsWith<IOException> {
            HealthDataExportFormatter.write(emptyList(), emptyList(), emptyList(), sink)
            sink.flush()
        }
    }

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

        assertTrue(output.contains("\"schemaVersion\": 2"))
        assertTrue(output.indexOf("\"timestamp\": 60") < output.indexOf("\"timestamp\": 120"))
        assertTrue(output.contains("\"pluggedIn\": 1"))
        assertTrue(output.contains("\"sleepIntentHint\": 1"))
        assertTrue(output.contains("\"timezoneOffset15Minutes\": -4"))
        assertTrue(output.contains("\"sleepDiagnostics\": null"))
        assertTrue(output.indexOf("\"type\": 1") < output.indexOf("\"type\": 2"))
        assertTrue(output.contains("""Watch \"One\""""))
        assertTrue(output.indexOf("Watch \\\"One\\\"") < output.indexOf("Watch Two"))
    }

    @Test
    fun exportIncludesReadableValidSleepDiagnostics() {
        val flags = SleepDiagnosticFlags.SCORE_VALID or
            SleepDiagnosticFlags.COMPUTED_NOT_WORN or
            SleepDiagnosticFlags.SESSION_ACCEPTED or
            SleepDiagnosticFlags.FRAGMENT_ACCEPTED
        val output = HealthDataExportFormatter.format(
            minutes = listOf(
                HealthDataEntity(
                    60, 1, 0, 0, 0, 0, 0, 0, 0,
                    sleepScore = 4_294_967_295L,
                    sleepFlags = flags,
                )
            ),
            overlays = emptyList(),
            watches = emptyList(),
        )

        assertTrue(output.contains("\"score\": 4294967295"))
        assertTrue(output.contains("\"flags\": $flags"))
        assertTrue(output.contains("\"scoreValid\": true"))
        assertTrue(output.contains("\"computedNotWorn\": true"))
        assertTrue(output.contains("\"sessionAccepted\": true"))
        assertTrue(output.contains("\"fragmentAccepted\": true"))
        assertTrue(output.contains("\"rejectedNotWorn\": false"))
        assertTrue(output.contains("\"hrmOffWristInput\": false"))
    }

    @Test
    fun exportKeepsLegacyDaytimeAndAwakeMinutesIndependentOfOverlays() {
        val output = HealthDataExportFormatter.format(
            minutes = listOf(
                HealthDataEntity(60, 0, 0, 0, 0, 0, 0, 0, 0),
                HealthDataEntity(120, 25, 0, 0, 0, 0, 0, 0, 0),
                HealthDataEntity(
                    180, 0, 0, 0, 0, 0, 0, 0, 0,
                    sleepScore = 50,
                    sleepFlags = SleepDiagnosticFlags.SCORE_VALID,
                ),
            ),
            overlays = listOf(OverlayDataEntity(120, 60, 1, 0, 0, 0, 0, 0)),
            watches = emptyList(),
        )

        assertTrue(output.contains("\"timestamp\": 60"))
        assertTrue(output.contains("\"timestamp\": 120"))
        assertTrue(output.contains("\"timestamp\": 180"))
        assertTrue(output.contains("\"steps\": 25"))
        assertTrue(output.contains("\"sleepMinute\": false"))
    }
}

private class CountingSink : RawSink {
    var bytesWritten = 0L
    var largestWrite = 0L

    override fun write(source: Buffer, byteCount: Long) {
        bytesWritten += byteCount
        largestWrite = maxOf(largestWrite, byteCount)
        source.skip(byteCount)
    }

    override fun flush() = Unit
    override fun close() = Unit
}
