/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.command

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool schemas the AI Command Bar exposes to Anthropic. The full set is:
 *  - find_empty_spot          → locate a free (page, row, col) of given size
 *  - create_generated_widget  → describe → generate HTML → place
 *  - place_app_icon           → drop an installed app on the grid
 *  - create_folder            → group 2+ apps into a folder cell
 *  - open_app                 → just launch
 *  - list_installed_apps      → enumerate launchable apps (optionally filtered)
 *  - get_layout               → current home structure (compact)
 *  - remove_cell              → delete a placement by id
 *  - move_cell                → relocate an existing placement
 *  - add_to_folder            → add an installed app to an existing folder
 *  - remove_from_folder       → pull an app out of a folder (auto-collapse)
 *  - rename_folder            → change a folder's display name
 *  - add_to_dock              → place an app icon in the dock
 *  - remove_from_dock         → remove an icon from the dock
 *  - swap_cells               → swap two placements' positions
 *  - reorganize_into_folders  → batch-create multiple folders at once
 *  - generate_wallpaper       → AI-write a live-wallpaper HTML payload, save, auto-select
 *  - set_iappyx_wallpaper     → deep-link the user to the system live-wallpaper picker
 *
 * Each schema follows Anthropic's tool-use JSON format.
 */
object Tools {

    fun definitions(): JSONArray = JSONArray().apply {
        put(tool("find_empty_spot",
            "Find a free (row, col) on a home page that fits a widget of the requested size. " +
                    "Considers ALL placements (icons, folders, stock widgets, generated widgets). " +
                    "If page_index is omitted, checks every page in order — and if none has space, AUTOMATICALLY appends a new empty page and returns (new_index, 0, 0). " +
                    "If page_index is supplied, only that page is checked and the call may fail with {error: 'no free space'}.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("page_index", JSONObject().apply {
                        put("type", "integer"); put("description", "Specific page (0-based). Omit to search all pages.")
                    })
                    put("w_span", JSONObject().apply { put("type", "integer"); put("description", "Width in cells, default 1.") })
                    put("h_span", JSONObject().apply { put("type", "integer"); put("description", "Height in cells, default 1.") })
                })
                put("required", JSONArray().put("w_span").put("h_span"))
            }))

        put(tool("create_generated_widget",
            "Generate an HTML/JS widget from a plain-language description and place it on the home grid. " +
                    "The description should be like a user prompt to a widget AI (e.g. 'water tracker with cup count and a big + button'). " +
                    "If page/row/col are omitted, an empty spot is auto-found across all pages — and a NEW page is automatically appended if none fits. Default size is 2×2.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("description", JSONObject().apply { put("type", "string"); put("description", "What the widget should do.") })
                    put("w_span", JSONObject().apply { put("type", "integer"); put("description", "Width in cells, default 2.") })
                    put("h_span", JSONObject().apply { put("type", "integer"); put("description", "Height in cells, default 2.") })
                    put("page_index", JSONObject().apply { put("type", "integer") })
                    put("row", JSONObject().apply { put("type", "integer") })
                    put("col", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("description"))
            }))

        put(tool("edit_generated_widget",
            "Refine an existing generated widget. Reads its current HTML, applies the change in `instruction`, " +
                    "and writes the new HTML back over the same widget id. Use this WHENEVER the user wants to modify " +
                    "an existing widget ('make my clock darker', 'add seconds to the timer', 'change the bar to red', " +
                    "'fix the alignment'). " +
                    "DO NOT call create_generated_widget for changes — that produces a fresh widget and discards the existing customizations. " +
                    "The placement_id comes from get_layout (filter by type=GENERATED_WIDGET; match against label).",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("placement_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "id of the GENERATED_WIDGET placement to edit (from get_layout).")
                    })
                    put("instruction", JSONObject().apply {
                        put("type", "string")
                        put("description", "Plain-language description of what should change. The current HTML is automatically embedded — you don't need to repeat it.")
                    })
                })
                put("required", JSONArray().put("placement_id").put("instruction"))
            }))

        put(tool("place_app_icon",
            "Place an installed-app icon on the home grid. Use list_installed_apps first if unsure of the package name.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("page_index", JSONObject().apply { put("type", "integer") })
                    put("row", JSONObject().apply { put("type", "integer") })
                    put("col", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("package_name"))
            }))

        put(tool("create_folder",
            "Group two or more installed apps into a folder and place it on the home grid as a single 1×1 cell. " +
                    "Use this when the user says things like 'group all my social apps in a folder' or 'make a Games folder with these apps'. " +
                    "If page/row/col are omitted, an empty 1×1 spot is auto-found across all pages — and a NEW page is automatically appended if none fits.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_names", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply { put("type", "string") })
                        put("description", "Two or more package names of installed apps. Use list_installed_apps if unsure.")
                    })
                    put("folder_name", JSONObject().apply {
                        put("type", "string"); put("description", "Display label for the folder, e.g. 'Social' or 'Games'.")
                    })
                    put("page_index", JSONObject().apply { put("type", "integer") })
                    put("row", JSONObject().apply { put("type", "integer") })
                    put("col", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("package_names"))
            }))

        put(tool("open_app", "Launch an installed app by package name.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().put("package_name"))
            }))

        put(tool("list_installed_apps",
            "List installed launchable apps. Optionally filter by case-insensitive substring on label or package.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply { put("type", "string") })
                })
            }))

        put(tool("get_layout",
            "Return a compact JSON of the current home layout (cols, rows, pages with their placements: id, type, position, size, label).",
            JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }))

        put(tool("remove_cell", "Remove a placement (icon / folder / widget) by its id.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("placement_id", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().put("placement_id"))
            }))

        put(tool("move_cell",
            "Move an EXISTING placement (icon / folder / widget) to a new location WITHOUT regenerating its content. " +
                    "Use this when the user says things like 'move the clock widget to page 3' or 'put my Gmail icon on a new page'. " +
                    "If page_index / row / col are omitted, an empty spot is auto-found on a DIFFERENT page than the placement currently lives on (a new empty page is appended if needed). " +
                    "If page_index is supplied without row/col, the first empty spot on that page is used. " +
                    "If row/col are supplied, the destination must be empty (excluding the moving placement itself).",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("placement_id", JSONObject().apply { put("type", "string") })
                    put("page_index", JSONObject().apply { put("type", "integer") })
                    put("row", JSONObject().apply { put("type", "integer") })
                    put("col", JSONObject().apply { put("type", "integer") })
                })
                put("required", JSONArray().put("placement_id"))
            }))

        put(tool("add_to_folder",
            "Add an installed app to an EXISTING folder. Identify the folder by `folder_id` (preferred — get it from get_layout) or `folder_name` (case-insensitive, exact then substring). " +
                    "By default also removes any existing home-grid icon for the same package so the app doesn't appear twice.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("folder_id", JSONObject().apply { put("type", "string"); put("description", "Placement id from get_layout.") })
                    put("folder_name", JSONObject().apply { put("type", "string"); put("description", "Fallback when id isn't known.") })
                    put("remove_from_home", JSONObject().apply { put("type", "boolean"); put("description", "Default true — also strip any home-grid icon for this package.") })
                })
                put("required", JSONArray().put("package_name"))
            }))

        put(tool("remove_from_folder",
            "Remove an app from a folder. If `to_home` is true, the app is placed as a 1×1 icon on the first free home cell. " +
                    "Folders that drop to 1 item are auto-collapsed back into a plain icon at the same cell; empty folders are deleted.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("folder_id", JSONObject().apply { put("type", "string") })
                    put("folder_name", JSONObject().apply { put("type", "string") })
                    put("to_home", JSONObject().apply { put("type", "boolean"); put("description", "Default false — set true to drop the app onto the home grid as an icon.") })
                })
                put("required", JSONArray().put("package_name"))
            }))

        put(tool("rename_folder",
            "Change a folder's display name.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("new_name", JSONObject().apply { put("type", "string") })
                    put("folder_id", JSONObject().apply { put("type", "string") })
                    put("folder_name", JSONObject().apply { put("type", "string"); put("description", "Current name (used when folder_id is not known).") })
                })
                put("required", JSONArray().put("new_name"))
            }))

        put(tool("rename_page",
            "Set or clear the user-given name for a home page. Pass an empty `new_name` to clear the name (the page falls back to ordinal labelling). Use this whenever the user asks to label or rename a page (\"call page 2 Work\", \"rename my reading page to Long-form\", \"remove the name from page 3\").",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("page_index", JSONObject().apply {
                        put("type", "integer")
                        put("description", "0-based home-page index. Resolve user phrases like 'page 2' to 1, or look up the current name in get_layout.")
                    })
                    put("new_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "New name (max 40 chars). Empty string clears the name.")
                    })
                })
                put("required", JSONArray().put("page_index").put("new_name"))
            }))

        // PLUGINS: BEGIN
        put(tool("get_plugins",
            "List installable JS plugins currently enabled on this device. Each plugin extends the launcher with new capabilities (remote photo libraries, smart-home bridges, etc.) and exposes one or more methods callable from widgets/wallpapers as `await iappyx.plugin('<id>').<method>(args)`. Use BEFORE create_generated_widget when the user mentions a service the launcher might integrate with (Immich, Home Assistant, etc.) — if a relevant plugin is enabled, pass an explicit hint in the widget description so the generator wires up the bridge call directly. Returns {plugins: [{id, name, version, description, capabilities, exposes, aiPrompt}]}.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }))
        // PLUGINS: END

        put(tool("add_to_dock",
            "Place an installed-app icon in the dock at the bottom of the screen. " +
                    "If dock_page_index/slot are omitted, the first free slot is used (a new dock page is appended if all slots on existing dock pages are full).",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("dock_page_index", JSONObject().apply { put("type", "integer"); put("description", "0-based; only N pages exist when N>0.") })
                    put("slot", JSONObject().apply { put("type", "integer"); put("description", "0-based slot column (0..dock_slots-1).") })
                })
                put("required", JSONArray().put("package_name"))
            }))

        put(tool("remove_from_dock",
            "Remove an icon from the dock — match by package_name OR placement_id. Returns the count removed (>1 if the same package was duplicated across dock pages).",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply { put("type", "string") })
                    put("placement_id", JSONObject().apply { put("type", "string") })
                })
            }))

        put(tool("swap_cells",
            "Swap the positions of two existing placements. Both must be the same size (you can't swap a 1×1 icon with a 2×2 widget). Pages may differ — the placements move to each other's pages too.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("placement_id_a", JSONObject().apply { put("type", "string") })
                    put("placement_id_b", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().put("placement_id_a").put("placement_id_b"))
            }))

        put(tool("reorganize_into_folders",
            "Batch-create multiple folders in one call. Each folder spec gives a name and a list of package_names. " +
                    "By default the existing home-grid icons for those packages are removed so the apps end up ONLY in their new folders (no duplicates). " +
                    "Folders are placed on the first free 1×1 spot, appending pages as needed. " +
                    "Use this when the user asks for a categorisation like 'group all my messaging apps' or 'organise my home into Work / Social / Banking folders'.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("folders", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("name", JSONObject().apply { put("type", "string") })
                                put("package_names", JSONObject().apply {
                                    put("type", "array")
                                    put("items", JSONObject().apply { put("type", "string") })
                                })
                            })
                            put("required", JSONArray().put("name").put("package_names"))
                        })
                    })
                    put("remove_originals", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Default true — strip existing home-grid icons for the bundled packages.")
                    })
                })
                put("required", JSONArray().put("folders"))
            }))

        put(tool("generate_wallpaper",
            "Generate a live-wallpaper HTML payload from a plain-language vibe (e.g. 'foggy ocean at dawn', 'lava lamp', 'matrix rain in slow motion'). " +
                "Saves the wallpaper to the launcher's library, auto-selects it, and broadcasts a hot-reload to the running wallpaper engine. " +
                "Result includes wallpaper_active: true|false — when false, iappyxOS Live isn't the user's current wallpaper, so tell them to set it via Launcher Settings → Live wallpaper, or call set_iappyx_wallpaper.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("prompt", JSONObject().apply {
                        put("type", "string")
                        put("description", "What the wallpaper should look/feel like.")
                    })
                })
                put("required", JSONArray().put("prompt"))
            }))

        put(tool("undo_last_action",
            "Reverse the most recent change you made on behalf of the user. " +
                "Reverts: placements (newly-created widgets, app icons, folders), the home / dock layout, " +
                "the active wallpaper / transition / icon-filter selections, AND the contents of any widget / wallpaper / transition file that the previous action edited. " +
                "Call this when the user says \"undo\", \"revert\", \"take that back\", \"go back\", or expresses regret about the change you just applied. " +
                "If the stack is empty (nothing to undo) the tool returns an error — relay that to the user.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }))

        put(tool("iterate_wallpaper",
            "Refine the user's currently-active wallpaper in place — same model as edit_generated_widget but for wallpapers. Reads the current wallpaper's HTML, applies the user's instruction, writes it back, and hot-reloads the running wallpaper engine on the same id. " +
                "Use this whenever the user wants to MODIFY their current wallpaper ('make my wallpaper darker', 'speed up the animation', 'change the colours to teal', 'add more particles'). DO NOT call generate_wallpaper for these — that would create a brand-new wallpaper entry and discard the customisations the user already has. " +
                "Use generate_wallpaper only when the user wants an entirely NEW look from scratch ('I want something completely different', 'replace my wallpaper with rain'). " +
                "wallpaper_id is optional — defaults to the currently-active wallpaper, which matches the natural 'edit my wallpaper' intent.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("instruction", JSONObject().apply {
                        put("type", "string")
                        put("description", "Plain-language description of what should change.")
                    })
                    put("wallpaper_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional: the id of the wallpaper to edit. Defaults to the currently-active wallpaper.")
                    })
                })
                put("required", JSONArray().put("instruction"))
            }))

        put(tool("set_iappyx_wallpaper",
            "Open the system live-wallpaper preview screen pointed at iappyxOS Live, so the user can confirm setting it as their wallpaper. " +
                "Use this only when the user explicitly asks to set the wallpaper, OR after generate_wallpaper returns wallpaper_active=false AND the user confirms they want to see it.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }))

        put(tool("generate_transition",
            "Generate a custom page-transition spec from a plain-language description (e.g. 'cells fall down with a row delay', 'a sine-wave wobble across columns', 'cube turn'). " +
                "The output is a small JSON of math expressions evaluated per frame as the user swipes home pages. " +
                "Saves to the transitions library and AUTO-SETS it as the active page transition. " +
                "Result includes id and title.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("prompt", JSONObject().apply {
                        put("type", "string")
                        put("description", "What the transition should look/feel like.")
                    })
                })
                put("required", JSONArray().put("prompt"))
            }))

        put(tool("iterate_transition",
            "Refine the user's currently-active page transition in place — same model as edit_generated_widget but for transitions. Reads the current transition JSON spec, applies the user's instruction, writes back over the same id, and invalidates the compiled-spec cache so the next page swipe shows the change. " +
                "Use this whenever the user wants to MODIFY the current transition ('make it slower', 'add more wobble', 'reverse the direction', 'tighten the easing'). DO NOT call generate_transition for tweaks — that creates a brand-new transition and discards the existing one. " +
                "Built-in transitions auto-fork on first edit: a writable user copy is created and set active, the original built-in stays untouched. Result includes forked: true on the first edit. " +
                "Hand-coded transitions without a JSON spec (e.g. cube on some builds) can't be edited — the tool returns an error suggesting the user generate a new one instead. " +
                "transition_id is optional; defaults to the currently-active transition.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("instruction", JSONObject().apply {
                        put("type", "string")
                        put("description", "Plain-language description of what should change.")
                    })
                    put("transition_id", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional: id of the transition to edit. Defaults to the currently-active transition.")
                    })
                })
                put("required", JSONArray().put("instruction"))
            }))

        put(tool("generate_icon_filter",
            "Generate a custom icon-style filter from a plain-language description ('cyberpunk neon', '70s film', 'minimalist black-and-white', 'kawaii pastel'). " +
                "Output is a small JSON spec of bake ops (saturation/contrast tweaks, ColorMatrix tints, pixelate, aurora gradient) and an optional per-cell tint. " +
                "It restyles every existing app icon — the icon's identity stays intact, only the colours/texture change. " +
                "Saves to the icon-filter library and AUTO-SETS it as the active icon style. " +
                "Result includes id and title.",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("prompt", JSONObject().apply {
                        put("type", "string")
                        put("description", "What the icon style should look/feel like.")
                    })
                })
                put("required", JSONArray().put("prompt"))
            }))
    }

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
}
