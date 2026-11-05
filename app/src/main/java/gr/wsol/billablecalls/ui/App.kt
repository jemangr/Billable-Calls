package gr.wsol.billablecalls.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.core.t

private val Brand = Color(0xFF1F5EFF)

@Composable
fun BillableTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFF8FB0FF), secondary = Color(0xFFFFC83D), background = Color(0xFF111318), surface = Color(0xFF1B1E25))
    else lightColorScheme(primary = Brand, secondary = Color(0xFFB07800), background = Color(0xFFF6F7FB), surface = Color.White, surfaceVariant = Color(0xFFE9ECF4))
    MaterialTheme(colorScheme = colors, content = content)
}

enum class Tab { TODAY, CALLS, COMPANIES, MISSED, SETTINGS }

@Composable
fun App(startTab: Tab = Tab.TODAY) {
    val d by Store.flow.collectAsState()
    if (!d.settings.onboarded) { Onboarding(); return }
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var company by rememberSaveable { mutableStateOf<String?>(null) }
    val missed = d.reminders.count { !it.stopped }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(Tab.TODAY, t("Σήμερα", "Today"), Icons.Default.Today),
                    Triple(Tab.CALLS, t("Κλήσεις", "Calls"), Icons.Default.Call),
                    Triple(Tab.COMPANIES, t("Εταιρείες", "Companies"), Icons.Default.Business),
                    Triple(Tab.MISSED, t("Αναπάντ.", "Missed"), Icons.Default.PhoneMissed),
                    Triple(Tab.SETTINGS, t("Ρυθμίσεις", "Settings"), Icons.Default.Settings),
                ).forEach { (tb, label, icon) ->
                    val badge = if (tb == Tab.MISSED) missed else 0
                    NavigationBarItem(selected = tab == tb, onClick = { tab = tb; company = null; editing = null }, label = { Text(label, maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                        icon = { if (badge > 0) BadgedBox(badge = { Badge { Text("$badge") } }) { Icon(icon, null) } else Icon(icon, null) })
                }
            }
        },
    ) { pad ->
        // imePadding: when the keyboard opens, the screen moves up above it
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when {
                editing != null -> CallEditor(editing!!, onClose = { editing = null })
                tab == Tab.TODAY -> TodayScreen(onCall = { editing = it }, onMissed = { tab = Tab.MISSED })
                tab == Tab.CALLS -> CallsScreen(onCall = { editing = it })
                tab == Tab.COMPANIES -> if (company == null) CompaniesScreen(onOpen = { company = it }) else CompanyScreen(company!!, onCall = { editing = it }, onBack = { company = null })
                tab == Tab.MISSED -> MissedScreen()
                else -> SettingsScreen()
            }
        }
    }
}
