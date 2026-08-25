package coredevices.pebble.ui

import PlatformShareLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import coredevices.database.BatteryHistoryEntity
import coredevices.pebble.services.BatteryHistoryRepository
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.getTempFilePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.compose.koinInject

private val batteryExportLogger = Logger.withTag("BatteryExport")

@Composable
fun BatterySettingsScreen(navBarNav: NavBarNav, topBarParams: TopBarParams) {
    val repository = koinInject<BatteryHistoryRepository>()
    val shareLauncher = koinInject<PlatformShareLauncher>()
    val appContext = koinInject<AppContext>()
    val history by repository.observeRecent().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(history.isNotEmpty(), isExporting) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Battery")
        topBarParams.actions {
            IconButton(
                onClick = {
                    scope.launch {
                        exportError = null
                        isExporting = true
                        try {
                            val csvPath = withContext(Dispatchers.Default) {
                                getTempFilePath(
                                    appContext,
                                    "pebble-battery-history.csv",
                                    EXPORT_CACHE_DIRECTORY,
                                ).also {
                                    SystemFileSystem.sink(it, append = false).buffered().use { sink ->
                                        sink.writeString(repository.exportAll().toBatteryCsv())
                                    }
                                }
                            }
                            shareLauncher.share("Pebble battery history", csvPath, mimeType = "text/csv")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            batteryExportLogger.e(e) { "Failed to export battery history" }
                            exportError = "Battery export failed. Try again."
                        } finally {
                            isExporting = false
                        }
                    }
                },
                enabled = history.isNotEmpty() && !isExporting,
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export battery history")
            }
        }
    }

    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("No local battery history yet.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text("Hourly battery records from connected watches will appear here.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        exportError?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            LatestBatteryState(history.first())
            Text("Hourly history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        }
        items(history, key = { it.id }) { row -> BatteryHistoryRow(row) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private const val EXPORT_CACHE_DIRECTORY = "exports"

@Composable
private fun LatestBatteryState(row: BatteryHistoryEntity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Latest", style = MaterialTheme.typography.titleMedium)
        Text(
            "${formatPercent(row.socCentipercent)}%, ${row.voltageMv} mV, ${row.currentUa} uA",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("${formatTimestamp(row.timestampSeconds)}  ${row.serial}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Estimated remaining ${formatDuration(row.tteSeconds * 1_000)}. " +
                "Lowest SOC ${formatPercent(row.socMinCentipercent)}%.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BatteryHistoryRow(row: BatteryHistoryEntity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTimestamp(row.timestampSeconds), style = MaterialTheme.typography.titleSmall)
            Text("${formatPercent(row.socCentipercent)}% (${formatPercent(row.socDropCentipercent)}% drop)")
        }
        Text(
            "${row.currentUa} uA, ${row.voltageMv} mV (${row.voltageDeltaMv} mV), ${row.temperatureMc / 1000.0} C",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Backlight ${formatDuration(row.backlightOnTimeMs)} at ${row.backlightAverageIntensityPercent}%, " +
                "vibration ${formatDuration(row.vibratorOnTimeMs)}, HRM ${formatDuration(row.hrmOnTimeMs)}, " +
                "CPU ${formatPercent(row.cpuRunningCentipercent)}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "BLE ${formatDuration(row.bleConnectedTimeMs)} connected, latency 0 ${formatDuration(row.bleLatencyZeroTimeMs)}, " +
                "watchface ${row.watchfaceName.ifBlank { "system" }}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatPercent(centipercent: Int): String = (centipercent / 100.0).toString()

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }
}

private fun formatTimestamp(seconds: Long): String {
    val time = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.date} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

private fun List<BatteryHistoryEntity>.toBatteryCsv(): String = buildString {
    append(BATTERY_CSV_HEADER).append('\n')
    this@toBatteryCsv.forEach { row ->
        append(
            listOf(
                row.id, row.serial, row.timestampSeconds, row.recordVersion, row.socCentipercent, row.socMinCentipercent,
                row.socDropCentipercent, row.voltageMv, row.voltageDeltaMv, row.temperatureMc, row.currentUa,
                row.tteSeconds, row.chargeTimeMs, row.dischargeDurationMs, row.backlightOnTimeMs,
                row.backlightAverageIntensityPercent, row.vibratorOnTimeMs, row.hrmOnTimeMs, row.cpuRunningCentipercent,
                row.taskCpuKernelMainCentipercent, row.taskCpuKernelBackgroundCentipercent, row.taskCpuWorkerCentipercent,
                row.taskCpuAppCentipercent, row.taskCpuBtHostCentipercent, row.taskCpuBtControllerCentipercent,
                row.taskCpuBtHciCentipercent, row.taskCpuNewTimersCentipercent, row.taskCpuPulseCentipercent,
                row.taskCpuIdleCentipercent, row.bleConnectedTimeMs, row.bleExpectedTimeMs, row.bleLatencyZeroTimeMs,
                row.bleConnectionIntervalMinTimeMs, row.bleConnectionIntervalMidTimeMs,
                row.bleConnectionIntervalMaxTimeMs, row.bleConnectionIntervalOtherTimeMs,
                row.bleConnectionParameterUpdateCount, row.watchfaceName, row.watchfaceUuid, row.secondTickSubscribed,
            ).joinToString(",") { it.toString().csvEscape() },
        ).append('\n')
    }
}

internal fun String.csvEscape(): String = if (contains(',') || contains('"') || contains('\n') || contains('\r')) {
    "\"${replace("\"", "\"\"")}\""
} else {
    this
}

private const val BATTERY_CSV_HEADER = "id,serial,timestamp_seconds,record_version,soc_centipercent,soc_min_centipercent," +
    "soc_drop_centipercent,voltage_mv,voltage_delta_mv,temperature_mc,current_ua,tte_seconds,charge_time_ms," +
    "discharge_duration_ms,backlight_on_time_ms,backlight_average_intensity_percent,vibrator_on_time_ms,hrm_on_time_ms," +
    "cpu_running_centipercent,task_cpu_kernel_main_centipercent,task_cpu_kernel_background_centipercent," +
    "task_cpu_worker_centipercent,task_cpu_app_centipercent,task_cpu_bt_host_centipercent," +
    "task_cpu_bt_controller_centipercent,task_cpu_bt_hci_centipercent,task_cpu_new_timers_centipercent," +
    "task_cpu_pulse_centipercent,task_cpu_idle_centipercent,ble_connected_time_ms,ble_expected_time_ms," +
    "ble_latency_zero_time_ms,ble_connection_interval_min_time_ms,ble_connection_interval_mid_time_ms," +
    "ble_connection_interval_max_time_ms,ble_connection_interval_other_time_ms,ble_connection_parameter_update_count," +
    "watchface_name,watchface_uuid,second_tick_subscribed"
