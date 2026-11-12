package gr.wsol.billablecalls

import gr.wsol.billablecalls.core.Lang
import gr.wsol.billablecalls.core.n
import gr.wsol.billablecalls.core.topic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LangTest {
    @After fun greek() { Lang.english = false }

    @Test fun countsUseTheRightNumber() {
        Lang.english = true
        assertEquals("1 call", n(1, "κλήση", "κλήσεις", "call", "calls"))
        assertEquals("0 calls", n(0, "κλήση", "κλήσεις", "call", "calls"))
        assertEquals("3 calls", n(3, "κλήση", "κλήσεις", "call", "calls"))
        Lang.english = false
        assertEquals("1 κλήση", n(1, "κλήση", "κλήσεις", "call", "calls"))
        assertEquals("2 κλήσεις", n(2, "κλήση", "κλήσεις", "call", "calls"))
    }

    @Test fun builtInTopicsFollowTheLanguage() {
        Lang.english = true
        assertEquals("Support", topic("Υποστήριξη"))
        assertEquals("Other", topic("Άλλο"))
        assertEquals("Λογιστικά", topic("Λογιστικά")) // a topic you added stays as written
        Lang.english = false
        assertEquals("Υποστήριξη", topic("Υποστήριξη"))
    }
}
