package gr.wsol.billablecalls.sys

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import gr.wsol.billablecalls.MainActivity
import gr.wsol.billablecalls.R
import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import java.util.concurrent.Executors

/**
 * The background work, with Android's JobScheduler: a job that runs a few seconds after every
 * change of the call log (even with the app closed), a sync every 15′ as a safety net, and the
 * daily backup to Drive.
 */
object Jobs {
    const val CALLS = 1
    const val PERIODIC = 2
    const val BACKUP = 3

    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        val cn = ComponentName(context, CallJob::class.java)
        js.schedule(JobInfo.Builder(CALLS, cn)
            .addTriggerContentUri(JobInfo.TriggerContentUri(CallLog.Calls.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(1_000).setTriggerContentMaxDelay(4_000).build())
        if (js.getPendingJob(PERIODIC) == null) js.schedule(JobInfo.Builder(PERIODIC, cn).setPeriodic(15 * 60_000L).setPersisted(true).build())
        val s = Store.settings
        if (s.autoBackup && s.driveFolderId.isNotEmpty()) {
            if (js.getPendingJob(BACKUP) == null) js.schedule(JobInfo.Builder(BACKUP, cn).setPeriodic(24 * 3600_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        } else js.cancel(BACKUP)
    }
}

class CallJob : JobService() {
    private val pool = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        pool.execute {
            try {
                Store.init(this)
                when (params.jobId) {
                    Jobs.BACKUP -> Backup.autoToDrive(this)
                    else -> { CallSync.run(this); Alarms.ring(this); Webhook.flush(this) }
                }
            } catch (e: Exception) { Report.note("job", e.toString()) }
            // the content trigger fires once: set it again for the next call
            if (params.jobId == Jobs.CALLS) Jobs.schedule(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = params.jobId == Jobs.CALLS
}

/**
 * «Instant card»: an ongoing notification keeps this service alive, and it hears when a call ends,
 * for phones that delay the background jobs (Xiaomi, Samsung, Huawei…).
 */
class InstantService : Service() {
    private var callback: TelephonyCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var inCall = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, Notify.SERVICE).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(t("Billable Calls: άμεση κάρτα", "Billable Calls: instant card"))
            .setContentText(t("Ακούει πότε τελειώνει μια κλήση.", "Listens for the end of a call.")).setOngoing(true).setContentIntent(open).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
        }.onFailure { Report.note("instant", it.toString()); stopSelf(); return START_NOT_STICKY }
        listen()
        return START_STICKY
    }

    private val ring = PhoneStateReceiver()

    private fun listen() {
        runCatching { registerReceiver(ring, android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)) }
        if (callback != null || Build.VERSION.SDK_INT < 31) return
        if (!Perms.has(this, android.Manifest.permission.READ_PHONE_STATE)) return
        val tm = getSystemService(TelephonyManager::class.java)
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (state != TelephonyManager.CALL_STATE_IDLE) { inCall = true; return }
                if (!inCall) return
                inCall = false
                // the call log is written a moment after the call ends
                main.postDelayed({ pool.execute { CallSync.run(this@InstantService); Alarms.ring(this@InstantService) } }, 2_000)
            }
        }
        runCatching { tm.registerTelephonyCallback(mainExecutor, cb); callback = cb }.onFailure { Report.note("instant", it.toString()) }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(ring) }
        callback?.let { runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) } }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) { runCatching { context.startForegroundService(Intent(context, InstantService::class.java)) }.onFailure { Report.note("instant", it.toString()) } }
        fun stop(context: Context) { context.stopService(Intent(context, InstantService::class.java)) }
    }
}
