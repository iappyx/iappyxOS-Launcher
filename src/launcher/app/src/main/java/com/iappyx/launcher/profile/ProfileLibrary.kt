/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.profile

import android.content.Context
import com.iappyx.launcher.model.Profile
import org.json.JSONObject
import java.io.File

/**
 * On-disk catalogue of [Profile] entries. Each profile lives in its own
 * subdirectory at `filesDir/profiles/{slug}/profile.json` so the future
 * showcase / nearby flows have a clean per-artefact bundle. Mirrors the
 * shape of [com.iappyx.launcher.cells.IconFilterRegistry] /
 * [com.iappyx.launcher.transitions.TransitionLibrary].
 *
 * Methods here are CRUD only — applying a snapshot to the live launcher
 * state lives in [ProfileApplier]; matching live device state to a
 * profile lives in [ProfileMatcher].
 */
object ProfileLibrary {

    // ConcurrentHashMap: read/written from the UI thread, ProfileWatcher's
    // network/BT/geofence binder callbacks, and the remote-edit HTTP server
    // threads. A plain HashMap here risked ConcurrentModificationException /
    // resize corruption (H7-3). Values are never null in practice (only
    // successfully-loaded profiles are cached), so a non-null value type is safe.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Profile>()

    fun root(context: Context): File =
        File(context.filesDir, "profiles").also { it.mkdirs() }

    fun all(context: Context): List<Profile> {
        val dir = root(context)
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { sub ->
                val slug = sub.name
                cache[slug] ?: loadFromDisk(context, slug)?.also { cache[slug] = it }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    fun get(context: Context, slug: String): Profile? {
        cache[slug]?.let { return it }
        return loadFromDisk(context, slug)?.also { cache[slug] = it }
    }

    private fun loadFromDisk(context: Context, slug: String): Profile? {
        val f = File(root(context), "$slug/profile.json")
        if (!f.exists()) return null
        return try { Profile.fromJson(JSONObject(f.readText())) }
        catch (_: Throwable) { null }
    }

    fun save(context: Context, profile: Profile): Boolean {
        val dir = File(root(context), profile.slug).also { it.mkdirs() }
        return try {
            File(dir, "profile.json").writeText(profile.toJson().toString(2), Charsets.UTF_8)
            cache[profile.slug] = profile
            true
        } catch (_: Throwable) { false }
    }

    fun delete(context: Context, slug: String): Boolean {
        cache.remove(slug)
        val dir = File(root(context), slug)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    fun rename(context: Context, slug: String, newName: String): Boolean {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isEmpty()) return false
        val current = get(context, slug) ?: return false
        return save(context, current.copy(name = clean))
    }

    fun invalidate(slug: String) { cache.remove(slug) }
    fun invalidateAll() { cache.clear() }

    /** Slug-uniquifier — derives a URL-safe slug from a display name and
     *  appends a counter if a profile already owns that slug. */
    fun freshSlugFor(context: Context, name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "profile" }
        if (get(context, base) == null) return base
        var n = 2
        while (get(context, "$base-$n") != null) n++
        return "$base-$n"
    }
}
