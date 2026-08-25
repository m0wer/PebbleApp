package coredevices.pebble.health

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HealthDataExporter(
    private val libPebble: LibPebble,
) {
    suspend fun export(): String = HealthDataExportFormatter.format(
        libPebble.getAllHealthData(),
        libPebble.getAllOverlayEntries(),
        libPebble.getKnownWatches(),
    )
}

object HealthDataExportFormatter {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun format(
        minutes: List<HealthDataEntity>,
        overlays: List<OverlayDataEntity>,
        watches: List<KnownWatchItem>,
    ): String = json.encodeToString(
        HealthDataExport(
            minutes = minutes.sortedBy { it.timestamp }.map(::MinuteExport),
            overlays = overlays.sortedWith(compareBy<OverlayDataEntity> { it.startTime }.thenBy { it.type })
                .map(::OverlayExport),
            watches = watches.sortedWith(
                compareBy<KnownWatchItem> { it.serial }.thenBy { it.name }.thenBy { it.runningFwVersion }
            ).map { WatchExport(it.name, it.serial, it.runningFwVersion) },
        )
    )
}

@Serializable
private data class HealthDataExport(
    val schemaVersion: Int = 1,
    val timestampUnit: String = "seconds_since_unix_epoch",
    val timezoneOffsetUnit: String = "15_minute_intervals_from_utc",
    val watches: List<WatchExport>,
    val minutes: List<MinuteExport>,
    val overlays: List<OverlayExport>,
)

@Serializable
private data class WatchExport(val name: String, val serial: String, val firmwareVersion: String)

@Serializable
private data class MinuteExport(
    val timestamp: Long,
    val timezoneOffset15Minutes: Int,
    val steps: Int,
    val orientation: Int,
    val intensity: Int,
    val lightIntensity: Int,
    val activeMinutes: Int,
    val pluggedIn: Int,
    val sleepIntentHint: Int,
    val restingGramCalories: Int,
    val activeGramCalories: Int,
    val distanceCm: Int,
    val heartRate: Int,
    val heartRateWeight: Int,
    val heartRateZone: Int,
) {
    constructor(data: HealthDataEntity) : this(
        data.timestamp, data.timezoneOffset15Minutes, data.steps, data.orientation, data.intensity,
        data.lightIntensity, data.activeMinutes, data.pluggedIn, data.sleepIntentHint,
        data.restingGramCalories, data.activeGramCalories, data.distanceCm, data.heartRate,
        data.heartRateWeight, data.heartRateZone,
    )
}

@Serializable
private data class OverlayExport(
    val type: Int,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Long,
    val timezoneOffsetSeconds: Int,
    val steps: Int,
    val restingKiloCalories: Int,
    val activeKiloCalories: Int,
    val distanceCm: Int,
) {
    constructor(data: OverlayDataEntity) : this(
        data.type, data.startTime, data.startTime + data.duration, data.duration, data.offsetUTC,
        data.steps, data.restingKiloCalories, data.activeKiloCalories, data.distanceCm,
    )
}
