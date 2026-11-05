package gr.wsol.billablecalls.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t
import gr.wsol.billablecalls.sys.CallSync
import gr.wsol.billablecalls.sys.Jobs
import gr.wsol.billablecalls.sys.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first start, one question per page: language, permissions, customer list, past calls,
 * how the company is found, SIMs, billable time, reminders, the after-call card, backup.
 * Everything can be changed later in Settings.
 */
@Composable
fun Onboarding() {
    val d by Store.flow.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf(Perms.CORE.all { Perms.has(ctx, it) }) }
    var imported by rememberSaveable { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = Perms.CORE.all { Perms.has(ctx, it) } }
    val last = 9
    // safeDrawingPadding: below the status bar, and above the keyboard when typing
    // a Surface: the text takes the colours of the theme (white on black)
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LinearProgressIndicator(progress = { (step + 1) / (last + 1f) }, modifier = Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            @Composable fun head(title: String, text: String) {
                Text(title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(text, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            when (step) {
                0 -> {
                    head("Billable Calls", t("Καταγράφει τις κλήσεις με τους πελάτες σου, βρίσκει σε ποια εταιρεία ανήκουν και μετράει τον χρεώσιμο χρόνο. Όλα μένουν στο κινητό σου.",
                        "Logs your calls with customers, finds their company and counts the billable time. Everything stays on your phone."))
                    SectionCard(t("Γλώσσα", "Language")) { Choice(listOf(false to "Ελληνικά", true to "English"), d.settings.english) { v -> Store.settings { it.copy(english = v) } } }
                }
                1 -> {
                    head(t("Άδειες", "Permissions"), t("Ιστορικό κλήσεων: για να διαβάζει τις κλήσεις. Επαφές: για όνομα και εταιρεία. Τηλέφωνο: για τις SIM και το τέλος της κλήσης. Ειδοποιήσεις: για υπενθυμίσεις και κάρτα. Τίποτα δεν φεύγει από το κινητό.",
                        "Call log: to read the calls. Contacts: for name and company. Phone: for the SIMs and the end of a call. Notifications: for reminders and the card. Nothing leaves the phone."))
                    SectionCard(if (granted) t("✓ Δόθηκαν", "✓ Granted") else t("Χρειάζονται", "Needed")) {
                        Button(onClick = { ask.launch(Perms.CORE + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()) }) { Text(t("Δώσε άδειες", "Grant")) }
                    }
                }
                2 -> { head(t("Πελάτες", "Customers"), t("Φέρε τη λίστα με τις εταιρείες, ή πρόσθεσέ τες αργότερα με το χέρι.", "Load the list of companies, or add them by hand later.")); ListSection(d) }
                3 -> {
                    head(t("Παλιές κλήσεις", "Past calls"), t("Να μπουν οι κλήσεις που έχει ήδη το κινητό; Δεν θα βγουν κάρτες ούτε υπενθυμίσεις για αυτές.", "Add the calls already on the phone? No cards or reminders for them."))
                    SectionCard(imported?.let { t("✓ Μπήκαν $it κλήσεις", "✓ Added $it calls") } ?: t("Ιστορικό", "History")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && granted && imported == null, onClick = { busy = true; scope.launch { imported = withContext(Dispatchers.IO) { CallSync.importHistory(ctx) }; busy = false } }) { Text(t("Εισαγωγή όλων", "Import all")) }
                            OutlinedButton(enabled = !busy && imported == null, onClick = { scope.launch { withContext(Dispatchers.IO) { CallSync.skipHistory(ctx) }; imported = 0 } }) { Text(t("Μόνο από τώρα", "Only from now")) }
                        }
                        if (!granted) Muted(t("Χρειάζεται πρώτα η άδεια για το ιστορικό κλήσεων.", "The call-log permission is needed first."))
                    }
                }
                4 -> { head(t("Εταιρεία από την επαφή", "Company from the contact"), t("Πώς είναι γραμμένες οι επαφές των πελατών σου;", "How are your customers' contacts written?")); MatchSection(d) }
                5 -> { head("SIM", t("Αν έχεις δύο SIM, διάλεξε ποια μετράει.", "With two SIMs, pick the one that counts.")); SimSection(d); Muted(t("Με μία SIM δεν χρειάζεται τίποτα.", "With one SIM nothing is needed."), Modifier.padding(horizontal = 20.dp)) }
                6 -> { head(t("Χρεώσιμος χρόνος", "Billable time"), t("Από πόσο και πάνω μετράει μια κλήση, και πώς στρογγυλεύεται.", "How long a call must be to count, and how it is rounded.")); BillingSection(d) }
                7 -> { head(t("Υπενθυμίσεις", "Reminders"), t("Για αναπάντητες και για εξερχόμενες που δεν απαντήθηκαν.", "For missed calls and for outgoing calls not answered.")); ReminderSection(d) }
                8 -> { head(t("Στο τέλος της κλήσης", "At the end of a call"), t("Μια κάρτα ρωτάει αν ήταν εταιρική, ποια εταιρεία, θέμα και σημείωση.", "A card asks if it was business, which company, topic and note.")); CardSection(d); BatterySection(); CategorySection(d) }
                else -> { head("Backup", t("Αν χαθεί το κινητό, χάνονται και οι καταγραφές. Σύνδεσε το Google Drive για αυτόματο backup, ή κάνε backup σε αρχείο από τις Ρυθμίσεις.", "If the phone is lost, so are the records. Connect Google Drive for automatic backup, or back up to a file in Settings.")); BackupSection(d) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            if (step > 0) TextButton(onClick = { step-- }) { Text(t("Πίσω", "Back")) }
            Spacer(Modifier.weight(1f))
            if (step < last) Button(onClick = {
                if (step == 3 && imported == null) scope.launch { withContext(Dispatchers.IO) { CallSync.skipHistory(ctx) }; imported = 0 }
                step++
            }) { Text(t("Επόμενο", "Next")) }
            else Button(onClick = {
                if (Store.settings.since == 0L) Store.settings { it.copy(since = System.currentTimeMillis()) }
                Store.settings { it.copy(onboarded = true) }
                Jobs.schedule(ctx)
            }) { Text(t("Τέλος", "Done")) }
        }
    }
}
}
