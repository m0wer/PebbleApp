package coredevices.pebble.backup

import coredevices.database.BatteryHistoryEntity
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class HealthBatteryBackupExportData(
    val knownWatches: List<HealthBatteryBackupWatch>,
    val healthMinutes: List<HealthDataEntity>,
    val overlays: List<OverlayDataEntity>,
    val batteryHistory: List<BatteryHistoryEntity>,
)

data class HealthBatteryBackupImportData(
    val healthMinutes: List<HealthDataEntity>,
    val overlays: List<OverlayDataEntity>,
    val batteryHistory: List<BatteryHistoryEntity>,
)

data class HealthBatteryBackupWatch(
    val name: String,
    val serial: String,
    val firmwareVersion: String,
)

object HealthBatteryBackupCodec {
    const val FORMAT = "pebble_health_battery_backup"
    const val SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(data: HealthBatteryBackupExportData, exportedAtEpochSeconds: Long = Clock.System.now().epochSeconds): String {
        return json.encodeToString(
            HealthBatteryBackupArchiveV1(
                format = FORMAT,
                schemaVersion = SCHEMA_VERSION,
                exportedAtEpochSeconds = exportedAtEpochSeconds,
                timestampUnit = TIMESTAMP_UNIT,
                timezoneOffsetUnit = TIMEZONE_OFFSET_UNIT,
                knownWatches = data.knownWatches
                    .sortedWith(compareBy<HealthBatteryBackupWatch> { it.serial }.thenBy { it.name }.thenBy { it.firmwareVersion })
                    .map(::HealthBatteryBackupWatchV1),
                healthMinutes = data.healthMinutes.sortedBy { it.timestamp }.map(::HealthMinuteV1),
                overlays = data.overlays
                    .sortedWith(compareBy<OverlayDataEntity> { it.startTime }.thenBy { it.type })
                    .map(::HealthOverlayV1),
                batteryHistory = data.batteryHistory
                    .sortedWith(compareBy<BatteryHistoryEntity> { it.timestampSeconds }.thenBy { it.serial })
                    .map(::BatteryHistoryV1),
            )
        )
    }

    fun decode(document: String): HealthBatteryBackupImportData {
        val archive = json.decodeFromString<HealthBatteryBackupArchiveV1>(document)
        require(archive.format == FORMAT) { "Unsupported backup format." }
        require(archive.schemaVersion == SCHEMA_VERSION) { "Unsupported backup schema version." }
        require(archive.timestampUnit == TIMESTAMP_UNIT) { "Unsupported backup timestamp unit." }
        require(archive.timezoneOffsetUnit == TIMEZONE_OFFSET_UNIT) { "Unsupported backup timezone offset unit." }
        return HealthBatteryBackupImportData(
            healthMinutes = archive.healthMinutes.map(HealthMinuteV1::toEntity),
            overlays = archive.overlays.map(HealthOverlayV1::toEntity),
            batteryHistory = archive.batteryHistory.map(BatteryHistoryV1::toEntity),
        )
    }

    private const val TIMESTAMP_UNIT = "seconds_since_unix_epoch"
    private const val TIMEZONE_OFFSET_UNIT = "15_minute_intervals_from_utc"
}

@Serializable
data class HealthBatteryBackupArchiveV1(
    val format: String,
    val schemaVersion: Int,
    val exportedAtEpochSeconds: Long,
    val timestampUnit: String,
    val timezoneOffsetUnit: String,
    val knownWatches: List<HealthBatteryBackupWatchV1>,
    val healthMinutes: List<HealthMinuteV1>,
    val overlays: List<HealthOverlayV1>,
    val batteryHistory: List<BatteryHistoryV1>,
)

@Serializable
data class HealthBatteryBackupWatchV1(
    val name: String,
    val serial: String,
    val firmwareVersion: String,
) {
    constructor(watch: HealthBatteryBackupWatch) : this(watch.name, watch.serial, watch.firmwareVersion)
}

@Serializable
data class HealthMinuteV1(
    val timestamp: Long,
    val steps: Int,
    val orientation: Int,
    val intensity: Int,
    val lightIntensity: Int,
    val activeMinutes: Int,
    val restingGramCalories: Int,
    val activeGramCalories: Int,
    val distanceCm: Int,
    val heartRate: Int,
    val heartRateZone: Int,
    val heartRateWeight: Int,
    val pluggedIn: Int,
    val sleepIntentHint: Int,
    val timezoneOffset15Minutes: Int,
    val sleepScore: Long,
    val sleepFlags: Int,
) {
    constructor(data: HealthDataEntity) : this(
        data.timestamp, data.steps, data.orientation, data.intensity, data.lightIntensity,
        data.activeMinutes, data.restingGramCalories, data.activeGramCalories, data.distanceCm,
        data.heartRate, data.heartRateZone, data.heartRateWeight, data.pluggedIn,
        data.sleepIntentHint, data.timezoneOffset15Minutes, data.sleepScore, data.sleepFlags,
    )

    fun toEntity() = HealthDataEntity(
        timestamp = timestamp,
        steps = steps,
        orientation = orientation,
        intensity = intensity,
        lightIntensity = lightIntensity,
        activeMinutes = activeMinutes,
        restingGramCalories = restingGramCalories,
        activeGramCalories = activeGramCalories,
        distanceCm = distanceCm,
        heartRate = heartRate,
        heartRateZone = heartRateZone,
        heartRateWeight = heartRateWeight,
        pluggedIn = pluggedIn,
        sleepIntentHint = sleepIntentHint,
        timezoneOffset15Minutes = timezoneOffset15Minutes,
        sleepScore = sleepScore,
        sleepFlags = sleepFlags,
    )
}

@Serializable
data class HealthOverlayV1(
    val startTime: Long,
    val duration: Long,
    val type: Int,
    val steps: Int,
    val restingKiloCalories: Int,
    val activeKiloCalories: Int,
    val distanceCm: Int,
    val offsetUTC: Int,
) {
    constructor(data: OverlayDataEntity) : this(
        data.startTime, data.duration, data.type, data.steps, data.restingKiloCalories,
        data.activeKiloCalories, data.distanceCm, data.offsetUTC,
    )

    fun toEntity() = OverlayDataEntity(
        startTime = startTime,
        duration = duration,
        type = type,
        steps = steps,
        restingKiloCalories = restingKiloCalories,
        activeKiloCalories = activeKiloCalories,
        distanceCm = distanceCm,
        offsetUTC = offsetUTC,
    )
}

@Serializable
data class BatteryHistoryV1(
    val serial: String,
    val timestampSeconds: Long,
    val recordVersion: Int,
    val socCentipercent: Int,
    val socMinCentipercent: Int,
    val socDropCentipercent: Int,
    val voltageMv: Int,
    val voltageDeltaMv: Int,
    val temperatureMc: Int,
    val currentUa: Int,
    val tteSeconds: Long,
    val chargeTimeMs: Long,
    val dischargeDurationMs: Long,
    val backlightOnTimeMs: Long,
    val backlightAverageIntensityPercent: Int,
    val vibratorOnTimeMs: Long,
    val hrmOnTimeMs: Long,
    val cpuRunningCentipercent: Int,
    val taskCpuKernelMainCentipercent: Int,
    val taskCpuKernelBackgroundCentipercent: Int,
    val taskCpuWorkerCentipercent: Int,
    val taskCpuAppCentipercent: Int,
    val taskCpuBtHostCentipercent: Int,
    val taskCpuBtControllerCentipercent: Int,
    val taskCpuBtHciCentipercent: Int,
    val taskCpuNewTimersCentipercent: Int,
    val taskCpuPulseCentipercent: Int,
    val taskCpuIdleCentipercent: Int,
    val bleConnectedTimeMs: Long,
    val bleExpectedTimeMs: Long,
    val bleLatencyZeroTimeMs: Long,
    val bleConnectionIntervalMinTimeMs: Long,
    val bleConnectionIntervalMidTimeMs: Long,
    val bleConnectionIntervalMaxTimeMs: Long,
    val bleConnectionIntervalOtherTimeMs: Long,
    val bleConnectionParameterUpdateCount: Long,
    val watchfaceName: String,
    val watchfaceUuid: String,
    val secondTickSubscribed: Boolean,
) {
    constructor(data: BatteryHistoryEntity) : this(
        data.serial, data.timestampSeconds, data.recordVersion, data.socCentipercent,
        data.socMinCentipercent, data.socDropCentipercent, data.voltageMv, data.voltageDeltaMv,
        data.temperatureMc, data.currentUa, data.tteSeconds, data.chargeTimeMs,
        data.dischargeDurationMs, data.backlightOnTimeMs, data.backlightAverageIntensityPercent,
        data.vibratorOnTimeMs, data.hrmOnTimeMs, data.cpuRunningCentipercent,
        data.taskCpuKernelMainCentipercent, data.taskCpuKernelBackgroundCentipercent,
        data.taskCpuWorkerCentipercent, data.taskCpuAppCentipercent, data.taskCpuBtHostCentipercent,
        data.taskCpuBtControllerCentipercent, data.taskCpuBtHciCentipercent,
        data.taskCpuNewTimersCentipercent, data.taskCpuPulseCentipercent, data.taskCpuIdleCentipercent,
        data.bleConnectedTimeMs, data.bleExpectedTimeMs, data.bleLatencyZeroTimeMs,
        data.bleConnectionIntervalMinTimeMs, data.bleConnectionIntervalMidTimeMs,
        data.bleConnectionIntervalMaxTimeMs, data.bleConnectionIntervalOtherTimeMs,
        data.bleConnectionParameterUpdateCount, data.watchfaceName, data.watchfaceUuid,
        data.secondTickSubscribed,
    )

    fun toEntity() = BatteryHistoryEntity(
        id = 0,
        serial = serial,
        timestampSeconds = timestampSeconds,
        recordVersion = recordVersion,
        socCentipercent = socCentipercent,
        socMinCentipercent = socMinCentipercent,
        socDropCentipercent = socDropCentipercent,
        voltageMv = voltageMv,
        voltageDeltaMv = voltageDeltaMv,
        temperatureMc = temperatureMc,
        currentUa = currentUa,
        tteSeconds = tteSeconds,
        chargeTimeMs = chargeTimeMs,
        dischargeDurationMs = dischargeDurationMs,
        backlightOnTimeMs = backlightOnTimeMs,
        backlightAverageIntensityPercent = backlightAverageIntensityPercent,
        vibratorOnTimeMs = vibratorOnTimeMs,
        hrmOnTimeMs = hrmOnTimeMs,
        cpuRunningCentipercent = cpuRunningCentipercent,
        taskCpuKernelMainCentipercent = taskCpuKernelMainCentipercent,
        taskCpuKernelBackgroundCentipercent = taskCpuKernelBackgroundCentipercent,
        taskCpuWorkerCentipercent = taskCpuWorkerCentipercent,
        taskCpuAppCentipercent = taskCpuAppCentipercent,
        taskCpuBtHostCentipercent = taskCpuBtHostCentipercent,
        taskCpuBtControllerCentipercent = taskCpuBtControllerCentipercent,
        taskCpuBtHciCentipercent = taskCpuBtHciCentipercent,
        taskCpuNewTimersCentipercent = taskCpuNewTimersCentipercent,
        taskCpuPulseCentipercent = taskCpuPulseCentipercent,
        taskCpuIdleCentipercent = taskCpuIdleCentipercent,
        bleConnectedTimeMs = bleConnectedTimeMs,
        bleExpectedTimeMs = bleExpectedTimeMs,
        bleLatencyZeroTimeMs = bleLatencyZeroTimeMs,
        bleConnectionIntervalMinTimeMs = bleConnectionIntervalMinTimeMs,
        bleConnectionIntervalMidTimeMs = bleConnectionIntervalMidTimeMs,
        bleConnectionIntervalMaxTimeMs = bleConnectionIntervalMaxTimeMs,
        bleConnectionIntervalOtherTimeMs = bleConnectionIntervalOtherTimeMs,
        bleConnectionParameterUpdateCount = bleConnectionParameterUpdateCount,
        watchfaceName = watchfaceName,
        watchfaceUuid = watchfaceUuid,
        secondTickSubscribed = secondTickSubscribed,
    )
}
