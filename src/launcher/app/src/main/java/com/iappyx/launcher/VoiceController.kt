/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Voice command capture: SpeechRecognizer + a "Listening…" overlay with
 * live partial transcription. Used by the long-press gesture on the AI
 * pill in the home indicator.
 *
 * Permission acquisition stays in the activity (the
 * `registerForActivityResult` launcher must live there for the right
 * lifecycle timing). The activity calls [start] only once mic permission
 * is already granted; this class handles everything from there.
 *
 * Lifecycle: construct with the activity. Call [start] / [finish] /
 * [cancel] from gesture callbacks. Call [destroy] from the activity's
 * `onDestroy` to release the recognizer + animator.
 *
 * @param activity hosts the overlay + provides Context for SpeechRecognizer
 * @param rootViewId the FrameLayout id under which the overlay scrim is added
 * @param onRecordingStateChanged surfaces recording on/off state for UI
 *        (e.g. WormIndicator's recording dot)
 * @param onTranscript called with the final transcribed text (non-empty)
 */
class VoiceController(
    private val activity: Activity,
    private val rootViewId: Int,
    private val onRecordingStateChanged: (Boolean) -> Unit,
    private val onTranscript: (String) -> Unit,
) {

    private var speechRecognizer: SpeechRecognizer? = null

    /** Set true once SpeechRecognizer fires onBeginningOfSpeech (or
     *  onReadyForSpeech). [finish] defers stopListening until this is
     *  true so a fast release doesn't kill the recognizer before audio
     *  capture has started — original cause of spurious "No speech
     *  detected" toasts. */
    private var captureBegun: Boolean = false

    /** When set, the user released the trigger before the recognizer
     *  started capturing. We honour the stop intent as soon as capture
     *  begins. */
    private var pendingStop: Boolean = false

    private var overlay: View? = null
    private var overlayText: TextView? = null
    private var pulseAnimator: ValueAnimator? = null

    /** Begin a voice capture. Caller is responsible for permission gating
     *  (RECORD_AUDIO must be granted before this is called). Returns true
     *  if recognition started, false if the device has no SpeechRecognizer
     *  available (caller should toast). */
    fun start(): Boolean {
        Log.d(TAG, "voice: startRecognizer")
        onRecordingStateChanged(true)
        showOverlay()
        captureBegun = false
        pendingStop = false
        speechRecognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Toast.makeText(activity, "No speech recognizer on this device", Toast.LENGTH_SHORT).show()
            onRecordingStateChanged(false)
            hideOverlay()
            return false
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "voice: onReadyForSpeech")
                    captureBegun = true
                    setOverlayText("speak now")
                    if (pendingStop) {
                        pendingStop = false
                        try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
                    }
                }
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "voice: onBeginningOfSpeech")
                    captureBegun = true
                    setOverlayText("…")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "voice: onEndOfSpeech")
                }
                override fun onError(error: Int) {
                    Log.d(TAG, "voice: onError $error (captureBegun=$captureBegun)")
                    onRecordingStateChanged(false)
                    hideOverlay()
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected — try again, hold the pill while speaking"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission denied"
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error"
                        SpeechRecognizer.ERROR_CLIENT -> null // transient — silently drop
                        else -> null
                    }
                    if (msg != null) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    if (text.isNotEmpty()) setOverlayText(text)
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim().orEmpty()
                    Log.d(TAG, "voice: onResults text='$text' matches=${matches?.size}")
                    onRecordingStateChanged(false)
                    hideOverlay()
                    if (text.isNotEmpty()) onTranscript(text)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Generous timeouts — short utterances ("open spotify") were
                // getting cut off mid-syllable on the default 1000ms thresholds.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            startListening(intent)
        }
        return true
    }

    /** User released the trigger after a long-press. Defers stopListening
     *  until capture has begun (see [captureBegun]). */
    fun finish() {
        Log.d(TAG, "voice: finish captureBegun=$captureBegun")
        if (captureBegun) {
            try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
        } else {
            pendingStop = true
        }
    }

    /** Long-press was aborted (finger drifted off, gesture interrupted).
     *  Drop any in-flight transcription. */
    fun cancel() {
        try { speechRecognizer?.cancel() } catch (_: Throwable) {}
        pendingStop = false
        captureBegun = false
        onRecordingStateChanged(false)
        hideOverlay()
    }

    /** Release the recognizer + animator. Call from `onDestroy`. */
    fun destroy() {
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        speechRecognizer = null
        try { pulseAnimator?.cancel() } catch (_: Throwable) {}
        pulseAnimator = null
    }

    /** Lazily build and show the overlay. Single instance reused across
     *  voice commands. */
    private fun showOverlay() {
        val root = activity.findViewById<FrameLayout>(rootViewId) ?: return
        if (overlay == null) {
            val ctx = activity
            val dp = activity.resources.displayMetrics.density
            // Outer FrameLayout = full-screen dim scrim. Tapping the scrim does
            // NOT cancel — release the trigger or drag away. The overlay never
            // owns the gesture; the WormIndicator does.
            val scrim = FrameLayout(ctx).apply {
                background = ColorDrawable(0xCC000000.toInt())
                isClickable = true
            }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((28 * dp).toInt(), (32 * dp).toInt(), (28 * dp).toInt(), (28 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF1F1F23.toInt())
                    cornerRadius = 24 * dp
                }
                elevation = 24 * dp
            }
            val micWrap = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt())
            }
            val pulseRing = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt(), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x44FF5252)
                }
            }
            val micCircle = View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt(), Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFF5252.toInt())
                }
            }
            val micText = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt(), Gravity.CENTER)
                    .apply { gravity = Gravity.CENTER }
                gravity = Gravity.CENTER
                text = "🎤"
                textSize = 28f
            }
            micWrap.addView(pulseRing)
            micWrap.addView(micCircle)
            micWrap.addView(micText)
            card.addView(micWrap)
            // Pulse animation — scale 1→1.5 + alpha 0.4→0, repeat.
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { v ->
                    val t = v.animatedValue as Float
                    val scale = 1f + 0.5f * t
                    pulseRing.scaleX = scale
                    pulseRing.scaleY = scale
                    pulseRing.alpha = 0.4f * (1f - t)
                }
                start()
                pulseAnimator = this
            }

            val header = TextView(ctx).apply {
                text = "Listening…"
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 18f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                letterSpacing = 0.05f
                gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, 0)
            }
            card.addView(header)

            val textView = TextView(ctx).apply {
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), 0)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                minHeight = (96 * dp).toInt()
            }
            card.addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            val hint = TextView(ctx).apply {
                text = "Release to send · Slide off the AI pill to cancel"
                setTextColor(0xFF888888.toInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (16 * dp).toInt(), 0, 0)
            }
            card.addView(hint)

            scrim.addView(card, FrameLayout.LayoutParams(
                (320 * dp).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))

            overlay = scrim
            overlayText = textView
            root.addView(scrim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        overlayText?.text = ""
        overlay?.visibility = View.VISIBLE
        overlay?.alpha = 0f
        overlay?.bringToFront()
        overlay?.animate()?.alpha(1f)?.setDuration(160)?.start()
    }

    private fun setOverlayText(text: String) {
        overlayText?.text = text
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        v.animate().alpha(0f).setDuration(160).withEndAction {
            v.visibility = View.GONE
        }.start()
    }

    /** Defensive force-hide — used by the activity's `onPause`/`onResume`
     *  defensive reset in case a gesture was interrupted by a process
     *  pause / kill mid-listen. */
    fun forceHideOverlay() {
        overlay?.let {
            it.animate().cancel()
            it.alpha = 0f
            it.visibility = View.GONE
        }
    }

    private companion object {
        const val TAG = "iappyxLauncher"
    }
}
