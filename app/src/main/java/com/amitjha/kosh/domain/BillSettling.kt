package com.amitjha.kosh.domain

import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.model.TxnType

/**
 * Which unpaid card bills the money paid into the card has covered. A bill is paid once the payments made since its
 * statement add up to its total due, however many parts they came in. A card's next statement carries over whatever
 * was left of the one before, so once a newer bill is covered nothing from the older ones is owed either.
 */
object BillSettling {

    /** Days before the due date a statement is taken to have been made when its message didn't say. */
    const val GRACE_DAYS = 20L

    /** Payments into [cardId] among [rows]: card-payment messages on the card and transfers to it. */
    fun payments(rows: List<TransactionEntity>, cardId: Long): List<TransactionEntity> = rows.filter {
        (it.accountId == cardId && it.type == TxnType.INCOME && it.categoryKey == SmsMapping.TRANSFER_CATEGORY) ||
            (it.toAccountId == cardId && it.type == TxnType.TRANSFER)
    }

    /** The first day (epoch day) whose payments count towards [bill]; [createdDay] is the day it was added. */
    fun countsFrom(bill: CardBillEntity, createdDay: Long): Long =
        bill.statementDate ?: bill.dueDate?.minus(GRACE_DAYS) ?: (createdDay - GRACE_DAYS)

    /** Rounding: paying ₹10,234 against ₹10,234.56 settles it. */
    fun tolerance(due: Paise): Paise = maxOf(100L, due / 1000)

    /**
     * The [unpaid] bills of one card that [payments] into it cover. [dayOf] turns a timestamp into its epoch day.
     */
    fun settled(unpaid: List<CardBillEntity>, payments: List<TransactionEntity>, dayOf: (Long) -> Long): List<CardBillEntity> {
        if (unpaid.isEmpty()) return emptyList()
        val paid = payments.map { dayOf(it.timestamp) to it.amount }
        val from = unpaid.associate { it.id to countsFrom(it, dayOf(it.createdAt)) }
        val covered = unpaid.filter { bill ->
            val since = from.getValue(bill.id)
            paid.filter { it.first >= since }.sumOf { it.second } + tolerance(bill.totalDue) >= bill.totalDue
        }
        val newest = covered.maxOfOrNull { from.getValue(it.id) } ?: return emptyList()
        val coveredIds = covered.map { it.id }.toSet()
        return unpaid.filter { it.id in coveredIds || from.getValue(it.id) <= newest }
    }

    /**
     * The bills of one card still owed: only the newest unpaid one, since a card's statement already includes what
     * was left of the one before. Bills past their due date by more than [staleDays] are no longer counted either:
     * they were paid in a way Kosh didn't see, or a newer statement Kosh missed has replaced them.
     */
    fun owed(unpaid: List<CardBillEntity>, today: Long, staleDays: Long = STALE_DAYS): CardBillEntity? =
        unpaid.filter { it.dueDate == null || it.dueDate >= today - staleDays }
            .maxWithOrNull(compareBy<CardBillEntity>({ it.dueDate ?: Long.MIN_VALUE }, { it.statementDate ?: Long.MIN_VALUE }, { it.createdAt }))

    /** [owed] for every card among [unpaid]. */
    fun owedAll(unpaid: List<CardBillEntity>, today: Long): List<CardBillEntity> =
        unpaid.groupBy { it.accountId }.values.mapNotNull { owed(it, today) }

    /** A card bill this long overdue no longer counts as owed (the next statement is due within about a month). */
    const val STALE_DAYS = 35L
}
