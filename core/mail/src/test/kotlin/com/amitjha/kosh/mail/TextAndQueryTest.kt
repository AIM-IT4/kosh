package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TextAndQueryTest {
    @Test
    fun htmlToText() {
        val html = """
            <html><head><title>T</title><style>.a{x:1}</style><script>var a=1;</script></head>
            <body><!-- hidden --><div>Total&nbsp;Amount&nbsp;Due</div>
            <table><tr><td>Amount</td><td>&#8377;1,234.50</td><td>&#x20B9;10</td></tr></table>
            <p>Tom &amp; Jerry<br/>Second line &rupee; 5</p></body></html>
        """.trimIndent()
        val text = HtmlText.toText(html)
        assertEquals("Total Amount Due\nAmount | ₹1,234.50 | ₹10\nTom & Jerry\nSecond line ₹ 5", text)
        assertFalse(text.contains("var a"))
        assertFalse(text.contains("hidden"))
    }

    @Test
    fun tablePairsFromHeaderRow() {
        val html = "<table><tr><th>Statement Date</th><th>Total Dues</th></tr>" +
            "<tr><td>15/01/2025</td><td>Rs. 100.00</td></tr></table>"
        assertEquals(
            listOf("Statement Date" to "15/01/2025", "Total Dues" to "Rs. 100.00"),
            HtmlText.tablePairs(html),
        )
        assertTrue(HtmlText.tablePairs("<table><tr><td>A</td><td>1</td></tr></table>").isEmpty())
    }

    @Test
    fun dateFormats() {
        val ref = LocalDate.of(2025, 1, 10)
        fun first(s: String) = TextDates.findAll(s, ref).first().date
        assertEquals(LocalDate.of(2025, 2, 5), first("05/02/2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("05-02-2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("05.02.2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("05/02/25"))
        assertEquals(LocalDate.of(2025, 2, 5), first("05-Feb-2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("05 Feb 2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("5th February, 2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("February 5, 2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("Feb 05 2025"))
        assertEquals(LocalDate.of(2025, 2, 5), first("2025-02-05"))
        assertEquals(LocalDate.of(2025, 9, 15), first("15-Sept-25"))
        assertEquals(LocalDate.of(2025, 2, 5), first("5 Feb"))
        assertEquals(LocalDate.of(2025, 12, 31), first("12/31/2025"))
        val two = TextDates.findAll("from 13 Nov 2024 to 12 Dec 2024", ref)
        assertEquals(2, two.size)
        assertTrue(TextDates.findAll("call 1800 266 2666 now", ref).isEmpty())
    }

    @Test
    fun billQueryForSelectedBanks() {
        val q = BillQueries.forBanks(setOf(Bank.ICICI, Bank.HDFC, Bank.KOTAK, Bank.DBS), 30)
        assertTrue(q.startsWith("newer_than:30d "))
        assertTrue(q.contains("credit_cards@icicibank.com"))
        assertTrue(q.contains("hdfcbank.net"))
        assertTrue(q.contains("kotak.com"))
        assertTrue(q.contains("dbs.com"))
        assertFalse(q.contains("sbicard.com"))
        assertTrue(q.contains("\"total amount due\""))
        assertTrue(q.contains("-in:spam"))
        assertEquals(q.count { it == '(' }, q.count { it == ')' })
    }

    @Test
    fun billQueryWithNoBanksCoversAllIssuers() {
        val q = BillQueries.forBanks(emptySet(), 7)
        assertTrue(q.contains("sbicard.com") && q.contains("axisbank.com") && q.contains("icicibank.com"))
        val p = BillQueries.paymentsForBanks(setOf(Bank.HDFC), 7)
        assertTrue(p.contains("hdfcbank.net") && p.contains("\"payment received\""))
    }

    @Test
    fun issuerDirectory() {
        assertEquals(Bank.HDFC, IssuerDirectory.bankForSender("HDFC <emailstatements.cards@hdfcbank.net>"))
        assertEquals(Bank.KOTAK, IssuerDirectory.bankForSender("no-reply@mail.kotak.com"))
        assertEquals(null, IssuerDirectory.bankForSender("someone@gmail.com"))
        assertEquals(Bank.DBS, IssuerDirectory.bankInText("Your DBS Bank card"))
        assertEquals(null, IssuerDirectory.bankInText("nothing here"))
    }

    @Test
    fun miniJson() {
        val v = MiniJson.parse("""{"a":[1,2.5,true,null,"x\"₹\n"],"b":{}}""").obj()
        assertEquals(listOf(1L, 2.5, true, null, "x\"₹\n"), v["a"].arr())
        assertTrue(v["b"].obj().isEmpty())
    }
}
