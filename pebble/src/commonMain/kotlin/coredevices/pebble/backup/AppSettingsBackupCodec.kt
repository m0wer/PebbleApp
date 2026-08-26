package coredevices.pebble.backup

import coredevices.util.CloudTranscriptionProvider
import coredevices.util.CoreConfig
import coredevices.util.OpenAITranscriptionConfig
import coredevices.util.STTConfig
import coredevices.util.WeatherUnit
import coredevices.util.models.CactusSTTMode
import io.rebble.libpebblecommon.LibPebbleConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import theme.CoreAppTheme
import kotlin.time.Duration.Companion.milliseconds

data class AppSettingsBackupExportData(
    val coreConfig: CoreConfig,
    val libPebbleConfig: LibPebbleConfig,
    val theme: CoreAppTheme,
    val enableMemfaultUploads: Boolean,
    val enableFirebaseUploads: Boolean,
    val enableMixpanelUploads: Boolean,
    val showDebugOptions: Boolean,
    val enableExperimentalDevices: Boolean,
    val healthSyncEnabled: Boolean,
)

data class AppSettingsBackupImportData(
    val coreConfig: CoreConfig,
    val libPebbleConfig: LibPebbleConfig,
    val theme: CoreAppTheme,
    val enableMemfaultUploads: Boolean,
    val enableFirebaseUploads: Boolean,
    val enableMixpanelUploads: Boolean,
    val showDebugOptions: Boolean,
    val enableExperimentalDevices: Boolean,
    val healthSyncEnabled: Boolean,
)

object AppSettingsBackupCodec {
    const val FORMAT = "pebble_app_settings_backup"
    const val SCHEMA_VERSION = 1
    const val TIMESTAMP_UNIT = "milliseconds_since_unix_epoch"
    const val DURATION_UNIT = "milliseconds"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(data: AppSettingsBackupExportData, exportedAtEpochMilliseconds: Long): String {
        validateCoreConfig(data.coreConfig)
        validateLibPebbleConfig(data.libPebbleConfig)
        return json.encodeToString(
            AppSettingsBackupArchiveV1(
                format = FORMAT,
                schemaVersion = SCHEMA_VERSION,
                exportedAtEpochMilliseconds = exportedAtEpochMilliseconds,
                timestampUnit = TIMESTAMP_UNIT,
                durationUnit = DURATION_UNIT,
                coreConfig = CoreConfigBackupV1.from(data.coreConfig),
                libPebbleConfig = data.libPebbleConfig,
                themeKey = data.theme.key,
                standaloneSettings = AppSettingsStandaloneV1(
                    enableMemfaultUploads = data.enableMemfaultUploads,
                    enableFirebaseUploads = data.enableFirebaseUploads,
                    enableMixpanelUploads = data.enableMixpanelUploads,
                    showDebugOptions = data.showDebugOptions,
                    enableExperimentalDevices = data.enableExperimentalDevices,
                    healthSyncEnabled = data.healthSyncEnabled,
                ),
            )
        )
    }

    fun decode(document: String): AppSettingsBackupImportData {
        val element = json.parseToJsonElement(document)
        val archive = json.decodeFromString<AppSettingsBackupArchiveV1>(document)
        validateKnownJsonTypes(element, json.parseToJsonElement(json.encodeToString(archive)))
        require(archive.format == FORMAT) { "Unsupported backup format." }
        require(archive.schemaVersion == SCHEMA_VERSION) { "Unsupported backup schema version." }
        require(archive.timestampUnit == TIMESTAMP_UNIT) { "Unsupported backup timestamp unit." }
        require(archive.durationUnit == DURATION_UNIT) { "Unsupported backup duration unit." }
        val coreConfig = archive.coreConfig.toCoreConfig()
        val theme = CoreAppTheme.entries.firstOrNull { it.key == archive.themeKey }
            ?: error("Unknown theme key.")
        validateCoreConfig(coreConfig)
        validateLibPebbleConfig(archive.libPebbleConfig)
        return AppSettingsBackupImportData(
            coreConfig = coreConfig,
            libPebbleConfig = archive.libPebbleConfig,
            theme = theme,
            enableMemfaultUploads = archive.standaloneSettings.enableMemfaultUploads,
            enableFirebaseUploads = archive.standaloneSettings.enableFirebaseUploads,
            enableMixpanelUploads = archive.standaloneSettings.enableMixpanelUploads,
            showDebugOptions = archive.standaloneSettings.showDebugOptions,
            enableExperimentalDevices = archive.standaloneSettings.enableExperimentalDevices,
            healthSyncEnabled = archive.standaloneSettings.healthSyncEnabled,
        )
    }

    private fun validateKnownJsonTypes(actual: JsonElement, expected: JsonElement) {
        when (expected) {
            is JsonObject -> {
                val actualObject = actual as? JsonObject ?: error("Expected JSON object.")
                expected.forEach { (key, expectedValue) ->
                    validateKnownJsonTypes(actualObject[key] ?: error("Missing setting: $key"), expectedValue)
                }
            }
            is JsonArray -> {
                val actualArray = actual as? JsonArray ?: error("Expected JSON array.")
                if (expected.isNotEmpty()) {
                    actualArray.forEach { validateKnownJsonTypes(it, expected.first()) }
                }
            }
            is JsonNull -> require(actual is JsonNull) { "Expected JSON null." }
            is JsonPrimitive -> {
                val actualPrimitive = actual as? JsonPrimitive ?: error("Expected JSON primitive.")
                when {
                    expected.isString -> require(actualPrimitive.isString) { "Expected JSON string." }
                    expected.booleanOrNull != null -> require(
                        !actualPrimitive.isString && actualPrimitive.booleanOrNull != null
                    ) { "Expected JSON boolean." }
                    else -> require(!actualPrimitive.isString && actualPrimitive.content.toLongOrNull() != null) {
                        "Expected JSON integer."
                    }
                }
            }
        }
    }

    private fun validateCoreConfig(config: CoreConfig) {
        require(config.regularSyncInterval.isPositive()) { "Full sync interval must be positive." }
        require(config.weatherSyncInterval.isPositive()) { "Weather sync interval must be positive." }
    }

    private fun validateLibPebbleConfig(config: LibPebbleConfig) {
        require(config.watchConfig.lockerSyncLimitV2 >= 0) { "Locker sync limit must not be negative." }
        require(config.notificationConfig.storeNotifiationsForDays >= 0) {
            "Notification retention must not be negative."
        }
    }
}

@Serializable
data class AppSettingsBackupArchiveV1(
    val format: String,
    val schemaVersion: Int,
    val exportedAtEpochMilliseconds: Long,
    val timestampUnit: String,
    val durationUnit: String,
    val coreConfig: CoreConfigBackupV1,
    val libPebbleConfig: LibPebbleConfig,
    val themeKey: String,
    val standaloneSettings: AppSettingsStandaloneV1,
)

/** CoreConfig v1 deliberately omits target-device index consent. Durations use [AppSettingsBackupCodec.DURATION_UNIT]. */
@Serializable
data class CoreConfigBackupV1(
    val ignoreOtherPebbleApps: Boolean,
    val disableCompanionDeviceManager: Boolean,
    val weatherPinsV2: Boolean,
    val fetchWeather: Boolean,
    val disableFirmwareUpdateNotifications: Boolean,
    val enableIndex: Boolean,
    val weatherUnitCode: String?,
    val showAllSettingsTab: Boolean,
    val sttConfig: SttConfigBackupV1,
    val interceptPKJSWeather: Boolean,
    val regularSyncIntervalMilliseconds: Long,
    val weatherSyncIntervalMilliseconds: Long,
    val preferHealthTab: Boolean,
    val obfuscateSensitiveLogs: Boolean,
    val hidePermissionWarningBadges: Boolean,
    val androidForegroundServiceForWatchConnectionV2: Boolean,
    val showWatchConnectionDebugInfo: Boolean,
    val notifyWatchFullyCharged: Boolean,
    val useEngDashOta: Boolean,
) {
    fun toCoreConfig(): CoreConfig {
        val weatherUnit = weatherUnitCode?.let { code ->
            WeatherUnit.entries.firstOrNull { it.code == code } ?: error("Unknown weather unit.")
        }
        val mode = CactusSTTMode.entries.firstOrNull { it.id == sttConfig.modeId }
            ?: error("Unknown STT mode.")
        val provider = CloudTranscriptionProvider.entries.firstOrNull { it.name == sttConfig.cloudProvider }
            ?: error("Unknown transcription provider.")
        return CoreConfig(
            ignoreOtherPebbleApps = ignoreOtherPebbleApps,
            disableCompanionDeviceManager = disableCompanionDeviceManager,
            weatherPinsV2 = weatherPinsV2,
            fetchWeather = fetchWeather,
            disableFirmwareUpdateNotifications = disableFirmwareUpdateNotifications,
            enableIndex = enableIndex,
            weatherUnits = weatherUnit,
            showAllSettingsTab = showAllSettingsTab,
            sttConfig = STTConfig(
                mode = mode,
                modelName = sttConfig.modelName,
                spokenLanguage = sttConfig.spokenLanguage,
                cloudProvider = provider,
                openAI = OpenAITranscriptionConfig(
                    endpoint = sttConfig.openAI.endpoint,
                    model = sttConfig.openAI.model,
                    prompt = sttConfig.openAI.prompt,
                ),
            ),
            interceptPKJSWeather = interceptPKJSWeather,
            regularSyncInterval = regularSyncIntervalMilliseconds.milliseconds,
            weatherSyncInterval = weatherSyncIntervalMilliseconds.milliseconds,
            preferHealthTab = preferHealthTab,
            obfuscateSensitiveLogs = obfuscateSensitiveLogs,
            hidePermissionWarningBadges = hidePermissionWarningBadges,
            androidForegroundServiceForWatchConnectionV2 = androidForegroundServiceForWatchConnectionV2,
            showWatchConnectionDebugInfo = showWatchConnectionDebugInfo,
            notifyWatchFullyCharged = notifyWatchFullyCharged,
            useEngDashOta = useEngDashOta,
        )
    }

    companion object {
        fun from(config: CoreConfig) = CoreConfigBackupV1(
            ignoreOtherPebbleApps = config.ignoreOtherPebbleApps,
            disableCompanionDeviceManager = config.disableCompanionDeviceManager,
            weatherPinsV2 = config.weatherPinsV2,
            fetchWeather = config.fetchWeather,
            disableFirmwareUpdateNotifications = config.disableFirmwareUpdateNotifications,
            enableIndex = config.enableIndex,
            weatherUnitCode = config.weatherUnits?.code,
            showAllSettingsTab = config.showAllSettingsTab,
            sttConfig = SttConfigBackupV1.from(config.sttConfig),
            interceptPKJSWeather = config.interceptPKJSWeather,
            regularSyncIntervalMilliseconds = config.regularSyncInterval.inWholeMilliseconds,
            weatherSyncIntervalMilliseconds = config.weatherSyncInterval.inWholeMilliseconds,
            preferHealthTab = config.preferHealthTab,
            obfuscateSensitiveLogs = config.obfuscateSensitiveLogs,
            hidePermissionWarningBadges = config.hidePermissionWarningBadges,
            androidForegroundServiceForWatchConnectionV2 = config.androidForegroundServiceForWatchConnectionV2,
            showWatchConnectionDebugInfo = config.showWatchConnectionDebugInfo,
            notifyWatchFullyCharged = config.notifyWatchFullyCharged,
            useEngDashOta = config.useEngDashOta,
        )
    }
}

@Serializable
data class SttConfigBackupV1(
    val modeId: Int,
    val modelName: String?,
    val spokenLanguage: String?,
    val cloudProvider: String,
    val openAI: OpenAITranscriptionConfigBackupV1,
) {
    companion object {
        fun from(config: STTConfig) = SttConfigBackupV1(
            modeId = config.mode.id,
            modelName = config.modelName,
            spokenLanguage = config.spokenLanguage,
            cloudProvider = config.cloudProvider.name,
            openAI = OpenAITranscriptionConfigBackupV1(
                endpoint = config.openAI.endpoint,
                model = config.openAI.model,
                prompt = config.openAI.prompt,
            ),
        )
    }
}

@Serializable
data class OpenAITranscriptionConfigBackupV1(
    val endpoint: String,
    val model: String,
    val prompt: String,
)

@Serializable
data class AppSettingsStandaloneV1(
    val enableMemfaultUploads: Boolean,
    val enableFirebaseUploads: Boolean,
    val enableMixpanelUploads: Boolean,
    val showDebugOptions: Boolean,
    val enableExperimentalDevices: Boolean,
    val healthSyncEnabled: Boolean,
)
