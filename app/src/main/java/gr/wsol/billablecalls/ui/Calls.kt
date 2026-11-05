@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package gr.wsol.billablecalls.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t

enum class Range { TODAY, WEEK, MONTH, LAST_MONTH, ALL }

fun Range.period() = when (this) {
    Range.TODAY -> Period.today(); Range.WEEK -> Period.week(); Range.MONTH -> Period.month(); Range.LAST_MONTH -> Period.lastMonth(); Range.ALL -> Period.ALL
}

fun Range.label() = when (this) {
    Range.TODAY -> t("Σήμερα", "Today"); Range.WEEK -> t("Εβδομάδα", "Week"); Range.MONTH -> t("Μήνας", "Month"); Range.LAST_MONTH -> t("Προηγ. μήνας", "Last month"); Range.ALL -> t("Όλες", "All")
}

fun arrow(dir: Direction) = when (dir) { Direction.IN -> "↙"; Direction.OUT -> "↗"; Direction.MISSED, Direction.REJECTED -> "✕↙"; Direction.OUT_NO_ANSWER -> "✕↗" }

/** One call as a row: contact name first, then company, day and time, duration, billable. */
@Composable
fun CallRow(c: Call, d: Data, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val bill = Billing.seconds(c, d)
    Row(
        Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val company = d.company(c.companyId)?.name ?: if (c.personal) t("Προσωπική", "Personal") else t("Χωρίς εταιρεία", "No company")
            Text("${arrow(c.direction)} $company${c.category?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall,
                color = if (c.business) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.note.isNotBlank()) Muted("✎ " + c.note.take(80))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(dayHm(c.start), style = MaterialTheme.typography.bodySmall)
            Text(Billing.hms(c.durationSec), style = MaterialTheme.typography.bodySmall)
            val flags = (if (gr.wsol.billablecalls.core.Overtime.isOut(c, d.settings)) "⏰" else "") + (if (d.settings.billedOn && c.billedAt != null) "🧾" else "")
            if (flags.isNotEmpty()) Text(flags, style = MaterialTheme.typography.bodySmall)
            if (bill > 0) Text("⏱ " + Billing.hms(bill), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider()
}

/**
 * The calls: «Business» and «Other» tabs. Long press selects; from «Business» you remove calls (they
 * become personal), from «Other» you add them to a company. Both ask: only these, or every call of
 * these numbers (and the next ones)?
 */
@Composable
fun CallsScreen(onCall: (Long) -> Unit) {
    val d by Store.flow.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var range by rememberSaveable { mutableStateOf(Range.MONTH) }
    var other by rememberSaveable { mutableStateOf(0) } // 0 all, 1 no company, 2 personal
    var sim by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var picking by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<String?>(null) } // company id to move to, "" = personal
    val p = range.period()
    val calls = d.calls.filter { c ->
        p.contains(c.start) && (sim == null || c.sim == sim) && if (tab == 0) c.business else !c.business && when (other) { 1 -> !c.personal; 2 -> c.personal; else -> true }
    }.sortedByDescending { it.start }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            Tab(tab == 0, { tab = 0; selected = emptySet() }, text = { Text(t("Εταιρικές", "Business")) })
            Tab(tab == 1, { tab = 1; selected = emptySet() }, text = { Text(t("Άλλες", "Other")) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Range.entries.forEach { r -> FilterChip(range == r, { range = r }, label = { Text(r.label()) }) }
        }
        if (tab == 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(t("Όλες", "All"), t("Χωρίς εταιρεία", "No company"), t("Προσωπικές", "Personal")).forEachIndexed { i, l -> FilterChip(other == i, { other = i }, label = { Text(l) }) }
        }
        val sims = d.calls.mapNotNull { it.sim }.distinct()
        if (sims.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(sim == null, { sim = null }, label = { Text(t("Όλες οι SIM", "All SIMs")) })
            sims.forEachIndexed { i, s -> FilterChip(sim == s, { sim = s }, label = { Text("SIM ${i + 1}") }) }
        }
        if (selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, t("Ακύρωση", "Cancel")) }
                Text(t("${selected.size} επιλεγμένες", "${selected.size} selected"), Modifier.weight(1f))
                if (tab == 0) TextButton(onClick = { ask = "" }) { Text(t("Αφαίρεση", "Remove")) }
                else TextButton(onClick = { picking = true }) { Text(t("Προσθήκη σε εταιρεία", "Add to company")) }
            }
        } else {
            val bill = calls.sumOf { Billing.seconds(it, d) }
            Muted(if (tab == 0) t("${calls.size} κλήσεις · χρεώσιμος χρόνος ${Billing.nice(bill)} · κράτα πατημένο για επιλογή", "${calls.size} calls · billable ${Billing.nice(bill)} · long-press to select")
                else t("${calls.size} κλήσεις · κράτα πατημένο για επιλογή", "${calls.size} calls · long-press to select"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (picking) {
            Column(Modifier.padding(16.dp)) {
                Text(t("Σε ποια εταιρεία;", "Which company?"), style = MaterialTheme.typography.titleLarge)
                CompanyPicker(d.companies, null, onAdd = { name -> var id = ""; Store.update { x -> Ops.addCompany(x, name).also { id = it.second.id }.first }; picking = false; ask = id },
                    onPick = { picking = false; ask = it.id })
                TextButton(onClick = { picking = false }) { Text(t("Άκυρο", "Cancel")) }
            }
            return@Column
        }
        if (calls.isEmpty()) Muted(t("Καμία κλήση σε αυτή την περίοδο.", "No calls in this period."), Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(calls, key = { it.id }) { c ->
                CallRow(c, d, c.id in selected,
                    onClick = { if (selected.isNotEmpty()) selected = if (c.id in selected) selected - c.id else selected + c.id else onCall(c.id) },
                    onLong = { selected = if (c.id in selected) selected - c.id else selected + c.id })
            }
        }
    }
    ask?.let { target ->
        val n = selected.size
        AlertDialog(onDismissRequest = { ask = null },
            title = { Text(if (target.isEmpty()) t("Αφαίρεση από τις εταιρικές", "Remove from business") else d.company(target)?.name.orEmpty()) },
            text = { Text(t("Το ίδιο για όλες τις κλήσεις της επαφής (με όλους τους αριθμούς της, παλιές και επόμενες), ή μόνο για τις $n επιλεγμένες;", "The same for every call of the contact (all its numbers, past and next), or only the $n selected?")) },
            confirmButton = {
                Button(onClick = { Store.update { Ops.assign(it, selected, target.ifEmpty { null }, forNumbers = true) }; selected = emptySet(); ask = null }) { Text(t("Όλες της επαφής", "All of the contact")) }
            },
            dismissButton = {
                TextButton(onClick = { Store.update { Ops.assign(it, selected, target.ifEmpty { null }, forNumbers = false) }; selected = emptySet(); ask = null }) { Text(t("Μόνο αυτές", "Only these")) }
            })
    }
}
