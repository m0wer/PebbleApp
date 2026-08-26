package coredevices.pebble.backup

import coredevices.database.BatteryHistoryDao
import coredevices.database.BatteryHistoryEntity
import io.rebble.libpebblecommon.connection.LibPebble

interface HealthBatteryBackupDataSource {
    suspend fun read(): HealthBatteryBackupExportData
    suspend fun merge(data: HealthBatteryBackupImportData): HealthBatteryBackupImportCounts
}

data class HealthBatteryBackupImportCounts(
    val healthMinutes: Int,
    val overlays: Int,
    val batteryHistory: Int,
)

class HealthBatteryBackupRepository(
    private val dataSource: HealthBatteryBackupDataSource,
) {
    suspend fun export(): String = HealthBatteryBackupCodec.encode(dataSource.read())

    suspend fun importBackup(document: String): HealthBatteryBackupImportCounts {
        val decoded = HealthBatteryBackupCodec.decode(document)
        return dataSource.merge(decoded)
    }
}

class RealHealthBatteryBackupDataSource(
    private val libPebble: LibPebble,
    private val batteryHistoryDao: BatteryHistoryDao,
) : HealthBatteryBackupDataSource {
    override suspend fun read(): HealthBatteryBackupExportData = HealthBatteryBackupExportData(
        knownWatches = libPebble.getKnownWatches().map {
            HealthBatteryBackupWatch(it.name, it.serial, it.runningFwVersion)
        },
        healthMinutes = libPebble.getAllHealthData(),
        overlays = libPebble.getAllOverlayEntries(),
        batteryHistory = batteryHistoryDao.exportAll(),
    )

    override suspend fun merge(data: HealthBatteryBackupImportData): HealthBatteryBackupImportCounts {
        libPebble.mergeHealthBackupData(data.healthMinutes, data.overlays)
        batteryHistoryDao.insertAllIgnoringConflicts(data.batteryHistory.map { it.copy(id = 0) })
        return HealthBatteryBackupImportCounts(
            healthMinutes = data.healthMinutes.size,
            overlays = data.overlays.size,
            batteryHistory = data.batteryHistory.size,
        )
    }
}
