/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.iappyx.launcher.GeofenceTransitionReceiver
import com.iappyx.launcher.model.Profile
import com.iappyx.launcher.model.ProfileTrigger

/**
 * Registers / deregisters profile-bound geofences with Google Play
 * Services. Mirrors the registration pattern used by the widget trigger
 * system (see WidgetHost.registerGeofenceWithPlayServices) but uses a
 * `profile:{slug}` request-id prefix so [GeofenceTransitionReceiver]
 * can route events back to the profile system rather than the widget
 * trigger store.
 *
 * All calls are best-effort: missing permissions, no Play Services, or
 * a malformed geofence are logged and swallowed. Profile auto-activation
 * gracefully degrades to the next-priority trigger when registration
 * silently fails.
 */
object ProfileGeofenceManager {

    private const val ID_PREFIX = "profile:"
    private const val TAG = "iappyxProfileGeofence"

    fun requestId(slug: String): String = ID_PREFIX + slug
    fun isProfileFenceId(id: String): Boolean = id.startsWith(ID_PREFIX)
    fun slugFromFenceId(id: String): String? =
        if (id.startsWith(ID_PREFIX)) id.removePrefix(ID_PREFIX) else null

    /** Register the geofence for [profile] (no-op when the trigger isn't
     *  Geofence). Idempotent — Play Services replaces an existing fence
     *  with the same request id.
     *
     *  Geofences only fire when the app holds ACCESS_FINE_LOCATION and (on
     *  API 29+) ACCESS_BACKGROUND_LOCATION. Without them, `addGeofences`
     *  fails ASYNCHRONOUSLY — so the previous code, which logged "registered"
     *  on the synchronous return, silently lied. We now check the permissions
     *  up front and attach success/failure listeners, reporting the real
     *  outcome via [onResult] `(ok, error)` so the caller can surface it.
     */
    fun register(
        context: Context,
        profile: Profile,
        onResult: ((ok: Boolean, error: String?) -> Unit)? = null,
    ) {
        val t = profile.trigger as? ProfileTrigger.Geofence ?: return
        val appCtx = context.applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "register ${profile.slug}: no fine location")
            onResult?.invoke(false, "location-off")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "register ${profile.slug}: no background location")
            onResult?.invoke(false, "needs-background-location")
            return
        }
        try {
            val fence = Geofence.Builder()
                .setRequestId(requestId(profile.slug))
                .setCircularRegion(t.latitude, t.longitude, t.radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
                )
                .build()
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(fence)
                .build()
            LocationServices.getGeofencingClient(appCtx)
                .addGeofences(req, pendingIntent(context))
                .addOnSuccessListener {
                    Log.i(TAG, "registered ${profile.slug}")
                    onResult?.invoke(true, null)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "register failed (async) ${profile.slug}: ${e.message}")
                    onResult?.invoke(false, e.message ?: "registration-failed")
                }
        } catch (se: SecurityException) {
            Log.w(TAG, "register failed (permission): ${se.message}")
            onResult?.invoke(false, se.message ?: "permission")
        } catch (e: Throwable) {
            Log.w(TAG, "register failed: ${e.message}")
            onResult?.invoke(false, e.message ?: "error")
        }
    }

    /** Deregister the geofence for [slug]. Safe to call for slugs that
     *  were never registered (Play Services tolerates the unknown id). */
    fun unregister(context: Context, slug: String) {
        try {
            LocationServices.getGeofencingClient(context.applicationContext)
                .removeGeofences(listOf(requestId(slug)))
            ProfileGeofenceState.forget(context, slug)
            Log.i(TAG, "unregistered $slug")
        } catch (e: Throwable) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
    }

    /** Re-register every currently-saved profile that uses a Geofence
     *  trigger. Called from LauncherActivity.onCreate so a device
     *  reboot (which clears Play Services' geofence registry) doesn't
     *  silently break auto-switch. */
    fun reRegisterAll(context: Context) {
        for (profile in ProfileLibrary.all(context)) register(context, profile)
    }

    /** Pending intent every profile geofence shares — Play Services
     *  fans out to the same broadcast receiver, which inspects the
     *  request id to distinguish profile vs widget fences. */
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceTransitionReceiver::class.java)
            .setAction(GeofenceTransitionReceiver.ACTION)
            // Explicit identifier so Android 14+ can propagate sender identity
            // for the broadcast (matches the system's `scheduleRegisteredReceiver`
            // identity-propagation expectation). Without this we get a noisy
            // E ActivityThread warning on every geofence transition. Play
            // Services Geofencing requires FLAG_MUTABLE (it fills in event
            // extras), so we can't switch to FLAG_IMMUTABLE — the identifier
            // is the right knob to satisfy Android's check.
            .setIdentifier("profile-geofence")
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        // requestCode is shared across all profile fences — Play Services
        // de-duplicates by Intent action+data which is identical here, so
        // we get the same broadcast for every fence (the receiver picks
        // out which one fired from the GeofencingEvent).
        return PendingIntent.getBroadcast(
            context.applicationContext,
            ID_PREFIX.hashCode() and 0x7FFFFFFF,
            intent,
            flags,
        )
    }
}
