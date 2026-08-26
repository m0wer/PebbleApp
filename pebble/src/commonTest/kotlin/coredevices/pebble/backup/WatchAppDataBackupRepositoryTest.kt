package coredevices.pebble.backup

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.services.WatchAppDataBackup
import io.rebble.libpebblecommon.services.WatchAppDataBackupInfo
import io.rebble.libpebblecommon.services.WatchAppDataBackupRecord
import io.rebble.libpebblecommon.services.WatchAppDataBackupStore
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalUnsignedTypes::class)
class WatchAppDataBackupRepositoryTest {
    @Test
    fun exportUsesWatchBackupAndRepositoryClock() = runBlocking {
        val watch = RecordingWatch(validBackup())
        val repository = WatchAppDataBackupRepository(fixedClock(123))

        val document = repository.export(watch)

        assertEquals(1, watch.exportCalls)
        assertTrue(document.contains("\"exportedAtEpochMilliseconds\": 123"))
    }

    @Test
    fun invalidDocumentsNeverRestoreAndValidDocumentRestoresOnce() = runBlocking {
        val watch = RecordingWatch(validBackup())
        val repository = WatchAppDataBackupRepository(fixedClock(1))
        val validDocument = WatchAppDataBackupCodec.encode(validBackup(), 1)

        assertFailsWith<Exception> {
            repository.importBackup(validDocument.replace("\"key\": 1", "\"key\": -1"), watch)
        }
        assertEquals(0, watch.restoreCalls)

        repository.importBackup(validDocument, watch)

        assertEquals(1, watch.restoreCalls)
        assertNotNull(watch.restored)
        assertTrue(watch.restored!!.stores.single().records.single().value.contentEquals(ubyteArrayOf(0u, 255u)))
    }

    private fun validBackup() = WatchAppDataBackup(
        listOf(
            WatchAppDataBackupStore(
                Uuid.parse("00112233-4455-6677-8899-aabbccddeeff"),
                listOf(WatchAppDataBackupRecord(1u, ubyteArrayOf(0u, 255u))),
            ),
        ),
    )

    private fun fixedClock(epochMilliseconds: Long) = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochMilliseconds)
    }

    private class RecordingWatch(
        private val backup: WatchAppDataBackup,
    ) : ConnectedPebble.WatchAppDataBackup {
        var exportCalls = 0
        var restoreCalls = 0
        var restored: WatchAppDataBackup? = null

        override suspend fun getInfo(): WatchAppDataBackupInfo = error("Not used by this test.")

        override suspend fun export(): WatchAppDataBackup {
            exportCalls++
            return backup
        }

        override suspend fun restore(backup: WatchAppDataBackup) {
            restoreCalls++
            restored = backup
        }
    }
}
