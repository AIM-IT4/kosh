package com.amitjha.kosh.finance

import com.amitjha.kosh.finance.Recurring.Cadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurringTest {

    private val today = d(2026, 10, 16) // a Friday

    private fun d(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    private fun pay(date: LocalDate, amount: Long, merchant: String, autoDebit: Boolean = false, category: String = "subscriptions") =
        Recurring.Payment(date, amount, merchant, category, autoDebit = autoDebit)

    @Test
    fun monthlyNetflixSeriesPredictsTheNextDate() {
        val payments = listOf(d(2026, 7, 5), d(2026, 8, 5), d(2026, 9, 5), d(2026, 10, 5)).map { pay(it, 649_00, "Netflix") }
        val series = Recurring.detect(payments, today)
        assertEquals(
            listOf(
                Recurring.Series(
                    key = "netflix:MONTHLY",
                    merchant = "Netflix",
                    category = "subscriptions",
                    cadence = Cadence.MONTHLY,
                    occurrences = 4,
                    lastDate = d(2026, 10, 5),
                    nextDate = d(2026, 11, 5),
                    lastAmount = 649_00,
                    previousAmount = 649_00,
                    typicalAmount = 649_00,
                    monthlyEquivalent = 649_00,
                    priceChangePct = null,
                    autoDebit = false,
                    overdue = false,
                ),
            ),
            series,
        )
    }

    @Test
    fun weeklySeriesAcceptsGapsOfSixToEightDays() {
        // Sat 19 Sep, Sun 27 Sep (8 days), Sat 3 Oct (6), Sat 10 Oct (7)
        val payments = listOf(d(2026, 9, 19), d(2026, 9, 27), d(2026, 10, 3), d(2026, 10, 10)).map { pay(it, 350_00, "Milkbasket") }
        val s = Recurring.detect(payments, today).single()
        assertEquals(Cadence.WEEKLY, s.cadence)
        assertEquals(4, s.occurrences)
        assertEquals(d(2026, 10, 17), s.nextDate)
        assertEquals(151_667L, s.monthlyEquivalent) // ₹350 × 52 / 12 = ₹1,516.67
        assertFalse(s.overdue)
    }

    @Test
    fun quarterlySeries() {
        // 10 Jan → 10 Apr is 90 days, 10 Apr → 10 Jul is 91
        val payments = listOf(d(2026, 1, 10), d(2026, 4, 10), d(2026, 7, 10)).map { pay(it, 2_397_00, "ACT Fibernet") }
        val s = Recurring.detect(payments, today).single()
        assertEquals(Cadence.QUARTERLY, s.cadence)
        assertEquals("act fibernet:QUARTERLY", s.key)
        assertEquals(3, s.occurrences)
        assertEquals(d(2026, 10, 10), s.nextDate)
        assertEquals(799_00L, s.monthlyEquivalent)
        assertFalse(s.overdue) // 10 Oct + 7 days of grace is still ahead of 16 Oct
    }

    @Test
    fun yearlySeriesNeedsTwoPaymentsAndIgnoresOnesOlderThan400Days() {
        val payments = listOf(d(2024, 9, 20), d(2025, 9, 20), d(2026, 9, 20)).map { pay(it, 1_499_00, "Amazon Prime") }
        val s = Recurring.detect(payments, today).single()
        assertEquals(Cadence.YEARLY, s.cadence)
        assertEquals(2, s.occurrences) // 20 Sep 2024 is 756 days back and doesn't count
        assertEquals(d(2025, 9, 20), d(2026, 9, 20).minusDays(365)) // the gap that fits 350–380
        assertEquals(d(2027, 9, 20), s.nextDate)
        assertEquals(12_492L, s.monthlyEquivalent) // ₹1,499 / 12 = ₹124.92
    }

    @Test
    fun priceChangeOnTheLastPaymentIsReported() {
        val old = listOf(d(2026, 6, 12), d(2026, 7, 12), d(2026, 8, 12)).map { pay(it, 499_00, "Netflix") }
        val s = Recurring.detect(old + pay(d(2026, 9, 12), 649_00, "Netflix"), today).single()
        assertEquals(Cadence.MONTHLY, s.cadence)
        assertEquals(4, s.occurrences)
        assertEquals(649_00L, s.lastAmount)
        assertEquals(499_00L, s.previousAmount)
        assertEquals(499_00L, s.typicalAmount) // median of 499, 499, 499, 649
        assertEquals(30.06, s.priceChangePct!!, 0.01)
        assertEquals(d(2026, 10, 12), s.nextDate)

        // A cut is reported too.
        val cut = listOf(d(2026, 7, 15), d(2026, 8, 15), d(2026, 9, 15)).map { pay(it, 299_00, "Hotstar") } +
            pay(d(2026, 10, 15), 149_00, "Hotstar")
        assertEquals(-50.17, Recurring.detect(cut, today).single().priceChangePct!!, 0.01)
    }

    @Test
    fun changesUnderOnePercentAreNotAPriceChange() {
        val payments = listOf(
            pay(d(2026, 7, 20), 119_00, "Spotify"),
            pay(d(2026, 8, 20), 119_00, "Spotify"),
            pay(d(2026, 9, 20), 119_50, "Spotify"), // +0.42%
        )
        val s = Recurring.detect(payments, today).single()
        assertNull(s.priceChangePct)
        assertEquals(119_00L, s.typicalAmount)
    }

    @Test
    fun irregularGapsAreRejected() {
        // Gaps of 3, 16, 11 and 13 days fit no cadence.
        val swiggy = listOf(d(2026, 9, 1), d(2026, 9, 4), d(2026, 9, 20), d(2026, 10, 1), d(2026, 10, 14)).map { pay(it, 300_00, "Swiggy") }
        // 30, 50, 31: two monthly pairs split by a 50-day gap, neither long enough without an auto-debit.
        val gym = listOf(d(2026, 6, 1), d(2026, 7, 1), d(2026, 8, 20), d(2026, 9, 20)).map { pay(it, 1_500_00, "Gym") }
        assertTrue(Recurring.detect(swiggy + gym, today).isEmpty())
    }

    @Test
    fun amountsTooFarApartAreRejected() {
        val dates = listOf(d(2026, 6, 10), d(2026, 7, 10), d(2026, 8, 10), d(2026, 9, 10))
        // ₹1,500 is 50% above the ₹1,000 median.
        val power = dates.zip(listOf(1_000_00L, 1_500_00L, 900_00L, 1_000_00L)).map { (date, amount) -> pay(date, amount, "BESCOM") }
        // Only the last payment may deviate; an earlier outlier still rejects the series.
        val music = dates.zip(listOf(500_00L, 500_00L, 900_00L, 500_00L)).map { (date, amount) -> pay(date, amount, "Gaana") }
        // With two payments the earlier one must agree with the median too: ₹1,499 is 33% under ₹2,249.
        val shop = listOf(pay(d(2025, 10, 1), 1_499_00, "Amazon"), pay(d(2026, 10, 1), 2_999_00, "Amazon"))
        assertTrue(Recurring.detect(power + music + shop, today).isEmpty())
    }

    @Test
    fun twoMonthlyPaymentsNeedAnAutoDebit() {
        val manual = listOf(pay(d(2026, 9, 1), 5_000_00, "Groww SIP"), pay(d(2026, 10, 1), 5_000_00, "Groww SIP"))
        assertTrue(Recurring.detect(manual, today).isEmpty())

        val mandate = listOf(pay(d(2026, 9, 1), 5_000_00, "Groww SIP"), pay(d(2026, 10, 1), 5_000_00, "Groww SIP", autoDebit = true))
        val s = Recurring.detect(mandate, today).single()
        assertEquals(Cadence.MONTHLY, s.cadence)
        assertEquals(2, s.occurrences)
        assertTrue(s.autoDebit)
        assertEquals(d(2026, 11, 1), s.nextDate)

        // The exception is only for monthly: two weekly payments are not enough even with a mandate.
        val weekly = listOf(pay(d(2026, 10, 2), 100_00, "Daily Dairy", autoDebit = true), pay(d(2026, 10, 9), 100_00, "Daily Dairy", autoDebit = true))
        assertTrue(Recurring.detect(weekly, today).isEmpty())
    }

    @Test
    fun monthEndAnchorClampsAndComesBack() {
        val jan31 = d(2026, 1, 31)
        assertEquals(d(2026, 2, 28), Recurring.nextOccurrence(jan31, Cadence.MONTHLY, jan31))
        assertEquals(d(2026, 3, 31), Recurring.nextOccurrence(jan31, Cadence.MONTHLY, d(2026, 2, 28)))
        assertEquals(d(2026, 4, 30), Recurring.nextOccurrence(jan31, Cadence.MONTHLY, d(2026, 3, 31)))
        assertEquals(
            listOf(jan31, d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30), d(2026, 5, 31), d(2026, 6, 30)),
            Recurring.occurrences(jan31, Cadence.MONTHLY, d(2026, 1, 1), d(2026, 6, 30)),
        )
        // Leap years: February gets its 29th.
        assertEquals(d(2028, 2, 29), Recurring.nextOccurrence(d(2028, 1, 31), Cadence.MONTHLY, d(2028, 2, 1)))
        assertEquals(d(2025, 2, 28), Recurring.nextOccurrence(d(2024, 2, 29), Cadence.YEARLY, d(2024, 2, 29)))
        assertEquals(d(2028, 2, 29), Recurring.nextOccurrence(d(2024, 2, 29), Cadence.YEARLY, d(2027, 3, 1)))

        // Detection: rent paid on the 31st, clamped to 28 Feb, is next due on 31 Mar (not 28 Mar).
        val rent = listOf(d(2025, 12, 31), d(2026, 1, 31), d(2026, 2, 28)).map { pay(it, 25_000_00, "Landlord", category = "rent") }
        val s = Recurring.detect(rent, d(2026, 3, 5)).single()
        assertEquals(d(2026, 2, 28), s.lastDate)
        assertEquals(d(2026, 3, 31), s.nextDate)
    }

    @Test
    fun occurrencesIncludeBothEndsOfTheRange() {
        val friday = d(2026, 10, 2)
        assertEquals(
            listOf(d(2026, 10, 9), d(2026, 10, 16), d(2026, 10, 23), d(2026, 10, 30)),
            Recurring.occurrences(friday, Cadence.WEEKLY, d(2026, 10, 9), d(2026, 10, 30)),
        )
        assertEquals(
            listOf(d(2026, 1, 15), d(2026, 4, 15), d(2026, 7, 15), d(2026, 10, 15)),
            Recurring.occurrences(d(2026, 1, 15), Cadence.QUARTERLY, d(2026, 1, 15), d(2026, 10, 15)),
        )
        // The schedule starts on its anchor.
        assertEquals(listOf(friday, d(2026, 10, 9)), Recurring.occurrences(friday, Cadence.WEEKLY, d(2026, 9, 1), d(2026, 10, 10)))
        assertEquals(friday, Recurring.nextOccurrence(friday, Cadence.WEEKLY, d(2026, 9, 1)))
        assertEquals(d(2026, 10, 9), Recurring.nextOccurrence(friday, Cadence.WEEKLY, friday))
        // No scheduled date in range, or an empty range.
        assertTrue(Recurring.occurrences(friday, Cadence.WEEKLY, d(2026, 10, 10), d(2026, 10, 15)).isEmpty())
        assertTrue(Recurring.occurrences(friday, Cadence.WEEKLY, d(2026, 10, 20), d(2026, 10, 10)).isEmpty())
    }

    @Test
    fun overdueAfterTheGracePeriod() {
        // Monthly: next due 5 Aug, 7 days of grace.
        val gym = listOf(d(2026, 5, 5), d(2026, 6, 5), d(2026, 7, 5)).map { pay(it, 1_500_00, "Gym") }
        assertEquals(d(2026, 8, 5), Recurring.detect(gym, today).single().nextDate)
        assertTrue(Recurring.detect(gym, today).single().overdue)
        assertFalse(Recurring.detect(gym, d(2026, 8, 12)).single().overdue)
        assertTrue(Recurring.detect(gym, d(2026, 8, 13)).single().overdue)

        // Weekly: next due Fri 16 Oct, 3 days of grace.
        val milk = listOf(d(2026, 9, 25), d(2026, 10, 2), d(2026, 10, 9)).map { pay(it, 350_00, "Milkbasket") }
        assertFalse(Recurring.detect(milk, today).single().overdue)
        assertFalse(Recurring.detect(milk, d(2026, 10, 19)).single().overdue)
        assertTrue(Recurring.detect(milk, d(2026, 10, 20)).single().overdue)
    }

    @Test
    fun monthlyEquivalentPerCadence() {
        val payments = listOf(d(2026, 9, 25), d(2026, 10, 2), d(2026, 10, 9)).map { pay(it, 350_00, "Milkbasket") } +
            listOf(d(2026, 7, 20), d(2026, 8, 20), d(2026, 9, 20)).map { pay(it, 1_500_00, "Gym") } +
            listOf(d(2026, 4, 10), d(2026, 7, 10)).map { pay(it, 2_397_00, "ACT Fibernet") } +
            listOf(d(2025, 10, 1), d(2026, 10, 1)).map { pay(it, 18_000_00, "Term Insurance") }
        val byCadence = Recurring.detect(payments, today).associateBy { it.cadence }
        assertEquals(4, byCadence.size)
        assertEquals(151_667L, byCadence.getValue(Cadence.WEEKLY).monthlyEquivalent) // ×52/12, rounded
        assertEquals(1_500_00L, byCadence.getValue(Cadence.MONTHLY).monthlyEquivalent)
        assertEquals(799_00L, byCadence.getValue(Cadence.QUARTERLY).monthlyEquivalent) // /3
        assertEquals(1_500_00L, byCadence.getValue(Cadence.YEARLY).monthlyEquivalent) // /12
    }

    @Test
    fun merchantsGroupIgnoringCaseAndSpaces() {
        val payments = listOf(
            pay(d(2026, 7, 5), 649_00, "  NETFLIX"),
            pay(d(2026, 8, 5), 649_00, "netflix"),
            pay(d(2026, 9, 5), 649_00, "Netflix "),
            pay(d(2026, 7, 1), 99_00, "Times  Prime"),
            pay(d(2026, 8, 1), 99_00, "times prime"),
            pay(d(2026, 9, 1), 99_00, " TIMES PRIME "),
        )
        val series = Recurring.detect(payments, today)
        assertEquals(listOf("times prime:MONTHLY", "netflix:MONTHLY"), series.map { it.key })
        assertEquals(listOf("TIMES PRIME", "Netflix"), series.map { it.merchant }) // the latest spelling, trimmed
        assertEquals(listOf(3, 3), series.map { it.occurrences })
    }

    @Test
    fun resultsAreSortedByNextDate() {
        val payments = listOf(d(2025, 10, 1), d(2026, 10, 1)).map { pay(it, 18_000_00, "Term Insurance") } +
            listOf(d(2026, 7, 5), d(2026, 8, 5), d(2026, 9, 5), d(2026, 10, 5)).map { pay(it, 649_00, "Netflix") } +
            listOf(d(2026, 9, 25), d(2026, 10, 2), d(2026, 10, 9)).map { pay(it, 350_00, "Milkbasket") } +
            listOf(d(2026, 4, 10), d(2026, 7, 10)).map { pay(it, 2_397_00, "ACT Fibernet") }
        val series = Recurring.detect(payments.shuffled(java.util.Random(7)), today)
        assertEquals(listOf("ACT Fibernet", "Milkbasket", "Netflix", "Term Insurance"), series.map { it.merchant })
        assertEquals(listOf(d(2026, 10, 10), d(2026, 10, 16), d(2026, 11, 5), d(2027, 10, 1)), series.map { it.nextDate })
    }

    @Test
    fun aMerchantYieldsOneSeriesWithTheMostFittingGaps() {
        // Three weekly classes in January, then a monthly membership from June: 2 weekly gaps vs 3 monthly ones.
        val cult = listOf(d(2026, 1, 2), d(2026, 1, 9), d(2026, 1, 16)).map { pay(it, 500_00, "Cult") } +
            listOf(d(2026, 6, 5), d(2026, 7, 5), d(2026, 8, 5), d(2026, 9, 5)).map { pay(it, 1_500_00, "Cult") }
        val c = Recurring.detect(cult, today).single()
        assertEquals(Cadence.MONTHLY, c.cadence)
        assertEquals(4, c.occurrences)

        // Five weekly classes (4 gaps) beat three monthly payments (2 gaps).
        val yoga = listOf(d(2026, 1, 2), d(2026, 1, 9), d(2026, 1, 16), d(2026, 1, 23), d(2026, 1, 30)).map { pay(it, 500_00, "Yoga") } +
            listOf(d(2026, 6, 19), d(2026, 7, 19), d(2026, 8, 19)).map { pay(it, 1_500_00, "Yoga") }
        val y = Recurring.detect(yoga, today).single()
        assertEquals(Cadence.WEEKLY, y.cadence)
        assertEquals(5, y.occurrences)
        assertTrue(y.overdue)
    }

    @Test
    fun aOneOffPaymentToTheSameMerchantDoesNotHideTheSeries() {
        val payments = listOf(d(2026, 7, 5), d(2026, 8, 5), d(2026, 9, 5), d(2026, 10, 5)).map { pay(it, 649_00, "Netflix") } +
            pay(d(2026, 10, 8), 1_000_00, "Netflix") // a gift card three days later
        val s = Recurring.detect(payments, today).single()
        assertEquals(4, s.occurrences)
        assertEquals(d(2026, 10, 5), s.lastDate)
        assertEquals(649_00L, s.lastAmount)
        assertEquals(d(2026, 11, 5), s.nextDate)
    }
}
