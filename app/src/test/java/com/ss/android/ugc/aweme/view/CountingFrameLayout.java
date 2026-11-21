package com.ss.android.ugc.aweme.view;

import android.content.Context;
import android.widget.FrameLayout;

/** Host-shaped test root that counts direct-child enumeration by discovery passes. */
public final class CountingFrameLayout extends FrameLayout {
    private int childCountReads;

    public CountingFrameLayout(Context context) {
        super(context);
    }

    @Override public int getChildCount() {
        childCountReads++;
        return super.getChildCount();
    }

    public int childCountReads() {
        return childCountReads;
    }

    public void resetChildCountReads() {
        childCountReads = 0;
    }
}
