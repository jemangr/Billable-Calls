package gr.wsol.billablecalls

import android.app.Application
import gr.wsol.billablecalls.core.Crash
import gr.wsol.billablecalls.core.Store
import gr.wsol.billablecalls.sys.InstantService
import gr.wsol.billablecalls.sys.Jobs
import gr.wsol.billablecalls.sys.Notify

class BillableApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Store.init(this)
        Notify.channels(this)
        if (Store.settings.onboarded) {
            runCatching { Jobs.schedule(this) }
            if (Store.settings.instantCard) InstantService.start(this)
        }
    }
}
