package coredevices.pebble.backup

import coredevices.util.CloudTranscriptionProvider
import coredevices.util.CoreConfig
import coredevices.util.OpenAITranscriptionConfig
import coredevices.util.STTConfig
import coredevices.util.WeatherUnit
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.WatchConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import theme.CoreAppTheme

class AppSettingsBackupRepositoryTest {
    @Test
    fun codecIsDeterministicAndRoundTripsAllIncludedSettings() {
        val data = exportData()

        val document = AppSettingsBackupCodec.encode(data, exportedAtEpochMilliseconds = 99)
        val decoded = AppSettingsBackupCodec.decode(document)

        assertEquals(document, AppSettingsBackupCodec.encode(data, exportedAtEpochMilliseconds = 99))
        assertEquals(data.coreConfig.copy(indexPermissionsConfirmed = false), decoded.coreConfig)
        assertEquals(data.libPebbleConfig, decoded.libPebbleConfig)
        assertEquals(data.theme, decoded.theme)
        assertEquals(data.enableMemfaultUploads, decoded.enableMemfaultUploads)
        assertEquals(data.enableFirebaseUploads, decoded.enableFirebaseUploads)
        assertEquals(data.enableMixpanelUploads, decoded.enableMixpanelUploads)
        assertEquals(data.showDebugOptions, decoded.showDebugOptions)
        assertEquals(data.enableExperimentalDevices, decoded.enableExperimentalDevices)
        assertEquals(data.healthSyncEnabled, decoded.healthSyncEnabled)
        assertFalse(document.contains("indexPermissionsConfirmed"))
        assertFalse(document.contains("apiKey"))
        assertFalse(document.contains("lastSynced"))
        assertFalse(document.contains("token"))
    }

    @Test
    fun invalidDocumentsAreRejectedBeforeReplace() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = AppSettingsBackupRepository(dataSource, fixedClock)
        val valid = AppSettingsBackupCodec.encode(exportData(), 1)

        val invalidDocuments = listOf(
            "format" to valid.replace(AppSettingsBackupCodec.FORMAT, "wrong_format"),
            "schema" to valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            "timestamp unit" to valid.replace(
                "\"timestampUnit\": \"milliseconds_since_unix_epoch\"",
                "\"timestampUnit\": \"seconds_since_unix_epoch\"",
            ),
            "duration unit" to valid.replace("\"durationUnit\": \"milliseconds\"", "\"durationUnit\": \"seconds\""),
            "theme" to valid.replace("\"themeKey\": \"black\"", "\"themeKey\": \"unknown\""),
            "boolean type" to valid.replace("\"enableMemfaultUploads\": false", "\"enableMemfaultUploads\": \"false\""),
            "full sync interval" to valid.replace("\"regularSyncIntervalMilliseconds\": 120000", "\"regularSyncIntervalMilliseconds\": 0"),
            "weather sync interval" to valid.replace("\"weatherSyncIntervalMilliseconds\": 60000", "\"weatherSyncIntervalMilliseconds\": -1"),
            "locker limit" to valid.replace("\"lockerSyncLimitV2\": 3", "\"lockerSyncLimitV2\": -1"),
            "locker limit type" to valid.replace("\"lockerSyncLimitV2\": 3", "\"lockerSyncLimitV2\": \"3\""),
            "notification retention" to valid.replace("\"storeNotifiationsForDays\": 2", "\"storeNotifiationsForDays\": -1"),
        ).forEach { document ->
            assertTrue(document.second != valid, "${document.first} document was not modified")
            assertFailsWith<Exception>(document.first) { repository.importBackup(document.second) }
        }

        assertEquals(0, dataSource.replaceCalls)
    }

    @Test
    fun importCallsOneReplaceWithValidatedSettings() = runBlocking {
        val dataSource = RecordingDataSource()
        val repository = AppSettingsBackupRepository(dataSource, fixedClock)
        val document = AppSettingsBackupCodec.encode(exportData(), 1)

        repository.importBackup(document)

        assertEquals(1, dataSource.replaceCalls)
        assertEquals(AppSettingsBackupCodec.decode(document), dataSource.replacedData)
    }

    @Test
    fun targetIndexConsentControlsRestoredIndexPreference() {
        val requested = AppSettingsBackupCodec.decode(
            AppSettingsBackupCodec.encode(exportData().copy(coreConfig = exportData().coreConfig.copy(enableIndex = true)), 1)
        )

        val withoutConsent = restoreCoreConfig(requested.coreConfig, targetIndexPermissionsConfirmed = false)
        val withConsent = restoreCoreConfig(requested.coreConfig, targetIndexPermissionsConfirmed = true)

        assertFalse(withoutConsent.enableIndex)
        assertFalse(withoutConsent.indexPermissionsConfirmed)
        assertTrue(withConsent.enableIndex)
        assertTrue(withConsent.indexPermissionsConfirmed)
    }

    private fun exportData() = AppSettingsBackupExportData(
        coreConfig = CoreConfig(
            ignoreOtherPebbleApps = true,
            disableCompanionDeviceManager = true,
            weatherPinsV2 = false,
            fetchWeather = false,
            disableFirmwareUpdateNotifications = true,
            enableIndex = true,
            indexPermissionsConfirmed = true,
            weatherUnits = WeatherUnit.Imperial,
            showAllSettingsTab = true,
            sttConfig = STTConfig(
                mode = CactusSTTMode.LocalFirst,
                modelName = "tiny.en",
                spokenLanguage = "en",
                cloudProvider = CloudTranscriptionProvider.OpenAI,
                openAI = OpenAITranscriptionConfig(
                    endpoint = "https://example.com/v1/audio/transcriptions",
                    model = "gpt-4o-mini-transcribe",
                    prompt = "Prefer Pebble names",
                ),
            ),
            interceptPKJSWeather = false,
            regularSyncInterval = 2.minutes,
            weatherSyncInterval = 1.minutes,
            preferHealthTab = false,
            obfuscateSensitiveLogs = false,
            hidePermissionWarningBadges = true,
            androidForegroundServiceForWatchConnectionV2 = false,
            showWatchConnectionDebugInfo = true,
            notifyWatchFullyCharged = false,
            useEngDashOta = false,
        ),
        libPebbleConfig = LibPebbleConfig(
            watchConfig = WatchConfig(lockerSyncLimitV2 = 3, calendarPins = false),
            notificationConfig = NotificationConfig(storeNotifiationsForDays = 2, sendNotifications = false),
        ),
        theme = CoreAppTheme.Black,
        enableMemfaultUploads = false,
        enableFirebaseUploads = false,
        enableMixpanelUploads = false,
        showDebugOptions = true,
        enableExperimentalDevices = true,
        healthSyncEnabled = true,
    )

    private class RecordingDataSource : AppSettingsBackupDataSource {
        var replaceCalls = 0
        var replacedData: AppSettingsBackupImportData? = null

        override fun read(): AppSettingsBackupExportData = error("Not needed by this test")

        override fun replace(data: AppSettingsBackupImportData) {
            replaceCalls++
            replacedData = data
        }
    }

    private companion object {
        val fixedClock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(10)
        }
    }
}
