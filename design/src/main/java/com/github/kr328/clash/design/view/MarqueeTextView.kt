package com.github.kr328.clash.design.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/** Uses the platform marquee without pretending to own input focus. */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    var marqueeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateMarquee()
        }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected && marqueeEnabled && isAttachedToWindow && isShown &&
                windowVisibility == View.VISIBLE && hasWindowFocus())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMarquee()
    }

    override fun onDetachedFromWindow() {
        isSelected = false
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateMarquee()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMarquee()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMarquee()
    }

    private fun updateMarquee() {
        isSelected = marqueeEnabled
    }
}
