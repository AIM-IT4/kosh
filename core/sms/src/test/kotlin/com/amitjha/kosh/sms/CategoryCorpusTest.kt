package com.amitjha.kosh.sms

import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A corpus of realistic Indian merchants, UPI payees and message bodies with their expected category. */
class CategoryCorpusTest {

    private data class Case(val merchant: String?, val body: String, val expected: String, val channel: TxnChannel? = null)

    private val cases = listOf(
        // food
        Case("Swiggy", "Sent Rs.342 to swiggy@icici", "food"),
        Case("Zomato", "UPI-ZOMATO", "food"),
        Case("Domino's", "spent at DOMINOS PIZZA", "food"),
        Case("McDonald's", "", "food"),
        Case("Starbucks", "", "food"),
        Case("Chaayos", "", "food"),
        Case("Third Wave Coffee", "", "food"),
        Case("Haldiram Snacks", "", "food"),
        Case("Barbeque Nation", "", "food"),
        Case("Eatsure", "", "food"),
        Case("Annapoorna Restaurant", "", "food"),
        Case("Sri Krishna Sweets", "", "food"),
        Case("Burger King", "", "food"),
        // groceries
        Case("Swiggy", "Spent Rs 640 on SWIGGY INSTAMART", "groceries"),
        Case("Blinkit", "", "groceries"),
        Case("Zepto", "", "groceries"),
        Case("BigBasket", "", "groceries"),
        Case("DMart", "", "groceries"),
        Case("JioMart", "", "groceries"),
        Case("Reliance Fresh", "", "groceries"),
        Case("Country Delight", "", "groceries"),
        Case("Sharma Kirana Store", "", "groceries"),
        Case("Licious", "", "groceries"),
        // shopping
        Case("Amazon", "spent using ICICI Bank Card on AMAZON", "shopping"),
        Case("Flipkart", "", "shopping"),
        Case("Myntra", "", "shopping"),
        Case("Nykaa", "", "shopping"),
        Case("Croma", "", "shopping"),
        Case("Decathlon", "", "shopping"),
        Case("Zudio", "", "shopping"),
        Case("Tanishq", "", "shopping"),
        Case("Lifestyle", "", "shopping"),
        Case("Raj Electronics", "", "shopping"),
        // transport / fuel
        Case("Uber", "", "transport"),
        Case("Ola", "", "transport"),
        Case("Rapido", "", "transport"),
        Case("Delhi Metro Rail", "", "transport"),
        Case("NETC FASTag", "FASTag recharge", "transport"),
        Case("Indian Oil", "", "fuel"),
        Case("HP Petrol", "", "fuel"),
        Case("Shell", "", "fuel"),
        Case("Krishna Filling Station", "", "fuel"),
        // bills / subscriptions
        Case("Airtel", "", "bills"),
        Case("Jio", "Jio prepaid recharge", "bills"),
        Case("BESCOM", "", "bills"),
        Case("Tata Power", "", "bills"),
        Case("Mahanagar Gas", "", "bills"),
        Case("ACT Fibernet", "", "bills"),
        Case("Netflix", "", "subscriptions"),
        Case("Spotify", "", "subscriptions"),
        Case("Amazon Prime", "", "subscriptions"),
        Case("YouTube", "YouTube Premium", "subscriptions"),
        Case("Apple", "APPLE.COM/BILL", "subscriptions"),
        Case("OpenAI", "CHATGPT SUBSCRIPTION", "subscriptions"),
        Case("JioHotstar", "", "subscriptions"),
        // rent / emi / investment / insurance / taxes
        Case("NoBroker", "Rent payment", "rent"),
        Case("Green Valley Society", "maintenance", "rent"),
        Case("Bajaj Finserv", "EMI", "emi"),
        Case(null, "NACH debit for LOAN A/C 1234 EMI Rs 12,500", "emi"),
        Case("Zerodha", "", "investment"),
        Case("Groww", "SIP", "investment"),
        Case(null, "Rs 5000 debited towards SIP mutual fund", "investment"),
        Case("LIC", "premium", "insurance"),
        Case("Star Health", "", "insurance"),
        Case("Acko", "", "insurance"),
        Case("Income Tax Department", "", "taxes"),
        Case("TIN NSDL", "", "taxes"),
        // health / fitness / personal care / education
        Case("Apollo", "", "health"),
        Case("PharmEasy", "", "health"),
        Case("Tata 1mg", "", "health"),
        Case("City Care Hospital", "", "health"),
        Case("Sai Medical Stores", "", "health"),
        Case("Cult.fit", "", "fitness"),
        Case("Gold's Gym", "", "fitness"),
        Case("Urban Company", "", "personal_care"),
        Case("Looks Salon", "", "personal_care"),
        Case("Udemy", "", "education"),
        Case("St Xavier School", "fees", "education"),
        // travel / entertainment / household / gifts
        Case("IRCTC", "", "travel"),
        Case("MakeMyTrip", "", "travel"),
        Case("IndiGo", "", "travel"),
        Case("OYO", "", "travel"),
        Case("redBus", "", "travel"),
        Case("BookMyShow", "", "entertainment"),
        Case("PVR", "", "entertainment"),
        Case("Pepperfry", "", "household"),
        Case("Wakefit", "", "household"),
        Case("Ferns N Petals", "", "gifts"),
        Case("ISKCON Temple", "donation", "gifts"),
        // cash / transfer / people
        Case("ATM withdrawal", "Rs 5000 withdrawn at ATM", "cash", TxnChannel.ATM),
        Case("CRED", "paid to CRED", "transfer"),
        Case("UPI XXXXXX3210", "Sent Rs.500 to 9876543210@ybl", "people"),
        Case("Rahul Kumar", "Sent Rs.1000 to rahulk@okaxis", "people"),
        Case("Priya", "Sent Rs.300 to priya.s@okhdfcbank UPI/P2A", "people"),
        Case("Amit Jha", "IMPS to AMIT JHA", "people"),
        Case(null, "Rs 2,000 sent via IMPS to a/c XX1234", "people"),
        // unknown merchants still get a real category
        Case("Bluemoon", "Paid to bluemoon via paytmqr281005@paytm", "shopping"),
        Case("Kumar Enterprises", "", "shopping"),
    )

    @Test
    fun corpusIsCategorisedCorrectly() {
        val wrong = cases.mapNotNull { c ->
            val got = CategoryGuesser.guess(c.merchant, c.body, TxnType.EXPENSE, c.channel)
            if (got != c.expected) "${c.merchant} / '${c.body}': expected ${c.expected}, got $got" else null
        }
        assertTrue("Miscategorised:\n" + wrong.joinToString("\n"), wrong.isEmpty())
        assertTrue("corpus too small", cases.size >= 80)
    }

    @Test
    fun otherIsALastResort() {
        val other = cases.count { CategoryGuesser.guess(it.merchant, it.body, TxnType.EXPENSE, it.channel) == "other" }
        assertEquals(0, other)
        assertEquals("other", CategoryGuesser.guess(null, "Rs 500 debited from your a/c", TxnType.EXPENSE))
    }

    @Test
    fun fullMessagesGetRealCategories() {
        fun cat(sender: String, body: String): String =
            (SmsParser.parse(SmsMessage(sender, body, 1_736_000_000_000)) as SmsParseResult.Transaction).txn.suggestedCategory
        assertEquals("cash", cat("AD-HDFCBK", "Rs.5000 withdrawn from HDFC Bank Card x1234 at ATM MG ROAD on 05-01-25. Avl Bal Rs.20,000"))
        assertEquals(
            "people",
            cat("JD-KOTAKB-S", "Sent Rs.500.00 from Kotak Bank AC X1234 to rahul.k@okaxis on 05-01-25.UPI Ref 512345678901. Not you, kotak.com/fraud"),
        )
    }
}
