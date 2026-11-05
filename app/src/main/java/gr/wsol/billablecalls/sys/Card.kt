package gr.wsol.billablecalls.sys

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import gr.wsol.billablecalls.CardActivity
import gr.wsol.billablecalls.R
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t

/**
 * The card at the end of a call. With «display over other apps» it opens straight away over
 * whatever is on screen; without it, a notification opens it.
 */
object Card {
    const val NID = 5

    fun canOverlay(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, calls: List<Call>) {
        val ids = calls.map { it.id }.toLongArray()
        val intent = Intent(context, CardActivity::class.java).putExtra("ids", ids)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (canOverlay(context) && runCatching { context.startActivity(intent) }.isSuccess) return
        val last = calls.last()
        val d = Store.data
        val title = last.label + (d.company(last.companyId)?.name?.let { " · $it" } ?: "")
        val text = t("Κλήση ${Billing.hms(last.durationSec)}. Εταιρική; Πάτα για εταιρεία και σημείωση.", "Call ${Billing.hms(last.durationSec)}. Business? Tap for company and note.")
        val pi = PendingIntent.getActivity(context, NID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            Notify.post(context, NID, NotificationCompat.Builder(context, Notify.CARD).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS).build())
        }.onFailure { Report.note("card", it.toString()) }
    }

    fun overlaySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
