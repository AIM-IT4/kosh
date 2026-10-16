package com.amitjha.kosh.sms

import com.amitjha.kosh.model.TxnChannel
import com.amitjha.kosh.model.TxnType

/**
 * Suggests one of the app's category keys for an imported transaction.
 *
 * Order of evidence, strongest first:
 * 1. channel / message hints that decide on their own (ATM → cash, card bill payments → transfer);
 * 2. a dictionary of 1,000+ Indian merchant patterns (kosh/merchants.json), matched on the merchant name;
 * 3. generic business words in the merchant name ("Sharma Medical Stores" → health);
 * 4. the same merchant dictionary and a few strong phrases in the message body;
 * 5. person-to-person heuristics (UPI to a person, IMPS/NEFT to a name) → people;
 * 6. any other named merchant → shopping. "other" is used only when there is nothing to go on.
 */
object CategoryGuesser {

    val CATEGORY_KEYS = listOf(
        "food", "groceries", "shopping", "transport", "fuel", "bills", "rent", "entertainment", "health",
        "fitness", "education", "travel", "subscriptions", "emi", "investment", "insurance", "taxes", "cash",
        "people", "personal_care", "household", "gifts", "transfer", "salary", "refund", "interest", "cashback",
        "other_income", "other",
    )

    private class Rule(val category: String, brands: String, generic: String = "") {
        val brands = Regex("(?i)(?:$brands)")
        val generic: Regex? = generic.takeIf { it.isNotEmpty() }?.let { Regex("(?i)(?:$it)") }
    }

    /**
     * Checked in order, first match wins. Specific brands come before categories whose brands they
     * contain ("amazon prime" before "amazon", "jiomart" before "jio", "instamart" before "swiggy").
     */
    /**
     * The bundled community dataset (kosh/merchants.json), or the built-in copy below if it can't be read.
     */
    private val rules: List<Rule> = MerchantDataset.load()
        ?.rules?.map { Rule(it.category, it.brands.joinToString("|"), it.generic.joinToString("|")) }
        ?: builtInRules()

    /** Number of categorisation rules in use (for diagnostics and tests). */
    val ruleCount: Int get() = rules.size

    private fun builtInRules(): List<Rule> = listOf(
        Rule(
            "emi",
            "\\bemi\\b|bajaj ?fin(serv)?|home ?loan|car ?loan|personal ?loan|two ?wheeler ?loan|loan ?(a/?c|account|emi|repay)" +
                "|tata ?capital|hdb ?financial|lic ?housing|pnb ?housing|indiabulls|kreditbee|money ?view|moneytap|navi ?(finserv|loan)" +
                "|fullerton|muthoot|manappuram|lazypay|simpl\\b|zestmoney|early ?salary|fibe\\b|cashe\\b|home ?credit|l&t ?finance" +
                "|aditya ?birla ?finance|poonawalla|piramal ?finance|shriram ?finance|cholamandalam|mahindra ?finance",
            "\\bloan\\b|finance ltd|finserv",
        ),
        Rule(
            "investment",
            "\\bsip\\b|mutual ?fund|zerodha|groww|nextbillion|upstox|rksv|kuvera|coin ?by|indmoney|et ?money|smallcase|paytm ?money" +
                "|angel ?(one|broking)|5 ?paisa|icici ?direct|icici ?securities|hdfc ?securities|kotak ?securities|motilal|sharekhan" +
                "|\\bdhan\\b|fyers|\\bnps\\b|nps ?trust|\\bppf\\b|bse ?ltd|bse ?star|nse ?clearing|nsccl|iccl|indian ?clearing|\\bcams\\b" +
                "|kfin|karvy|mf ?utilit|sgb\\b|gold ?bond|digital ?gold|safegold|augmont|jar ?app|wint ?wealth|grip ?invest|golden ?pi",
            "securities|broking|capital ?markets|wealth",
        ),
        Rule(
            "insurance",
            "insurance|\\blic\\b|life ?insurance ?corp|hdfc ?life|hdfc ?ergo|icici ?pru|icici ?lombard|max ?life|sbi ?life" +
                "|sbi ?general|bajaj ?allianz|star ?health|niva ?bupa|care ?health|\\backo\\b|go ?digit|policy ?bazaar|tata ?aig" +
                "|reliance ?general|new ?india ?assurance|oriental ?insurance|united ?india|kotak ?life|aditya ?birla ?(health|sun ?life)" +
                "|manipal ?cigna|future ?generali|royal ?sundaram|iffco ?tokio|chola ?ms|liberty ?general|bharti ?axa|tata ?aia|pnb ?metlife",
            "assurance|premium",
        ),
        Rule(
            "taxes",
            "income ?tax|incometax|tin[- ]?nsdl|protean ?egov|cbdt|advance ?tax|self ?assessment ?tax|tax ?payment|e-?pay ?tax" +
                "|property ?tax|municipal ?corp|mcgm|bbmp|ghmc|pmc ?property|gst ?payment|gstn",
            "\\btax\\b|challan",
        ),
        Rule(
            "transfer",
            "credit ?card ?(bill|payment)|card ?bill ?pay|cc ?bill|\\bcred\\b|dreamplug|billdesk ?.*card|bobcard ?payment",
        ),
        Rule(
            "rent",
            "\\brent\\b|nobroker(?!hood)|nestaway|housing\\.com|magicbricks|zolo|stanza ?living|colive|square ?yards",
            "landlord|society|maintenance|apartment|residency|\\brwa\\b|nobrokerhood|mygate",
        ),
        Rule(
            "groceries",
            "instamart|bigbasket|bbnow|innovative ?retail|blinkit|grofers|zepto|kiranakart|dmart|avenue ?supermarts|jiomart" +
                "|reliance ?(fresh|smart|retail)|smart ?bazaar|more ?(retail|supermarket|megastore)|nature'?s ?basket|spencer" +
                "|star ?bazaar|\\bspar\\b|lulu|ratnadeep|vijetha|heritage ?fresh|dunzo|country ?delight|milkbasket|licious" +
                "|fresh ?to ?home|freshtohome|zappfresh|otipy|easyday|nilgiris|foodhall|big ?bazaar|amul|mother ?dairy|nandini",
            "kirana|provision|general ?store|grocer|supermarket|hypermarket|vegetable|fruits?\\b|dairy|\\bmilk\\b|departmental|mart\\b|bazaar",
        ),
        Rule(
            "subscriptions",
            "netflix|spotify|amazon ?prime|prime ?video|primevideo|hotstar|jio ?cinema|jiohotstar|disney|youtube|yt ?premium" +
                "|apple\\.com|itunes|apple ?services|icloud|google ?one|google ?play|google ?storage|microsoft|office ?365|xbox ?game ?pass" +
                "|adobe|chatgpt|openai|anthropic|notion|linkedin|sony ?liv|sonyliv|zee5|altbalaji|alt ?balaji|mx ?player|gaana|wynk" +
                "|jiosaavn|audible|kindle|medium\\.com|substack|github|dropbox|canva|zoom\\.us|grammarly|duolingo|tinder|bumble" +
                "|times ?prime|discovery\\+|sun ?nxt|hoichoi|eros ?now|lionsgate|mubi|crunchyroll|perplexity|claude\\.ai",
            "subscription|membership|renewal",
        ),
        Rule(
            "fitness",
            "cult\\.?fit|cultfit|curefit|gold'?s ?gym|anytime ?fitness|healthifyme|healthify|fitpass|snap ?fitness|talwalkars|crossfit",
            "\\bgym\\b|fitness|yoga|zumba|pilates",
        ),
        Rule(
            "health",
            "apollo|\\b1mg\\b|tata ?1mg|pharmeasy|netmeds|medplus|practo|thyrocare|lal ?path|dr ?lal|metropolis|healthians|redcliffe" +
                "|max ?healthcare|fortis|manipal ?hospital|narayana|aster ?(dm|cmi)|medanta|aiims|wellness ?forever|truemeds|frank ?ross" +
                "|guardian ?pharmacy|healthkart|mfine|tata ?health|eye ?q|lenskart ?eye ?test",
            "pharma|chemist|medical|hospital|clinic|dental|dentist|diagnostic|\\blabs?\\b|nursing ?home|healthcare|doctor|\\bdr\\.? |ayurved|homeopath|physio",
        ),
        Rule(
            "personal_care",
            "urban ?company|urbanclap|lakme ?salon|naturals ?salon|jawed ?habib|toni ?(&|and) ?guy|enrich ?salon|bodycraft|yes ?madam|green ?trends",
            "salon|saloon|parlou?r|\\bspa\\b|barber|beauty|grooming|unisex|hair ?cut",
        ),
        Rule(
            "household",
            "pepperfry|wakefit|urban ?ladder|home ?centre|homecentre|sleepwell|duroflex|livspace|godrej ?interio|nilkamal|home ?town" +
                "|asian ?paints|berger|kent ?ro|aquaguard|eureka ?forbes|wonderchef|prestige ?xclusive",
            "furniture|hardware|electricals|sanitary|laundry|dry ?clean|pest ?control|plumb|carpenter|paints|interior|home ?decor|utensils",
        ),
        Rule(
            "gifts",
            "ferns ?n ?petals|\\bfnp\\b|\\bigp\\b|giva|archies|ketto|milaap|give\\.do|giveindia|akshaya ?patra|\\bcry\\b|smile ?foundation",
            "\\bgift|donation|donate|temple|mandir|church|masjid|mosque|gurudwara|charity|trust\\b|foundation|\\bngo\\b|flowers?\\b|florist",
        ),
        Rule(
            "education",
            "udemy|coursera|byju|unacademy|vedantu|upgrad|simplilearn|great ?learning|scaler|physics ?wallah|toppr|whitehat|edx" +
                "|skillshare|masterclass|leetcode|ielts|toefl|aakash|allen ?career|fiitjee|khan ?academy|brilliant\\.org",
            "school|college|university|institute|academy|tuition|classes|coaching|\\bfees?\\b|education|learning|vidyalaya|edu\\b",
        ),
        Rule(
            "travel",
            "irctc|makemytrip|\\bmmt\\b|goibibo|cleartrip|yatra|ixigo|easemytrip|indigo|interglobe|air ?india|vistara|akasa|spicejet" +
                "|air ?asia|aix ?connect|alliance ?air|go ?first|\\boyo\\b|oravel|airbnb|booking\\.com|agoda|trivago|treebo|fabhotel" +
                "|taj ?hotels|ihcl|marriott|hyatt|itc ?hotels|lemon ?tree|radisson|hilton|redbus|abhibus|zingbus|intrcity|flixbus" +
                "|ksrtc|msrtc|tsrtc|apsrtc|gsrtc|upsrtc|thomas ?cook|\\bvfs\\b|zostel|club ?mahindra|sterling ?holiday",
            "hotel|resort|\\binn\\b|lodge|travels|tours|holiday|airlines?\\b|airport|homestay|hostel",
        ),
        Rule(
            "transport",
            "\\buber\\b|\\bola\\b|olacabs|ani ?technologies|rapido|roppen|blusmart|bluesmart|namma ?yatri|\\bmeru\\b|quick ?ride|yulu" +
                "|\\bbounce\\b|vogo|\\bdmrc\\b|delhi ?metro|bmrcl|namma ?metro|mmrda|mmrcl|mumbai ?metro|hmrl|hyderabad ?metro|cmrl|kmrl" +
                "|fastag|\\bnetc\\b|\\bnhai\\b|park\\+|indrive|zoomcar|revv|drivezy|chalo ?app|\\bbest\\b ?bus|metro ?card",
            "metro|\\btoll|parking|\\bcabs?\\b|taxi|auto ?rickshaw|motors|automobile|tyres?\\b|car ?wash|service ?centre|garage",
        ),
        Rule(
            "fuel",
            "bpcl|bharat ?petroleum|hpcl|hindustan ?petroleum|hp ?petrol|\\biocl?\\b|indian ?oil|\\bshell\\b|nayara|jio-?bp|reliance ?bp" +
                "|essar ?oil|tata ?power ?ez|statiq|charge ?zone|ather ?grid",
            "petrol|diesel|\\bfuel|filling ?station|service ?station|\\bcng\\b|ev ?charg|petroleum",
        ),
        Rule(
            "food",
            "swiggy|zomato|eatsure|eat ?sure|box8|faasos|behrouz|oven ?story|rebel ?foods|domino|jubilant|pizza ?hut|mcdonald" +
                "|hardcastle|westlife|\\bkfc\\b|devyani|burger ?king|subway|starbucks|cafe ?coffee ?day|\\bccd\\b|chaayos|chai ?point" +
                "|third ?wave|blue ?tokai|haldiram|bikanervala|barbeque ?nation|wow ?momo|taco ?bell|dunkin|baskin|keventers|theobroma" +
                "|social\\b|hard ?rock|mainland ?china|paradise ?biryani|biryani ?blues|magicpin|dineout|eazydiner|zomato ?gold|swiggy ?dineout" +
                "|natural ?ice ?cream|naturals ?ice|cream ?bell|wendy|krispy|tim ?hortons|costa ?coffee|mad ?over ?donuts|chili'?s|nando",
            "restaurant|\\bresto|dhaba|\\bcafe|café|bakery|bakers|sweets|mithai|\\bfoods?\\b|eatery|canteen|\\bmess\\b|tiffin|juice" +
                "|\\bchai\\b|\\btea\\b|pizza|burger|momo|shawarma|dosa|idli|biryani|kitchen|bistro|\\bpub\\b|brewery|lounge|bar ?&|grill|dine",
        ),
        Rule(
            "entertainment",
            "bookmyshow|bigtree|\\bpvr\\b|inox|cinepolis|carnival ?cinemas|miraj|paytm ?insider|insider\\.in|district\\b|steam ?games" +
                "|steampowered|playstation|\\bpsn\\b|epic ?games|riot ?games|nintendo|dream11|my11circle|\\bmpl\\b|winzo|rummy|pokerbaazi" +
                "|smaaash|timezone|wonderla|imagica|ticketnew|skillbox|bowling|kidzania|snow ?world",
            "cinema|movies?\\b|theatre|gaming|games?\\b|amusement|concert|events?\\b|club\\b",
        ),
        Rule(
            "bills",
            "airtel|\\bjio\\b|reliance ?jio|vodafone|\\bvi\\b|\\bvil\\b|bsnl|mtnl|tata ?play|tata ?sky|dish ?tv|\\bd2h\\b|sun ?direct" +
                "|act ?fibernet|act ?broadband|hathway|excitel|you ?broadband|tikona|spectranet|bescom|msedcl|mahadiscom|tpddl|tata ?power" +
                "|\\bbses\\b|adani ?electricity|\\bcesc\\b|tneb|tangedco|kseb|uppcl|pspcl|jvvnl|dhbvn|uhbvn|wbsedcl|apspdcl|tsspdcl|tgspdcl" +
                "|torrent ?power|\\bmseb\\b|indraprastha ?gas|\\bigl\\b|mahanagar ?gas|\\bmgl\\b|gujarat ?gas|adani ?(total )?gas|indane" +
                "|bharat ?gas|hp ?gas|jal ?board|bwssb|\\bbbps\\b|bill ?desk|billdesk|paytm ?bill|bharat ?bill",
            "electricity|power ?distribution|\\bgas\\b|\\blpg\\b|water ?(bill|supply)|broadband|fibernet|internet|telecom|recharge" +
                "|postpaid|prepaid|\\bdth\\b|utility|utilities|\\bbill\\b",
        ),
        Rule(
            "shopping",
            "amazon|amzn|flipkart|fkrt|myntra|ajio|nykaa|meesho|tata ?cliq|tatacliq|snapdeal|shopclues|croma|reliance ?digital" +
                "|vijay ?sales|decathlon|ikea|lenskart|firstcry|h ?& ?m|\\bhnm\\b|zara|uniqlo|shoppers ?stop|lifestyle|westside" +
                "|pantaloons|max ?fashion|reliance ?trends|v-?mart|zudio|\\bbata\\b|metro ?shoes|nike|adidas|puma|levi'?s|allen ?solly" +
                "|van ?heusen|peter ?england|fabindia|\\bbiba\\b|manyavar|tanishq|caratlane|bluestone|malabar|kalyan ?jewel|titan" +
                "|fastrack|\\bboat\\b|noise\\b|oneplus|samsung|xiaomi|apple ?store|imagine ?store|poorvika|sangeetha|purplle|mamaearth" +
                "|sugar ?cosmetics|man ?company|beardo|bewakoof|souled ?store|urbanic|shein|mokobara|wildcraft|skechers|crocs|jockey" +
                "|miniso|daiso|hamleys|staples|landmark|sapna ?book|crossword",
            "\\bmall\\b|\\bstores?\\b|retail|fashion|garments|textiles|footwear|boutique|electronics|mobiles?\\b|jewell?er|jewell?ery" +
                "|enterprises|traders|trading|emporium|collection|apparel|clothing|optical|opticians|cosmetics|books?\\b|stationery|gifts? ?shop",
        ),
    )

    private val atm = Regex("(?i)\\batm\\b|cash ?withdrawal|cash ?wdl|withdrawn ?(at|from)|\\bnfs\\b")
    private val instamart = Regex("(?i)instamart")
    private val bodyPhrases: List<Pair<String, Regex>> = listOf(
        "investment" to "\\bsip\\b|mutual ?fund|\\bmf\\b ?(purchase|sip)",
        "emi" to "\\bemi\\b|loan ?(a/?c|account|repayment)|\\bnach\\b|\\becs\\b",
        "insurance" to "insurance ?premium|policy ?(no|premium)",
        "bills" to "recharge|electricity|broadband|\\bdth\\b|postpaid|bill ?payment|\\bbbps\\b",
        "rent" to "\\brent\\b",
        "taxes" to "income ?tax|tax ?payment|challan",
    ).map { it.first to Regex("(?i)" + it.second) }

    /** UPI handles that belong to merchants (QR codes, payment aggregators). */
    private val merchantVpa = Regex(
        "(?i)paytmqr|bharatpe|\\bq\\d{6,}@|gpay-\\d+@|@okbiz|@ptys|@yespay|mab\\.|\\.rzp@|@razorpay|@cashfree|@payu|@hdfcbankmerchant" +
            "|@pineapp|@icicimerchant|@axisbankmerchant|merchant|\\bp2m\\b|upi/p2m",
    )
    /** Markers of a payment to a person. */
    private val personMarkers = Regex("(?i)\\bp2p\\b|\\bp2a\\b|upi/p2a|upi/p2p")
    private val personalVpa = Regex("(?i)[a-z][a-z0-9.\\-_]{1,}@(ok(axis|hdfcbank|sbi|icici)|ybl|ibl|axl|upi|apl|paytm|kotak|icici|sbi|hdfcbank|axisbank|yesbank|idfcbank|fbl|jupiteraxis|slc|waaxis|superyes|naviaxis|freecharge)\\b")
    private val phoneVpa = Regex("(?i)\\b[6-9]\\d{9}(@|\\b)")
    private val p2pChannels = Regex("(?i)\\bimps\\b|\\bneft\\b|\\brtgs\\b")
    private val businessWord = Regex(
        "(?i)\\b(ltd|limited|pvt|private|llp|inc|corp|company|co|india|services|solutions|technologies|tech|labs|ventures|industries" +
            "|enterprises|traders|trading|store|stores|mart|shop|centre|center|agency|agencies|associates|group|hub|point|world|house" +
            "|global|international|retail|systems|digital|online|payments?|pay|bank|finance|motors|electronics|pharma|medical|hospital)\\b",
    )

    /**
     * Guesses the category of a transaction.
     *
     * @param merchant the normalised merchant (see [MerchantNormalizer]), if any.
     * @param body the message text.
     * @param channel the payment channel when known (ATM withdrawals become `cash`).
     */
    fun guess(merchant: String?, body: String, type: TxnType, channel: TxnChannel? = null): String {
        val m = merchant.orEmpty()
        val text = listOf(m, body).joinToString(" ")
        val lower = text.lowercase()
        return when (type) {
            TxnType.TRANSFER -> "transfer"
            TxnType.INCOME -> when {
                Regex("salary|\\bsal\\b|payroll|wages|stipend").containsMatchIn(lower) -> "salary"
                Regex("refund|reversal|reversed|returned").containsMatchIn(lower) -> "refund"
                Regex("cashback|reward|cash back").containsMatchIn(lower) -> "cashback"
                Regex("\\binterest\\b|\\bint\\.? (?:pd|paid|cr)|dividend").containsMatchIn(lower) -> "interest"
                Regex("payment.*(?:received|credited).*credit card|credit card.*payment.*received").containsMatchIn(lower) -> "transfer"
                else -> "other_income"
            }
            TxnType.EXPENSE -> guessExpense(m, body, channel)
        }
    }

    private fun guessExpense(merchant: String, body: String, channel: TxnChannel?): String {
        if (channel == TxnChannel.ATM || atm.containsMatchIn(merchant) || atm.containsMatchIn(body)) return "cash"
        if (instamart.containsMatchIn(merchant) || instamart.containsMatchIn(body)) return "groceries"
        if (merchant.isNotBlank()) {
            rules.firstOrNull { it.brands.containsMatchIn(merchant) }?.let { return it.category }
            rules.firstOrNull { it.generic?.containsMatchIn(merchant) == true }?.let { return it.category }
            if (looksLikePerson(merchant, body)) return "people"
        }
        rules.firstOrNull { it.brands.containsMatchIn(body) }?.let { return it.category }
        bodyPhrases.firstOrNull { it.second.containsMatchIn(body) }?.let { return it.first }
        if (merchant.isNotBlank()) {
            if (merchantVpa.containsMatchIn(body)) return "shopping"
            if (personMarkers.containsMatchIn(body)) return "people"
            return "shopping"
        }
        if (personMarkers.containsMatchIn(body) || personalVpa.containsMatchIn(body) || p2pChannels.containsMatchIn(body)) return "people"
        if (channel == TxnChannel.CARD || channel == TxnChannel.UPI) return "shopping"
        return "other"
    }

    /**
     * A payment to an individual: a masked phone-number payee ("UPI XXXXXX3210"), a P2P marker, or a
     * short name made only of letters that contains no business words, paid to a personal UPI handle
     * or over IMPS/NEFT.
     */
    fun looksLikePerson(merchant: String, body: String): Boolean {
        val m = merchant.trim()
        if (m.startsWith("UPI X", ignoreCase = true) || phoneVpa.containsMatchIn(m)) return true
        if (merchantVpa.containsMatchIn(body)) return false
        val words = m.split(Regex("\\s+")).filter { it.isNotBlank() }
        val nameLike = words.size in 1..4 &&
            words.all { w -> w.length in 1..15 && w.all { it.isLetter() || it == '.' } } &&
            !businessWord.containsMatchIn(m)
        if (!nameLike) return false
        if (personMarkers.containsMatchIn(body)) return true
        // Two or more words that look like "Rahul Kumar" are very likely a person.
        if (words.size >= 2) return true
        return personalVpa.containsMatchIn(body) || p2pChannels.containsMatchIn(body)
    }
}
