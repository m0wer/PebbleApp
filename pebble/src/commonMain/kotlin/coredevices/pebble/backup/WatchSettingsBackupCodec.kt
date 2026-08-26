package coredevices.pebble.backup

import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.ActivityPrefsValue
import io.rebble.libpebblecommon.database.entity.ActivityPrefsValue.Companion.encodeToString
import io.rebble.libpebblecommon.database.entity.ColorWatchPref
import io.rebble.libpebblecommon.database.entity.EnumWatchPref
import io.rebble.libpebblecommon.database.entity.HeartRatePreferencesValue
import io.rebble.libpebblecommon.database.entity.HeartRatePreferencesValue.Companion.encodeToString
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntry
import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue
import io.rebble.libpebblecommon.database.entity.HrmPreferencesValue.Companion.encodeToString
import io.rebble.libpebblecommon.database.entity.NumberWatchPref
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import io.rebble.libpebblecommon.database.entity.RgbColorWatchPref
import io.rebble.libpebblecommon.database.entity.UnitsDistanceValue
import io.rebble.libpebblecommon.database.entity.UnitsDistanceValue.Companion.encodeToString
import io.rebble.libpebblecommon.database.entity.WatchPref
import io.rebble.libpebblecommon.database.entity.WatchPrefItem
import io.rebble.libpebblecommon.timeline.TimelineColor
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class WatchSettingsBackupExportData(
    val knownWatches: List<HealthBatteryBackupWatch>,
    val watchPrefs: List<WatchPrefItem>,
    val healthSettings: List<HealthSettingsEntry>,
    val weatherLocationUuids: List<Uuid>?,
)

data class WatchSettingsBackupImportData(
    val watchPrefs: List<WatchPrefItem>,
    val healthSettings: List<HealthSettingsEntry>,
    val weatherLocationUuids: List<Uuid>?,
)

object WatchSettingsBackupCodec {
    const val FORMAT = "pebble_watch_settings_backup"
    const val SCHEMA_VERSION = 1
    const val TIMESTAMP_UNIT = "milliseconds_since_unix_epoch"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(data: WatchSettingsBackupExportData, exportedAtEpochMilliseconds: Long): String {
        require(data.watchPrefs.map { it.id }.distinct().size == data.watchPrefs.size) {
            "Duplicate watch preference in database."
        }
        data.watchPrefs.forEach {
            validateWatchPref(WatchSettingsPrefV1(it.id, it.value, it.timestamp.instant.toEpochMilliseconds()))
        }
        require(data.healthSettings.map { it.id }.distinct().size == data.healthSettings.size) {
            "Duplicate health setting in database."
        }
        require(data.healthSettings.all { it.id in HEALTH_SETTINGS_IDS }) {
            "Unsupported health setting in database."
        }
        require(data.weatherLocationUuids?.distinct()?.size == data.weatherLocationUuids?.size) {
            "Duplicate weather location UUID in database."
        }
        val healthSettings = data.healthSettings.associateBy { it.id }
        return json.encodeToString(
            WatchSettingsBackupArchiveV1(
                format = FORMAT,
                schemaVersion = SCHEMA_VERSION,
                exportedAtEpochMilliseconds = exportedAtEpochMilliseconds,
                timestampUnit = TIMESTAMP_UNIT,
                knownWatches = data.knownWatches
                    .sortedWith(compareBy<HealthBatteryBackupWatch> { it.serial }.thenBy { it.name }.thenBy { it.firmwareVersion })
                    .map(::HealthBatteryBackupWatchV1),
                watchPrefs = data.watchPrefs.sortedBy { it.id }.map {
                    WatchSettingsPrefV1(it.id, it.value, it.timestamp.instant.toEpochMilliseconds())
                },
                healthSettings = WatchSettingsHealthSettingsV1(
                    activityPreferences = healthSettings[ACTIVITY_PREFERENCES]?.let {
                        TimedActivityPreferencesV1(
                            ActivityPrefsValue.fromString(it.value)
                                ?: error("Invalid activity preferences in database."),
                            it.timestamp.instant.toEpochMilliseconds(),
                        )
                    },
                    unitsDistance = healthSettings[UNITS_DISTANCE]?.let {
                        TimedUnitsDistanceV1(
                            UnitsDistanceValue.fromString(it.value) ?: error("Invalid distance units in database."),
                            it.timestamp.instant.toEpochMilliseconds(),
                        )
                    },
                    hrmPreferences = healthSettings[HRM_PREFERENCES]?.let {
                        TimedHrmPreferencesV1(
                            HrmPreferencesValue.fromString(it.value) ?: error("Invalid HRM preferences in database."),
                            it.timestamp.instant.toEpochMilliseconds(),
                        )
                    },
                    heartRatePreferences = healthSettings[HEART_RATE_PREFERENCES]?.let {
                        TimedHeartRatePreferencesV1(
                            HeartRatePreferencesValue.fromString(it.value)
                                ?: error("Invalid heart rate preferences in database."),
                            it.timestamp.instant.toEpochMilliseconds(),
                        )
                    },
                ),
                weatherApp = data.weatherLocationUuids?.let { WeatherAppPreferencesV1(it.map(Uuid::toString)) },
            )
        )
    }

    fun decode(document: String): WatchSettingsBackupImportData {
        val element = json.parseToJsonElement(document)
        val archive = json.decodeFromString<WatchSettingsBackupArchiveV1>(document)
        require(archive.format == FORMAT) { "Unsupported backup format." }
        require(archive.schemaVersion == SCHEMA_VERSION) { "Unsupported backup schema version." }
        require(archive.timestampUnit == TIMESTAMP_UNIT) { "Unsupported backup timestamp unit." }

        val ids = mutableSetOf<String>()
        val watchPrefs = archive.watchPrefs.map { pref ->
            require(ids.add(pref.id)) { "Duplicate watch preference: ${pref.id}" }
            validateWatchPref(pref)
            WatchPrefItem(pref.id, pref.encodedValue, Instant.fromEpochMilliseconds(pref.sourceTimestampEpochMilliseconds).asMillisecond())
        }
        validateHealthSettings(element.jsonObject)
        val weatherLocationUuids = archive.weatherApp?.locationUuids?.map(Uuid::parse)
        require(weatherLocationUuids?.distinct()?.size == weatherLocationUuids?.size) { "Duplicate weather location UUID." }
        val healthSettings = listOfNotNull(
            archive.healthSettings.activityPreferences?.let {
                HealthSettingsEntry(ACTIVITY_PREFERENCES, it.value.encodeToString(), it.sourceTimestamp())
            },
            archive.healthSettings.unitsDistance?.let {
                HealthSettingsEntry(UNITS_DISTANCE, it.value.encodeToString(), it.sourceTimestamp())
            },
            archive.healthSettings.hrmPreferences?.let {
                HealthSettingsEntry(HRM_PREFERENCES, it.value.encodeToString(), it.sourceTimestamp())
            },
            archive.healthSettings.heartRatePreferences?.let {
                HealthSettingsEntry(HEART_RATE_PREFERENCES, it.value.encodeToString(), it.sourceTimestamp())
            },
        )

        return WatchSettingsBackupImportData(
            watchPrefs = watchPrefs,
            healthSettings = healthSettings,
            weatherLocationUuids = weatherLocationUuids,
        )
    }

    private fun validateWatchPref(pref: WatchSettingsPrefV1) {
        val watchPref = WatchPref.from(pref.id)
        requireNotNull(watchPref) { "Unsupported watch preference: ${pref.id}" }
        when (watchPref) {
            is io.rebble.libpebblecommon.database.entity.BoolWatchPref ->
                require(pref.encodedValue == "0" || pref.encodedValue == "1") { "Invalid boolean preference value." }
            is EnumWatchPref -> {
                val code = pref.encodedValue.toUByteOrNull()
                require(code != null && watchPref.options.any { it.code == code }) { "Invalid enum preference value." }
            }
            is NumberWatchPref -> {
                val value = pref.encodedValue.toLongOrNull()
                require(value != null && value in watchPref.min.toLong()..watchPref.max.toLong()) { "Invalid numeric preference value." }
            }
            is QuicklaunchWatchPref -> QuickLaunchSetting.fromJson(pref.encodedValue)
            is RgbColorWatchPref -> {
                val rgb = pref.encodedValue.toUIntOrNull()
                require(rgb != null && rgb <= 0x00FFFFFFu) { "Invalid RGB preference value." }
            }
            is ColorWatchPref -> require(TimelineColor.findByName(pref.encodedValue) != null) { "Invalid color preference value." }
        }
    }

    private fun validateHealthSettings(archive: JsonObject) {
        val settings = archive["healthSettings"]?.jsonObject ?: error("Missing health settings.")
        validateTimedValue(settings, "activityPreferences", setOf(
            "heightMm", "weightDag", "trackingEnabled", "activityInsightsEnabled", "sleepInsightsEnabled", "ageYears", "gender",
        ))
        validateTimedValue(settings, "unitsDistance", setOf("imperialUnits"))
        validateTimedValue(settings, "hrmPreferences", setOf("enabled", "measurementInterval", "activityTrackingEnabled"))
        validateTimedValue(settings, "heartRatePreferences", setOf(
            "restingHr", "elevatedHr", "maxHr", "zone1Threshold", "zone2Threshold", "zone3Threshold",
        ))
    }

    private fun validateTimedValue(settings: JsonObject, key: String, requiredValueKeys: Set<String>) {
        val timedValue = settings[key] ?: error("Missing health setting: $key")
        if (timedValue.toString() == "null") return
        val value = timedValue.jsonObject["value"]?.jsonObject ?: error("Missing health setting value: $key")
        require(value.keys.containsAll(requiredValueKeys)) { "Incomplete health setting: $key" }
    }

    private const val ACTIVITY_PREFERENCES = "activityPreferences"
    private const val UNITS_DISTANCE = "unitsDistance"
    private const val HRM_PREFERENCES = "hrmPreferences"
    private const val HEART_RATE_PREFERENCES = "heartRatePreferences"
    private val HEALTH_SETTINGS_IDS = setOf(
        ACTIVITY_PREFERENCES,
        UNITS_DISTANCE,
        HRM_PREFERENCES,
        HEART_RATE_PREFERENCES,
    )
}

private fun TimedActivityPreferencesV1.sourceTimestamp() =
    Instant.fromEpochMilliseconds(sourceTimestampEpochMilliseconds).asMillisecond()

private fun TimedUnitsDistanceV1.sourceTimestamp() =
    Instant.fromEpochMilliseconds(sourceTimestampEpochMilliseconds).asMillisecond()

private fun TimedHrmPreferencesV1.sourceTimestamp() =
    Instant.fromEpochMilliseconds(sourceTimestampEpochMilliseconds).asMillisecond()

private fun TimedHeartRatePreferencesV1.sourceTimestamp() =
    Instant.fromEpochMilliseconds(sourceTimestampEpochMilliseconds).asMillisecond()

@Serializable
data class WatchSettingsBackupArchiveV1(
    val format: String,
    val schemaVersion: Int,
    val exportedAtEpochMilliseconds: Long,
    val timestampUnit: String,
    val knownWatches: List<HealthBatteryBackupWatchV1>,
    val watchPrefs: List<WatchSettingsPrefV1>,
    val healthSettings: WatchSettingsHealthSettingsV1,
    val weatherApp: WeatherAppPreferencesV1?,
)

@Serializable
data class WatchSettingsPrefV1(
    val id: String,
    val encodedValue: String,
    val sourceTimestampEpochMilliseconds: Long,
)

@Serializable
data class WatchSettingsHealthSettingsV1(
    val activityPreferences: TimedActivityPreferencesV1?,
    val unitsDistance: TimedUnitsDistanceV1?,
    val hrmPreferences: TimedHrmPreferencesV1?,
    val heartRatePreferences: TimedHeartRatePreferencesV1?,
)

@Serializable
data class TimedActivityPreferencesV1(val value: ActivityPrefsValue, val sourceTimestampEpochMilliseconds: Long)

@Serializable
data class TimedUnitsDistanceV1(val value: UnitsDistanceValue, val sourceTimestampEpochMilliseconds: Long)

@Serializable
data class TimedHrmPreferencesV1(val value: HrmPreferencesValue, val sourceTimestampEpochMilliseconds: Long)

@Serializable
data class TimedHeartRatePreferencesV1(val value: HeartRatePreferencesValue, val sourceTimestampEpochMilliseconds: Long)

@Serializable
data class WeatherAppPreferencesV1(val locationUuids: List<String>)
