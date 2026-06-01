/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.iappyx.launcher.model.IntentAction
import com.iappyx.launcher.model.IntentExtra

/**
 * Builds an [Intent] from a configured [IntentAction] and dispatches it
 * via the chosen verb (broadcast / activity / service / foreground
 * service). Returns a typed [Result] so callers can decide whether to
 * surface the failure to the user.
 *
 * Design notes:
 *  - Apps that expose this integration usually gate it on an in-app
 *    "Allow remote control" toggle, so missing-permission failures look
 *    like silent no-ops rather than thrown exceptions. We can't detect
 *    that case directly; failures users see are limited to the typed
 *    classes below.
 *  - Activities are launched with `FLAG_ACTIVITY_NEW_TASK` because
 *    [ProfileApplier] is invoked from a non-Activity context (the
 *    matcher / receiver). Users can override the flag set on the
 *    action itself; the helper merges them.
 */
object IntentRunner {

    private const val TAG = "iappyxIntentRunner"

    sealed class Result {
        object Ok : Result()
        /** No component handled the intent. Editor's verb / package / class
         *  combination doesn't resolve to anything installed. */
        data class NoMatchingComponent(val message: String) : Result()
        /** SecurityException — caller lacks the permission the target
         *  declared. Hint user to check the target app's settings. */
        data class PermissionDenied(val message: String) : Result()
        /** Anything else thrown during build or dispatch. */
        data class Failed(val throwable: Throwable) : Result()
    }

    fun fire(context: Context, action: IntentAction): Result {
        val intent = try {
            buildIntent(action)
        } catch (t: Throwable) {
            Log.w(TAG, "build intent failed: ${t.message}", t)
            return Result.Failed(t)
        }

        // Warm-up: if the user opted in for this action, pull the target
        // into the active standby bucket BEFORE firing the real intent.
        // Android's cached-app freezer can otherwise leave the target's
        // native backend suspended; the receiver then wakes briefly,
        // no-ops, and the broadcast looks dropped.
        //
        // After the broadcast fires we try to restore the calling
        // activity to the top of its stack (REORDER_TASKS permission)
        // so the user lands back where they were. Only effective when
        // [context] is an Activity — i.e. when fired from the editor's
        // Test button or any in-app surface. From a background
        // ProfileApplier dispatch we have no task to restore, so the
        // warm-up target stays foreground (the rare path; user opted
        // in knowing this is the worst case).
        if (action.warmupTargetFirst && !action.packageName.isNullOrBlank()) {
            try {
                val warmIntent = context.packageManager
                    .getLaunchIntentForPackage(action.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (warmIntent != null) {
                    // Capture taskId BEFORE switching to applicationContext —
                    // that's the only Activity-bound piece we need to remember.
                    // Use applicationContext for the deferred dispatch so the
                    // 600 ms Handler doesn't pin a finishing Activity in memory.
                    val callerTaskId = (context as? android.app.Activity)?.taskId ?: -1
                    val appCtx = context.applicationContext
                    context.startActivity(warmIntent)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        dispatch(appCtx, intent, action)
                        // Restoration: prefer "back where the user was"
                        // (their task) when we have a taskId; fall back
                        // to the home screen when we don't (background
                        // profile activation has no calling Activity).
                        if (callerTaskId > 0) {
                            try {
                                val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE)
                                    as? android.app.ActivityManager
                                am?.moveTaskToFront(callerTaskId, 0)
                            } catch (t: Throwable) {
                                Log.w(TAG, "moveTaskToFront failed: ${t.message}")
                            }
                        } else {
                            try {
                                val home = Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                appCtx.startActivity(home)
                            } catch (t: Throwable) {
                                Log.w(TAG, "home fallback failed: ${t.message}")
                            }
                        }
                    }, 600L)
                    return Result.Ok
                }
                // Falls through to non-warm-up dispatch when the target
                // has no launcher activity.
            } catch (t: Throwable) {
                Log.w(TAG, "warm-up failed for ${action.packageName}: ${t.message}")
                // Continue to dispatch anyway — better to try the real
                // intent than to bail because the warm-up didn't take.
            }
        }
        return dispatch(context, intent, action)
    }

    private fun dispatch(context: Context, intent: Intent, action: IntentAction): Result {
        // For ACTIVITY verb only: pre-resolve so a missing component
        // surfaces a clean error rather than the system's
        // ActivityNotFoundException stack trace. sendBroadcast and
        // startService cannot be reliably pre-checked — `setClassName`
        // intents don't always resolve through queryBroadcastReceivers
        // / queryIntentServices on every Android version, so an explicit-
        // target broadcast can be a valid call that the query path drops.
        // Trust sendBroadcast and only surface failures via the exception
        // path below.
        if (action.verb == IntentAction.Verb.ACTIVITY) {
            val resolves = try {
                context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            } catch (_: Throwable) { true }
            if (!resolves) {
                return Result.NoMatchingComponent(
                    "No activity handles this intent. Check package / class / action.",
                )
            }
        }
        return try {
            when (action.verb) {
                IntentAction.Verb.BROADCAST -> {
                    // Match `adb shell am broadcast`'s default flags —
                    // FLAG_RECEIVER_FOREGROUND pushes the broadcast onto
                    // the foreground delivery queue (without it, manifest
                    // receivers in cached processes can have the broadcast
                    // deferred or dropped on Android 14+);
                    // FLAG_INCLUDE_STOPPED_PACKAGES makes sure delivery
                    // happens even when the target is in stopped state.
                    if (intent.flags and Intent.FLAG_RECEIVER_FOREGROUND == 0) {
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    if (intent.flags and Intent.FLAG_INCLUDE_STOPPED_PACKAGES == 0) {
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                    context.sendBroadcast(intent)
                    Result.Ok
                }
                IntentAction.Verb.ACTIVITY -> {
                    val flagged = Intent(intent).apply {
                        if (flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    context.startActivity(flagged)
                    Result.Ok
                }
                IntentAction.Verb.SERVICE -> {
                    context.startService(intent)
                    Result.Ok
                }
                IntentAction.Verb.FOREGROUND_SERVICE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Result.Ok
                }
            }
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "no component for $action: ${t.message}")
            Result.NoMatchingComponent(t.message ?: "Nothing handles this intent")
        } catch (t: SecurityException) {
            Log.w(TAG, "security: ${t.message}")
            Result.PermissionDenied(t.message ?: "Permission denied")
        } catch (t: Throwable) {
            Log.w(TAG, "dispatch failed: ${t.message}", t)
            Result.Failed(t)
        }
    }

    /** Public so the editor's "Test now" button can build + show the
     *  resulting Intent's flat string for debugging without firing. */
    fun buildIntent(action: IntentAction): Intent {
        val intent = Intent()
        action.action?.let { intent.action = it }
        // Component (package + class) takes precedence over package-only
        // because Intent.setPackage clears the component if both are set.
        if (!action.packageName.isNullOrBlank() && !action.className.isNullOrBlank()) {
            intent.setClassName(action.packageName, action.className)
        } else if (!action.packageName.isNullOrBlank()) {
            intent.setPackage(action.packageName)
        }
        if (!action.dataUri.isNullOrBlank() && !action.mimeType.isNullOrBlank()) {
            intent.setDataAndType(Uri.parse(action.dataUri), action.mimeType)
        } else {
            if (!action.dataUri.isNullOrBlank()) intent.data = Uri.parse(action.dataUri)
            if (!action.mimeType.isNullOrBlank()) intent.type = action.mimeType
        }
        for (cat in action.categories) intent.addCategory(cat)
        if (action.flags != 0) intent.addFlags(action.flags)
        for (e in action.extras) putTyped(intent, e)
        return intent
    }

    private fun putTyped(intent: Intent, e: IntentExtra) {
        when (e.type) {
            IntentExtra.ExtraType.STRING -> intent.putExtra(e.key, e.value)
            IntentExtra.ExtraType.INT -> e.value.toIntOrNull()?.let { intent.putExtra(e.key, it) }
            IntentExtra.ExtraType.LONG -> e.value.toLongOrNull()?.let { intent.putExtra(e.key, it) }
            IntentExtra.ExtraType.BOOL -> intent.putExtra(e.key, e.value.equals("true", ignoreCase = true) || e.value == "1")
            IntentExtra.ExtraType.FLOAT -> e.value.toFloatOrNull()?.let { intent.putExtra(e.key, it) }
        }
    }
}
