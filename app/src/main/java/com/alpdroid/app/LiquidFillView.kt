package com.alpdroid.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * The app's own mountain mark (ic_launcher_foreground — same shape as the launcher icon and
 * splash screen), filling like liquid from the bottom up as [progress] rises, with a continuously
 * animated wavy top edge. Same on-screen API as the ProgressBar it replaces on the setup screen
 * (`progress`, `isIndeterminate`, `visibility`) so every call site that drives it — the download
 * byte-count poller, the indeterminate-while-extracting/starting-shell states — needed no changes
 * beyond the type of the property itself.
 *
 * Rendering technique: draw the mark once as a dim, always-visible outline, then draw a
 * sine-wave-topped fill shape into an offscreen layer and clip it to the mark's own silhouette
 * with a DST_IN Porter-Duff composite — only the mark's opaque pixels (regardless of the mark's
 * own multiple fill colors) let the liquid layer show through underneath them.
 */
class LiquidFillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            targetLevel = field / 100f
        }

    /** While true, the fill level breathes gently between two levels instead of tracking
     *  [progress] — there's no known total (extracting, or starting a shell) but the animation
     *  still needs to read as "something is happening," not stuck at a fixed, arbitrary height. */
    var isIndeterminate: Boolean = true

    private var targetLevel = 0f
    private var displayedLevel = 0f
    private var wavePhase = 0f

    private var maskBitmap: Bitmap? = null
    private val wavePath = Path()

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 46 }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val wavePaintBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_dim)
        alpha = 200
    }
    private val wavePaintFront = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            wavePhase = it.animatedFraction
            val level = if (isIndeterminate) {
                0.18f + 0.32f * (0.5f + 0.5f * sin(it.animatedFraction * 2 * Math.PI).toFloat())
            } else {
                targetLevel
            }
            // Eased toward the target rather than snapping — a real download's byte-count ticks
            // in uneven bursts, and a liquid level jumping instantly on every tick would look like
            // flickering, not filling.
            displayedLevel += (level - displayedLevel) * 0.12f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // isShown(), not just the isAggregatedVisible default — the platform only calls
        // onVisibilityAggregated() at attach time when the view is actually shown, so a view
        // attached under a currently-GONE ancestor would otherwise never get that callback at all
        // and start animating (and requesting Choreographer frames) while invisible, based on
        // isAggregatedVisible's untouched default of true.
        if (isShown) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    // The setup screen only ever hides this view's *parent* (setupContainer.visibility = GONE),
    // not this view itself, so onDetachedFromWindow() never fires just because setup finished —
    // without this, the animator would keep requesting a Choreographer frame every ~16ms (a
    // continuous CPU wake) for the rest of the app's life, forever after the user only ever saw
    // this view for a few seconds during first-run setup.
    private var isAggregatedVisible = true

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isAggregatedVisible = isVisible
        if (isVisible) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Recycle the outgoing bitmap: each rotation otherwise leaks native pixel memory.
        maskBitmap?.recycle()
        maskBitmap = if (w > 0 && h > 0) rasterizeMask(w, h) else null
    }

    private fun rasterizeMask(w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.apply {
            setBounds(0, 0, w, h)
            draw(Canvas(bitmap))
        }
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        val mask = maskBitmap ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawBitmap(mask, 0f, 0f, outlinePaint)

        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        val fillTop = h * (1f - displayedLevel)
        val amplitude = dp(3f)
        val wavelength = w / 1.4f
        drawWave(canvas, w, h, fillTop, wavelength, amplitude, phaseOffset = 0.5f, paint = wavePaintBack)
        drawWave(canvas, w, h, fillTop, wavelength, amplitude, phaseOffset = 0f, paint = wavePaintFront)
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.restoreToCount(layer)
    }

    private fun drawWave(canvas: Canvas, w: Float, h: Float, fillTop: Float, wavelength: Float, amplitude: Float, phaseOffset: Float, paint: Paint) {
        wavePath.reset()
        wavePath.moveTo(0f, h)
        wavePath.lineTo(0f, fillTop)
        var x = 0f
        while (x <= w) {
            val y = fillTop + sin((x / wavelength) * 2 * Math.PI + (wavePhase + phaseOffset) * 2 * Math.PI).toFloat() * amplitude
            wavePath.lineTo(x, y)
            x += 4f
        }
        wavePath.lineTo(w, h)
        wavePath.close()
        canvas.drawPath(wavePath, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
