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
    ],
    version = 4,
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

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): KoshDatabase =
            Room.databaseBuilder(context, KoshDatabase::class.java, "kosh.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
