package com.douai.module.feature;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SeenWorkProbeTest {
    private final ArrayDeque<Runnable> events = new ArrayDeque<>();
    private final AtomicBoolean same = new AtomicBoolean(true), visible = new AtomicBoolean(false);
    private final AtomicInteger checked = new AtomicInteger(), writes = new AtomicInteger();
    private SeenWorkProbe begin() {
        SeenWorkProbe probe = new SeenWorkProbe(() -> {
            checked.incrementAndGet();
            if (!same.get()) return true;
            if (!visible.get()) return false;
            writes.incrementAndGet(); return true;
        }, (run, delay) -> events.add(run));
        events.add(probe);
        return probe;
    }
    private void drain() { while(!events.isEmpty()) events.remove().run(); }
    @Test public void coldWindowBecomesFocusedOnLaterPass() {
        begin(); events.remove().run(); assertEquals(0,writes.get());
        visible.set(true); drain(); assertEquals(1,writes.get()); assertEquals(2,checked.get());
    }
    @Test public void recycledOrCancelledSelectionCannotWriteAfterRegainingFocus() {
        begin(); events.remove().run(); same.set(false); visible.set(true); drain(); assertEquals(0,writes.get());
        same.set(true); visible.set(false); SeenWorkProbe old=begin(); events.remove().run();
        old.cancel(); visible.set(true); drain(); assertEquals(0,writes.get());
    }
    @Test public void neverFocusedWindowStopsChecking() {
        begin(); for(int i=0;i<10&&!events.isEmpty();i++) events.remove().run();
        assertTrue(events.isEmpty()); assertEquals(0,writes.get());
        assertTrue(checked.get()>1 && checked.get()<=6);
    }
}
