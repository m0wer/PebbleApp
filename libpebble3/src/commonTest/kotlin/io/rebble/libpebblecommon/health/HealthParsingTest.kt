package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import io.rebble.libpebblecommon.health.parsers.parseStepsData
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthParsingTest {
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
        assertEquals(-4, v13.timezoneOffset15Minutes)

        val v14 = parseStepsData(payload(14u, 1u), 26u).single()
        assertEquals(1, v14.pluggedIn)
        assertEquals(1, v14.sleepIntentHint)
        assertEquals(-4, v14.timezoneOffset15Minutes)
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
}
