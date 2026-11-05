package gr.wsol.billablecalls

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.captureRoboImage
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.How
import gr.wsol.billablecalls.core.Lang
import gr.wsol.billablecalls.core.Reminder
import gr.wsol.billablecalls.core.Settings
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.ui.App
import gr.wsol.billablecalls.ui.BillableTheme
import gr.wsol.billablecalls.ui.CallCardContent
import gr.wsol.billablecalls.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The screens on the JVM: they open without errors, the main actions work, and screenshots to check by eye. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private fun call(id: Long, who: String?, number: String, company: String?, min: Int, dir: Direction = Direction.IN, ago: Int = 60, note: String = "") =
        Call(id, number, number, who, null, "sim1", dir, now - ago * 60_000L, min * 60 + 12, company, how = if (company != null) How.NAME else How.NONE, note = note, reviewed = true)

    private fun sample(english: Boolean = false) = Data(
        companies = listOf(Company("a", "ΑΒΓ Α.Ε.", listOf("Φούρνος Νίκου")), Company("d", "ΔΕΛΤΑ ΕΠΕ"), Company("e", "Ελαιώνας ΙΚΕ")),
        calls = listOf(
            call(1, "ΑΒΓ - Νίκος", "6971111111", "a", 14, note = "ΦΠΑ τριμήνου"),
            call(2, "Γιώργος Δέλτα", "2103333333", "d", 3, Direction.OUT, 90),
            call(3, null, "6999999999", null, 0, Direction.MISSED, 30),
            call(4, "Μαμά", "6972222222", null, 7, ago = 120).copy(personal = true),
            call(5, "Ελαιώνας - Μαρία", "2109999999", "e", 32, ago = 200),
            call(6, "Πέτρος", "6944444444", null, 5, ago = 20).copy(reviewed = false),
        ),
        reminders = listOf(Reminder("6999999999", "6999999999", "6999999999", null, Direction.MISSED, now - 30 * 60_000L, count = 1, nextAt = now + 20 * 60_000L)),
        settings = Settings(english = english, onboarded = true, since = now - 86_400_000L),
    )

    @Before fun data() { Store.inMemory(sample()) }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { BillableTheme { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screens/$name.png")
    }

    private fun tap(text: String) { compose.onAllNodesWithText(text, substring = true)[0].performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle() }

    @Test fun today() = shot("01-today") { App(Tab.TODAY) }
    @Test fun calls() = shot("02-calls") { App(Tab.CALLS) }
    @Test fun companies() = shot("04-companies") { App(Tab.COMPANIES) }
    @Test fun missed() = shot("05-missed") { App(Tab.MISSED) }
    @Test fun settings() = shot("06-settings") { App(Tab.SETTINGS) }
    @Test fun card() = shot("07-card") { CallCardContent(6) {} }
    @Test fun english() { Store.inMemory(sample(english = true)); shot("08-today-en") { App(Tab.TODAY) } }
    @Test fun onboarding() { Store.inMemory(Data()); shot("09-onboarding") { App() } }

    @Test fun otherTabAndAddToCompany() {
        shot("03-calls-other") { App(Tab.CALLS) }
        tap("Άλλες")
        compose.onRoot().captureRoboImage("build/screens/03-calls-other.png")
        compose.onAllNodesWithText("Πέτρος")[0].performTouchInput { longClick() }
        compose.waitForIdle()
        tap("Προσθήκη σε εταιρεία")
        compose.onRoot().captureRoboImage("build/screens/03-calls-pick.png")
        tap("ΔΕΛΤΑ ΕΠΕ")
        tap("Όλες της επαφής")
        assertEquals("d", Store.data.calls.first { it.id == 6L }.companyId)
        assertEquals("d", Store.data.rule("6944444444")?.companyId)
    }

    @Test fun removeFromBusiness() {
        shot("02-calls") { App(Tab.CALLS) }
        compose.onAllNodesWithText("Γιώργος Δέλτα")[0].performTouchInput { longClick() }
        compose.waitForIdle()
        tap("Αφαίρεση")
        tap("Μόνο αυτές")
        val c = Store.data.calls.first { it.id == 2L }
        assertTrue(c.personal); assertEquals(null, c.companyId)
    }

    @Test fun cardSavesCompanyAndNote() {
        shot("07-card") { CallCardContent(6) {} }
        tap("ΑΒΓ Α.Ε.")
        // «Υποστήριξη» is already chosen (the default topic)
        tap("Αποθήκευση")
        val c = Store.data.calls.first { it.id == 6L }
        assertEquals("a", c.companyId); assertEquals("Υποστήριξη", c.category); assertTrue(c.reviewed)
        assertEquals("a", Store.data.rule("6944444444")?.companyId)
    }

    @Test fun allOnboardingPages() {
        Store.inMemory(Data())
        compose.setContent { BillableTheme { App() } }
        repeat(9) { i ->
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/screens/onboarding-$i.png")
            tap("Επόμενο")
        }
        tap("Τέλος")
        assertTrue(Store.settings.onboarded)
        Lang.english = false
    }
}
