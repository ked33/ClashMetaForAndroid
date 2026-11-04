package com.github.kr328.clash.design.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.design.R

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
    private val delayBounds = RectF()
    private val delayArc = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDelayTarget = 48f * resources.displayMetrics.density
    private var delayGesture = false
    private var delayGestureCancelled = false
    private var delayDownX = 0f
    private var delayDownY = 0f

    var onDelayClick: (() -> Unit)? = null

    var marqueeAllowed = false
        set(value) {
            if (field == value) return
            field = value
            resetMarquee()
            invalidate()
        }

    init {
        background = context.getDrawableCompat(config.clickableBackground)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.delay_test)) { _, _ ->
            performDelayClick()
        }
    }

    var state: ProxyViewState? = null
        set(value) {
            field = value
            delayGesture = false
            isPressed = false
            delayBounds.setEmpty()
            cachedTextSize = -1f
            resetMarquee()
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        delayGesture = false
        isPressed = false
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
        if ((marqueeAllowed || state?.delayTesting == true) && isAttachedToWindow &&
            windowVisibility == VISIBLE && hasWindowFocus() && isShown && getLocalVisibleRect(visibleRect)) {
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

    private fun performDelayClick(): Boolean {
        val current = state ?: return false
        val click = onDelayClick ?: return false
        if (!isEnabled || current.delayTesting) return false
        playSoundEffect(SoundEffectConstants.CLICK)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        click()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        state?.let {
            info.contentDescription = "${it.title}, ${it.subtitle}, ${if (it.delayTesting) context.getString(R.string.loading) else it.delayText}"
        }
    }

    // Canvas has two actions; the separate accessibility action above mirrors the delay target.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isEnabled && onDelayClick != null && delayBounds.contains(event.x, event.y)) {
                    delayGesture = true
                    delayGestureCancelled = false
                    delayDownX = event.x
                    delayDownY = event.y
                    isPressed = true
                    drawableHotspotChanged(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (delayGesture) {
                if (kotlin.math.abs(event.x - delayDownX) > touchSlop ||
                    kotlin.math.abs(event.y - delayDownY) > touchSlop) {
                    delayGestureCancelled = true
                    isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (delayGesture) {
                delayGestureCancelled = true
                isPressed = false
                return true
            }
            MotionEvent.ACTION_UP -> if (delayGesture) {
                delayGesture = false
                isPressed = false
                if (!delayGestureCancelled && delayBounds.contains(event.x, event.y)) performDelayClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (delayGesture) {
                delayGesture = false
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
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

        val delayTargetWidth = maxOf(delayWidth + state.config.contentPadding, minDelayTarget)
        val mainTextWidth = (width -
                state.config.layoutPadding * 2 -
                state.config.contentPadding -
                delayTargetWidth -
                state.config.textMargin
                )
            .coerceAtLeast(0f)

        titleLine.update(state.title, mainTextWidth, paint)
        subtitleLine.update(state.subtitle, mainTextWidth, paint)
        val scrolling = canMarquee()
        if (!scrolling) resetMarquee()
        val now = if (scrolling) SystemClock.uptimeMillis() else 0L
        val speed = 30f * resources.displayMetrics.density / 1000f
        val gap = mainTextWidth / 3f

        val delayRight = width - state.config.layoutPadding - state.config.contentPadding
        val delayLeft = delayRight - delayWidth
        val targetRight = width - state.config.layoutPadding
        val targetHalfHeight = maxOf(paint.textSize, minDelayTarget / 2f)
        delayBounds.set(
            (targetRight - delayTargetWidth).coerceAtLeast(state.config.layoutPadding),
            (height / 2f - targetHalfHeight).coerceAtLeast(0f),
            targetRight,
            (height / 2f + targetHalfHeight).coerceAtMost(height),
        )

        // draw delay
        val spin = state.delayTesting && isAttachedToWindow && windowVisibility == VISIBLE &&
                hasWindowFocus() && isShown && getLocalVisibleRect(visibleRect)
        if (state.delayTesting) {
            val radius = paint.textSize / 2f
            val centerX = delayRight - maxOf(delayWidth, paint.textSize) / 2f
            delayArc.set(centerX - radius, height / 2f - radius, centerX + radius, height / 2f + radius)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * resources.displayMetrics.density
            val angle = if (spin) (SystemClock.uptimeMillis() % 1000L) * 0.36f else 0f
            canvas.drawArc(delayArc, angle, 270f, false, paint)
            paint.style = Paint.Style.FILL
        } else {
            canvas.drawText(state.delayText, 0, delayCount, delayLeft, height / 2f - textOffset, paint)
        }

        val x = state.config.layoutPadding + state.config.contentPadding
        val titleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f - textOffset
        val subtitleY = state.config.layoutPadding +
                (height - state.config.layoutPadding * 2) / 3f * 2 - textOffset
        val titleDelay = titleLine.draw(canvas, paint, x, titleY, height, scrolling, now, speed, gap)
        val subtitleDelay = subtitleLine.draw(canvas, paint, x, subtitleY, height, scrolling, now, speed, gap)
        if (spin && frameScheduled) {
            // A marquee may be waiting through its pause; the spinner needs the next frame.
            Choreographer.getInstance().removeFrameCallback(this)
            frameScheduled = false
        }
        scheduleFrame(if (spin) 0L else minOf(titleDelay, subtitleDelay))
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
