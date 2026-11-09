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
    /** The reminders' channel: «reminders_v<n>», a new one each time the sound settings change. */
    const val REMINDERS_PREFIX = "reminders"
    const val CARD = "card"
    const val SERVICE = "service"
    const val INFO = "info"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        reminders(context)
        nm.createNotificationChannel(NotificationChannel(CARD, t("Κάρτα μετά την κλήση", "After-call card"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(SERVICE, t("Άμεση κάρτα (μόνιμη)", "Instant card (ongoing)"), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(INFO, t("Backup και ενημερώσεις", "Backup and updates"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun reminderId(r: gr.wsol.billablecalls.core.ReminderSettings) = REMINDERS_PREFIX + "_v" + r.channelVer

    /** The reminders' channel, made with the sound and vibration of the settings; the older ones are deleted. */
    fun reminders(context: Context): String {
        Store.init(context)
        val r = Store.settings.reminders
        val id = reminderId(r)
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching {
            nm.notificationChannels.filter { it.id.startsWith(REMINDERS_PREFIX) && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
            if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(NotificationChannel(id, t("Υπενθυμίσεις", "Reminders"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = t("Αναπάντητες και ενέργειες με υπενθύμιση", "Missed calls and actions with a reminder")
                val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (r.sound) setSound(r.soundUri?.let { Uri.parse(it) } ?: android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs) else setSound(null, null)
                enableVibration(r.vibrate)
                if (r.vibrate) vibrationPattern = longArrayOf(0, 300, 200, 300)
            })
        }.onFailure { Report.note("channel", it.toString()) }
        return id
    }

    /** What Android says about the channel now: off, or no sound (the user may have changed it there). */
    fun reminderBlocked(context: Context): Boolean = runCatching {
        val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(reminders(context))
        ch == null || ch.importance == NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    fun reminderSilent(context: Context): Boolean = runCatching {
        val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(reminders(context))
        ch != null && ch.sound == null && Store.settings.reminders.sound
    }.getOrDefault(false)

    /** Android's settings for the reminders' channel (sound, vibration, pop-up, «do not disturb»). */
    fun openReminderSettings(context: Context) {
        val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, reminders(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
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
        val d = Store.data
        val next = (d.reminders.filter { !it.stopped && it.nextAt != Long.MAX_VALUE }.map { it.nextAt } +
            d.followUps.filter { it.doneAt == null && !it.notified && it.at != null }.mapNotNull { it.at }).minOrNull()
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
        // the reminders you set from the card
        val follow = Store.data.followUps.filter { it.doneAt == null && !it.notified && it.at != null && it.at <= now + 30_000 }
        if (follow.isNotEmpty()) {
            Store.update { d -> d.copy(followUps = d.followUps.map { f -> if (follow.any { it.id == f.id }) f.copy(notified = true) else f }) }
            follow.forEach { showFollowUp(context, it) }
        }
        schedule(context)
    }

    const val ACTION_F_DONE = "gr.wsol.billablecalls.F_DONE"
    const val ACTION_F_LATER = "gr.wsol.billablecalls.F_LATER"
    fun fid(id: String) = 40_000 + (id.hashCode() and 0xFFFF)

    private fun showFollowUp(context: Context, f: gr.wsol.billablecalls.core.FollowUp) {
        val d = Store.data
        val who = d.company(f.companyId)?.name ?: f.label
        fun act(action: String, code: Int) = PendingIntent.getBroadcast(context, fid(f.id) + code, Intent(context, AlarmReceiver::class.java).setAction(action).putExtra("f", f.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(context, fid(f.id), Intent(context, MainActivity::class.java).putExtra("tab", "todo"), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, Notify.reminders(context)).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⏰ $who").setContentText(f.text.ifBlank { t("Υπενθύμιση", "Reminder") }).setStyle(NotificationCompat.BigTextStyle().bigText(f.text.ifBlank { t("Υπενθύμιση", "Reminder") }))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
        f.number?.let { n -> b.addAction(0, t("Κάλεσε", "Call"), PendingIntent.getActivity(context, fid(f.id) + 1, Intent(context, DialActivity::class.java).putExtra("number", n), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
        b.addAction(0, t("Ολοκληρώθηκε", "Done"), act(ACTION_F_DONE, 2))
        b.addAction(0, t("Αργότερα", "Later"), act(ACTION_F_LATER, 3))
        Notify.post(context, fid(f.id), b.build())
    }

    fun followUpAct(context: Context, action: String, id: String) {
        Store.init(context)
        Notify.cancel(context, fid(id))
        val now = System.currentTimeMillis()
        when (action) {
            ACTION_F_DONE -> Store.update { gr.wsol.billablecalls.core.Ops.followUpDone(it, id, true, now) }
            ACTION_F_LATER -> Store.update { gr.wsol.billablecalls.core.Ops.snoozeFollowUp(it, id, now + Store.settings.reminders.snoozeMin * 60_000L) }
        }
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
        Notify.post(context, nid(r.key), NotificationCompat.Builder(context, Notify.reminders(context))
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(who).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, t("Κάλεσε", "Call"), PendingIntent.getActivity(context, nid(r.key) + 1, Intent(context, DialActivity::class.java).putExtra("key", r.key), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, t("Αργότερα", "Later"), act(ACTION_LATER, 2))
            .addAction(0, t("Αγνόησε", "Ignore"), act(ACTION_IGNORE, 3))
            .build())
    }

    /** «Test»: a reminder now, with the sound and vibration of the settings. */
    fun test(context: Context) {
        val n = NotificationCompat.Builder(context, Notify.reminders(context)).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t("Δοκιμή υπενθύμισης", "Test reminder")).setContentText(t("Έτσι θα ακούγεται και θα δονείται.", "This is how it will sound and vibrate."))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 9_999, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        Notify.post(context, 9_999, n)
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
                    Alarms.ACTION_F_DONE, Alarms.ACTION_F_LATER -> intent.getStringExtra("f")?.let { Alarms.followUpAct(context, intent.action!!, it) }
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
        intent.getStringExtra("number")?.let { Alarms.dial(this, it) }
        finish()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // only the two system broadcasts of the manifest (Lint: UnsafeProtectedBroadcastReceiver)
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.init(context)
        Jobs.schedule(context)
        Alarms.schedule(context)
        if (Store.settings.instantCard) InstantService.start(context)
    }
}
