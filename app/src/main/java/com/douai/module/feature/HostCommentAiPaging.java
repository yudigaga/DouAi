package com.douai.module.feature;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Scoped scroll adapter for module-owned recommendations, separate from native feed loading. */
final class HostCommentAiPaging implements AutoCloseable {
    final Method scrolled;
    final Method touch;
    final Method automaticLoad;
    private final Method component, recycler, range, offset, extent, enableLoadMore;
    private final Field context, ownerViews, viewBridge, bridgeFeed, feedRecycler, refreshView;
    private final Field autoEnabled, loadMoreEnabled, manualLoadMore, loaderFeed;
    private final Class<?> managerType, bridgeType, feedType, recyclerType, refreshType;
    private final Map<Object, WeakReference<Handle>> byRecycler = new WeakHashMap<>();
    private final Map<Object, WeakReference<Handle>> byFeed = new WeakHashMap<>();

    private HostCommentAiPaging(ClassLoader loader) throws ReflectiveOperationException {
        managerType = type(loader, "X.1B3z");
        Class<?> componentType = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.ai.component.SearchAIComponent");
        Class<?> contextType = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.context.IC2ArchContext");
        Class<?> composerType = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.C2ComposerImpl");
        Class<?> holderType = type(loader, "X.1AxW");
        bridgeType = type(loader, "X.1AxX");
        feedType = type(loader, "com.ss.android.ugc.aweme.o.c2feed.core.C2Feed");
        recyclerType = type(loader, "androidx.recyclerview.widget.RecyclerView");
        refreshType = type(loader, "com.scwang.smartrefresh.layout.SmartRefreshLayout");
        Class<?> nativeRefreshType = type(loader, "com.ss.android.ugc.aweme.o.c2feed.core.ui.C2MainHandlerSmartRefreshLayout");
        if (!View.class.isAssignableFrom(recyclerType) || !refreshType.isAssignableFrom(nativeRefreshType)
                || !contextType.isAssignableFrom(composerType)) throw changed();
        component = method(managerType, "LJI", componentType);
        recycler = method(componentType, "LJJIJIL", recyclerType);
        scrolled = method(recyclerType, "dispatchOnScrolled", void.class, int.class, int.class);
        touch = method(recyclerType, "onTouchEvent", boolean.class, MotionEvent.class);
        range = method(recyclerType, "computeVerticalScrollRange", int.class);
        offset = method(recyclerType, "computeVerticalScrollOffset", int.class);
        extent = method(recyclerType, "computeVerticalScrollExtent", int.class);
        context = field(managerType, "LIZIZ", contextType);
        ownerViews = field(composerType, "o", holderType);
        viewBridge = field(holderType, "LIZ", type(loader, "X.1BrG"));
        bridgeFeed = field(bridgeType, "LIZ", feedType);
        feedRecycler = field(feedType, "f", recyclerType);
        refreshView = field(feedType, "g", nativeRefreshType);
        autoEnabled = field(feedType, "v", boolean.class);
        loadMoreEnabled = field(refreshType, "C", boolean.class);
        manualLoadMore = field(refreshType, "V", boolean.class);
        enableLoadMore = method(refreshType, "LJJIIJ", refreshType, boolean.class);
        Class<?> automaticLoader = type(loader, "X.1Avk");
        automaticLoad = method(automaticLoader, "as", void.class);
        loaderFeed = field(automaticLoader, "a", feedType);
    }

    static HostCommentAiPaging create(ClassLoader loader) throws ReflectiveOperationException {
        return new HostCommentAiPaging(loader);
    }

    Handle attach(Object manager, Runnable loadMore) throws Exception {
        mainThread();
        if (!managerType.isInstance(manager) || loadMore == null) return null;
        Object owner = context.get(manager);
        Object holder = owner == null ? null : ownerViews.get(owner);
        Object bridge = holder == null ? null : viewBridge.get(holder);
        if (!bridgeType.isInstance(bridge)) return null;
        Object feed = bridgeFeed.get(bridge);
        Object view = recycler.invoke(component.invoke(manager));
        if (!feedType.isInstance(feed) || !recyclerType.isInstance(view) || feedRecycler.get(feed) != view) return null;
        Handle previous = dereference(byFeed.get(feed));
        if (previous != null) previous.close();
        previous = dereference(byRecycler.get(view));
        if (previous != null) previous.close();
        Object refresh = refreshView.get(feed);
        Handle handle = new Handle((View) view, feed, refresh, autoEnabled.getBoolean(feed),
                refresh != null && loadMoreEnabled.getBoolean(refresh),
                refresh != null && manualLoadMore.getBoolean(refresh), loadMore);
        try {
            handle.disableNativeLoading();
            byRecycler.put(view, new WeakReference<>(handle));
            byFeed.put(feed, new WeakReference<>(handle));
            return handle;
        } catch (Exception failure) {
            handle.close();
            throw failure;
        }
    }

    /** Called before the host dispatches its own scroll listeners. */
    void scrolled(Object receiver, int dy) throws Exception {
        mainThread();
        Handle handle = dereference(byRecycler.get(receiver));
        if (handle == null || handle.closed || handle.view.get() != receiver) return;
        handle.disableNativeLoading();
        if (dy <= 0 || !(receiver instanceof View)) return;
        View view = (View) receiver;
        if (!view.isShown()) return;
        if (atBottom(view)) {
            handle.gesture.scrolled();
            handle.loadMore.run();
        }
    }

    /** Observes an upward release without consuming the host's touch event. */
    void touched(Object receiver, MotionEvent event) throws Exception {
        mainThread();
        Handle handle = dereference(byRecycler.get(receiver));
        if (event == null || handle == null || handle.closed || handle.view.get() != receiver) return;
        handle.disableNativeLoading();
        View view = (View) receiver;
        float minimum = view.getResources().getDisplayMetrics().density * 32;
        if (handle.gesture.touch(event.getActionMasked(), event.getY(), minimum) && view.isShown() && atBottom(view)) {
            handle.loadMore.run();
        }
    }

    private boolean atBottom(View view) throws Exception {
        return !view.canScrollVertically(1) || nearBottom((Integer) range.invoke(view),
                (Integer) offset.invoke(view), (Integer) extent.invoke(view));
    }

    /** Stops the native automatic-loader callback even if a render pass re-enabled its flag. */
    boolean ownsAutomaticLoader(Object loader) throws Exception {
        mainThread();
        if (loader == null || !loaderFeed.getDeclaringClass().isInstance(loader)) return false;
        Object feed = loaderFeed.get(loader);
        Handle handle = dereference(byFeed.get(feed));
        if (handle == null || handle.closed || handle.feed.get() != feed) return false;
        handle.disableNativeLoading();
        return true;
    }

    static boolean nearBottom(int range, int offset, int extent) {
        return range >= 0 && offset >= 0 && extent > 0
                && (long) range - offset - extent <= extent;
    }

    @Override public void close() {
        for (WeakReference<Handle> reference : new ArrayList<>(byFeed.values())) {
            Handle handle = dereference(reference);
            if (handle != null) handle.close();
        }
        byRecycler.clear();
        byFeed.clear();
    }

    final class Handle implements AutoCloseable {
        private final WeakReference<View> view;
        private final WeakReference<Object> feed, refresh;
        private final boolean originalAuto, originalLoadMore, originalManual;
        private Runnable loadMore;
        private final SwipeGesture gesture = new SwipeGesture();
        private boolean closed;
        Handle(View view, Object feed, Object refresh, boolean originalAuto, boolean originalLoadMore,
                boolean originalManual, Runnable loadMore) {
            this.view = new WeakReference<>(view); this.feed = new WeakReference<>(feed);
            this.refresh = new WeakReference<>(refresh); this.originalAuto = originalAuto;
            this.originalLoadMore = originalLoadMore; this.originalManual = originalManual;
            this.loadMore = loadMore;
        }
        void disableNativeLoading() throws Exception {
            Object target = feed.get();
            if (target != null) autoEnabled.setBoolean(target, false);
            Object layout = refresh.get();
            if (layout != null) enableLoadMore.invoke(layout, false);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            View targetView = view.get();
            Object targetFeed = feed.get();
            Object targetRefresh = refresh.get();
            if (dereference(byRecycler.get(targetView)) == this) byRecycler.remove(targetView);
            if (dereference(byFeed.get(targetFeed)) == this) byFeed.remove(targetFeed);
            try { if (targetFeed != null) autoEnabled.setBoolean(targetFeed, originalAuto); }
            catch (ReflectiveOperationException ignored) { }
            try {
                if (targetRefresh != null) {
                    enableLoadMore.invoke(targetRefresh, originalLoadMore);
                    manualLoadMore.setBoolean(targetRefresh, originalManual);
                }
            } catch (ReflectiveOperationException ignored) { }
            loadMore = () -> { };
            view.clear(); feed.clear(); refresh.clear();
        }
    }

    static final class SwipeGesture {
        private float startY = Float.NaN;
        private boolean requested;
        boolean touch(int action, float y, float minimum) {
            if (action == MotionEvent.ACTION_DOWN) {
                startY = y;
                requested = false;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                startY = Float.NaN;
                requested = false;
            } else if (action == MotionEvent.ACTION_UP) {
                boolean request = !requested && !Float.isNaN(startY) && startY - y >= minimum;
                startY = Float.NaN;
                if (request) requested = true;
                return request;
            }
            return false;
        }
        void scrolled() { requested = true; }
    }

    private static Handle dereference(WeakReference<Handle> reference) { return reference == null ? null : reference.get(); }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Field field(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers())) throw changed();
        field.setAccessible(true); return field;
    }
    private static Method method(Class<?> owner, String name, Class<?> expected, Class<?>... arguments) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != expected || Modifier.isStatic(method.getModifiers())) throw changed();
        method.setAccessible(true); return method;
    }
    private static void mainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Comment pagination requires the main thread");
    }
    private static IllegalStateException changed() { return new IllegalStateException("Native comment pagination contract changed"); }
}
