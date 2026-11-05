package gr.wsol.billablecalls.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Out of hours: a call that starts outside the working hours (when the option is on). */
object Overtime {
    fun isOut(call: Call, s: Settings, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        s.overtimeOn && !Hours.allowed(s.workHours, LocalDateTime.ofInstant(Instant.ofEpochMilli(call.start), zone))
}

/** Support contracts: the hours of a month included in a company's package. */
object Packages {
    fun month(ms: Long, zone: ZoneId = ZoneId.systemDefault()): YearMonth = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone))

    /** Billable seconds of [companyId] in the month of [ms]. */
    fun used(d: Data, companyId: String, ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val m = month(ms, zone)
        return d.calls.filter { it.companyId == companyId && !it.personal && month(it.start, zone) == m }.sumOf { Billing.seconds(it, d) }
    }

    data class State(val includedSec: Int, val usedSec: Int) {
        val leftSec get() = (includedSec - usedSec).coerceAtLeast(0)
        val overSec get() = (usedSec - includedSec).coerceAtLeast(0)
    }

    fun state(d: Data, c: Company, ms: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): State? {
        if (!d.settings.packagesOn) return null
        val inc = c.packageMin?.takeIf { it > 0 } ?: return null
        return State(inc * 60, used(d, c.id, ms, zone))
    }

    /** The companies whose package was just exceeded this month (not alerted yet), and the data with them marked. */
    fun newlyOver(d: Data, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<Data, List<Company>> {
        if (!d.settings.packagesOn) return d to emptyList()
        val m = month(now, zone).toString()
        val over = d.companies.filter { c -> d.packageAlerted[c.id] != m && (state(d, c, now, zone)?.overSec ?: 0) > 0 }
        if (over.isEmpty()) return d to emptyList()
        return d.copy(packageAlerted = d.packageAlerted + over.associate { it.id to m }) to over
    }
}

/** Greeklish: Greek and Latin spelling to one skeleton, so «Papadopoulos» and «Παπαδόπουλος» meet. */
object Greeklish {
    private val gr = mapOf(
        'Α' to "a", 'Β' to "v", 'Γ' to "g", 'Δ' to "d", 'Ε' to "e", 'Ζ' to "z", 'Η' to "i", 'Θ' to "th", 'Ι' to "i", 'Κ' to "k",
        'Λ' to "l", 'Μ' to "m", 'Ν' to "n", 'Ξ' to "x", 'Ο' to "o", 'Π' to "p", 'Ρ' to "r", 'Σ' to "s", 'Τ' to "t", 'Υ' to "y",
        'Φ' to "f", 'Χ' to "ch", 'Ψ' to "ps", 'Ω' to "o",
    )

    /** From a [Matcher.norm] text (upper case, no accents) to a Latin skeleton. */
    fun skeleton(normed: String): String {
        var s = normed.map { gr[it] ?: it.lowercaseChar().toString() }.joinToString("")
        // spellings that sound the same
        for ((a, b) in listOf("oy" to "u", "ay" to "av", "ey" to "ev", "mp" to "b", "nt" to "d", "gk" to "g", "gg" to "g", "ou" to "u", "ph" to "f", "th" to "8", "ch" to "h", "kh" to "h",
            "ck" to "k", "c" to "k", "q" to "k", "w" to "o", "ei" to "i", "oi" to "i", "y" to "i", "ai" to "e", "b" to "v", "x" to "ks")) s = s.replace(a, b)
        // double letters count once
        return s.replace(Regex("(.)\\1+"), "$1")
    }

    /** Similarity 0..1 (1 - edit distance / longer length). */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
    }
}
