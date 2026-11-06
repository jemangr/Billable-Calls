package gr.wsol.billablecalls.sys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.CardMode
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Phone
import gr.wsol.billablecalls.core.Reminders
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store

/** A call as the phone's call log has it. */
data class RawCall(val id: Long, val number: String, val type: Int, val date: Long, val duration: Int, val account: String?)

/** A contact: its name and its «Company» field. */
data class Contact(val name: String?, val org: String?)

/** Reads the call log and the contacts; the rest (matching, reminders, the card) is in [CallSync.merge]. */
interface CallSource {
    fun calls(afterId: Long): List<RawCall>
    fun contact(number: String): Contact?
}

class AndroidCallSource(private val context: Context) : CallSource {
    override fun calls(afterId: Long): List<RawCall> {
        if (!Perms.has(context, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = mutableListOf<RawCall>()
        val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID)
        context.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, "${CallLog.Calls._ID} > ?", arrayOf(afterId.toString()), "${CallLog.Calls._ID} ASC")?.use { c ->
            while (c.moveToNext()) out += RawCall(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2), c.getLong(3), c.getInt(4), c.getString(5))
        }
        return out
    }

    private val cache = HashMap<String, Contact?>()

    override fun contact(number: String): Contact? = cache.getOrPut(number) {
        if (number.isBlank() || !Perms.has(context, Manifest.permission.READ_CONTACTS)) return@getOrPut null
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val (id, name) = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
            } ?: return@runCatching null
            val org = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(id.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            Contact(name, org?.takeIf { it.isNotBlank() })
        }.getOrNull()
    }
}

object CallSync {
    fun direction(type: Int, duration: Int): Direction? = when (type) {
        CallLog.Calls.INCOMING_TYPE -> if (duration > 0) Direction.IN else Direction.MISSED
        CallLog.Calls.OUTGOING_TYPE -> if (duration > 0) Direction.OUT else Direction.OUT_NO_ANSWER
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> Direction.MISSED
        CallLog.Calls.REJECTED_TYPE -> Direction.REJECTED
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> null
        CallLog.Calls.BLOCKED_TYPE -> null
        else -> null
    }

    /** The result of a sync: the new calls that want the after-call card. */
    data class Result(val data: Data, val added: List<Call>, val forCard: List<Call>)

    /**
     * New calls from the log into the data: their company, the reminders of missed calls, the end
     * of reminders when you talked. Calls before [Settings.since] are history (no card, no reminder).
     */
    fun merge(d0: Data, raw: List<RawCall>, source: CallSource, now: Long): Result {
        var d = d0
        val s = d.settings
        val known = d.calls.map { it.id }.toHashSet()
        // after a restore on another phone the ids differ: the same number at the same moment is the same call
        val seen = d.calls.map { it.key + "@" + it.start / 1000 }.toHashSet()
        val idx = Matcher.index(d.companies)
        val added = mutableListOf<Call>()
        var lastId = s.lastCallId
        for (r in raw) {
            lastId = maxOf(lastId, r.id)
            if (r.id in known && d.calls.any { it.id == r.id && it.start == r.date }) continue
            if (Phone.key(r.number) + "@" + r.date / 1000 in seen) continue
            if (s.sims.isNotEmpty() && r.account != null && r.account !in s.sims) continue
            val dir = direction(r.type, r.duration) ?: continue
            val contact = source.contact(r.number)
            val cid = if (r.id in known) (d.calls.maxOfOrNull { it.id } ?: 0L) + 1_000_000_000L + added.size else r.id
            var call = Ops.withDefaultTopic(Ops.classify(Call(cid, Phone.key(r.number), r.number, contact?.name, contact?.org, r.account, dir, r.date, r.duration, reviewed = r.date < s.since), d, idx), s)
            // a note written on the «before you answer» card during this call
            Ops.takeDraft(d, call).let { (c2, d2) -> call = c2; d = d2 }
            added += call
        }
        d = d.copy(calls = d.calls + added, settings = d.settings.copy(lastCallId = lastId))
        var reminders = d
        for (c in added.filter { it.start >= s.since && Reminders.wanted(it, d) }) reminders = reminders.copy(reminders = Reminders.add(c, reminders, now))
        d = reminders.copy(reminders = Reminders.sweep(reminders, now))
        val forCard = added.filter { c ->
            c.start >= s.since && c.answered && now - c.end < 30 * 60_000L && when (s.cardMode) {
                CardMode.ALL -> !c.personal
                CardMode.UNMATCHED -> c.companyId == null && !c.personal
                CardMode.NEVER -> false
            }
        }
        return Result(d, added, forCard)
    }

    @Volatile private var running = false

    /** Reads the new calls now (from the job, the service or the app), then the card and the alarms. */
    fun run(context: Context, showCard: Boolean = true) {
        synchronized(this) { if (running) return; running = true }
        try {
            Store.init(context)
            val src = AndroidCallSource(context)
            val raw = src.calls(Store.settings.lastCallId)
            if (raw.isEmpty()) return
            var res: Result? = null
            Store.update { d -> merge(d, raw, src, System.currentTimeMillis()).also { res = it }.data }
            Alarms.schedule(context)
            PackageAlerts.check(context)
            val forCard = res?.forCard.orEmpty()
            if (showCard && forCard.isNotEmpty()) Card.show(context, forCard)
        } catch (e: Exception) {
            Report.note("sync", e.toString())
        } finally { running = false }
    }

    /**
     * «Read the history again» (Settings): the whole call log once more. Calls already here stay as they
     * are; missing ones are added (as history: no card, no reminder).
     */
    fun reread(context: Context): Int {
        Store.init(context)
        val before = Store.data.calls.size
        Store.settings { it.copy(lastCallId = 0) }
        run(context, showCard = false)
        Store.update { Ops.rematch(it) }
        return Store.data.calls.size - before
    }

    /** «Only from now on»: the calls already in the log are skipped. */
    fun skipHistory(context: Context) {
        Store.init(context)
        val last = AndroidCallSource(context).calls(Store.settings.lastCallId).maxOfOrNull { it.id } ?: Store.settings.lastCallId
        Store.settings { it.copy(since = System.currentTimeMillis(), lastCallId = last) }
    }

    /** The first start: everything already in the log, as history. */
    fun importHistory(context: Context): Int {
        Store.init(context)
        val before = Store.data.calls.size
        Store.settings { it.copy(since = System.currentTimeMillis()) }
        run(context, showCard = false)
        return Store.data.calls.size - before
    }
}

/** The phone's SIMs (phone accounts), for the «which SIM counts» setting. */
object Sims {
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<Pair<String, String>> = runCatching {
        if (!Perms.has(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val tm = context.getSystemService(TelecomManager::class.java)
        tm.callCapablePhoneAccounts.map { h -> h.id to (tm.getPhoneAccount(h)?.label?.toString() ?: h.id) }
    }.getOrDefault(emptyList())
}

object Perms {
    fun has(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val CORE = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
}
