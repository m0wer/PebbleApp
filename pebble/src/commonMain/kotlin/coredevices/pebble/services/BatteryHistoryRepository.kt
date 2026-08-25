package coredevices.pebble.services

import co.touchlab.kermit.Logger
import coredevices.database.BatteryHistoryDao
import coredevices.database.BatteryHistoryEntity
import io.rebble.libpebblecommon.datalogging.NativeAnalyticsHeartbeatParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class BatteryHistoryRepository(
    private val dao: BatteryHistoryDao,
) {
    private val logger = Logger.withTag("BatteryHistoryRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun store(serial: String, payload: ByteArray) {
        val heartbeat = NativeAnalyticsHeartbeatParser.parse(payload) ?: run {
            logger.w { "Ignoring malformed native analytics heartbeat for $serial" }
            return
        }
        scope.launch {
            dao.insertAndRetain(
                BatteryHistoryEntity(
                    serial = serial,
                    timestampSeconds = heartbeat.timestampSeconds,
                    recordVersion = heartbeat.version,
                    socCentipercent = heartbeat.batterySocCentipercent,
                    socMinCentipercent = heartbeat.batterySocMinCentipercent,
                    socDropCentipercent = heartbeat.batterySocDropCentipercent,
                    voltageMv = heartbeat.batteryVoltageMv,
                    voltageDeltaMv = heartbeat.batteryVoltageDeltaMv,
                    temperatureMc = heartbeat.batteryTemperatureMc,
                    currentUa = heartbeat.batteryCurrentUa,
                    tteSeconds = heartbeat.batteryTteSeconds,
                    chargeTimeMs = heartbeat.batteryChargeTimeMs,
                    dischargeDurationMs = heartbeat.batteryDischargeDurationMs,
                    backlightOnTimeMs = heartbeat.backlightOnTimeMs,
                    backlightAverageIntensityPercent = heartbeat.backlightAverageIntensityPercent,
                    vibratorOnTimeMs = heartbeat.vibratorOnTimeMs,
                    hrmOnTimeMs = heartbeat.hrmOnTimeMs,
                    cpuRunningCentipercent = heartbeat.cpuRunningCentipercent,
                    taskCpuKernelMainCentipercent = heartbeat.taskCpuCentipercent[0],
                    taskCpuKernelBackgroundCentipercent = heartbeat.taskCpuCentipercent[1],
                    taskCpuWorkerCentipercent = heartbeat.taskCpuCentipercent[2],
                    taskCpuAppCentipercent = heartbeat.taskCpuCentipercent[3],
                    taskCpuBtHostCentipercent = heartbeat.taskCpuCentipercent[4],
                    taskCpuBtControllerCentipercent = heartbeat.taskCpuCentipercent[5],
                    taskCpuBtHciCentipercent = heartbeat.taskCpuCentipercent[6],
                    taskCpuNewTimersCentipercent = heartbeat.taskCpuCentipercent[7],
                    taskCpuPulseCentipercent = heartbeat.taskCpuCentipercent[8],
                    taskCpuIdleCentipercent = heartbeat.taskCpuCentipercent[9],
                    bleConnectedTimeMs = heartbeat.bleConnectedTimeMs,
                    bleExpectedTimeMs = heartbeat.bleExpectedTimeMs,
                    bleLatencyZeroTimeMs = heartbeat.bleLatencyZeroTimeMs,
                    bleConnectionIntervalMinTimeMs = heartbeat.bleConnectionIntervalMinTimeMs,
                    bleConnectionIntervalMidTimeMs = heartbeat.bleConnectionIntervalMidTimeMs,
                    bleConnectionIntervalMaxTimeMs = heartbeat.bleConnectionIntervalMaxTimeMs,
                    bleConnectionIntervalOtherTimeMs = heartbeat.bleConnectionIntervalOtherTimeMs,
                    bleConnectionParameterUpdateCount = heartbeat.bleConnectionParameterUpdateCount,
                    watchfaceName = heartbeat.watchfaceName,
                    watchfaceUuid = heartbeat.watchfaceUuid,
                    secondTickSubscribed = heartbeat.appSecondTickSubscribed,
                ),
                RETENTION_PER_WATCH,
            )
        }
    }

    fun observeLatest(): Flow<BatteryHistoryEntity?> = dao.observeLatest()
    fun observeRecent(): Flow<List<BatteryHistoryEntity>> = dao.observeRecent(RETENTION_PER_WATCH)
    suspend fun exportAll(): List<BatteryHistoryEntity> = dao.exportAll()

    companion object {
        const val RETENTION_PER_WATCH = 2500
    }
}
