package com.amitjha.kosh.repo

import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.statement.StatementCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class StatementImporterTest {

    @Test
    fun channelComesFromTheNarration() {
        assertEquals(TxnChannel.UPI, StatementImporter.channelOf("UPI/412345678901/SWIGGY/swiggy@icici"))
        assertEquals(TxnChannel.NEFT, StatementImporter.channelOf("NEFT CR-HDFC0000001-ACME PVT LTD"))
        assertEquals(TxnChannel.ATM, StatementImporter.channelOf("ATM WDL 12345 MG ROAD"))
        assertEquals(TxnChannel.AUTO_DEBIT, StatementImporter.channelOf("ACH D- HDFC LIFE INSURANCE"))
        assertEquals(TxnChannel.CARD, StatementImporter.channelOf("POS 4321XXXX AMAZON"))
        assertEquals(TxnChannel.OTHER, StatementImporter.channelOf("INTEREST CREDIT"))
    }

    @Test
    fun fingerprintSeparatesAccountsAndRows() {
        val row = StatementCsv.Row(4, LocalDate.of(2026, 10, 1), "UPI/1/ZOMATO", 45_000, TxnType.EXPENSE, null, null)
        assertEquals(StatementImporter.fingerprint(1, row), StatementImporter.fingerprint(1, row.copy(line = 9)))
        assertNotEquals(StatementImporter.fingerprint(1, row), StatementImporter.fingerprint(2, row))
        assertNotEquals(StatementImporter.fingerprint(1, row), StatementImporter.fingerprint(1, row.copy(amount = 45_100)))
    }
}
