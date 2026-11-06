package gr.wsol.billablecalls.core

import java.util.UUID

/** Changes to the data, without Android (so the tests can check them). */
object Ops {
    fun newId() = UUID.randomUUID().toString().take(8)

    /** The company of a new call: the number's rule first, then the contact. */
    fun classify(call: Call, d: Data, idx: Map<String, Set<String>> = Matcher.index(d.companies)): Call {
        d.rule(call.key)?.let { r ->
            return if (r.personal) call.copy(companyId = null, personal = true, how = How.RULE)
            else call.copy(companyId = r.companyId, personal = false, how = How.RULE)
        }
        val m = Matcher.match(call.contact, call.org, d.companies, d.settings, idx)
        return call.copy(companyId = m.companyId, personal = false, how = m.how)
    }

    /** After the company list or the matching settings changed: the automatic matches again. */
    fun rematch(d: Data): Data {
        val idx = Matcher.index(d.companies)
        val ids = d.companies.map { it.id }.toSet()
        return d.copy(calls = d.calls.map { c ->
            when (c.how) {
                How.MANUAL -> if (c.companyId != null && c.companyId !in ids) c.copy(companyId = null, how = How.NONE) else c
                else -> classify(c, d, idx).let { if (!it.reviewed) withDefaultTopic(it, d.settings) else it }
            }
        })
    }

    /**
     * Puts calls in a company (or makes them personal with [companyId] null). With [forNumbers],
     * all calls of those numbers, and the next ones too (a number rule).
     */
    fun assign(d: Data, callIds: Set<Long>, companyId: String?, forNumbers: Boolean): Data {
        // the contact's every number: the numbers of these calls, and of any call with the same contact name
        val picked = d.calls.filter { it.id in callIds }
        val names = picked.mapNotNull { it.contact?.takeIf { n -> n.isNotBlank() } }.toSet()
        val keys = (picked.map { it.key } + if (forNumbers) d.calls.filter { it.contact in names }.map { it.key } else emptyList()).toSet()
        val personal = companyId == null
        val calls = d.calls.map { c ->
            if (c.id in callIds || (forNumbers && c.key in keys)) withDefaultTopic(c.copy(companyId = companyId, personal = personal, how = if (forNumbers) How.RULE else How.MANUAL), d.settings)
            else c
        }
        val rules = if (!forNumbers) d.rules else d.rules.filter { it.key !in keys } + keys.map { NumberRule(it, companyId, personal) }
        val reminders = d.reminders.map { r -> if (r.key in keys && !r.stopped && personal && forNumbers) Reminders.dismiss(r, System.currentTimeMillis()) else if (r.key in keys) r.copy(companyId = companyId) else r }
        return d.copy(calls = calls, rules = rules, reminders = reminders)
    }

    /** The note written on the «before you answer» card for [key] (empty text removes it). */
    fun setDraft(d: Data, key: String, text: String, shownAt: Long, now: Long): Data {
        val rest = d.drafts.filter { !(it.key == key && it.shownAt == shownAt) && now - it.at < 24 * 3_600_000L }
        return d.copy(drafts = if (text.isBlank()) rest else rest + Draft(key, text.trim(), shownAt, now))
    }

    /** The call [c] reached the log: a note written during it goes into its note (and the draft is used up). */
    fun takeDraft(d: Data, c: Call): Pair<Call, Data> {
        val dr = d.drafts.firstOrNull { it.key == c.key && it.shownAt >= c.start - 3 * 60_000L && it.shownAt <= c.end + 60_000L } ?: return c to d
        val note = listOf(c.note.trim(), dr.text).filter { it.isNotBlank() }.joinToString("\n")
        return c.copy(note = note) to d.copy(drafts = d.drafts - dr)
    }

    fun edit(d: Data, id: Long, change: (Call) -> Call) = d.copy(calls = d.calls.map { if (it.id == id) change(it) else it })

    /**
     * What the card (or its notification buttons) says about a call: business or personal, which
     * company (also for the next calls of the number with [forNumber]), topic, billable, note.
     */
    fun review(d0: Data, callId: Long, business: Boolean, companyId: String?, forNumber: Boolean, category: String?, billable: Boolean?, note: String?): Data =
        reviewed(d0, callId, business, companyId, forNumber, category, billable, note).let { d ->
            // the webhook (when on) sends the call after its card
            if (d.settings.webhookOn && d.settings.webhookUrl.isNotBlank() && business) d.copy(pendingHooks = (d.pendingHooks + callId).distinct()) else d
        }

    private fun reviewed(d0: Data, callId: Long, business: Boolean, companyId: String?, forNumber: Boolean, category: String?, billable: Boolean?, note: String?): Data {
        val call = d0.calls.firstOrNull { it.id == callId } ?: return d0
        var d = d0
        val target = if (business) companyId else null
        val changed = target != call.companyId || (!business) != call.personal
        val ruleDiffers = forNumber && d.rule(call.key).let { it == null || it.companyId != target || it.personal != !business }
        if ((changed || ruleDiffers) && (!business || target != null)) d = assign(d, setOf(call.id), target, forNumber)
        return edit(d, call.id) { it.copy(category = if (business) category else it.category, billable = billable, note = note?.trim() ?: it.note, reviewed = true) }
    }

    // ---- reminders you set, and «completed» ----
    fun addFollowUp(d: Data, f: FollowUp) = d.copy(followUps = d.followUps.filterNot { it.id == f.id } + f)
    fun followUpDone(d: Data, id: String, done: Boolean, now: Long = System.currentTimeMillis()) =
        d.copy(followUps = d.followUps.map { if (it.id == id) it.copy(doneAt = if (done) now else null) else it })
    fun removeFollowUp(d: Data, id: String) = d.copy(followUps = d.followUps.filterNot { it.id == id })
    fun snoozeFollowUp(d: Data, id: String, at: Long) = d.copy(followUps = d.followUps.map { if (it.id == id) it.copy(at = at, notified = false) else it })
    fun callDone(d: Data, id: Long, done: Boolean, now: Long = System.currentTimeMillis()) = edit(d, id) { it.copy(doneAt = if (done) now else null) }

    /** Joins [id] to [into] (billed as one), or undoes it with null. */
    fun merge(d: Data, id: Long, into: Long?) = edit(d, id) { it.copy(mergedInto = into) }

    /** The default topic for a new business call without one. */
    fun withDefaultTopic(c: Call, s: Settings): Call =
        if (c.business && c.category == null && s.defaultCategory.isNotBlank() && s.defaultCategory in s.categories) c.copy(category = s.defaultCategory) else c

    fun addCompany(d: Data, name: String, aliases: List<String> = emptyList()): Pair<Data, Company> {
        val key = Matcher.norm(name)
        d.companies.firstOrNull { Matcher.norm(it.name) == key }?.let { return d to it }
        val c = Company(newId(), name.trim(), aliases.map { it.trim() }.filter { it.isNotEmpty() })
        return rematch(d.copy(companies = d.companies + c)) to c
    }

    fun saveCompany(d: Data, c: Company) = rematch(d.copy(companies = d.companies.map { if (it.id == c.id) c else it }))

    /** Removes a company; its calls go to «no company» and its number rules are dropped. */
    fun removeCompany(d: Data, id: String): Data {
        val calls = d.calls.map { if (it.companyId == id) it.copy(companyId = null, how = How.NONE) else it }
        return rematch(d.copy(companies = d.companies.filter { it.id != id }, calls = calls, rules = d.rules.filter { it.companyId != id }))
    }

    /**
     * The customer list again (from a file, a link or Drive): the companies of the list are
     * replaced, keeping their ids and own settings; those added by hand stay. A company that left
     * the list but has calls stays as «by hand».
     */
    fun importList(d: Data, rows: List<Pair<String, List<String>>>): Data {
        val byKey = d.companies.associateBy { Matcher.norm(it.name) }
        val seen = HashSet<String>()
        val fromList = rows.mapNotNull { (name, aliases) ->
            val k = Matcher.norm(name); if (k.isBlank() || !seen.add(k)) return@mapNotNull null
            val old = byKey[k]
            (old ?: Company(newId(), name.trim())).copy(name = name.trim(), aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), source = "list")
        }
        val keep = d.companies.filter { c -> Matcher.norm(c.name) !in seen && (c.source != "list" || d.calls.any { it.companyId == c.id }) }.map { it.copy(source = "manual") }
        return rematch(d.copy(companies = (fromList + keep).sortedBy { Matcher.norm(it.name) }))
    }
}
