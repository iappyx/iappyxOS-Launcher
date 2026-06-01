/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.cells

import android.content.Context
import com.iappyx.launcher.ai.AiService
import com.iappyx.launcher.ai.SecureStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * AI-driven generation + iteration of icon-filter specs. Mirrors the
 * shape of [com.iappyx.launcher.transitions.TransitionGenerator] /
 * [com.iappyx.launcher.wallpaper.WallpaperGenerator] so the command-bar
 * tool surface stays consistent.
 *
 * Output is a JSON object matching [IconFilterSpec]. The AI picks `name`,
 * an optional `subtitle`, and a `bake` chain composed of the operations
 * the runner understands; we assign the slug ourselves (UUID) so per-
 * device imports never collide.
 */
object IconFilterGenerator {

    private const val PROMPT_MAX = 1500

    class GenerationException(msg: String) : Exception(msg)

    @Throws(GenerationException::class)
    fun generate(context: Context, description: String): String {
        val trimmed = description.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what the icon style should look like")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Description too long (${trimmed.length}/$PROMPT_MAX)")
        }
        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val raw = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(AiService.Message("user", trimmed)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Generation failed")
        }
        val sanitised = sanitise(raw)
        // Stamp the slug (and a name fallback) BEFORE validation so an AI
        // output without a slug — which the prompt deliberately doesn't ask
        // for, since we assign UUIDs ourselves — passes fromJson's
        // required-field check.
        val slug = UUID.randomUUID().toString()
        val withSlug = JSONObject(sanitised).apply {
            put("slug", slug)
            if (optString("name").isBlank()) put("name", smartTitle(trimmed))
        }
        try {
            IconFilterSpec.fromJson(withSlug)
        } catch (e: Throwable) {
            throw GenerationException("AI output didn't match the spec format: ${e.message}")
        }
        val title = withSlug.optString("name").ifBlank { smartTitle(trimmed) }

        val dir = File(IconFilterRegistry.userDir(context), slug).also { it.mkdirs() }
        try {
            File(dir, "spec.json").writeText(withSlug.toString(2), Charsets.UTF_8)
            IconFilterRegistry.writeMeta(context, slug, prompt = trimmed, title = title)
            // Force a re-resolve next time something asks for this slug.
            IconFilterRegistry.invalidate(slug)
            // Sanity: the runner can resolve + apply the spec. Done lazily on
            // first icon render, but exercise the resolve path here so a
            // corrupt spec surfaces during generation rather than silently.
            IconFilterRegistry.resolve(context, slug)
        } catch (e: Throwable) {
            File(dir, "spec.json").delete()
            File(dir, "meta.json").delete()
            dir.delete()
            throw GenerationException("Failed to save: ${e.message}")
        }
        return slug
    }

    @Throws(GenerationException::class)
    fun iterate(context: Context, slug: String, instruction: String): String {
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) throw GenerationException("Describe what should change")
        if (trimmed.length > PROMPT_MAX) {
            throw GenerationException("Instruction too long (${trimmed.length}/$PROMPT_MAX)")
        }
        if (!IconFilterRegistry.isUserGenerated(slug)) {
            throw GenerationException("Bundled icon styles can't be refined — generate a new one instead")
        }
        val specFile = File(IconFilterRegistry.userDir(context), "$slug/spec.json")
        if (!specFile.exists()) throw GenerationException("Icon style not found")

        val store = SecureStore(context)
        val key = store.anthropicKey?.takeIf { it.isNotBlank() }
            ?: throw GenerationException("API key not set — open Settings")

        val current = specFile.readText()
        val userMsg = buildString {
            append("Here is the current icon-filter spec:\n```json\n")
            append(current)
            append("\n```\n\nThe user wants this change:\n")
            append(trimmed)
            append("\n\nReturn the FULL updated JSON spec, no commentary, no markdown fences.")
        }
        val raw = try {
            AiService.generate(
                apiKey = key,
                model = store.anthropicModel,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(AiService.Message("user", userMsg)),
            )
        } catch (e: Exception) {
            throw GenerationException(e.message ?: "Iteration failed")
        }
        val sanitised = sanitise(raw)
        // Stamp the slug back in (the prompt says we own slugs) before
        // validating, otherwise a fully-correct refine would fail the
        // spec-missing-slug check.
        val rewrittenSlug = JSONObject(sanitised).apply { put("slug", slug) }
        try { IconFilterSpec.fromJson(rewrittenSlug) }
        catch (e: Throwable) {
            throw GenerationException("AI output didn't match the spec format: ${e.message}")
        }
        try {
            specFile.writeText(rewrittenSlug.toString(2), Charsets.UTF_8)
            IconFilterRegistry.invalidate(slug)
        } catch (e: Throwable) {
            throw GenerationException("Failed to save: ${e.message}")
        }
        return slug
    }

    /** Build the same prompt the manual-AI flow would copy to the
     *  clipboard — for users who'd rather paste into ChatGPT / their own
     *  AI and bring back the JSON manually. */
    fun buildManualPrompt(description: String): String =
        SYSTEM_PROMPT.trim() + "\n\n---\n\nIcon-style description: " + description.trim()

    /** Best-effort title derivation — first 4 words of the prompt,
     *  capitalised. Used only when the AI didn't supply its own `name`. */
    private fun smartTitle(prompt: String): String {
        val words = prompt.replace(Regex("[^A-Za-z0-9 ]"), "").split(' ')
            .filter { it.isNotBlank() }.take(4)
        return words.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }.ifBlank { "Generated icon style" }
    }

    private fun sanitise(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) {
                s = s.substring(firstNl + 1)
                val closer = s.lastIndexOf("```")
                if (closer >= 0) s = s.substring(0, closer)
                s = s.trim()
            }
        }
        val open = s.indexOf('{')
        val close = s.lastIndexOf('}')
        return if (open >= 0 && close > open) s.substring(open, close + 1) else s
    }

    private val SYSTEM_PROMPT = """
You design icon-filter presets for an Android home-screen launcher. Each preset is a chain of per-pixel transformations applied to every app icon — like a photographic preset, but for the launcher's icon grid. The icon's identity stays intact (Spotify still looks like Spotify); the filter restyles its colours / texture so all icons feel cohesive.

Output format (JSON, NOTHING else):

{
  "name":     "<short title, ≤ 32 chars>",
  "subtitle": "<one-sentence description, ≤ 80 chars, optional>",
  "shape":    <optional silhouette shape; omit or null = rounded square>,
  "bake":     [<chain of bake ops, applied in order>],
  "tint":     <optional tint op or null>
}

Shape (optional — overrides the default rounded-square silhouette every
icon is masked into; corners outside the shape become transparent and
atmospheric ops like vignette/glow/aurora trace the new outline):

  { "kind": "rounded_square", "corner_fraction": <0..0.5> }
      Default. corner_fraction 0 = sharp square, 0.22 = launcher default,
      0.5 = full circle.

  { "kind": "circle" }
      Inscribed circle.

  { "kind": "squircle", "n": <2..12> }
      Superellipse |x|^n + |y|^n = 1. n=4 is the iOS-style squircle
      (rounder than rounded-square, flatter than circle).

  { "kind": "hexagon", "flat_top": <true|false> }
      Regular hexagon. flat_top true = horizontal top/bottom edges;
      false = pointy top.

  { "kind": "heart" }
      Classic heart silhouette, edge-to-edge.

  { "kind": "custom", "path": "<SVG path data>" }
      Arbitrary silhouette from SVG `d`-attribute path data (M/L/C/Q/A/Z
      commands). Auto-scaled to fill the icon square (preserves aspect
      ratio, ~4 % anti-aliasing inset). Use for stars, clouds, leaves,
      shields, anything you can draw in an SVG editor. Coordinate space
      can be anything — the parser computes bounds and fits. Keep paths
      simple (one closed contour) so the silhouette reads at icon size.
      Example path for a five-pointed star (viewBox 100×100):
        "M 50 3 L 61 38 L 98 38 L 68 60 L 79 95 L 50 73 L 21 95 L 32 60 L 2 38 L 39 38 Z"

Bake ops — colour adjustments (sugar, cheap; chain freely):

  { "op": "brightness", "value": <-1..+1> }
      Adds the same offset to every channel. +0.2 lifts highlights, −0.3
      crushes them. Map: ±1 ≈ ±128 in 8-bit space.

  { "op": "contrast", "value": <-1..+1> }
      Contrast scaled around mid-grey. 0 = identity,
      +1 doubles contrast, −1 collapses to flat grey.

  { "op": "set_saturation", "value": <0..2> }
      0 = greyscale, 1 = unchanged, >1 = oversaturated.

  { "op": "hue_rotate", "degrees": <-180..+180> }
      Rotate the entire palette around the hue wheel. ±20° is subtle,
      ±60° is dramatic.

  { "op": "color_matrix", "values": [20 floats] }
      Escape hatch: 4×5 ColorMatrix in row-major (R,G,B,A,offset per
      row). Identity = [1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0].
      Keep the alpha row at 0,0,0,1,0 unless you explicitly want alpha
      driven by colour. Offsets are 0..255 (NOT 0..1).

Bake ops — split-tone & atmosphere (atomic, more expensive):

  { "op": "duotone", "shadow_color": "#…", "highlight_color": "#…", "balance": <-1..+1> }
      Map every pixel's luminance between shadow_color (darks) and
      highlight_color (lights). balance shifts the midpoint:
        −1 = whole image leans toward shadow_color
        +1 = whole image leans toward highlight_color
         0 = clean linear blend (default).
      Split-toning lives here. Cinematic teal-and-orange,
      bleach bypass, infrared etc.

  { "op": "vignette", "amount": <0..1>, "feather": <0..1> }
      Radial darkening at the edges. amount = how dark; feather = how
      smooth (0 hard edge ring, 1 smooth from centre).

  { "op": "grain", "amount": <0..1>, "size": <1..8> }
      Film-grain noise overlay. amount = visibility; size = chunk size
      (1 = fine 35mm, 4–6 = chunky Super-8).

  { "op": "posterize", "levels": <2..16> }
      Quantise each channel to N steps. 4 = screen-print, 8 = subtle.

  { "op": "glow", "radius": <1..40>, "color": "#…", "alpha": <0..1> }
      Tinted bloom halo around bright pixels. radius is in px;
      alpha controls intensity. Diffuse-glow / Orton-effect look.

Bake ops — special:

  { "op": "pixelate", "grid": <2..64> }   8-bit retro. 14 baseline.

  { "op": "tinted_mono" }
      Atomic: flatten each icon to luminance, sample its dominant colour,
      retint with it. iOS-18 monochrome flavour. Don't combine with
      set_saturation.

  { "op": "aurora", "colors": ["#…", "#…", …] }
      Iridescent sweep gradient with OVERLAY blend, alpha-clipped to
      the icon. Holographic / oil-slick. 3..6 hex colours; first and
      last usually match for a smooth seam.

Tint ops (single per-cell colour applied at view time; optional):

  { "op": "position_hue", "saturation": <0..1>, "value": <0..1> }
      Hue varies by grid position — radial rainbow. PAIR with a
      set_saturation:0 bake op so the underlying icon is greyscale.

  { "op": "wallpaper_color" }
      Cell tint sampled from the user's wallpaper at that cell's
      region. Pair with set_saturation:0.

  { "op": "system_accent" }
      Material You system accent applied uniformly. Pair with
      set_saturation:0.

Constraints:
  - Output ONLY the JSON object. No commentary, no markdown fences.
  - Keep the bake chain ordered: colour adjustments first, atmospherics
    (vignette/grain/glow) last, so noise sits on top of the colour grade.
  - Bake chain length 1..6 ops. More than 6 starts to wash the icon out.
  - Don't invent op names. Validation will reject unknowns.
  - Hex colours must be either #RRGGBB or #AARRGGBB.

Examples (study how the ops compose):

  "Faded film" — Polaroid-warm midtones, lifted blacks, gentle grain:
    "bake": [
      {"op":"contrast","value":-0.2},
      {"op":"brightness","value":0.05},
      {"op":"set_saturation","value":0.7},
      {"op":"duotone","shadow_color":"#FF3A2E2A","highlight_color":"#FFE9D9B0","balance":0.0},
      {"op":"grain","amount":0.18,"size":2},
      {"op":"vignette","amount":0.25,"feather":0.6}
    ]

  "Teal & orange" — cinematic split tone:
    "bake": [
      {"op":"contrast","value":0.15},
      {"op":"set_saturation","value":1.05},
      {"op":"duotone","shadow_color":"#FF14333E","highlight_color":"#FFFFB36B","balance":0.1}
    ]

  "Bleach bypass" — desat + crunchy contrast:
    "bake": [
      {"op":"set_saturation","value":0.35},
      {"op":"contrast","value":0.4},
      {"op":"brightness","value":-0.05}
    ]

  "Cyberpunk neon glow":
    "bake": [
      {"op":"set_saturation","value":1.4},
      {"op":"hue_rotate","degrees":-15},
      {"op":"glow","radius":10,"color":"#FFFF2DD8","alpha":0.45}
    ]

  "Posterized comic":
    "bake": [
      {"op":"contrast","value":0.3},
      {"op":"set_saturation","value":1.3},
      {"op":"posterize","levels":5}
    ]

  "Greyscale + Material You wash":
    "bake": [{"op":"set_saturation","value":0}],
    "tint": {"op":"system_accent"}

  "Sweetheart" — heart-shaped icons in soft pink:
    "shape": {"kind":"heart"},
    "bake": [
      {"op":"set_saturation","value":0.6},
      {"op":"duotone","shadow_color":"#FF5C2A4A","highlight_color":"#FFFFD0E0","balance":0.0}
    ]

  "Round mono" — circular icons tinted to your accent:
    "shape": {"kind":"circle"},
    "bake": [{"op":"set_saturation","value":0}],
    "tint": {"op":"system_accent"}

  "Hex tiles" — hexagonal icons with a film grain:
    "shape": {"kind":"hexagon","flat_top":true},
    "bake": [
      {"op":"set_saturation","value":0.85},
      {"op":"grain","amount":0.18,"size":2}
    ]
""".trimIndent()
}
