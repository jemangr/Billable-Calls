package gr.wsol.billablecalls.core

import kotlin.math.ceil

/** Billable time: calls longer than the minimum, rounded up to the block (per company if set). */
object Billing {
    fun minSec(c: Company?, s: Settings) = c?.minSec ?: s.minSec
    fun roundMin(c: Company?, s: Settings) = c?.roundMin ?: s.roundMin

    /** Does the call count as billable? Manual yes/no wins; otherwise answered, business and long enough. */
    fun counts(call: Call, c: Company?, s: Settings): Boolean {
        if (!call.answered || !call.business) return false
        call.billable?.let { return it }
        // by topic: only the billable topics count (a call without a topic doesn't)
        // (a call without a topic counts as the default topic, e.g. calls from before the topics)
        val topic = call.category ?: s.defaultCategory.takeIf { it.isNotBlank() }
        if (s.billByCategory && (topic == null || topic !in s.billableCategories)) return false
        return call.durationSec > minSec(c, s)
    }

    /** Billable seconds of the call (0 if it doesn't count). */
    fun seconds(call: Call, c: Company?, s: Settings): Int {
        if (!counts(call, c, s)) return 0
        return factor(call, rounded(call.durationSec, roundMin(c, s)), s)
    }

    /** Out of hours or on site (when on): × the bigger of the two factors. */
    fun factor(call: Call, sec: Int, s: Settings): Int {
        val f = maxOf(if (Overtime.isOut(call, s)) s.overtimeFactor else 1.0, if (s.onsiteOn && call.onsite) s.onsiteFactor else 1.0)
        return if (f == 1.0) sec else Math.round(sec * f).toInt()
    }

    fun rounded(sec: Int, roundMin: Int): Int {
        if (roundMin <= 0) return sec
        val block = roundMin * 60
        return (ceil(sec / block.toDouble()) * block).toInt().coerceAtLeast(block)
    }

    /**
     * With the joined calls: a call joined to an earlier one bills 0, and the first one bills the time
     * of all of them together, rounded once (two 4′ calls are one 15′ block, not two).
     */
    fun seconds(call: Call, d: Data): Int {
        if (call.mergedInto != null && d.calls.any { it.id == call.mergedInto }) return 0
        val joined = d.calls.filter { it.mergedInto == call.id }
        if (joined.isEmpty()) return seconds(call, d.company(call.companyId), d.settings)
        val total = call.copy(durationSec = call.durationSec + joined.sumOf { if (it.answered) it.durationSec else 0 })
        return seconds(total, d.company(call.companyId), d.settings)
    }

    /** The earlier call of the same number this one may be joined to (ended within the window), if any. */
    fun mergeCandidate(call: Call, d: Data): Call? {
        if (!d.settings.mergeOn || !call.answered) return null
        val win = d.settings.mergeMin * 60_000L
        return d.calls.filter { it.id != call.id && it.answered && !it.personal && it.mergedInto == null && it.start < call.start &&
            (it.key == call.key || call.companyId != null && it.companyId == call.companyId) && call.start - it.end in 0..win }
            .maxByOrNull { it.start }
    }

    /** «1:05:00», «12:30», «0:45». */
    fun hms(sec: Int): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** «2 ώρ. 15 λ.», «45 λ.» */
    fun nice(sec: Int): String {
        val h = sec / 3600; val m = ((sec % 3600) + 59) / 60
        return when {
            h > 0 && m > 0 -> t("$h ώρ. $m λ.", "${h}h ${m}m")
            h > 0 -> t("$h ώρ.", "${h}h")
            else -> t("$m λ.", "${m}m")
        }
    }
}
