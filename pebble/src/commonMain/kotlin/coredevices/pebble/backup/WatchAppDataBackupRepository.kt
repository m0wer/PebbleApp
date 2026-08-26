package coredevices.pebble.backup

import io.rebble.libpebblecommon.connection.ConnectedPebble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

class WatchAppDataBackupRepository(
    private val clock: Clock,
) {
    suspend fun export(watch: ConnectedPebble.WatchAppDataBackup): String {
        val backup = watch.export()
        return withContext(Dispatchers.Default) {
            WatchAppDataBackupCodec.encode(backup, clock.now().toEpochMilliseconds())
        }
    }

    suspend fun importBackup(document: String, watch: ConnectedPebble.WatchAppDataBackup) {
        val backup = withContext(Dispatchers.Default) {
            WatchAppDataBackupCodec.decode(document)
        }
        watch.restore(backup)
    }
}
