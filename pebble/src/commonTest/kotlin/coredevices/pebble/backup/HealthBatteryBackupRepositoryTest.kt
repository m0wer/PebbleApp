package coredevices.pebble.backup

import coredevices.database.BatteryHistoryEntity
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthBatteryBackupRepositoryTest {
    @Test
    fun codecRoundTripsEveryPersistedFieldExceptBatteryId() {
        val minute = HealthDataEntity(
            timestamp = 120,
            steps = 1,
            orientation = 2,
            intensity = 3,
            lightIntensity = 4,
            activeMinutes = 5,
            restingGramCalories = 6,
            activeGramCalories = 7,
            distanceCm = 8,
            heartRate = 9,
            heartRateZone = 10,
            heartRateWeight = 11,
            pluggedIn = 12,
            sleepIntentHint = 13,
            timezoneOffset15Minutes = 14,
            sleepScore = 15,
            sleepFlags = 16,
        )
        val overlay = OverlayDataEntity(120, 21, 22, 23, 24, 25, 26, 27)
        val battery = batteryHistory(id = 99)
        val decoded = HealthBatteryBackupCodec.decode(
            HealthBatteryBackupCodec.encode(
                HealthBatteryBackupExportData(
                    knownWatches = listOf(HealthBatteryBackupWatch("Pebble", "SERIAL", "v4")),
                    healthMinutes = listOf(minute),
                    overlays = listOf(overlay),
                    batteryHistory = listOf(battery),
                ),
                exportedAtEpochSeconds = 1,
            )
        )

        assertEquals(listOf(minute), decoded.healthMinutes)
        assertEquals(listOf(overlay), decoded.overlays)
        assertEquals(listOf(battery.copy(id = 0)), decoded.batteryHistory)
    }

    @Test
    fun codecOrderingIsDeterministic() {
        val data = HealthBatteryBackupExportData(
            knownWatches = listOf(
                HealthBatteryBackupWatch("Second", "B", "v2"),
                HealthBatteryBackupWatch("First", "A", "v1"),
            ),
            healthMinutes = listOf(minute(120), minute(60)),
            overlays = listOf(overlay(120, 2), overlay(120, 1)),
            batteryHistory = listOf(batteryHistory(serial = "B", timestampSeconds = 2), batteryHistory(serial = "A", timestampSeconds = 1)),
        )

        val output = HealthBatteryBackupCodec.encode(data, exportedAtEpochSeconds = 1)

        assertEquals(output, HealthBatteryBackupCodec.encode(data, exportedAtEpochSeconds = 1))
        assertTrue(output.indexOf("\"serial\": \"A\"") < output.indexOf("\"serial\": \"B\""))
        assertTrue(output.indexOf("\"timestamp\": 60") < output.indexOf("\"timestamp\": 120"))
        assertTrue(output.indexOf("\"type\": 1") < output.indexOf("\"type\": 2"))
        assertTrue(output.indexOf("\"timestampSeconds\": 1") < output.indexOf("\"timestampSeconds\": 2"))
    }

    @Test
    fun invalidDocumentsDoNotWriteAndUnsupportedArchivesAreRejected() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = HealthBatteryBackupRepository(dataSource)
        val valid = HealthBatteryBackupCodec.encode(emptyExportData(), exportedAtEpochSeconds = 1)

        assertIs<IllegalArgumentException>(importFailure(repository, valid.replace(HealthBatteryBackupCodec.FORMAT, "wrong_format")))
        assertIs<IllegalArgumentException>(importFailure(repository, valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
        assertNotNull(importFailure(repository, "not json"))

        assertEquals(0, dataSource.mergeCalls)
    }

    @Test
    fun importReturnsArchiveRecordCounts() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = HealthBatteryBackupRepository(dataSource)
        val document = HealthBatteryBackupCodec.encode(
            emptyExportData().copy(
                healthMinutes = listOf(minute(60)),
                overlays = listOf(overlay(60, 1)),
                batteryHistory = listOf(batteryHistory()),
            ),
            exportedAtEpochSeconds = 1,
        )

        val counts = repository.importBackup(document)

        assertEquals(HealthBatteryBackupImportCounts(1, 1, 1), counts)
        assertEquals(1, dataSource.mergeCalls)
    }

    @Test
    fun documentReaderEnforcesActualByteLimit() {
        val source = Buffer().apply { writeString("1234") }

        assertFailsWith<IllegalArgumentException> {
            HealthBatteryBackupDocumentReader.readUtf8(source, maxDocumentBytes = 3)
        }
    }

    private fun emptyExportData() = HealthBatteryBackupExportData(emptyList(), emptyList(), emptyList(), emptyList())

    private suspend fun importFailure(repository: HealthBatteryBackupRepository, document: String): Exception? {
        return try {
            repository.importBackup(document)
            null
        } catch (e: Exception) {
            e
        }
    }

    private fun minute(timestamp: Long) = HealthDataEntity(timestamp, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun overlay(startTime: Long, type: Int) = OverlayDataEntity(startTime, 0, type, 0, 0, 0, 0, 0)

    private fun batteryHistory(
        id: Long = 0,
        serial: String = "SERIAL",
        timestampSeconds: Long = 1,
    ) = BatteryHistoryEntity(
        id = id,
        serial = serial,
        timestampSeconds = timestampSeconds,
        recordVersion = 1,
        socCentipercent = 2,
        socMinCentipercent = 3,
        socDropCentipercent = 4,
        voltageMv = 5,
        voltageDeltaMv = 6,
        temperatureMc = 7,
        currentUa = 8,
        tteSeconds = 9,
        chargeTimeMs = 10,
        dischargeDurationMs = 11,
        backlightOnTimeMs = 12,
        backlightAverageIntensityPercent = 13,
        vibratorOnTimeMs = 14,
        hrmOnTimeMs = 15,
        cpuRunningCentipercent = 16,
        taskCpuKernelMainCentipercent = 17,
        taskCpuKernelBackgroundCentipercent = 18,
        taskCpuWorkerCentipercent = 19,
        taskCpuAppCentipercent = 20,
        taskCpuBtHostCentipercent = 21,
        taskCpuBtControllerCentipercent = 22,
        taskCpuBtHciCentipercent = 23,
        taskCpuNewTimersCentipercent = 24,
        taskCpuPulseCentipercent = 25,
        taskCpuIdleCentipercent = 26,
        bleConnectedTimeMs = 27,
        bleExpectedTimeMs = 28,
        bleLatencyZeroTimeMs = 29,
        bleConnectionIntervalMinTimeMs = 30,
        bleConnectionIntervalMidTimeMs = 31,
        bleConnectionIntervalMaxTimeMs = 32,
        bleConnectionIntervalOtherTimeMs = 33,
        bleConnectionParameterUpdateCount = 34,
        watchfaceName = "Watchface",
        watchfaceUuid = "uuid",
        secondTickSubscribed = true,
    )

    private class RecordingDataSource : HealthBatteryBackupDataSource {
        var mergeCalls = 0

        override suspend fun read(): HealthBatteryBackupExportData =
            HealthBatteryBackupExportData(emptyList(), emptyList(), emptyList(), emptyList())

        override suspend fun merge(data: HealthBatteryBackupImportData): HealthBatteryBackupImportCounts {
            mergeCalls++
            return HealthBatteryBackupImportCounts(
                data.healthMinutes.size,
                data.overlays.size,
                data.batteryHistory.size,
            )
        }
    }
}
