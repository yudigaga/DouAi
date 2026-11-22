package com.douai.module.feature;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FeedRenderScaleHooksTest {
    @Test public void preparesThenCallsHostOnceAndRecoversEvenOnFalseReturn() throws Throwable {
        View renderer = new View(RuntimeEnvironment.getApplication());
        Object work = new Object();
        Object video = new Object();
        List<String> events = new ArrayList<>();
        XposedInterface.Chain chain = chain(renderer, work, video, () -> {
            events.add("host");
            return false;
        });

        Object result = FeedRenderScaleHooks.observeHostLayout(chain, (actualRenderer, actualWork, actualVideo) -> {
            assertSame(renderer, actualRenderer);
            assertSame(work, actualWork);
            assertSame(video, actualVideo);
            events.add("prepare");
            return () -> events.add("recover");
        });

        assertEquals(false, result);
        assertEquals(List.of("prepare", "host", "recover"), events);
    }

    @Test public void hostThrowIsPropagatedUnchangedAndSkipsRecovery() {
        View renderer = new View(RuntimeEnvironment.getApplication());
        IllegalStateException nativeError = new IllegalStateException("native");
        List<String> events = new ArrayList<>();
        XposedInterface.Chain chain = chain(renderer, null, null, () -> {
            events.add("host");
            throw nativeError;
        });

        Throwable error = assertThrows(IllegalStateException.class,
                () -> FeedRenderScaleHooks.observeHostLayout(chain, (actualRenderer, work, video) -> {
                    events.add("prepare");
                    return () -> events.add("recover");
                }));

        assertSame(nativeError, error);
        assertEquals(List.of("prepare", "host"), events);
    }

    @Test public void optionalCallbackFailuresPreserveTheHostResult() throws Throwable {
        View renderer = new View(RuntimeEnvironment.getApplication());
        List<String> events = new ArrayList<>();
        XposedInterface.Chain chain = chain(renderer, null, null, () -> {
            events.add("host");
            return true;
        });

        assertEquals(true, FeedRenderScaleHooks.observeHostLayout(chain, (view, work, video) -> {
            throw new IllegalStateException("prepare");
        }));
        assertEquals(true, FeedRenderScaleHooks.observeHostLayout(chain, (view, work, video) -> () -> {
            throw new IllegalStateException("recover");
        }));
        assertEquals(List.of("host", "host"), events);
    }

    private interface Proceed { Object run() throws Throwable; }

    private static XposedInterface.Chain chain(View renderer, Object work, Object video, Proceed proceed) {
        Object[] args = {null, renderer, work, video};
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, parameters) ->
                        switch (method.getName()) {
                            case "getArg" -> args[(Integer) parameters[0]];
                            case "proceed" -> proceed.run();
                            default -> throw new AssertionError(method.getName());
                        });
    }
}
