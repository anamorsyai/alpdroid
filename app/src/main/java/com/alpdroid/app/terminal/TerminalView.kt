package com.alpdroid.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a [TerminalEmulator]'s screen grid and turns keyboard/touch input into the raw bytes
 * a shell expects on its stdin — arrow keys and friends become the right CSI/SS3 escape
 * sequence (respecting the emulator's application-cursor-keys mode), everything typed through
 * the soft keyboard goes through a small custom [InputConnection] rather than relying on
 * Android's normal text-editing model, which assumes an editable text field, not a live stream.
 */
class TerminalView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var emulator: TerminalEmulator? = null
        set(value) {
            // Detach the previous emulator first: its onAltScreenChanged lambda captures this
            // view (via resizeHandler), so leaving it set keeps posting resizes for a dead
            // session and pins the whole view hierarchy in memory after a tab is replaced.
            if (field !== value) field?.onAltScreenChanged = null
            field = value
            // Reacting to this immediately (not waiting for the next onDraw's own polling check)
            // is what closes the race described on scheduleResize(delayMs=0L)'s own call site
            // below — see onAltScreenChanged's doc comment on TerminalEmulator.
            value?.onAltScreenChanged = { resizeHandler.post { scheduleResize(0L) } }
            requestLayout()
            invalidate()
        }

    /** Raw bytes to write to the pty — key input, IME commits, and extra-key-row buttons all funnel through this. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Cell grid size changed (from a layout pass) — the owner resizes the pty accordingly. */
    var onGridSize: ((rows: Int, cols: Int) -> Unit)? = null

    /** Fired once at the end of a pinch gesture with the new size in sp, for the owner to persist. */
    var onFontScaleChanged: ((sp: Float) -> Unit)? = null

    /** A confident horizontal swipe (not a pinch, not the vertical scrollback drag) — true means
     *  swiped toward the next tab (finger moved left), false means the previous tab. */
    var onSwipeTab: ((next: Boolean) -> Unit)? = null

    /** Fired on every change to [ctrlArmed]/[altArmed], from any source — toggling the on-screen
     *  key, or the armed state getting consumed by the next keystroke in sendControlAware()/
     *  onKeyDown() below — so the on-screen CTRL/ALT buttons can stay visually in sync with the
     *  real state instead of just reflecting whichever tap last touched them. */
    var onModifierStateChanged: (() -> Unit)? = null

    /** Set by the on-screen "Ctrl" extra key; the next single character typed is sent as its control code instead. */
    var ctrlArmed = false
        set(value) {
            field = value
            onModifierStateChanged?.invoke()
        }

    /** Set by the on-screen "Alt" extra key; the next key sent gets an ESC prefix (the standard
     *  "altSendsEscape" encoding most terminal apps already understand for Meta/Alt). */
    var altArmed = false
        set(value) {
            field = value
            onModifierStateChanged?.invoke()
        }

    /** Fired from the selection context menu's "Save to file" — the owner decides where files
     *  live (shared storage vs. app-private) and how to report success/failure, since this view
     *  has no Android storage/toast concerns of its own. */
    var onSaveSelection: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = context.resources.displayMetrics.density * 15f
    }

    /** Each row is already drawn per same-style run (a whole multi-character string in one
     *  drawText call, not glyph-by-glyph — see drawRow below), so Android's own HarfBuzz-based
     *  text shaping already substitutes ligatures for a font that defines them (Fira Code's
     *  "calt"/"liga" features) without any extra work; this only makes that explicit and gives
     *  Settings a way to turn it back off for someone who wants literal separate glyphs instead
     *  (e.g. distinguishing "!=" from a single joined glyph at a glance). */
    fun setLigaturesEnabled(enabled: Boolean) {
        paint.fontFeatureSettings = if (enabled) "\"calt\" 1, \"liga\" 1" else "\"calt\" 0, \"liga\" 0"
        invalidate()
    }
    private var naturalCellWidth = 1f

    /** The width each column is actually drawn/hit-tested at: the font's natural advance stretched
     *  just enough that `cols` columns span the whole view, instead of leaving the sub-cell
     *  remainder (up to one glyph wide) as a dead strip on the right that never resizes with the
     *  window — i.e. justified full-width text. The extra per cell is under one glyph divided by
     *  the column count (~1-2%), so it's invisible per glyph; capped at 5% so a momentarily stale
     *  column count mid-resize/pinch (the pty resize is debounced) can't smear the text. */
    private val cellWidth: Float get() = naturalCellWidth

    /** Horizontal padding that centers the column grid: the sub-glyph remainder of the view width
     *  (floor(width/advance) leaves up to one glyph over) is split evenly left/right instead of
     *  sitting as a dead strip on one side. Pure render/hit-test offset — column count and cell
     *  width are untouched, so what the shell sees never changes. */
    private val gridOffsetX: Float
        get() {
            val cols = emulator?.cols ?: return 0f
            return ((width - cols * naturalCellWidth) / 2f).coerceAtLeast(0f)
        }
    private var cellHeight = 1f
    private var baselineOffset = 0f

    /** Reused across every row of every frame in [drawRow] — filled once per row rather than
     *  allocating a fresh String per same-style run (a row with several color/style changes used
     *  to allocate that many Strings, every single frame, including on every blink tick while
     *  otherwise idle). canvas.drawText(CharArray, ...) draws directly from this with no further
     *  allocation, so a row only grows this buffer the first time it needs to (a wider font/pinch
     *  zoom bumping column count), never on a normal per-frame redraw. */
    private var rowCharBuf = CharArray(0)

    /** Reused across every same-style run of every row of every frame in [drawRow] — the
     *  per-character position array drawPosText needs, grown only the first time a run needs
     *  more room (a wider font/pinch zoom bumping column count), never allocated per-run. */
    private var runPosBuf = FloatArray(0)

    private var scrollOffset = 0 // 0 = live view; >0 = scrolled up into scrollback by that many rows

    // Text selection: a long-press starts it (selectionActive = true while the finger is still
    // down, extended by drag); lifting the finger finalizes it (hasSelection stays true, keeping
    // the highlight and offering the copy/paste/clear menu) until the next tap or action.
    private var selectionActive = false
    private var hasSelection = false
    private var selAnchorRow = 0
    private var selAnchorCol = 0
    private var selEndRow = 0
    private var selEndCol = 0

    // Auto-scroll while dragging a selection near the top/bottom edge, like any native text
    // selection — otherwise selecting anything beyond one screenful was impossible, since a drag
    // could only ever reach cells already visible when the gesture started.
    private var autoScrollDirection = 0 // +1 reveal older content, -1 toward live, 0 none
    private var lastSelectionTouchX = 0f
    private var lastSelectionTouchY = 0f
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val em = emulator
            if (autoScrollDirection == 0 || !selectionActive || em == null) return
            scrollOffset = (scrollOffset + autoScrollDirection).coerceIn(0, em.scrollbackSize())
            val (row, col) = touchToCell(lastSelectionTouchX, lastSelectionTouchY)
            selEndRow = row
            selEndCol = col
            invalidate()
            autoScrollHandler.postDelayed(this, 60)
        }
    }

    // Scrollback search: recomputed only when the query text changes, not on every next/prev step.
    private var searchQuery = ""
    private var searchMatches: List<Int> = emptyList()
    private var searchMatchIndex = -1

    // Blinking block cursor: a plain Handler toggle rather than a ValueAnimator — this only
    // needs to flip a boolean and repaint twice a second, an Animator's overhead buys nothing.
    private var cursorBlinkOn = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            blinkHandler.postDelayed(this, 530)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        recomputeCellMetrics()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blinkHandler.postDelayed(blinkRunnable, 530)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkHandler.removeCallbacks(blinkRunnable)
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        pendingResize?.let { resizeHandler.removeCallbacks(it) }
    }

    /** Backgrounding the app (Home button, switching apps) doesn't detach this view from its
     *  window — only actually finishing the Activity does — so without this, a Handler tick every
     *  530ms would keep firing indefinitely the whole time the app sits in the background doing
     *  nothing visible, which is exactly the kind of small, needless wakeup this app's own
     *  keep-alive/battery-exemption features are otherwise trying to spend power deliberately on.
     *  Called from MainActivity's onPause()/onResume(). */
    fun pauseBlink() = blinkHandler.removeCallbacks(blinkRunnable)

    fun resumeBlink() {
        cursorBlinkOn = true
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.postDelayed(blinkRunnable, 530)
    }

    private fun recomputeCellMetrics() {
        naturalCellWidth = paint.measureText("M")
        // fontSpacing (not the narrower descent-ascent) is the metric Android itself defines as
        // the correct line-to-line advance — using anything narrower packs rows tighter than the
        // glyphs are actually drawn for, which is exactly the kind of mismatch that makes a
        // full-screen ncurses app (vim, htop, less) either clip its last line or leave the real
        // last row of pixels unused despite proot reporting that pty ioctl(TIOCGWINSZ) fits it.
        cellHeight = paint.fontSpacing
        baselineOffset = -paint.fontMetrics.ascent
    }

    fun setTextSizePx(px: Float) {
        paint.textSize = px
        recomputeCellMetrics()
        applyGridSize()
        invalidate()
    }

    fun setTypeface(tf: Typeface) {
        paint.typeface = tf
        recomputeCellMetrics()
        applyGridSize()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyGridSize()
    }

    private var lastReportedRows = -1
    private var lastReportedCols = -1

    /**
     * Text-size changes (the Settings slider, or a pinch) don't change this View's own pixel
     * width/height, so `requestLayout()` alone never re-triggers [onSizeChanged] — the grid
     * needs recomputing directly whenever the cell size changes, not just when the view does.
     *
     * A pinch gesture calls this on every single frame of the gesture (see onScale below), most
     * of which land on the same whole cols/rows count as the frame before (cell size changes
     * continuously; the cell *count* only changes step-wise) — onGridSize only fires on an actual
     * change rather than every frame, since its owner turns each call into a real PTY resize
     * (a FIFO write) that a pinch would otherwise repeat dozens of times a second for nothing.
     *
     * Debounced, with a small hysteresis margin on top: MainActivity's window-inset handling
     * (see applyInsetsManually()) means the soft keyboard no longer changes this view's actual
     * pixel size at all, which was the real source of the "lines disappear on keyboard toggle"
     * saga this pair of mechanisms was built to survive — a single keyboard animation used to
     * drive this view through a dozen-plus distinct intermediate row counts, each one a real
     * SIGWINCH the shell reacted to by printing a fresh prompt line, faster than anything could
     * reasonably debounce around on every device's animation timing. With the keyboard out of the
     * picture, what's left here is normal, low-frequency terminal-emulator hygiene: a pinch
     * gesture calls applyGridSize() on every single frame (see onScale below), and a device
     * rotation can still animate through a couple of intermediate sizes on some OEM skins. Both
     * debouncing (collapse a fast burst into the size it settles on) and hysteresis (ignore a new
     * size until it differs from the last one acted on by a full cell, so a slow multi-frame
     * slide doesn't retrigger on every frame either) stay cheap insurance against those — a real
     * rotation or zoom clears either margin immediately, so nothing here makes them feel slower.
     */
    private val resizeHandler = Handler(Looper.getMainLooper())
    private var pendingResize: Runnable? = null
    // Wall-clock deadline of whatever [pendingResize] currently points at, so scheduleResize()
    // below can tell whether a new request is more urgent than one already waiting rather than
    // always just replacing it — see that function's own doc comment for why that distinction is
    // the actual fix, not a refinement of one.
    private var pendingResizeDueAtMs = Long.MAX_VALUE
    private var stableWidth = -1f
    private var stableHeight = -1f

    /** The grid size this view's own pixels/font would produce with the keyboard completely out
     *  of the picture — kept separate from whatever [scheduleResize] actually sends, since the
     *  keyboard-driven reduction below needs a stable baseline to shrink from and grow back to. */
    private var baseRows = -1
    private var baseCols = -1

    private fun applyGridSize() {
        if (naturalCellWidth <= 0f || cellHeight <= 0f || width <= 0 || height <= 0) return
        if (stableWidth < 0f || kotlin.math.abs(width - stableWidth) >= naturalCellWidth * HYSTERESIS_CELLS) stableWidth = width.toFloat()
        if (stableHeight < 0f || kotlin.math.abs(height - stableHeight) >= cellHeight * HYSTERESIS_CELLS) stableHeight = height.toFloat()
        baseCols = max(1, (stableWidth / naturalCellWidth).toInt())
        baseRows = max(1, (stableHeight / cellHeight).toInt())
        scheduleResize()
    }

    /** How many fewer rows to actually use while the keyboard covers part of the screen — only
     *  while a full-screen program has the alt screen up. A plain shell prompt keeps using
     *  [baseRows] unchanged and relies on [renderShiftPx] instead: that's the pairing that fixed
     *  the historical "lines disappear on every keyboard toggle" bug (a real resize there makes
     *  most shells reprint their prompt on every single toggle). A full-screen TUI is the opposite
     *  case — it's *expected* to redraw itself completely on a resize (that's what SIGWINCH means
     *  to vim/htop/less/opencode), and typically fills the entire screen, so shifting rendering
     *  instead of actually resizing just pushes most of its content off the top of the view with
     *  nothing gained — exactly what made a streaming response disappear behind the keyboard until
     *  it closed again. */
    private fun keyboardAdjustedRows(): Int {
        val em = emulator
        if (em == null || imeCoverPx <= 0 || cellHeight <= 0f || !em.inAltScreen) return baseRows
        val keyboardRows = (imeCoverPx / cellHeight).toInt()
        return (baseRows - keyboardRows).coerceAtLeast(1)
    }

    /** [delayMs] is the normal debounce for anything that fires in a rapid burst (a pinch gesture,
     *  an IME animation) — but exiting a full-screen program is a single, discrete event, not a
     *  burst, and needs the shell's real size restored immediately (0L, from
     *  [TerminalEmulator.onAltScreenChanged] and the onDraw() alt-screen check): the whole point is
     *  closing the window where a command typed right away would still run at the keyboard-reduced
     *  row count.
     *
     *  Whichever request is due to fire SOONEST always wins, rather than whichever one was made
     *  most recently unconditionally cancelling and replacing it: a slower debounced request (this
     *  view resizing, the keyboard animating) landing after a faster one was already scheduled
     *  used to push that faster deadline back out regardless of which one was actually more urgent
     *  — which is exactly what reintroduced the "restoring the shell's size races with typing
     *  straight into it" bug even after the immediate (0-delay) request existed, since a plain
     *  debounced call arriving even a moment later still unconditionally overwrote it. The
     *  scheduled runnable itself always recomputes rows/cols fresh at the moment it actually fires
     *  (not whatever was current back when it was scheduled), so honoring the sooner deadline never
     *  means acting on stale numbers — it only ever changes *when* the eventual resize happens, not
     *  what it resizes to. */
    private fun scheduleResize(delayMs: Long = RESIZE_DEBOUNCE_MS) {
        if (baseRows <= 0 || baseCols <= 0) return
        val dueAt = SystemClock.uptimeMillis() + delayMs
        if (pendingResize != null && dueAt > pendingResizeDueAtMs) return
        pendingResize?.let { resizeHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingResize = null
            pendingResizeDueAtMs = Long.MAX_VALUE
            val em = emulator
            val rows = keyboardAdjustedRows()
            val cols = baseCols
            // If the user had manually scrolled up into history, a resize (keyboard open/close,
            // most commonly) can shift how many rows sit in scrollback vs. the live screen —
            // resizePrimaryScreen() pushes/pulls rows between them to preserve content. scrollOffset
            // is a plain row count measured from live, so without adjusting it by the same amount
            // scrollback grew or shrank by, it would end up pointing at a different absolute
            // position than what was actually on screen a moment ago: from the outside, exactly
            // "the view jumps on its own" every time the keyboard toggles, even though nothing
            // asked it to move. Live view (scrollOffset == 0) is left alone — snapping that to
            // "stay at the bottom" is the one case actually wanted here.
            val oldScrollbackSize = em?.scrollbackSize() ?: 0
            em?.resize(rows, cols)
            if (scrollOffset > 0) {
                val delta = (em?.scrollbackSize() ?: 0) - oldScrollbackSize
                scrollOffset = (scrollOffset + delta).coerceIn(0, em?.scrollbackSize() ?: 0)
            }
            if (rows != lastReportedRows || cols != lastReportedCols) {
                lastReportedRows = rows
                lastReportedCols = cols
                onGridSize?.invoke(rows, cols)
            }
            invalidate()
        }
        pendingResize = runnable
        pendingResizeDueAtMs = dueAt
        resizeHandler.postDelayed(runnable, delayMs)
    }

    private val minTextSizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)
    private val maxTextSizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 40f, resources.displayMetrics)
    private val pxPerSp = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newSize = (paint.textSize * detector.scaleFactor).coerceIn(minTextSizePx, maxTextSizePx)
            if (newSize != paint.textSize) {
                paint.textSize = newSize
                recomputeCellMetrics()
                applyGridSize()
                invalidate()
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            onFontScaleChanged?.invoke(paint.textSize / pxPerSp)
        }
    })

    // A command producing output fast (a large recursive listing, a big/virtual file) calls
    // onPtyOutput() once per ~8KB read — every call queues a Runnable, and a tight enough flood
    // can queue them onto the UI thread's message loop faster than it can drain them, growing
    // without bound. Since every one of those Runnables just re-reads whatever the emulator's
    // *current* state is when it finally runs, only the most recent one queued actually needs to
    // exist; this flag coalesces a whole burst down to a single pending repaint.
    private val ptyOutputPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Called after new bytes were fed into [emulator] — repaints and snaps scroll back to live.
     *  Safe to call from any thread (the reader thread that feeds the emulator calls this
     *  directly): posts the actual state changes onto the UI thread rather than touching
     *  [scrollOffset]/[cursorBlinkOn] or calling the UI-thread-only invalidate() from elsewhere. */
    fun onPtyOutput() {
        if (!ptyOutputPending.compareAndSet(false, true)) return
        post {
            ptyOutputPending.set(false)
            scrollOffset = 0
            // Real terminals snap the cursor solid on activity rather than leaving it mid-blink.
            if (!cursorBlinkOn) {
                cursorBlinkOn = true
                blinkHandler.removeCallbacks(blinkRunnable)
                blinkHandler.postDelayed(blinkRunnable, 530)
            }
            invalidate()
        }
    }

    /**
     * How much the soft keyboard currently covers, in pixels — set by MainActivity from the IME
     * inset. For a plain shell prompt, the terminal's own row/col count and pixel size never
     * change for this (see the [emulator] field's resize() and MainActivity.applyInsetsManually()
     * for why: a real resize means a real SIGWINCH, and most shells react to *every* one by moving
     * the cursor and reprinting the prompt, which is what actually caused lines to visibly
     * disappear across a keyboard toggle — not anything in this app's own scrollback handling).
     * Instead that case gets a pure render-time shift, computed fresh each frame in
     * [renderShiftPx] from wherever the cursor actually is, so the keyboard covering the bottom of
     * an otherwise-idle prompt doesn't yank content upward that was never at risk of being hidden.
     *
     * A full-screen program (alt screen up) goes through [scheduleResize] instead — see
     * [keyboardAdjustedRows] for why a real resize is both safe and necessary there.
     */
    var imeCoverPx: Int = 0
        set(value) {
            field = value.coerceAtLeast(0)
            scheduleResize()
            invalidate()
        }

    /** Pixels to shift rendering up by so the cursor stays visible above the keyboard — 0 when the
     *  keyboard is closed, the cursor already sits above where it would start covering, or a
     *  full-screen program is up (that case is handled by an actual resize in [scheduleResize]
     *  instead; shifting on top of that as well would double-compensate). */
    private fun renderShiftPx(em: TerminalEmulator): Float {
        if (imeCoverPx <= 0 || height <= 0 || em.inAltScreen) return 0f
        val visibleBottom = height - imeCoverPx
        // contentBottomRow() extends a little past cursorRow when the app draws a few more
        // non-blank rows directly under it (a status line, a hint row under an input box) — see
        // its own doc comment for why that's a contiguous walk rather than a whole-screen search.
        val cursorBottom = (em.contentBottomRow() + 1) * cellHeight
        return (cursorBottom - visibleBottom).coerceIn(0f, imeCoverPx.toFloat())
    }

    private var lastInAltScreen = false

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return
        // Catches switching onto a tab that's already showing the alt screen (resetViewState()
        // forces lastInAltScreen back to false so this re-checks against the newly attached
        // emulator) — the one case TerminalEmulator.onAltScreenChanged can't see on its own, since
        // switching tabs re-points `emulator` without ever calling switchAltScreen() itself.
        //
        // Deliberately 0-delay here too, matching onAltScreenChanged's own call site below in the
        // emulator setter — NOT the plain debounced scheduleResize() this used to call. A real
        // alt-screen exit already gets the immediate path via that callback, posted with
        // Handler.post() the instant it happens; but this check ALSO runs on every single onDraw()
        // frame, scheduled via the completely separate Choreographer/VSYNC path Android uses for
        // View invalidation — there is no guaranteed ordering between the two on the same Looper.
        // If this frame's check happened to run first and called the plain debounced
        // scheduleResize() (150ms), it would cancel and replace the callback's own already-pending
        // *immediate* resize with that slower one — silently reintroducing the exact race this was
        // meant to close, just non-deterministically, depending on whether a draw pass or the
        // callback's posted message happened to win the scheduling race on any given run. That
        // "sometimes hangs, sometimes doesn't depending on timing" was reported behavior, not a
        // hypothetical: both paths must request the same 0-delay to ever be safe together.
        if (em.inAltScreen != lastInAltScreen) {
            lastInAltScreen = em.inAltScreen
            scheduleResize(0L)
        }
        canvas.drawColor(TerminalColors.DEFAULT_BG)
        // One atomic snapshot for the whole frame (see renderSnapshot): reading rows, the
        // scrollback size and each row through separate calls let feed()/resize() on the
        // pty-reader thread shrink the grid between two reads and crash on a stale index.
        val snap = em.renderSnapshot()
        val rows = snap.rows
        val scrollbackSize = snap.scrollbackSize
        val shift = renderShiftPx(em)

        canvas.save()
        canvas.translate(gridOffsetX, -shift)

        for (screenRow in 0 until rows) {
            val sourceIndex = screenRow - scrollOffset
            val row: Array<Cell> = if (sourceIndex < 0) {
                val sbIndex = scrollbackSize + sourceIndex
                if (sbIndex < 0 || sbIndex >= snap.scrollbackRows.size) continue else snap.scrollbackRows[sbIndex]
            } else {
                if (sourceIndex >= snap.screenRows.size) continue else snap.screenRows[sourceIndex]
            }
            drawRow(canvas, row, screenRow)
        }

        if (scrollOffset == 0 && snap.cursorVisible && cursorBlinkOn) {
            val cx = snap.cursorCol * cellWidth
            val cy = (snap.cursorRow * cellHeight).roundToInt().toFloat()
            val cyBottom = ((snap.cursorRow + 1) * cellHeight).roundToInt().toFloat()
            paint.color = TerminalColors.CURSOR
            paint.alpha = 170
            canvas.drawRect(cx, cy, cx + cellWidth, cyBottom, paint)
            paint.alpha = 255
        }

        if (hasSelection) drawSelectionOverlay(canvas, em)
        canvas.restore()
    }

    /** Highlights every cell between the (possibly reversed) selection endpoints, drawn as a
     *  translucent overlay on top of the already-rendered rows rather than folded into
     *  [drawRow]'s own per-cell logic — selection is comparatively rare, so keeping the hot
     *  per-frame render path free of an extra check per cell is worth the second pass. */
    private fun drawSelectionOverlay(canvas: Canvas, em: TerminalEmulator) {
        var r1 = selAnchorRow; var c1 = selAnchorCol
        var r2 = selEndRow; var c2 = selEndCol
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            val tr = r1; val tc = c1; r1 = r2; c1 = c2; r2 = tr; c2 = tc
        }
        val scrollbackSize = em.scrollbackSize()
        paint.color = TerminalColors.DEFAULT_FG
        paint.alpha = 80
        for (screenRow in 0 until em.rows) {
            val logicalRow = scrollbackSize + screenRow - scrollOffset
            if (logicalRow < r1 || logicalRow > r2) continue
            val fromCol = if (logicalRow == r1) c1 else 0
            val toCol = if (logicalRow == r2) c2 else em.cols - 1
            val y = (screenRow * cellHeight).roundToInt().toFloat()
            val yBottom = ((screenRow + 1) * cellHeight).roundToInt().toFloat()
            canvas.drawRect(fromCol * cellWidth, y, (toCol + 1) * cellWidth, yBottom, paint)
        }
        paint.alpha = 255
    }

    /** Maps a touch position to a (combined-row, column) cell — the same coordinate space
     *  [TerminalEmulator.textInRange] expects, so a selection survives scrolling while it's held. */
    private fun touchToCell(x: Float, y: Float): Pair<Int, Int> {
        val em = emulator ?: return 0 to 0
        // rows/cols are >= 1 by construction now, but a view laid out at 0 size briefly
        // reports a 0 grid — coerceIn(0, -1) would throw, so bail out explicitly.
        if (em.rows < 1 || em.cols < 1) return 0 to 0
        val screenRow = ((y + renderShiftPx(em)) / cellHeight).toInt().coerceIn(0, em.rows - 1)
        val col = ((x - gridOffsetX) / cellWidth).toInt().coerceIn(0, em.cols - 1)
        val logicalRow = (em.scrollbackSize() + screenRow - scrollOffset).coerceIn(0, em.combinedRowCount() - 1)
        return logicalRow to col
    }

    private fun drawRow(canvas: Canvas, row: Array<Cell>, screenRow: Int) {
        // Rounding each row's top/bottom independently to device pixels (rather than using
        // cellHeight as a fixed offset from a float y) makes row N's bottom edge exactly equal
        // row N+1's top edge — both derived from the same formula on consecutive integers.
        // Without this, adjacent rows' background rects can each round to a slightly different
        // pixel boundary and leave a hairline gap of the base canvas color between them, visible
        // as faint horizontal banding across any large filled area (status bars, `less`, etc).
        val cw = cellWidth
        val y = (screenRow * cellHeight).roundToInt().toFloat()
        val yBottom = ((screenRow + 1) * cellHeight).roundToInt().toFloat()
        if (rowCharBuf.size < row.size) rowCharBuf = CharArray(row.size)
        for (i in row.indices) rowCharBuf[i] = row[i].ch
        var runStart = 0
        while (runStart < row.size) {
            val cell = row[runStart]
            var runEnd = runStart + 1
            while (runEnd < row.size && sameStyle(row[runEnd], cell)) runEnd++
            val x = runStart * cw
            val fg = if (cell.reverse) cell.bg else cell.fg
            val bg = if (cell.reverse) cell.fg else cell.bg
            if (bg != TerminalColors.DEFAULT_BG) {
                paint.color = bg
                canvas.drawRect(x, y, x + (runEnd - runStart) * cw, yBottom, paint)
            }
            var blank = !cell.underline
            if (blank) for (i in runStart until runEnd) if (rowCharBuf[i] != ' ') { blank = false; break }
            if (blank) { runStart = runEnd; continue }
            paint.color = fg
            paint.isFakeBoldText = cell.bold
            paint.isUnderlineText = cell.underline
            // Pinning every glyph to its own exact cell position (rather than one drawText() call
            // per run, letting the font's default shaping space it) is what fixed box-drawing/
            // block/shade art (opencode's own splash logo, `tree`, progress bars) drifting and
            // misaligning between rows: those glyphs' natural advance widths don't match this
            // grid's fixed cellWidth (measured off "M") the way plain Latin text's do, and that
            // drift compounds across a run with nothing forcing the next run to reconcile against
            // it. drawPosText batches that same per-glyph pinning into ONE draw call per run
            // instead of one call per character — same visual correctness, without the per-frame
            // cost of up to rows*cols individual drawText calls on every single repaint (visible
            // as dropped frames/stutter under fast output — opencode streaming, `cat` of a big
            // file, a large `ls`). Deprecated since API 30 with no non-deprecated replacement that
            // keeps per-glyph positions instead of re-shaping the whole run (which is the exact
            // bug this avoids); still fully functional at this app's targetSdk.
            val runLen = runEnd - runStart
            if (runPosBuf.size < runLen * 2) runPosBuf = FloatArray(runLen * 2)
            for (i in 0 until runLen) {
                runPosBuf[i * 2] = (runStart + i) * cw
                runPosBuf[i * 2 + 1] = y + baselineOffset
            }
            @Suppress("DEPRECATION")
            canvas.drawPosText(rowCharBuf, runStart, runLen, runPosBuf, paint)
            runStart = runEnd
        }
    }

    private fun sameStyle(a: Cell, b: Cell): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.bold == b.bold && a.underline == b.underline && a.reverse == b.reverse

    // --- Touch: drag to scroll into scrollback ---

    private var scrollRemainderPx = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scrollRemainderPx = 0f
            return false
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // GestureDetector never delivers onScroll() once onLongPress has already fired for a
            // gesture (its ACTION_MOVE handling short-circuits while in that state), so extending
            // a selection can't be driven from here — onTouchEvent() below reads the drag
            // position directly instead. selectionActive is still checked as a plain "not a
            // scrollback drag" guard, in case a stray call ever arrives while it's active.
            if (selectionActive) return true
            val em = emulator ?: return false
            // GestureDetector's dy is (previousY - currentY) — i.e. already flipped from the
            // finger's actual direction of travel. Subtracting it (not adding) is what makes
            // dragging the finger DOWN reveal OLDER scrollback (scrollOffset increases), matching
            // the natural "pull down to see what's above" scrolling every other app on the
            // device uses — adding it had every drag moving the view backwards from what the
            // finger did.
            // Carried across events: a normal-speed drag delivers well under half a row of
            // distance per event, so rounding each event on its own to whole rows gave 0 every
            // time and dropped that distance for good — a slow or ordinary drag scrolled nothing,
            // only a fast flick did. Whole rows are taken out of the running total and the
            // remainder stays for the next event.
            scrollRemainderPx += dy
            val deltaRows = (scrollRemainderPx / cellHeight).toInt()
            if (deltaRows == 0) return true
            scrollRemainderPx -= deltaRows * cellHeight
            if (em.inAltScreen) {
                // scrollOffset has no meaning here — the alt screen has no scrollback of its own
                // to reveal (it's a fixed-size buffer the running program redraws entirely on its
                // own), so dragging inside a full-screen app used to just do nothing at all.
                if (em.mouseReportingMode != 0 && em.mouseSgrMode) {
                    // Once a program has turned on mouse tracking, a real terminal reports wheel
                    // scrolling as its own distinct button codes (64/65) instead of arrow keys —
                    // that's the only way such a program can tell "the user scrolled" apart from
                    // "the user pressed the up/down arrow key". Sending plain arrow keys here
                    // instead (as this used to) is what made opencode v2 treat a scroll drag as
                    // up/down navigation between its own subagent/shell screens rather than a
                    // scroll of the current one. Gated on SGR (mode 1006) specifically: legacy
                    // X10 wheel encoding turned out not to register as a scroll at all for
                    // opencode v2 (nothing happened rather than the wrong thing happening), so
                    // only the more modern/unambiguous SGR form is trusted here — everything
                    // else falls back to the arrow-key path below, which at least does something.
                    // Confirmed inverted on-device against the "look further up" convention the
                    // rest of this gesture follows (finger down = drag reveals older content) —
                    // opencode v2 reads wheel-down (65) as "go up"/wheel-up (64) as "go down",
                    // the opposite of the usual physical-mouse-wheel mapping.
                    val button = if (deltaRows > 0) 65 else 64
                    val (row, col) = touchToCell(e2.x, e2.y)
                    // One pty write for the whole gesture, not one per row: up to 40 separate
                    // writes per motion event used to flood the pty and stall the reader.
                    val sb = StringBuilder()
                    repeat(min(abs(deltaRows), 40)) {
                        em.mouseClickSequence(row, col, button = button, pressed = true)?.let { sb.append(it) }
                    }
                    if (sb.isNotEmpty()) send(sb.toString().toByteArray(Charsets.UTF_8))
                } else {
                    // No mouse tracking: fall back to repeated up/down-arrow presses ("alternate
                    // scroll mode"), which is what vim/htop/less/a plain pager react to for
                    // scrolling their own content — respecting DECCKM (application cursor keys)
                    // so a program that switched cursor keys to application mode still sees the
                    // sequence it's actually expecting. Same direction convention as the
                    // scrollback case above (finger down = "look further up").
                    val seq = if (em.applicationCursorKeys) {
                        if (deltaRows > 0) "\u001BOA" else "\u001BOB"
                    } else {
                        if (deltaRows > 0) "\u001B[A" else "\u001B[B"
                    }
                    repeat(min(abs(deltaRows), 40)) { send(seq.toByteArray(Charsets.UTF_8)) }
                }
                return true
            }
            scrollOffset = (scrollOffset - deltaRows).coerceIn(0, em.scrollbackSize())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (hasSelection) {
                clearSelection()
                return true
            }
            // A full-screen TUI that turned on mouse click reporting (opencode's own clickable
            // "expand thinking" sections, an fzf-style picker, mouse-aware vim/less) wants a tap
            // reported as a click at that cell — previously every tap unconditionally requested
            // focus and popped the keyboard instead, so there was no way to actually click anything
            // inside such a program; tapping a button in it just opened the keyboard over it.
            val em = emulator
            if (em != null && em.mouseReportingMode != 0) {
                val (row, col) = touchToCell(e.x, e.y)
                em.mouseClickSequence(row, col, pressed = true)?.let { send(it.toByteArray(Charsets.UTF_8)) }
                em.mouseClickSequence(row, col, pressed = false)?.let { send(it.toByteArray(Charsets.UTF_8)) }
                return true
            }
            requestFocus()
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (emulator == null) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val (row, col) = touchToCell(e.x, e.y)
            // Long-pressing near either end of an already-finalized selection fine-tunes that
            // endpoint instead of always starting a fresh single-cell selection — previously
            // there was no way to nudge a selection's boundary slightly without redoing the
            // whole drag from scratch.
            if (hasSelection) {
                val distToAnchor = kotlin.math.abs(row - selAnchorRow) + kotlin.math.abs(col - selAnchorCol)
                val distToEnd = kotlin.math.abs(row - selEndRow) + kotlin.math.abs(col - selEndCol)
                if (minOf(distToAnchor, distToEnd) <= NEAR_ENDPOINT_THRESHOLD) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    selectionActive = true
                    if (distToEnd > distToAnchor) {
                        // The anchor end is the one being grabbed — the current end point
                        // becomes the new fixed far side.
                        selAnchorRow = selEndRow; selAnchorCol = selEndCol
                    }
                    selEndRow = row; selEndCol = col
                    lastSelectionTouchX = e.x; lastSelectionTouchY = e.y
                    invalidate()
                    return
                }
            }
            selectionActive = true
            hasSelection = true
            selAnchorRow = row; selAnchorCol = col
            selEndRow = row; selEndCol = col
            // showSelectionMenu() (called once the finger lifts) anchors itself to this position —
            // a plain long-press with no drag afterward never reaches the ACTION_MOVE handler that
            // would otherwise be the only thing updating it, so without setting it here too, the
            // menu would anchor to wherever the *previous* selection's drag happened to end (or
            // 0,0, before any selection at all) instead of where this long-press actually was.
            lastSelectionTouchX = e.x; lastSelectionTouchY = e.y
            invalidate()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selectionActive || scaleGestureDetector.isInProgress) return false
            // Require a clearly horizontal, fast flick so this never fires for an ordinary
            // vertical drag-to-scroll gesture that happens to end with some sideways motion.
            if (kotlin.math.abs(velocityX) < 800f || kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 2f) return false
            onSwipeTab?.invoke(velocityX < 0)
            return true
        }
    })

    /** Anchoring straight to `this` (the whole TerminalView) is what made the menu ignore where
     *  the long-press actually was: PopupMenu/PopupWindow only flips itself above its anchor when
     *  there isn't room below *that anchor's own bounds*, and an anchor the size of the entire
     *  terminal always "has room below" as far as that check is concerned — so the menu kept
     *  opening downward and getting clipped/scrollable near the bottom of the screen regardless of
     *  where the finger actually was. A 1x1 marker view placed at the real touch position gives it
     *  an anchor small enough for that same built-in logic to work as intended: room below the
     *  point → opens down, not enough room → opens up instead, automatically. */
    private fun showSelectionMenu() {
        val parentGroup = parent as? ViewGroup
        val anchor: View = if (parentGroup != null) {
            View(context).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                x = this@TerminalView.x + lastSelectionTouchX
                y = this@TerminalView.y + lastSelectionTouchY
            }.also { parentGroup.addView(it) }
        } else {
            this
        }
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Copy")
        popup.menu.add(0, 2, 1, "Paste")
        popup.menu.add(0, 5, 2, "Save to file")
        popup.menu.add(0, 3, 3, "Clear")
        popup.menu.add(0, 4, 4, "Reset terminal")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> copySelectionToClipboard()
                2 -> pasteFromClipboard()
                3 -> emulator?.clearAll()
                4 -> sendText("\u001Bc") // full VT100 reset, interpreted guest-side
                5 -> emulator?.textInRange(selAnchorRow, selAnchorCol, selEndRow, selEndCol)?.let { onSaveSelection?.invoke(it) }
            }
            true
        }
        popup.setOnDismissListener {
            clearSelection()
            if (anchor !== this) parentGroup?.removeView(anchor)
        }
        popup.show()
    }

    /** Silent success/failure here was genuinely undiagnosable from a bug report alone — a Toast
     *  either way turns "copy doesn't work" into an immediately visible fact: nothing landed on
     *  the clipboard vs. it did but was unexpectedly empty (a single long-press with no drag
     *  lands on exactly one cell, and a blank/space cell there is a real, if easy to hit, case
     *  that used to copy nothing with zero indication why). */
    private fun copySelectionToClipboard() {
        val text = emulator?.textInRange(selAnchorRow, selAnchorCol, selEndRow, selEndCol).orEmpty()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("terminal selection", text))
        Toast.makeText(
            context,
            if (text.isEmpty()) "Selection was empty — nothing copied" else "Copied ${text.length} character${if (text.length == 1) "" else "s"}",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun clearSelection() {
        hasSelection = false
        invalidate()
    }

    /** Called right before attaching a different tab's emulator — scrollOffset, any active
     *  selection, and any in-progress scrollback search are this view's own state, not the
     *  emulator's, so swapping [emulator] alone leaves them pointing at whatever they were
     *  mid-way through on the *previous* tab: a scrolled-up view lands on the new tab already
     *  scrolled by an unrelated amount (clamped against its own, different scrollback size),
     *  cursor hidden since it only draws when scrollOffset is 0, a stale selection can still
     *  reference the old tab's coordinates, and — left uncleared — a still-open search bar would
     *  reuse the old tab's match row indices against the new tab's unrelated scrollback the next
     *  time ▲/▼ is pressed without retyping the query. */
    fun resetViewState() {
        scrollOffset = 0
        hasSelection = false
        selectionActive = false
        autoScrollDirection = 0
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
        clearSearch()
        // Forces onDraw()'s alt-screen-transition check to compare against the newly attached
        // tab's own state on the very next frame instead of whatever the previous tab last left
        // this at — otherwise switching onto a tab that's already alt-screen while this was true
        // from the last one looks like "no change" and skips the resize it may actually need
        // (a different keyboard-covered row count, if the two tabs' sessions differ).
        lastInAltScreen = false
        invalidate()
    }

    /** Jumps the view to the next (or previous) match for [query] in scrollback+screen. Returns
     *  false when there are no matches at all, so the caller can show "not found". */
    fun search(query: String, forward: Boolean): Boolean {
        val em = emulator ?: return false
        if (query != searchQuery) {
            searchQuery = query
            searchMatches = em.findRows(query)
            searchMatchIndex = -1
        }
        if (searchMatches.isEmpty()) return false
        searchMatchIndex = if (searchMatchIndex < 0) {
            if (forward) 0 else searchMatches.size - 1
        } else if (forward) {
            (searchMatchIndex + 1) % searchMatches.size
        } else {
            (searchMatchIndex - 1 + searchMatches.size) % searchMatches.size
        }
        val targetRow = searchMatches[searchMatchIndex]
        scrollOffset = (em.scrollbackSize() - targetRow).coerceIn(0, em.scrollbackSize())
        invalidate()
        return true
    }

    fun clearSearch() {
        searchQuery = ""
        searchMatches = emptyList()
        searchMatchIndex = -1
    }

    private fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        // Bracketed paste (mode 2004) — every readline-based program (bash, a Python REPL, and
        // every interactive CLI agent tool this app exists to run) that enables it wants pasted
        // text delivered as one marked block. Without this wrapping, each newline in a pasted
        // multi-line prompt read back as a literal Enter keypress, submitting every line as its
        // own separate, incomplete command/message instead of landing as one editable block.
        if (emulator?.bracketedPasteEnabled == true) {
            send("\u001B[200~".toByteArray(Charsets.UTF_8))
            sendText(text)
            send("\u001B[201~".toByteArray(Charsets.UTF_8))
        } else {
            sendText(text)
        }
    }

    /** requestFocus() alone doesn't reopen the IME once it's been dismissed (back button, etc.)
     *  while this view already has focus — a tap needs to ask explicitly every time. */
    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    /** Explicit keyboard show/hide, independent of a tap's own click-vs-keyboard decision — once a
     *  running program turns on mouse click reporting (see onSingleTapUp), every tap anywhere in
     *  it (its own text input box included) reports as a click instead of opening the keyboard,
     *  which is correct for the parts of its UI that are genuinely clickable but leaves no way at
     *  all to type into the parts that aren't (an app's own prompt/input field). A dedicated
     *  keyboard toggle key (see MainActivity's extra-keys row) is what real terminal apps use to
     *  cover exactly this — it isn't a tap on the terminal, so it never gets reinterpreted as a
     *  click regardless of what the running program has enabled. */
    fun toggleKeyboard() {
        // isActive() (used elsewhere for "does this view currently own the IME connection") isn't
        // enough here — it stays true even after the keyboard has been dismissed while this view
        // keeps focus (back button, in particular), which would make this toggle never re-show it
        // in exactly that case. The actual on-screen visibility is what WindowInsetsCompat reports.
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        val currentlyVisible = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        requestFocus()
        if (currentlyVisible) {
            imm?.hideSoftInputFromWindow(windowToken, 0)
        } else {
            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 2) {
            // The first finger down already started gestureDetector tracking a single-finger
            // gesture, long-press timer included — real fingers essentially never land in the
            // exact same frame, so there's always a brief window where only one finger is down
            // before a pinch's second finger arrives. Without an explicit cancel here, that timer
            // can still fire mid-pinch (the pointerCount==1 guard below only stops *new* events
            // from reaching it, not an already-scheduled callback), opening the selection/context
            // menu right in the middle of a zoom gesture instead of it registering as a pinch.
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            gestureDetector.onTouchEvent(cancel)
            cancel.recycle()
        }
        scaleGestureDetector.onTouchEvent(event)
        // A second finger mid-pinch would otherwise also register as a scroll/tap on the pan
        // gesture detector (it tracks the touch focal point, not pointer count) — suppress it
        // for the duration of the pinch and while a second pointer is down.
        if (!scaleGestureDetector.isInProgress && event.pointerCount == 1) {
            gestureDetector.onTouchEvent(event)
        }
        if (selectionActive) {
            when (event.actionMasked) {
                // Reading the drag position straight from the raw event — GestureDetector's own
                // onScroll() is never delivered once onLongPress has already fired for this same
                // gesture (see the comment in onScroll above), which is why extending a selection
                // used to be dead code: the highlight always stayed pinned to the initial
                // long-pressed cell no matter how far the finger dragged afterward.
                MotionEvent.ACTION_MOVE -> {
                    lastSelectionTouchX = event.x
                    lastSelectionTouchY = event.y
                    val (row, col) = touchToCell(event.x, event.y)
                    selEndRow = row
                    selEndCol = col
                    invalidate()
                    val edgeZone = cellHeight * 1.5f
                    val newDirection = when {
                        event.y < edgeZone -> 1
                        event.y > height - edgeZone -> -1
                        else -> 0
                    }
                    if (newDirection != autoScrollDirection) {
                        autoScrollDirection = newDirection
                        autoScrollHandler.removeCallbacks(autoScrollRunnable)
                        if (newDirection != 0) autoScrollHandler.post(autoScrollRunnable)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selectionActive = false
                    autoScrollDirection = 0
                    autoScrollHandler.removeCallbacks(autoScrollRunnable)
                    showSelectionMenu()
                }
            }
        }
        return true
    }

    // --- Keyboard input ---

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                sendControlAware(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { sendControlAware(byteArrayOf(0x7F)) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                // Some IMEs (notably Gboard, for plain letters in some layouts/modes) deliver a
                // key press through here as a hardware-style KeyEvent instead of commitText —
                // routed to onKeyDown() below, which is itself control/alt-aware, rather than
                // straight to send()/sendText(), or CTRL/ALT armed from the extra-keys row would
                // silently never apply to anything typed on the keyboard itself.
                if (event.action == KeyEvent.ACTION_DOWN) return this@TerminalView.onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    fun send(bytes: ByteArray) {
        scrollOffset = 0
        onInput?.invoke(bytes)
    }

    fun sendText(text: String) = send(text.toByteArray(Charsets.UTF_8))

    /**
     * What every key path should call instead of [send]/[sendText] directly: the extra-keys row
     * (ESC, TAB, arrows, HOME/END, the punctuation keys), the soft keyboard's own IME text/key
     * paths above, and [onKeyDown] below. A single point that applies [ctrlArmed] (turning a
     * single a-z byte into its control code) and [altArmed] (prefixing an ESC byte, the standard
     * "altSendsEscape" encoding), then unconditionally clears both — so tapping CTRL or ALT then
     * any *other* key still consumes the armed state instead of leaving it latched onto whatever
     * unrelated key gets pressed next.
     */
    fun sendControlAware(bytes: ByteArray) {
        var result = bytes
        if (ctrlArmed && result.size == 1) {
            val c = result[0].toInt().toChar().lowercaseChar()
            if (c in 'a'..'z') result = byteArrayOf((c.code - 'a'.code + 1).toByte())
        }
        if (altArmed) result = byteArrayOf(0x1B) + result
        ctrlArmed = false
        altArmed = false
        send(result)
    }

    fun sendControlAware(text: String) = sendControlAware(text.toByteArray(Charsets.UTF_8))

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val appCursor = emulator?.applicationCursorKeys == true
        val prefix = if (appCursor) "\u001BO" else "\u001B["
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> sendControlAware(byteArrayOf(0x0D))
            KeyEvent.KEYCODE_DEL -> sendControlAware(byteArrayOf(0x7F))
            KeyEvent.KEYCODE_FORWARD_DEL -> sendControlAware("\u001B[3~")
            KeyEvent.KEYCODE_TAB -> sendControlAware(byteArrayOf(0x09))
            KeyEvent.KEYCODE_ESCAPE -> sendControlAware(byteArrayOf(0x1B))
            KeyEvent.KEYCODE_DPAD_UP -> sendControlAware("$prefix" + "A")
            KeyEvent.KEYCODE_DPAD_DOWN -> sendControlAware("$prefix" + "B")
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendControlAware("$prefix" + "C")
            KeyEvent.KEYCODE_DPAD_LEFT -> sendControlAware("$prefix" + "D")
            KeyEvent.KEYCODE_PAGE_UP -> sendControlAware("\u001B[5~")
            KeyEvent.KEYCODE_PAGE_DOWN -> sendControlAware("\u001B[6~")
            KeyEvent.KEYCODE_MOVE_HOME -> sendControlAware("\u001B[H")
            KeyEvent.KEYCODE_MOVE_END -> sendControlAware("\u001B[F")
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0 && event.action == KeyEvent.ACTION_DOWN) {
                    if (event.isCtrlPressed && unicode in 'a'.code..'z'.code) {
                        ctrlArmed = false
                        altArmed = false
                        send(byteArrayOf((unicode - 'a'.code + 1).toByte()))
                    } else {
                        sendControlAware(String(Character.toChars(unicode)))
                    }
                } else {
                    return super.onKeyDown(keyCode, event)
                }
            }
        }
        return true
    }

    companion object {
        /** Combined row+column distance (in cells) within which a long-press is treated as
         *  grabbing an existing selection's endpoint rather than starting a fresh one. */
        private const val NEAR_ENDPOINT_THRESHOLD = 3

        /** How long applyGridSize() waits for the pixel size to stop changing before actually
         *  resizing the emulator and the PTY — covers a pinch gesture's continuous stream of calls
         *  and the odd OEM rotation animation. The soft keyboard no longer resizes this view at
         *  all (see MainActivity.applyInsetsManually()), so this only needs to comfortably outlast
         *  those, not survive an unpredictable keyboard animation across every device. */
        private const val RESIZE_DEBOUNCE_MS = 150L

        /** Hysteresis margin, in cell-size multiples: a new width/height only replaces the one
         *  actually acted on once it differs by at least this many full cells — keeps a slow
         *  multi-frame rotation animation from retriggering the debounce on every intermediate
         *  frame. A real rotation or pinch changes size by many cells at once and clears this
         *  immediately either way. */
        private const val HYSTERESIS_CELLS = 1f
    }
}
