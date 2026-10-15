package com.amitjha.kosh.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the exported Room schemas against [KoshDatabase.MIGRATION_1_2]: v2 must be v1 plus exactly the
 * `transactions.suspicious` column the migration adds, declared NOT NULL DEFAULT 0 so Room's validation
 * accepts migrated databases. (A full MigrationTestHelper run needs a device.)
 */
class MigrationSchemaTest {

    private fun schema(version: Int): JSONObject {
        val dir = listOf("schemas", "app/schemas").map { File(it, "com.amitjha.kosh.data.KoshDatabase") }.first { it.exists() }
        return JSONObject(File(dir, "$version.json").readText()).getJSONObject("database")
    }

    private fun columns(db: JSONObject, table: String): Map<String, JSONObject> {
        val entities = db.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == table) {
                val fields = e.getJSONArray("fields")
                return (0 until fields.length()).associate { fields.getJSONObject(it).let { f -> f.getString("columnName") to f } }
            }
        }
        error("no table $table")
    }

    @Test
    fun version2AddsOnlyTheSuspiciousColumn() {
        val v1 = columns(schema(1), "transactions")
        val v2 = columns(schema(2), "transactions")
        assertEquals(setOf("suspicious"), v2.keys - v1.keys)
        assertEquals(v1.keys, v1.keys intersect v2.keys)
        val col = v2.getValue("suspicious")
        assertEquals("INTEGER", col.getString("affinity"))
        assertTrue(col.getBoolean("notNull"))
        assertEquals("0", col.getString("defaultValue"))
    }

    @Test
    fun otherTablesAreUnchanged() {
        listOf("accounts", "categories", "card_bills", "budgets", "goals", "investments").forEach { table ->
            assertEquals(table, columns(schema(1), table).keys, columns(schema(2), table).keys)
        }
    }

    @Test
    fun version4AddsSavingsInterestColumnsToAccounts() {
        val v3 = columns(schema(3), "accounts")
        val v4 = columns(schema(4), "accounts")
        assertEquals(setOf("interestRatePct", "interestCreditFrequency"), v4.keys - v3.keys)
        val rate = v4.getValue("interestRatePct")
        assertEquals("REAL", rate.getString("affinity"))
        assertTrue(!rate.getBoolean("notNull"))
        val freq = v4.getValue("interestCreditFrequency")
        assertEquals("TEXT", freq.getString("affinity"))
        assertTrue(freq.getBoolean("notNull"))
        assertEquals("'QUARTERLY'", freq.getString("defaultValue"))
    }

    private fun tables(db: JSONObject): Set<String> {
        val entities = db.getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
    }

    @Test
    fun version5AddsOnlyTheTombstoneAndDemoTables() {
        assertEquals(setOf("deleted_fingerprints", "demo_rows"), tables(schema(5)) - tables(schema(4)))
        tables(schema(4)).forEach { table ->
            assertEquals(table, columns(schema(4), table).keys, columns(schema(5), table).keys)
        }
        val tomb = columns(schema(5), "deleted_fingerprints")
        assertEquals(setOf("fingerprint", "deletedAt"), tomb.keys)
        assertTrue(tomb.values.all { it.getBoolean("notNull") })
        assertEquals(setOf("kind", "rowKey"), columns(schema(5), "demo_rows").keys)
    }

    @Test
    fun migration4To5CreatesTheSameTablesRoomExpects() {
        val sql = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, m, args -> if (m.name == "execSQL") sql += args[0] as String; null } as androidx.sqlite.db.SupportSQLiteDatabase
        KoshDatabase.MIGRATION_4_5.migrate(db)
        val entities = schema(5).getJSONArray("entities")
        val expected = (0 until entities.length()).map { entities.getJSONObject(it) }
            .filter { it.getString("tableName") in setOf("deleted_fingerprints", "demo_rows") }
            .map { it.getString("createSql").replace("\${TABLE_NAME}", it.getString("tableName")) }
        fun norm(s: String) = s.replace("IF NOT EXISTS", "").replace(Regex("\\s+"), " ").trim()
        assertEquals(expected.map(::norm).toSet(), sql.map(::norm).toSet())
    }
}
