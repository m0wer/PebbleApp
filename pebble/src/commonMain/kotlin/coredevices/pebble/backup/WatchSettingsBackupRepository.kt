package coredevices.pebble.backup

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.WatchSettingsBackupSnapshot
import io.rebble.libpebblecommon.database.asMillisecond
import kotlin.time.Clock

interface WatchSettingsBackupDataSource {
    suspend fun read(): WatchSettingsBackupExportData
    suspend fun merge(data: WatchSettingsBackupImportData): WatchSettingsBackupImportCounts
}

data class WatchSettingsBackupImportCounts(
    val watchPrefs: Int,
    val healthSettings: Int,
    val weatherLocations: Int,
)

class WatchSettingsBackupRepository(
    private val dataSource: WatchSettingsBackupDataSource,
    private val clock: Clock,
) {
    suspend fun export(): String = WatchSettingsBackupCodec.encode(
        dataSource.read(),
        clock.now().toEpochMilliseconds(),
    )

    suspend fun importBackup(document: String): WatchSettingsBackupImportCounts {
        val decoded = WatchSettingsBackupCodec.decode(document)
        return dataSource.merge(decoded)
    }
}

class RealWatchSettingsBackupDataSource(
    private val libPebble: LibPebble,
    private val clock: Clock,
) : WatchSettingsBackupDataSource {
    override suspend fun read(): WatchSettingsBackupExportData {
        val snapshot = libPebble.getWatchSettingsBackupSnapshot()
        return WatchSettingsBackupExportData(
            knownWatches = libPebble.getKnownWatches().map {
                HealthBatteryBackupWatch(it.name, it.serial, it.runningFwVersion)
            },
            watchPrefs = snapshot.watchPrefs,
            healthSettings = snapshot.healthSettings,
            weatherLocationUuids = snapshot.weatherLocationUuids,
        )
    }

    override suspend fun merge(data: WatchSettingsBackupImportData): WatchSettingsBackupImportCounts {
        val now = clock.now().asMillisecond()
        val healthSettings = data.healthSettings.map { it.copy(timestamp = now) }
        val watchPrefs = data.watchPrefs.map { it.copy(timestamp = now) }
        libPebble.replaceWatchSettingsBackup(
            WatchSettingsBackupSnapshot(watchPrefs, healthSettings, data.weatherLocationUuids)
        )
        return WatchSettingsBackupImportCounts(
            watchPrefs = watchPrefs.size,
            healthSettings = healthSettings.size,
            weatherLocations = data.weatherLocationUuids?.size ?: 0,
        )
    }
}
