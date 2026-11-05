package gr.wsol.billablecalls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.sys.Alarms
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.Files
import gr.wsol.billablecalls.sys.Notify
import gr.wsol.billablecalls.update.Updates
import gr.wsol.billablecalls.ui.App
import gr.wsol.billablecalls.ui.BillableTheme
import gr.wsol.billablecalls.ui.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tab = if (intent?.getStringExtra("tab") == "missed") Tab.MISSED else Tab.TODAY
        setContent { BillableTheme { App(tab) } }
    }

    override fun onResume() {
        super.onResume()
        if (!Store.settings.onboarded) return
        lifecycleScope.launch(Dispatchers.IO) {
            // a call that just ended gets its card here too, if the background job hasn't run yet
            CallSync.run(this@MainActivity)
            Alarms.ring(this@MainActivity)
            // the customer list from a link or Drive: again once a day
            val s = Store.settings
            if ((s.listSource == "url" || s.listSource == "drive") && System.currentTimeMillis() - s.listSynced > 20 * 3600_000L) Files.loadList(this@MainActivity)
            // a new version on GitHub: a notification (at most every few hours; can be turned off)
            if (BuildConfig.UPDATE_CHECK && s.updateCheck && System.currentTimeMillis() - s.lastUpdateCheck > Updates.EVERY_MS) {
                Store.settings { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                Updates.check(BuildConfig.VERSION_NAME)?.let { r ->
                    Notify.info(this@MainActivity, 8, t("Νέα έκδοση ${r.version}", "New version ${r.version}"), t("Ρυθμίσεις → Ενημερώσεις → Εγκατάσταση.", "Settings → Updates → Install."))
                }
            }
        }
    }
}
