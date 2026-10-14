package com.amitjha.kosh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
        fun create(context: Context): KoshDatabase =
            Room.databaseBuilder(context, KoshDatabase::class.java, "kosh.db").build()
    }
}
