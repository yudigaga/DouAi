package com.douai.module.feature;

import android.app.Dialog;
import android.graphics.Rect;
import android.view.MotionEvent;
import com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Registration contracts only; the fake framework does not execute real LSPosed callbacks. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
public class LiquidGlassNativeRoutingHookTest {
    @Test public void bothExactNativeHooksAreInstalledBeforeCompactRoutingIsEnabled() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            scene.controller=new LiquidGlassController(scene.activity,
                    LiquidGlassClearanceControllerTest.Scene.options(true,false),ignored->{});
            Framework framework=new Framework();
            assertFalse(WindowProgressTouchBridge.isRoutingAvailable());
            try(LiquidGlassWindowHooks hooks=new LiquidGlassWindowHooks(framework.api(),scene.controller,getClass().getClassLoader(),ignored->{})) {
                assertTrue(framework.active.containsKey(nativeDispatch()));
                assertTrue(framework.active.containsKey(nativeBounds()));
                assertTrue(WindowProgressTouchBridge.isRoutingAvailable());
            }
            assertFalse(WindowProgressTouchBridge.isRoutingAvailable());assertTrue(framework.active.isEmpty());
        }
    }

    @Test public void eitherNativeRegistrationFailureRetainsOrdinaryWindowHooksAndFullTouchPolicy() throws Exception {
        for(Method rejected:new Method[]{nativeDispatch(),nativeBounds()})
            try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
                scene.controller=new LiquidGlassController(scene.activity,
                        LiquidGlassClearanceControllerTest.Scene.options(true,false),ignored->{});
                Framework framework=new Framework();framework.rejected=rejected;
                ArrayList<String> logs=new ArrayList<>();
                try(LiquidGlassWindowHooks hooks=new LiquidGlassWindowHooks(framework.api(),scene.controller,getClass().getClassLoader(),logs::add)) {
                    assertFalse("Partial interception must not enable compact geometry",WindowProgressTouchBridge.isRoutingAvailable());
                    assertFalse(framework.active.containsKey(nativeDispatch()));
                    assertFalse(framework.active.containsKey(nativeBounds()));
                    assertTrue("Optional routing failure must retain the original window hooks",
                            framework.active.containsKey(Dialog.class.getDeclaredMethod("show")));
                    assertTrue(logs.toString(),logs.stream().anyMatch(line->line.contains("native_touch_routing_unavailable=")));
                }
                assertTrue(framework.active.isEmpty());assertFalse(WindowProgressTouchBridge.isRoutingAvailable());
            }
    }

    private static Method nativeDispatch() throws Exception {
        return ProTouchDelegateFrameLayout.class.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class);
    }
    private Method nativeBounds() throws Exception {
        Method method=Class.forName("X.1R6X",false,getClass().getClassLoader()).getDeclaredMethod("getDelegateBound");
        assertEquals(Rect.class,method.getReturnType());return method;
    }
    private static final class Framework {
        final Map<Method,XposedInterface.Hooker> active=new HashMap<>();
        Method rejected;
        XposedInterface api() {
            return (XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{XposedInterface.class},
                    (proxy,method,args)->method.getName().equals("hook")?builder((Method)args[0]):null);
        }
        private XposedInterface.HookBuilder builder(Method target) {
            return (XposedInterface.HookBuilder)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class},(proxy,method,args)->{
                        if(!method.getName().equals("intercept"))return proxy;
                        if(target.equals(rejected))throw new IllegalStateException("fixture native hook failure");
                        active.put(target,(XposedInterface.Hooker)args[0]);
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{XposedInterface.HookHandle.class},
                                (handle,operation,values)->{if(operation.getName().equals("unhook"))active.remove(target);return null;});
                    });
        }
    }
}
