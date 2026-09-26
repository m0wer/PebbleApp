package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Recreates every schema an existing install may have and opens it with the real builder, so Room
// runs the migrations and validates every table exactly as it does on a device at startup.
class DatabaseUpgradeTest {
    private val schemaDir = File("schema/io.rebble.libpebblecommon.database.Database")

    // Schemas shipped by fork releases whose version number clashes with a different upstream one.
    private val forkSchemas = listOf("fork-schemas/47.json")

    @Test
    fun everyPreviousSchemaUpgradesToCurrent() {
        val upstream = schemaDir.listFiles { file -> file.extension == "json" }!!
            .map { it.nameWithoutExtension.toInt() to it.readText() }
        val current = upstream.maxOf { it.first }
        // Versions below 10 are recreated from scratch rather than migrated.
        val starts = upstream.filter { it.first in 10 until current }
            .map { "upstream ${it.first}" to it.second } +
            forkSchemas.map { "fork $it" to resource(it) }
        assertTrue(starts.size > 30, "schema exports not found in ${schemaDir.absolutePath}")

        val failures = starts.mapNotNull { (name, json) ->
            runCatching { upgrade(json) }.exceptionOrNull()?.let { "$name: ${it.message?.take(300)}" }
        }
        if (failures.isNotEmpty()) fail("upgrades failed:\n" + failures.joinToString("\n"))
    }

    private fun upgrade(schemaJson: String) {
        val dir = createTempDirectory("db-upgrade").toFile()
        try {
            val file = File(dir, "test.db")
            createFromSchema(file, Json.parseToJsonElement(schemaJson).jsonObject["database"]!!.jsonObject)
            val database = Room.databaseBuilder<Database>(name = file.absolutePath).withMigrations().build()
            try {
                runBlocking { database.useReaderConnection { } }
            } finally {
                database.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createFromSchema(file: File, schema: JsonObject) {
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                connection.execSQL(entity.sql().replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach {
                    connection.execSQL(it.jsonObject.sql().replace("\${TABLE_NAME}", table))
                }
            }
            schema["views"]?.jsonArray?.forEach {
                val view = it.jsonObject
                connection.execSQL(view.sql().replace("\${VIEW_NAME}", view["viewName"]!!.jsonPrimitive.content))
            }
            schema["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = ${schema["version"]!!.jsonPrimitive.int}")
        }
    }

    private fun JsonObject.sql() = this["createSql"]!!.jsonPrimitive.content

    private fun resource(path: String) =
        javaClass.classLoader.getResource(path)!!.readText()
}
