package com.douai.module.feature;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/** Owns only the whole native navigation item's visibility and interaction flags. */
final class NavigationItemVisibility implements AutoCloseable {
    private final View owner;
    private int visibility,accessibility,focusability;
    private boolean enabled,clickable,longClickable,contextClickable,applied,closed;

    NavigationItemVisibility(View owner){this.owner=owner;}

    void hide() {
        if(closed)return;
        if(!applied || owner.getVisibility()!=View.GONE) {
            // The host may forward the UP of an already-started gesture to this item after it is gone.
            long now=SystemClock.uptimeMillis();MotionEvent cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
            try{owner.dispatchTouchEvent(cancel);}finally{cancel.recycle();}
            owner.cancelPendingInputEvents();
        }
        if(!applied || owner.getVisibility()!=View.GONE)visibility=owner.getVisibility();
        if(!applied || owner.isEnabled())enabled=owner.isEnabled();
        if(!applied || owner.isClickable())clickable=owner.isClickable();
        if(!applied || owner.isLongClickable())longClickable=owner.isLongClickable();
        if(!applied || owner.isContextClickable())contextClickable=owner.isContextClickable();
        if(!applied || owner.getFocusable()!=View.NOT_FOCUSABLE)focusability=owner.getFocusable();
        if(!applied || owner.getImportantForAccessibility()!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            accessibility=owner.getImportantForAccessibility();
        if(owner.isEnabled())owner.setEnabled(false);
        if(owner.isClickable())owner.setClickable(false);
        if(owner.isLongClickable())owner.setLongClickable(false);
        if(owner.isContextClickable())owner.setContextClickable(false);
        if(owner.getFocusable()!=View.NOT_FOCUSABLE)owner.setFocusable(View.NOT_FOCUSABLE);
        if(owner.getImportantForAccessibility()!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            owner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if(owner.getVisibility()!=View.GONE)owner.setVisibility(View.GONE);
        applied=true;
    }

    @Override public void close() {
        if(closed)return;closed=true;
        if(!applied)return;
        if(!owner.isEnabled())owner.setEnabled(enabled);
        if(!owner.isClickable())owner.setClickable(clickable);
        if(!owner.isLongClickable())owner.setLongClickable(longClickable);
        if(!owner.isContextClickable())owner.setContextClickable(contextClickable);
        if(owner.getFocusable()==View.NOT_FOCUSABLE)owner.setFocusable(focusability);
        if(owner.getImportantForAccessibility()==View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            owner.setImportantForAccessibility(accessibility);
        if(owner.getVisibility()==View.GONE)owner.setVisibility(visibility);
    }
}
