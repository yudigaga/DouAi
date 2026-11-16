package com.douai.module.feature;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** Refreshes the verified 41.0.0 delegate and scopes optional gesture filtering to managed sliders. */
final class WindowProgressTouchBridge {
    private static final HostReflectionCache ACCESS = new HostReflectionCache();
    private static final String DELEGATE = "com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout";
    private static final Map<Object,Route> COMPONENTS = new WeakHashMap<>();
    private static final Map<View,Route> VIEWS = new WeakHashMap<>();
    private static final ThreadLocal<Integer> NATIVE_READ_DEPTH = new ThreadLocal<>();
    private static int routingOwners;
    private static long routingEpoch;

    private WindowProgressTouchBridge() { }

    static RectF refresh(View seekbar, View windowRoot) {
        return refresh(seekbar,windowRoot,false);
    }

    static RectF refresh(View seekbar, View windowRoot, boolean managed) {
        if(!managed || !isRoutingAvailable())forget(seekbar);
        else discardInvalidRoute(seekbar,windowRoot);
        return refreshNative(seekbar,windowRoot,managed);
    }

    private static RectF refreshNative(View seekbar, View windowRoot, boolean managed) {
        if (seekbar == null || windowRoot == null || !seekbar.isLaidOut() || !windowRoot.isLaidOut()) return null;
        try {
            View delegate = ancestor(seekbar);
            Matrix toRoot = delegate == null ? null : toRoot(delegate, windowRoot);
            if (toRoot == null) return null;
            Method getManager = ACCESS.publicNoArgMethod(delegate.getClass(), "getManager");
            if (getManager == null) return unmapped(seekbar,windowRoot);
            Object manager = getManager.invoke(delegate);
            Object entries = read(manager, "LIZJ");
            if (!(entries instanceof Iterable<?>)) return unmapped(seekbar,windowRoot);
            Object match = null;
            int count = 0;
            for (Object entry : (Iterable<?>) entries) {
                if (++count > 256) return unmapped(seekbar,windowRoot);
                Object component = read(entry, "a");
                if (component == null || !component.getClass().getName().equals("X.1R6X")
                        || read(component, "j") != seekbar) continue;
                if (match != null && match != component) return unmapped(seekbar,windowRoot);
                match = component;
            }
            if (match == null) return unmapped(seekbar,windowRoot);
            Field cached = ACCESS.field(match.getClass(), "g");
            Method getBounds = ACCESS.publicNoArgMethod(match.getClass(), "getDelegateBound");
            if (cached == null || getBounds == null) return unmapped(seekbar,windowRoot);
            Class<?> refresher = Class.forName("X.1R6Y", false, match.getClass().getClassLoader());
            Object handler = refresher.getConstructor(match.getClass()).newInstance(match);
            if (!(handler instanceof Runnable)) return null;
            Object nativeBounds;
            // The handler can notify observers synchronously; the whole native refresh must bypass gesture filtering.
            Integer depth=NATIVE_READ_DEPTH.get();NATIVE_READ_DEPTH.set(depth==null?1:depth+1);
            try {
                // This native handler recalculates padding/config-aware bounds; LJJIZ would also reset dragging.
                ((Runnable) handler).run();cached.set(match,null);nativeBounds=getBounds.invoke(match);
            }
            finally {if(depth==null)NATIVE_READ_DEPTH.remove();else NATIVE_READ_DEPTH.set(depth);}
            if (!(nativeBounds instanceof Rect)) return null;
            RectF result = new RectF((Rect) nativeBounds);
            toRoot.mapRect(result);
            if(!finite(result))return null;
            if(managed)register(match,seekbar,windowRoot);
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    static synchronized void enableRouting() {if(routingOwners++==0)routingEpoch++;}

    static synchronized void disableRouting() {
        if(routingOwners>0 && --routingOwners==0)routingEpoch++;
        if(routingOwners==0){COMPONENTS.clear();VIEWS.clear();}
    }

    static synchronized long epoch() {return routingEpoch;}

    static synchronized boolean isRoutingAvailable() {return routingOwners>0;}

    static synchronized Rect filterDelegateBounds(Object component,Rect original) {
        if(original==null || routingOwners==0 || NATIVE_READ_DEPTH.get()!=null)return original;
        Route route=COMPONENTS.get(component);
        if(route==null)return original;
        View view=route.view.get(),root=route.root.get();
        if(route.component.get()!=component || !route.valid(view,root)){remove(route);return original;}
        return route.gestureProtected?new Rect():original;
    }

    static synchronized boolean canRoute(View view,View root) {
        if(routingOwners==0 || view==null || root==null)return false;
        Route route=VIEWS.get(view);
        if(route==null)return false;
        if(route.view.get()!=view || route.root.get()!=root)return false;
        if(!route.valid(view,root)){remove(route);return false;}
        return true;
    }

    static synchronized void setGestureProtected(View view,View root,boolean value) {
        if(canRoute(view,root))VIEWS.get(view).gestureProtected=value;
    }

    static synchronized void clearGesture(View view) {
        Route route=view==null?null:VIEWS.get(view);
        if(route!=null)route.gestureProtected=false;
    }

    static synchronized void forget(View view) {
        Route route=view==null?null:VIEWS.get(view);
        if(route!=null)remove(route);
    }

    private static synchronized void register(Object component,View view,View root) {
        if(routingOwners==0 || view.getParent()==null || view.getLayoutParams()==null || !within(view,root))return;
        Route old=VIEWS.get(view);
        if(old!=null && old.component.get()==component && old.valid(view,root))return;
        boolean protectedGesture=old!=null && old.valid(view,root) && old.gestureProtected;
        if(old!=null)remove(old);
        Route occupied=COMPONENTS.get(component);if(occupied!=null)remove(occupied);
        Route route=new Route(component,view,root);route.gestureProtected=protectedGesture;
        COMPONENTS.put(component,route);VIEWS.put(view,route);
    }

    private static synchronized void discardInvalidRoute(View view,View root) {
        Route route=view==null?null:VIEWS.get(view);
        if(route!=null && route.root.get()==root && !route.valid(view,root))remove(route);
    }

    private static synchronized RectF unmapped(View view,View root) {
        Route route=view==null?null:VIEWS.get(view);
        if(route!=null && route.root.get()==root)remove(route);
        return null;
    }

    private static void remove(Route route) {
        Object component=route.component.get();View view=route.view.get();route.gestureProtected=false;
        if(component!=null && COMPONENTS.get(component)==route)COMPONENTS.remove(component);
        if(view!=null && VIEWS.get(view)==route)VIEWS.remove(view);
    }

    private static boolean within(View view,View root) {
        for(int depth=0;view!=null && depth<=40;depth++) {
            if(view==root)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }

    private static final class Route {
        final WeakReference<Object> component,params;
        final WeakReference<View> view,root;
        final WeakReference<ViewParent> parent;
        final int rootWidth,rootHeight;
        boolean gestureProtected;
        Route(Object component,View view,View root) {
            this.component=new WeakReference<>(component);this.view=new WeakReference<>(view);this.root=new WeakReference<>(root);
            parent=new WeakReference<>(view.getParent());params=new WeakReference<>(view.getLayoutParams());
            rootWidth=root.getWidth();rootHeight=root.getHeight();
        }
        boolean valid(View view,View root) {
            return view!=null && root!=null && this.view.get()==view && this.root.get()==root && component.get()!=null
                    && parent.get()!=null && params.get()!=null && view.getParent()==parent.get()
                    && view.getLayoutParams()==params.get() && root.getWidth()==rootWidth && root.getHeight()==rootHeight
                    && view.isLaidOut() && root.isLaidOut() && view.getWidth()>0 && view.getHeight()>0
                    && rootWidth>0 && rootHeight>0 && within(view,root);
        }
    }

    private static Object read(Object owner, String name) throws IllegalAccessException {
        if (owner == null) return null;
        Field field = ACCESS.field(owner.getClass(), name);
        return field == null ? null : field.get(owner);
    }

    private static View ancestor(View view) {
        for (int depth = 0; view != null && depth < 40; depth++) {
            if (view.getClass().getName().equals(DELEGATE)) return view;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return null;
    }

    private static Matrix toRoot(View view, View root) {
        Matrix result = new Matrix();
        for (int depth = 0; depth < 40; depth++) {
            if (view == root) return result;
            if (!view.isLaidOut() || view.getRotationX() != 0 || view.getRotationY() != 0
                    || !(view.getParent() instanceof View)) return null;
            Matrix local = view.getMatrix();
            if (!local.isAffine()) return null;
            result.postConcat(local);
            View parent = (View) view.getParent();
            result.postTranslate(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY());
            view = parent;
        }
        return null;
    }

    private static boolean finite(RectF box) {
        return !box.isEmpty() && Float.isFinite(box.left) && Float.isFinite(box.top)
                && Float.isFinite(box.right) && Float.isFinite(box.bottom);
    }
}
