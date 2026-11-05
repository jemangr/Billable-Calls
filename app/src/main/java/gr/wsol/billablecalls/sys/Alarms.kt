package gr.wsol.billablecalls.sys

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import gr.wsol.billablecalls.MainActivity
import gr.wsol.billablecalls.R
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Reminders
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notify {
    const val REMINDERS = "reminders"
    const val CARD = "card"
    const val SERVICE = "service"
    const val INFO = "info"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REMINDERS, t("Υπενθυμίσεις αναπάντητων", "Missed-call reminders"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CARD, t("Κάρτα μετά την κλήση", "After-call card"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(SERVICE, t("Άμεση κάρτα (μόνιμη)", "Instant card (ongoing)"), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(INFO, t("Backup και ενημερώσεις", "Backup and updates"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed(context: Context) = Perms.has(context, Manifest.permission.POST_NOTIFICATIONS) || android.os.Build.VERSION.SDK_INT < 33

    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!allowed(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }.onFailure { Report.note("notify", it.toString()) }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun info(context: Context, id: Int, title: String, text: String) = post(context, id,
        NotificationCompat.Builder(context, INFO).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
}

/** The reminders of missed calls: one alarm at the earliest one, and the notification when it rings. */
object Alarms {
    private const val ACTION_RING = "gr.wsol.billablecalls.RING"
    const val ACTION_CALL = "gr.wsol.billablecalls.CALL"
    const val ACTION_LATER = "gr.wsol.billablecalls.LATER"
    const val ACTION_IGNORE = "gr.wsol.billablecalls.IGNORE"

    fun nid(key: String) = 10_000 + (key.hashCode() and 0xFFFFF)

    fun schedule(context: Context) {
        val next = Store.data.reminders.filter { !it.stopped && it.nextAt != Long.MAX_VALUE }.minOfOrNull { it.nextAt }
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 1, Intent(context, AlarmReceiver::class.java).setAction(ACTION_RING), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(pi)
        if (next != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(next, System.currentTimeMillis() + 5_000), pi)
    }

    /** The alarm rang: every reminder that is due rings (or stops, if a stop condition happened). */
    fun ring(context: Context) {
        Store.init(context)
        val now = System.currentTimeMillis()
        val due = mutableListOf<gr.wsol.billablecalls.core.Reminder>()
        Store.update { d ->
            d.copy(reminders = d.reminders.map { r ->
                if (r.stopped || r.nextAt > now + 30_000) r
                else Reminders.stopReason(r, d, now)?.let { r.copy(stopped = true, reason = it, stoppedAt = now) }
                    ?: Reminders.afterRing(r, d, now).also { due += r }
            })
        }
        due.forEach { show(context, it) }
        schedule(context)
    }

    private fun show(context: Context, r: gr.wsol.billablecalls.core.Reminder) {
        val d = Store.data
        val who = r.label + (d.company(r.companyId)?.name?.takeIf { it != r.label }?.let { " · $it" } ?: "")
        val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(r.callStart))
        val text = if (r.direction == Direction.OUT_NO_ANSWER) t("Δεν απάντησε στις $time. Ξαναπάρε.", "Didn't answer at $time. Call again.")
        else t("Αναπάντητη στις $time. Πάρε πίσω.", "Missed at $time. Call back.")
        fun act(action: String, code: Int) = PendingIntent.getBroadcast(context, nid(r.key) + code, Intent(context, AlarmReceiver::class.java).setAction(action).putExtra("key", r.key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, nid(r.key), Intent(context, MainActivity::class.java).putExtra("tab", "missed"), PendingIntent.FLAG_IMMUTABLE)
        Notify.post(context, nid(r.key), NotificationCompat.Builder(context, Notify.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(who).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, t("Κάλεσε", "Call"), PendingIntent.getActivity(context, nid(r.key) + 1, Intent(context, DialActivity::class.java).putExtra("key", r.key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, t("Αργότερα", "Later"), act(ACTION_LATER, 2))
            .addAction(0, t("Αγνόησε", "Ignore"), act(ACTION_IGNORE, 3))
            .build())
    }

    fun dial(context: Context, number: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun act(context: Context, action: String, key: String) {
        Store.init(context)
        val now = System.currentTimeMillis()
        val r = Store.data.reminders.firstOrNull { it.key == key && !it.stopped }
        Notify.cancel(context, nid(key))
        when (action) {
            ACTION_CALL -> {
                r?.let { dial(context, it.number) }
                // the reminder stops when you talk (if that's on), not by the tap alone
                Store.update { d -> d.copy(reminders = d.reminders.map { if (it.key == key && !it.stopped) Reminders.snooze(it, d, now) else it }) }
            }
            ACTION_LATER -> Store.update { d -> d.copy(reminders = d.reminders.map { if (it.key == key && !it.stopped) Reminders.snooze(it, d, now) else it }) }
            ACTION_IGNORE -> Store.update { d -> d.copy(reminders = d.reminders.map { if (it.key == key && !it.stopped) Reminders.dismiss(it, now) else it }) }
        }
        schedule(context)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    Alarms.ACTION_CALL, Alarms.ACTION_LATER, Alarms.ACTION_IGNORE -> {
                        val key = intent.getStringExtra("key")
                        if (key != null) Alarms.act(context, intent.action!!, key)
                    }
                    else -> { CallSync.run(context); Alarms.ring(context) }
                }
            } catch (e: Exception) { Report.note("alarm", e.toString()) } finally { pending.finish() }
        }.start()
    }
}

/** After a restart of the phone: the alarm, the jobs and the instant-card service again. */
/** «Call» from the reminder: the dialler with the number (an activity, since Android 12 allows no other way). */
class DialActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("key")?.let { Alarms.act(this, Alarms.ACTION_CALL, it) }
        finish()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Jobs.schedule(context)
        Alarms.schedule(context)
        if (Store.settings.instantCard) InstantService.start(context)
    }
}
