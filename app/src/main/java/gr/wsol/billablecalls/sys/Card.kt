package gr.wsol.billablecalls.sys

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import gr.wsol.billablecalls.CardActivity
import gr.wsol.billablecalls.R
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.CardStyle
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t

/**
 * The card at the end of a call. As a window over the screen (with «display over other apps»), or
 * as a notification with buttons: confirm the company, personal, or a note written right in it.
 * Tapping the notification opens the full card.
 */
object Card {
    const val NID = 5
    const val ACTION_CONFIRM = "gr.wsol.billablecalls.CARD_CONFIRM"
    const val ACTION_PERSONAL = "gr.wsol.billablecalls.CARD_PERSONAL"
    const val ACTION_NOTE = "gr.wsol.billablecalls.CARD_NOTE"
    const val KEY_NOTE = "note"

    fun canOverlay(context: Context) = Settings.canDrawOverlays(context)

    fun nid(callId: Long) = 20_000 + (callId % 10_000).toInt()

    fun intent(context: Context, ids: LongArray) = Intent(context, CardActivity::class.java).putExtra("ids", ids)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun show(context: Context, calls: List<Call>) {
        if (calls.isEmpty()) return
        if (Store.settings.cardStyle == CardStyle.OVERLAY && canOverlay(context) &&
            runCatching { context.startActivity(intent(context, calls.map { it.id }.toLongArray())) }.isSuccess) return
        calls.forEach { notify(context, it) }
    }

    /** One notification per call, so its buttons act on that call. */
    fun notify(context: Context, call: Call) {
        val d = Store.data
        val company = d.company(call.companyId)
        val title = call.label + (company?.name?.let { " · $it" } ?: "")
        val topic = Ops.withDefaultTopic(call, d.settings).category
        val text = if (company != null) t("Κλήση ${Billing.hms(call.durationSec)} · ${topic ?: "χωρίς θέμα"}. Εταιρική;", "Call ${Billing.hms(call.durationSec)} · ${topic ?: "no topic"}. Business?")
            else t("Κλήση ${Billing.hms(call.durationSec)}. Πάτα για εταιρεία, θέμα και σημείωση.", "Call ${Billing.hms(call.durationSec)}. Tap for company, topic and note.")
        val id = nid(call.id)
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(context, id * 4 + code,
            Intent(context, CardReceiver::class.java).setAction(a).putExtra("id", call.id), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, id, intent(context, longArrayOf(call.id)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, Notify.CARD).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS)
        if (company != null) b.addAction(0, "✓ " + company.name.take(18), action(ACTION_CONFIRM, 1))
        b.addAction(0, t("Προσωπική", "Personal"), action(ACTION_PERSONAL, 2))
        val input = RemoteInput.Builder(KEY_NOTE).setLabel(t("Τι συζητήθηκε;", "What was discussed?")).build()
        b.addAction(NotificationCompat.Action.Builder(0, t("✎ Σημείωση", "✎ Note"), action(ACTION_NOTE, 3)).addRemoteInput(input).setAllowGeneratedReplies(false).build())
        runCatching { Notify.post(context, id, b.build()) }.onFailure { Report.note("card", it.toString()) }
    }

    fun overlaySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The buttons of the after-call notification. */
class CardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L).takeIf { it >= 0 } ?: return
        Store.init(context)
        val call = Store.data.calls.firstOrNull { it.id == id } ?: return
        val s = Store.settings
        val topic = Ops.withDefaultTopic(call, s).category
        when (intent.action) {
            Card.ACTION_CONFIRM -> Store.update { Ops.review(it, id, true, call.companyId, true, topic, call.billable, null) }
            Card.ACTION_PERSONAL -> Store.update { Ops.review(it, id, false, null, true, call.category, call.billable, null) }
            Card.ACTION_NOTE -> {
                val note = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Card.KEY_NOTE)?.toString().orEmpty()
                // a note on a call with a company: also confirms it as business; without one, only the note
                Store.update { d -> if (call.companyId != null) Ops.review(d, id, true, call.companyId, true, topic, call.billable, note) else Ops.edit(d, id) { it.copy(note = note.trim()) } }
            }
        }
        Notify.cancel(context, Card.nid(id))
        Alarms.schedule(context)
        PackageAlerts.check(context)
        Webhook.flushLater(context)
    }
}
