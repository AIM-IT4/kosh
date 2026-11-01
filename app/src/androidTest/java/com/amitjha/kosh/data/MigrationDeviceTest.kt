package com.amitjha.kosh.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule val helper = MigrationTestHelper(instrumentation, KoshDatabase::class.java)

    @Test fun everyHistoricalSchemaMigratesAndPreservesRows() {
        for (version in 1..9) {
            val name = "migration-$version.db"
            helper.createDatabase(name, version).use { seed(it) }
            helper.runMigrationsAndValidate(name, 10, true, *KoshDatabase.ALL_MIGRATIONS).use { assertRows(it) }
            context.deleteDatabase(name)
        }
    }

    @Test fun plaintextUpgradeEncryptsPreservesRowsAndReopens() {
        // Isolate the production encryption path from any installed/user database.
        val name = "encrypted-migration.db"
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File =
                context.getDatabasePath(if (name == DatabaseEncryption.NAME) "encrypted-migration.db" else "test-$name")
        }
        val key = "0123456789abcdef0123456789abcdef".toByteArray()
        helper.createDatabase(name, 1).use { seed(it) }
        assertTrue(DatabaseEncryption.prepare(isolated, key))
        assertEquals(FileHeader.Kind.ENCRYPTED, FileHeader.kindOf(context.getDatabasePath(name)))
        repeat(2) {
            val db = Room.databaseBuilder(context, KoshDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
                .addMigrations(*KoshDatabase.ALL_MIGRATIONS)
                .build()
            try {
                assertRows(db.openHelper.writableDatabase)
            } finally {
                db.close()
            }
        }
        context.deleteDatabase(name)
    }

    private fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO accounts (id,name,type,bank,openingBalance,openingDate,archived,autoCreated) " +
            "VALUES (1,'Upgrade account','BANK','HDFC',100000,1,0,0)")
        db.execSQL("INSERT INTO transactions (id,accountId,type,amount,categoryKey,timestamp,channel,source," +
            "fingerprint,reviewed,excludeFromStats) VALUES (1,1,'EXPENSE',12345,'other',2,'UPI','SMS','upgrade-sms',1,0)")
    }

    private fun assertRows(db: SupportSQLiteDatabase) {
        db.query("SELECT name,openingBalance,memberId FROM accounts WHERE id=1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Upgrade account", c.getString(0))
            assertEquals(100000L, c.getLong(1))
            assertTrue(c.isNull(2))
        }
        db.query("SELECT amount,fingerprint,reviewed,suspicious,tags FROM transactions WHERE id=1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(12345L, c.getLong(0))
            assertEquals("upgrade-sms", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(0, c.getInt(3))
            assertEquals("", c.getString(4))
            assertFalse(c.moveToNext())
        }
    }
}
