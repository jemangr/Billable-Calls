package gr.wsol.billablecalls.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What goes into an export: the calls of a period, of every company or one. */
data class Period(val from: LocalDate, val to: LocalDate) {
    fun contains(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return !d.isBefore(from) && !d.isAfter(to)
    }
    val label: String get() = if (from == to) from.format(DAY) else "${from.format(DAY)} – ${to.format(DAY)}"

    companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        fun today(z: ZoneId = ZoneId.systemDefault()) = LocalDate.now(z).let { Period(it, it) }
        fun week(z: ZoneId = ZoneId.systemDefault()) = LocalDate.now(z).let { Period(it.minusDays((it.dayOfWeek.value - 1).toLong()), it) }
        fun month(z: ZoneId = ZoneId.systemDefault()) = LocalDate.now(z).let { Period(it.withDayOfMonth(1), it) }
        fun lastMonth(z: ZoneId = ZoneId.systemDefault()) = LocalDate.now(z).withDayOfMonth(1).minusMonths(1).let { Period(it, it.plusMonths(1).minusDays(1)) }
        val ALL = Period(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))
    }
}

object Export {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun direction(d: Direction) = when (d) {
        Direction.IN -> t("Εισερχόμενη", "Incoming")
        Direction.OUT -> t("Εξερχόμενη", "Outgoing")
        Direction.MISSED -> t("Αναπάντητη", "Missed")
        Direction.OUT_NO_ANSWER -> t("Εξερχόμενη χωρίς απάντηση", "Outgoing, no answer")
        Direction.REJECTED -> t("Απορρίφθηκε", "Declined")
    }

    /** The calls of the export: business only (or all with [all]), of one company if given. */
    fun calls(d: Data, p: Period, companyId: String?, all: Boolean = false, zone: ZoneId = ZoneId.systemDefault()) =
        d.calls.filter { c -> p.contains(c.start, zone) && (all || c.business) && (companyId == null || c.companyId == companyId) }.sortedBy { it.start }

    fun header() = listOf(
        t("Εταιρεία", "Company"), t("Επαφή", "Contact"), t("Αριθμός", "Number"), t("Κατεύθυνση", "Direction"), "SIM",
        t("Ημερομηνία", "Date"), t("Έναρξη", "Start"), t("Λήξη", "End"), t("Διάρκεια (λεπτά)", "Duration (min)"),
        t("Χρεώσιμο (λεπτά)", "Billable (min)"), t("Κατηγορία", "Category"), t("Σημείωση", "Note"),
    )

    fun rows(d: Data, calls: List<Call>, sims: Map<String, String> = emptyMap(), zone: ZoneId = ZoneId.systemDefault()): List<List<Any?>> =
        listOf(header()) + calls.map { c ->
            val s = Instant.ofEpochMilli(c.start).atZone(zone); val e = Instant.ofEpochMilli(c.end).atZone(zone)
            listOf(
                d.company(c.companyId)?.name ?: if (c.personal) t("Προσωπική", "Personal") else "",
                c.contact.orEmpty(), c.number, direction(c.direction), c.sim?.let { sims[it] ?: it }.orEmpty(),
                s.format(Period.DAY), s.format(TIME), e.format(TIME), round2(c.durationSec / 60.0), round2(Billing.seconds(c, d) / 60.0),
                c.category.orEmpty(), c.note,
            )
        }

    data class Total(val company: Company?, val calls: Int, val talkSec: Int, val billableSec: Int)

    fun totals(d: Data, calls: List<Call>): List<Total> = calls.filter { it.business }.groupBy { it.companyId }.map { (id, cs) ->
        Total(d.company(id), cs.size, cs.sumOf { if (it.answered) it.durationSec else 0 }, cs.sumOf { Billing.seconds(it, d) })
    }.sortedByDescending { it.billableSec }

    fun totalRows(d: Data, calls: List<Call>): List<List<Any?>> {
        val ts = totals(d, calls)
        return listOf(listOf(t("Εταιρεία", "Company"), t("Κλήσεις", "Calls"), t("Ομιλία (λεπτά)", "Talk (min)"), t("Χρεώσιμο (λεπτά)", "Billable (min)"), t("Χρεώσιμο (ώρες)", "Billable (h)"))) +
            ts.map { listOf(it.company?.name.orEmpty(), it.calls, round2(it.talkSec / 60.0), round2(it.billableSec / 60.0), round2(it.billableSec / 3600.0)) } +
            listOf(listOf(t("Σύνολο", "Total"), ts.sumOf { it.calls }, round2(ts.sumOf { it.talkSec } / 60.0), round2(ts.sumOf { it.billableSec } / 60.0), round2(ts.sumOf { it.billableSec } / 3600.0)))
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    fun fileName(p: Period, company: Company?, ext: String): String {
        val who = company?.name?.let { Matcher.norm(it).replace(' ', '-').take(30) } ?: t("ΟΛΕΣ", "ALL")
        val f = DateTimeFormatter.ofPattern("yyyyMMdd")
        return "BillableCalls-$who-${p.from.format(f)}-${p.to.format(f)}.$ext"
    }
}
