package com.douai.module.feature;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.PopupWindow;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

/** Observes window and touch lifecycle without consuming or rewriting host events. */
public final class LiquidGlassWindowHooks implements AutoCloseable {
    private final BehaviorHookGroup hooks,routingHooks;
    private boolean routingEnabled;
    public LiquidGlassWindowHooks(XposedInterface framework,LiquidGlassController controller,Consumer<String> logger) {
        this(framework,controller,null,logger);
    }
    @android.annotation.SuppressLint("PrivateApi") // Optional LSPosed observation of PopupDecorView; reflection failure retains Activity/Dialog observers.
    public LiquidGlassWindowHooks(XposedInterface framework,LiquidGlassController controller,ClassLoader hostClassLoader,
            Consumer<String> logger) {
        Consumer<String> log=logger==null?ignored->{}:logger;
        hooks=new BehaviorHookGroup("liquid_glass",log);
        routingHooks=new BehaviorHookGroup("liquid_glass_touch",log);
        try {
            hooks.add(framework,Dialog.class.getDeclaredMethod("show"),chain->{
                Object result=chain.proceed();
                try {Dialog dialog=(Dialog)chain.getThisObject();
                    if(dialog.isShowing() && dialog.getWindow()!=null)controller.onWindow(dialog.getWindow().getDecorView());
                }catch(RuntimeException|LinkageError failure){log.accept("liquid_glass: dialog_attach_failed");}
                return result;
            });
            hooks.add(framework,Dialog.class.getDeclaredMethod("dismiss"),chain->{
                Dialog dialog=(Dialog)chain.getThisObject();View root=dialog.getWindow()==null?null:dialog.getWindow().getDecorView();
                Object result=chain.proceed();controller.onWindowClosed(root);return result;
            });
            observeTouch(framework,controller,Activity.class,log);
            observeTouch(framework,controller,Dialog.class,log);
            for(Method method:PopupWindow.class.getDeclaredMethods()) {
                if(!method.getName().equals("showAtLocation") && !method.getName().equals("showAsDropDown"))continue;
                hooks.add(framework,method,chain->{
                    Object result=chain.proceed();
                    try {PopupWindow popup=(PopupWindow)chain.getThisObject();
                        if(popup.isShowing() && popup.getContentView()!=null)controller.onWindow(popup.getContentView().getRootView());
                    }catch(RuntimeException|LinkageError failure){log.accept("liquid_glass: popup_attach_failed");}
                    return result;
                });
            }
            hooks.add(framework,PopupWindow.class.getDeclaredMethod("dismiss"),chain->{
                PopupWindow popup=(PopupWindow)chain.getThisObject();
                View root=popup.getContentView()==null?null:popup.getContentView().getRootView();
                Object result=chain.proceed();controller.onWindowClosed(root);return result;
            });
            try {observeTouch(framework,controller,Class.forName("android.widget.PopupWindow$PopupDecorView"),log);}
            catch(ClassNotFoundException ignored){log.accept("liquid_glass: popup_touch_observer_unavailable");}
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){routingHooks.close();hooks.close();throw new IllegalStateException("Glass window hooks unavailable",failure);}
        installRouting(framework,controller,hostClassLoader,log);
    }
    private void installRouting(XposedInterface framework,LiquidGlassController controller,ClassLoader loader,Consumer<String> log) {
        if(loader==null)return;
        try {
            Class<?> delegate=Class.forName("com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout",false,loader);
            Method dispatch=delegate.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class);
            if(dispatch.getReturnType()!=boolean.class || Modifier.isStatic(dispatch.getModifiers()))
                throw new NoSuchMethodException("Native delegate dispatch contract changed");
            routingHooks.add(framework,dispatch,touchObserver(controller,log,true));
            Class<?> component=Class.forName("X.1R6X",false,loader);
            // Resolve the exact DEX signature without asking lint to parse this obfuscated class as a Java type.
            Method bounds=HostReflectionCache.REFLECTION.declaredMethod(component,"getDelegateBound","()Landroid/graphics/Rect;");
            if(bounds.getReturnType()!=Rect.class || Modifier.isStatic(bounds.getModifiers()))
                throw new NoSuchMethodException("Native delegate Rect contract changed");
            routingHooks.add(framework,bounds,chain->{
                Object original=chain.proceed();
                if(!(original instanceof Rect))return original;
                try {return WindowProgressTouchBridge.filterDelegateBounds(chain.getThisObject(),(Rect)original);}
                catch(RuntimeException|LinkageError failure) {
                    disableRouting();reportRouting(log,"liquid_glass: native_touch_routing_runtime_unavailable");return original;
                }
            });
            WindowProgressTouchBridge.enableRouting();routingEnabled=true;
            reportRouting(log,"liquid_glass: native_touch_routing_registered");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
            disableRouting();routingHooks.close();reportRouting(log,"liquid_glass: native_touch_routing_unavailable="+failure.getClass().getSimpleName());
        }
    }
    private static void reportRouting(Consumer<String> log,String message) {
        try {log.accept(message);}catch(RuntimeException|LinkageError ignored){/* Optional routing diagnostics cannot disable window observation. */}
    }
    private void disableRouting() {
        if(routingEnabled){routingEnabled=false;WindowProgressTouchBridge.disableRouting();}
    }
    private void observeTouch(XposedInterface framework,LiquidGlassController controller,Class<?> type,Consumer<String> log) {
        try {
            hooks.add(framework,type.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class),touchObserver(controller,log,false));
        }catch(NoSuchMethodException|RuntimeException failure){log.accept("liquid_glass: touch_observer_unavailable="+type.getName());}
    }
    private XposedInterface.Hooker touchObserver(LiquidGlassController controller,Consumer<String> log,boolean critical) {
        return chain->{
            MotionEvent event=(MotionEvent)chain.getArg(0);
            View dispatchRoot=null;
            try {dispatchRoot=dispatchRoot(chain.getThisObject());controller.onTouch(event,dispatchRoot);}
            catch(RuntimeException|LinkageError failure) {
                if(critical)disableRouting();
                reportRouting(log,critical?"liquid_glass: native_touch_routing_before_failed":"liquid_glass: touch_observer_failed");
            }
            try {return chain.proceed();}
            finally {
                try {controller.onTouchDispatched(event,dispatchRoot);}
                catch(RuntimeException|LinkageError failure) {
                    if(critical)disableRouting();
                    reportRouting(log,critical?"liquid_glass: native_touch_routing_after_failed":"liquid_glass: touch_completion_observer_failed");
                }
            }
        };
    }
    private static View dispatchRoot(Object source) {
        if(source instanceof Activity) {
            android.view.Window window=((Activity)source).getWindow();return window==null?null:window.getDecorView();
        }
        if(source instanceof Dialog) {
            android.view.Window window=((Dialog)source).getWindow();return window==null?null:window.getDecorView();
        }
        return source instanceof View?((View)source).getRootView():null;
    }
    @Override public void close(){disableRouting();routingHooks.close();hooks.close();}
}
