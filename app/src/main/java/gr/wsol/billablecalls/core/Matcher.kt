package gr.wsol.billablecalls.core

/**
 * Finds the company of a call from the contact: its «Company» field, or its name (the company
 * before or after the person's name). Automatic only when it is sure: one company and only one.
 */
object Matcher {
    private val accents = mapOf(
        'Ά' to 'Α', 'Έ' to 'Ε', 'Ή' to 'Η', 'Ί' to 'Ι', 'Ϊ' to 'Ι', 'Ό' to 'Ο', 'Ύ' to 'Υ', 'Ϋ' to 'Υ', 'Ώ' to 'Ω',
        'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ϊ' to 'ι', 'ΐ' to 'ι', 'ό' to 'ο', 'ύ' to 'υ', 'ϋ' to 'υ', 'ΰ' to 'υ', 'ώ' to 'ω', 'ς' to 'σ',
    )

    /** Legal forms that don't count when comparing names (written without dots). */
    private val forms = setOf(
        "ΑΕ", "ΑΒΕΕ", "ΑΕΒΕ", "ΑΒΕ", "ΑΤΕ", "ΑΕΕ", "ΕΠΕ", "ΙΚΕ", "ΜΙΚΕ", "ΟΕ", "ΕΕ", "ΜΕΠΕ", "ΑΜΚΕ", "ΚΟΙΝΣΕΠ",
        "SA", "LTD", "IKE", "OE", "EE", "AE", "EPE", "PC", "INC", "LLC", "GMBH",
    )

    /** Upper case, no accents, no legal form, no punctuation: «Α.Β.Γ. Α.Ε.» and «αβγ» are the same. */
    fun norm(s: String): String {
        val up = s.map { accents[it] ?: it }.joinToString("").uppercase().map { accents[it] ?: it }.joinToString("")
        val words = up.split(Regex("[\\s,;:|/()\\[\\]\"'«»&+_-]+")).filter { it.isNotBlank() }.flatMap { w ->
            when {
                '.' !in w -> listOf(w)
                // initials such as «Α.Β.Γ.» or «Α.Ε.» become one word
                w.replace(".", "") in forms || w.matches(Regex("(\\p{L}\\.)+\\p{L}?")) -> listOf(w.replace(".", ""))
                else -> w.split('.').filter { it.isNotBlank() }
            }
        }
        return words.filter { it !in forms }.joinToString(" ")
    }

    data class Result(val companyId: String?, val how: How, val suggestions: List<String> = emptyList())

    /** The keys (normalized name and aliases) of every company. */
    fun index(companies: List<Company>, key: (String) -> String = ::norm): Map<String, Set<String>> {
        val m = HashMap<String, MutableSet<String>>()
        for (c in companies) for (n in listOf(c.name) + c.aliases) {
            val k = key(n); if (k.length >= 2) m.getOrPut(k) { mutableSetOf() } += c.id
        }
        return m
    }

    private val latin: (String) -> String = { Greeklish.skeleton(norm(it)) }

    fun match(contact: String?, org: String?, companies: List<Company>, s: Settings, idx: Map<String, Set<String>> = index(companies)): Result {
        if (companies.isEmpty()) return Result(null, How.NONE)
        fun tryWith(ix: Map<String, Set<String>>, key: (String) -> String): Result? {
            if (s.matchMode != MatchMode.NAME && !org.isNullOrBlank()) sure(key(org), ix, whole = true)?.let { return Result(it, How.COMPANY_FIELD) }
            if (s.matchMode != MatchMode.COMPANY_FIELD && !contact.isNullOrBlank()) fromName(contact, s, ix, key)?.let { return Result(it, How.NAME) }
            return null
        }
        tryWith(idx, ::norm)?.let { return it }
        // greeklish: the same, on the Latin skeleton of both sides
        if (s.greeklish) tryWith(index(companies, latin), latin)?.let { return it }
        return Result(null, How.NONE, suggest(listOfNotNull(org, contact), companies, idx))
    }

    /** The one company whose key equals [k], or the one with the longest key at the start/end of [k]. */
    private fun sure(k: String, idx: Map<String, Set<String>>, whole: Boolean, atStart: Boolean = true): String? {
        if (k.isBlank()) return null
        idx[k]?.let { return it.singleOrNull() }
        if (whole) return null
        val hits = idx.entries.filter { (key, _) -> key.length >= 3 && (if (atStart) k.startsWith("$key ") else k.endsWith(" $key")) }
        if (hits.isEmpty()) return null
        val best = hits.maxOf { it.key.length }
        val ids = hits.filter { it.key.length == best }.flatMap { it.value }.toSet()
        return ids.singleOrNull()
    }

    private fun fromName(name: String, s: Settings, idx: Map<String, Set<String>>, key: (String) -> String = ::norm): String? {
        val before = s.namePosition == NamePosition.BEFORE
        val seps = if (s.separator == Separator.ANY) Separator.entries.filter { it != Separator.ANY && it != Separator.SPACE } else listOf(s.separator)
        for (sep in seps) {
            if (sep == Separator.SPACE) continue
            val parts = splitBy(name, sep)
            if (parts.size >= 2) {
                val part = if (before) parts.first() else parts.last()
                sure(key(part), idx, whole = true)?.let { return it }
            }
        }
        // No separator (or a space): the company is the first (or last) words of the name.
        val k = key(name)
        sure(k, idx, whole = true)?.let { return it }
        return sure(k, idx, whole = false, atStart = before)
    }

    private fun splitBy(name: String, sep: Separator): List<String> = when (sep) {
        Separator.PAREN -> {
            val inside = Regex("\\(([^)]*)\\)").find(name)?.groupValues?.get(1)
            if (inside == null) emptyList() else { val outside = name.replace(Regex("\\([^)]*\\)"), " ").trim(); listOf(inside, outside).let { if (s0(name)) it else it.reversed() } }
        }
        Separator.DASH -> name.split(Regex("\\s+[-–—]\\s+|\\s*[–—]\\s*")).let { if (it.size >= 2) it else name.split("-") }.map { it.trim() }.filter { it.isNotEmpty() }
        else -> name.split(sep.chars).map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** «(ΑΒΓ) Νίκος»: the bracket comes first. */
    private fun s0(name: String) = name.trim().startsWith("(")

    /**
     * Companies for the «which company?» list: they share a word with the contact, or are written
     * almost the same (a typo, greeklish): only suggested, never matched by themselves.
     */
    fun suggest(texts: List<String>, companies: List<Company>, idx: Map<String, Set<String>>): List<String> {
        val words = texts.flatMap { norm(it).split(' ') }.filter { it.length >= 3 }.toSet()
        if (words.isEmpty()) return emptyList()
        val byWord = idx.entries.filter { (k, _) -> k.split(' ').any { it in words } }.flatMap { it.value }
        val sk = words.map { Greeklish.skeleton(it) }
        val close = companies.filter { c -> (listOf(c.name) + c.aliases).any { n -> Greeklish.skeleton(norm(n)).split(' ').any { w -> w.length >= 3 && sk.any { Greeklish.similarity(it, w) >= 0.8 } } } }.map { it.id }
        return (byWord + close).distinct().take(5)
    }
}

/** Phone numbers as keys: digits only, without the country code for Greek numbers. */
object Phone {
    fun key(number: String): String {
        var d = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) d = "00$d"
        if (d.startsWith("0030") && d.length >= 14) d = d.drop(4)
        else if (d.startsWith("30") && d.length == 12) d = d.drop(2)
        return d
    }
}
