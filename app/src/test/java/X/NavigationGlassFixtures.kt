package X

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView

/** JVM names match the verified bottom owner without inventing a Java identifier. */
class `1JgG`(context: Context) : FrameLayout(context) {
    var backgroundWrites = 0
        private set

    override fun setBackground(background: Drawable?) {
        backgroundWrites++
        super.setBackground(background)
    }
}

/** A host setter that starts rejecting style writes after its own update. */
class ThrowingLabel(context: Context) : TextView(context) {
    private var reject = false

    fun hostSizeThenRejectFurtherStyle() {
        super.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
        reject = true
    }

    override fun setTextSize(unit: Int, size: Float) {
        if (reject) throw IllegalStateException("Host rejected a style update")
        super.setTextSize(unit, size)
    }
}
