package com.amitjha.kosh.domain

import com.amitjha.kosh.data.AccountEntity
import com.amitjha.kosh.data.BudgetEntity
import com.amitjha.kosh.data.CardBillEntity
import com.amitjha.kosh.data.CategoryEntity
import com.amitjha.kosh.data.GoalEntity
import com.amitjha.kosh.data.InvestmentEntity
import com.amitjha.kosh.data.InvestmentSnapshotEntity
import com.amitjha.kosh.data.MerchantRuleEntity
import com.amitjha.kosh.data.NetWorthSnapshotEntity
import com.amitjha.kosh.data.TransactionEntity
import com.amitjha.kosh.finance.Compounding
import com.amitjha.kosh.finance.NpsHolding
import com.amitjha.kosh.model.AccountType
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnSource
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.repo.InvestmentRepository
import com.amitjha.kosh.security.BackupCrypto
import com.amitjha.kosh.security.BackupData
import com.amitjha.kosh.security.BackupJson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class CodecAndCryptoTest {

    @Test
    fun specCodecRoundTrips() {
        val specs = listOf(
            InvestmentSpec.Fd(1_00_000_00, 7.1, LocalDate.of(2025, 1, 15), LocalDate.of(2026, 1, 15), Compounding.MONTHLY),
            InvestmentSpec.Epf(6_42_000_00, LocalDate.of(2025, 4, 1), 7_800_00, 2_386_00, 8.25, 10),
            InvestmentSpec.Nps(
                holdings = listOf(NpsHolding("SM008001", "Scheme E", 3850.5, 51.1015, LocalDate.of(2025, 10, 1)), NpsHolding("SM008002", "Scheme C", 10.0, 27.4)),
                monthlyContribution = 5_000_00,
                allocation = mapOf("SM008001" to 0.75, "SM008002" to 0.25),
                contributionDay = 7,
                investedAmount = 2_95_000_00,
                contributionsAppliedTill = LocalDate.of(2025, 10, 7),
            ),
        )
        for (spec in specs) assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
    }

    @Test
    fun fdValuationUsesFinanceEngine() {
        val spec = InvestmentSpec.Fd(1_00_000_00, 7.0, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))
        val (value, invested) = InvestmentRepository.valuation(spec, "FD", "HDFC", LocalDate.of(2025, 1, 1))
        assertEquals(1_00_000_00L, invested)
        assertEquals(10_718_590L, value)
    }

    @Test
    fun backupCryptoRoundTripsAndRejectsWrongPassword() {
        val plain = "{\"hello\":\"₹1,23,456\"}".toByteArray()
        val enc = BackupCrypto.encrypt(plain, "correct horse".toCharArray(), iterations = 1_000)
        assertNotEquals(plain.toList(), enc.toList())
        assertArrayEquals(plain, BackupCrypto.decrypt(enc, "correct horse".toCharArray()))
        try {
            BackupCrypto.decrypt(enc, "wrong".toCharArray())
            throw AssertionError("expected WrongPasswordException")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
        val tampered = enc.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            BackupCrypto.decrypt(tampered, "correct horse".toCharArray())
            throw AssertionError("expected WrongPasswordException")
        } catch (_: BackupCrypto.WrongPasswordException) {
        }
    }

    @Test
    fun backupJsonRoundTrips() {
        val data = BackupData(
            accounts = listOf(AccountEntity(1, "HDFC", AccountType.BANK, "HDFC", "4521", 100, 5, 200, 6, null, null, null, 0xFF004C8F, false, true)),
            categories = listOf(CategoryEntity(1, "food", "Food", TxnType.EXPENSE, "restaurant", 0xFFFF7A59, true)),
            transactions = listOf(
                TransactionEntity(1, 1, null, TxnType.EXPENSE, 49_900, "food", "Swiggy", null, 1_000, TxnChannel.UPI, TxnSource.SMS, "fp1", "ref", false, false),
                TransactionEntity(2, 1, 3, TxnType.TRANSFER, 10_000_00, "transfer", null, "Card bill", 2_000, TxnChannel.OTHER, TxnSource.MANUAL, null, null, true, true),
            ),
            bills = listOf(CardBillEntity(1, 3, 28_430_00, 1_430_00, 20_100, 20_080, TxnSource.EMAIL, "bill1", false, null, 7)),
            budgets = listOf(BudgetEntity(1, null, 60_000_00), BudgetEntity(2, "food", 6_000_00)),
            goals = listOf(GoalEntity(1, "Trip", 3_50_000_00, 90_000_00, 20_500, "flight", 7.0, 9)),
            investments = listOf(InvestmentEntity(1, InvestmentType.FD, "FD", "HDFC", "{\"type\":\"FD\"}", 100, 110, 11, 20_600, false)),
            snapshots = listOf(InvestmentSnapshotEntity(1, 1, 20_000, 110)),
            rules = listOf(MerchantRuleEntity(1, "Swiggy", "food")),
            netWorth = listOf(NetWorthSnapshotEntity(20_000, 1, 2, 3, 2)),
        )
        assertEquals(data, BackupJson.decode(BackupJson.encode(data)))
    }
}
