package com.douai.module.feature;

import android.view.MotionEvent;
import io.github.libxposed.api.XposedInterface;

/** Allows only our own two gates to recognize one explicitly assigned host gesture. */
final class AssignedDoubleTapLike {
    private static final ThreadLocal<AssignedDoubleTapLike> ACTIVE = new ThreadLocal<>();
    private final Object panel, holder, work;
    private final MotionEvent event;
    private boolean passedOuter, passedDigg;

    AssignedDoubleTapLike(Object panel, MotionEvent event, Object holder, Object work) {
        this.panel = panel;
        this.event = event;
        this.holder = holder;
        this.work = work;
    }

    Object proceed(XposedInterface.Chain chain) throws Throwable {
        if (ACTIVE.get() != null) return chain.proceed();
        ACTIVE.set(this);
        try { return chain.proceed(); }
        finally { ACTIVE.remove(); }
    }

    static boolean admit(String rule, XposedInterface.Chain chain) {
        AssignedDoubleTapLike active = ACTIVE.get();
        if (active == null || chain.getThisObject() != active.panel || chain.getArg(0) != active.event) return false;
        if ("adv_ui_double_tap".equals(rule) && !active.passedOuter) {
            active.passedOuter = true;
            return true;
        }
        if ("adv_ui_big_digg".equals(rule) && active.passedOuter && !active.passedDigg
                && chain.getArg(1) == active.holder && chain.getArg(2) == active.work) {
            active.passedDigg = true;
            return true;
        }
        return false;
    }
}
