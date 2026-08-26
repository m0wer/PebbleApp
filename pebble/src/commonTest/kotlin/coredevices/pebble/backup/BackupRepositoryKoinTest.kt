package coredevices.pebble.backup

import coredevices.database.BatteryHistoryDao
import coredevices.database.BatteryHistoryEntity
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class BackupRepositoryKoinTest {
    @Test
    fun repositoriesResolveWithoutLibPebbleDaos() {
        val application = koinApplication {
            modules(
                backupModule,
                module {
                    single<LibPebble> { FakeLibPebble() }
                    single<BatteryHistoryDao> { FakeBatteryHistoryDao() }
                    single<Clock> { Clock.System }
                },
            )
        }

        assertNotNull(application.koin.get<HealthBatteryBackupRepository>())
        assertNotNull(application.koin.get<WatchSettingsBackupRepository>())
        application.close()
    }

    private class FakeBatteryHistoryDao : BatteryHistoryDao {
        override suspend fun insert(row: BatteryHistoryEntity): Long = 0

        override suspend fun insertAllIgnoringConflicts(rows: List<BatteryHistoryEntity>): List<Long> = emptyList()

        override fun observeLatest(): Flow<BatteryHistoryEntity?> = emptyFlow()

        override fun observeRecent(limit: Int): Flow<List<BatteryHistoryEntity>> = emptyFlow()

        override suspend fun exportAll(): List<BatteryHistoryEntity> = emptyList()

        override suspend fun retainNewestForWatch(serial: String, limit: Int) {}
    }
}
