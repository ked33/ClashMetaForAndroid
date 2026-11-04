package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.View
import com.github.kr328.clash.common.compat.getDrawableCompat

class ProxyView(
    context: Context,
    config: ProxyViewConfig,
) : View(context), Choreographer.FrameCallback {

    private val titleLine = MarqueeLine()
    private val subtitleLine = MarqueeLine()
    private val visibleRect = Rect()
    private var frameScheduled = false
    private var cachedDelay = ""
    private var cachedTextSize = -1f
    private var cachedWidth = -1
    private var delayWidth = 0f
    private var delayCount = 0

    var marqueeAllowed = false
        set(value) {
            if (field == value) return
            field = value
            resetMarquee()
            invalidate()
        }

    init {
        background = context.getDrawableCompat(config.clickableBackground)
    }

    var state: ProxyViewState? = null
        set(value) {
            field = value
            cachedTextSize = -1f
            resetMarquee()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        resetMarquee()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (state != null) {
            resetMarquee()
            invalidate()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (state != null) {
            resetMarquee()
            invalidate()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (state != null) {
            resetMarquee()
            invalidate()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (marqueeAllowed && isAttachedToWindow && windowVisibility == VISIBLE && hasWindowFocus()) {
            invalidate()
        } else {
            resetMarquee()
        }
    }

    private fun canMarquee(): Boolean = marqueeAllowed && state?.selected == true &&
            isAttachedToWindow && windowVisibility == VISIBLE && hasWindowFocus() &&
            isShown && getLocalVisibleRect(visibleRect)

    private fun resetMarquee() {
        if (frameScheduled) {
            Choreographer.getInstance().removeFrameCallback(this)
            frameScheduled = false
        }
        titleLine.resetAnimation()
        subtitleLine.resetAnimation()
    }

    private fun scheduleFrame(delayMillis: Long) {
        if (frameScheduled || delayMillis == Long.MAX_VALUE) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallbackDelayed(this, delayMillis)
    }

    constructor(context: Context) : this(context, ProxyViewConfig(context, 2))
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val state = state ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                resources.displayMetrics.widthPixels
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                MeasureSpec.getSize(widthMeasureSpec)
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        state.paint.apply {
            reset()

            textSize = state.config.textSize

            getTextBounds("Stub!", 0, 1, state.rect)
        }

        val textHeight = state.rect.height()
        val exceptHeight = (state.config.layoutPadding * 2 +
                state.config.contentPadding * 2 +
                textHeight * 2 +
                state.config.textMargin).toInt()

        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED ->
                exceptHeight
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY ->
                exceptHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else ->
                throw IllegalArgumentException("invalid measure spec")
        }

        setMeasuredDimension(width, height)
    }

    override fun draw(canvas: Canvas) {
        val state = state ?: return super.draw(canvas)

        if (state.update(false))
            postInvalidateOnAnimation()

        val width = width.toFloat()
        val height = height.toFloat()

        val paint = state.paint

        paint.reset()

        paint.color = state.background
        paint.style = Paint.Style.FILL

        // draw background
        canvas.apply {
            if (state.config.proxyLine==1) {
                drawRect(0f, 0f, width, height, paint)
            } else {
                val path = state.path

                path.reset()

                path.addRoundRect(
                    state.config.layoutPadding,
                    state.config.layoutPadding,
                    width - state.config.layoutPadding,
                    height - state.config.layoutPadding,
                    state.config.cardRadius,
                    state.config.cardRadius,
                    Path.Direction.CW,
                )

                paint.setShadowLayer(
                    state.config.cardRadius,
                    state.config.cardOffset,
                    state.config.cardOffset,
                    state.config.shadow
                )

                drawPath(path, paint)

                clipPath(path)
            }
        }

        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val state = state ?: return

        val paint = state.paint

        val width = width.toFloat()
        val height = height.toFloat()

        paint.reset()
        paint.textSize = state.config.textSize

        // Keep the system font's baseline, matching the existing card height in onMeasure.
        val textOffset = (paint.descent() + paint.ascent()) / 2
        paint.typeface = state.config.textTypeface
        paint.isAntiAlias = true
        paint.color = state.controls

        // measure delay text bounds
        if (cachedDelay != state.delayText || cachedTextSize != paint.textSize || cachedWidth != this.width) {
            cachedDelay = state.delayText
            cachedTextSize = paint.textSize
            cachedWidth = this.width
            delayCount = paint.breakText(
                state.delayText,
                true,
                (width - state.config.layoutPadding * 2 - state.config.contentPadding * 2)
                    .coerceAtLeast(0f),
                null,
            )
            delayWidth = paint.measureText(state.delayText, 0, delayCount)
        }

        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding * 2 -
                delayWidth -
                state.config.textMargin * 2
                )
            .coerceAtLeast(0f)

        titleLine.update(state.title, mainTextWidth, paint)
        subtitleLine.update(state.subtitle, mainTextWidth, paint)
        val scrolling = canMarquee()
        if (!scrolling) resetMarquee()
        val now = if (scrolling) SystemClock.uptimeMillis() else 0L
        val speed = 30f * resources.displayMetrics.density / 1000f
        val gap = mainTextWidth / 3f

        // draw delay
        canvas.apply {
            val x = width - state.config.layoutPadding - state.config.contentPadding - delayWidth
            val y = height / 2f - textOffset

            drawText(state.delayText, 0, delayCount, x, y, paint)
        }

        val x = state.config.layoutPadding + state.config.contentPadding
        val titleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f - textOffset
        val subtitleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset
        val titleDelay = titleLine.draw(canvas, paint, x, titleY, height, scrolling, now, speed, gap)
        val subtitleDelay = subtitleLine.draw(canvas, paint, x, subtitleY, height, scrolling, now, speed, gap)
        scheduleFrame(minOf(titleDelay, subtitleDelay))
    }

    /** Measurement and ellipsis allocation happen only when text or geometry changes. */
    private class MarqueeLine {
        private var text = ""
        private var ellipsized = ""
        private var textSize = -1f
        private var availableWidth = -1f
        private var textWidth = 0f
        private var startedAt = 0L

        fun update(value: String, width: Float, paint: TextPaint) {
            if (text == value && availableWidth == width && textSize == paint.textSize) return
            text = value
            availableWidth = width
            textSize = paint.textSize
            textWidth = paint.measureText(value)
            ellipsized = TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString()
            resetAnimation()
        }

        fun resetAnimation() {
            startedAt = 0L
        }

        fun draw(
            canvas: Canvas, paint: TextPaint, x: Float, y: Float, height: Float,
            scrolling: Boolean, now: Long, speed: Float, gap: Float,
        ): Long {
            if (!scrolling || availableWidth <= 0f || textWidth <= availableWidth) {
                canvas.drawText(ellipsized, x, y, paint)
                return Long.MAX_VALUE
            }
            if (startedAt == 0L) startedAt = now
            val distance = textWidth + gap
            val travelMillis = kotlin.math.ceil(distance / speed).toLong().coerceAtLeast(1L)
            val elapsed = (now - startedAt) % (PAUSE_MILLIS + travelMillis)
            val offset = if (elapsed < PAUSE_MILLIS) 0f else (elapsed - PAUSE_MILLIS) * speed
            val saved = canvas.save()
            canvas.clipRect(x, 0f, x + availableWidth, height)
            canvas.drawText(text, x - offset, y, paint)
            if (offset + availableWidth > distance) {
                canvas.drawText(text, x - offset + distance, y, paint)
            }
            canvas.restoreToCount(saved)
            return if (elapsed < PAUSE_MILLIS) PAUSE_MILLIS - elapsed else 0L
        }

        companion object {
            private const val PAUSE_MILLIS = 1200L
        }
    }
}
