package io.rebble.libpebblecommon.services

import TestPebbleProtocolHandler
import io.rebble.libpebblecommon.packets.WatchAppDataBackupCommand
import io.rebble.libpebblecommon.packets.WatchAppDataBackupRequest
import io.rebble.libpebblecommon.packets.WatchAppDataBackupResponse
import io.rebble.libpebblecommon.packets.WatchAppDataBackupStatus
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WatchAppDataBackupServiceTest {
    @Test
    fun `export waits through pending correlates responses and validates metadata`() = runTest {
        val uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        val sent = mutableListOf<WatchAppDataBackupRequest>()
        val handler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            sent += request
            when (command(request)) {
                WatchAppDataBackupCommand.OPEN_EXPORT -> {
                    receivePacket(response(request, WatchAppDataBackupStatus.PENDING, 0u))
                    receivePacket(response(request, WatchAppDataBackupStatus.OK, 9u))
                }

                WatchAppDataBackupCommand.LIST_STORES -> receivePacket(response(request, payload = payload {
                    uint(7u)
                    ushort(1u)
                    ubyte(1u)
                    ubyte(1u)
                    uuid(uuid)
                }))

                WatchAppDataBackupCommand.OPEN_STORE -> receivePacket(response(request))
                WatchAppDataBackupCommand.READ_PAGE -> {
                    receivePacket(response(request).apply { version.set(2u) })
                    receivePacket(response(request).apply { requestId.set((request.requestId.get() + 1u).toUShort()) })
                    receivePacket(response(request).apply { transactionId.set(99u) })
                    receivePacket(response(request).apply { command.set(0xffu) })
                    receivePacket(response(request, payload = payload {
                        ubyte(1u)
                        ushort(2u)
                        uint(UInt.MAX_VALUE)
                        ushort(1u)
                        bytes(ubyteArrayOf(9u))
                        uint(2u)
                        ushort(2u)
                        bytes(ubyteArrayOf(1u, 2u))
                    }))
                }

                WatchAppDataBackupCommand.CLOSE_STORE -> receivePacket(response(request, payload = payload {
                    uint(2u)
                    uint(3u)
                    uint(crc32(payload {
                        uint(UInt.MAX_VALUE)
                        ushort(1u)
                        bytes(ubyteArrayOf(9u))
                        uint(2u)
                        ushort(2u)
                        bytes(ubyteArrayOf(1u, 2u))
                    }))
                }))

                WatchAppDataBackupCommand.FINISH_EXPORT -> receivePacket(response(request))
                else -> error("Unexpected command ${command(request)}")
            }
        }
        val service = WatchAppDataBackupService(handler)
        service.init(true)

        val backup = service.export()

        assertEquals(listOf(uuid), backup.stores.map { it.uuid })
        assertEquals(listOf(2u, UInt.MAX_VALUE), backup.stores.single().records.map { it.key })
        assertEquals(
            listOf(
                WatchAppDataBackupCommand.OPEN_EXPORT,
                WatchAppDataBackupCommand.LIST_STORES,
                WatchAppDataBackupCommand.OPEN_STORE,
                WatchAppDataBackupCommand.READ_PAGE,
                WatchAppDataBackupCommand.CLOSE_STORE,
                WatchAppDataBackupCommand.FINISH_EXPORT,
            ),
            sent.map(::command),
        )
        assertTrue(sent.all { it.transactionId.get() == 0u || it.transactionId.get() == 9u })
    }

    @Test
    fun `restore sorts stores and records and resets sequence per store`() = runTest {
        val first = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        val second = Uuid.parse("10112233-4455-6677-8899-aabbccddeeff")
        val sent = mutableListOf<WatchAppDataBackupRequest>()
        val handler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            sent += request
            when (command(request)) {
                WatchAppDataBackupCommand.OPEN_IMPORT -> {
                    receivePacket(response(request, WatchAppDataBackupStatus.PENDING, 0u))
                    receivePacket(response(request, WatchAppDataBackupStatus.OK, 3u))
                }

                WatchAppDataBackupCommand.BEGIN_STORE,
                WatchAppDataBackupCommand.PUT_RECORD,
                WatchAppDataBackupCommand.COMMIT_STORE,
                WatchAppDataBackupCommand.FINISH_IMPORT -> receivePacket(response(request))
                else -> error("Unexpected command ${command(request)}")
            }
        }
        val service = WatchAppDataBackupService(handler)
        service.init(true)

        service.restore(
            WatchAppDataBackup(
                listOf(
                    WatchAppDataBackupStore(second, listOf(WatchAppDataBackupRecord(2u, ubyteArrayOf(2u)))),
                    WatchAppDataBackupStore(
                        first,
                        listOf(
                            WatchAppDataBackupRecord(9u, ubyteArrayOf(9u)),
                            WatchAppDataBackupRecord(1u, ubyteArrayOf(1u)),
                        ),
                    ),
                ),
            ),
        )

        val begins = sent.filter { command(it) == WatchAppDataBackupCommand.BEGIN_STORE }
        assertEquals(listOf(first, second), begins.map { Uuid.fromByteArray(it.payload.get().copyOfRange(0, 16).asByteArray()) })
        val openImport = sent.single { command(it) == WatchAppDataBackupCommand.OPEN_IMPORT }.payload.get()
        assertEquals(2u, uintAt(openImport, 0))
        assertEquals(3u, uintAt(openImport, 4))
        assertEquals(3u, uintAt(openImport, 8))
        assertEquals(2u, uintAt(begins[0].payload.get(), 16))
        assertEquals(2u, uintAt(begins[0].payload.get(), 20))
        assertEquals(
            crc32(payload {
                uint(1u)
                ushort(1u)
                ubyte(1u)
                uint(9u)
                ushort(1u)
                ubyte(9u)
            }),
            uintAt(begins[0].payload.get(), 24),
        )
        val puts = sent.filter { command(it) == WatchAppDataBackupCommand.PUT_RECORD }
        assertEquals(listOf(0u, 1u, 0u), puts.map { uintAt(it.payload.get(), 0) })
        assertEquals(listOf(1u, 9u, 2u), puts.map { uintAt(it.payload.get(), 4) })
    }

    @Test
    fun `stale export retries once and cancels failed transaction`() = runTest {
        var opens = 0
        var cancels = 0
        val handler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            when (command(request)) {
                WatchAppDataBackupCommand.OPEN_EXPORT -> {
                    opens++
                    receivePacket(response(request, WatchAppDataBackupStatus.PENDING, 0u))
                    receivePacket(response(request, WatchAppDataBackupStatus.OK, opens.toUInt()))
                }

                WatchAppDataBackupCommand.LIST_STORES -> receivePacket(
                    response(
                        request,
                        if (opens == 1) WatchAppDataBackupStatus.STALE_SNAPSHOT else WatchAppDataBackupStatus.OK,
                        request.transactionId.get(),
                        if (opens == 1) ubyteArrayOf() else payload {
                            uint(1u)
                            ushort(0u)
                            ubyte(1u)
                            ubyte(0u)
                        },
                    ),
                )

                WatchAppDataBackupCommand.CANCEL -> cancels++
                WatchAppDataBackupCommand.FINISH_EXPORT -> receivePacket(response(request))
                else -> error("Unexpected command ${command(request)}")
            }
        }
        val service = WatchAppDataBackupService(handler)
        service.init(true)

        assertEquals(emptyList(), service.export().stores)
        assertEquals(2, opens)
        assertEquals(1, cancels)
    }

    @Test
    fun `export rejects a record page that does not advance`() = runTest {
        val uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        var cancelled = false
        val handler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            when (command(request)) {
                WatchAppDataBackupCommand.OPEN_EXPORT -> {
                    receivePacket(response(request, WatchAppDataBackupStatus.PENDING, 0u))
                    receivePacket(response(request, WatchAppDataBackupStatus.OK, 9u))
                }
                WatchAppDataBackupCommand.LIST_STORES -> receivePacket(response(request, payload = payload {
                    uint(7u)
                    ushort(1u)
                    ubyte(1u)
                    ubyte(1u)
                    uuid(uuid)
                }))
                WatchAppDataBackupCommand.OPEN_STORE -> receivePacket(response(request))
                WatchAppDataBackupCommand.READ_PAGE -> receivePacket(response(request, payload = payload {
                    ubyte(0u)
                    ushort(0u)
                }))
                WatchAppDataBackupCommand.CANCEL -> {
                    cancelled = true
                    awaitCancellation()
                }
                else -> error("Unexpected command ${command(request)}")
            }
        }
        val service = WatchAppDataBackupService(handler)
        service.init(true)

        assertFailsWith<WatchAppDataBackupException.Protocol> { service.export() }
        assertTrue(cancelled)
    }

    @Test
    fun `matching response with unknown status is rejected`() = runTest {
        val handler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            receivePacket(response(request).apply { status.set(99u) })
        }
        val service = WatchAppDataBackupService(handler)
        service.init(true)

        assertFailsWith<WatchAppDataBackupException.Protocol> { service.getInfo() }
    }

    @Test
    fun `unsupported operation fails before sending and status is typed`() = runTest {
        val handler = TestPebbleProtocolHandler { error("No packet should be sent") }
        val unsupported = WatchAppDataBackupService(handler)
        assertFailsWith<WatchAppDataBackupException.Unsupported> { unsupported.getInfo() }

        val statusHandler = TestPebbleProtocolHandler { packet ->
            val request = packet as WatchAppDataBackupRequest
            receivePacket(response(request, WatchAppDataBackupStatus.DENIED, 0u))
        }
        val supported = WatchAppDataBackupService(statusHandler)
        supported.init(true)
        val error = assertFailsWith<WatchAppDataBackupException.Status> { supported.getInfo() }
        assertEquals(WatchAppDataBackupStatus.DENIED, error.status)
    }
}

private fun command(request: WatchAppDataBackupRequest): WatchAppDataBackupCommand =
    WatchAppDataBackupCommand.entries.first { it.value == request.command.get() }

private fun response(
    request: WatchAppDataBackupRequest,
    status: WatchAppDataBackupStatus = WatchAppDataBackupStatus.OK,
    transactionId: UInt = request.transactionId.get(),
    payload: UByteArray = ubyteArrayOf(),
): WatchAppDataBackupResponse = WatchAppDataBackupResponse(payload.size).apply {
    command.set((request.command.get().toInt() or 0x80).toUByte())
    version.set(1u)
    requestId.set(request.requestId.get())
    this.transactionId.set(transactionId)
    this.status.set(status.value)
    this.payload.set(payload)
}

private class PayloadWriter {
    private val output = mutableListOf<UByte>()

    fun ubyte(value: UByte) {
        output += value
    }

    fun ushort(value: UShort) {
        output += (value.toUInt() shr 8).toUByte()
        output += value.toUByte()
    }

    fun uint(value: UInt) {
        output += (value shr 24).toUByte()
        output += (value shr 16).toUByte()
        output += (value shr 8).toUByte()
        output += value.toUByte()
    }

    fun uuid(value: Uuid) {
        bytes(value.toByteArray().asUByteArray())
    }

    fun bytes(value: UByteArray) {
        output += value.toList()
    }

    fun build(): UByteArray = output.toUByteArray()
}

private fun payload(block: PayloadWriter.() -> Unit): UByteArray = PayloadWriter().apply(block).build()

private fun uintAt(bytes: UByteArray, offset: Int): UInt =
    (bytes[offset].toUInt() shl 24) or (bytes[offset + 1].toUInt() shl 16) or
        (bytes[offset + 2].toUInt() shl 8) or bytes[offset + 3].toUInt()

private fun crc32(bytes: UByteArray): UInt {
    var crc = UInt.MAX_VALUE
    bytes.forEach { byte ->
        crc = crc xor byte.toUInt()
        repeat(8) {
            crc = if ((crc and 1u) != 0u) (crc shr 1) xor 0xedb88320u else crc shr 1
        }
    }
    return crc xor UInt.MAX_VALUE
}
