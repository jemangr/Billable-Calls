package gr.wsol.billablecalls

import gr.wsol.billablecalls.core.Report
import gr.wsol.billablecalls.sys.Shake
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportShakeTest {
    @Test fun payloadHasTheAppAndOnlyTheEmailTyped() {
        val j = JSONObject(Report.payload("it closed", "  me@example.com "))
        assertEquals("billable-calls", j.getString("app"))
        assertEquals("it closed", j.getString("text"))
        assertEquals("me@example.com", j.getString("email"))
        assertEquals("", j.getString("website"))
        assertEquals("", JSONObject(Report.payload("x", "")).getString("email"))
    }

    @Test fun threeStrongShakesFireOnceThenRest() {
        var fired = 0
        val s = Shake { fired++ }
        // walking or a tap on the table: never
        listOf(1.0, 1.4, 2.0, 1.1).forEachIndexed { i, g -> assertFalse(s.feed(g, 100L * i)) }
        // three strong shakes within a second: once
        assertFalse(s.feed(3.0, 1000)); assertFalse(s.feed(3.2, 1300)); assertTrue(s.feed(3.1, 1600))
        // again right away: not before the pause
        assertFalse(s.feed(3.0, 2000)); assertFalse(s.feed(3.0, 2200)); assertFalse(s.feed(3.0, 2400))
        // shakes spread out: no
        assertFalse(s.feed(3.0, 10_000)); assertFalse(s.feed(3.0, 12_000)); assertFalse(s.feed(3.0, 14_000))
        assertEquals(0, fired) // feed() only reports; onShake is called by the sensor callback
    }
}
