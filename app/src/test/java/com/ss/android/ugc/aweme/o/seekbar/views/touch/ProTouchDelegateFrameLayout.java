package com.ss.android.ugc.aweme.o.seekbar.views.touch;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import X.WindowProgressTouchFixtures;

/** Existing fixtures use ordinary dispatch; routing regressions opt into the verified native manager order. */
public final class ProTouchDelegateFrameLayout extends FrameLayout {
    private Object manager;
    private boolean managerFirstRouting;
    public ProTouchDelegateFrameLayout(Context context) { super(context); }
    public Object getManager() { return manager; }
    public void setManager(Object manager) { this.manager = manager; }
    public void setManagerFirstRouting(boolean enabled) { managerFirstRouting = enabled; }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return managerFirstRouting && WindowProgressTouchFixtures.dispatchToManager(this,event)
                || super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        return managerFirstRouting && WindowProgressTouchFixtures.dispatchToManager(this,event)
                || super.onTouchEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        try { return super.dispatchTouchEvent(event); }
        finally { if(managerFirstRouting)WindowProgressTouchFixtures.finishManagerGesture(this,event); }
    }
}
