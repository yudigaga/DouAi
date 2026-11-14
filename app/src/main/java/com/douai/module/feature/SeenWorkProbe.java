package com.douai.module.feature;

import java.util.function.BooleanSupplier;

/** Short settling window for the initial selection; no ongoing visibility polling. */
final class SeenWorkProbe implements Runnable {
    interface Scheduler { void later(Runnable run,long delayMillis); }
    private static final int[] DELAYS = {64, 160, 320, 640};
    private final BooleanSupplier finished;
    private final Scheduler scheduler;
    private int attempt;
    private volatile boolean cancelled;
    SeenWorkProbe(BooleanSupplier finished,Scheduler scheduler) {
        this.finished=finished; this.scheduler=scheduler;
    }
    void cancel() { cancelled=true; }
    @Override public void run() {
        if (cancelled || finished.getAsBoolean() || attempt==DELAYS.length) return;
        scheduler.later(this,DELAYS[attempt++]);
    }
}
