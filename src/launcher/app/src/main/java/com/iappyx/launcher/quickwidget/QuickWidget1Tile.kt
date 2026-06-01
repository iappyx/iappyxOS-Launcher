/*
 * MIT License - Copyright (c) 2026 iappyx
 * QUICK WIDGETS: tile slot 1. Phase 1 ships one; Phase 2 adds 2–5. Each
 * slot is a separate TileService subclass because Android caches tile
 * classes by FQCN.
 */
package com.iappyx.launcher.quickwidget

class QuickWidget1Tile : QuickWidgetTileBase() {
    override val slot: Int = 1
}
