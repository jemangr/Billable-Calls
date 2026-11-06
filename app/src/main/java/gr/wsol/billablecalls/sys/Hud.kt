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
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Packages
import gr.wsol.billablecalls.core.Phone
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Before you answer: while the phone rings, a card in the middle of the screen with the company, what's left of
 * its package, the last notes, and the open actions (1.3.4: Giannis 6/11 «στη μέση της οθόνης, να μετακινείται,
 * να μένει και μετά την απάντηση μέχρι το κλείσιμο»). Drag it up or down (the place is kept for the next call);
 * it stays while you talk and goes when the call ends, or with its «✕».
 */
object Hud {
    /** Every card on the screen (normally one): all of them go together, so none is left behind. */
    private val views = mutableListOf<View>()
    @Volatile private var shownFor: String? = null
    @Volatile private var shownAt = 0L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Only a safety net, if the phone never says the call ended: the ✕ closes it anyway. */
    private const val SAFETY_MS = 3 * 60 * 60_000L
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

    /** The card on the screen now (for the keyboard). */
    private var current: View? = null
    private fun box(): View? = current

    /** The card takes the keyboard only while you write in the note (otherwise touches go to the phone app). */
    private fun takeKeyboard(v: View?, lp: WindowManager.LayoutParams, wm: WindowManager, focusable: Boolean) {
        v ?: return
        lp.flags = if (focusable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /** Keeps what is written on the card for [number] (empty = nothing). */
    fun saveDraft(number: String, shownAt: Long, text: String) {
        Store.update { Ops.setDraft(it, Phone.key(number), text, shownAt, System.currentTimeMillis()) }
    }

    private fun noteField(context: Context, number: String, shownAt: Long, dp: (Float) -> Int, focus: (Boolean) -> Unit): View {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edit = android.widget.EditText(context).apply {
            hint = t("Σημείωση όσο μιλάτε…", "Note while you talk…")
            setHintTextColor(0xFF8F95A3.toInt()); setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // 1.3.6: always 4 lines; more text scrolls up and down inside the field
            minLines = 4; maxLines = 4; gravity = Gravity.TOP or Gravity.START
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(0xFF23252B.toInt()); setStroke(dp(1f), 0xFF3A3F4A.toInt()) }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            // tapping the field: the card takes the keyboard
            setOnTouchListener { v, e ->
                // scrolling the note must not drag the card
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN || e.actionMasked == android.view.MotionEvent.ACTION_MOVE) v.parent?.requestDisallowInterceptTouchEvent(true)
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP && !v.hasFocus()) {
                    focus(true); v.requestFocus(); main.postDelayed({ imm?.showSoftInput(v, 0) }, 120)
                }
                false
            }
            val save = Runnable { saveDraft(number, shownAt, text.toString()) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { main.removeCallbacks(save); main.postDelayed(save, 600) }
            })
        }
        val done = TextView(context).apply {
            text = "✓"; setTextColor(0xFF8FB0FF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER
            contentDescription = t("Τέλος σημείωσης", "Done with the note")
            setOnClickListener {
                saveDraft(number, shownAt, edit.text.toString())
                imm?.hideSoftInputFromWindow(edit.windowToken, 0); edit.clearFocus(); focus(false)
            }
        }
        row.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(done, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        return row
    }

    fun show(context: Context, number: String) {
        val shownAt = System.currentTimeMillis()
        if (number.isBlank() || !claim(number, shownAt)) return
        Store.init(context)
        if (!Store.settings.hudOn || !Card.canOverlay(context)) return
        val contact = runCatching { AndroidCallSource(context).contact(number) }.getOrNull()
        val lines = lines(Store.data, number, contact?.name, contact?.org) ?: return
        main.post {
            runCatching {
                removeAll()
                val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt() }
                val wm = context.getSystemService(WindowManager::class.java)
                val screenH = context.resources.displayMetrics.heightPixels
                val maxOff = (screenH / 2 - dp(90f)).coerceAtLeast(0)
                val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT).apply {
                    // y is the distance from the middle of the screen (gravity CENTER_VERTICAL)
                    gravity = Gravity.CENTER_VERTICAL; x = 0; y = dp(Store.settings.hudOffsetDp.toFloat()).coerceIn(-maxOff, maxOff); horizontalMargin = 0.03f
                }
                val box = FrameLayout(context).apply {
                    background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(0xF0151619.toInt()); setStroke(dp(1f), 0xFF8FB0FF.toInt()) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(10f), dp(48f), dp(12f))
                        // the handle: shows that the card moves
                        addView(TextView(context).apply {
                            text = "⠿  " + t("σύρε πάνω ή κάτω", "drag up or down"); setTextColor(0xFF8F95A3.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        })
                        lines.forEachIndexed { i, l ->
                            addView(TextView(context).apply {
                                text = l; setTextColor(if (i == 0) 0xFFFFFFFF.toInt() else 0xFFD5D8E0.toInt())
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 0) 17f else 14f); if (i == 0) typeface = Typeface.DEFAULT_BOLD
                                maxLines = 2
                            })
                        }
                        // 1.3.5: a note while you talk; it is kept at once and goes into the call when it reaches the log
                        addView(noteField(context, number, shownAt, dp, { focusable -> takeKeyboard(box(), lp, wm, focusable) }),
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
                    })
                    // the ✕ in the corner: the only way to close it by hand
                    addView(TextView(context).apply {
                        text = "✕"; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        gravity = Gravity.CENTER; contentDescription = t("Κλείσιμο", "Close")
                        setOnClickListener { removeAll() }
                    }, FrameLayout.LayoutParams(dp(48f), dp(48f), Gravity.TOP or Gravity.END))
                    // drag up or down; the place is kept for the next call
                    var startRaw = 0f; var startY = 0
                    setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> { startRaw = e.rawY; startY = lp.y; true }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                lp.y = (startY + (e.rawY - startRaw).toInt()).coerceIn(-maxOff, maxOff)
                                runCatching { wm.updateViewLayout(v, lp) }; true
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                val off = (lp.y / context.resources.displayMetrics.density).toInt()
                                if (off != Store.settings.hudOffsetDp) Store.settings { it.copy(hudOffsetDp = off) }
                                true
                            }
                            else -> false
                        }
                    }
                }
                wm.addView(box, lp)
                views += box
                current = box
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, SAFETY_MS)
            }.onFailure { Report.note("hud", it.toString()) }
        }
    }

    /** Removes every card now (on the main thread). The call stays «seen», so it doesn't come back while it rings. */
    private fun removeAll() {
        main.removeCallbacks(timeout)
        current = null
        views.toList().forEach { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        views.clear()
    }

    /** The call ended: every card goes, and the next call (even from the same number) gets a new one. */
    fun hide() {
        main.post { removeAll(); synchronized(this) { shownFor = null; shownAt = 0L } }
    }


    /** For the tests. */
    internal fun count() = views.size
}

/** The phone's call state: ringing → the card before you answer; answered → it stays while you talk; ended → gone. */
class PhoneStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.let { Hud.show(context.applicationContext, it) }
            TelephonyManager.EXTRA_STATE_IDLE -> Hud.hide()
        }
    }
}
