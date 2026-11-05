package gr.wsol.billablecalls.sys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import gr.wsol.billablecalls.core.Company

/**
 * «On site» from the phone's location: only the last location Android already has (no GPS of
 * its own, nothing in the background), compared with the place saved for the company.
 */
object Place {
    const val NEAR_M = 300f

    fun allowed(context: Context) = Perms.has(context, Manifest.permission.ACCESS_COARSE_LOCATION) || Perms.has(context, Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    fun last(context: Context): Location? {
        if (!allowed(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        return runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
    }

    /** Is the phone near the company now? */
    fun near(context: Context, c: Company?): Boolean {
        val lat = c?.lat ?: return false; val lng = c.lng ?: return false
        val here = last(context) ?: return false
        val out = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, lat, lng, out)
        return out[0] <= NEAR_M
    }
}
