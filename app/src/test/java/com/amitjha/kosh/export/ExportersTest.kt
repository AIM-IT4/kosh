package com.amitjha.kosh.export

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ExportersTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val hdfc = AccountEntity(
        id = 1, name = "HDFC Savings", type = AccountType.BANK, bank = "HDFC", last4 = "4821",
        openingBalance = 10_000_00, openingDate = at(2026, 1, 1),
        reportedBalance = 59_550_00, reportedAt = at(2026, 1, 5, 12),
    )
    private val card = AccountEntity(
        id = 2, name = "ICICI Amazon Pay", type = AccountType.CREDIT_CARD, bank = "ICICI", last4 = "1234",
        openingBalance = 0, openingDate = at(2026, 1, 1), creditLimit = 100_000_00,
        reportedBalance = 100_000_00, reportedAt = at(2026, 1, 5, 11),
    )
    private val categories = listOf(
        CategoryEntity(key = "food", name = "Food & Dining", type = TxnType.EXPENSE, icon = "food", colorArgb = 0),
        CategoryEntity(key = "salary", name = "Salary", type = TxnType.INCOME, icon = "salary", colorArgb = 0),
    )
    private val txns = listOf(
        TransactionEntity(
            id = 1, accountId = 1, type = TxnType.INCOME, amount = 50_000_00, categoryKey = "salary",
            merchant = "Acme Corp", timestamp = at(2026, 1, 2, 9), source = TxnSource.SMS, referenceNo = "UTR123",
        ),
        TransactionEntity(
            id = 2, accountId = 2, type = TxnType.EXPENSE, amount = 450_00, categoryKey = "food",
            merchant = "Swiggy, Bangalore", note = "dinner \"party\"", timestamp = at(2026, 1, 3, 20, 30), source = TxnSource.SMS,
        ),
        TransactionEntity(
            id = 3, accountId = 1, toAccountId = 2, type = TxnType.TRANSFER, amount = 450_00, categoryKey = "transfer",
            note = "card bill", timestamp = at(2026, 1, 5, 10),
        ),
        // Outside the range: never exported.
        TransactionEntity(id = 4, accountId = 1, type = TxnType.EXPENSE, amount = 99_00, categoryKey = "food", timestamp = at(2026, 2, 1, 8)),
    )
    private val data = ExportData(listOf(hdfc, card), categories, txns, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), ZoneOffset.UTC)

    @Test fun csvGolden() {
        val expected = Exporters.BOM + listOf(
            "date,account,type,category,merchant,note,amount,source,reference",
            "2026-01-02 09:00,HDFC Savings ••4821,Income,Salary,Acme Corp,,50000.00,SMS,UTR123",
            "2026-01-03 20:30,ICICI Amazon Pay ••1234,Expense,Food & Dining,\"Swiggy, Bangalore\",\"dinner \"\"party\"\"\",-450.00,SMS,",
            "2026-01-05 10:00,HDFC Savings ••4821 → ICICI Amazon Pay ••1234,Transfer,transfer,,card bill,450.00,Manual,",
            "",
        ).joinToString("\n")
        assertEquals(expected, Exporters.export(ExportFormat.CSV, data))
        assertEquals(expected.replace("\n", "\r\n"), Exporters.export(ExportFormat.EXCEL, data))
        assertEquals('﻿', Exporters.export(ExportFormat.CSV, data)[0])
    }

    @Test fun beancountGolden() {
        val expected = listOf(
            "; Exported from Kosh, 2026-01-01 to 2026-01-31",
            "option \"title\" \"Kosh\"",
            "option \"operating_currency\" \"INR\"",
            "",
            "2026-01-01 commodity INR",
            "",
            "2026-01-01 open Assets:Bank:HDFC-Savings-4821 INR",
            "2026-01-01 open Equity:Opening-Balances INR",
            "2026-01-01 open Income:Salary INR",
            "2026-01-01 open Expenses:Food-And-Dining INR",
            "2026-01-01 open Liabilities:CreditCard:ICICI-Amazon-Pay-1234 INR",
            "",
            "2026-01-01 * \"Opening balance\"",
            "  Assets:Bank:HDFC-Savings-4821                    10000.00 INR",
            "  Equity:Opening-Balances                         -10000.00 INR",
            "",
            "2026-01-02 * \"Acme Corp\"",
            "  Assets:Bank:HDFC-Savings-4821                    50000.00 INR",
            "  Income:Salary                                   -50000.00 INR",
            "",
            "2026-01-03 * \"Swiggy, Bangalore\" \"dinner \\\"party\\\"\"",
            "  Expenses:Food-And-Dining                           450.00 INR",
            "  Liabilities:CreditCard:ICICI-Amazon-Pay-1234      -450.00 INR",
            "",
            "2026-01-05 * \"Transfer\" \"card bill\"",
            "  Liabilities:CreditCard:ICICI-Amazon-Pay-1234       450.00 INR",
            "  Assets:Bank:HDFC-Savings-4821                     -450.00 INR",
            "",
            "2026-01-06 balance Assets:Bank:HDFC-Savings-4821  59550.00 INR",
            "2026-01-06 balance Liabilities:CreditCard:ICICI-Amazon-Pay-1234  0.00 INR",
            "",
        ).joinToString("\n")
        assertEquals(expected, Exporters.export(ExportFormat.BEANCOUNT, data))
    }

    @Test fun ledgerGolden() {
        val expected = listOf(
            "; Exported from Kosh, 2026-01-01 to 2026-01-31",
            "",
            "account Assets:Bank:HDFC-Savings-4821",
            "account Equity:Opening-Balances",
            "account Income:Salary",
            "account Expenses:Food-And-Dining",
            "account Liabilities:CreditCard:ICICI-Amazon-Pay-1234",
            "",
            "2026/01/01 * Opening balance",
            "    Assets:Bank:HDFC-Savings-4821                 INR 10000.00",
            "    Equity:Opening-Balances                       INR -10000.00",
            "",
            "2026/01/02 * Acme Corp",
            "    Assets:Bank:HDFC-Savings-4821                 INR 50000.00",
            "    Income:Salary                                 INR -50000.00",
            "",
            "2026/01/03 * Swiggy, Bangalore  ; dinner \"party\"",
            "    Expenses:Food-And-Dining                      INR 450.00",
            "    Liabilities:CreditCard:ICICI-Amazon-Pay-1234  INR -450.00",
            "",
            "2026/01/05 * Transfer  ; card bill",
            "    Liabilities:CreditCard:ICICI-Amazon-Pay-1234  INR 450.00",
            "    Assets:Bank:HDFC-Savings-4821                 INR -450.00",
            "",
            "2026/01/06 * Balance reported by bank SMS",
            "    Assets:Bank:HDFC-Savings-4821                 INR 0.00 = INR 59550.00",
            "",
            "2026/01/06 * Balance reported by bank SMS",
            "    Liabilities:CreditCard:ICICI-Amazon-Pay-1234  INR 0.00 = INR 0.00",
            "",
            "",
        ).joinToString("\n")
        assertEquals(expected, Exporters.export(ExportFormat.LEDGER, data))
    }

    @Test fun unverifiableBalanceBecomesComment() {
        val wrong = data.copy(accounts = listOf(hdfc.copy(reportedBalance = 1_00), card))
        val out = Exporters.beancount(wrong)
        assertTrue(out.contains("; 2026-01-06 reported by bank SMS: Assets:Bank:HDFC-Savings-4821 1.00 INR"))
        assertFalse(out.contains("balance Assets:Bank:HDFC"))
    }

    @Test fun csvFieldNeutralisesFormulas() {
        assertEquals("'=SUM(A1)", Exporters.csvField("=SUM(A1)"))
        assertEquals("\"'+1,2\"", Exporters.csvField("+1,2"))
        assertEquals("-450.00", Exporters.csvField("-450.00"))
    }

    @Test fun accountComponents() {
        assertEquals("Food-And-Dining", Exporters.component("food & dining"))
        assertEquals("Other", Exporters.component("₹ — "))
        assertEquals("Assets:Unknown", Exporters.ledgerAccount(null))
        assertEquals("kosh-2026-01-01-to-2026-01-31.beancount", Exporters.fileName(ExportFormat.BEANCOUNT, data.from, data.to))
    }
}
