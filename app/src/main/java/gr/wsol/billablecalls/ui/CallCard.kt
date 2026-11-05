@file:OptIn(ExperimentalLayoutApi::class)

package gr.wsol.billablecalls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t

/** Saves what the card says about a call: business or not, which company, topic, billable, note. */
fun saveCard(call: Call, business: Boolean, companyId: String?, forNumber: Boolean, category: String?, billable: Boolean?, note: String) {
    Store.update { d0 ->
        var d = d0
        val target = if (business) companyId else null
        if (target != call.companyId || (!business) != call.personal || forNumber && d.rule(call.key)?.companyId != target) {
            if (!business || target != null) d = Ops.assign(d, setOf(call.id), target, forNumber)
        }
        Ops.edit(d, call.id) { it.copy(category = category, billable = billable, note = note.trim(), reviewed = true) }
    }
}

/**
 * The card of one call (after the call, or opened from the lists): «Business call?», the company
 * list with search, topic, billable yes/no, note.
 */
@Composable
fun CallCardContent(callId: Long, counter: String? = null, onDone: () -> Unit) {
    val d by Store.flow.collectAsState()
    val call = d.calls.firstOrNull { it.id == callId } ?: run { androidx.compose.runtime.LaunchedEffect(callId) { onDone() }; return }
    val suggestions = remember(callId) { Matcher.match(call.contact, call.org, d.companies, d.settings).suggestions }
    var business by remember(callId) { mutableStateOf(!call.personal) }
    var companyId by remember(callId) { mutableStateOf(call.companyId) }
    var forNumber by remember(callId) { mutableStateOf(true) }
    var category by remember(callId) { mutableStateOf(call.category) }
    var billable by remember(callId) { mutableStateOf(call.billable) }
    var note by remember(callId) { mutableStateOf(call.note) }
    var picking by remember(callId) { mutableStateOf(call.companyId == null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(call.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Muted("${if (call.contact != null) call.number + " · " else ""}${Export.direction(call.direction)} · ${dayHm(call.start)}–${hm(call.end)} · ${Billing.hms(call.durationSec)}")
            }
            counter?.let { Muted(it) }
        }
        Text(t("Εταιρική κλήση;", "Business call?"), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(business, { business = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(t("Ναι", "Yes")) }
            SegmentedButton(!business, { business = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(t("Όχι, προσωπική", "No, personal")) }
        }
        if (business) {
            val c = d.company(companyId)
            if (c != null && !picking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { picking = true }) { Text(t("Αλλαγή", "Change")) }
                }
            } else {
                CompanyPicker(d.companies, companyId, suggestions,
                    onAdd = { name -> var made: String? = null; Store.update { x -> Ops.addCompany(x, name).also { made = it.second.id }.first }; companyId = made; picking = false },
                    onPick = { companyId = it.id; picking = false })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(forNumber, { forNumber = it })
                Text(t("Ισχύει και για τις επόμενες κλήσεις αυτού του αριθμού", "Also for the next calls of this number"), style = MaterialTheme.typography.bodyMedium)
            }
            Text(t("Θέμα", "Topic"), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.settings.categories.forEach { cat -> FilterChip(category == cat, { category = if (category == cat) null else cat }, label = { Text(cat) }) }
            }
            Text(t("Χρεώσιμη", "Billable"), style = MaterialTheme.typography.titleSmall)
            val auto = call.copy(companyId = companyId, personal = false, billable = null).let { Billing.counts(it, d.company(companyId), d.settings) }
            Choice(listOf<Pair<Boolean?, String>>(null to t("Αυτόματα (${if (auto) "ναι" else "όχι"})", "Auto (${if (auto) "yes" else "no"})"), true to t("Ναι", "Yes"), false to t("Όχι", "No")), billable) { billable = it }
            val sec = Billing.seconds(call.copy(companyId = companyId, personal = false, billable = billable), d.company(companyId), d.settings)
            Muted(t("Χρεώσιμος χρόνος: ", "Billable time: ") + Billing.hms(sec))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(forNumber, { forNumber = it })
                Text(t("Και οι επόμενες κλήσεις του αριθμού ως προσωπικές", "The next calls of this number as personal too"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text(t("Σημείωση: τι συζητήθηκε", "Note: what was discussed")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { Store.update { Ops.edit(it, call.id) { c -> c.copy(reviewed = true) } }; onDone() }) { Text(t("Παράλειψη", "Skip")) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { saveCard(call, business, companyId, forNumber, category, billable, note); onDone() }, enabled = !business || companyId != null) { Text(t("Αποθήκευση", "Save")) }
        }
    }
}

/** The same card, inside the app (from the lists). */
@Composable
fun CallEditor(id: Long, onClose: () -> Unit) {
    androidx.activity.compose.BackHandler { onClose() }
    CallCardContent(id, onDone = onClose)
}
