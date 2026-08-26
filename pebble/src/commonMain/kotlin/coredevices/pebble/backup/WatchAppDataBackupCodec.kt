package coredevices.pebble.backup

import io.rebble.libpebblecommon.services.WatchAppDataBackup
import io.rebble.libpebblecommon.services.WatchAppDataBackupRecord
import io.rebble.libpebblecommon.services.WatchAppDataBackupStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
object WatchAppDataBackupCodec {
    const val FORMAT = "pebble_watch_app_data_backup"
    const val SCHEMA_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L
    const val MAX_VALUE_BYTES = 256
    const val MAX_STORE_VALUE_BYTES = 1024 * 1024

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(backup: WatchAppDataBackup, exportedAtEpochMilliseconds: Long): String {
        val stores = validateAndSort(backup).stores
        val document = json.encodeToString(
            WatchAppDataBackupArchiveV1(
                format = FORMAT,
                schemaVersion = SCHEMA_VERSION,
                exportedAtEpochMilliseconds = exportedAtEpochMilliseconds,
                stores = stores.map { store ->
                    WatchAppDataBackupStoreV1(
                        uuid = store.uuid.toString(),
                        records = store.records.map { record ->
                            WatchAppDataBackupRecordV1(
                                key = record.key.toLong(),
                                valueBase64 = Base64.encode(record.value.toByteArray()),
                            )
                        },
                    )
                },
            )
        )
        requireDocumentSize(document)
        return document
    }

    fun decode(document: String): WatchAppDataBackup {
        requireDocumentSize(document)
        val root = json.parseToJsonElement(document)
        JsonDuplicateKeyValidator(document).validate()
        val archive = root.parseArchive()
        require(archive.format == FORMAT) { "Unsupported backup format." }
        require(archive.schemaVersion == SCHEMA_VERSION) { "Unsupported backup schema version." }
        return validateAndSort(
            WatchAppDataBackup(
                archive.stores.map { store ->
                    val uuid = parseCanonicalUuid(store.uuid)
                    WatchAppDataBackupStore(
                        uuid = uuid,
                        records = store.records.map { record ->
                            require(record.key in 0..UInt.MAX_VALUE.toLong()) { "Record key is outside the UInt range." }
                            WatchAppDataBackupRecord(
                                key = record.key.toUInt(),
                                value = decodeCanonicalBase64(record.valueBase64),
                            )
                        },
                    )
                },
            )
        )
    }

    private fun JsonElement.parseArchive(): WatchAppDataBackupArchiveV1 {
        val root = requiredObject("backup")
        root.requireKeys(ARCHIVE_KEYS, "backup")
        return WatchAppDataBackupArchiveV1(
            format = root.requiredString("format"),
            schemaVersion = root.requiredInt("schemaVersion"),
            exportedAtEpochMilliseconds = root.requiredLong("exportedAtEpochMilliseconds"),
            stores = root.requiredArray("stores").mapIndexed { storeIndex, element ->
                val store = element.requiredObject("stores[$storeIndex]")
                store.requireKeys(STORE_KEYS, "stores[$storeIndex]")
                WatchAppDataBackupStoreV1(
                    uuid = store.requiredString("uuid"),
                    records = store.requiredArray("records").mapIndexed { recordIndex, recordElement ->
                        val record = recordElement.requiredObject("stores[$storeIndex].records[$recordIndex]")
                        record.requireKeys(RECORD_KEYS, "stores[$storeIndex].records[$recordIndex]")
                        WatchAppDataBackupRecordV1(
                            key = record.requiredLong("key"),
                            valueBase64 = record.requiredString("valueBase64"),
                        )
                    },
                )
            },
        )
    }

    private fun validateAndSort(backup: WatchAppDataBackup): WatchAppDataBackup {
        val seenUuids = mutableSetOf<Uuid>()
        var totalRecords = 0L
        var totalValueBytes = 0L
        val stores = backup.stores.map { store ->
            require(seenUuids.add(store.uuid)) { "Backup contains duplicate store UUIDs." }
            val seenKeys = mutableSetOf<UInt>()
            var storeValueBytes = 0L
            val records = store.records.map { record ->
                require(seenKeys.add(record.key)) { "Store contains duplicate record keys." }
                val valueSize = record.value.size
                require(valueSize in 1..MAX_VALUE_BYTES) { "Record value length is invalid." }
                storeValueBytes += valueSize.toLong()
                record
            }.sortedBy { it.key }
            require(storeValueBytes <= MAX_STORE_VALUE_BYTES) { "Store values exceed the maximum size." }
            totalRecords += records.size
            totalValueBytes += storeValueBytes
            WatchAppDataBackupStore(store.uuid, records)
        }
        require(stores.size.toLong() <= UInt.MAX_VALUE.toLong() &&
            totalRecords <= UInt.MAX_VALUE.toLong() && totalValueBytes <= UInt.MAX_VALUE.toLong()) {
            "Backup totals exceed protocol limits."
        }
        return WatchAppDataBackup(stores.sortedBy { it.uuid.toString() })
    }

    private fun parseCanonicalUuid(value: String): Uuid {
        val uuid = try {
            Uuid.parse(value)
        } catch (_: IllegalArgumentException) {
            invalid("Store UUID is invalid.")
        }
        require(uuid.toString() == value) { "Store UUID must use canonical lowercase form." }
        return uuid
    }

    private fun decodeCanonicalBase64(value: String): UByteArray {
        val decoded = try {
            Base64.decode(value)
        } catch (_: IllegalArgumentException) {
            invalid("Record value is not valid base64.")
        }
        require(Base64.encode(decoded) == value) { "Record value base64 is not canonical." }
        return decoded.toUByteArray()
    }

    private fun JsonElement.requiredObject(description: String): JsonObject =
        this as? JsonObject ?: invalid("$description must be an object.")

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key] as? JsonArray ?: invalid("$key must be an array.")

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: invalid("$key must be a string.")
        require(primitive.isString) { "$key must be a string." }
        return primitive.content
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val primitive = this[key] as? JsonPrimitive ?: invalid("$key must be an integer.")
        require(!primitive.isString) { "$key must be an integer." }
        return primitive.intOrNull ?: invalid("$key must be an integer.")
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: invalid("$key must be an integer.")
        require(!primitive.isString) { "$key must be an integer." }
        return primitive.longOrNull ?: invalid("$key must be an integer.")
    }

    private fun JsonObject.requireKeys(expected: Set<String>, description: String) {
        require(keys == expected) { "$description has missing or unknown fields." }
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)

    internal fun requireDocumentSize(document: String, maxDocumentBytes: Long = MAX_DOCUMENT_BYTES) {
        require(maxDocumentBytes >= 0) { "Maximum document size must not be negative." }
        require(document.encodeToByteArray().size.toLong() <= maxDocumentBytes) {
            "Backup exceeds the maximum document size."
        }
    }

    private val ARCHIVE_KEYS = setOf("format", "schemaVersion", "exportedAtEpochMilliseconds", "stores")
    private val STORE_KEYS = setOf("uuid", "records")
    private val RECORD_KEYS = setOf("key", "valueBase64")

    private class JsonDuplicateKeyValidator(private val document: String) {
        private var index = 0

        fun validate() {
            skipWhitespace()
            readValue()
            skipWhitespace()
            require(index == document.length) { "Backup contains trailing JSON content." }
        }

        private fun readValue() {
            skipWhitespace()
            when (document[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                else -> readPrimitive()
            }
        }

        private fun readObject() {
            index++
            skipWhitespace()
            if (consume('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                val key = readString()
                require(keys.add(key)) { "Backup contains duplicate JSON object fields." }
                skipWhitespace()
                require(consume(':')) { "Backup JSON object is malformed." }
                readValue()
                skipWhitespace()
                if (consume('}')) return
                require(consume(',')) { "Backup JSON object is malformed." }
                skipWhitespace()
            }
        }

        private fun readArray() {
            index++
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                readValue()
                skipWhitespace()
                if (consume(']')) return
                require(consume(',')) { "Backup JSON array is malformed." }
                skipWhitespace()
            }
        }

        private fun readString(): String {
            val start = index
            require(consume('"')) { "Backup JSON object key is malformed." }
            while (index < document.length) {
                when (document[index++]) {
                    '\\' -> index++
                    '"' -> return WatchAppDataBackupCodec.json.decodeFromString(document.substring(start, index))
                }
            }
            WatchAppDataBackupCodec.invalid("Backup JSON string is unterminated.")
        }

        private fun readPrimitive() {
            val start = index
            while (index < document.length && document[index] !in PRIMITIVE_DELIMITERS) index++
            require(index > start) { "Backup JSON value is malformed." }
        }

        private fun skipWhitespace() {
            while (index < document.length && document[index].isWhitespace()) index++
        }

        private fun consume(expected: Char): Boolean {
            if (index >= document.length || document[index] != expected) return false
            index++
            return true
        }

        private companion object {
            val PRIMITIVE_DELIMITERS = setOf(' ', '\t', '\r', '\n', ',', ']', '}')
        }
    }
}

@Serializable
private data class WatchAppDataBackupArchiveV1(
    val format: String,
    val schemaVersion: Int,
    val exportedAtEpochMilliseconds: Long,
    val stores: List<WatchAppDataBackupStoreV1>,
)

@Serializable
private data class WatchAppDataBackupStoreV1(
    val uuid: String,
    val records: List<WatchAppDataBackupRecordV1>,
)

@Serializable
private data class WatchAppDataBackupRecordV1(
    val key: Long,
    val valueBase64: String,
)
