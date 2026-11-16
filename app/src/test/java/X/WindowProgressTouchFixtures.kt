package X

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView
import com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout
import java.util.TreeSet
import java.util.function.BiFunction
import kotlin.math.roundToInt

/** The host keeps raw, extended and lazily cached delegate rectangles separately. */
class `1R6X` @JvmOverloads constructor(@JvmField val j: ProSeekBarView, val root: ViewGroup,
              val extraTop: Int = ((7 * j.resources.displayMetrics.density).roundToInt() - j.paddingTop).coerceAtLeast(0),
              val extraBottom: Int = ((22 * j.resources.displayMetrics.density).roundToInt() - j.paddingBottom).coerceAtLeast(0)) {
    @JvmField var e: Rect? = null
    @JvmField var f: Rect? = null
    @JvmField var g: Rect? = null
    // Keep the provider list and raw caches separate from the optional getter-return hook.
    @JvmField val h = ArrayList<Any>()

    fun getDelegateBound(): Rect {
        if (g == null) g = Rect(requireNotNull(e))
        val original = requireNotNull(g)
        return WindowProgressTouchFixtures.filterBounds(this, original)
    }
}

class `1R6i`(@JvmField val a: `1R6X`) {
    private var capturedDown = false
    fun getDelegateBound(): Rect = a.getDelegateBound()
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            capturedDown = getDelegateBound().contains(event.x.toInt(), event.y.toInt())
        }
        val captured = capturedDown
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) capturedDown = false
        return captured && a.j.dispatchTouchEvent(event)
    }
    fun reset() { capturedDown = false }
}

class `1R6j`(@JvmField val LIZ: ViewGroup) {
    var captured: `1R6i`? = null
    @JvmField val LIZJ = TreeSet<`1R6i`> { first, second ->
        Integer.compare(System.identityHashCode(first), System.identityHashCode(second))
    }
}

/** Recomputes e/f exactly as a layout update would; the host runnable does not clear g. */
class `1R6Y`(@JvmField val a: `1R6X`) : Runnable {
    override fun run() {
        WindowProgressTouchFixtures.checkNativeRefresh()
        val raw = Rect()
        a.j.getHitRect(raw)
        var parent = a.j.parent as? View
        while (parent != null && parent !== a.root) {
            raw.offset(parent.left, parent.top)
            parent = parent.parent as? View
        }
        require(parent === a.root)
        a.f = Rect(raw)
        raw.top -= a.extraTop
        raw.bottom += a.extraBottom
        a.e = raw
    }
}

object WindowProgressTouchFixtures {
    /** Null by default, so existing fixtures keep their original native return values. */
    private var delegateBoundsFilter: BiFunction<Any, Rect, Rect>? = null
    private var nativeRefreshFailureRequested = false
    @JvmStatic fun setBoundsFilter(filter: BiFunction<Any, Rect, Rect>?) { delegateBoundsFilter = filter }
    @JvmStatic fun setFailNativeRefresh(fail: Boolean) { nativeRefreshFailureRequested = fail }
    internal fun filterBounds(component: Any, original: Rect): Rect = delegateBoundsFilter?.apply(component, original) ?: original
    internal fun checkNativeRefresh() { check(!nativeRefreshFailureRequested) { "Native refresh failure requested by routing regression" } }
    @JvmStatic fun clearCache(component: Any) { (component as `1R6X`).g = null }
    @JvmStatic fun register(root: ProTouchDelegateFrameLayout, bar: ProSeekBarView): Any {
        return registerComponent(root, `1R6X`(bar, root))
    }

    @JvmStatic fun register(root: ProTouchDelegateFrameLayout, bar: ProSeekBarView, extraTop: Int, extraBottom: Int): Any {
        require(extraTop >= 0 && extraBottom >= 0)
        return registerComponent(root, `1R6X`(bar, root, extraTop, extraBottom))
    }

    private fun registerComponent(root: ProTouchDelegateFrameLayout, component: `1R6X`): Any {
        val manager = root.manager as? `1R6j` ?: `1R6j`(root).also { root.manager = it }
        manager.LIZJ.add(`1R6i`(component))
        `1R6Y`(component).run()
        component.getDelegateBound()
        return component
    }

    /** Mirrors 1R6j/1R6i: the delegate manager gets DOWN before ordinary descendants. */
    @JvmStatic fun dispatchToManager(root: ProTouchDelegateFrameLayout, event: MotionEvent): Boolean {
        val manager = root.manager as? `1R6j` ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) manager.captured = null
        manager.captured?.let { return it.onTouchEvent(event) }
        for (wrapper in manager.LIZJ) {
            if (wrapper.a.j.visibility != View.VISIBLE || !wrapper.a.j.isEnabled) continue
            if (wrapper.onTouchEvent(event)) {
                manager.captured = wrapper
                root.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    /** The real layout invokes its reset helper after UP/CANCEL has reached the captured wrapper. */
    @JvmStatic fun finishManagerGesture(root: ProTouchDelegateFrameLayout, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) return
        val manager = root.manager as? `1R6j` ?: return
        manager.LIZJ.forEach { it.reset() }; manager.captured = null
        root.parent?.requestDisallowInterceptTouchEvent(false)
    }

    @JvmStatic fun returnedBounds(component: Any): Rect = Rect((component as `1R6X`).getDelegateBound())
    @JvmStatic fun rawCachedBounds(component: Any): Rect = Rect(requireNotNull((component as `1R6X`).g))
    @JvmStatic fun rawExtendedBounds(component: Any): Rect = Rect(requireNotNull((component as `1R6X`).e))
    @JvmStatic fun providers(component: Any): Any = (component as `1R6X`).h
    @JvmStatic fun rawCachedBoundsOnScreen(component: Any): Rect {
        val delegate = component as `1R6X`
        val result = rawCachedBounds(component)
        val location = IntArray(2); delegate.root.getLocationOnScreen(location)
        result.offset(location[0], location[1]); return result
    }

    @JvmStatic fun cachedBoundsOnScreen(component: Any): Rect {
        val delegate = component as `1R6X`
        val result = Rect(delegate.getDelegateBound())
        val location = IntArray(2)
        delegate.root.getLocationOnScreen(location)
        result.offset(location[0], location[1])
        return result
    }

    @JvmStatic fun currentBoundsOnScreen(root: ProTouchDelegateFrameLayout, bar: ProSeekBarView): Rect {
        val result = Rect(0, 0, bar.width, bar.height)
        root.offsetDescendantRectToMyCoords(bar, result)
        val component = (root.manager as? `1R6j`)?.LIZJ?.firstOrNull { it.a.j === bar }?.a
        val density = bar.resources.displayMetrics.density
        result.top -= component?.extraTop ?: ((7 * density).roundToInt() - bar.paddingTop).coerceAtLeast(0)
        result.bottom += component?.extraBottom ?: ((22 * density).roundToInt() - bar.paddingBottom).coerceAtLeast(0)
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        result.offset(location[0], location[1])
        return result
    }
}
