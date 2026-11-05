package gr.wsol.billablecalls

import gr.wsol.billablecalls.core.Billing
import gr.wsol.billablecalls.core.Call
import gr.wsol.billablecalls.core.Company
import gr.wsol.billablecalls.core.Data
import gr.wsol.billablecalls.core.Direction
import gr.wsol.billablecalls.core.Export
import gr.wsol.billablecalls.core.Greeklish
import gr.wsol.billablecalls.core.Matcher
import gr.wsol.billablecalls.core.Ops
import gr.wsol.billablecalls.core.Overtime
import gr.wsol.billablecalls.core.Packages
import gr.wsol.billablecalls.core.Period
import gr.wsol.billablecalls.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ProTest {
    private val z = ZoneId.systemDefault()
    private fun ms(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(z).toInstant().toEpochMilli()
    private fun call(id: Long, at: Long, sec: Int, company: String? = "a", contact: String? = null, key: String = "k$id") =
        Call(id, key, key, contact, null, null, Direction.IN, at, sec, company, category = "Υποστήριξη")
    private val a = Company("a", "ΑΒΓ")

    @Test fun outOfHoursTimesTheFactor() {
        val s = Settings(roundMin = 15, overtimeOn = true, overtimeFactor = 1.5)
        // Friday 6/11 at 10:00 is in hours, at 20:00 out, Saturday out
        assertFalse(Overtime.isOut(call(1, ms(6, 10), 600), s))
        assertTrue(Overtime.isOut(call(1, ms(6, 20), 600), s))
        assertTrue(Overtime.isOut(call(1, ms(7, 11), 600), s))
        assertEquals(900, Billing.seconds(call(1, ms(6, 10), 600), a, s))
        assertEquals(1350, Billing.seconds(call(1, ms(6, 20), 600), a, s))
        assertEquals(900, Billing.seconds(call(1, ms(6, 20), 600), a, s.copy(overtimeOn = false)))
    }

    @Test fun packagesAndTheAlertOncePerMonth() {
        val c = a.copy(packageMin = 60)
        var d = Data(companies = listOf(c), calls = listOf(call(1, ms(3, 10), 1700), call(2, ms(4, 10), 1700)), settings = Settings(roundMin = 15, packagesOn = true))
        val st = Packages.state(d, c, ms(5, 10))!!
        assertEquals(3600, st.includedSec); assertEquals(3600, st.usedSec); assertEquals(0, st.overSec)
        assertTrue(Packages.newlyOver(d, ms(5, 10)).second.isEmpty())
        d = d.copy(calls = d.calls + call(3, ms(5, 9), 300))
        val (d2, over) = Packages.newlyOver(d, ms(5, 10))
        assertEquals(listOf("a"), over.map { it.id })
        assertTrue(Packages.newlyOver(d2, ms(5, 11)).second.isEmpty()) // once
        assertNull(Packages.state(d.copy(settings = d.settings.copy(packagesOn = false)), c))
    }

    @Test fun greeklish() {
        assertEquals(Greeklish.skeleton(Matcher.norm("Παπαδόπουλος")), Greeklish.skeleton(Matcher.norm("Papadopoulos")))
        assertEquals(Greeklish.skeleton(Matcher.norm("Ινφολιμπ")), Greeklish.skeleton(Matcher.norm("Infolib")))
        val list = listOf(Company("p", "ΠΑΠΑΔΟΠΟΥΛΟΣ Α.Ε."), Company("i", "INFOLIB ΙΚΕ"))
        assertEquals("p", Matcher.match("Papadopoulos - Nikos", null, list, Settings()).companyId)
        assertNull(Matcher.match("Papadopoulos - Nikos", null, list, Settings(greeklish = false)).companyId)
        // a typo: only a suggestion
        val typo = Matcher.match("Infolibb Γιώργος", null, list, Settings(matchMode = gr.wsol.billablecalls.core.MatchMode.COMPANY_FIELD))
        assertNull(typo.companyId); assertTrue("i" in typo.suggestions)
    }

    @Test fun invoicedCalls() {
        var d = Data(companies = listOf(a), calls = listOf(call(1, ms(3, 10), 600), call(2, ms(4, 10), 600)), settings = Settings(billedOn = true))
        d = Export.markBilled(d, setOf(1L), ms(5, 10))
        val p = Period(java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 30))
        assertEquals(listOf(2L), Export.calls(d, p, null, onlyUnbilled = true).map { it.id })
        assertTrue(Export.rows(d, Export.calls(d, p, null)).first().contains("Τιμολογήθηκε"))
    }

    @Test fun theWholeContactMoves() {
        // the same contact from two numbers: «all of the contact» moves both
        val d = Data(companies = listOf(a), calls = listOf(call(1, ms(3, 10), 60, null, "Κώστας", "111"), call(2, ms(4, 10), 60, null, "Κώστας", "222"), call(3, ms(4, 11), 60, null, "Μαρία", "333")))
        val d2 = Ops.assign(d, setOf(1L), "a", forNumbers = true)
        assertEquals(listOf("a", "a", null), d2.calls.map { it.companyId })
        assertEquals(setOf("111", "222"), d2.rules.map { it.key }.toSet())
    }

    @Test fun reviewQueuesTheWebhook() {
        val d = Data(companies = listOf(a), calls = listOf(call(1, ms(3, 10), 600)), settings = Settings(webhookOn = true, webhookUrl = "https://n8n.example/hook"))
        assertEquals(listOf(1L), Ops.review(d, 1, true, "a", false, "Υποστήριξη", null, "ok").pendingHooks)
        assertTrue(Ops.review(d.copy(settings = d.settings.copy(webhookOn = false)), 1, true, "a", false, null, null, null).pendingHooks.isEmpty())
    }

    @Test fun backToBackCallsBilledAsOne() {
        val s = Settings(roundMin = 15, minSec = 60, mergeOn = true, mergeMin = 20)
        val first = call(1, ms(3, 10), 240, key = "111")
        val second = call(2, first.end + 3 * 60_000, 180, key = "111")
        var d = Data(companies = listOf(a), calls = listOf(first, second), settings = s)
        assertEquals(1L, Billing.mergeCandidate(second, d)?.id)
        assertEquals(1800, d.calls.sumOf { Billing.seconds(it, d) }) // apart: 15′ + 15′
        d = Ops.merge(d, 2, 1)
        assertEquals(900, Billing.seconds(d.calls[0], d)); assertEquals(0, Billing.seconds(d.calls[1], d)) // together: one 15′ block
        // too late for the window: no offer
        assertNull(Billing.mergeCandidate(call(3, first.end + 40 * 60_000, 60, key = "111"), d.copy(calls = listOf(first))))
    }

    @Test fun onSiteFactor() {
        val s = Settings(roundMin = 15, onsiteOn = true, onsiteFactor = 2.0)
        assertEquals(1800, Billing.seconds(call(1, ms(3, 10), 600).copy(onsite = true), a, s))
        assertEquals(900, Billing.seconds(call(1, ms(3, 10), 600), a, s))
    }

    @Test fun beforeYouAnswer() {
        val c = a.copy(packageMin = 300)
        val d = Data(companies = listOf(c), settings = Settings(packagesOn = true),
            calls = listOf(call(1, ms(3, 10), 600, key = "6971111111").copy(note = "Πρόβλημα με Exchange")),
            followUps = listOf(gr.wsol.billablecalls.core.FollowUp("f", "a", 1, null, "", "Να στείλω προσφορά", null)))
        val lines = gr.wsol.billablecalls.sys.Hud.lines(d, "+30 697 111 1111", "Νίκος", null)!!
        assertTrue(lines[0].contains("ΑΒΓ")); assertTrue(lines.any { it.contains("Exchange") }); assertTrue(lines.any { it.contains("προσφορά") }); assertTrue(lines.any { it.contains("Πακέτο") })
        assertNull(gr.wsol.billablecalls.sys.Hud.lines(d, "6999999999", null, null))
    }
}
