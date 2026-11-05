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
        if (s.billByCategory && (call.category == null || call.category !in s.billableCategories)) return false
        return call.durationSec > minSec(c, s)
    }

    /** Billable seconds of the call (0 if it doesn't count). */
    fun seconds(call: Call, c: Company?, s: Settings): Int {
        if (!counts(call, c, s)) return 0
        return rounded(call.durationSec, roundMin(c, s))
    }

    fun rounded(sec: Int, roundMin: Int): Int {
        if (roundMin <= 0) return sec
        val block = roundMin * 60
        return (ceil(sec / block.toDouble()) * block).toInt().coerceAtLeast(block)
    }

    fun seconds(call: Call, d: Data) = seconds(call, d.company(call.companyId), d.settings)

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
