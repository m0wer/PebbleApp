package coredevices.pebble.ui

import PlatformShareLauncher
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
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

    val summary = remember(history) { summarizeBatteryHistory(history) }
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
            LatestBatteryState(summary)
            Spacer(Modifier.height(16.dp))
            BatteryTrend(summary)
            Text("Hourly history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        }
        items(summary.rows.asReversed(), key = { it.id }) { row -> BatteryHistoryRow(row) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private const val EXPORT_CACHE_DIRECTORY = "exports"

@Composable
private fun LatestBatteryState(summary: BatteryHistorySummary) {
    val row = summary.latest
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Latest", style = MaterialTheme.typography.titleMedium)
        Text(
            "${formatPercent(row.socCentipercent)}%",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("${formatTimestamp(row.timestampSeconds)}  ${row.serial}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Instantaneous gauge sample: ${row.currentUa} uA. Lowest SOC ${formatPercent(row.socMinCentipercent)}%.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BatteryTrend(summary: BatteryHistorySummary) {
    val rows = summary.rows
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("SOC trend", style = MaterialTheme.typography.titleMedium)
        Text(
            "${formatPercent(summary.startSocCentipercent)}% to ${formatPercent(summary.endSocCentipercent)}% " +
                "(${formatSignedPercent(summary.socChangeCentipercent)}%)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Canvas(modifier = Modifier.fillMaxWidth().height(112.dp).padding(vertical = 8.dp)) {
            val socValues = rows.map { it.socCentipercent.toFloat() }
            val rawMinimum = socValues.min()
            val rawMaximum = socValues.max()
            val range = maxOf(100f, rawMaximum - rawMinimum)
            var minimum = (rawMinimum + rawMaximum - range) / 2f
            var maximum = minimum + range
            if (minimum < 0f) {
                maximum -= minimum
                minimum = 0f
            }
            if (maximum > 10_000f) {
                minimum -= maximum - 10_000f
                maximum = 10_000f
            }

            drawLine(outline, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
            val points = socValues.mapIndexed { index, soc ->
                val x = if (socValues.size == 1) size.width / 2f else size.width * index / (socValues.size - 1)
                val y = size.height - ((soc - minimum) / (maximum - minimum) * size.height)
                Offset(x, y)
            }
            points.zipWithNext().forEach { (start, end) -> drawLine(primary, start, end, strokeWidth = 3f) }
            points.forEach { point -> drawCircle(primary, radius = 3f, center = point) }
        }
        summary.observedRatePercentPerHour?.let { rate ->
            Text(
                "Observed drain ${formatRate(rate)}%/h across ${formatDuration(summary.observedDurationMs)} " +
                    "of valid discharge (window ${formatDuration(summary.windowDurationMs)}).",
                style = MaterialTheme.typography.bodyMedium,
            )
        } ?: Text(
            "No valid discharge intervals recorded yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BatteryHistoryRow(row: BatteryHistoryEntity) {
    val interval = row.toBatteryInterval()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(formatTimestamp(row.timestampSeconds), style = MaterialTheme.typography.titleSmall)
        Text("SOC ${formatPercent(row.socCentipercent)}%", style = MaterialTheme.typography.bodyMedium)
        Text(
            interval?.let {
                "Interval drop ${formatPercent(it.socDropCentipercent)}%, ${formatRate(it.ratePercentPerHour)}%/h " +
                    "over ${formatDuration(it.durationMs)}"
            } ?: intervalUnavailableText(row),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Recorded activity: ${recordedActivity(row).ifEmpty { listOf("None reported") }.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "${row.voltageMv} mV (${row.voltageDeltaMv} mV), ${row.temperatureMc / 1000.0} C, " +
                "watchface ${row.watchfaceName.ifBlank { "not reported" }}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatPercent(centipercent: Int): String = (centipercent / 100.0).toString()

private fun formatRate(ratePercentPerHour: Double): String {
    val thousandths = (ratePercentPerHour * 1_000 + 0.5).toLong()
    return "${thousandths / 1_000}.${(thousandths % 1_000).toString().padStart(3, '0')}"
}

private fun formatSignedPercent(centipercent: Int): String = if (centipercent > 0) {
    "+${formatPercent(centipercent)}"
} else {
    formatPercent(centipercent)
}

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

internal data class BatteryInterval(
    val socDropCentipercent: Int,
    val durationMs: Long,
    val ratePercentPerHour: Double,
)

internal data class BatteryHistorySummary(
    val latest: BatteryHistoryEntity,
    val rows: List<BatteryHistoryEntity>,
    val startSocCentipercent: Int,
    val endSocCentipercent: Int,
    val socChangeCentipercent: Int,
    val observedRatePercentPerHour: Double?,
    val observedDurationMs: Long,
    val windowDurationMs: Long,
)

internal fun summarizeBatteryHistory(history: List<BatteryHistoryEntity>): BatteryHistorySummary {
    val newestRecord = history.maxWith(compareBy<BatteryHistoryEntity> { it.timestampSeconds }.thenBy { it.id })
    val currentWatchRows = history.filter { it.serial == newestRecord.serial }
        .sortedWith(compareBy<BatteryHistoryEntity> { it.timestampSeconds }.thenBy { it.id })
    val validIntervals = currentWatchRows.drop(1).mapNotNull { it.toBatteryInterval() }
    val observedDurationMs = validIntervals.sumOf { it.durationMs }
    val observedDropCentipercent = validIntervals.sumOf { it.socDropCentipercent }
    val observedRate = if (observedDurationMs > 0) {
        observedDropCentipercent / 100.0 * 3_600_000.0 / observedDurationMs
    } else {
        null
    }

    return BatteryHistorySummary(
        latest = currentWatchRows.last(),
        rows = currentWatchRows,
        startSocCentipercent = currentWatchRows.first().socCentipercent,
        endSocCentipercent = currentWatchRows.last().socCentipercent,
        socChangeCentipercent = currentWatchRows.last().socCentipercent - currentWatchRows.first().socCentipercent,
        observedRatePercentPerHour = observedRate,
        observedDurationMs = observedDurationMs,
        windowDurationMs = currentWatchRows.last().timestampSeconds
            .minus(currentWatchRows.first().timestampSeconds)
            .coerceAtLeast(0)
            .times(1_000),
    )
}

internal fun BatteryHistoryEntity.toBatteryInterval(): BatteryInterval? {
    if (chargeTimeMs > 0 || dischargeDurationMs <= 0 || socDropCentipercent < 0) return null
    return BatteryInterval(
        socDropCentipercent = socDropCentipercent,
        durationMs = dischargeDurationMs,
        ratePercentPerHour = socDropCentipercent / 100.0 * 3_600_000.0 / dischargeDurationMs,
    )
}

internal fun recordedActivity(row: BatteryHistoryEntity): List<String> = buildList {
    if (row.hrmOnTimeMs > 0) add("HRM ${formatDuration(row.hrmOnTimeMs)}")
    if (row.backlightOnTimeMs > 0) {
        add("Backlight ${formatDuration(row.backlightOnTimeMs)} at ${row.backlightAverageIntensityPercent}%")
    }
    if (row.vibratorOnTimeMs > 0) add("Vibration ${formatDuration(row.vibratorOnTimeMs)}")
    if (row.taskCpuAppCentipercent > 0) add("App CPU ${formatPercent(row.taskCpuAppCentipercent)}%")
    if (row.bleLatencyZeroTimeMs > 0) add("BLE low latency ${formatDuration(row.bleLatencyZeroTimeMs)}")
}

private fun intervalUnavailableText(row: BatteryHistoryEntity): String = when {
    row.chargeTimeMs > 0 -> "Charging recorded for ${formatDuration(row.chargeTimeMs)}"
    row.dischargeDurationMs <= 0 -> "No discharge interval recorded"
    else -> "Invalid interval drop recorded"
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
