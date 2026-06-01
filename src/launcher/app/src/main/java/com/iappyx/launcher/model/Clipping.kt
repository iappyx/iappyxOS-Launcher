/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.model

import org.json.JSONObject

/**
 * A share-to-launcher capture pinned on the rightmost "Clippings" page.
 *
 * Conceptually a thin pointer to an ambient widget folder under
 * `filesDir/widgets/<widgetId>/` — the heavy state (kind, createdAt,
 * expiresAt, userLocked, sourceUrl, thumbnailUrl, ...) lives in that folder's
 * `meta.json`. Clippings are list-ordered (newest first), not grid-placed,
 * so there are no row/col/span fields here.
 */
data class Clipping(val widgetId: String) {
    fun toJson(): JSONObject = JSONObject().apply { put("widgetId", widgetId) }

    companion object {
        fun fromJson(o: JSONObject): Clipping? {
            val id = o.optString("widgetId", "")
            if (id.isEmpty()) return null
            return Clipping(id)
        }
    }
}
