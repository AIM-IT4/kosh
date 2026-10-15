package com.amitjha.kosh.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType

/**
 * A bank account, credit card, cash wallet or e-wallet.
 *
 * For bank accounts the balance is `openingBalance` plus every transaction after `openingDate`;
 * `reportedBalance` is the latest balance a bank SMS told us, used to reconcile.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val bank: String,
    val last4: String? = null,
    val openingBalance: Paise = 0,
    val openingDate: Long = System.currentTimeMillis(),
    val reportedBalance: Paise? = null,
    val reportedAt: Long? = null,
    val creditLimit: Paise? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val colorArgb: Long? = null,
    val archived: Boolean = false,
    /** Created automatically from an SMS or e-mail; the user should confirm its name and opening balance. */
    val autoCreated: Boolean = false,
)

@Entity(tableName = "categories", indices = [Index(value = ["key"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val name: String,
    val type: TxnType,
    val icon: String,
    val colorArgb: Long,
    val isDefault: Boolean = true,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index("accountId"), Index("timestamp"), Index("categoryKey"),
        Index(value = ["fingerprint"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** Destination account for transfers (e.g. paying a credit card from a bank account). */
    val toAccountId: Long? = null,
    val type: TxnType,
    val amount: Paise,
    val categoryKey: String,
    val merchant: String? = null,
    val note: String? = null,
    val timestamp: Long,
    val channel: TxnChannel = TxnChannel.OTHER,
    val source: TxnSource = TxnSource.MANUAL,
    /** Dedupe key for SMS/email imports; null for manual entries. */
    val fingerprint: String? = null,
    val referenceNo: String? = null,
    /** Auto-imported transactions start unreviewed so the user can confirm or fix them. */
    val reviewed: Boolean = true,
    val excludeFromStats: Boolean = false,
    /**
     * An imported amount that looks implausible (for example a reference number read as an amount).
     * Suspicious rows don't move balances or count in stats until the user confirms them in Review.
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val suspicious: Boolean = false,
)

@Entity(
    tableName = "card_bills",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("accountId"), Index(value = ["fingerprint"], unique = true)],
)
data class CardBillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val totalDue: Paise,
    val minimumDue: Paise? = null,
    /** Epoch day (LocalDate.toEpochDay). */
    val dueDate: Long?,
    val statementDate: Long? = null,
    val source: TxnSource,
    val fingerprint: String,
    val paid: Boolean = false,
    val paidAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A monthly budget; `categoryKey == null` is the overall budget. */
@Entity(tableName = "budgets", indices = [Index(value = ["categoryKey"], unique = true)])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryKey: String?,
    val monthlyLimit: Paise,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val target: Paise,
    val saved: Paise = 0,
    /** Epoch day. */
    val deadline: Long,
    val icon: String = "flag",
    val expectedReturnPct: Double = 7.0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * An FD, EPF or NPS holding. Type-specific inputs are stored as JSON in `specJson` and
 * interpreted by the finance engine; `currentValue` is refreshed by the daily worker.
 */
@Entity(tableName = "investments")
data class InvestmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: InvestmentType,
    val name: String,
    val institution: String,
    val specJson: String,
    val investedAmount: Paise,
    val currentValue: Paise,
    val lastUpdated: Long,
    /** Epoch day of maturity for FDs. */
    val maturityDate: Long? = null,
    val archived: Boolean = false,
)

@Entity(
    tableName = "investment_history",
    foreignKeys = [
        ForeignKey(entity = InvestmentEntity::class, parentColumns = ["id"], childColumns = ["investmentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["investmentId", "epochDay"], unique = true)],
)
data class InvestmentSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val investmentId: Long,
    val epochDay: Long,
    val value: Paise,
)

/** Learned categorisation: when the user recategorises a merchant, future imports follow. */
@Entity(tableName = "merchant_rules", indices = [Index(value = ["merchant"], unique = true)])
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val categoryKey: String,
)

/** Daily net worth, recorded by the background refresh for the history chart. */
@Entity(tableName = "net_worth_history")
data class NetWorthSnapshotEntity(
    @PrimaryKey val epochDay: Long,
    val liquid: Paise,
    val cardDebt: Paise,
    val investments: Paise,
    val total: Paise,
)
