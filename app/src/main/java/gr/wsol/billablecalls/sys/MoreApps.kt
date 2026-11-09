package gr.wsol.billablecalls.sys

import android.content.Context
import android.content.Intent
import android.net.Uri
import gr.wsol.billablecalls.core.t

/**
 * «Κοινοποίηση» of this app and «Οι άλλες εφαρμογές μας» (8/11/2026): the same list in every app of jemangr,
 * without the app itself. Install = the latest APK from the app's GitHub releases, in the browser; if the app is
 * already on the phone, it opens instead (the packages are in <queries> of the manifest).
 */
object MoreApps {
    const val SELF = "gr.wsol.billablecalls"

    class App(val pkg: String, val emoji: String, val name: String, val descEl: String, val descEn: String, val page: String, val apk: String, val adult: Boolean = false) {
        val desc get() = t(descEl, descEn)
    }

    val all = listOf(
        App("gr.wsol.gamenight", "🎲", "GameNight",
            "54 παιχνίδια παρέας σε ένα κινητό. Χωρίς internet και διαφημίσεις.",
            "54 party games on one phone. No internet, no ads.",
            "https://github.com/jemangr/Gamenight-GR", "https://github.com/jemangr/Gamenight-GR/releases/latest/download/GamenightGR.apk"),
        App("app.binelik", "🤬", "Binelik AI",
            "Ξέσπασε σε AI χαρακτήρες που σου απαντάνε. Με δικό σου κλειδί AI. 18+",
            "Vent at AI characters who answer back. With your own AI key. 18+",
            "https://github.com/jemangr/Binelik-AI", "https://github.com/jemangr/Binelik-AI/releases/latest/download/BinelikAI.apk", adult = true),
        App("gr.wsol.billablecalls", "📞", "Billable Calls",
            "Οι κλήσεις με πελάτες, ο χρεώσιμος χρόνος ανά εταιρεία και υπενθυμίσεις για αναπάντητες.",
            "Calls with clients, billable time per company and reminders for missed calls.",
            "https://github.com/jemangr/Billable-Calls", "https://github.com/jemangr/Billable-Calls/releases/latest/download/BillableCalls.apk"),
    )

    val others get() = all.filter { it.pkg != SELF }
    val self get() = all.first { it.pkg == SELF }

    fun installed(c: Context, pkg: String): Boolean = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Opens the app if it is on the phone, else downloads its APK in the browser. */
    fun openOrInstall(c: Context, a: App) {
        val launch = c.packageManager.getLaunchIntentForPackage(a.pkg)
        val i = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(a.apk))
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun share(c: Context) {
        val me = self
        val text = t("${me.name}: ${me.descEl}\nΔωρεάν για Android: ${me.page}", "${me.name}: ${me.descEn}\nFree for Android: ${me.page}")
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, me.name).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { c.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
