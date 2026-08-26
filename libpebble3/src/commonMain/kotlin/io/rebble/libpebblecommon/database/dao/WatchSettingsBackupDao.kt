package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.rebble.libpebblecommon.database.entity.AppPrefsEntry
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryEntity
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntry
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntryEntity
import io.rebble.libpebblecommon.database.entity.WatchPrefItem
import io.rebble.libpebblecommon.database.entity.WatchPrefItemEntity
import io.rebble.libpebblecommon.database.entity.WatchPref

@Dao
interface WatchSettingsBackupDao {
    @Query("SELECT * FROM WatchPrefItemEntity WHERE id IN (:ids) AND deleted = 0")
    suspend fun getWatchPrefs(ids: List<String>): List<WatchPrefItem>

    @Query("SELECT * FROM HealthSettingsEntryEntity WHERE id IN (:ids) AND deleted = 0")
    suspend fun getHealthSettings(ids: List<String>): List<HealthSettingsEntry>

    @Query("SELECT * FROM AppPrefsEntryEntity WHERE id = :id AND deleted = 0")
    suspend fun getAppPrefsEntry(id: String): AppPrefsEntry?

    @Transaction
    suspend fun replaceSettings(
        watchPrefs: List<WatchPrefItem>,
        healthSettings: List<HealthSettingsEntry>,
        weatherApp: AppPrefsEntry?,
    ) {
        markWatchPrefsDeleted(WatchPref.enumeratePrefs().map { it.id })
        markHealthSettingsDeleted(HEALTH_SETTINGS_IDS)
        markAppPrefsDeleted(WEATHER_APP_ID)

        upsertWatchPrefs(watchPrefs.map { WatchPrefItemEntity(it.recordHashCode(), false, it, sync = true) })
        upsertHealthSettings(healthSettings.map { HealthSettingsEntryEntity(it.recordHashCode(), false, it, sync = true) })
        weatherApp?.let { upsertAppPrefs(AppPrefsEntryEntity(it.recordHashCode(), false, it, sync = true)) }
    }

    @Query("UPDATE WatchPrefItemEntity SET deleted = 1 WHERE id IN (:ids)")
    suspend fun markWatchPrefsDeleted(ids: List<String>)

    @Query("UPDATE HealthSettingsEntryEntity SET deleted = 1 WHERE id IN (:ids)")
    suspend fun markHealthSettingsDeleted(ids: List<String>)

    @Query("UPDATE AppPrefsEntryEntity SET deleted = 1 WHERE id = :id")
    suspend fun markAppPrefsDeleted(id: String)

    @Upsert
    suspend fun upsertWatchPrefs(entries: List<WatchPrefItemEntity>)

    @Upsert
    suspend fun upsertHealthSettings(entries: List<HealthSettingsEntryEntity>)

    @Upsert
    suspend fun upsertAppPrefs(entry: AppPrefsEntryEntity)

    companion object {
        const val WEATHER_APP_ID = "weatherApp"
        val HEALTH_SETTINGS_IDS = listOf(
            "activityPreferences",
            "unitsDistance",
            "hrmPreferences",
            "heartRatePreferences",
        )
    }
}
