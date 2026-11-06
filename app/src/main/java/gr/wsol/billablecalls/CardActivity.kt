package gr.wsol.billablecalls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.sys.Card
import gr.wsol.billablecalls.sys.Notify
import gr.wsol.billablecalls.ui.BillableTheme
import gr.wsol.billablecalls.ui.CallCardContent

/** The after-call card as a small window over whatever is on screen; several calls one after the other. */
class CardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        // The window keeps one size (the whole screen) and the card sits at the bottom, above the keyboard.
        // Before, the window was as tall as the card: every new line of the note resized and re-centred it,
        // and the keyboard was taken into account twice (adjustResize + imePadding), so the screen jumped.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val ids = intent.getLongArrayExtra("ids")?.toList().orEmpty().filter { id -> Store.data.calls.any { it.id == id && !it.reviewed } }
        if (ids.isEmpty()) { finish(); return }
        ids.forEach { Notify.cancel(this, Card.nid(it)) }
        setContent {
            BillableTheme {
                var i by remember { mutableIntStateOf(0) }
                // the only place the keyboard is taken into account: the card stays above it and scrolls inside
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                        CallCardContent(ids[i], counter = if (ids.size > 1) "${i + 1}/${ids.size}" else null) {
                            if (i + 1 < ids.size) i++ else finish()
                        }
                    }
                }
            }
        }
    }
}
