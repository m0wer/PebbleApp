package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.util.Endian

enum class WatchAppDataBackupCommand(val value: UByte) {
    GET_INFO(0x01u),
    OPEN_EXPORT(0x02u),
    OPEN_IMPORT(0x03u),
    CANCEL(0x04u),
    LIST_STORES(0x10u),
    OPEN_STORE(0x11u),
    READ_PAGE(0x12u),
    CLOSE_STORE(0x13u),
    FINISH_EXPORT(0x14u),
    BEGIN_STORE(0x20u),
    PUT_RECORD(0x21u),
    COMMIT_STORE(0x22u),
    FINISH_IMPORT(0x23u),
}

enum class WatchAppDataBackupStatus(val value: UShort) {
    OK(0u),
    PENDING(1u),
    DENIED(2u),
    UNSUPPORTED_VERSION(3u),
    MALFORMED(4u),
    UNAUTHORIZED(5u),
    BUSY(6u),
    NOT_FOUND(7u),
    LIMIT_EXCEEDED(8u),
    STALE_SNAPSHOT(9u),
    CHECKSUM_MISMATCH(10u),
    TARGET_NOT_EMPTY(11u),
    STORAGE_FULL(12u),
    EXPIRED(13u),
    OUT_OF_ORDER(14u),
    INTERNAL(15u),
    ;

    companion object {
        fun fromValue(value: UShort): WatchAppDataBackupStatus? = entries.firstOrNull { it.value == value }
    }
}

class WatchAppDataBackupRequest(
    command: WatchAppDataBackupCommand,
    requestId: UShort,
    transactionId: UInt,
    payloadBytes: UByteArray = ubyteArrayOf(),
) : PebblePacket(ProtocolEndpoint.WATCH_APP_DATA_BACKUP) {
    val command = SUByte(m, command.value)
    val version = SUByte(m, VERSION)
    val requestId = SUShort(m, requestId, Endian.Big)
    val transactionId = SUInt(m, transactionId, Endian.Big)
    val payload = SBytes(m, payloadBytes.size, payloadBytes)

    companion object {
        const val VERSION: UByte = 1u
    }
}

class WatchAppDataBackupResponse(payloadLength: Int = 0) : PebblePacket(ProtocolEndpoint.WATCH_APP_DATA_BACKUP) {
    val command = SUByte(m)
    val version = SUByte(m)
    val requestId = SUShort(m, endianness = Endian.Big)
    val transactionId = SUInt(m, endianness = Endian.Big)
    val status = SUShort(m, endianness = Endian.Big)
    val payload = SBytes(m, payloadLength)
}

fun watchAppDataBackupPacketsRegister() {
    PacketRegistry.register(ProtocolEndpoint.WATCH_APP_DATA_BACKUP) { packet ->
        require(packet.size >= FRAME_HEADER_SIZE + RESPONSE_HEADER_SIZE) { "Malformed watch app data backup response" }
        WatchAppDataBackupResponse(packet.size - FRAME_HEADER_SIZE - RESPONSE_HEADER_SIZE)
    }
}

private const val FRAME_HEADER_SIZE = 4
private const val RESPONSE_HEADER_SIZE = 10
