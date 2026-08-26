package coredevices.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "battery_history",
    indices = [Index(value = ["serial", "timestampSeconds"], unique = true)],
)
data class BatteryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
)

@Dao
interface BatteryHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: BatteryHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringConflicts(rows: List<BatteryHistoryEntity>): List<Long>

    @Query("SELECT * FROM battery_history ORDER BY timestampSeconds DESC, serial ASC, id ASC LIMIT 1")
    fun observeLatest(): Flow<BatteryHistoryEntity?>

    @Query("SELECT * FROM battery_history ORDER BY timestampSeconds DESC, serial ASC, id ASC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BatteryHistoryEntity>>

    @Query("SELECT * FROM battery_history ORDER BY timestampSeconds ASC, serial ASC, id ASC")
    suspend fun exportAll(): List<BatteryHistoryEntity>

    @Query("DELETE FROM battery_history WHERE serial = :serial AND id NOT IN (SELECT id FROM battery_history WHERE serial = :serial ORDER BY timestampSeconds DESC, id ASC LIMIT :limit)")
    suspend fun retainNewestForWatch(serial: String, limit: Int)

    @Transaction
    suspend fun insertAndRetain(row: BatteryHistoryEntity, limit: Int) {
        insert(row)
        retainNewestForWatch(row.serial, limit)
    }
}
