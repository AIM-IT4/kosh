package com.amitjha.kosh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        CardBillEntity::class,
        BudgetEntity::class,
        GoalEntity::class,
        InvestmentEntity::class,
        InvestmentSnapshotEntity::class,
        MerchantRuleEntity::class,
        NetWorthSnapshotEntity::class,
        DeletedFingerprintEntity::class,
        DemoRowEntity::class,
        HouseholdItemEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class KoshDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun transactions(): TransactionDao
    abstract fun bills(): CardBillDao
    abstract fun budgets(): BudgetDao
    abstract fun goals(): GoalDao
    abstract fun investments(): InvestmentDao
    abstract fun merchantRules(): MerchantRuleDao
    abstract fun netWorth(): NetWorthDao
    abstract fun tombstones(): TombstoneDao
    abstract fun household(): HouseholdDao
    abstract fun restore(): RestoreDao
    abstract fun demoRows(): DemoRowDao

    companion object {
        /** v2 adds `transactions.suspicious`; existing rows keep their data and start unflagged. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN suspicious INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 adds the optional purchase loan to goals; existing goals keep their data with no loan. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE goals ADD COLUMN loanAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE goals ADD COLUMN loanRatePct REAL NOT NULL DEFAULT 9.0")
                db.execSQL("ALTER TABLE goals ADD COLUMN loanTenureMonths INTEGER NOT NULL DEFAULT 36")
            }
        }

        /** v4 adds per-account savings interest settings; existing accounts use the built-in rate, credited quarterly. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN interestRatePct REAL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN interestCreditFrequency TEXT NOT NULL DEFAULT 'QUARTERLY'")
            }
        }

        /**
         * v5 remembers fingerprints of imported transactions the user deleted, so they aren't re-imported, and
         * which rows came from "Load sample data", so they can be removed on their own.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deleted_fingerprints` (`fingerprint` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, PRIMARY KEY(`fingerprint`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `demo_rows` (`kind` TEXT NOT NULL, `rowKey` TEXT NOT NULL, " +
                        "PRIMARY KEY(`kind`, `rowKey`))",
                )
            }
        }

        /** v6 adds the shared household space synced with a paired phone. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `household_items` (`uuid` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`ownerId` TEXT NOT NULL, `ownerName` TEXT NOT NULL, `localId` INTEGER, `payload` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`uuid`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_household_items_kind_localId` ON `household_items` (`kind`, `localId`)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        /**
         * Opens the database encrypted with SQLCipher (key in the Android Keystore). An existing plaintext
         * database is converted once; if that can't be done, the plaintext one keeps working and the
         * conversion is retried on the next launch.
         */
        fun create(context: Context): KoshDatabase {
            val builder = Room.databaseBuilder(context, KoshDatabase::class.java, DatabaseEncryption.NAME)
                .addMigrations(*ALL_MIGRATIONS)
            val passphrase = runCatching { com.amitjha.kosh.security.DatabaseKey.passphrase(context) }.getOrNull()
            if (passphrase == null) DatabaseEncryption.quarantineUnreadable(context)
            if (passphrase != null && runCatching { DatabaseEncryption.prepare(context, passphrase) }.getOrDefault(false)) {
                builder.openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase))
            }
            return builder.build()
        }
    }
}
