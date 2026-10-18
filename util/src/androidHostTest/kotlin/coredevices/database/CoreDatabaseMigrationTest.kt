package coredevices.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreDatabaseMigrationTest {
    @Test
    fun bothVersion10LayoutsKeepRowsAndConverge() {
        val schemas = listOf(
            File("schema/coredevices.database.CoreDatabase/10.json").readText(),
            javaClass.classLoader!!.getResource("fork-schemas/10.json")!!.readText(),
        )
        val current = schema(File("schema/coredevices.database.CoreDatabase/11.json").readText())
        for (old in schemas) {
            BundledSQLiteDriver().open(":memory:").use { connection ->
                val previous = schema(old)
                previous["entities"]!!.jsonArray.forEach { entry ->
                    val entity = entry.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    connection.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    entity["indices"]?.jsonArray.orEmpty().forEach {
                        connection.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                    }
                }
                val existing = if (connection.hasTable("battery_history")) "battery_history" else "sync_tasks"
                val columns = connection.columns(existing)
                connection.execSQL(
                    "INSERT INTO `$existing` (${columns.keys.joinToString { "`$it`" }}) VALUES (" +
                        columns.values.joinToString { if (it == "TEXT") "'preserved'" else "7" } + ")"
                )

                connection.execSQL("BEGIN TRANSACTION")
                MIGRATION_10_11.migrate(connection)
                connection.execSQL("ROLLBACK")
                assertEquals(false, connection.hasTable(if (existing == "sync_tasks") "battery_history" else "sync_tasks"))
                MIGRATION_10_11.migrate(connection)
                MIGRATION_10_11.migrate(connection)

                connection.prepare("SELECT count(*) FROM `$existing`").use {
                    it.step()
                    assertEquals(1L, it.getLong(0))
                }
                for (entry in current["entities"]!!.jsonArray) {
                    val entity = entry.jsonObject
                    val table = entity["tableName"]!!.jsonPrimitive.content
                    val expected = entity["fields"]!!.jsonArray.associate {
                        it.jsonObject["columnName"]!!.jsonPrimitive.content to
                            it.jsonObject["affinity"]!!.jsonPrimitive.content
                    }
                    assertEquals(expected, connection.columns(table), table)
                    val expectedIndices = entity["indices"]?.jsonArray.orEmpty()
                        .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                    assertEquals(expectedIndices, connection.indices(table), table)
                }
            }
        }
    }

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject["database"]!!.jsonObject

    private fun SQLiteConnection.columns(table: String): Map<String, String> =
        prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildMap { while (statement.step()) put(statement.getText(1), statement.getText(2)) }
        }

    private fun SQLiteConnection.indices(table: String): Set<String> =
        prepare("PRAGMA index_list(`$table`)").use { statement ->
            buildSet {
                while (statement.step()) {
                    statement.getText(1).takeUnless { it.startsWith("sqlite_autoindex_") }?.let(::add)
                }
            }
        }

    private fun SQLiteConnection.hasTable(table: String): Boolean =
        prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").use { it.step() }
}
