package com.visus.app.service

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.visus.app.data.AuthState
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Automatically reports GPS location to server every 2 minutes for blind users.
 */
object LocationReportService {
    private const val TAG = "LocationReport"
    private var scope: CoroutineScope? = null
    private var locationManager: LocationManager? = null
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var running = false

    fun init(context: Context) {
        if (running) return
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastLat = loc.latitude; lastLng = loc.longitude
                scope?.launch { reportToServer() }
            }
            override fun onProviderDisabled(p: String) {}
            override fun onProviderEnabled(p: String) {}
            override fun onStatusChanged(p: String, s: Int, e: Bundle?) {}
        }

        val looper = Looper.getMainLooper()
        try {
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000L, 50f, locationListener, looper)
        } catch (_: SecurityException) {}
        try {
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000L, 100f, locationListener, looper)
        } catch (_: SecurityException) {}

        // Get last known immediately
        try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                locationManager?.getLastKnownLocation(p)?.let { locationListener.onLocationChanged(it) }
            }
        } catch (_: SecurityException) {}

        // Periodic fallback
        scope?.launch {
            while (isActive) { delay(120_000); reportToServer() }
        }
    }

    private suspend fun reportToServer() {
        if (lastLat == 0.0 && lastLng == 0.0) return
        try {
            val token = AuthState.token.value ?: return
            if (AuthState.userType.value != "blind") return
            SocialApiClient.post("/api/status/update/", JSONObject().apply {
                put("status", true)
                put("latitude", lastLat)
                put("longitude", lastLng)
                put("city", "")
                put("alert_type", "auto_location")
                put("note", "auto")
            }, token)
        } catch (_: Exception) {}
    }
}
