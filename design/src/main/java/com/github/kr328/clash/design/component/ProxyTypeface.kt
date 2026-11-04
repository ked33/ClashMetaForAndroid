package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.R

/** Flags come from the bundled font; other characters use Android's system fallback. */
internal object ProxyTypeface {
    private var cached: Typeface? = null

    @Synchronized
    fun get(context: Context): Typeface {
        cached?.let { return it }

        val typeface = try {
            ResourcesCompat.getFont(context.applicationContext, R.font.noto_color_emoji_flags)
                ?: Typeface.DEFAULT
        } catch (e: Exception) {
            Log.w("Unable to load proxy flag font", e)
            Typeface.DEFAULT
        }

        // Cache failures too: never retry font I/O while scrolling or rebinding cards.
        cached = typeface
        return typeface
    }
}
