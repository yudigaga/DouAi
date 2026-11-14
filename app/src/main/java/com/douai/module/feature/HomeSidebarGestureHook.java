package com.douai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SidebarSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostAdaptation;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Consumer;

/** Uses the host's existing sidebar pager gates; no application-wide touch listener is installed. */
public final class HomeSidebarGestureHook implements AutoCloseable {
    private final Bindings host;
    private final BehaviorHookGroup hooks;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private HomeSidebarGestureHook(Bindings host, LocalOptions options, Consumer<String> log) {
        this.host = host; this.options = options;
        hooks = new BehaviorHookGroup("home_sidebar_gesture", log == null ? ignored -> { } : log);
    }

    public static HomeSidebarGestureHook install(XposedInterface framework, ClassLoader loader,
            LocalOptions options, Consumer<String> log) throws Exception {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_410) {
            throw new IllegalStateException("Home sidebar gesture only covers the reviewed 41.0 host");
        }
        HomeSidebarGestureHook feature = new HomeSidebarGestureHook(new Bindings(loader), options, log);
        try {
            // Read before the pager can pause the feed for its drawer. A touch inside the drawer must
            // not replace the earlier observation with the host's temporary paused state.
            feature.hooks.add(framework, feature.host.dispatch, chain -> {
                Object pager=chain.getThisObject(), event=chain.getArg(0);
                LocalOptions opts=feature.options;
                if(!feature.closed && opts!=null && opts.enabled(SidebarSettings.ENABLED)
                        && event instanceof MotionEvent && ((MotionEvent)event).getActionMasked()==MotionEvent.ACTION_DOWN
                        && pager instanceof View && Looper.myLooper()==Looper.getMainLooper()){
                    try {
                        Object ability=feature.host.sideAbility.invoke(null);
                        Context owner=activityContext(((View)pager).getContext(),feature.host.activityType);
                        if(ability!=null && owner instanceof Activity
                                && Boolean.FALSE.equals(feature.host.sideVisible.invoke(ability,""))
                                && Boolean.TRUE.equals(feature.host.mainPage.invoke(pager)))
                            FeedGestureToolsHook.captureSidebarPlayback((Activity)owner);
                    }catch(ReflectiveOperationException | RuntimeException | LinkageError ignored){}
                }
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.area, chain -> {
                Object pager = chain.getThisObject();
                if (feature.applies(pager) && "homepage_home".equals(chain.getArgs().get(1))) return true;
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.childrenCanScroll, chain -> {
                Object pager = chain.getThisObject();
                if (claimRootRightward(pager, chain.getArgs().get(3), ((Number) chain.getArgs().get(0)).intValue())
                        && feature.applies(pager)) return false;
                return chain.proceed();
            });
            return feature;
        } catch (Exception failure) { feature.close(); throw failure; }
    }

    public void setOptions(LocalOptions next) { options = next; }
    public boolean isActive() { return !closed && hooks.isActive(); }

    private boolean applies(Object pager) {
        if (closed || options == null || !options.enabled(SidebarSettings.ENABLED)
                || Looper.myLooper() != Looper.getMainLooper() || !(pager instanceof View)) return false;
        try {
            View view = (View) pager;
            if (!view.isShown()) return false;
            Context context = activityContext(view.getContext(), host.activityType);
            if (context == null) return false;
            Object work = host.currentWork.get(host.homeData.invoke(null, context));
            if (work == null || !host.awemeType.isInstance(work)) return false;
            Object rawImages = host.images.get(work);
            int priorPhoto = rawImages instanceof List<?> && !((List<?>) rawImages).isEmpty()
                    ? Math.max(0, host.photoPosition.getInt(work)) : 0;
            return eligible(options, (String) host.bottomTab.invoke(pager), (String) host.topTab.invoke(pager),
                    Boolean.TRUE.equals(host.mainPage.invoke(pager)),
                    Boolean.TRUE.equals(host.sidebarAvailable.invoke(host.sidebar)), host.pagingEnabled.getBoolean(pager),
                    Boolean.TRUE.equals(host.live.invoke(work)), priorPhoto);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean eligible(LocalOptions options, String bottom, String top, boolean mainPage,
            boolean sidebarAvailable, boolean pagingEnabled, boolean live, int priorPhoto) {
        return options != null && options.enabled(SidebarSettings.ENABLED)
                && "homepage_home".equals(bottom) && "homepage_hot".equals(top)
                && mainPage && sidebarAvailable && pagingEnabled && !live && priorPhoto <= 0;
    }

    static boolean claimRootRightward(Object pager, Object queriedChild, int delta) {
        return pager != null && pager == queriedChild && delta > 0;
    }

    static Context activityContext(Context context, Class<?> activityType) {
        for (int depth = 0; context != null && depth < 16; depth++) {
            if (activityType.isInstance(context)) return context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    /** Closes only the currently visible home sidebar, never a profile drawer or another page. */
    public void closeSidebar() {
        if (closed || Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            Object ability = host.sideAbility.invoke(null);
            if (ability != null && Boolean.TRUE.equals(host.sideVisible.invoke(ability, ""))) {
                host.closeSidebar.invoke(ability, true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            try { DouAiLog.event("home_sidebar", "navigation", "native_close_unavailable",
                    "error_type=" + unavailable.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public void close() { if (!closed) { closed = true; hooks.close(); } }

    private static final class Bindings {
        final Class<?> activityType, awemeType;
        final Method dispatch, area, childrenCanScroll, bottomTab, topTab, mainPage, sidebarAvailable;
        final Method homeData, live, sideAbility, sideVisible, closeSidebar;
        final Field pagingEnabled, currentWork, photoPosition, images;
        final Object sidebar;
        Bindings(ClassLoader loader) throws Exception {
            Class<?> pager = type(loader, "com.ss.android.ugc.aweme.homepage.slide.pager.MainScrollableViewPager");
            Class<?> scrollable = type(loader, "com.ss.android.ugc.aweme.base.ui.ScrollableViewPager");
            activityType = type(loader, "androidx.fragment.app.FragmentActivity");
            awemeType = type(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
            Class<?> data = type(loader, "com.ss.android.ugc.aweme.homepage.api.data.HomePageDataViewModel");
            Class<?> proxy = type(loader, "com.ss.android.ugc.aweme.sidebar.HomeSideBarServiceProxy");
            Class<?> side = type(loader, "X.1aOQ");
            dispatch=method(pager,"dispatchTouchEvent",boolean.class,MotionEvent.class);
            area = method(pager, "LJLJJL", boolean.class, float.class, String.class);
            childrenCanScroll = method(pager, "LJIILIIL", boolean.class, int.class, int.class, int.class, View.class, boolean.class);
            bottomTab = method(pager, "LJLJJLL", String.class);
            topTab = method(pager, "getCurrentTopTabId", String.class);
            mainPage = method(pager, "LJLL", boolean.class);
            sidebarAvailable = method(proxy, "isLeftSideBarPageInMain", boolean.class);
            sidebar = proxy.getField("INSTANCE").get(null);
            homeData = method(type(loader, "X.0tvg"), "LIZ", data, activityType);
            if (!Modifier.isStatic(homeData.getModifiers())) throw new NoSuchMethodException("Home data lookup is not static");
            pagingEnabled = field(scrollable, "m", boolean.class);
            currentWork = field(data, "j", awemeType);
            photoPosition = field(awemeType, "photosCurPos", int.class);
            images = field(awemeType, "images", List.class);
            live = method(awemeType, "isLive", boolean.class);
            sideAbility = method(type(loader, "com.ss.android.ugc.aweme.ability.EzHomePage"), "LJ", side);
            sideVisible = side.getMethod("Y1", String.class);
            if (sideVisible.getReturnType() != boolean.class) throw new NoSuchMethodException("Sidebar visibility contract changed");
            closeSidebar = side.getMethod("Z1", boolean.class);
            if (closeSidebar.getReturnType() != void.class) throw new NoSuchMethodException("Sidebar close contract changed");
        }
        private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
        private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws ReflectiveOperationException {
            Method method = owner.getDeclaredMethod(name, args);
            if (method.getReturnType() != result) throw new NoSuchMethodException("Home sidebar method contract changed");
            method.setAccessible(true); return method;
        }
        private static Field field(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            if (field.getType() != expected || Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException("Home sidebar field contract changed");
            field.setAccessible(true); return field;
        }
    }
}
