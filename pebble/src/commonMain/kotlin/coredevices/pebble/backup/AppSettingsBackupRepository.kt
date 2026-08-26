package coredevices.pebble.backup

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import coredevices.CoreBackgroundSync
import coredevices.EnableExperimentalDevices
import coredevices.pebble.health.HealthSyncTracker
import coredevices.util.CoreConfigHolder
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import theme.ThemeProvider
import kotlin.time.Clock

interface AppSettingsBackupDataSource {
    fun read(): AppSettingsBackupExportData
    fun replace(data: AppSettingsBackupImportData)
}

class AppSettingsBackupRepository(
    private val dataSource: AppSettingsBackupDataSource,
    private val clock: Clock,
) {
    fun export(): String = AppSettingsBackupCodec.encode(
        dataSource.read(),
        clock.now().toEpochMilliseconds(),
    )

    suspend fun importBackup(document: String) {
        val decoded = withContext(Dispatchers.Default) {
            AppSettingsBackupCodec.decode(document)
        }
        dataSource.replace(decoded)
    }
}

class RealAppSettingsBackupDataSource(
    private val coreConfigHolder: CoreConfigHolder,
    private val libPebble: LibPebble,
    private val themeProvider: ThemeProvider,
    private val settings: Settings,
    private val enableExperimentalDevices: EnableExperimentalDevices,
    private val healthSyncTracker: HealthSyncTracker,
    private val coreBackgroundSync: CoreBackgroundSync,
) : AppSettingsBackupDataSource {
    override fun read() = AppSettingsBackupExportData(
        coreConfig = coreConfigHolder.config.value,
        libPebbleConfig = libPebble.config.value,
        theme = themeProvider.theme.value,
        enableMemfaultUploads = settings.getBoolean(KEY_ENABLE_MEMFAULT_UPLOADS, true),
        enableFirebaseUploads = settings.getBoolean(KEY_ENABLE_FIREBASE_UPLOADS, true),
        enableMixpanelUploads = settings.getBoolean(KEY_ENABLE_MIXPANEL_UPLOADS, true),
        showDebugOptions = settings.getBoolean(KEY_SHOW_DEBUG_OPTIONS, false),
        enableExperimentalDevices = enableExperimentalDevices.enabled.value,
        healthSyncEnabled = healthSyncTracker.enabled.value,
    )

    override fun replace(data: AppSettingsBackupImportData) {
        val targetIndexPermissionsConfirmed = coreConfigHolder.config.value.indexPermissionsConfirmed
        val restoredCoreConfig = restoreCoreConfig(data.coreConfig, targetIndexPermissionsConfirmed)
        coreConfigHolder.update(restoredCoreConfig)
        libPebble.updateConfig(data.libPebbleConfig)
        themeProvider.setTheme(data.theme)
        coreBackgroundSync.updateFullSyncPeriod(restoredCoreConfig.regularSyncInterval)
        coreBackgroundSync.updateWeatherSyncPeriod(restoredCoreConfig.weatherSyncInterval)
        settings[KEY_ENABLE_MEMFAULT_UPLOADS] = data.enableMemfaultUploads
        settings[KEY_ENABLE_FIREBASE_UPLOADS] = data.enableFirebaseUploads
        settings[KEY_ENABLE_MIXPANEL_UPLOADS] = data.enableMixpanelUploads
        settings[KEY_SHOW_DEBUG_OPTIONS] = data.showDebugOptions
        enableExperimentalDevices.set(data.enableExperimentalDevices)
        healthSyncTracker.setEnabled(data.healthSyncEnabled)
    }

    private companion object {
        const val KEY_ENABLE_MEMFAULT_UPLOADS = "enable_memfault_uploads"
        const val KEY_ENABLE_FIREBASE_UPLOADS = "enable_firebase_uploads"
        const val KEY_ENABLE_MIXPANEL_UPLOADS = "enable_mixpanel_uploads"
        const val KEY_SHOW_DEBUG_OPTIONS = "showDebugOptions"
    }
}

internal fun restoreCoreConfig(imported: coredevices.util.CoreConfig, targetIndexPermissionsConfirmed: Boolean) = imported.copy(
    enableIndex = imported.enableIndex && targetIndexPermissionsConfirmed,
    indexPermissionsConfirmed = targetIndexPermissionsConfirmed,
)
