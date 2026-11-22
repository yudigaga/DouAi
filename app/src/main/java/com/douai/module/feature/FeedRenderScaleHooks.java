package com.douai.module.feature;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Prepares renderer recovery before the verified host layout and schedules it after the layout returns. */
final class FeedRenderScaleHooks implements AutoCloseable {
    @FunctionalInterface
    interface Recovery {
        Runnable prepare(View renderer, Object work, Object video);
    }

    private final BehaviorHookGroup hostLayout;

    private FeedRenderScaleHooks(Consumer<String> log) {
        hostLayout = new BehaviorHookGroup("hide_video_black_edges.scale_host_layout", log);
    }

    static FeedRenderScaleHooks install(XposedInterface api, ClassLoader loader, Consumer<String> log,
                                        Recovery prepareRecovery) {
        FeedRenderScaleHooks hooks = new FeedRenderScaleHooks(log == null ? ignored -> { } : log);
        hooks.installHostLayout(api, loader, prepareRecovery);
        return hooks;
    }

    private void installHostLayout(XposedInterface api, ClassLoader loader, Recovery prepareRecovery) {
        try {
            Class<?> service = hostType(loader, "com.ss.android.ugc.aweme.pad_impl.feed.PadBusinessFeedServiceImpl");
            Method layout = service.getDeclaredMethod("LIZJ", Context.class, View.class,
                    hostType(loader, "com.ss.android.ugc.aweme.feed.model.Aweme"),
                    hostType(loader, "com.ss.android.ugc.aweme.feed.model.Video"),
                    int.class, int.class, hostType(loader, "X.0tNk"),
                    hostType(loader, "com.ss.android.ugc.aweme.feed.model.OcrLocation"),
                    int.class, int.class, String.class, hostType(loader, "Y.AObjectS1367S0100000_30"));
            if (Modifier.isStatic(layout.getModifiers()) || layout.isSynthetic()
                    || layout.getReturnType() != boolean.class) {
                throw new NoSuchMethodException("Unexpected PadBusinessFeedServiceImpl.LIZJ contract");
            }
            hostLayout.add(api, layout, chain -> observeHostLayout(chain, prepareRecovery));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            hostLayout.fail(unavailable);
        }
    }

    private static Class<?> hostType(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Object observeHostLayout(XposedInterface.Chain chain, Recovery prepareRecovery) throws Throwable {
        Runnable recovery = null;
        try {
            if (prepareRecovery != null && chain.getArg(1) instanceof View renderer) {
                recovery = prepareRecovery.prepare(renderer, chain.getArg(2), chain.getArg(3));
            }
        } catch (Throwable ignored) {
            // Preserve the native layout when its renderer or binding cannot be verified.
        }
        Object result = chain.proceed();
        if (recovery != null) {
            try {
                recovery.run();
            } catch (Throwable ignored) {
                // Optional recovery must leave the host's successful result intact.
            }
        }
        return result;
    }

    @Override public void close() {
        hostLayout.close();
    }
}
