package com.amitjha.kosh.repo

import com.amitjha.kosh.data.InvestmentEntity
import com.amitjha.kosh.data.InvestmentSnapshotEntity
import com.amitjha.kosh.data.KoshDatabase
import com.amitjha.kosh.domain.InvestmentSpec
import com.amitjha.kosh.domain.SpecCodec
import com.amitjha.kosh.finance.NavQuote
import com.amitjha.kosh.finance.NavSource
import com.amitjha.kosh.finance.PortfolioValuer
import com.amitjha.kosh.model.InvestmentType
import com.amitjha.kosh.model.Paise
import com.amitjha.kosh.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class InvestmentItem(
    val entity: InvestmentEntity,
    val spec: InvestmentSpec,
    /** Simple annualised return since the start, when meaningful. */
    val annualisedReturnPct: Double?,
) {
    val id: Long get() = entity.id
    val type: InvestmentType get() = entity.type
    val value: Paise get() = entity.currentValue
    val invested: Paise get() = entity.investedAmount
    val gain: Paise get() = value - invested
    val gainPct: Double? get() = if (invested > 0) gain * 100.0 / invested else null
    val maturityDate: LocalDate? get() = entity.maturityDate?.let(LocalDate::ofEpochDay)
    fun daysToMaturity(today: LocalDate = Time.today()): Long? = maturityDate?.let { ChronoUnit.DAYS.between(today, it) }
}

data class PortfolioTotals(val value: Paise, val invested: Paise, val byType: Map<InvestmentType, Paise>) {
    val gain: Paise get() = value - invested
}

data class RevalueResult(val updated: Int, val navsFetched: Int, val contributionsApplied: Int)

class InvestmentRepository(private val db: KoshDatabase, private val navSource: NavSource) {

    private val dao get() = db.investments()

    @Volatile private var schemeCache: Pair<Long, List<NavQuote>>? = null

    fun observeItems(): Flow<List<InvestmentItem>> = dao.observeActive().map { list -> list.map(::toItem) }

    fun observeTotals(): Flow<PortfolioTotals> = dao.observeActive().map { list ->
        PortfolioTotals(
            value = list.sumOf { it.currentValue },
            invested = list.sumOf { it.investedAmount },
            byType = list.groupBy { it.type }.mapValues { (_, v) -> v.sumOf { it.currentValue } },
        )
    }

    fun observeHistory(investmentId: Long): Flow<List<InvestmentSnapshotEntity>> = dao.observeHistory(investmentId)

    suspend fun items(): List<InvestmentItem> = dao.active().map(::toItem)

    suspend fun byId(id: Long): InvestmentItem? = dao.byId(id)?.let(::toItem)

    suspend fun addFd(name: String, bank: String, spec: InvestmentSpec.Fd): Long = insert(InvestmentType.FD, name, bank, spec)

    suspend fun addEpf(name: String, spec: InvestmentSpec.Epf, institution: String = "EPFO"): Long =
        insert(InvestmentType.EPF, name, institution, spec)

    /**
     * Adds an NPS account; monthly contributions are applied from today onwards. When the amount invested is
     * left blank it starts at today's value, so later contributions add to it rather than replace it.
     */
    suspend fun addNps(name: String, spec: InvestmentSpec.Nps, institution: String = "NPS"): Long =
        insert(InvestmentType.NPS, name, institution, seedInvested(spec).copy(contributionsAppliedTill = spec.contributionsAppliedTill ?: Time.today()))

    /** Adds a simple NPS (value today + monthly contribution at an expected return). */
    suspend fun addNpsSimple(name: String, spec: com.amitjha.kosh.domain.NpsSimple, institution: String = "NPS"): Long =
        insert(InvestmentType.NPS, name, institution, spec)

    suspend fun update(id: Long, name: String, institution: String, spec: InvestmentSpec) {
        val existing = dao.byId(id) ?: return
        val today = Time.today()
        val (value, invested) = valuation(spec, name, institution, today)
        dao.update(
            existing.copy(
                name = name,
                institution = institution,
                specJson = SpecCodec.encode(spec),
                investedAmount = invested,
                currentValue = value,
                lastUpdated = System.currentTimeMillis(),
                maturityDate = (spec as? InvestmentSpec.Fd)?.maturityDate?.toEpochDay(),
            ),
        )
        snapshot(id, today, value)
    }

    suspend fun archive(id: Long) {
        dao.byId(id)?.let { dao.update(it.copy(archived = true)) }
    }

    suspend fun delete(id: Long) {
        dao.byId(id)?.let { dao.delete(it) }
    }

    /**
     * Daily auto-update: values FDs and EPF for today, fetches NPS NAVs, applies NPS monthly contributions that
     * fell due since the last run, and records a history snapshot for each investment.
     */
    suspend fun revalueAll(today: LocalDate = Time.today(), fetchNavs: Boolean = true): RevalueResult {
        var navsFetched = 0
        var contributions = 0
        val all = dao.active()
        val codes = all.mapNotNull { e -> (runCatching { decode(e) }.getOrNull() as? InvestmentSpec.Nps)?.holdings?.map { it.schemeCode } }
            .flatten().toSet()
        val navs: Map<String, NavQuote> = if (fetchNavs && codes.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                codes.mapNotNull { runCatching { navSource.latestNav(it) }.getOrNull() }
                    .filter { it.nav.isFinite() && it.nav > 0 }
                    .associateBy { it.schemeCode }
            }
        } else {
            emptyMap()
        }
        navsFetched = navs.size

        var updated = 0
        for (e in all) {
            // One bad entry must not stop the others from being revalued.
            val ok = runCatching {
                var spec = decode(e)
                if (spec is InvestmentSpec.Nps) {
                    spec = seedInvested(spec)
                    val navMap = navs.mapValues { it.value.nav }
                    var account = spec.toFinance().withQuotes(navs, today)
                    val from = spec.contributionsAppliedTill ?: today
                    var invested = spec.investedAmount
                    if (spec.monthlyContribution > 0) {
                        for (event in account.contributionsDue(from, today)) {
                            account = account.applyContribution(event.amount, navMap, event.date).account
                            invested += event.amount
                            contributions++
                        }
                    }
                    spec = spec.copy(holdings = account.holdings, investedAmount = invested, contributionsAppliedTill = today)
                }
                val (value, invested) = valuation(spec, e.name, e.institution, today)
                dao.update(
                    e.copy(
                        specJson = SpecCodec.encode(spec),
                        currentValue = value,
                        investedAmount = invested,
                        lastUpdated = System.currentTimeMillis(),
                    ),
                )
                snapshot(e.id, today, value)
            }.isSuccess
            if (ok) updated++
        }
        return RevalueResult(updated, navsFetched, contributions)
    }

    /** Searches NPS schemes by name or code (the full list is cached for 12 hours). */
    suspend fun searchNpsSchemes(query: String): List<NavQuote> {
        val now = System.currentTimeMillis()
        val cached = schemeCache
        val list = if (cached != null && now - cached.first < 12 * 3_600_000L && cached.second.isNotEmpty()) {
            cached.second
        } else {
            withContext(Dispatchers.IO) { navSource.allSchemes() }.also { if (it.isNotEmpty()) schemeCache = now to it }
        }
        val q = query.trim()
        if (q.isEmpty()) return list.take(50)
        val terms = q.lowercase().split(Regex("\\s+"))
        return list.filter { quote ->
            val hay = (quote.schemeName + " " + quote.schemeCode).lowercase()
            terms.all { it in hay }
        }.take(50)
    }

    suspend fun latestNav(schemeCode: String): NavQuote? = withContext(Dispatchers.IO) { navSource.latestNav(schemeCode) }

    private suspend fun insert(type: InvestmentType, name: String, institution: String, spec: InvestmentSpec): Long {
        val today = Time.today()
        val (value, invested) = valuation(spec, name, institution, today)
        val id = dao.insert(
            InvestmentEntity(
                type = type,
                name = name,
                institution = institution,
                specJson = SpecCodec.encode(spec),
                investedAmount = invested,
                currentValue = value,
                lastUpdated = System.currentTimeMillis(),
                maturityDate = (spec as? InvestmentSpec.Fd)?.maturityDate?.toEpochDay(),
            ),
        )
        snapshot(id, today, value)
        return id
    }

    private suspend fun snapshot(id: Long, day: LocalDate, value: Paise) {
        dao.upsertSnapshot(InvestmentSnapshotEntity(investmentId = id, epochDay = day.toEpochDay(), value = value))
    }

    private fun decode(e: InvestmentEntity): InvestmentSpec = SpecCodec.decode(e.specJson)

    private fun toItem(e: InvestmentEntity): InvestmentItem {
        val spec = decode(e)
        return InvestmentItem(e, spec, annualisedReturn(spec, e.currentValue, e.investedAmount, Time.today()))
    }

    /** NPS entered with a blank amount invested starts from today's value. */
    private fun seedInvested(spec: InvestmentSpec.Nps): InvestmentSpec.Nps =
        if (spec.investedAmount > 0) spec else spec.copy(investedAmount = spec.toFinance().value())

    companion object {
        /**
         * Yearly return to show for an investment. Value-over-cost growth is only meaningful for a cumulative FD;
         * other kinds use the rate they actually earn, because contributions or interest payouts distort it.
         */
        fun annualisedReturn(spec: InvestmentSpec, value: Paise, invested: Paise, today: LocalDate): Double? = when (spec) {
            is InvestmentSpec.Fd -> if (spec.payout == com.amitjha.kosh.finance.FdPayout.CUMULATIVE) {
                PortfolioValuer.annualised(value, invested, spec.startDate, today)
            } else {
                spec.annualRatePct
            }
            is InvestmentSpec.Epf -> spec.annualRatePct
            is com.amitjha.kosh.domain.NpsSimple -> spec.annualReturnPct
            is InvestmentSpec.Nps -> null
        }

        /** Current value and amount invested for a spec on [date]. */
        fun valuation(spec: InvestmentSpec, name: String, institution: String, date: LocalDate): Pair<Paise, Paise> = when (spec) {
            is InvestmentSpec.Fd -> {
                val fd = spec.toFinance(institution, name)
                fd.valueOn(date) to fd.principal
            }
            is InvestmentSpec.Epf -> {
                val p = spec.toFinance().projectBalance(date)
                p.totalIncludingAccrued to (spec.balanceAsOf + p.contributionsSinceAsOf)
            }
            is InvestmentSpec.Nps -> {
                val v = spec.toFinance().value()
                v to (if (spec.investedAmount > 0) spec.investedAmount else v)
            }
            is com.amitjha.kosh.domain.NpsSimple -> spec.valueOn(date)
        }
    }
}
