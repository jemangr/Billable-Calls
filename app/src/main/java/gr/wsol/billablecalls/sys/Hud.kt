package gr.wsol.billablecalls.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.Packages
import gr.wsol.billablecalls.core.Phone
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Before you answer: while the phone rings, a small card at the top with the company, what's left of
 * its package, the last topics and notes, and the open actions. Tap it to close; it goes away when
 * the call is answered (after a few seconds) or ends.
 */
object Hud {
    /** Every card on the screen (normally one): all of them go together, so none is left behind. */
    private val views = mutableListOf<View>()
    @Volatile private var shownFor: String? = null
    @Volatile private var shownAt = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val timeout = Runnable { removeAll() }

    /** The lines for [number], or null if there's nothing to say (not a customer, no history). */
    fun lines(d: Data, number: String, contact: String?, org: String?): List<String>? {
        val key = Phone.key(number)
        val past = d.calls.filter { it.key == key }.sortedByDescending { it.start }
        val rule = d.rule(key)
        if (rule?.personal == true) return null
        val companyId = rule?.companyId ?: past.firstOrNull { it.companyId != null }?.companyId ?: Matcher.match(contact, org, d.companies, d.settings).companyId
        val company = d.company(companyId) ?: return null
        val out = mutableListOf("🏢 " + company.name + (contact?.takeIf { it.isNotBlank() && it != company.name }?.let { " · $it" } ?: ""))
        Packages.state(d, company)?.let { st ->
            out += if (st.overSec > 0) t("Πακέτο: +${Billing.nice(st.overSec)} πάνω από τις ${Billing.nice(st.includedSec)} του μήνα", "Package: ${Billing.nice(st.overSec)} over the month's ${Billing.nice(st.includedSec)}")
            else t("Πακέτο: απομένουν ${Billing.nice(st.leftSec)} / ${Billing.nice(st.includedSec)} αυτόν τον μήνα", "Package: ${Billing.nice(st.leftSec)} / ${Billing.nice(st.includedSec)} left this month")
        }
        out += notes(d, company.id)
        val open = d.followUps.filter { it.companyId == company.id && it.doneAt == null }
        if (open.isNotEmpty()) out += t("📌 Εκκρεμεί: ", "📌 To do: ") + open.joinToString("; ") { it.text.take(40) }
        return out
    }

    /** The company's 2 latest notes (any number of the company), or its last call if it has none. */
    fun notes(d: Data, companyId: String): List<String> {
        val fmt = SimpleDateFormat("dd/MM", Locale.ROOT)
        val calls = d.calls.filter { it.companyId == companyId }.sortedByDescending { it.start }
        val noted = calls.filter { it.note.isNotBlank() }.take(2)
        if (noted.isNotEmpty()) return noted.map { c ->
            "• " + listOfNotNull(fmt.format(Date(c.start)), c.category, "«" + c.note.trim().replace('\n', ' ').take(70) + "»").joinToString(" · ")
        }
        val last = calls.firstOrNull() ?: return emptyList()
        return listOf(t("Τελευταία κλήση: ", "Last call: ") + listOfNotNull(fmt.format(Date(last.start)), last.category, t("χωρίς σημείωση", "no note")).joinToString(" · "))
    }

    /** A second «ringing» for the same number (the phone sends it to more than one listener) is ignored. */
    @Synchronized private fun claim(number: String, now: Long): Boolean {
        if (shownFor == number && now - shownAt < 90_000) return false
        shownFor = number; shownAt = now
        return true
    }

    fun show(context: Context, number: String) {
        if (number.isBlank() || !claim(number, System.currentTimeMillis())) return
        Store.init(context)
        if (!Store.settings.hudOn || !Card.canOverlay(context)) return
        val contact = runCatching { AndroidCallSource(context).contact(number) }.getOrNull()
        val lines = lines(Store.data, number, contact?.name, contact?.org) ?: return
        main.post {
            runCatching {
                removeAll()
                val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt() }
                val box = FrameLayout(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xF0151619.toInt()); setStroke(dp(1f), 0xFF8FB0FF.toInt()) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(12f), dp(40f), dp(12f))
                        lines.forEachIndexed { i, l ->
                            addView(TextView(context).apply {
                                text = l; setTextColor(if (i == 0) 0xFFFFFFFF.toInt() else 0xFFD5D8E0.toInt())
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 0) 17f else 14f); if (i == 0) typeface = Typeface.DEFAULT_BOLD
                                maxLines = 2
                            })
                        }
                    })
                    // the ✕ in the corner
                    addView(TextView(context).apply {
                        text = "✕"; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        gravity = Gravity.CENTER; contentDescription = t("Κλείσιμο", "Close")
                        setOnClickListener { removeAll() }
                    }, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.END))
                    setOnClickListener { removeAll() }
                }
                val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; x = 0; y = dp(70f); horizontalMargin = 0.03f }
                context.getSystemService(WindowManager::class.java).addView(box, lp)
                views += box
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, 60_000)
            }.onFailure { Report.note("hud", it.toString()) }
        }
    }

    /** Removes every card now (on the main thread). The call stays «seen», so it doesn't come back while it rings. */
    private fun removeAll() {
        main.removeCallbacks(timeout)
        views.toList().forEach { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        views.clear()
    }

    /** The call ended: every card goes, and the next call (even from the same number) gets a new one. */
    fun hide() {
        main.post { removeAll(); synchronized(this) { shownFor = null; shownAt = 0L } }
    }

    /** Answered: the card stays a few seconds, then goes. */
    fun hideIn(ms: Long) { main.postDelayed({ removeAll() }, ms) }

    /** For the tests. */
    internal fun count() = views.size
}

/** The phone's call state: ringing → the card before you answer; answered → it goes after a few seconds; ended → gone. */
class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { Hud.show(context.applicationContext, it) }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> Hud.hideIn(8_000)
            TelephonyManager.EXTRA_STATE_IDLE -> Hud.hide()
        }
    }
}
