package com.amitjha.kosh.model

/** Banks and card issuers the app recognises. Parsers are tuned for ICICI, HDFC, Kotak and DBS. */
enum class Bank(val displayName: String, val shortName: String, val brandColor: Long) {
    ICICI("ICICI Bank", "ICICI", 0xFFB02A30),
    HDFC("HDFC Bank", "HDFC", 0xFF004C8F),
    KOTAK("Kotak Mahindra Bank", "Kotak", 0xFFED1C24),
    DBS("DBS Bank", "DBS", 0xFFE20613),
    SBI("State Bank of India", "SBI", 0xFF22409A),
    AXIS("Axis Bank", "Axis", 0xFF97144D),
    OTHER("Other", "Other", 0xFF5F6B7A);

    companion object {
        fun fromName(name: String?): Bank = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

enum class TxnType { EXPENSE, INCOME, TRANSFER }

enum class TxnChannel { UPI, CARD, NEFT, IMPS, RTGS, ATM, NETBANKING, AUTO_DEBIT, CHEQUE, OTHER }

enum class AccountType { BANK, CREDIT_CARD, CASH, WALLET }

enum class TxnSource { MANUAL, SMS, EMAIL, AUTO }

enum class InvestmentType { FD, EPF, NPS }
