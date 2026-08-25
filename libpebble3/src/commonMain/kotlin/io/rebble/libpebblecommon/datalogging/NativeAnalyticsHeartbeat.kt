package io.rebble.libpebblecommon.datalogging

data class NativeAnalyticsHeartbeat(
    val version: Int,
    val timestampSeconds: Long,
    val batterySocCentipercent: Int,
    val batterySocMinCentipercent: Int,
    val batterySocDropCentipercent: Int,
    val batteryVoltageMv: Int,
    val batteryVoltageDeltaMv: Int,
    val batteryTemperatureMc: Int,
    val batteryCurrentUa: Int,
    val batteryTteSeconds: Long,
    val batteryChargeTimeMs: Long,
    val batteryDischargeDurationMs: Long,
    val backlightOnTimeMs: Long,
    val backlightAverageIntensityPercent: Int,
    val vibratorOnTimeMs: Long,
    val hrmOnTimeMs: Long,
    val cpuRunningCentipercent: Int,
    val taskCpuCentipercent: List<Int>,
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
    val appSecondTickSubscribed: Boolean,
)

object NativeAnalyticsHeartbeatParser {
    fun parse(bytes: ByteArray): NativeAnalyticsHeartbeat? = runCatching {
        Reader(bytes).parse()
    }.getOrNull()

    private class Reader(private val bytes: ByteArray) {
        private var offset = 0

        fun parse(): NativeAnalyticsHeartbeat {
            val version = u8()
            require(version == 3 || version == 4)
            val timestamp = u64()
            skip(20) // BUILD_ID_EXPECTED_LEN

            repeat(7) { u32() }
            i32() // utc_offset_s
            fixedString(33) // fw_version
            repeat(2) { u32() }

            val soc = scaledUnsigned()
            val socDrop = scaledUnsigned()
            val voltage = scaledUnsigned()
            val voltageDelta = scaledSigned()
            val tte = u32()
            val chargeTime = u32()
            val dischargeDuration = u32()

            val backlightTime = u32()
            val backlightIntensity = u32().toInt()
            val vibratorTime = u32()
            u32() // vibrator_avg_strength_pct
            repeat(5) { u32() } // speaker metrics
            val hrmTime = u32()
            repeat(5) { u32() } // button, touch, gesture, and touch-driver metrics

            val cpuRunning = scaledUnsigned()
            repeat(3) { scaledUnsigned() }
            u32() // sifli_ipc_not_idle_count
            val taskCpu = List(10) { scaledUnsigned() }

            repeat(4) { u32() } // accelerometer metrics
            repeat(3) { u32() }
            u32() // phone_call_time_ms
            repeat(2) { u32() } // low_power and stationary timers

            u32() // watchface_time_ms
            val watchfaceName = fixedString(33)
            val watchfaceUuid = fixedString(40)
            repeat(2) { u32() }

            u32() // pfs_space_free_kb
            repeat(2) { u32() } // flash metrics

            repeat(2) { u32() } // BLE advertising timers
            val intervalMinTime = u32()
            val intervalMidTime = u32()
            val intervalMaxTime = u32()
            repeat(6) { u32() } // BLE disconnect counters
            u32() // ppog_reversed
            repeat(8) { u32() } // settings
            repeat(2) { u32() }
            val secondTick = u32() != 0L
            val connectedTime = u32()
            val expectedTime = u32()

            val latencyZeroTime = u32()
            val connectionParameterUpdates = u32()
            repeat(2) { u32() } // accel recovery and unexpected reboot counters
            val temperature = scaledSigned()
            u32() // i2c_transfer_error_count
            val otherIntervalTime = u32()
            u32() // drv_init_fail_flags
            val socMin = scaledUnsigned()
            u32() // touch_gated_touchdown_count
            val current = if (version == 4) i32() else 0

            require(offset == bytes.size)
            return NativeAnalyticsHeartbeat(
                version = version,
                timestampSeconds = timestamp,
                batterySocCentipercent = soc,
                batterySocMinCentipercent = socMin,
                batterySocDropCentipercent = socDrop,
                batteryVoltageMv = voltage,
                batteryVoltageDeltaMv = voltageDelta,
                batteryTemperatureMc = temperature,
                batteryCurrentUa = current,
                batteryTteSeconds = tte,
                batteryChargeTimeMs = chargeTime,
                batteryDischargeDurationMs = dischargeDuration,
                backlightOnTimeMs = backlightTime,
                backlightAverageIntensityPercent = backlightIntensity,
                vibratorOnTimeMs = vibratorTime,
                hrmOnTimeMs = hrmTime,
                cpuRunningCentipercent = cpuRunning,
                taskCpuCentipercent = taskCpu,
                bleConnectedTimeMs = connectedTime,
                bleExpectedTimeMs = expectedTime,
                bleLatencyZeroTimeMs = latencyZeroTime,
                bleConnectionIntervalMinTimeMs = intervalMinTime,
                bleConnectionIntervalMidTimeMs = intervalMidTime,
                bleConnectionIntervalMaxTimeMs = intervalMaxTime,
                bleConnectionIntervalOtherTimeMs = otherIntervalTime,
                bleConnectionParameterUpdateCount = connectionParameterUpdates,
                watchfaceName = watchfaceName,
                watchfaceUuid = watchfaceUuid,
                appSecondTickSubscribed = secondTick,
            )
        }

        private fun u8(): Int = readByte()
        private fun u32(): Long = (0 until 4).fold(0L) { value, shift -> value or (readByte().toLong() shl (shift * 8)) }
        private fun i32(): Int = u32().toInt()
        private fun u64(): Long = (0 until 8).fold(0L) { value, shift -> value or (readByte().toLong() shl (shift * 8)) }
        private fun scaledUnsigned(): Int = u32().toInt().also { u16() }
        private fun scaledSigned(): Int = i32().also { u16() }
        private fun u16(): Int = readByte() or (readByte() shl 8)

        private fun fixedString(length: Int): String {
            val start = offset
            skip(length)
            val end = (start until offset).firstOrNull { bytes[it] == 0.toByte() } ?: offset
            return bytes.copyOfRange(start, end).decodeToString()
        }

        private fun readByte(): Int {
            require(offset < bytes.size)
            return bytes[offset++].toInt() and 0xff
        }

        private fun skip(count: Int) {
            require(offset + count <= bytes.size)
            offset += count
        }
    }
}
