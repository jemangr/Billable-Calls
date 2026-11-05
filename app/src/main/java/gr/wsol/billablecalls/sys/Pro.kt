package gr.wsol.billablecalls.sys

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Overtime
import gr.wsol.billablecalls.core.Packages
import gr.wsol.billablecalls.core.Reminders
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.Voip
import gr.wsol.billablecalls.core.t
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The webhook (e.g. your n8n): a POST with the call as JSON after every card; failed ones are sent again later. */
object Webhook {
    fun json(c: Call): String {
        val d = Store.data
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }
        return buildJsonObject {
            put("id", c.id); put("company", d.company(c.companyId)?.name); put("contact", c.contact); put("number", c.number)
            put("direction", c.direction.name.lowercase()); put("sim", c.sim)
            put("start", iso.format(Date(c.start))); put("end", iso.format(Date(c.end))); put("durationSec", c.durationSec)
            put("billableSec", Billing.seconds(c, d)); put("topic", c.category); put("note", c.note)
            put("outOfHours", Overtime.isOut(c, d.settings)); put("invoiced", c.billedAt != null)
        }.toString()
    }

    fun post(url: String, body: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.setRequestProperty("User-Agent", "BillableCalls-app")
        try { c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }; return c.responseCode } finally { c.disconnect() }
    }

    /** Sends what waits; off the main thread. */
    fun flush(context: Context) {
        Store.init(context)
        val s = Store.settings
        if (!s.webhookOn || s.webhookUrl.isBlank()) return
        for (id in Store.data.pendingHooks.toList()) {
            val call = Store.data.calls.firstOrNull { it.id == id }
            val ok = call == null || runCatching { post(s.webhookUrl, json(call)) in 200..299 }.onFailure { Report.note("webhook", it.toString()) }.getOrDefault(false)
            if (!ok) return
            Store.update { d -> d.copy(pendingHooks = d.pendingHooks - id) }
        }
    }

    fun flushLater(context: Context) { if (Store.data.pendingHooks.isNotEmpty()) Thread { flush(context.applicationContext) }.start() }
}

/** The package alerts: a notification the first time a company goes over its hours in a month. */
object PackageAlerts {
    fun check(context: Context) {
        var over = emptyList<gr.wsol.billablecalls.core.Company>()
        Store.update { d -> Packages.newlyOver(d).also { over = it.second }.first }
        over.forEach { c ->
            val st = Packages.state(Store.data, c) ?: return@forEach
            Notify.info(context, 30_000 + (c.id.hashCode() and 0xFFF), t("Πέρασε το πακέτο: ${c.name}", "Package exceeded: ${c.name}"),
                t("Αυτόν τον μήνα ${Billing.nice(st.usedSec)} από ${Billing.nice(st.includedSec)} (+${Billing.nice(st.overSec)}).", "This month ${Billing.nice(st.usedSec)} of ${Billing.nice(st.includedSec)} (+${Billing.nice(st.overSec)})."))
        }
    }
}

/** Battery optimisation: the exemption, and the extra steps some makers need. */
object Battery {
    fun exempt(context: Context) = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun ask(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun appSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val maker: String get() = Build.MANUFACTURER.lowercase()

    /** What to do on this phone, beyond the exemption. */
    fun tips(): String = when {
        "xiaomi" in maker || "redmi" in maker || "poco" in maker -> t("Xiaomi: Ρυθμίσεις → Εφαρμογές → Billable Calls → Εξοικονόμηση μπαταρίας → «Χωρίς περιορισμούς», και «Αυτόματη εκκίνηση» ανοιχτό.", "Xiaomi: Settings → Apps → Billable Calls → Battery saver → «No restrictions», and «Autostart» on.")
        "samsung" in maker -> t("Samsung: Ρυθμίσεις → Μπαταρία → Όρια χρήσης παρασκηνίου → «Εφαρμογές που δεν τίθενται ποτέ σε αδράνεια» → πρόσθεσε το Billable Calls.", "Samsung: Settings → Battery → Background usage limits → «Never sleeping apps» → add Billable Calls.")
        "huawei" in maker || "honor" in maker -> t("Huawei: Ρυθμίσεις → Μπαταρία → Εκκίνηση εφαρμογών → Billable Calls → «Διαχείριση χειροκίνητα» και όλα ανοιχτά.", "Huawei: Settings → Battery → App launch → Billable Calls → «Manage manually», all on.")
        "oneplus" in maker || "oppo" in maker || "realme" in maker -> t("OnePlus/Oppo: Ρυθμίσεις → Μπαταρία → Βελτιστοποίηση μπαταρίας → Billable Calls → «Χωρίς βελτιστοποίηση», και «Αυτόματη εκκίνηση» ανοιχτό.", "OnePlus/Oppo: Settings → Battery → Battery optimisation → Billable Calls → «Don't optimise», and «Auto-launch» on.")
        else -> t("Αν η κάρτα αργεί, άνοιξε και την «Άμεση κάρτα».", "If the card is late, turn on «Instant card» too.")
    }

    fun site(): String = "https://dontkillmyapp.com/" + when {
        "xiaomi" in maker || "redmi" in maker || "poco" in maker -> "xiaomi"; "samsung" in maker -> "samsung"; "huawei" in maker -> "huawei"
        "oneplus" in maker -> "oneplus"; "oppo" in maker -> "oppo"; "realme" in maker -> "realme"; else -> ""
    }
}

/**
 * Viber and WhatsApp calls, from their call notifications (when the option is on and the user gave
 * «notification access»). The time is approximate: from the moment the call shows as ongoing until
 * its notification goes away. Only the name the app shows is known, not the number.
 */
class VoipListener : NotificationListenerService() {
    private data class Track(val app: String, val title: String, val firstSeen: Long, var talking: Long? = null, var outgoing: Boolean = false)
    private val tracks = HashMap<String, Track>()

    private fun text(n: Notification) = listOfNotNull(n.extras.getCharSequence(Notification.EXTRA_TEXT), n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)).joinToString(" ").lowercase()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = Voip.APPS[sbn.packageName] ?: return
        Store.init(this)
        if (!Store.settings.voipOn) return
        val n = sbn.notification
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (title.isBlank()) return
        val tx = text(n)
        val missed = listOf("missed", "αναπάντητ").any { it in tx }
        if (missed && n.flags and Notification.FLAG_ONGOING_EVENT == 0) { record(app, title, System.currentTimeMillis(), 0, Direction.MISSED); return }
        val call = n.category == Notification.CATEGORY_CALL || (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && listOf("call", "κλήση", "κλήσ").any { it in tx })
        if (!call) return
        val t = tracks.getOrPut(sbn.key) { Track(app, title, System.currentTimeMillis()) }
        if (listOf("calling", "ringing", "καλεί…", "κλήση…", "καλείται").any { it in tx }) t.outgoing = true
        // the call is on: a running clock, or an «ongoing» text
        val on = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || listOf("ongoing", "σε εξέλιξη", "ενεργή").any { it in tx }
        if (on && t.talking == null) t.talking = System.currentTimeMillis()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val t = tracks.remove(sbn.key) ?: return
        val now = System.currentTimeMillis()
        val start = t.talking ?: return // never answered: the app posts its own «missed call»
        val sec = ((now - start) / 1000).toInt()
        if (sec < 3) return
        record(t.app, t.title, start, sec, if (t.outgoing) Direction.OUT else Direction.IN)
    }

    private fun record(app: String, name: String, start: Long, sec: Int, dir: Direction) {
        runCatching {
            val now = System.currentTimeMillis()
            var added: Call? = null
            Store.update { d ->
                val c = Ops.withDefaultTopic(Ops.classify(Call(Voip.id(now), Voip.key(app, name), name, name, null, app, dir, start, sec), d), d.settings)
                added = c
                val d2 = d.copy(calls = d.calls + c)
                d2.copy(reminders = Reminders.sweep(d2, now))
            }
            val c = added ?: return
            val mode = Store.settings.cardMode
            if (c.answered && (mode == gr.wsol.billablecalls.core.CardMode.ALL || mode == gr.wsol.billablecalls.core.CardMode.UNMATCHED && c.companyId == null)) Card.show(this, listOf(c))
            PackageAlerts.check(this)
        }.onFailure { Report.note("voip", it.toString()) }
    }

    companion object {
        fun allowed(context: Context): Boolean = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(context, VoipListener::class.java).flattenToString()) == true

        fun openSettings(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
