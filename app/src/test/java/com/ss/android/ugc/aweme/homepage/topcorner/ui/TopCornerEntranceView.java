package com.ss.android.ugc.aweme.homepage.topcorner.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Consumed 41.0.0 View contract; no entrance manager or business-state simulation. */
public final class TopCornerEntranceView extends FrameLayout {
    public String d;
    // The host field is final; mutable here only to model a stale/out-of-owner getter.
    public ImageView e;

    public TopCornerEntranceView(Context context,String entranceId) {
        super(context);d=entranceId;
        e=new ImageView(context);e.setId(0x7f0abc18);
        addView(e,new FrameLayout.LayoutParams(40,40));
        View badge=new View(context);badge.setId(0x7f0abc17);
        addView(badge,new FrameLayout.LayoutParams(12,12));
    }
    public ImageView getIconView(){return e;}
    public int getCurrentStatus(){return 0;}
}
