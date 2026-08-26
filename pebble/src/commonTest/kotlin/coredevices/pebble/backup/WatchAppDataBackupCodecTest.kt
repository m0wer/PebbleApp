package coredevices.pebble.backup

import io.rebble.libpebblecommon.services.WatchAppDataBackup
import io.rebble.libpebblecommon.services.WatchAppDataBackupRecord
import io.rebble.libpebblecommon.services.WatchAppDataBackupStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalEncodingApi::class, ExperimentalUnsignedTypes::class)
class WatchAppDataBackupCodecTest {
    @Test
    fun codecSortsDeterministicallyAndRoundTripsBinaryValues() {
        val firstUuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        val secondUuid = Uuid.parse("10112233-4455-6677-8899-aabbccddeeff")
        val backup = WatchAppDataBackup(
            listOf(
                WatchAppDataBackupStore(
                    secondUuid,
                    listOf(
                        WatchAppDataBackupRecord(UInt.MAX_VALUE, ubyteArrayOf(0u, 255u)),
                        WatchAppDataBackupRecord(1u, ubyteArrayOf(128u)),
                    ),
                ),
                WatchAppDataBackupStore(firstUuid, listOf(WatchAppDataBackupRecord(2u, ubyteArrayOf(1u))),),
            ),
        )

        val encoded = WatchAppDataBackupCodec.encode(backup, exportedAtEpochMilliseconds = 123)
        val decoded = WatchAppDataBackupCodec.decode(encoded)

        assertEquals(encoded, WatchAppDataBackupCodec.encode(backup, exportedAtEpochMilliseconds = 123))
        assertTrue(encoded.indexOf(firstUuid.toString()) < encoded.indexOf(secondUuid.toString()))
        assertTrue(encoded.indexOf("\"key\": 1") < encoded.indexOf("\"key\": 4294967295"))
        assertTrue(encoded.contains("\"valueBase64\": \"AP8=\""))
        assertBackupEquals(
            WatchAppDataBackup(
                listOf(
                    WatchAppDataBackupStore(firstUuid, listOf(WatchAppDataBackupRecord(2u, ubyteArrayOf(1u))),),
                    WatchAppDataBackupStore(
                        secondUuid,
                        listOf(
                            WatchAppDataBackupRecord(1u, ubyteArrayOf(128u)),
                            WatchAppDataBackupRecord(UInt.MAX_VALUE, ubyteArrayOf(0u, 255u)),
                        ),
                    ),
                ),
            ),
            decoded,
        )
    }

    @Test
    fun codecRejectsWrongFormatVersionUnknownMissingAndWrongTypes() {
        assertInvalid(validDocument().replace(WatchAppDataBackupCodec.FORMAT, "wrong_format"))
        assertInvalid(validDocument().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        assertInvalid(validDocument().replace("\n}", ",\n  \"unexpected\": true\n}"))
        assertInvalid(validDocument().replace("  \"format\": \"${WatchAppDataBackupCodec.FORMAT}\",\n", ""))
        assertInvalid(validDocument().replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""))
        assertInvalid(validDocument().replace("\"key\":1", "\"key\":true"))
    }

    @Test
    fun codecRejectsNoncanonicalUuidAndBase64() {
        assertInvalid(validDocument().replace(UUID, UUID.uppercase()))
        assertInvalid(validDocument().replace("\"AQ==\"", "\"AQ\""))
    }

    @Test
    fun codecRejectsDuplicateStoresRecordsAndOutOfRangeKeys() {
        assertInvalid(
            """{"format":"${WatchAppDataBackupCodec.FORMAT}","schemaVersion":1,"exportedAtEpochMilliseconds":1,"stores":[{"uuid":"$UUID","records":[]},{"uuid":"$UUID","records":[]}]}""",
        )
        assertInvalid(recordsDocument("""[{"key":1,"valueBase64":"AQ=="},{"key":1,"valueBase64":"Ag=="}]"""))
        assertInvalid(recordsDocument("""[{"key":-1,"valueBase64":"AQ=="}]"""))
        assertInvalid(recordsDocument("""[{"key":4294967296,"valueBase64":"AQ=="}]"""))
    }

    @Test
    fun codecRejectsDuplicateJsonObjectFields() {
        assertInvalid(
            validDocument().replace(
                "\"format\": \"${WatchAppDataBackupCodec.FORMAT}\"",
                "\"format\": \"${WatchAppDataBackupCodec.FORMAT}\", \"format\": \"${WatchAppDataBackupCodec.FORMAT}\"",
            ),
        )
        assertInvalid(
            validDocument().replace("\"uuid\":\"$UUID\"", "\"uuid\":\"$UUID\",\"uuid\":\"$UUID\""),
        )
        assertInvalid(recordsDocument("""[{"key":1,"key":1,"valueBase64":"AQ=="}]"""))
        assertInvalid(validDocument().replace("\"format\"", "\"\\u0066ormat\": \"ignored\", \"format\""))
    }

    @Test
    fun codecRejectsInvalidValueAndStoreSizes() {
        assertInvalid(recordsDocument("""[{"key":1,"valueBase64":""}]"""))
        assertInvalid(recordsDocument("""[{"key":1,"valueBase64":"${Base64.encode(ByteArray(257))}"}]"""))
        val records = buildString {
            repeat(4097) { key ->
                if (key > 0) append(',')
                append("{\"key\":$key,\"valueBase64\":\"${Base64.encode(ByteArray(256))}\"}")
            }
        }
        assertInvalid(recordsDocument("[$records]"))
    }

    @Test
    fun codecValidatesSourceModelsBeforeEncoding() {
        val uuid = Uuid.parse(UUID)
        val duplicateKeys = WatchAppDataBackup(
            listOf(
                WatchAppDataBackupStore(
                    uuid,
                    listOf(
                        WatchAppDataBackupRecord(1u, ubyteArrayOf(1u)),
                        WatchAppDataBackupRecord(1u, ubyteArrayOf(2u)),
                    ),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> { WatchAppDataBackupCodec.encode(duplicateKeys, 1) }
        assertFailsWith<IllegalArgumentException> {
            WatchAppDataBackupCodec.encode(
                WatchAppDataBackup(listOf(WatchAppDataBackupStore(uuid, listOf(WatchAppDataBackupRecord(1u, UByteArray(0)))),),),
                1,
            )
        }
    }

    @Test
    fun documentLimitUsesUtf8Bytes() {
        WatchAppDataBackupCodec.requireDocumentSize("\u00e9", maxDocumentBytes = 2)
        assertFailsWith<IllegalArgumentException> {
            WatchAppDataBackupCodec.requireDocumentSize("\u00e9", maxDocumentBytes = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            WatchAppDataBackupCodec.requireDocumentSize("", maxDocumentBytes = -1)
        }
    }

    private fun validDocument() = recordsDocument("""[{"key":1,"valueBase64":"AQ=="}]""")

    private fun recordsDocument(records: String) =
        """{
  "format": "${WatchAppDataBackupCodec.FORMAT}",
  "schemaVersion": 1,
  "exportedAtEpochMilliseconds": 1,
  "stores": [{"uuid":"$UUID","records":$records}]
}"""

    private fun assertInvalid(document: String) {
        assertFailsWith<Exception> { WatchAppDataBackupCodec.decode(document) }
    }

    private fun assertBackupEquals(expected: WatchAppDataBackup, actual: WatchAppDataBackup) {
        assertEquals(expected.stores.size, actual.stores.size)
        expected.stores.zip(actual.stores).forEach { (expectedStore, actualStore) ->
            assertEquals(expectedStore.uuid, actualStore.uuid)
            assertEquals(expectedStore.records.size, actualStore.records.size)
            expectedStore.records.zip(actualStore.records).forEach { (expectedRecord, actualRecord) ->
                assertEquals(expectedRecord.key, actualRecord.key)
                assertTrue(expectedRecord.value.contentEquals(actualRecord.value))
            }
        }
    }

    private companion object {
        const val UUID = "00112233-4455-6677-8899-aabbccddeeff"
    }
}
