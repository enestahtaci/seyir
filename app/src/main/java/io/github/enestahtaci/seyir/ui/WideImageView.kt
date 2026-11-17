package io.github.enestahtaci.seyir.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** An ImageView that is always 16:9 of its width, so cards keep their size before the thumbnail arrives. */
class WideImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, width * 9 / 16)
    }
}
