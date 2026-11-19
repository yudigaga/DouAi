package com.ss.android.ugc.aweme.feed.widget;

import android.content.Context;
import android.widget.FrameLayout;

public final class DiggAnimationLayout extends FrameLayout {
    public boolean reportLayout = true;
    public DiggAnimationLayout(Context context) { super(context); }
    @Override public boolean isLaidOut() { return reportLayout && super.isLaidOut(); }
}
