package com.amitjha.kosh.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** Salary comes on the working day before a weekend or holiday payday. */
class PaydayTest {

    private val oct = YearMonth.of(2026, 10) // 25 Oct 2026 is a Sunday
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)

    @Test
    fun aWeekendPaydayMovesToFriday() {
        val cycle = PayCycle(25)
        assertEquals(d(10, 23), cycle.payday(oct))
        assertEquals("25 Oct is a Sunday, 24 Oct is a Saturday", cycle.reason(oct))
        // A working-day payday stays put.
        assertEquals(d(11, 25), cycle.payday(YearMonth.of(2026, 11)))
        assertNull(cycle.reason(YearMonth.of(2026, 11)))
    }

    @Test
    fun holidaysBeforeTheWeekendMoveItFurther() {
        val cycle = PayCycle(25, holidays = setOf(d(10, 23), d(10, 22)))
        assertEquals(d(10, 21), cycle.payday(oct))
        // Christmas 2026 is a Friday: salary on the 24th.
        val national = PayCycle(25, holidays = IndianHolidays.dates)
        assertEquals(d(12, 24), national.payday(YearMonth.of(2026, 12)))
        assertEquals("25 Dec is Christmas", national.reason(YearMonth.of(2026, 12), IndianHolidays.named))
    }

    @Test
    fun weekendRules() {
        // 17 Oct 2026 is the 3rd Saturday: banks are open, so with the bank rule salary stays on the 17th.
        assertEquals(d(10, 17), PayCycle(17, weekend = PayWeekend.BANK).payday(oct))
        assertEquals(d(10, 16), PayCycle(17).payday(oct))
        assertEquals(d(10, 17), PayCycle(17, weekend = PayWeekend.SUN).payday(oct))
        // 24 Oct is the 4th Saturday, a bank holiday.
        assertEquals(d(10, 23), PayCycle(25, weekend = PayWeekend.BANK).payday(oct))
    }

    @Test
    fun theFirstNeverMovesAndTheRuleCanBeTurnedOff() {
        // 1 Nov 2026 is a Sunday; a cycle on the 1st is a calendar month.
        assertEquals(d(11, 1), PayCycle(1).payday(YearMonth.of(2026, 11)))
        assertEquals(d(10, 25), PayCycle(25, toWorkingDay = false, paid = setOf(d(10, 21))).payday(oct))
    }

    @Test
    fun periodsRunFromPaydayToPayday() {
        val cycle = PayCycle(25)
        val before = cycle.periodContaining(d(10, 22))
        assertEquals(YearMonth.of(2026, 9), before.month)
        assertEquals(d(9, 25), before.start)
        assertEquals(d(10, 23), before.endExclusive)
        val after = cycle.periodContaining(d(10, 23))
        assertEquals(oct, after.month)
        assertEquals(d(11, 25), after.endExclusive)
        // A payday due on the 2nd can move back into the month before.
        val early = PayCycle(2) // 2 May 2026 is a Saturday → Friday 1 May; 1 Nov 2026 Sunday doesn't matter
        assertEquals(d(5, 1), early.payday(YearMonth.of(2026, 5)))
        val aug = PayCycle(2).periodContaining(d(7, 31)) // 2 Aug 2026 is a Sunday → Friday 31 July
        assertEquals(YearMonth.of(2026, 8), aug.month)
        assertEquals(d(7, 31), aug.start)
    }

    @Test
    fun theDaySalaryActuallyCameWins() {
        val credits = listOf(d(10, 21) to 1_00_000_00L, d(10, 28) to 20_000_00L, d(9, 25) to 1_00_000_00L, d(10, 5) to 5_000_00L)
        val learned = PayCycle(25).learnPaydays(credits)
        // The big credit near each due day; arrears on the 28th and a mid-month credit are ignored.
        assertEquals(setOf(d(10, 21), d(9, 25)), learned)
        val cycle = PayCycle(25, paid = learned)
        assertEquals(d(10, 21), cycle.payday(oct))
        assertEquals("salary arrived on 21 Oct", cycle.reason(oct))
        assertEquals(d(10, 21), cycle.periodContaining(d(10, 22)).start)
    }

    @Test
    fun nextPayday() {
        val cycle = PayCycle(25)
        assertEquals(d(10, 23), cycle.nextPayday(d(10, 18)))
        // Due today and not in yet: today. Arrived today: next month's.
        assertEquals(d(10, 23), cycle.nextPayday(d(10, 23)))
        assertEquals(d(11, 25), cycle.copy(paid = setOf(d(10, 23))).nextPayday(d(10, 23)))
        assertEquals(d(11, 25), cycle.nextPayday(d(10, 24)))
    }

    @Test
    fun nationalHolidays() {
        val named = IndianHolidays.named
        assertEquals("Diwali", named[d(11, 8)])
        assertEquals("Diwali", named[d(10, 29, 2027)])
        assertEquals("Republic Day", named[d(1, 26, 2031)])
    }
}
