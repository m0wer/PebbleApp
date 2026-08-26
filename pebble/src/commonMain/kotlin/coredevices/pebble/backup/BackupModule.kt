package coredevices.pebble.backup

import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val backupModule = module {
    singleOf(::RealHealthBatteryBackupDataSource) bind HealthBatteryBackupDataSource::class
    singleOf(::HealthBatteryBackupRepository)
    singleOf(::RealWatchSettingsBackupDataSource) bind WatchSettingsBackupDataSource::class
    singleOf(::WatchSettingsBackupRepository)
    singleOf(::RealAppSettingsBackupDataSource) bind AppSettingsBackupDataSource::class
    singleOf(::AppSettingsBackupRepository)
    singleOf(::WatchAppDataBackupRepository)
}
