package com.amitjha.kosh.sms.statement

import com.amitjha.kosh.model.TxnType
import com.amitjha.kosh.sms.statement.StatementCsv.Columns
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bank statement CSV import: layouts of the major Indian banks, delimiters, quoting, footers and the helpers. */
class StatementCsvTest {

    @Test
    fun hdfcStatementWithPreambleAndSummary() {
        val text = """
            HDFC BANK Ltd.,,,,,,
            Page No .: 1,,,,,,Statement of accounts
            MR FAKE KUMAR,,,,,,Account Branch : SAMPLE NAGAR
            12 SAMPLE ROAD,,,,,,Account No : XXXXXXXXXX1234
            BENGALURU 560001,,,,,,Currency : INR
            "Statement From : 01/10/2026  To : 15/10/2026",,,,,,
            ,,,,,,
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            ********,********,********,********,********,********,********
            01/10/26,UPI-SWIGGY-SWIGGY@ICICI-ICIC0000001-427412345678-PAYMENT,0000427412345678,01/10/26,349.00,,"45,231.10"
            03/10/26,NEFT CR-ACME0000001-ACME SOFTWARE PVT LTD-SALARY OCT,ACMEN26276000001,03/10/26,,"1,25,000.00","1,70,231.10"
            05/10/26,ATW-412345XXXXXX1234-S1ANBG01-BENGALURU,0000000000001234,05/10/26,"5,000.00",,"1,65,231.10"
            ********,********,********,********,********,********,********
            STATEMENT SUMMARY :-,,,,,,
            Opening Balance,Dr Count,Cr Count,Debits,Credits,Closing Bal,
            "45,580.10",2,1,"5,349.00","1,25,000.00","1,65,231.10",
            Generated On: 16/10/2026 09:15,,,,,,
            This is a computer generated statement and does not require signature.,,,,,,
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(8, p.headerLine)
        assertEquals("Date", p.header.first())
        assertEquals(Columns(date = 0, description = 1, debit = 4, credit = 5, balance = 6, reference = 2), p.columns)
        assertEquals(3, p.rows.size)
        // Two separator rows, the four summary lines and the footer.
        assertEquals(7, p.skippedLines)

        val swiggy = p.rows[0]
        assertEquals(10, swiggy.line)
        assertEquals(LocalDate.of(2026, 10, 1), swiggy.date)
        assertEquals("UPI-SWIGGY-SWIGGY@ICICI-ICIC0000001-427412345678-PAYMENT", swiggy.description)
        assertEquals(34_900L, swiggy.amount)
        assertEquals(TxnType.EXPENSE, swiggy.type)
        assertEquals(4_523_110L, swiggy.balance)
        assertEquals("0000427412345678", swiggy.reference)

        val salary = p.rows[1]
        assertEquals(LocalDate.of(2026, 10, 3), salary.date)
        assertEquals(12_500_000L, salary.amount)
        assertEquals(TxnType.INCOME, salary.type)
        assertEquals(17_023_110L, salary.balance)

        assertEquals(500_000L, p.rows[2].amount)
        assertEquals(TxnType.EXPENSE, p.rows[2].type)
        assertEquals(12, p.rows[2].line)
    }

    @Test
    fun iciciStatementUsesTransactionDateOverValueDate() {
        val text = """
            ,,,,,,,,
            ,DETAILED STATEMENT,,,,,,,
            ,,,,,,,,
            ,Transactions List - FAKE NAME (INR) - XXXXXXXX4321,,,,,,,
            ,S No.,Value Date,Transaction Date,Cheque Number,Transaction Remarks,Withdrawal Amount (INR ),Deposit Amount (INR ),Balance (INR )
            ,1,02/10/2026,01/10/2026,-,UPI/427498765432/Blinkit/blinkit@hdfc/Groceries,"1,249.50",0.00,"38,750.50"
            ,2,04/10/2026,04/10/2026,-,NEFT-HDFCN52026100412345-FAKE EMPLOYER LTD,0.00,"85,000.00","1,23,750.50"
            ,3,06/10/2026,06/10/2026,000123,CHQ PAID-MICR CTS-BANGALORE,"15,000.00",0.00,"1,08,750.50"
            ,,,,,,,,
            ,Legends Used in Account Statement,,,,,,,
            ,"UPI - Unified Payments Interface, NEFT - National Electronic Funds Transfer",,,,,,,
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(5, p.headerLine)
        assertEquals("Withdrawal Amount (INR )", p.header[6])
        assertEquals(Columns(date = 3, description = 5, debit = 6, credit = 7, balance = 8, reference = 4), p.columns)
        assertEquals(3, p.rows.size)
        assertEquals(2, p.skippedLines)

        val blinkit = p.rows[0]
        assertEquals(LocalDate.of(2026, 10, 1), blinkit.date)
        assertEquals(124_950L, blinkit.amount)
        assertEquals(TxnType.EXPENSE, blinkit.type)
        assertEquals(3_875_050L, blinkit.balance)
        assertNull(blinkit.reference)

        assertEquals(8_500_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)
        assertEquals(12_375_050L, p.rows[1].balance)

        assertEquals("000123", p.rows[2].reference)
        assertEquals(1_500_000L, p.rows[2].amount)
    }

    @Test
    fun sbiTabSeparatedWithMonthNameDates() {
        val text = listOf(
            "Account Name\t:\tMr. FAKE SHARMA",
            "Address\t:\t42 Example Street, Pune",
            "Account Number\t:\tXXXXXXX5678",
            "Start Date\t:\t1 Oct 2026",
            "End Date\t:\t15 Oct 2026",
            "",
            "Txn Date\tValue Date\tDescription\tRef No./Cheque No.\t        Debit\tCredit\tBalance",
            "1 Oct 2026\t1 Oct 2026\tTO TRANSFER-UPI/DR/427411112222/ZOMATO/YESB/zomato@yesb/Food--\tTRANSFER TO 4897690000001\t450.00\t \t9,550.00",
            "7 Oct 2026\t7 Oct 2026\tBY TRANSFER-NEFT*HDFC0000001*N280261234567*FAKE EMPLOYER\tTRANSFER FROM 3199000000002\t \t60,000.00\t69,550.00",
            "12 Oct 2026\t13 Oct 2026\tATM WDL-ATM CASH 1234 PUNE MAIN BRANCH\t \t2,000.00\t \t67,550.00",
            "",
            "**This is a computer generated statement and does not require a signature",
        ).joinToString("\n")
        assertEquals('\t', StatementCsv.detectDelimiter(text.lines()))

        val p = StatementCsv.parse(text)
        assertEquals(7, p.headerLine)
        assertEquals(Columns(date = 0, description = 2, debit = 4, credit = 5, balance = 6, reference = 3), p.columns)
        assertEquals(3, p.rows.size)
        assertEquals(1, p.skippedLines)

        val zomato = p.rows[0]
        assertEquals(8, zomato.line)
        assertEquals(LocalDate.of(2026, 10, 1), zomato.date)
        assertEquals(45_000L, zomato.amount)
        assertEquals(TxnType.EXPENSE, zomato.type)
        assertEquals(955_000L, zomato.balance)
        assertEquals("TRANSFER TO 4897690000001", zomato.reference)

        assertEquals(6_000_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)

        val atm = p.rows[2]
        assertEquals(LocalDate.of(2026, 10, 12), atm.date)
        assertEquals(200_000L, atm.amount)
        assertNull(atm.reference)
        assertEquals(6_755_000L, atm.balance)
    }

    @Test
    fun axisUppercaseShortHeaders() {
        val text = """
            Name :- FAKE VERMA
            Statement of Account No - XXXXXXXXXXX9012 for the period (From : 01-10-2026 To : 15-10-2026)

            Tran Date,CHQNO,PARTICULARS,DR,CR,BAL,SOL
            01-10-2026,,UPI/P2M/427455556666/BIGBASKET/Groceries,2100.00,,27900.00,1234
            08-10-2026,,IMPS/P2A/427477778888/FAKE FRIEND/Rent share,,"12,000.00","39,900.00",1234
            10-10-2026,456789,CLG/FAKE LANDLORD/HDFC BANK,"18,000.00",,"21,900.00",1234
            ,,OPENING BALANCE,,,"30,000.00",
            ,,TRANSACTION TOTAL DR/CR,"20,100.00","12,000.00",,
            ,,CLOSING BALANCE,,,"21,900.00",
            Unless the constituent notifies the bank immediately of any discrepancy the statement is deemed correct.
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(4, p.headerLine)
        assertEquals(Columns(date = 0, description = 2, debit = 3, credit = 4, balance = 5, reference = 1), p.columns)
        assertEquals(3, p.rows.size)
        assertEquals(4, p.skippedLines)

        assertEquals(LocalDate.of(2026, 10, 1), p.rows[0].date)
        assertEquals(210_000L, p.rows[0].amount)
        assertEquals(TxnType.EXPENSE, p.rows[0].type)
        assertEquals(2_790_000L, p.rows[0].balance)
        assertNull(p.rows[0].reference)

        assertEquals(1_200_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)

        assertEquals("456789", p.rows[2].reference)
        assertEquals(1_800_000L, p.rows[2].amount)
        assertEquals(TxnType.EXPENSE, p.rows[2].type)
    }

    @Test
    fun kotakAmountWithDrCrColumn() {
        val text = """
            "Kotak Mahindra Bank"
            "Account No.","XXXXXX7890"
            "Period","01-10-2026 to 15-10-2026"
            ""
            "Sl. No.","Transaction Date","Value Date","Description","Chq / Ref No.","Amount","Dr / Cr","Balance","Dr / Cr"
            "1","01-10-2026 09:41:12","01-10-2026","UPI/FAKE CAFE/427400001111/Coffee","UPI-427400001111","180.00","DR","19,820.00","CR"
            "2","05-10-2026 18:02:55","05-10-2026","IMPS-427400002222-FAKE CLIENT-Invoice 42","IMPS-427400002222","25,000.00","CR","44,820.00","CR"
            "3","09-10-2026 11:15:00","09-10-2026","NACH/FAKE MUTUAL FUND SIP","NACH-0009","5,000.00","DR","39,820.00","CR"
            "","","","Opening balance","","20,000.00","CR","",""
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(5, p.headerLine)
        // The first "Dr / Cr" belongs to the amount; the second (for the balance) is left alone.
        assertEquals(Columns(date = 1, description = 3, amount = 5, drCr = 6, balance = 7, reference = 4), p.columns)
        assertEquals(3, p.rows.size)
        assertEquals(1, p.skippedLines)

        val cafe = p.rows[0]
        assertEquals(LocalDate.of(2026, 10, 1), cafe.date)
        assertEquals(18_000L, cafe.amount)
        assertEquals(TxnType.EXPENSE, cafe.type)
        assertEquals(1_982_000L, cafe.balance)
        assertEquals("UPI-427400001111", cafe.reference)

        assertEquals(2_500_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)
        assertEquals(500_000L, p.rows[2].amount)
        assertEquals(TxnType.EXPENSE, p.rows[2].type)
    }

    @Test
    fun singleSignedAmountColumn() {
        val text = """
            Date,Description,Amount,Balance
            2026-10-01,Grocery store,-1250.75,8749.25
            2026-10-02,Refund from FAKE SHOP,300.00,9049.25
            2026-10-03,Electricity bill,(2000.00),7049.25
            2026-10-04,Interest credit,+12.40,7061.65
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(Columns(date = 0, description = 1, amount = 2, balance = 3), p.columns)
        assertEquals(listOf(125_075L, 30_000L, 200_000L, 1_240L), p.rows.map { it.amount })
        assertEquals(
            listOf(TxnType.EXPENSE, TxnType.INCOME, TxnType.EXPENSE, TxnType.INCOME),
            p.rows.map { it.type },
        )
        assertEquals(LocalDate.of(2026, 10, 3), p.rows[2].date)
        assertEquals(704_925L, p.rows[2].balance)
    }

    @Test
    fun amountTaggedDrOrCrWithoutDirectionColumn() {
        val text = """
            Date,Description,Amount
            01/10/2026,Rent,"25,000.00 Dr"
            02/10/2026,Salary,"90,000.00 Cr"
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(listOf(2_500_000L, 9_000_000L), p.rows.map { it.amount })
        assertEquals(listOf(TxnType.EXPENSE, TxnType.INCOME), p.rows.map { it.type })
    }

    @Test
    fun semicolonDelimitedWithGroupedAmounts() {
        val text = """
            Txn Date;Narration;Debit;Credit;Balance
            01.10.2026;UPI/FAKE PHARMACY/Medicines, vitamins;1,234.00;;8,766.00
            02.10.2026;Cashback, card offer;;50.00;8,816.00
        """.trimIndent()
        assertEquals(';', StatementCsv.detectDelimiter(text.lines()))
        val p = StatementCsv.parse(text)
        assertEquals(2, p.rows.size)
        assertEquals("UPI/FAKE PHARMACY/Medicines, vitamins", p.rows[0].description)
        assertEquals(LocalDate.of(2026, 10, 1), p.rows[0].date)
        assertEquals(123_400L, p.rows[0].amount)
        assertEquals(TxnType.EXPENSE, p.rows[0].type)
        assertEquals(876_600L, p.rows[0].balance)
        assertEquals(5_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)
    }

    @Test
    fun quotedFieldsWithDelimitersAndEscapedQuotes() {
        val text = """
            Date,Description,Debit,Credit
            "01/10/2026","Payment to ""FAKE STORE"", Mumbai","1,500.00",""
            "02/10/2026","  Salary,   October  ",,"50,000.00"
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(2, p.rows.size)
        assertEquals("Payment to \"FAKE STORE\", Mumbai", p.rows[0].description)
        assertEquals(150_000L, p.rows[0].amount)
        assertEquals(TxnType.EXPENSE, p.rows[0].type)
        assertEquals("Salary, October", p.rows[1].description)
        assertEquals(5_000_000L, p.rows[1].amount)
        assertEquals(TxnType.INCOME, p.rows[1].type)
    }

    @Test
    fun quotedNarrationSpanningLines() {
        val text = "Date,Narration,Debit,Credit\n01/10/2026,\"UPI payment\nto FAKE SHOP\",100.00,\n02/10/2026,Next,,5.00"
        val p = StatementCsv.parse(text)
        assertEquals(2, p.rows.size)
        assertEquals("UPI payment to FAKE SHOP", p.rows[0].description)
        assertEquals(2, p.rows[0].line)
        assertEquals(4, p.rows[1].line)
        assertEquals(0, p.skippedLines)
    }

    @Test
    fun summaryAndFooterLinesAreSkippedEvenWithADate() {
        val text = """
            Date,Description,Amount,Dr/Cr,Balance
            01/10/2026,Opening Balance,"10,000.00",Cr,"10,000.00"
            02/10/2026,UPI/FAKE GROCER,500.00,Dr,"9,500.00"
            15/10/2026,Closing Balance,"9,500.00",Cr,"9,500.00"
            ,Total,500.00,,
            ** End of statement **
            Legend: Dr = Debit; Cr = Credit
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(Columns(date = 0, description = 1, amount = 2, drCr = 3, balance = 4), p.columns)
        assertEquals(1, p.rows.size)
        assertEquals("UPI/FAKE GROCER", p.rows[0].description)
        assertEquals(50_000L, p.rows[0].amount)
        assertEquals(TxnType.EXPENSE, p.rows[0].type)
        assertEquals(5, p.skippedLines)
    }

    @Test
    fun rowsWithNoDebitOrCreditAreSkipped() {
        val text = """
            Date,Narration,Withdrawal Amt,Deposit Amt,Closing Balance
            01/10/2026,Balance enquiry,0.00,0.00,"5,000.00"
            02/10/2026,Charges reversal,,,"5,000.00"
            03/10/2026,Coffee,80.00,0.00,"4,920.00"
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(1, p.rows.size)
        assertEquals("Coffee", p.rows[0].description)
        assertEquals(8_000L, p.rows[0].amount)
        assertEquals(2, p.skippedLines)
    }

    @Test
    fun overdrawnBalanceTaggedDrIsNegative() {
        val text = """
            Date,Particulars,Debit,Credit,Balance
            01/10/2026,Cheque return charges,590.00,,590.00 Dr
            02/10/2026,Cash deposit,,"1,000.00",410.00 Cr
        """.trimIndent()
        val p = StatementCsv.parse(text)
        assertEquals(-59_000L, p.rows[0].balance)
        assertEquals(41_000L, p.rows[1].balance)
    }

    @Test
    fun bomCrlfAndBlankLines() {
        val text = "\uFEFFDate,Narration,Withdrawal,Deposit\r\n\r\n01/10/2026,Tea,20.00,\r\n\r\n02/10/2026,Refund,,20.00\r\n"
        val p = StatementCsv.parse(text)
        assertEquals(1, p.headerLine)
        assertEquals(listOf("Date", "Narration", "Withdrawal", "Deposit"), p.header)
        assertEquals(listOf(3, 5), p.rows.map { it.line })
        assertEquals("Tea", p.rows[0].description)
        assertEquals(TxnType.INCOME, p.rows[1].type)
        assertEquals(0, p.skippedLines)
    }

    @Test
    fun explicitColumnsWhenHeaderIsUnrecognised() {
        val text = """
            Posted,Memo,Out,In,Running
            01/10/2026,Coffee,120.00,,880.00
            02/10/2026,Refund,,40.00,920.00
        """.trimIndent()

        val auto = StatementCsv.parse(text)
        assertNull(auto.columns)
        assertEquals(0, auto.headerLine)
        assertTrue(auto.header.isEmpty())
        assertTrue(auto.rows.isEmpty())
        assertEquals(3, auto.skippedLines)

        val columns = Columns(date = 0, description = 1, debit = 2, credit = 3, balance = 4)
        val p = StatementCsv.parse(text, columns)
        assertEquals(columns, p.columns)
        assertEquals(0, p.headerLine)
        assertEquals(2, p.rows.size)
        // Without a recognised header the header line itself is just an unreadable row.
        assertEquals(1, p.skippedLines)
        assertEquals(12_000L, p.rows[0].amount)
        assertEquals(TxnType.EXPENSE, p.rows[0].type)
        assertEquals(92_000L, p.rows[1].balance)
        assertEquals(TxnType.INCOME, p.rows[1].type)
    }

    @Test
    fun explicitColumnsOverrideTheDetectedMapping() {
        val text = """
            Date,Value Date,Description,Debit,Credit
            30/09/2026,01/10/2026,Card settlement,999.00,
        """.trimIndent()
        assertEquals(LocalDate.of(2026, 9, 30), StatementCsv.parse(text).rows.single().date)

        val p = StatementCsv.parse(text, Columns(date = 1, description = 2, debit = 3, credit = 4))
        assertEquals(1, p.headerLine)
        assertEquals(0, p.skippedLines)
        assertEquals(LocalDate.of(2026, 10, 1), p.rows.single().date)
        assertEquals(99_900L, p.rows.single().amount)
    }

    @Test
    fun splitLineHandlesQuotes() {
        assertEquals(listOf("a", "b", "", "c"), StatementCsv.splitLine("a,b,,c", ','))
        assertEquals(
            listOf("x, y", "he said \"hi\"", "z"),
            StatementCsv.splitLine("\"x, y\",\"he said \"\"hi\"\"\",z", ','),
        )
        assertEquals(listOf("q", "x"), StatementCsv.splitLine(" \"q\" ,x", ','))
        assertEquals(listOf("5\" pipe", "x"), StatementCsv.splitLine("5\" pipe,x", ','))
        assertEquals(listOf("", ""), StatementCsv.splitLine("\"\",", ','))
        assertEquals(listOf("a", ""), StatementCsv.splitLine("a,", ','))
        assertEquals(listOf(""), StatementCsv.splitLine("", ','))
        assertEquals(listOf("a,b", "c"), StatementCsv.splitLine("a,b|c", '|'))
        assertEquals(listOf("a b", " c"), StatementCsv.splitLine("a b\t c", '\t'))
        assertEquals(listOf("1", "a;b", "2"), StatementCsv.splitLine("1;\"a;b\";2", ';'))
    }

    @Test
    fun detectDelimiterFromHeaderOrConsistency() {
        assertEquals(',', StatementCsv.detectDelimiter(listOf("Bank,Statement", "Date,Narration,Debit,Credit")))
        assertEquals('\t', StatementCsv.detectDelimiter(listOf("Txn Date\tDescription\tDebit\tCredit")))
        assertEquals('|', StatementCsv.detectDelimiter(listOf("Date|Description|Amount", "01/10/2026|Tea|-20")))
        // No header: the delimiter that splits every line the same way wins over commas inside text.
        assertEquals(';', StatementCsv.detectDelimiter(listOf("01/10/2026;Tea, snacks;20.00", "02/10/2026;Bus;15.00")))
        assertEquals(',', StatementCsv.detectDelimiter(listOf("01/10/2026,\"Tea; snacks\",20.00", "02/10/2026,Bus,15.00")))
        assertEquals(',', StatementCsv.detectDelimiter(emptyList()))
    }

    @Test
    fun parseDateFormats() {
        val oct12 = LocalDate.of(2026, 10, 12)
        val formats = listOf(
            "12/10/2026", "12/10/26", "12-10-2026", "12-10-26", "12.10.2026", "12.10.26",
            "12-Oct-2026", "12-OCT-26", "12 Oct 2026", "12 oct 26", "12 October 2026", "12Oct2026", "12-Oct-2026 ",
            "2026-10-12", "2026/10/12", "Oct 12, 2026",
            "12/10/2026 14:22:01", "12/10/2026 02:22 PM", "2026-10-12T10:00:00", "2026-10-12T10:00:00+05:30",
            "12-Oct-2026 10:15",
        )
        for (f in formats) assertEquals(f, oct12, StatementCsv.parseDate(f))
        assertEquals(LocalDate.of(2026, 2, 1), StatementCsv.parseDate("1/2/2026"))
        assertEquals(LocalDate.of(2026, 4, 5), StatementCsv.parseDate("05/04/2026")) // day-first
        assertEquals(LocalDate.of(2026, 9, 1), StatementCsv.parseDate("1 Sept 2026"))
        assertEquals(LocalDate.of(2026, 1, 31), StatementCsv.parseDate("31-JANUARY-2026"))
        assertEquals(LocalDate.of(2099, 12, 31), StatementCsv.parseDate("31/12/99"))
    }

    @Test
    fun parseDateRejectsNonDates() {
        val bad = listOf(
            "", "-", "Opening Balance", "********", "31/02/2026", "13/13/2026", "10/25/2026", "45,231.10",
            "12 Foo 2026", "Generated On: 16/10/2026", "2026-10", "349.00",
        )
        for (b in bad) assertNull(b, StatementCsv.parseDate(b))
    }

    @Test
    fun parseAmountFormats() {
        assertEquals(12_345_678L, StatementCsv.parseAmount("1,23,456.78"))
        assertEquals(50_000L, StatementCsv.parseAmount("Rs. 500"))
        assertEquals(50_000L, StatementCsv.parseAmount("Rs.500"))
        assertEquals(200_050L, StatementCsv.parseAmount("INR 2,000.50"))
        assertEquals(100_000L, StatementCsv.parseAmount("₹1,000"))
        assertEquals(50_000L, StatementCsv.parseAmount("500.00 Cr"))
        assertEquals(25_000L, StatementCsv.parseAmount("Dr 250"))
        assertEquals(100_000L, StatementCsv.parseAmount("1,000.00Dr."))
        assertEquals(50_000L, StatementCsv.parseAmount("-500"))
        assertEquals(50_000L, StatementCsv.parseAmount("(500.00)"))
        assertEquals(50_000L, StatementCsv.parseAmount("500-"))
        assertEquals(34_950L, StatementCsv.parseAmount("  349.5 "))
        assertEquals(50L, StatementCsv.parseAmount(".50"))
        assertEquals(1_240L, StatementCsv.parseAmount("+12.40"))
    }

    @Test
    fun parseAmountWithoutAnAmount() {
        for (raw in listOf("", "  ", "-", "0", "0.00", "-0.00", "Rs. 0.00", "N/A", "abc", "Cr", "1.2.3", "12345678901234.00")) {
            assertNull(raw, StatementCsv.parseAmount(raw))
        }
    }
}
