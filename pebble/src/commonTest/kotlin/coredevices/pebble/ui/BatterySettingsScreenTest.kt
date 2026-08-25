package coredevices.pebble.ui

import coredevices.database.BatteryHistoryEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BatterySettingsScreenTest {
    @Test
    fun csvEscapesCarriageReturns() {
        assertEquals("\"Pebble\rTime\"", "Pebble\rTime".csvEscape())
    }

    @Test
    fun summaryUsesDurationWeightedDrainRate() {
        val summary = summarizeBatteryHistory(
            listOf(
                batteryRow(timestampSeconds = 7_200, socCentipercent = 9_970, socDropCentipercent = 10, dischargeDurationMs = 7_200_000),
                batteryRow(timestampSeconds = 0, socCentipercent = 10_000, socDropCentipercent = 20, dischargeDurationMs = 3_600_000),
                batteryRow(timestampSeconds = 3_600, socCentipercent = 9_980, socDropCentipercent = 20, dischargeDurationMs = 3_600_000),
            ),
        )

        assertEquals(10_800_000, summary.observedDurationMs)
        assertEquals(0.1, assertNotNull(summary.observedRatePercentPerHour), absoluteTolerance = 0.0001)
        assertEquals(-30, summary.socChangeCentipercent)
    }

    @Test
    fun summaryIgnoresChargingAndInvalidIntervals() {
        val summary = summarizeBatteryHistory(
            listOf(
                batteryRow(timestampSeconds = 4, socDropCentipercent = 10, dischargeDurationMs = 3_600_000),
                batteryRow(timestampSeconds = 3, socDropCentipercent = 50, dischargeDurationMs = 3_600_000, chargeTimeMs = 1),
                batteryRow(timestampSeconds = 2, socDropCentipercent = -10, dischargeDurationMs = 3_600_000),
                batteryRow(timestampSeconds = 1, socDropCentipercent = 50, dischargeDurationMs = 3_600_000),
            ),
        )

        assertEquals(3_600_000, summary.observedDurationMs)
        assertEquals(0.1, assertNotNull(summary.observedRatePercentPerHour), absoluteTolerance = 0.0001)
        assertNull(batteryRow(socDropCentipercent = -1).toBatteryInterval())
    }

    @Test
    fun summaryUsesOnlyNewestRecordSerial() {
        val summary = summarizeBatteryHistory(
            listOf(
                batteryRow(serial = "current", timestampSeconds = 10, socCentipercent = 10_000, id = 1),
                batteryRow(serial = "other", timestampSeconds = 20, socCentipercent = 1_000, id = 5),
                batteryRow(serial = "current", timestampSeconds = 20, socCentipercent = 9_900, id = 6),
            ),
        )

        assertEquals("current", summary.latest.serial)
        assertEquals(6, summary.latest.id)
        assertEquals(listOf("current", "current"), summary.rows.map { it.serial })
        assertEquals(-100, summary.socChangeCentipercent)
    }

    @Test
    fun summaryHasNoObservedRateForSingleRow() {
        val summary = summarizeBatteryHistory(
            listOf(batteryRow(socDropCentipercent = 50, dischargeDurationMs = 3_600_000)),
        )

        assertEquals(0, summary.observedDurationMs)
        assertNull(summary.observedRatePercentPerHour)
    }

    @Test
    fun recordedActivityLabelsOnlyReportedActivity() {
        val activity = recordedActivity(
            batteryRow(
                hrmOnTimeMs = 60_000,
                backlightOnTimeMs = 30_000,
                backlightAverageIntensityPercent = 50,
                vibratorOnTimeMs = 2_000,
                taskCpuAppCentipercent = 125,
                bleLatencyZeroTimeMs = 120_000,
            ),
        )

        assertEquals(
            listOf("HRM 1m", "Backlight 30s at 50%", "Vibration 2s", "App CPU 1.25%", "BLE low latency 2m"),
            activity,
        )
        assertEquals(emptyList(), recordedActivity(batteryRow()))
    }

    private fun batteryRow(
        id: Long = 0,
        serial: String = "watch",
        timestampSeconds: Long = 0,
        socCentipercent: Int = 10_000,
        socDropCentipercent: Int = 0,
        dischargeDurationMs: Long = 3_600_000,
        chargeTimeMs: Long = 0,
        backlightOnTimeMs: Long = 0,
        backlightAverageIntensityPercent: Int = 0,
        vibratorOnTimeMs: Long = 0,
        hrmOnTimeMs: Long = 0,
        taskCpuAppCentipercent: Int = 0,
        bleLatencyZeroTimeMs: Long = 0,
    ) = BatteryHistoryEntity(
        id = id,
        serial = serial,
        timestampSeconds = timestampSeconds,
        recordVersion = 4,
        socCentipercent = socCentipercent,
        socMinCentipercent = socCentipercent,
        socDropCentipercent = socDropCentipercent,
        voltageMv = 4_000,
        voltageDeltaMv = 0,
        temperatureMc = 25_000,
        currentUa = -100,
        tteSeconds = 0,
        chargeTimeMs = chargeTimeMs,
        dischargeDurationMs = dischargeDurationMs,
        backlightOnTimeMs = backlightOnTimeMs,
        backlightAverageIntensityPercent = backlightAverageIntensityPercent,
        vibratorOnTimeMs = vibratorOnTimeMs,
        hrmOnTimeMs = hrmOnTimeMs,
        cpuRunningCentipercent = 0,
        taskCpuKernelMainCentipercent = 0,
        taskCpuKernelBackgroundCentipercent = 0,
        taskCpuWorkerCentipercent = 0,
        taskCpuAppCentipercent = taskCpuAppCentipercent,
        taskCpuBtHostCentipercent = 0,
        taskCpuBtControllerCentipercent = 0,
        taskCpuBtHciCentipercent = 0,
        taskCpuNewTimersCentipercent = 0,
        taskCpuPulseCentipercent = 0,
        taskCpuIdleCentipercent = 0,
        bleConnectedTimeMs = 0,
        bleExpectedTimeMs = 0,
        bleLatencyZeroTimeMs = bleLatencyZeroTimeMs,
        bleConnectionIntervalMinTimeMs = 0,
        bleConnectionIntervalMidTimeMs = 0,
        bleConnectionIntervalMaxTimeMs = 0,
        bleConnectionIntervalOtherTimeMs = 0,
        bleConnectionParameterUpdateCount = 0,
        watchfaceName = "",
        watchfaceUuid = "",
        secondTickSubscribed = false,
    )
}
