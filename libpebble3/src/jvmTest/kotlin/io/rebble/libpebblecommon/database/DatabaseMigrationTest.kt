package io.rebble.libpebblecommon.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseMigrationTest {
    @Test
    fun oldForkVersion46KeepsHealthContextAndGainsUpstreamColumns() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            createPreviousTables(connection, forkSchema = true, upstreamVersion = 46)
            MIGRATION_46_47.migrate(connection)
            MIGRATION_47_48.migrate(connection)

            assertEquals(7, connection.healthContext())
            assertEquals(1, connection.columnValue("CalendarEntity", "writable"))
            assertEquals(0, connection.columnValue("health_data", "sleepScore"))
            assertTrue("pluginManifest" in connection.columns("LockerEntryEntity"))
            assertTrue("configPage" in connection.columns("LockerEntryEntity"))
            assertEquals(1, connection.rowCount("health_data"))
        }
    }

    @Test
    fun upstreamVersion46GainsHealthFields() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            createPreviousTables(connection, forkSchema = false, upstreamVersion = 46)
            MIGRATION_46_47.migrate(connection)
            MIGRATION_47_48.migrate(connection)

            assertEquals(0, connection.healthContext())
            assertEquals(1, connection.columnValue("CalendarEntity", "writable"))
            assertEquals(1, connection.rowCount("health_data"))
            assertTrue("pluginManifest" in connection.columns("LockerEntryEntity"))
        }
    }

    @Test
    fun upstreamVersion47KeepsPluginData() {
        BundledSQLiteDriver().open(":memory:").use { connection ->
            createPreviousTables(connection, forkSchema = false, upstreamVersion = 47)
            MIGRATION_47_48.migrate(connection)

            assertEquals(0, connection.healthContext())
            assertEquals(1, connection.rowCount("health_data"))
            assertEquals(1, connection.rowCount("LockerEntryEntity"))
            assertEquals("existing-plugin", connection.textValue("LockerEntryEntity", "pluginManifest"))
            assertTrue("pluginManifest" in connection.columns("LockerEntryEntity"))
        }
    }

    private fun createPreviousTables(connection: SQLiteConnection, forkSchema: Boolean, upstreamVersion: Int) {
        connection.execSQL("CREATE TABLE CalendarEntity (id INTEGER PRIMARY KEY)")
        if (!forkSchema) connection.execSQL("ALTER TABLE CalendarEntity ADD COLUMN writable INTEGER NOT NULL DEFAULT 1")
        connection.execSQL("INSERT INTO CalendarEntity (id) VALUES (1)")
        connection.execSQL("CREATE TABLE LockerEntryEntity (id TEXT PRIMARY KEY)")
        if (upstreamVersion >= 47) {
            connection.execSQL("ALTER TABLE LockerEntryEntity ADD COLUMN pluginManifest TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE LockerEntryEntity ADD COLUMN configPage TEXT DEFAULT NULL")
        }
        if (upstreamVersion >= 47) {
            connection.execSQL("INSERT INTO LockerEntryEntity (id, pluginManifest) VALUES ('watchface', 'existing-plugin')")
        } else {
            connection.execSQL("INSERT INTO LockerEntryEntity (id) VALUES ('watchface')")
        }
        connection.execSQL("CREATE TABLE health_data (timestamp INTEGER PRIMARY KEY)")
        if (forkSchema) {
            for (column in listOf("pluggedIn", "sleepIntentHint", "timezoneOffset15Minutes")) {
                connection.execSQL("ALTER TABLE health_data ADD COLUMN $column INTEGER NOT NULL DEFAULT 0")
            }
            connection.execSQL("INSERT INTO health_data (timestamp, sleepIntentHint) VALUES (1, 7)")
        } else {
            connection.execSQL("INSERT INTO health_data (timestamp) VALUES (1)")
        }
    }

    private fun SQLiteConnection.columns(table: String): Set<String> =
        prepare("PRAGMA table_info($table)").use { statement ->
            buildSet { while (statement.step()) add(statement.getText(1)) }
        }

    private fun SQLiteConnection.rowCount(table: String): Int =
        prepare("SELECT count(*) FROM $table").use { statement ->
            statement.step()
            statement.getLong(0).toInt()
        }

    private fun SQLiteConnection.columnValue(table: String, column: String): Int =
        prepare("SELECT $column FROM $table").use { statement ->
            statement.step()
            statement.getLong(0).toInt()
        }

    private fun SQLiteConnection.textValue(table: String, column: String): String =
        prepare("SELECT $column FROM $table").use { statement ->
            statement.step()
            statement.getText(0)
        }

    private fun SQLiteConnection.healthContext(): Int = columnValue("health_data", "sleepIntentHint")
}
