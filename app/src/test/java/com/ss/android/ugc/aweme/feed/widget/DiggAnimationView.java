package com.ss.android.ugc.aweme.feed.widget;

import android.content.Context;
import androidx.appcompat.widget.AppCompatImageView;

public final class DiggAnimationView extends AppCompatImageView {
    public boolean reportLayout = true;
    public DiggAnimationView(Context context) { super(context); }
    @Override public boolean isLaidOut() { return reportLayout && super.isLaidOut(); }
}
