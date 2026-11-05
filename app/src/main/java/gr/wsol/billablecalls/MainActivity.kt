package gr.wsol.billablecalls

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.Alarms
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.Webhook
import gr.wsol.billablecalls.ui.App
import gr.wsol.billablecalls.ui.BillableTheme
import gr.wsol.billablecalls.ui.Tab
import gr.wsol.billablecalls.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A FragmentActivity for the lock (BiometricPrompt needs one). */
class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    private var leftAt = 0L
    /** A newer version found on GitHub: a dialog on the home screen with «Install». */
    private val update = mutableStateOf<Updates.Release?>(null)
    /** The bug report, opened by shaking the phone. */
    private val report = mutableStateOf(false)
    private val shake = gr.wsol.billablecalls.sys.Shake { if (Store.settings.shakeReport && !locked.value) report.value = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tab = if (intent?.getStringExtra("tab") in setOf("missed", "todo")) Tab.MISSED else Tab.TODAY
        locked.value = Store.settings.lockOn && savedInstanceState == null
        setContent {
            BillableTheme {
                if (locked.value) Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔒 Billable Calls", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { unlock() }) { Text(t("Ξεκλείδωμα", "Unlock")) }
                    }
                } else {
                    App(tab)
                    update.value?.let { r -> gr.wsol.billablecalls.ui.UpdateDialog(r) { update.value = null } }
                    if (report.value) gr.wsol.billablecalls.ui.ReportDialog { report.value = false }
                }
            }
        }
        if (locked.value) unlock()
    }

    override fun onStop() { super.onStop(); leftAt = System.currentTimeMillis() }

    override fun onPause() { super.onPause(); shake.stop() }

    override fun onStart() {
        super.onStart()
        // locked again after a minute away
        if (Store.settings.lockOn && leftAt > 0 && System.currentTimeMillis() - leftAt > 60_000 && !locked.value) { locked.value = true; unlock() }
    }

    private fun unlock() {
        if (!canLock(this)) { locked.value = false; return }
        val prompt = BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Billable Calls").setSubtitle(t("Ξεκλείδωμα", "Unlock"))
            .setAllowedAuthenticators(AUTH).build())
    }

    override fun onResume() {
        super.onResume()
        if (Store.settings.shakeReport) shake.start(this)
        if (!Store.settings.onboarded) return
        lifecycleScope.launch(Dispatchers.IO) {
            // a call that just ended gets its card here too, if the background job hasn't run yet
            CallSync.run(this@MainActivity)
            Alarms.ring(this@MainActivity)
            Webhook.flush(this@MainActivity)
            // the customer list from a link or Drive: again once a day
            val s = Store.settings
            if ((s.listSource == "url" || s.listSource == "drive") && System.currentTimeMillis() - s.listSynced > 20 * 3600_000L) Files.loadList(this@MainActivity)
            // a new version on GitHub: a notification (at most every few hours; can be turned off)
            // (at most every hour when the app is opened; the dialog shows on the home screen, with «Install»)
            if (BuildConfig.UPDATE_CHECK && s.updateCheck && update.value == null && System.currentTimeMillis() - s.lastUpdateCheck > Updates.OPEN_EVERY_MS) {
                Store.settings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                Updates.check(BuildConfig.VERSION_NAME)?.let { r -> update.value = r }
            }
        }
    }

    companion object {
        const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        /** Is there a fingerprint, face or screen lock to use? */
        fun canLock(context: android.content.Context) = BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
