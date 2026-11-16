package com.douai.module.feature;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Actual measure and input dispatch, including the host's already-started forwarded gesture. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
public class NavigationItemVisibilityTest {
    @Test public void goneReflowsTheVerifiedRelativeSiblingRuleWithoutHidingTheBar() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();RelativeLayout bar=new RelativeLayout(activity);
        View first=new View(activity),second=new View(activity);first.setId(View.generateViewId());
        bar.addView(first,new RelativeLayout.LayoutParams(100,60));
        RelativeLayout.LayoutParams afterFirst=new RelativeLayout.LayoutParams(100,60);afterFirst.addRule(RelativeLayout.RIGHT_OF,first.getId());
        bar.addView(second,afterFirst);activity.setContentView(bar);layout(bar,300,60);
        try {
            assertEquals(100,second.getLeft());
            try(NavigationItemVisibility hidden=new NavigationItemVisibility(first)) {
                hidden.hide();layout(bar,300,60);assertEquals(0,second.getLeft());assertEquals(View.VISIBLE,bar.getVisibility());
            }
            layout(bar,300,60);assertEquals(100,second.getLeft());assertSame(afterFirst,second.getLayoutParams());
        } finally {activity.finish();}
    }
    @Test public void goneRemovesTheWholeWeightedSlotAndRestoresItsOriginalClickBusiness() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout bar=new LinearLayout(activity);bar.setOrientation(LinearLayout.HORIZONTAL);
        View first=new View(activity),second=new View(activity);int[] clicks={0,0};
        first.setOnClickListener(ignored->clicks[0]++);second.setOnClickListener(ignored->clicks[1]++);
        first.setFocusable(true);first.setSelected(true);first.setTranslationY(3f);
        LinearLayout.LayoutParams firstSize=new LinearLayout.LayoutParams(0,60,1);
        bar.addView(first,firstSize);bar.addView(second,new LinearLayout.LayoutParams(0,60,1));activity.setContentView(bar);layout(bar,300,60);
        try {
          try(NavigationItemVisibility hidden=new NavigationItemVisibility(first)) {
            assertEquals(150,first.getWidth());tap(bar,20,20);assertEquals(1,clicks[0]);
            hidden.hide();layout(bar,300,60);
            assertEquals(View.GONE,first.getVisibility());assertFalse(first.isEnabled());assertFalse(first.isClickable());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,first.getImportantForAccessibility());
            assertEquals(0,second.getLeft());assertEquals(300,second.getWidth());assertSame(firstSize,first.getLayoutParams());
            tap(bar,20,20);assertArrayEquals(new int[]{1,1},clicks);
            assertTrue(first.isSelected());assertEquals(3f,first.getTranslationY(),0f);
          }
          layout(bar,300,60);assertEquals(View.VISIBLE,first.getVisibility());assertTrue(first.isEnabled());assertTrue(first.isFocusable());
          assertEquals(150,first.getWidth());tap(bar,20,20);assertArrayEquals(new int[]{2,1},clicks);
        } finally {activity.finish();}
    }

    @Test public void hidingCancelsAChildGestureBeforeTheHostForwardsItsLaterUp() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout owner=new FrameLayout(activity);
        View child=new View(activity);int[] clicks={0},cancels={0};
        child.setOnClickListener(ignored->clicks[0]++);
        child.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels[0]++;return false;});
        owner.addView(child,new FrameLayout.LayoutParams(-1,-1));activity.setContentView(owner);layout(owner,160,80);
        NavigationItemVisibility hidden=new NavigationItemVisibility(owner);
        try {
            dispatch(owner,MotionEvent.ACTION_DOWN,20,20);hidden.hide();
            assertEquals(1,cancels[0]);
            dispatch(owner,MotionEvent.ACTION_UP,20,20);assertEquals(0,clicks[0]);
            hidden.hide();assertEquals("Stable hidden frames do not dispatch repeated cancel events",1,cancels[0]);
            hidden.close();layout(owner,160,80);tap(owner,20,20);assertEquals(1,clicks[0]);
            dispatch(owner,MotionEvent.ACTION_DOWN,20,20);
            try(NavigationItemVisibility hiddenAgain=new NavigationItemVisibility(owner)){hiddenAgain.hide();}
            dispatch(owner,MotionEvent.ACTION_UP,20,20);ShadowLooper.idleMainLooper();
            assertEquals("Showing again before a forwarded UP must not resurrect the cancelled gesture",1,clicks[0]);
        } finally {hidden.close();activity.finish();}
    }

    @Test public void closePreservesHostChangesMadeAfterModuleOwnershipWasLost() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();View owner=new View(activity);
        owner.setEnabled(false);owner.setClickable(false);owner.setFocusable(false);
        NavigationItemVisibility hidden=new NavigationItemVisibility(owner);hidden.hide();
        owner.setVisibility(View.INVISIBLE);owner.setEnabled(true);owner.setClickable(true);owner.setFocusable(true);
        owner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        hidden.close();hidden.close();
        assertEquals(View.INVISIBLE,owner.getVisibility());assertTrue(owner.isEnabled());assertTrue(owner.isClickable());assertTrue(owner.isFocusable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES,owner.getImportantForAccessibility());activity.finish();
    }

    @Test public void repeatedOwnershipAdoptsNewNativeValuesAndNeverForcesANativelyGoneItemVisible() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();View owner=new View(activity);
        owner.setFocusable(false);NavigationItemVisibility hidden=new NavigationItemVisibility(owner);hidden.hide();
        owner.setVisibility(View.INVISIBLE);owner.setFocusable(true);hidden.hide();hidden.close();
        assertEquals(View.INVISIBLE,owner.getVisibility());assertTrue(owner.isFocusable());
        owner.setFocusable(View.FOCUSABLE_AUTO);
        owner.setVisibility(View.GONE);owner.setEnabled(false);
        try(NavigationItemVisibility originallyGone=new NavigationItemVisibility(owner)){originallyGone.hide();}
        assertEquals(View.GONE,owner.getVisibility());assertFalse(owner.isEnabled());assertEquals(View.FOCUSABLE_AUTO,owner.getFocusable());activity.finish();
    }

    private static void layout(View view,int width,int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        view.layout(0,0,width,height);
    }
    private static void tap(View owner,float x,float y){dispatch(owner,MotionEvent.ACTION_DOWN,x,y);dispatch(owner,MotionEvent.ACTION_UP,x,y);ShadowLooper.idleMainLooper();}
    private static void dispatch(View owner,int action,float x,float y) {
        long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
        try{owner.dispatchTouchEvent(event);}finally{event.recycle();}
    }
}
