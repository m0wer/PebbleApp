package io.rebble.libpebblecommon.datalogging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeAnalyticsHeartbeatParserTest {
    @Test
    fun parsesV3RecordWithExactNativeSize() {
        val record = nativeRecord(version = 3, size = V3_SIZE)
        record.putLongLittleEndian(1, 1_700_000_000)
        record.putIntLittleEndian(BATTERY_SOC_OFFSET, 4_999)

        val parsed = assertNotNull(NativeAnalyticsHeartbeatParser.parse(record))

        assertEquals(3, parsed.version)
        assertEquals(1_700_000_000, parsed.timestampSeconds)
        assertEquals(4_999, parsed.batterySocCentipercent)
        assertEquals(0, parsed.batteryCurrentUa)
    }

    @Test
    fun parsesV4AppendedCurrent() {
        val record = nativeRecord(version = 4, size = V4_SIZE)
        record.putIntLittleEndian(V3_SIZE, -42_000)

        val parsed = assertNotNull(NativeAnalyticsHeartbeatParser.parse(record))

        assertEquals(4, parsed.version)
        assertEquals(-42_000, parsed.batteryCurrentUa)
    }

    @Test
    fun rejectsUnknownVersionsAndMalformedSizes() {
        assertNull(NativeAnalyticsHeartbeatParser.parse(nativeRecord(version = 2, size = V3_SIZE)))
        assertNull(NativeAnalyticsHeartbeatParser.parse(nativeRecord(version = 3, size = V3_SIZE - 1)))
        assertNull(NativeAnalyticsHeartbeatParser.parse(nativeRecord(version = 4, size = V4_SIZE + 1)))
    }

    private fun nativeRecord(version: Int, size: Int) = ByteArray(size).also { it[0] = version.toByte() }

    private fun ByteArray.putIntLittleEndian(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun ByteArray.putLongLittleEndian(offset: Int, value: Long) {
        repeat(8) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private companion object {
        const val V3_SIZE = 567
        const val V4_SIZE = 571
        const val BATTERY_SOC_OFFSET = 102
    }
}
