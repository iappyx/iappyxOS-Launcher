/*
 * MIT License - Copyright (c) 2026 iappyx
 */
package com.iappyx.launcher.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.LinkMovementMethod
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iappyx.launcher.R
import com.iappyx.launcher.command.CommandSession

/**
 * Chat-style UI for the AI Command Bar. Lives at pager position 0 — the page
 * to the LEFT of the home screens.
 *
 * Bound to a [CommandSession] (owned by the activity so conversation state
 * survives page swipes and recycle / re-bind).
 */
class CommandPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Display modes the panel can be in. The toggle chip-row at the top
     *  (and the empty-state CTA) flip between them.
     *
     *   - [Mode.CHAT] — title + subtitle + chat scrollback + input + send.
     *     Visible only when an API key is configured.
     *   - [Mode.EMPTY] — replaces chat with the "AI chat is off" card when
     *     no API key. The card itself includes the 3 manual options inline,
     *     so the user can pick a manual flow without a single extra tap.
     *   - [Mode.MANUAL_PICKER] — shows the 3 manual options as a centred
     *     list, used when the user taps the "Manual" toggle chip while a
     *     key IS configured. Same rows as empty state, just standalone.
     *   - [Mode.MANUAL] — replaces chat with [ManualAiCanvas] for the
     *     selected artefact type. Reached from a manual option row.
     */
    private enum class Mode { CHAT, EMPTY, MANUAL_PICKER, MANUAL }

    private val density = resources.displayMetrics.density
    private val header: LinearLayout
    private val subtitle: TextView
    private val toggleRow: LinearLayout
    private val chatChip: TextView
    private val manualChip: TextView
    private lateinit var manualPicker: View
    private val list: RecyclerView
    private val input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var attachBtn: ImageView
    private lateinit var pendingImageRow: LinearLayout
    private lateinit var pendingImageThumb: ImageView
    private lateinit var pendingImageRemove: TextView
    /** Base64-encoded JPEG of the user's currently-pending image attachment.
     *  Set when [launchImagePickerForAttachment] succeeds, cleared on send,
     *  Cancel chip tap, or × on the chip. Mutually exclusive with the input
     *  field being empty — both image and text-only messages are valid. */
    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null
    private val workingPill: LinearLayout
    private val workingPillDot: View
    private val workingPillText: TextView
    private lateinit var suggestions: LinearLayout
    private val adapter = LineAdapter()
    private var workingPulse: android.animation.ValueAnimator? = null
    /** Last time [CommandSession.Listener.onProgress] updated the working
     *  pill text. Used to throttle updates to ~5 fps without dropping the
     *  monotonicity of the count. */
    private var lastProgressTextUpdateMs: Long = 0L
    /** Views toggled together with [Mode.CHAT] — chat scrollback,
     *  suggestions, and input row. */
    private val chatViews = mutableListOf<View>()
    private lateinit var emptyState: View
    private lateinit var manualCanvas: ManualAiCanvas

    private var mode: Mode = Mode.CHAT
    private var session: CommandSession? = null

    init {
        setBackgroundColor(Palette.bgHome(context))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            // The pager already pads for system bars (top + bottom). Avoid
            // double-counting — apply ONLY the extra IME inset that exceeds
            // the nav bar. Top stays 0 (handled by pager).
            val extraBottom = (ime.bottom - bars.bottom).coerceAtLeast(0)
            v.setPadding(v.paddingLeft, 0, v.paddingRight, extraBottom)
            insets
        }

        // Header — title only. The "working" pill lives in its own row below
        // the subtitle so it never squeezes or shifts the title when it
        // appears/disappears.
        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), (20 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_auto_awesome)
            imageTintList = ColorStateList.valueOf(Palette.textPrimary(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val s = (26 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (10 * density).toInt()
            }
        })
        val title = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_hero_title)
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            )
        }
        header.addView(title)
        root.addView(header)

        subtitle = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_hero_subtitle)
            setTextColor(Palette.textSecondary(context))
            textSize = 12f
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), (12 * density).toInt())
        }
        root.addView(subtitle)

        // Mode toggle chip-row — Chat | Manual. Visible when API key is
        // present so the user can pick either flow at any time. Without a
        // key, the "Chat" chip is greyed (dim alpha + non-clickable); the
        // empty state card is what surfaces the "Use manual AI" CTA.
        toggleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * density).toInt(), 0,
                (20 * density).toInt(), (8 * density).toInt())
        }
        chatChip = makeToggleChip(context.getString(com.iappyx.launcher.R.string.cmd_mode_chat), primary = true) { setMode(Mode.CHAT) }
        // Manual chip → inline picker view (no popup). Selecting a row
        // there enters the canvas. Back from the canvas returns to chat
        // (or empty state if no key).
        manualChip = makeToggleChip(context.getString(com.iappyx.launcher.R.string.cmd_mode_manual), primary = false) { setMode(Mode.MANUAL_PICKER) }
        toggleRow.addView(chatChip)
        toggleRow.addView(manualChip)
        root.addView(toggleRow)

        // "Thinking…" pill that fades in while the AI is working. A pulsing
        // accent dot + label so it's impossible to miss; label updates with
        // the current step (e.g. "Generating widget…", "Placing app…"). Its
        // own row so the title layout stays stable when work starts/stops.
        workingPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent(context), 0x33))
                setStroke((1 * density).toInt(), Palette.accent(context))
            }
            val hp = (12 * density).toInt(); val vp = (6 * density).toInt()
            setPadding(hp, vp, hp, vp)
            alpha = 0f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        }
        workingPillDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.accent(context))
            }
            val s = (10 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (8 * density).toInt()
            }
        }
        workingPillText = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_thinking)
            setTextColor(Palette.accent(context))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        workingPill.addView(workingPillDot)
        workingPill.addView(workingPillText)
        root.addView(workingPill)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            adapter = this@CommandPanel.adapter
            val sp = (16 * density).toInt()
            setPadding(sp, 0, sp, sp)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(list)

        // Build input + send first so the suggestion chips below can reference them.
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), 0, (12 * density).toInt(), (16 * density).toInt())
        }
        // "+" button — opens the system photo picker. Tapping while a tool-loop
        // runs is a no-op (sendBtn flips into a Cancel chip; the user has to
        // wait or cancel before attaching an image).
        attachBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_image)
            imageTintList = ColorStateList.valueOf(Palette.accent(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    Palette.accent(context), 0x1F))
                setStroke((1 * density).toInt(),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.accent(context), 0x66))
            }
            val s = (40 * density).toInt(); val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                val sess = session
                if (sess != null && sess.isWorking) return@setOnClickListener
                launchImagePickerForAttachment()
            }
        }
        input = EditText(context).apply {
            setHint(com.iappyx.launcher.R.string.cmd_input_hint)
            setHintTextColor(Palette.textSecondary(context))
            setTextColor(Palette.textPrimary(context))
            textSize = 15f
            setSingleLine()
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            val hp = (16 * density).toInt(); val vp = (10 * density).toInt()
            setPadding(hp, vp, hp, vp)
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendBtn.performClick(); true } else false
            }
        }
        sendBtn = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.action_send)
            setTextColor(Palette.bgHome(context))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Palette.accent(context))
            }
            val hp = (16 * density).toInt(); val vp = (10 * density).toInt()
            setPadding(hp, vp, hp, vp)
            isClickable = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = (8 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                // Dual-purpose: while a tool-loop is running, the button
                // is the cancel control. Otherwise it sends. The label /
                // colour are updated in onWorking() to make the mode obvious.
                val s = session ?: return@setOnClickListener
                if (s.isWorking) {
                    s.cancel()
                    return@setOnClickListener
                }
                val txt = input.text?.toString().orEmpty().trim()
                val img = pendingImageBase64
                val mime = pendingImageMime
                if (txt.isNotEmpty() || img != null) {
                    s.send(txt, img, mime)
                    input.setText("")
                    clearPendingImage()
                    suggestions.visibility = GONE
                    // Drop the IME so the streaming AI response + tool-use
                    // chips aren't hidden behind the keyboard.
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(input.windowToken, 0)
                    input.clearFocus()
                }
            }
        }
        inputRow.addView(attachBtn); inputRow.addView(input); inputRow.addView(sendBtn)

        // Pending-image chip row — appears above [inputRow] when the user
        // has attached an image but not yet sent. Shows a small thumbnail and
        // a × button to discard. Hidden by default.
        pendingImageRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (16 * density).toInt(), 0,
                (16 * density).toInt(), (8 * density).toInt(),
            )
            visibility = GONE
        }
        pendingImageThumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 8 * density)
                }
            }
            val s = (44 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (10 * density).toInt()
            }
        }
        val pendingImageLabel = TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_image_attached)
            setTextColor(Palette.textSecondary(context))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        pendingImageRemove = TextView(context).apply {
            text = "×"
            setTextColor(Palette.textPrimary(context))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.bgCell(context))
                setStroke((1 * density).toInt(), Palette.separator(context))
            }
            val s = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            isClickable = true; isFocusable = true
            setOnClickListener { clearPendingImage() }
        }
        pendingImageRow.addView(pendingImageThumb)
        pendingImageRow.addView(pendingImageLabel)
        pendingImageRow.addView(pendingImageRemove)

        // Suggestion chips above the input row — built last so they can
        // reference the now-initialized `input` + `sendBtn`.
        // Wrapped in a HorizontalScrollView so longer chip labels never wrap
        // vertically when three chips don't fit side-by-side.
        suggestions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Breathing room at the row edges so the first/last chip don't
            // butt up against the screen edge.
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        val suggestionsScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * density).toInt() }
            addView(
                suggestions,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        listOf(
            context.getString(com.iappyx.launcher.R.string.cmd_suggest_water_widget),
            context.getString(com.iappyx.launcher.R.string.cmd_suggest_ocean_wallpaper),
            context.getString(com.iappyx.launcher.R.string.cmd_suggest_add_maps),
            context.getString(com.iappyx.launcher.R.string.cmd_suggest_weather),
        ).forEach { text ->
            val chip = TextView(context).apply {
                this.text = text
                // Accent-tinted text + faint accent-tinted fill — visually
                // distinct from the neutral input field below, and reads as
                // "tap me, I'll fill the box" rather than another input.
                setTextColor(Palette.accent(context))
                textSize = 13f
                setSingleLine(true)
                ellipsize = null
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent(context), 0x1F))
                    setStroke(
                        (1 * density).toInt(),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent(context), 0x66),
                    )
                }
                val hp = (14 * density).toInt(); val vp = (8 * density).toInt()
                setPadding(hp, vp, hp, vp)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginStart = (4 * density).toInt(); lp.marginEnd = (4 * density).toInt()
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    input.setText(text); input.setSelection(text.length); sendBtn.performClick()
                }
            }
            suggestions.addView(chip)
        }
        root.addView(suggestionsScroll)
        root.addView(pendingImageRow)
        root.addView(inputRow)

        // Track which views to hide when no API key is set. The header /
        // subtitle / working pill stay visible (the empty-state card sits
        // below them; the title + tagline still describe what this tab is).
        chatViews.add(list)
        chatViews.add(suggestionsScroll)
        chatViews.add(inputRow)

        // Empty-state card — built once, shown only when [refresh] sees
        // no API key. Includes the 3 manual options inline.
        emptyState = buildEmptyState(context)
        root.addView(emptyState, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // Manual picker — same 3 rows, shown when the Manual chip is tapped
        // while a key IS configured. Replaces the popup that used to live
        // here; rows are inline and tappable in one go.
        manualPicker = buildManualPicker(context)
        root.addView(manualPicker, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        // Manual AI canvas — built once, configured per-type when the user
        // enters Manual mode. Hidden by default. Lives at the same level as
        // the chat list / empty state so the title + toggle row sit above it.
        manualCanvas = ManualAiCanvas(context).apply {
            visibility = View.GONE
            onLeave = {
                // Snap back to whichever state the API key implies. If a
                // key is present, the user lands on the chat with their
                // history intact; otherwise the empty state shows.
                val keyPresent = (context as? com.iappyx.launcher.LauncherActivity)
                    ?.hasApiKey() == true
                setMode(if (keyPresent) Mode.CHAT else Mode.EMPTY)
            }
        }
        root.addView(manualCanvas, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        addView(root)
        refresh()
    }

    /** Public entry — lets [CommandPanelHost.enterManualMode] (and via that,
     *  [LauncherActivity.enterManualAiMode]) switch the panel into the
     *  manual flow for a specific artefact type. Called from the
     *  empty-state CTA, the toggle chip, and AddToHomeSheet's "Generate
     *  with external AI" submenu. */
    fun enterManualMode(
        type: ManualAiCanvas.Type,
        placement: ManualAiCanvas.PlacementTarget? = null,
        edit: ManualAiCanvas.WidgetEdit? = null,
    ) {
        manualCanvas.configure(type, placement, edit)
        setMode(Mode.MANUAL)
    }

    private fun makeToggleChip(
        label: String, primary: Boolean, onClick: () -> Unit,
    ): TextView {
        val context = this.context
        val dp = density
        return TextView(context).apply {
            text = label; textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            val hp = (14 * dp).toInt(); val vp = (6 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = (8 * dp).toInt()
            layoutParams = lp
            // Initial selected vs. unselected styling — paintToggleChips
            // recomputes when the active mode changes.
            tag = primary
        }
    }

    /** Re-paint the toggle chips to match the active mode + key state. */
    private fun paintToggleChips() {
        val ctx = context
        val dp = density
        val accent = Palette.accent(ctx)
        val keyPresent = (ctx as? com.iappyx.launcher.LauncherActivity)?.hasApiKey() == true
        // Chat chip — disabled-style when no key (manual is the only path).
        val chatActive = mode == Mode.CHAT
        chatChip.alpha = if (keyPresent) 1f else 0.4f
        chatChip.isClickable = keyPresent
        chatChip.background = chipBackground(chatActive, accent, dp)
        chatChip.setTextColor(if (chatActive) Palette.bgHome(context) else accent)
        // Manual chip — always available. Both MANUAL_PICKER and MANUAL
        // count as "active manual flow" since they're consecutive steps
        // in the same user intent.
        val manualActive = mode == Mode.MANUAL || mode == Mode.MANUAL_PICKER
        manualChip.background = chipBackground(manualActive, accent, dp)
        manualChip.setTextColor(if (manualActive) Palette.bgHome(context) else accent)
    }

    private fun chipBackground(filled: Boolean, accent: Int, dp: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 999f
            if (filled) setColor(accent)
            else setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x1F))
            setStroke((1 * dp).toInt(),
                androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x66))
        }

    private fun setMode(target: Mode) {
        // Skip Chat mode when no key — fall back to empty state.
        val activity = context as? com.iappyx.launcher.LauncherActivity
        val effective = if (target == Mode.CHAT && activity?.hasApiKey() != true) Mode.EMPTY else target
        mode = effective
        applyModeVisibility()
        paintToggleChips()
    }

    private fun applyModeVisibility() {
        val showHeader = mode == Mode.CHAT
        header.visibility = if (showHeader) View.VISIBLE else View.GONE
        subtitle.visibility = if (showHeader) View.VISIBLE else View.GONE
        for (v in chatViews) v.visibility = if (mode == Mode.CHAT) View.VISIBLE else View.GONE
        emptyState.visibility = if (mode == Mode.EMPTY) View.VISIBLE else View.GONE
        manualPicker.visibility = if (mode == Mode.MANUAL_PICKER) View.VISIBLE else View.GONE
        manualCanvas.visibility = if (mode == Mode.MANUAL) View.VISIBLE else View.GONE
        // Toggle chips stay visible in all modes — they're how the user
        // switches between Chat and Manual without leaving the tab.
    }

    /** Recompute whether the API key is set and pick the correct mode.
     *  Called from [bind] (initial), [onAttachedToWindow] (when the panel
     *  re-attaches after a tab switch), and from
     *  [CommandPanelHost.refreshChatPane] / [CommandPanelHost.showPane]
     *  when the user selects the AI tab. Never overrides Manual mode — a
     *  user mid-paste shouldn't lose their input because Settings just
     *  added a key. */
    fun refresh() {
        // Preserve in-flight manual flows — MANUAL_PICKER is the user
        // mid-pick, MANUAL is mid-paste/mid-prompt. Don't yank them back
        // to chat just because Settings flipped a key.
        if (mode == Mode.MANUAL || mode == Mode.MANUAL_PICKER) {
            paintToggleChips()
            return
        }
        val activity = context as? com.iappyx.launcher.LauncherActivity
        val keyPresent = activity?.hasApiKey() == true
        setMode(if (keyPresent) Mode.CHAT else Mode.EMPTY)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    /** "API key not set" card. Shows the explainer + Open Settings CTA, then
     *  inline manual-AI options so the user can start a manual flow without
     *  any extra tap (no popup). */
    private fun buildEmptyState(context: Context): View {
        val dp = density
        val activity = context as? com.iappyx.launcher.LauncherActivity
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (32 * dp).toInt(),
                (28 * dp).toInt(), (32 * dp).toInt())
        }
        // Centred icon badge — same accent treatment AddToHomeSheet uses.
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_auto_awesome)
            imageTintList = ColorStateList.valueOf(Palette.accent(context))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    Palette.accent(context), 0x33))
            }
            val s = (72 * dp).toInt(); val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        card.addView(TextView(context).apply {
            text = "AI chat is off"
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        })
        card.addView(TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_no_key_message)
            setTextColor(Palette.textSecondary(context))
            textSize = 13f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        })
        card.addView(makeCtaButton(context, context.getString(com.iappyx.launcher.R.string.action_open_settings), primary = true) {
            activity?.startActivity(android.content.Intent(
                activity, com.iappyx.launcher.SettingsActivity::class.java,
            ))
        })
        // Inline manual options — header + 3 rows. No popup, no extra tap.
        card.addView(sectionDivider(context, context.getString(com.iappyx.launcher.R.string.cmd_or_use_manual), dp))
        for (row in buildManualOptionRows(context)) card.addView(row)
        scroll.addView(card, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return scroll
    }

    /** Standalone manual picker — same 3 rows as the empty state, but
     *  shown by tapping the "Manual" toggle chip while an API key IS
     *  configured. Replaces the popup. Selecting a row enters the canvas. */
    private fun buildManualPicker(context: Context): View {
        val dp = density
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (32 * dp).toInt(),
                (28 * dp).toInt(), (32 * dp).toInt())
        }
        card.addView(TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_manual_title)
            setTextColor(Palette.textPrimary(context))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(context).apply {
            setText(com.iappyx.launcher.R.string.cmd_manual_subtitle)
            setTextColor(Palette.textSecondary(context))
            textSize = 13f
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            lp.bottomMargin = (12 * dp).toInt()
            layoutParams = lp
        })
        for (row in buildManualOptionRows(context)) card.addView(row)
        scroll.addView(card, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        return scroll
    }

    /** Three Material-You row cards — Widget / Wallpaper / Page transition.
     *  Selecting one enters [Mode.MANUAL] for that type. Shared between
     *  empty state and manual picker so the UX stays consistent. */
    private fun buildManualOptionRows(context: Context): List<View> {
        data class OptionSpec(
            val iconRes: Int,
            val title: String,
            val subtitle: String,
            val type: ManualAiCanvas.Type,
        )
        val specs = listOf(
            OptionSpec(R.drawable.ic_widgets,
                context.getString(com.iappyx.launcher.R.string.cmd_option_widget_title),
                context.getString(com.iappyx.launcher.R.string.cmd_option_widget_subtitle),
                ManualAiCanvas.Type.WIDGET),
            OptionSpec(R.drawable.ic_image,
                context.getString(com.iappyx.launcher.R.string.cmd_option_wallpaper_title),
                context.getString(com.iappyx.launcher.R.string.cmd_option_wallpaper_subtitle),
                ManualAiCanvas.Type.WALLPAPER),
            OptionSpec(R.drawable.ic_swap_horiz,
                context.getString(com.iappyx.launcher.R.string.cmd_option_transition_title),
                context.getString(com.iappyx.launcher.R.string.cmd_option_transition_subtitle),
                ManualAiCanvas.Type.TRANSITION),
            OptionSpec(R.drawable.ic_auto_awesome,
                context.getString(com.iappyx.launcher.R.string.cmd_option_icon_title),
                context.getString(com.iappyx.launcher.R.string.cmd_option_icon_subtitle),
                ManualAiCanvas.Type.ICON_FILTER),
        )
        return specs.map { makeManualOptionRow(context, it.iconRes, it.title, it.subtitle) {
            enterManualMode(it.type)
        } }
    }

    private fun makeManualOptionRow(
        context: Context, iconRes: Int, title: String, subtitle: String, onClick: () -> Unit,
    ): View {
        val dp = density
        val accent = Palette.accent(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(Palette.bgCell(context))
                setStroke((1 * dp).toInt(),
                    androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.textPrimary(context), 0x22))
            }
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (8 * dp).toInt()
            layoutParams = lp
        }
        card.addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x33))
            }
            val s = (44 * dp).toInt(); val pad = (10 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginEnd = (14 * dp).toInt()
            }
        })
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        text.addView(TextView(context).apply {
            this.text = title
            setTextColor(Palette.textPrimary(context))
            textSize = 15f; setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(TextView(context).apply {
            this.text = subtitle
            setTextColor(Palette.textSecondary(context))
            textSize = 12f
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        card.addView(text)
        return card
    }

    private fun sectionDivider(context: Context, label: String, dp: Float): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (28 * dp).toInt()
            lp.bottomMargin = (4 * dp).toInt()
            layoutParams = lp
        }
        fun line() = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, (1 * dp).toInt(), 1f)
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                Palette.textPrimary(context), 0x22))
        }
        row.addView(line())
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Palette.textSecondary(context))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        })
        row.addView(line())
        return row
    }

    private fun makeCtaButton(
        context: Context, label: String, primary: Boolean, onClick: () -> Unit,
    ): TextView {
        val dp = density
        return TextView(context).apply {
            text = label; textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 999f
                if (primary) {
                    setColor(Palette.accent(context))
                } else {
                    setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        Palette.accent(context), 0x1F))
                    setStroke((1 * dp).toInt(),
                        androidx.core.graphics.ColorUtils.setAlphaComponent(
                            Palette.accent(context), 0x66))
                }
            }
            setTextColor(if (primary) Palette.bgHome(context) else Palette.accent(context))
            val hp = (28 * dp).toInt(); val vp = (12 * dp).toInt()
            setPadding(hp, vp, hp, vp)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }
    }


    /** Launch the system photo picker via the host activity. Resizes the
     *  chosen image to ~1568px long-edge, JPEG quality 85, base64-encodes,
     *  and stores it as pending. UI shows the thumbnail chip + reveals the
     *  pending row. Decode + resize runs on a worker thread so the picker's
     *  return doesn't jank the UI. */
    private fun launchImagePickerForAttachment() {
        val activity = context as? com.iappyx.launcher.LauncherActivity ?: return
        activity.launchImagePicker { uri ->
            if (uri == null) return@launchImagePicker
            // Worker thread for decode + resize + encode. Both BitmapFactory
            // and Base64.encodeToString are CPU-bound; doing this on the main
            // thread can stall for ~200-500 ms on a large source image.
            Thread({
                val (b64, mime, thumbBmp) = encodeImageForUpload(uri) ?: return@Thread
                post {
                    pendingImageBase64 = b64
                    pendingImageMime = mime
                    pendingImageThumb.setImageBitmap(thumbBmp)
                    pendingImageRow.visibility = VISIBLE
                }
            }, "iappyx-cmd-img").start()
        }
    }

    /** Clear any pending attachment + hide the chip row. Called on send,
     *  on × tap, and when the panel resets / detaches. */
    private fun clearPendingImage() {
        pendingImageBase64 = null
        pendingImageMime = null
        pendingImageThumb.setImageDrawable(null)
        pendingImageRow.visibility = GONE
    }

    /** Decode [uri], scale to fit within [MAX_IMAGE_DIM] on long edge,
     *  re-compress as JPEG q85, and base64-encode. Returns
     *  (base64, "image/jpeg", thumbnailBitmap) or null on any failure.
     *  The thumbnail is the same scaled bitmap — small enough (≤1568 px) to
     *  use directly as the pending-chip preview without a second decode. */
    private fun encodeImageForUpload(
        uri: android.net.Uri,
    ): Triple<String, String, android.graphics.Bitmap>? = try {
        // Two-pass decode: bounds first to compute inSampleSize, then real
        // decode with that sampler so we don't allocate a full-resolution
        // bitmap for a 12-MP source just to immediately scale it down.
        val cr = context.contentResolver
        val opts = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        cr.openInputStream(uri).use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        }
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) {
            null
        } else {
            var sample = 1
            while (srcW / (sample * 2) >= MAX_IMAGE_DIM
                && srcH / (sample * 2) >= MAX_IMAGE_DIM) sample *= 2
            val real = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val decoded = cr.openInputStream(uri).use { input ->
                android.graphics.BitmapFactory.decodeStream(input, null, real)
            }
            if (decoded == null) {
                null
            } else {
                val long = maxOf(decoded.width, decoded.height)
                val scaled = if (long > MAX_IMAGE_DIM) {
                    val ratio = MAX_IMAGE_DIM.toFloat() / long
                    val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                    val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                    val s = android.graphics.Bitmap.createScaledBitmap(decoded, w, h, true)
                    if (s !== decoded) decoded.recycle()
                    s
                } else decoded
                val baos = java.io.ByteArrayOutputStream()
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                Triple(b64, "image/jpeg", scaled)
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("iappyxCmdPanel", "image decode/encode failed: ${t.message}")
        null
    }

    /** Bind to a session (called from the host activity). The panel forwards
     *  session events to its conversation list. */
    /** Pre-fill the input box from outside (e.g. a Generate card on another
     *  tab routed back to AI chat). Caret lands at the end so the user can
     *  immediately keep typing. */
    fun setInputText(text: String) {
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
        input.requestFocus()
    }

    fun bind(session: CommandSession) {
        this.session = session
        session.listener = object : CommandSession.Listener {
            override fun onClear() { adapter.clear() }
            override fun onProgress(chars: Int) {
                // Throttle to ~5 fps so the working pill doesn't thrash —
                // text invalidates a layout pass on every set. The stream
                // emits ~15 chunks/sec, so at 200 ms we render every third
                // chunk; the count still feels live without burning CPU.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastProgressTextUpdateMs < 200) return
                lastProgressTextUpdateMs = now
                workingPillText.text = context.getString(
                    com.iappyx.launcher.R.string.cmd_generating_chars, chars,
                )
            }
            override fun onLine(line: CommandSession.Line) {
                // Reflect current activity in the working-pill label so the
                // user sees what the AI is doing right now, not just a vague
                // spinner.
                when (line) {
                    is CommandSession.Line.Tool -> workingPillText.text = "${line.summary}…"
                    is CommandSession.Line.Assistant -> workingPillText.setText(com.iappyx.launcher.R.string.cmd_replying)
                    else -> {}
                }
                adapter.append(line)
                list.post { list.scrollToPosition(adapter.itemCount - 1) }
            }
            override fun onWorking(working: Boolean) {
                if (working) startWorkingPulse() else stopWorkingPulse()
                // While working, repurpose the send button as a Cancel
                // control — fully active, just with a different label and
                // a muted background so it's distinct from the green Send.
                sendBtn.alpha = 1f
                sendBtn.isEnabled = true
                sendBtn.setText(
                    if (working) com.iappyx.launcher.R.string.cmd_cancel_btn
                    else com.iappyx.launcher.R.string.action_send,
                )
                (sendBtn.background as? GradientDrawable)?.setColor(
                    if (working) Palette.separatorStrong(context)
                    else Palette.accent(context),
                )
                input.isEnabled = !working
                input.alpha = if (working) 0.5f else 1f
            }
        }
    }

    private fun startWorkingPulse() {
        // Reset label to a generic state — onLine will refine it as tools fire.
        workingPillText.text = "Thinking…"
        workingPill.visibility = View.VISIBLE
        workingPill.animate().alpha(1f).setDuration(180L).start()
        workingPulse?.cancel()
        workingPulse = android.animation.ValueAnimator.ofFloat(0.4f, 1f, 0.4f).apply {
            duration = 1100L
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { v ->
                val a = v.animatedValue as Float
                workingPillDot.alpha = a
                val s = 0.7f + a * 0.5f
                workingPillDot.scaleX = s; workingPillDot.scaleY = s
            }
            start()
        }
    }

    private fun stopWorkingPulse() {
        workingPulse?.cancel(); workingPulse = null
        workingPillDot.alpha = 1f
        workingPillDot.scaleX = 1f; workingPillDot.scaleY = 1f
        workingPill.animate().alpha(0f).setDuration(220L)
            .withEndAction { workingPill.visibility = View.GONE }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The infinite working-pulse animator otherwise keeps firing into a
        // detached panel for the rest of the activity's lifetime.
        workingPulse?.cancel(); workingPulse = null
    }

    companion object {
        /** Long-edge cap (px) for image attachments before re-compression.
         *  1568 px matches Anthropic's recommended preprocessing target —
         *  larger sources offer no accuracy gain for vision and bloat the
         *  base64 payload (1.34× JPEG bytes). */
        private const val MAX_IMAGE_DIM = 1568
    }

    /** [imageView] is non-null only on the user view type (type 0); the
     *  bubble for that type is a vertical LinearLayout containing an optional
     *  thumbnail above the text. Other view types use just a TextView. */
    private class LineHolder(
        val frame: FrameLayout,
        val tv: TextView,
        val imageView: ImageView? = null,
    ) : RecyclerView.ViewHolder(frame)

    private inner class LineAdapter : RecyclerView.Adapter<LineHolder>() {
        private val items = mutableListOf<CommandSession.Line>()
        fun append(line: CommandSession.Line) {
            items.add(line); notifyItemInserted(items.size - 1)
        }
        /** Drop every row. Used by Settings → Clear chat history; the session
         *  has already wiped SQLite + the in-memory API messages by the time
         *  this fires. */
        fun clear() {
            val n = items.size
            if (n == 0) return
            items.clear()
            notifyItemRangeRemoved(0, n)
        }
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is CommandSession.Line.User -> 0
            is CommandSession.Line.Assistant -> 1
            is CommandSession.Line.Tool -> 2
            is CommandSession.Line.Error -> 3
            CommandSession.Line.Working -> 4
        }
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
            val ctx = parent.context; val dp = ctx.resources.displayMetrics.density
            // Wrap each text bubble in a FrameLayout so we can left/right align
            // user vs. assistant messages via Gravity (RV.LayoutParams has none).
            val frame = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val m = (4 * dp).toInt(); topMargin = m; bottomMargin = m
                }
            }
            // User-message bubble is unique: it can carry an image thumbnail
            // above the text. We build a vertical LinearLayout that owns the
            // bubble background+padding, and put both ImageView and TextView
            // inside. Other view types stick to a single TextView (the prior
            // shape — preserved to keep diffs minimal and binding fast).
            if (viewType == 0) {
                val pad = (12 * dp).toInt()
                val bubble = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(Palette.accent(ctx))
                    }
                    setPadding(pad, pad, pad, pad)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.END,
                    )
                }
                val iv = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    adjustViewBounds = true
                    visibility = View.GONE
                    val maxSide = (220 * dp).toInt()
                    maxWidth = maxSide
                    maxHeight = maxSide
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, 8 * dp)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = (8 * dp).toInt() }
                }
                val tv = TextView(ctx).apply {
                    textSize = 14f
                    setTextColor(Palette.bgHome(ctx))
                    movementMethod = LinkMovementMethod.getInstance()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                bubble.addView(iv); bubble.addView(tv)
                frame.addView(bubble)
                return LineHolder(frame, tv, iv)
            }
            val tv = TextView(ctx).apply {
                textSize = 14f
                val p = (12 * dp).toInt(); setPadding(p, p, p, p)
                movementMethod = LinkMovementMethod.getInstance()
            }
            val align = when (viewType) {
                1 -> Gravity.START         // assistant → left
                else -> Gravity.START      // tool / error / working → left
            }
            tv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                align,
            )
            when (viewType) {
                1 -> { // assistant
                    tv.background = GradientDrawable().apply {
                        cornerRadius = 16 * dp; setColor(Palette.bgCell(ctx))
                    }
                    tv.setTextColor(Palette.textPrimary(ctx))
                }
                2 -> { // tool
                    tv.setTextColor(Palette.textSecondary(ctx))
                    tv.textSize = 12f
                }
                3 -> { // error
                    tv.background = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(Color.parseColor("#33FF6B6B"))
                        setStroke((1 * dp).toInt(), Color.parseColor("#FF6B6B"))
                    }
                    tv.setTextColor(Color.parseColor("#FFE0E0"))
                }
                4 -> { tv.text = "…"; tv.setTextColor(Palette.textSecondary(ctx)) }
            }
            frame.addView(tv)
            return LineHolder(frame, tv)
        }

        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            val tv = holder.tv
            when (val line = items[position]) {
                is CommandSession.Line.User -> {
                    tv.text = line.text
                    tv.visibility = if (line.text.isEmpty()) View.GONE else View.VISIBLE
                    val iv = holder.imageView
                    if (iv != null) {
                        if (line.imageBase64 != null) {
                            // Decode-on-bind is acceptable: the bitmap is at most
                            // ~1568 px JPEG (~700 KB ARGB_8888 in memory), and
                            // RecyclerView's view pool bounds the live count.
                            // Wrapped in try/catch — corrupt base64 from a
                            // restored DB row should silently hide the thumb,
                            // not crash the chat.
                            try {
                                val bytes = android.util.Base64.decode(
                                    line.imageBase64, android.util.Base64.DEFAULT,
                                )
                                val bmp = android.graphics.BitmapFactory
                                    .decodeByteArray(bytes, 0, bytes.size)
                                if (bmp != null) {
                                    iv.setImageBitmap(bmp)
                                    iv.visibility = View.VISIBLE
                                } else {
                                    iv.setImageDrawable(null)
                                    iv.visibility = View.GONE
                                }
                            } catch (t: Throwable) {
                                iv.setImageDrawable(null)
                                iv.visibility = View.GONE
                            }
                        } else {
                            iv.setImageDrawable(null)
                            iv.visibility = View.GONE
                        }
                    }
                }
                is CommandSession.Line.Assistant -> tv.text = line.text
                is CommandSession.Line.Tool -> tv.text = "↳  ${line.summary}"
                is CommandSession.Line.Error -> tv.text = line.text
                CommandSession.Line.Working -> {}
            }
        }
    }
}
