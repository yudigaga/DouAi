package com.douai.module.feature;

import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/** Owns only the temporary paint suppression of the verified 41.0.0 free-space colours. */
final class FeedColourBandLease {
    private static final int OVERLAY_ROOT=0x7f0a3ffa, COLOUR_CONTAINER=0x7f0a3fac;
    private WeakReference<View> hidden=new WeakReference<>(null);

    @android.annotation.SuppressLint("ResourceType") // These are verified IDs from the original host APK.
    boolean sync(View card,int nativeWidth,boolean redundant) {
        if(Build.VERSION.SDK_INT<29 || !redundant){restore();return false;}
        View candidate=card.findViewById(COLOUR_CONTAINER);
        if(!(candidate instanceof FrameLayout) || candidate.getClass()!=FrameLayout.class
                || !(candidate.getParent() instanceof View)){restore();return false;}
        FrameLayout colours=(FrameLayout)candidate;
        View parent=(View)colours.getParent();
        if(parent.getId()!=OVERLAY_ROOT
                || !"com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout".equals(parent.getClass().getName())
                || !isDecoration(colours,nativeWidth)){restore();return false;}
        View previous=hidden.get();
        if(previous!=colours)restore();
        if(colours.getTransitionAlpha()==1f){
            colours.setTransitionAlpha(0f);
            hidden=new WeakReference<>(colours);
            return previous!=colours;
        }else if(colours.getTransitionAlpha()!=0f)hidden.clear();
        return false;
    }

    private static boolean isDecoration(FrameLayout colours,int nativeWidth) {
        int n=colours.getChildCount(), edges=0;
        if(n==0 || n>2 || colours.getVisibility()!=View.VISIBLE || colours.getHeight()<=0
                || colours.getBackground()!=null || colours.getForeground()!=null
                || colours.isClickable() || colours.isFocusable() || colours.getAnimation()!=null
                || !colours.getMatrix().isIdentity())return false;
        for(int i=0;i<n;i++){
            View child=colours.getChildAt(i);
            if(child.getClass()!=ImageView.class || child.getId()!=View.NO_ID
                    || child.isClickable() || child.isLongClickable() || child.isFocusable()
                    || child.getBackground()!=null || child.getForeground()!=null
                    || child.getAnimation()!=null || !child.getMatrix().isIdentity()
                    || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams))return false;
            ImageView band=(ImageView)child;
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)band.getLayoutParams();
            if(band.getDrawable()==null || band.getScaleType()!=ImageView.ScaleType.CENTER_CROP
                    || p.width!=nativeWidth || p.height<=0 || p.height>colours.getHeight()/2
                    || (p.leftMargin|p.topMargin|p.rightMargin|p.bottomMargin)!=0)return false;
            int edge=p.gravity==(Gravity.TOP|Gravity.CENTER_HORIZONTAL)?1:
                    p.gravity==(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL)?2:0;
            if(edge==0 || (edges&edge)!=0)return false;
            edges|=edge;
        }
        return true;
    }

    void restore() {
        View view=hidden.get();
        hidden.clear();
        if(Build.VERSION.SDK_INT>=29 && view!=null && view.getTransitionAlpha()==0f)view.setTransitionAlpha(1f);
    }
}
