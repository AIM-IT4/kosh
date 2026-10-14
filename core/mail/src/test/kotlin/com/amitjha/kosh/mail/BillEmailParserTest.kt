package com.amitjha.kosh.mail

import com.amitjha.kosh.model.Bank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BillEmailParserTest {
    private fun millis(y: Int, m: Int, d: Int) =
        LocalDate.of(y, m, d).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    private fun email(
        from: String,
        subject: String,
        text: String? = null,
        html: String? = null,
        date: Long = millis(2025, 1, 16),
        attachments: List<String> = emptyList(),
        id: String = "m1",
    ) = EmailMessage(id, from, subject, date, text, html, attachments)

    @Test
    fun iciciPlainTextStatement() {
        val bill = BillEmailParser.parse(
            email(
                from = "ICICI Bank <credit_cards@icicibank.com>",
                subject = "Your ICICI Bank Credit Card XX1005 Statement for December 2024",
                text = """
                    Dear Customer,
                    Your ICICI Bank Credit Card XX1005 statement for the period 13 Nov 2024 to 12 Dec 2024 has been generated.
                    Statement Date: 12/12/2024
                    Total Amount due: Rs. 23,456.78
                    Minimum Amount due: Rs. 1,180.00
                    Due Date: 30/12/2024
                    The statement is attached as a password protected PDF. The password is the first 4 letters of your name followed by DDMM of your date of birth.
                """.trimIndent(),
                date = millis(2024, 12, 13),
            ),
        )
        assertNotNull(bill)
        bill!!
        assertEquals(Bank.ICICI, bill.bank)
        assertEquals("1005", bill.cardLast4)
        assertEquals(2345678L, bill.totalDue)
        assertEquals(118000L, bill.minimumDue)
        assertEquals(LocalDate.of(2024, 12, 30), bill.dueDate)
        assertEquals(LocalDate.of(2024, 12, 12), bill.statementDate)
        assertTrue(bill.hasPdfStatement)
        assertFalse(bill.isCreditBalance)
    }

    @Test
    fun hdfcHtmlHeaderRowTable() {
        val html = """
            <html><head><style>td{color:#333}</style></head><body>
            <p>Dear Cardmember,</p>
            <p>Your HDFC Bank Regalia Credit Card ending 4321 statement is ready.</p>
            <table>
              <tr><th>Statement Date</th><th>Payment Due Date</th><th>Total Dues</th><th>Minimum Amount Due</th></tr>
              <tr><td>15/01/2025</td><td>04/02/2025</td><td>&#8377;45,210.50</td><td>&#8377;2,260.00</td></tr>
            </table>
            <p>Please find attached your e-statement.</p>
            </body></html>
        """.trimIndent()
        val bill = BillEmailParser.parse(
            email("HDFC Bank <Emailstatements.cards@hdfcbank.net>", "HDFC Bank Credit Card Statement", html = html),
        )!!
        assertEquals(Bank.HDFC, bill.bank)
        assertEquals("4321", bill.cardLast4)
        assertEquals(4521050L, bill.totalDue)
        assertEquals(226000L, bill.minimumDue)
        assertEquals(LocalDate.of(2025, 2, 4), bill.dueDate)
        assertEquals(LocalDate.of(2025, 1, 15), bill.statementDate)
        assertTrue(bill.hasPdfStatement)
    }

    @Test
    fun kotakTadMad() {
        val bill = BillEmailParser.parse(
            email(
                from = "Kotak Mahindra Bank <cardstatement@kotak.com>",
                subject = "Kotak Credit Card Statement - January 2025",
                text = """
                    Dear Customer, your Kotak Credit Card statement has been generated.
                    Card No: 4166XXXXXXXX7788
                    Statement Date: 16-Jan-2025
                    Total Amount Due (TAD): INR 8,999.00
                    Minimum Amount Due (MAD): INR 450.00
                    Payment Due Date: 05-Feb-2025
                """.trimIndent(),
            ),
        )!!
        assertEquals(Bank.KOTAK, bill.bank)
        assertEquals("7788", bill.cardLast4)
        assertEquals(899900L, bill.totalDue)
        assertEquals(45000L, bill.minimumDue)
        assertEquals(LocalDate.of(2025, 2, 5), bill.dueDate)
        assertEquals(LocalDate.of(2025, 1, 16), bill.statementDate)
    }

    @Test
    fun dbsHtmlLabelValueRows() {
        val html = """
            <div>Dear Customer, here is your DBS Bank Credit Card ending 9012 statement summary.</div>
            <table>
              <tr><td>Total Outstanding</td><td>Rs 12,000.00</td></tr>
              <tr><td>Minimum Amount Due</td><td>Rs 600.00</td></tr>
              <tr><td>Payment Due Date</td><td>February 10, 2025</td></tr>
              <tr><td>Credit Limit</td><td>Rs 2,00,000.00</td></tr>
            </table>
        """.trimIndent()
        val bill = BillEmailParser.parse(email("DBS Bank <cardstatements@dbs.com>", "Your card statement", html = html))!!
        assertEquals(Bank.DBS, bill.bank)
        assertEquals("9012", bill.cardLast4)
        assertEquals(1200000L, bill.totalDue)
        assertEquals(60000L, bill.minimumDue)
        assertEquals(LocalDate.of(2025, 2, 10), bill.dueDate)
    }

    @Test
    fun genericIssuerFallback() {
        val bill = BillEmailParser.parse(
            email(
                from = "Statements <statements@unknownbank.com>",
                subject = "Your credit card statement",
                text = "Your credit card ending 5555 statement: Total Amount Due ₹3,400.00. Payment due by 7 Mar 2025.",
            ),
        )!!
        assertEquals(Bank.OTHER, bill.bank)
        assertEquals("5555", bill.cardLast4)
        assertEquals(340000L, bill.totalDue)
        assertNull(bill.minimumDue)
        assertEquals(LocalDate.of(2025, 3, 7), bill.dueDate)
    }

    @Test
    fun skipsDateBeforeAmountAndCardDigits() {
        val bill = BillEmailParser.parse(
            email(
                from = "credit_cards@icicibank.com",
                subject = "Statement",
                text = "Total Amount Due on 05/02/2025 for card ending 1005 is Rs 12,345.00. Minimum Amount Due Rs 620",
            ),
        )!!
        assertEquals(1234500L, bill.totalDue)
        assertEquals(62000L, bill.minimumDue)
        assertEquals("1005", bill.cardLast4)
    }

    @Test
    fun creditBalanceMeansNothingPayable() {
        val bill = BillEmailParser.parse(
            email(
                from = "alerts@axisbank.com",
                subject = "Axis Bank Credit Card Statement",
                text = "Total Payment Due: Rs 1,234.00 Cr  Minimum Payment Due: Rs 0.00  Payment Due Date: 18/02/2025",
            ),
        )!!
        assertEquals(Bank.AXIS, bill.bank)
        assertEquals(0L, bill.totalDue)
        assertTrue(bill.isCreditBalance)
    }

    @Test
    fun yearInferredFromEmailDate() {
        val bill = BillEmailParser.parse(
            email(
                from = "statements@sbicard.com",
                subject = "SBI Card Monthly Statement",
                text = "Total Amount Due: Rs. 5,000.00  Minimum Amount Due: Rs. 250.00  Payment Due Date: 3 Jan",
                date = millis(2024, 12, 15),
            ),
        )!!
        assertEquals(Bank.SBI, bill.bank)
        assertEquals(LocalDate.of(2025, 1, 3), bill.dueDate)
    }

    @Test
    fun fingerprintIsStableAcrossMessages() {
        val text = "Total Amount Due: Rs 2,000.00 Due Date: 10/02/2025 card ending 1111 statement"
        val a = BillEmailParser.parse(email("cards@icicibank.com", "Statement", text = text, id = "a"))!!
        val b = BillEmailParser.parse(email("cards@icicibank.com", "Statement", text = text, id = "b"))!!
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(16, a.fingerprint.length)
    }

    @Test
    fun paymentAcknowledgementIsNotABill() {
        val ack = email(
            from = "HDFC Bank InstaAlerts <alerts@hdfcbank.net>",
            subject = "Payment received on your HDFC Bank Credit Card",
            text = "Dear Cardmember, Payment of Rs. 15,000.00 has been received towards your HDFC Bank Credit Card ending 4321 on 20-01-2025. Thank you.",
        )
        assertNull(BillEmailParser.parse(ack))
        val payment = BillEmailParser.parsePaymentReceived(ack)!!
        assertEquals(Bank.HDFC, payment.bank)
        assertEquals("4321", payment.cardLast4)
        assertEquals(1500000L, payment.amount)
        assertEquals(LocalDate.of(2025, 1, 20), payment.date)
    }

    @Test
    fun paymentAcknowledgementFallsBackToEmailDate() {
        val ack = email(
            from = "ICICI Bank <credit_cards@icicibank.com>",
            subject = "Thank you for your payment",
            text = "Thank you for your payment of INR 5,000 towards ICICI Bank Credit Card XX1005.",
            date = millis(2025, 1, 22),
        )
        val payment = BillEmailParser.parsePaymentReceived(ack)!!
        assertEquals(Bank.ICICI, payment.bank)
        assertEquals("1005", payment.cardLast4)
        assertEquals(500000L, payment.amount)
        assertEquals(LocalDate.of(2025, 1, 22), payment.date)
    }

    @Test
    fun promotionsAndAlertsAreRejected() {
        val promo = email(
            from = "HDFC Bank <offers@hdfcbank.net>",
            subject = "Get 10% cashback this weekend!",
            text = "Pay with your HDFC Bank Credit Card and get 10% cashback up to Rs 1,500. Offer valid till 31 Jan 2025.",
        )
        val emiPromo = email(
            from = "Kotak <offers@kotak.com>",
            subject = "Convert to EMI",
            text = "Convert your total amount due into easy EMIs. Click here to know more.",
        )
        val alert = email(
            from = "ICICI Bank <credit_cards@icicibank.com>",
            subject = "Transaction alert",
            text = "Rs 1,250.00 spent on your ICICI Bank Credit Card XX1005 at AMAZON on 12-Jan-25. Avl Limit: Rs 50,000.00",
        )
        assertNull(BillEmailParser.parse(promo))
        assertNull(BillEmailParser.parse(emiPromo))
        assertNull(BillEmailParser.parse(alert))
        assertNull(BillEmailParser.parsePaymentReceived(promo))
        assertNull(BillEmailParser.parsePaymentReceived(alert))
    }

    @Test
    fun pdfHint() {
        val hint = PdfHint.detect(
            email(
                from = "x@kotak.com",
                subject = "Statement",
                text = "Your statement is attached. To open the PDF, use the password: first four letters of your name in capitals and your date of birth (DDMM).",
                attachments = listOf("Statement_Jan2025.pdf"),
            ),
        )
        assertTrue(hint.hasPdfStatement)
        assertTrue(hint.passwordProtected)
        assertTrue(hint.passwordHint!!.contains("password", ignoreCase = true))
        val none = PdfHint.detect(email("x@kotak.com", "Hi", text = "Thanks for banking with us."))
        assertFalse(none.hasPdfStatement)
        assertFalse(none.passwordProtected)
    }
}
