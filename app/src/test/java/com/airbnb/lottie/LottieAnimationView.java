package com.airbnb.lottie;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** Models the host setter cancelling a pending composition callback. */
public class LottieAnimationView extends ImageView {
    private Object composition;
    private boolean pending=true, cancelled, failingGetter;
    public LottieAnimationView(Context context) { super(context); }
    public Object getComposition() {
        if(failingGetter)throw new IllegalStateException("host getter unavailable");
        return composition;
    }
    public void failCompositionGetter() { failingGetter=true; }
    public void setHostPlaceholder(Drawable drawable) { super.setImageDrawable(drawable); }
    public void completeComposition(Drawable drawable) {
        if(cancelled)return;
        pending=false;composition=new Object();super.setImageDrawable(drawable);
    }
    @Override public void setImageDrawable(Drawable drawable) {
        if(pending)cancelled=true;
        super.setImageDrawable(drawable);
    }
}
