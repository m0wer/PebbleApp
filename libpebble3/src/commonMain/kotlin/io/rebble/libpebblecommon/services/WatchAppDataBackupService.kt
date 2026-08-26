package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.WatchAppDataBackupCommand
import io.rebble.libpebblecommon.packets.WatchAppDataBackupRequest
import io.rebble.libpebblecommon.packets.WatchAppDataBackupResponse
import io.rebble.libpebblecommon.packets.WatchAppDataBackupStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

data class WatchAppDataBackupInfo(
    val protocolFeatures: UInt,
    val maxValueLength: UShort,
    val maxStoreBytes: UInt,
    val maxPayloadLength: UShort,
)

data class WatchAppDataBackupRecord(val key: UInt, val value: UByteArray)

data class WatchAppDataBackupStore(val uuid: Uuid, val records: List<WatchAppDataBackupRecord>)

data class WatchAppDataBackup(val stores: List<WatchAppDataBackupStore>)

sealed class WatchAppDataBackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported : WatchAppDataBackupException("Watch app data backup is not supported by this watch")
    class Validation(message: String) : WatchAppDataBackupException(message)
    class Protocol(message: String) : WatchAppDataBackupException(message)
    class Status(
        val status: WatchAppDataBackupStatus,
        val command: WatchAppDataBackupCommand,
        val transactionId: UInt,
    ) : WatchAppDataBackupException("Watch app data backup ${command.name} failed with ${status.name}")
}

class WatchAppDataBackupService(
    private val protocolHandler: PebbleProtocolHandler,
) : ConnectedPebble.WatchAppDataBackup {
    private val operationMutex = Mutex()
    private var supported = false
    private var nextRequestId: UShort = 1u

    fun init(supported: Boolean) {
        this.supported = supported
    }

    override suspend fun getInfo(): WatchAppDataBackupInfo = operationMutex.withLock {
        requireSupported()
        parseInfo(request(WatchAppDataBackupCommand.GET_INFO, 0u).payload.get())
    }

    override suspend fun export(): WatchAppDataBackup = operationMutex.withLock {
        requireSupported()
        var lastStaleSnapshot: WatchAppDataBackupException.Status? = null
        repeat(2) { attempt ->
            try {
                return@withLock exportOnce()
            } catch (error: WatchAppDataBackupException.Status) {
                if (error.status != WatchAppDataBackupStatus.STALE_SNAPSHOT || attempt == 1) {
                    throw error
                }
                lastStaleSnapshot = error
            }
        }
        throw checkNotNull(lastStaleSnapshot)
    }

    override suspend fun restore(backup: WatchAppDataBackup) = operationMutex.withLock {
        requireSupported()
        val prepared = prepareBackup(backup)
        var transactionId: UInt? = null
        try {
            val totals = backupTotals(prepared)
            transactionId = openImport(totals)
            for (store in prepared) {
                val metadata = storeMetadata(store)
                request(
                    WatchAppDataBackupCommand.BEGIN_STORE,
                    transactionId,
                    payload = payload {
                        uuid(store.uuid)
                        uint(metadata.recordCount)
                        uint(metadata.valueBytes)
                        uint(metadata.crc32)
                    },
                ).requireEmptyPayload()
                store.records.forEachIndexed { sequence, record ->
                    request(
                        WatchAppDataBackupCommand.PUT_RECORD,
                        transactionId,
                        payload = payload {
                            uint(sequence.toUInt())
                            uint(record.key)
                            ushort(record.value.size.toUShort())
                            bytes(record.value)
                        },
                    ).requireEmptyPayload()
                }
                request(WatchAppDataBackupCommand.COMMIT_STORE, transactionId).requireEmptyPayload()
            }
            request(WatchAppDataBackupCommand.FINISH_IMPORT, transactionId).requireEmptyPayload()
        } catch (error: Exception) {
            transactionId?.let { bestEffortCancel(it) }
            throw error
        }
    }

    private suspend fun exportOnce(): WatchAppDataBackup {
        var transactionId: UInt? = null
        try {
            transactionId = openExport()
            val stores = mutableListOf<WatchAppDataBackupStore>()
            var cursor = 0u.toUShort()
            var inventoryGeneration: UInt? = null
            do {
                val inventory = parseInventory(
                    request(
                        WatchAppDataBackupCommand.LIST_STORES,
                        transactionId,
                        payload { ushort(cursor) },
                    ).payload.get(),
                )
                if (inventoryGeneration != null && inventoryGeneration != inventory.generation) {
                    throw WatchAppDataBackupException.Protocol("Inventory generation changed during export")
                }
                inventoryGeneration = inventory.generation
                if (!inventory.done && inventory.nextCursor == cursor) {
                    throw WatchAppDataBackupException.Protocol("Inventory pagination did not advance")
                }
                cursor = inventory.nextCursor
                inventory.uuids.forEach { stores += exportStore(transactionId, it) }
                if (inventory.done) break
            } while (true)
            if (stores.map { it.uuid }.toSet().size != stores.size) {
                throw WatchAppDataBackupException.Protocol("Inventory contains duplicate store UUIDs")
            }
            request(WatchAppDataBackupCommand.FINISH_EXPORT, transactionId).requireEmptyPayload()
            return WatchAppDataBackup(stores.sortedWith(::compareStores))
        } catch (error: Exception) {
            transactionId?.let { bestEffortCancel(it) }
            throw error
        }
    }

    private suspend fun exportStore(transactionId: UInt, uuid: Uuid): WatchAppDataBackupStore {
        request(WatchAppDataBackupCommand.OPEN_STORE, transactionId, payload { uuid(uuid) }).requireEmptyPayload()
        val records = mutableListOf<WatchAppDataBackupRecord>()
        var done = false
        while (!done) {
            val page = parseRecordPage(
                request(
                    WatchAppDataBackupCommand.READ_PAGE,
                    transactionId,
                    payload { ushort(UShort.MAX_VALUE) },
                ).payload.get(),
            )
            if (!page.done && page.records.isEmpty()) {
                throw WatchAppDataBackupException.Protocol("Store $uuid pagination did not advance")
            }
            records += page.records
            done = page.done
        }
        if (records.map { it.key }.toSet().size != records.size) {
            throw WatchAppDataBackupException.Protocol("Store $uuid contains duplicate record keys")
        }
        val close = parseStoreMetadata(
            request(WatchAppDataBackupCommand.CLOSE_STORE, transactionId).payload.get(),
        )
        val local = storeMetadata(WatchAppDataBackupStore(uuid, records))
        if (close != local) {
            throw WatchAppDataBackupException.Protocol("Store $uuid metadata did not match exported records")
        }
        return WatchAppDataBackupStore(uuid, records.sortedWith(::compareRecords))
    }

    private suspend fun openExport(): UInt = open(WatchAppDataBackupCommand.OPEN_EXPORT, ubyteArrayOf())

    private suspend fun openImport(totals: BackupTotals): UInt = open(
        WatchAppDataBackupCommand.OPEN_IMPORT,
        payload {
            uint(totals.storeCount)
            uint(totals.recordCount)
            uint(totals.valueBytes)
        },
    )

    private suspend fun open(command: WatchAppDataBackupCommand, payload: UByteArray): UInt {
        val requestId = allocateRequestId()
        val request = WatchAppDataBackupRequest(command, requestId, 0u, payload)
        val response = withTimeout(OPEN_TIMEOUT) {
            coroutineScope {
                val awaitedResponse = async(start = CoroutineStart.UNDISPATCHED) {
                    protocolHandler.inboundMessages
                        .filterIsInstance<WatchAppDataBackupResponse>()
                        .first { candidate ->
                            if (!matches(candidate, command, requestId, null)) return@first false
                            when (candidate.statusValue()) {
                                WatchAppDataBackupStatus.PENDING -> {
                                    if (candidate.transactionId.get() != 0u) {
                                        throw WatchAppDataBackupException.Protocol("Pending $command response had a transaction ID")
                                    }
                                    false
                                }
                                WatchAppDataBackupStatus.OK -> candidate.transactionId.get() != 0u
                                else -> true
                            }
                        }
                    }
                protocolHandler.send(request)
                awaitedResponse.await()
            }
        }
        response.requireOk(command)
        val transactionId = response.transactionId.get()
        if (transactionId == 0u) {
            throw WatchAppDataBackupException.Protocol("$command completed without a transaction ID")
        }
        response.requireEmptyPayload()
        return transactionId
    }

    private suspend fun request(
        command: WatchAppDataBackupCommand,
        transactionId: UInt,
        payload: UByteArray = ubyteArrayOf(),
    ): WatchAppDataBackupResponse {
        val requestId = allocateRequestId()
        val request = WatchAppDataBackupRequest(command, requestId, transactionId, payload)
        val response = withTimeout(NORMAL_TIMEOUT) {
            coroutineScope {
                val awaitedResponse = async(start = CoroutineStart.UNDISPATCHED) {
                    protocolHandler.inboundMessages
                        .filterIsInstance<WatchAppDataBackupResponse>()
                        .first { matches(it, command, requestId, transactionId) }
                }
                protocolHandler.send(request)
                awaitedResponse.await()
            }
        }
        response.requireOk(command)
        return response
    }

    private suspend fun bestEffortCancel(transactionId: UInt) {
        withContext(NonCancellable) {
            try {
                withTimeoutOrNull(CANCEL_SEND_TIMEOUT) {
                    protocolHandler.send(
                        WatchAppDataBackupRequest(
                            WatchAppDataBackupCommand.CANCEL,
                            allocateRequestId(),
                            transactionId,
                        ),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun requireSupported() {
        if (!supported) throw WatchAppDataBackupException.Unsupported()
    }

    private fun allocateRequestId(): UShort {
        val requestId = nextRequestId
        nextRequestId = (nextRequestId.toUInt() + 1u).toUShort()
        return requestId
    }

    private fun matches(
        response: WatchAppDataBackupResponse,
        command: WatchAppDataBackupCommand,
        requestId: UShort,
        transactionId: UInt?,
    ): Boolean = response.version.get() == WatchAppDataBackupRequest.VERSION &&
        response.command.get() == (command.value.toInt() or 0x80).toUByte() &&
        response.requestId.get() == requestId &&
        (transactionId == null || response.transactionId.get() == transactionId)

    private fun WatchAppDataBackupResponse.statusValue(): WatchAppDataBackupStatus =
        WatchAppDataBackupStatus.fromValue(status.get())
            ?: throw WatchAppDataBackupException.Protocol("Unknown watch app data backup status ${status.get()}")

    private fun WatchAppDataBackupResponse.requireOk(command: WatchAppDataBackupCommand) {
        val status = statusValue()
        if (status != WatchAppDataBackupStatus.OK) {
            throw WatchAppDataBackupException.Status(status, command, transactionId.get())
        }
    }

    private fun WatchAppDataBackupResponse.requireEmptyPayload() {
        if (payload.get().isNotEmpty()) {
            throw WatchAppDataBackupException.Protocol("Unexpected response payload")
        }
    }

    private fun parseInfo(bytes: UByteArray): WatchAppDataBackupInfo = reader(bytes).run {
        val info = WatchAppDataBackupInfo(uint(), ushort(), uint(), ushort())
        requireEnd()
        info
    }

    private fun parseInventory(bytes: UByteArray): InventoryPage = reader(bytes).run {
        val generation = uint()
        val nextCursor = ushort()
        val done = boolean()
        val count = ubyte().toInt()
        val uuids = List(count) { uuid() }
        requireEnd()
        InventoryPage(generation, nextCursor, done, uuids)
    }

    private fun parseRecordPage(bytes: UByteArray): RecordPage = reader(bytes).run {
        val done = boolean()
        val count = ushort().toInt()
        val records = List(count) {
            val key = uint()
            val valueLength = ushort().toInt()
            if (valueLength !in 1..MAX_VALUE_LENGTH) {
                throw WatchAppDataBackupException.Protocol("Invalid exported value length $valueLength")
            }
            WatchAppDataBackupRecord(key, bytes(valueLength))
        }
        requireEnd()
        RecordPage(done, records)
    }

    private fun parseStoreMetadata(bytes: UByteArray): StoreMetadata = reader(bytes).run {
        val metadata = StoreMetadata(uint(), uint(), uint())
        requireEnd()
        metadata
    }

    private fun prepareBackup(backup: WatchAppDataBackup): List<WatchAppDataBackupStore> {
        if (backup.stores.map { it.uuid }.toSet().size != backup.stores.size) {
            throw WatchAppDataBackupException.Validation("Backup contains duplicate store UUIDs")
        }
        return backup.stores.map { store ->
            if (store.records.map { it.key }.toSet().size != store.records.size) {
                throw WatchAppDataBackupException.Validation("Store ${store.uuid} contains duplicate record keys")
            }
            var storeBytes = 0L
            store.records.forEach { record ->
                if (record.value.size !in 1..MAX_VALUE_LENGTH) {
                    throw WatchAppDataBackupException.Validation("Store ${store.uuid} has an invalid value length")
                }
                storeBytes += record.value.size
            }
            if (storeBytes > MAX_STORE_BYTES) {
                throw WatchAppDataBackupException.Validation("Store ${store.uuid} exceeds the 1 MiB limit")
            }
            WatchAppDataBackupStore(store.uuid, store.records.sortedWith(::compareRecords))
        }.sortedWith(::compareStores)
    }

    private fun backupTotals(stores: List<WatchAppDataBackupStore>): BackupTotals {
        var records = 0L
        var values = 0L
        stores.forEach { store ->
            records += store.records.size
            values += store.records.sumOf { it.value.size.toLong() }
        }
        if (stores.size.toLong() > UInt.MAX_VALUE.toLong() ||
            records > UInt.MAX_VALUE.toLong() || values > UInt.MAX_VALUE.toLong()) {
            throw WatchAppDataBackupException.Validation("Backup totals exceed protocol limits")
        }
        return BackupTotals(stores.size.toUInt(), records.toUInt(), values.toUInt())
    }

    private fun storeMetadata(store: WatchAppDataBackupStore): StoreMetadata {
        var crc = 0u
        var valueBytes = 0L
        store.records.forEach { record ->
            val canonical = payload {
                uint(record.key)
                ushort(record.value.size.toUShort())
                bytes(record.value)
            }
            crc = crc32(crc, canonical)
            valueBytes += record.value.size
        }
        if (store.records.size.toLong() > UInt.MAX_VALUE.toLong() || valueBytes > UInt.MAX_VALUE.toLong()) {
            throw WatchAppDataBackupException.Validation("Store ${store.uuid} totals exceed protocol limits")
        }
        return StoreMetadata(store.records.size.toUInt(), valueBytes.toUInt(), crc)
    }

    private data class InventoryPage(
        val generation: UInt,
        val nextCursor: UShort,
        val done: Boolean,
        val uuids: List<Uuid>,
    )

    private data class RecordPage(val done: Boolean, val records: List<WatchAppDataBackupRecord>)
    private data class StoreMetadata(val recordCount: UInt, val valueBytes: UInt, val crc32: UInt)
    private data class BackupTotals(val storeCount: UInt, val recordCount: UInt, val valueBytes: UInt)

    private companion object {
        val OPEN_TIMEOUT: Duration = 35.seconds
        val NORMAL_TIMEOUT: Duration = 10.seconds
        val CANCEL_SEND_TIMEOUT: Duration = 1.seconds
        const val MAX_VALUE_LENGTH = 256
        const val MAX_STORE_BYTES = 1024L * 1024L
    }
}

private class BackupPayloadWriter {
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

private fun payload(block: BackupPayloadWriter.() -> Unit): UByteArray = BackupPayloadWriter().apply(block).build()

private class BackupPayloadReader(private val input: UByteArray) {
    private var position = 0

    fun ubyte(): UByte = bytes(1)[0]

    fun ushort(): UShort {
        val bytes = bytes(2)
        return ((bytes[0].toUInt() shl 8) or bytes[1].toUInt()).toUShort()
    }

    fun uint(): UInt {
        val bytes = bytes(4)
        return (bytes[0].toUInt() shl 24) or (bytes[1].toUInt() shl 16) or
            (bytes[2].toUInt() shl 8) or bytes[3].toUInt()
    }

    fun uuid(): Uuid = Uuid.fromByteArray(bytes(16).asByteArray())

    fun bytes(count: Int): UByteArray {
        if (count < 0 || position + count > input.size) {
            throw WatchAppDataBackupException.Protocol("Malformed watch app data backup payload")
        }
        return input.copyOfRange(position, (position + count).also { position = it })
    }

    fun boolean(): Boolean = when (val value = ubyte()) {
        0u.toUByte() -> false
        1u.toUByte() -> true
        else -> throw WatchAppDataBackupException.Protocol("Invalid boolean value $value")
    }

    fun requireEnd() {
        if (position != input.size) {
            throw WatchAppDataBackupException.Protocol("Unexpected trailing response payload")
        }
    }
}

private fun reader(input: UByteArray): BackupPayloadReader = BackupPayloadReader(input)

private fun compareStores(left: WatchAppDataBackupStore, right: WatchAppDataBackupStore): Int =
    compareUuid(left.uuid, right.uuid)

private fun compareRecords(left: WatchAppDataBackupRecord, right: WatchAppDataBackupRecord): Int = left.key.compareTo(right.key)

private fun compareUuid(left: Uuid, right: Uuid): Int {
    val leftBytes = left.toByteArray()
    val rightBytes = right.toByteArray()
    for (index in leftBytes.indices) {
        val comparison = leftBytes[index].toUByte().compareTo(rightBytes[index].toUByte())
        if (comparison != 0) return comparison
    }
    return 0
}

private fun crc32(initial: UInt, bytes: UByteArray): UInt {
    var crc = initial xor UInt.MAX_VALUE
    bytes.forEach { byte ->
        crc = crc xor byte.toUInt()
        repeat(8) {
            crc = if ((crc and 1u) != 0u) (crc shr 1) xor 0xedb88320u else crc shr 1
        }
    }
    return crc xor UInt.MAX_VALUE
}
