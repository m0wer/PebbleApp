package coredevices.pebble.backup

import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.ActivityPrefsValue
import io.rebble.libpebblecommon.database.entity.HeartRatePreferencesValue
import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue
import io.rebble.libpebblecommon.database.entity.UnitsDistanceValue
import io.rebble.libpebblecommon.database.entity.WatchPrefItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WatchSettingsBackupRepositoryTest {
    @Test
    fun codecIsDeterministicAndRoundTripsSelectedFields() {
        val data = WatchSettingsBackupExportData(
            knownWatches = listOf(
                HealthBatteryBackupWatch("Second", "B", "v2"),
                HealthBatteryBackupWatch("First", "A", "v1"),
            ),
            watchPrefs = listOf(
                WatchPrefItem("clock24h", "1", Instant.fromEpochMilliseconds(2).asMillisecond()),
                WatchPrefItem("lightTimeoutMs", "3000", Instant.fromEpochMilliseconds(1).asMillisecond()),
            ),
            healthSettings = emptyList(),
            weatherLocationUuids = listOf(Uuid.parse("12345678-1234-1234-1234-123456789abc")),
        )
        val document = WatchSettingsBackupCodec.encode(data, exportedAtEpochMilliseconds = 99)
        val decoded = WatchSettingsBackupCodec.decode(document)

        assertEquals(document, WatchSettingsBackupCodec.encode(data, exportedAtEpochMilliseconds = 99))
        assertTrue(document.indexOf("\"serial\": \"A\"") < document.indexOf("\"serial\": \"B\""))
        assertEquals(data.watchPrefs.sortedBy { it.id }, decoded.watchPrefs)
        assertEquals(data.weatherLocationUuids, decoded.weatherLocationUuids)
        assertTrue(decoded.healthSettings.isEmpty())
    }

    @Test
    fun codecRoundTripsTypedHealthSettings() {
        val data = WatchSettingsBackupExportData(
            knownWatches = emptyList(),
            watchPrefs = emptyList(),
            healthSettings = listOf(
                health("activityPreferences", ActivityPrefsValue(ageYears = 42)),
                health("unitsDistance", UnitsDistanceValue(imperialUnits = true)),
                health("hrmPreferences", HrmPreferencesValue(enabled = false)),
                health("heartRatePreferences", HeartRatePreferencesValue(maxHr = 180)),
            ),
            weatherLocationUuids = null,
        )

        val decoded = WatchSettingsBackupCodec.decode(WatchSettingsBackupCodec.encode(data, 1))

        assertEquals(data.healthSettings.sortedBy { it.id }, decoded.healthSettings.sortedBy { it.id })
    }

    @Test
    fun invalidDocumentsAreRejectedBeforeMerge() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = WatchSettingsBackupRepository(dataSource, fixedClock)
        val valid = WatchSettingsBackupCodec.encode(emptyExportData(), 1)

        listOf(
            valid.replace(WatchSettingsBackupCodec.FORMAT, "wrong_format"),
            valid.replace("\"timestampUnit\": \"milliseconds_since_unix_epoch\"", "\"timestampUnit\": \"seconds_since_unix_epoch\""),
            valid.replace("\"watchPrefs\": []", "\"watchPrefs\": [{\"id\": \"clock24h\", \"encodedValue\": \"2\", \"sourceTimestampEpochMilliseconds\": 1}]"),
            valid.replace("\"watchPrefs\": []", "\"watchPrefs\": [{\"id\": \"unknown\", \"encodedValue\": \"1\", \"sourceTimestampEpochMilliseconds\": 1}]"),
            valid.replace("\"watchPrefs\": []", "\"watchPrefs\": [{\"id\": \"clock24h\", \"encodedValue\": \"1\", \"sourceTimestampEpochMilliseconds\": 1}, {\"id\": \"clock24h\", \"encodedValue\": \"1\", \"sourceTimestampEpochMilliseconds\": 2}]"),
            valid.replace("\"activityPreferences\": null", "\"activityPreferences\": {\"value\": {\"ageYears\": 42}, \"sourceTimestampEpochMilliseconds\": 1}"),
            valid.replace("\"weatherApp\": null", "\"weatherApp\": {\"locationUuids\": [\"not-a-uuid\"]}"),
            valid.replace("\"weatherApp\": null", "\"weatherApp\": {\"locationUuids\": [\"12345678-1234-1234-1234-123456789abc\", \"12345678-1234-1234-1234-123456789abc\"]}"),
        ).forEach { document ->
            assertFailsWith<Exception> { repository.importBackup(document) }
        }

        assertEquals(0, dataSource.mergeCalls)
    }

    @Test
    fun importCallsOneMergeWithDecodedRecords() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = WatchSettingsBackupRepository(dataSource, fixedClock)
        val document = WatchSettingsBackupCodec.encode(
            emptyExportData().copy(
                watchPrefs = listOf(WatchPrefItem("clock24h", "1", Instant.fromEpochMilliseconds(1).asMillisecond())),
            ),
            1,
        )

        assertEquals(WatchSettingsBackupImportCounts(1, 0, 0), repository.importBackup(document))
        assertEquals(1, dataSource.mergeCalls)
    }

    private fun health(id: String, value: Any) = when (value) {
        is ActivityPrefsValue -> io.rebble.libpebblecommon.database.entity.HealthSettingsEntry(
            id, with(ActivityPrefsValue.Companion) { value.encodeToString() }, Instant.fromEpochMilliseconds(1).asMillisecond(),
        )
        is UnitsDistanceValue -> io.rebble.libpebblecommon.database.entity.HealthSettingsEntry(
            id, with(UnitsDistanceValue.Companion) { value.encodeToString() }, Instant.fromEpochMilliseconds(1).asMillisecond(),
        )
        is HrmPreferencesValue -> io.rebble.libpebblecommon.database.entity.HealthSettingsEntry(
            id, with(HrmPreferencesValue.Companion) { value.encodeToString() }, Instant.fromEpochMilliseconds(1).asMillisecond(),
        )
        is HeartRatePreferencesValue -> io.rebble.libpebblecommon.database.entity.HealthSettingsEntry(
            id, with(HeartRatePreferencesValue.Companion) { value.encodeToString() }, Instant.fromEpochMilliseconds(1).asMillisecond(),
        )
        else -> error("Unsupported health value")
    }

    private fun emptyExportData() = WatchSettingsBackupExportData(emptyList(), emptyList(), emptyList(), null)

    private class RecordingDataSource : WatchSettingsBackupDataSource {
        var mergeCalls = 0

        override suspend fun read() = WatchSettingsBackupExportData(emptyList(), emptyList(), emptyList(), null)

        override suspend fun merge(data: WatchSettingsBackupImportData): WatchSettingsBackupImportCounts {
            mergeCalls++
            return WatchSettingsBackupImportCounts(
                data.watchPrefs.size,
                data.healthSettings.size,
                data.weatherLocationUuids?.size ?: 0,
            )
        }
    }

    private companion object {
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(10)
        }
    }
}
