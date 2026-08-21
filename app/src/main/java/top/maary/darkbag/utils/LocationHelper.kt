package top.maary.darkbag.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Helper class for managing location updates and retrieving the latest GPS/network
 * location snapshot without external GMS dependencies.
 */
class LocationHelper(private val context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @Volatile
    private var cachedLocation: Location? = null

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateIfBetterLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * Checks if either fine or coarse location permission is granted.
     */
    fun hasPermission(): Boolean = hasPermission(appContext)

    /**
     * Checks if fine location permission is granted.
     */
    fun hasFinePermission(): Boolean = hasFineLocationPermission(appContext)

    /**
     * Starts listening for location updates if permissions are granted and provider is enabled.
     */
    @Synchronized
    fun startListening() {
        if (isListening) return
        if (!hasPermission() || locationManager == null) return

        try {
            if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                Log.w(TAG, "Location is disabled in system settings")
                return
            }

            // Immediately check last known location from all available providers
            queryLastKnownLocations()

            val minTimeMs = 5000L
            val minDistanceM = 5f
            val hasFine = hasFinePermission()
            var registeredAny = false

            // Register GPS provider (requires FINE_LOCATION)
            if (hasFine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        minTimeMs,
                        minDistanceM,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    registeredAny = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException while requesting GPS updates", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register GPS updates", e)
                }
            }

            // Register Network provider (works with COARSE or FINE)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                try {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        minTimeMs,
                        minDistanceM,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    registeredAny = true
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException while requesting Network updates", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register Network updates", e)
                }
            }

            // Register Fused provider if available (Android 12+ / API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                        locationManager.requestLocationUpdates(
                            LocationManager.FUSED_PROVIDER,
                            minTimeMs,
                            minDistanceM,
                            locationListener,
                            Looper.getMainLooper()
                        )
                        registeredAny = true
                    }
                } catch (e: Throwable) {
                    // Ignore if FUSED_PROVIDER is not supported by device hardware
                }
            }

            isListening = registeredAny
            if (registeredAny) {
                Log.d(TAG, "Started location updates")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location listening", e)
        }
    }

    /**
     * Stops listening for location updates.
     */
    @Synchronized
    fun stopListening() {
        if (!isListening) return
        try {
            locationManager?.removeUpdates(locationListener)
            isListening = false
            Log.d(TAG, "Stopped location updates")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location updates", e)
        }
    }

    /**
     * Returns the latest available location snapshot.
     */
    fun getCurrentLocation(): Location? {
        if (!hasPermission() || locationManager == null) return null

        queryLastKnownLocations()
        return cachedLocation
    }

    private fun queryLastKnownLocations() {
        if (!hasPermission() || locationManager == null) return

        try {
            val providers = listOfNotNull(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        val loc = locationManager.getLastKnownLocation(provider)
                        if (loc != null) {
                            updateIfBetterLocation(loc)
                        }
                    }
                } catch (e: SecurityException) {
                    // Permission not granted for this provider
                } catch (e: Exception) {
                    // Provider unavailable
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying last known location", e)
        }
    }

    private fun updateIfBetterLocation(newLocation: Location) {
        // Filter out ancient location fixes (older than MAX_STALE_AGE_MS)
        val age = System.currentTimeMillis() - newLocation.time
        if (age > MAX_STALE_AGE_MS || age < -TWO_MINUTES) {
            return
        }

        val current = cachedLocation
        if (current == null) {
            cachedLocation = newLocation
            return
        }

        val timeDelta = newLocation.time - current.time
        val isSignificantlyNewer = timeDelta > TWO_MINUTES
        val isSignificantlyOlder = timeDelta < -TWO_MINUTES
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) {
            cachedLocation = newLocation
            return
        } else if (isSignificantlyOlder) {
            return
        }

        val accuracyDelta = (newLocation.accuracy - current.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        val isSameProvider = newLocation.provider == current.provider

        if (isMoreAccurate || (isNewer && !isLessAccurate) || (isNewer && !isSignificantlyLessAccurate && isSameProvider)) {
            cachedLocation = newLocation
        }
    }

    companion object {
        private const val TAG = "LocationHelper"
        private const val TWO_MINUTES = 1000 * 60 * 2
        private const val MAX_STALE_AGE_MS = 1000 * 60 * 30 // 30 minutes

        fun hasPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            return fine || coarse
        }

        fun hasFineLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasMediaLocationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        }
    }
}
