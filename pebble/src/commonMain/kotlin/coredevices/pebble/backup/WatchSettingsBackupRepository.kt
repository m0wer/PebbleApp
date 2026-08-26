package coredevices.pebble.backup

import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.WatchSettingsBackupDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntry
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue.Companion.encodeToString
import io.rebble.libpebblecommon.database.entity.WatchPref
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
    private val backupDao: WatchSettingsBackupDao,
    private val clock: Clock,
) : WatchSettingsBackupDataSource {
    override suspend fun read(): WatchSettingsBackupExportData {
        val weatherApp = backupDao.getAppPrefsEntry(WatchSettingsBackupDao.WEATHER_APP_ID)
        return WatchSettingsBackupExportData(
            knownWatches = libPebble.getKnownWatches().map {
                HealthBatteryBackupWatch(it.name, it.serial, it.runningFwVersion)
            },
            watchPrefs = backupDao.getWatchPrefs(WatchPref.enumeratePrefs().map { it.id }),
            healthSettings = backupDao.getHealthSettings(WatchSettingsBackupDao.HEALTH_SETTINGS_IDS),
            weatherLocationUuids = weatherApp?.let { WeatherPrefsValue.fromString(it.value)?.locationUuids },
        )
    }

    override suspend fun merge(data: WatchSettingsBackupImportData): WatchSettingsBackupImportCounts {
        val now = clock.now().asMillisecond()
        val healthSettings = data.healthSettings.map { it.copy(timestamp = now) }
        val watchPrefs = data.watchPrefs.map { it.copy(timestamp = now) }
        val weatherApp = data.weatherLocationUuids?.let {
            AppPrefsEntry(WatchSettingsBackupDao.WEATHER_APP_ID, WeatherPrefsValue(it).encodeToString())
        }
        backupDao.replaceSettings(watchPrefs, healthSettings, weatherApp)
        return WatchSettingsBackupImportCounts(
            watchPrefs = watchPrefs.size,
            healthSettings = healthSettings.size,
            weatherLocations = data.weatherLocationUuids?.size ?: 0,
        )
    }
}
