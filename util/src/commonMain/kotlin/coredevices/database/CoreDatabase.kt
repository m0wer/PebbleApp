package coredevices.database

import PlatformContext
import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.database.RoomTypeConverters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal const val CORE_DATABASE_FILENAME = "coreapp.db"

@Database(
    entities = [
        HeartbeatStateEntity::class,
        AppstoreSource::class,
        AppstoreCollection::class,
        WeatherLocationEntity::class,
        HeartEntity::class,
        MemfaultChunkEntity::class,
        AnalyticsHeartbeatEntity::class,
        SyncTaskEntity::class,
        BatteryHistoryEntity::class,
    ],
    version = 11,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ],
    exportSchema = true,
)
@ConstructedBy(CoreDatabaseConstructor::class)
@TypeConverters(RoomTypeConverters::class)
abstract class CoreDatabase : RoomDatabase() {
    abstract fun analyticsDao(): HeartbeatStateDao
    abstract fun appstoreSourceDao(): AppstoreSourceDao
    abstract fun appstoreCollectionDao(): AppstoreCollectionDao
    abstract fun weatherLocationDao(): WeatherLocationDao
    abstract fun heartsDao(): HeartsDao
    abstract fun memfaultChunkDao(): MemfaultChunkDao
    abstract fun analyticsHeartbeatDao(): AnalyticsHeartbeatDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun batteryHistoryDao(): BatteryHistoryDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object CoreDatabaseConstructor : RoomDatabaseConstructor<CoreDatabase> {
    override fun initialize(): CoreDatabase
}

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `sync_tasks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `type` TEXT NOT NULL, `payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                `attempts` INTEGER NOT NULL, `lastAttemptAt` INTEGER, `status` TEXT NOT NULL,
                `lastError` TEXT, `dedupeKey` TEXT)"""
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tasks_status` ON `sync_tasks` (`status`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tasks_type_dedupeKey` ON `sync_tasks` (`type`, `dedupeKey`)")
        connection.execSQL(
            """CREATE TABLE IF NOT EXISTS `battery_history` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `serial` TEXT NOT NULL, `timestampSeconds` INTEGER NOT NULL,
                `recordVersion` INTEGER NOT NULL, `socCentipercent` INTEGER NOT NULL,
                `socMinCentipercent` INTEGER NOT NULL, `socDropCentipercent` INTEGER NOT NULL,
                `voltageMv` INTEGER NOT NULL, `voltageDeltaMv` INTEGER NOT NULL,
                `temperatureMc` INTEGER NOT NULL, `currentUa` INTEGER NOT NULL,
                `tteSeconds` INTEGER NOT NULL, `chargeTimeMs` INTEGER NOT NULL,
                `dischargeDurationMs` INTEGER NOT NULL, `backlightOnTimeMs` INTEGER NOT NULL,
                `backlightAverageIntensityPercent` INTEGER NOT NULL, `vibratorOnTimeMs` INTEGER NOT NULL,
                `hrmOnTimeMs` INTEGER NOT NULL, `cpuRunningCentipercent` INTEGER NOT NULL,
                `taskCpuKernelMainCentipercent` INTEGER NOT NULL,
                `taskCpuKernelBackgroundCentipercent` INTEGER NOT NULL,
                `taskCpuWorkerCentipercent` INTEGER NOT NULL, `taskCpuAppCentipercent` INTEGER NOT NULL,
                `taskCpuBtHostCentipercent` INTEGER NOT NULL,
                `taskCpuBtControllerCentipercent` INTEGER NOT NULL,
                `taskCpuBtHciCentipercent` INTEGER NOT NULL,
                `taskCpuNewTimersCentipercent` INTEGER NOT NULL,
                `taskCpuPulseCentipercent` INTEGER NOT NULL, `taskCpuIdleCentipercent` INTEGER NOT NULL,
                `bleConnectedTimeMs` INTEGER NOT NULL, `bleExpectedTimeMs` INTEGER NOT NULL,
                `bleLatencyZeroTimeMs` INTEGER NOT NULL,
                `bleConnectionIntervalMinTimeMs` INTEGER NOT NULL,
                `bleConnectionIntervalMidTimeMs` INTEGER NOT NULL,
                `bleConnectionIntervalMaxTimeMs` INTEGER NOT NULL,
                `bleConnectionIntervalOtherTimeMs` INTEGER NOT NULL,
                `bleConnectionParameterUpdateCount` INTEGER NOT NULL,
                `watchfaceName` TEXT NOT NULL, `watchfaceUuid` TEXT NOT NULL,
                `secondTickSubscribed` INTEGER NOT NULL)"""
        )
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_battery_history_serial_timestampSeconds` ON `battery_history` (`serial`, `timestampSeconds`)")
    }
}

fun getCoreRoomDatabase(ctx: PlatformContext): CoreDatabase {
    return getCoreDatabaseBuilder(ctx)
        .addMigrations(MIGRATION_10_11)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        // V7 required a full re-create.
//        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

internal expect fun getCoreDatabaseBuilder(ctx: PlatformContext): RoomDatabase.Builder<CoreDatabase>
