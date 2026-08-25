package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import io.rebble.libpebblecommon.health.parsers.parseStepsData
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthParsingTest {
    @Test
    fun parsesInitialV4AndHeartRateWeightV12Records() {
        val v4Buffer = DataBuffer(UByteArray(14))
        v4Buffer.setEndian(Endian.Little)
        v4Buffer.putUShort(4u)
        v4Buffer.putUInt(1_700_000_000u)
        v4Buffer.putByte(0)
        v4Buffer.putUByte(0u)
        v4Buffer.putUByte(1u)
        v4Buffer.putUByte(42u)
        v4Buffer.putUByte(3u)
        v4Buffer.putUShort(99u)
        v4Buffer.putUByte(7u)

        val v4 = parseStepsData(v4Buffer.array().toByteArray(), 14u).single()
        assertEquals(42, v4.steps)
        assertEquals(0, v4.pluggedIn)

        val v12Buffer = DataBuffer(UByteArray(24))
        v12Buffer.setEndian(Endian.Little)
        v12Buffer.putUShort(12u)
        v12Buffer.putUInt(1_700_000_000u)
        v12Buffer.putByte(0)
        v12Buffer.putUByte(0u)
        v12Buffer.putUByte(1u)
        v12Buffer.putUByte(42u)
        v12Buffer.putUByte(3u)
        v12Buffer.putUShort(99u)
        v12Buffer.putUByte(7u)
        v12Buffer.putUByte(3u)
        v12Buffer.putUShort(10u)
        v12Buffer.putUShort(11u)
        v12Buffer.putUShort(12u)
        v12Buffer.putUByte(70u)
        v12Buffer.putUShort(13u)

        val v12 = parseStepsData(v12Buffer.array().toByteArray(), 24u).single()
        assertEquals(13, v12.heartRateWeight)
        assertEquals(0, v12.heartRateZone)
    }

    @Test
    fun parsesV13AndV14ChargingAndSleepIntentFields() {
        fun payload(version: UShort, extra: UByte? = null): ByteArray {
            val buffer = DataBuffer(UByteArray(if (extra == null) 25 else 26))
            buffer.setEndian(Endian.Little)
            buffer.putUShort(version)
            buffer.putUInt(1_700_000_000u)
            buffer.putByte((-4).toByte())
            buffer.putUByte(0u)
            buffer.putUByte(1u)
            buffer.putUByte(42u)
            buffer.putUByte(3u)
            buffer.putUShort(99u)
            buffer.putUByte(7u)
            buffer.putUByte(3u)
            buffer.putUShort(10u)
            buffer.putUShort(11u)
            buffer.putUShort(12u)
            buffer.putUByte(70u)
            buffer.putUShort(13u)
            buffer.putUByte(2u)
            extra?.let(buffer::putUByte)
            return buffer.array().toByteArray()
        }

        val v13 = parseStepsData(payload(13u), 25u).single()
        assertEquals(1, v13.pluggedIn)
        assertEquals(0, v13.sleepIntentHint)
        assertEquals(0, v13.sleepScore)
        assertEquals(0, v13.sleepFlags)
        assertEquals(-4, v13.timezoneOffset15Minutes)

        val v14 = parseStepsData(payload(14u, 1u), 26u).single()
        assertEquals(1, v14.pluggedIn)
        assertEquals(1, v14.sleepIntentHint)
        assertEquals(0, v14.sleepScore)
        assertEquals(0, v14.sleepFlags)
        assertEquals(-4, v14.timezoneOffset15Minutes)
    }

    @Test
    fun parsesV15SleepDiagnosticsWithUnsignedScore() {
        val flags = SleepDiagnosticFlags.SCORE_VALID or
            SleepDiagnosticFlags.SLEEP_MINUTE or
            SleepDiagnosticFlags.SESSION_ACCEPTED or
            SleepDiagnosticFlags.HRM_OFF_WRIST_INPUT

        val record = v15Payload(
            timestamp = 1_700_000_000u,
            sleepScore = UInt.MAX_VALUE,
            sleepFlags = flags.toUShort(),
        )

        val parsed = parseStepsData(record, record.size.toUShort()).single()

        assertEquals(4_294_967_295L, parsed.sleepScore)
        assertEquals(flags, parsed.sleepFlags)
        assertEquals(1, parsed.sleepIntentHint)
    }

    @Test
    fun truncatedV14ItemDoesNotConsumeFollowingItem() {
        fun payload(version: UShort, recordSize: Int): ByteArray {
            val buffer = DataBuffer(UByteArray(9 + recordSize))
            buffer.setEndian(Endian.Little)
            buffer.putUShort(version)
            buffer.putUInt(if (version == 14.toUShort()) 1_700_000_000u else 1_700_000_060u)
            buffer.putByte(0)
            buffer.putUByte(0u)
            buffer.putUByte(1u)
            buffer.putUByte(if (version == 14.toUShort()) 10u else 20u)
            buffer.putUByte(1u)
            buffer.putUShort(1u)
            buffer.putUByte(1u)
            buffer.putUByte(0u)
            buffer.putUShort(1u)
            buffer.putUShort(1u)
            buffer.putUShort(1u)
            buffer.putUByte(1u)
            buffer.putUShort(1u)
            buffer.putUByte(1u)
            if (version == 14.toUShort() && recordSize == 17) buffer.putUByte(1u)
            return buffer.array().toByteArray()
        }

        val truncatedV14 = payload(14u, 16)
        val validV13 = payload(13u, 16)

        val records = parseStepsData(truncatedV14 + validV13, 25u)

        assertEquals(1, records.size)
        assertEquals(1_700_000_060L, records.single().timestamp)
        assertEquals(20, records.single().steps)
    }

    @Test
    fun truncatedV15ItemDoesNotConsumeFollowingItem() {
        val truncatedV15 = v15Payload(
            timestamp = 1_700_000_000u,
            sleepScore = 100u,
            sleepFlags = SleepDiagnosticFlags.SCORE_VALID.toUShort(),
            recordSize = 22,
        )
        val validV14 = v14Payload(1_700_000_060u).copyOf(truncatedV15.size)

        val records = parseStepsData(truncatedV15 + validV14, truncatedV15.size.toUShort())

        assertEquals(1, records.size)
        assertEquals(1_700_000_060L, records.single().timestamp)
        assertEquals(0L, records.single().sleepScore)
    }

    @Test
    fun testStepsParsing() {
        // Simulate a raw steps record buffer
        // Structure:
        // Header: Version(2), Timestamp(4), Unused(1), RecordLength(1), RecordNum(1)
        // Record: Steps(1), Orientation(1), Intensity(2), Light(1), Flags(1), RestingCal(2),
        // ActiveCal(2), Distance(2), HR(1), HRWeight(2), HRZone(1)

        val buffer = DataBuffer(UByteArray(100))
        buffer.setEndian(Endian.Little)

        // Header
        buffer.putUShort(1u) // Version
        buffer.putUInt(1600000000u) // Timestamp
        buffer.putUByte(0u) // Unused
        buffer.putUByte(16u) // RecordLength (approx)
        buffer.putUByte(2u) // RecordNum (2 records)

        // Record 1
        buffer.putUByte(100u) // Steps
        buffer.putUByte(1u) // Orientation
        buffer.putUShort(500u) // Intensity
        buffer.putUByte(10u) // Light
        buffer.putUByte(2u) // Flags (Active)
        buffer.putUShort(10u) // RestingCal
        buffer.putUShort(50u) // ActiveCal
        buffer.putUShort(7000u) // Distance
        buffer.putUByte(60u) // HR
        buffer.putUShort(1u) // HRWeight
        buffer.putUByte(1u) // HRZone

        // Record 2
        buffer.putUByte(150u) // Steps
        buffer.putUByte(2u) // Orientation
        buffer.putUShort(600u) // Intensity
        buffer.putUByte(20u) // Light
        buffer.putUByte(0u) // Flags
        buffer.putUShort(12u) // RestingCal
        buffer.putUShort(60u) // ActiveCal
        buffer.putUShort(8000u) // Distance
        buffer.putUByte(65u) // HR
        buffer.putUShort(1u) // HRWeight
        buffer.putUByte(1u) // HRZone

        val data = buffer.array()

        // Now verify parsing logic (mimicking Datalogging.kt)
        val readBuffer = DataBuffer(data.toUByteArray())
        readBuffer.setEndian(Endian.Little)

        val version = readBuffer.getUShort()
        val timestamp = readBuffer.getUInt()
        readBuffer.getByte()
        val recordLength = readBuffer.getByte()
        val recordNum = readBuffer.getByte()

        assertEquals(1u, version)
        assertEquals(1600000000u, timestamp)
        assertEquals(2, recordNum)

        var currentTimestamp = timestamp

        for (i in 0 until recordNum.toInt()) {
            val rawRecord = RawStepsRecord()
            rawRecord.fromBytes(readBuffer)

            if (i == 0) {
                assertEquals(100u, rawRecord.steps.get())
                assertEquals(500u, rawRecord.intensity.get())
                assertEquals(1600000000u, currentTimestamp)
            } else {
                assertEquals(150u, rawRecord.steps.get())
                assertEquals(1600000060u, currentTimestamp)
            }
            currentTimestamp += 60u
        }
    }

    private fun v15Payload(
        timestamp: UInt,
        sleepScore: UInt,
        sleepFlags: UShort,
        recordSize: Int = 23,
    ): ByteArray {
        val buffer = DataBuffer(UByteArray(9 + recordSize))
        buffer.setEndian(Endian.Little)
        buffer.putUShort(15u)
        buffer.putUInt(timestamp)
        buffer.putByte(0)
        buffer.putUByte(0u)
        buffer.putUByte(1u)
        buffer.putUByte(42u)
        buffer.putUByte(3u)
        buffer.putUShort(99u)
        buffer.putUByte(7u)
        buffer.putUByte(3u)
        buffer.putUShort(10u)
        buffer.putUShort(11u)
        buffer.putUShort(12u)
        buffer.putUByte(70u)
        buffer.putUShort(13u)
        buffer.putUByte(2u)
        buffer.putUByte(1u)
        if (recordSize >= 21) buffer.putUInt(sleepScore)
        if (recordSize >= 23) buffer.putUShort(sleepFlags)
        return buffer.array().toByteArray()
    }

    private fun v14Payload(timestamp: UInt): ByteArray {
        val buffer = DataBuffer(UByteArray(26))
        buffer.setEndian(Endian.Little)
        buffer.putUShort(14u)
        buffer.putUInt(timestamp)
        buffer.putByte(0)
        buffer.putUByte(0u)
        buffer.putUByte(1u)
        buffer.putUByte(42u)
        buffer.putUByte(3u)
        buffer.putUShort(99u)
        buffer.putUByte(7u)
        buffer.putUByte(3u)
        buffer.putUShort(10u)
        buffer.putUShort(11u)
        buffer.putUShort(12u)
        buffer.putUByte(70u)
        buffer.putUShort(13u)
        buffer.putUByte(2u)
        buffer.putUByte(1u)
        return buffer.array().toByteArray()
    }
}
