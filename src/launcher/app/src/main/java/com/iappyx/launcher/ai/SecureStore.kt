/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API keys live in EncryptedSharedPreferences with a hardware-backed master key
 * from the Android Keystore. Same pattern iappyxOS uses via flutter_secure_storage.
 */
class SecureStore(context: Context) {
    /** Null on first cold boot before the user unlocks (Keystore not yet
     *  available) or on devices where EncryptedSharedPreferences fails to
     *  initialise. Treated as "no key set" by callers — they show the
     *  "open Settings → AI to add a key" message instead of crashing. */
    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "iappyx_launcher_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        android.util.Log.e("iappyxLauncher", "SecureStore init failed: ${e.message}")
        null
    }

    var anthropicKey: String?
        get() = prefs?.getString("anthropic_key", null)
        set(value) { prefs?.edit()?.putString("anthropic_key", value)?.apply() }

    /** Model used for **creating** widgets / wallpapers from scratch and as
     *  the fallback for [iterateModel] when an iterate attempt has to fall
     *  back to a full HTML rewrite (better quality on the high-stakes path).
     *  Defaults to Sonnet 4.6 — the highest-quality model in the Claude 4
     *  family for one-shot HTML generation. Stored as a free-form string so
     *  any model identifier (Anthropic now, OpenAI / OpenRouter / Google
     *  later when those providers are wired up) can be pasted in. */
    var anthropicModel: String
        get() = prefs?.getString("anthropic_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) { prefs?.edit()?.putString("anthropic_model", value)?.apply() }

    /** Model used for **editing / iterating** existing widgets and
     *  wallpapers. Iteration is the high-volume / latency-sensitive path
     *  (the user types short instructions and expects sub-second feedback);
     *  the work is mostly mechanical (find string → replace string → emit
     *  JSON), so a fast model is the right default. Falls back to
     *  [anthropicModel] when an iterate attempt has to retry in `full_html`
     *  mode (rare, only when the first edit-pass mismatches), so a complex
     *  rewrite still gets the higher-quality model.
     *
     *  Defaults to Haiku 4.5 — ~3× faster than Sonnet, with edit quality
     *  that's indistinguishable on the diff-emit path. Stored as a
     *  free-form string for forward-compat with non-Anthropic providers. */
    var iterateModel: String
        get() = prefs?.getString("iterate_model", DEFAULT_ITERATE_MODEL) ?: DEFAULT_ITERATE_MODEL
        set(value) { prefs?.edit()?.putString("iterate_model", value)?.apply() }

    /** GitHub Personal Access Token (classic, scope `public_repo`) used by
     *  the showcase Submit flow to open PRs against
     *  [iappyxOS-Launcher-showcase](https://github.com/iappyx/iappyxOS-Launcher-showcase).
     *  Null when the user hasn't set one yet — Submit is hidden / disabled
     *  in that case. Same encrypted-prefs storage as the Anthropic key. */
    var githubToken: String?
        get() = prefs?.getString("github_token", null)
        set(value) { prefs?.edit()?.putString("github_token", value)?.apply() }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_ITERATE_MODEL = "claude-haiku-4-5-20251001"
    }
}
