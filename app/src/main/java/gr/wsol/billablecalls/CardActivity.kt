package gr.wsol.billablecalls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        Notify.cancel(this, Card.NID)
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
        setContent {
            BillableTheme {
                var i by remember { mutableIntStateOf(0) }
                Surface(Modifier.fillMaxWidth().padding(8.dp), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                    CallCardContent(ids[i], counter = if (ids.size > 1) "${i + 1}/${ids.size}" else null) {
                        if (i + 1 < ids.size) i++ else finish()
                    }
                }
            }
        }
    }
}
