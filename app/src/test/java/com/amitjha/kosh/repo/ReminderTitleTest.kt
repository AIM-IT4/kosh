package com.amitjha.kosh.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReminderTitleTest {

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun aReminderMissedWhileThePhoneWasOffIsStillShown() {
        assertEquals("Maid is due today", RecurringRepository.reminderTitle("Maid", today, today))
        assertEquals("Maid was due on 18 Oct", RecurringRepository.reminderTitle("Maid", today.minusDays(2), today))
        assertNull("too old to be useful", RecurringRepository.reminderTitle("Maid", today.minusDays(8), today))
        assertNull(RecurringRepository.reminderTitle("Maid", today.plusDays(1), today))
    }
}
