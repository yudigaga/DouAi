package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBarContainer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="mdpi")
public class NavigationGlassLayoutTest {
    @Test public void bottomRestingInsetLeavesSixteenDpBeyondItsMotionEnvelopeAndRestoresOnClose() {
        try(Fixture f=new Fixture(false)) {
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,false);
            state.refresh(f.labels,10);layout(f.parent);
            assertEquals(26,f.params.bottomMargin);assertEquals(774,f.owner.getBottom());
            state.refresh(f.labels,0);layout(f.parent);
            assertEquals(16,f.params.bottomMargin);assertEquals(784,f.owner.getBottom());
            state.close();assertEquals(0,f.params.bottomMargin);
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void verifiedFixedTopContainerLeavesRoomForAllFourMotionDirections() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity);LinearLayout wrapper=new LinearLayout(activity);
            wrapper.setOrientation(LinearLayout.VERTICAL);wrapper.setClipChildren(false);
            root.addView(wrapper,new FrameLayout.LayoutParams(-1,-2));
            MainTitleBarContainer container=new MainTitleBarContainer(activity);container.setId(0x7f0ab82b);container.setClipChildren(false);
            LinearLayout.LayoutParams originalContainer=new LinearLayout.LayoutParams(-1,44);wrapper.addView(container,originalContainer);
            RelativeLayout owner=new RelativeLayout(activity);owner.setId(0x7f0a2866);
            FrameLayout.LayoutParams originalOwner=new FrameLayout.LayoutParams(-1,-1);container.addView(owner,originalOwner);
            activity.setContentView(root);layout(root);NavigationGlassLayout state=new NavigationGlassLayout(owner,true);
            try {
                state.refresh(Collections.emptyList(),10);layout(root);
                assertTrue(owner.getTop()>=10);assertTrue("The real top parent needs space below its navigation",container.getHeight()-owner.getBottom()>=10);
                assertFourDirections(activity,root,container,owner);assertFalse(state.refresh(Collections.emptyList(),10));
                assertFalse(container.getClipChildren());assertTrue(container.getClipToPadding());
                state.refresh(Collections.emptyList(),0);layout(root);assertEquals(0,originalOwner.topMargin);assertEquals(0,originalOwner.bottomMargin);
                state.close();layout(root);assertSame(originalContainer,container.getLayoutParams());assertEquals(44,container.getHeight());
                assertEquals(-1,originalOwner.height);assertFalse(container.getClipChildren());
            }finally{state.close();}
        }finally{activity.finish();}
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void wrapContentBottomContainerLeavesRoomForAllFourMotionDirections() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity),container=new FrameLayout(activity);container.setId(0x7f0a16fe);
            // The verified MainBottomTabContainer extends FrameLayout without a measuring override.
            FrameLayout.LayoutParams originalContainer=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(container,originalContainer);
            FrameLayout owner=(FrameLayout)Class.forName("X.1JgG").getConstructor(android.content.Context.class).newInstance(activity);
            FrameLayout.LayoutParams originalOwner=new FrameLayout.LayoutParams(-1,58,Gravity.BOTTOM);container.addView(owner,originalOwner);
            activity.setContentView(root);layout(root);NavigationGlassLayout state=new NavigationGlassLayout(owner,false);
            try {
                state.refresh(Collections.emptyList(),10);layout(root);
                assertTrue("The real bottom parent needs space above its navigation",owner.getTop()>=10);assertTrue(container.getHeight()-owner.getBottom()>=10);
                assertFourDirections(activity,root,container,owner);assertFalse(state.refresh(Collections.emptyList(),10));
                assertTrue(container.getClipChildren());assertTrue(container.getClipToPadding());
                state.refresh(Collections.emptyList(),0);layout(root);assertEquals(0,originalOwner.topMargin);assertEquals(16,originalOwner.bottomMargin);
                state.close();layout(root);assertSame(originalContainer,container.getLayoutParams());assertEquals(-2,originalContainer.height);
                assertEquals(58,container.getHeight());assertEquals(58,originalOwner.height);assertTrue(container.getClipChildren());
            }finally{state.close();}
        }finally{activity.finish();}
    }

    @Test public void motionLeavesRoomOnTheDockEdgeAndDisablingItRestoresThatSpace() {
        for(boolean top:new boolean[]{false,true}) {
            Fixture f=new Fixture(top);
            try {
                if(top){f.params.gravity=Gravity.TOP;f.owner.requestLayout();layout(f.parent);}
                NavigationGlassLayout state=new NavigationGlassLayout(f.owner,top);
                assertTrue(state.refresh(f.labels,10));layout(f.parent);
                assertEquals(top?10:26,top?f.params.topMargin:f.params.bottomMargin);
                if(top)assertEquals(10,f.owner.getTop());
                else assertEquals(774,f.owner.getBottom());
                assertFalse(state.refresh(f.labels,10));
                state.refresh(f.labels,0);layout(f.parent);
                assertEquals(top?0:16,top?f.params.topMargin:f.params.bottomMargin);
                state.refresh(f.labels,10);layout(f.parent);
                if(top)f.params.topMargin=4;else f.params.bottomMargin=4;
                state.close();assertEquals(4,top?f.params.topMargin:f.params.bottomMargin);
            } finally {f.close();}
        }
    }
    @Test public void bottomShrinksTheActualOwnerAndKeepsFiveEqualContentSlotsInsideIt() {
        Fixture f=new Fixture(false);
        try {
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,false);
            assertTrue(state.refresh(f.labels));layout(f.parent);
            assertSame(f.params,f.owner.getLayoutParams());
            assertEquals(24,f.owner.getLeft());assertEquals(336,f.owner.getRight());
            assertTrue(f.owner.getHeight()>=72);assertEquals(8,f.owner.getPaddingLeft());
            assertEquals(10,f.owner.getPaddingTop());assertFalse(state.refresh(f.labels));
            int first=f.row.getChildAt(0).getWidth();
            for(int i=0;i<5;i++) {
                View item=f.row.getChildAt(i);
                LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)item.getLayoutParams();
                assertEquals(0,params.width);assertEquals(1f,params.weight,0);
                assertTrue(Math.abs(first-item.getWidth())<=1);
                Rect bounds=new Rect(0,0,f.labels.get(i).getWidth(),f.labels.get(i).getHeight());
                f.owner.offsetDescendantRectToMyCoords(f.labels.get(i),bounds);
                assertTrue(bounds.left>=0 && bounds.top>=0);
                assertTrue(bounds.right<=f.owner.getWidth() && bounds.bottom<=f.owner.getHeight());
            }
            state.close();assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,f.params.width);
            assertEquals(48,f.params.height);assertEquals(0,f.owner.getMinimumHeight());
            assertEquals(0,f.owner.getPaddingTop());
        } finally {f.close();}
    }

    @Test public void topGrowsAllThreeContentFramesForAnIconAboveScaledText() {
        Fixture f=new Fixture(true);
        try {
            TextView label=f.labels.get(0);label.setTextSize(TypedValue.COMPLEX_UNIT_PX,28);
            ColorDrawable icon=new ColorDrawable(0xff333333);icon.setBounds(0,0,24,52);
            label.setCompoundDrawables(null,icon,null,null);label.setCompoundDrawablePadding(6);
            float size=label.getTextSize();CharSequence text=label.getText();
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,true);
            assertTrue(state.refresh(f.labels));layout(f.parent);
            assertEquals(16,f.owner.getLeft());assertEquals(344,f.owner.getRight());
            assertTrue(f.owner.getHeight()>=52+6+28+16);
            for(int id:new int[]{0x7f0abca6,0x7f0abc82,0x7f0abc52}) {
                assertEquals(f.owner.getHeight()-16,f.owner.findViewById(id).getHeight());
            }
            assertSame(text,label.getText());assertEquals(size,label.getTextSize(),0);
            assertSame(icon,label.getCompoundDrawables()[1]);
            state.close();
            for(int id:new int[]{0x7f0abca6,0x7f0abc82,0x7f0abc52})
                assertEquals(44,f.owner.findViewById(id).getLayoutParams().height);
        } finally {f.close();}
    }

    @Test public void wideCompoundIconsReduceOuterMarginsBeforeTheirSlotsCanClip() {
        Fixture f=new Fixture(false);
        try {
            for(TextView label:f.labels) {
                ColorDrawable icon=new ColorDrawable(0xff555555);icon.setBounds(0,0,58,20);
                label.setCompoundDrawables(null,icon,null,null);
            }
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,false);
            assertTrue(state.refresh(f.labels));layout(f.parent);
            assertTrue(f.owner.getLeft()<24);assertEquals(346,f.owner.getWidth());
            for(int i=0;i<5;i++)assertTrue(f.row.getChildAt(i).getWidth()>=66);
            state.close();
        } finally {f.close();}
    }

    @Test public void hostInPlaceChangesBecomeNewBaselinesWithoutAccumulatingPaddingOrHeight() {
        Fixture f=new Fixture(false);
        try {
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,false);
            state.refresh(f.labels);layout(f.parent);
            f.params.height=100;f.params.leftMargin=7;f.params.rightMargin=9;
            f.owner.setPadding(3,4,5,6);f.owner.setMinimumHeight(90);
            assertTrue(state.refresh(f.labels));layout(f.parent);
            assertEquals(100,f.owner.getHeight());assertFalse(state.refresh(f.labels));
            state.close();
            assertSame(f.params,f.owner.getLayoutParams());assertEquals(100,f.params.height);
            assertEquals(7,f.params.leftMargin);assertEquals(9,f.params.rightMargin);
            assertEquals(3,f.owner.getPaddingLeft());assertEquals(4,f.owner.getPaddingTop());
            assertEquals(5,f.owner.getPaddingRight());assertEquals(6,f.owner.getPaddingBottom());
            assertEquals(90,f.owner.getMinimumHeight());
        } finally {f.close();}
    }

    @Test public void closePreservesFieldsTheHostChangedAfterTheLastRefresh() {
        Fixture f=new Fixture(false);
        try {
            NavigationGlassLayout state=new NavigationGlassLayout(f.owner,false);
            state.refresh(f.labels);f.params.height=111;f.params.rightMargin=31;
            f.owner.setPadding(8,10,8,12);f.owner.setMinimumHeight(99);
            state.close();state.close();
            assertEquals(111,f.params.height);assertEquals(31,f.params.rightMargin);
            assertEquals(0,f.params.leftMargin);assertEquals(0,f.owner.getPaddingLeft());
            assertEquals(0,f.owner.getPaddingTop());assertEquals(12,f.owner.getPaddingBottom());
            assertEquals(99,f.owner.getMinimumHeight());assertFalse(state.refresh(f.labels));
        } finally {f.close();}
    }

    @Test public void relativeOwnerKeepsItsParameterTypeAndRestoresItsHorizontalRules() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            RelativeLayout parent=new RelativeLayout(activity);FrameLayout owner=new FrameLayout(activity);
            RelativeLayout.LayoutParams params=new RelativeLayout.LayoutParams(200,44);
            params.addRule(RelativeLayout.ALIGN_PARENT_END);params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            parent.addView(owner,params);activity.setContentView(parent);layout(parent);
            NavigationGlassLayout state=new NavigationGlassLayout(owner,true);
            assertTrue(state.refresh(Collections.emptyList()));layout(parent);
            assertSame(params,owner.getLayoutParams());assertEquals(96,owner.getLeft());
            assertEquals(264,owner.getRight());assertEquals(-1,params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM));
            state.close();parent.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);layout(parent);
            assertEquals(0,owner.getLeft());assertEquals(200,owner.getRight());
            assertEquals(0,params.getRule(RelativeLayout.CENTER_HORIZONTAL));
        } finally {activity.finish();}
    }

    @Test public void detachedOrUnmeasuredOwnersWaitWithoutMutatingHostLayout() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout owner=new FrameLayout(activity);owner.setPadding(1,2,3,4);
            NavigationGlassLayout state=new NavigationGlassLayout(owner,false);
            assertFalse(state.refresh(Collections.emptyList()));assertEquals(2,owner.getPaddingTop());
            FrameLayout parent=new FrameLayout(activity);parent.addView(owner,new FrameLayout.LayoutParams(-1,48));
            assertFalse(state.refresh(Collections.emptyList()));assertEquals(48,owner.getLayoutParams().height);
            state.close();
        } finally {activity.finish();}
    }

    @Test public void verifiedTopContainerGrowsSoTheNewAreaReallyDispatchesTouches() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity);LinearLayout wrapper=new LinearLayout(activity);
            wrapper.setOrientation(LinearLayout.VERTICAL);wrapper.setClipChildren(false);
            root.addView(wrapper,new FrameLayout.LayoutParams(-1,-2));
            MainTitleBarContainer container=new MainTitleBarContainer(activity);container.setId(0x7f0ab82b);container.setClipChildren(false);
            LinearLayout.LayoutParams containerParams=new LinearLayout.LayoutParams(-1,44);
            wrapper.addView(container,containerParams);
            RelativeLayout owner=new RelativeLayout(activity);owner.setId(0x7f0a2866);owner.setClipChildren(false);
            container.addView(owner,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout center=new FrameLayout(activity);center.setId(0x7f0abca6);
            RelativeLayout.LayoutParams centerParams=new RelativeLayout.LayoutParams(-1,44);centerParams.addRule(RelativeLayout.CENTER_VERTICAL);
            owner.addView(center,centerParams);
            TextView label=new TextView(activity);label.setText("首页");label.setTextSize(TypedValue.COMPLEX_UNIT_PX,11);
            center.addView(label,new FrameLayout.LayoutParams(48,20,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
            int[] clicks={0},touchEvents={0};label.setOnClickListener(ignored->clicks[0]++);
            label.setOnTouchListener((view,event)->{
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN || event.getActionMasked()==MotionEvent.ACTION_UP)touchEvents[0]++;
                return false;
            });
            activity.setContentView(root);layout(root);
            NavigationGlassLayout state=new NavigationGlassLayout(owner,true);assertTrue(state.refresh(Collections.singletonList(label)));layout(root);
            assertEquals(64,owner.getHeight());assertEquals(64,container.getHeight());assertEquals(64,wrapper.getHeight());
            Rect area=new Rect(0,0,label.getWidth(),label.getHeight());root.offsetDescendantRectToMyCoords(label,area);
            assertTrue("Tap lies beyond the original 44dp host container",area.centerY()>44);
            tap(root,area.centerX(),area.centerY());
            assertEquals("The real DOWN and UP must reach the original host label",2,touchEvents[0]);
            ShadowLooper.idleMainLooper();assertEquals(1,clicks[0]);
            // The queued window traversal may replace our synthetic dimensions; compare the same viewport.
            layout(root);
            assertFalse(state.refresh(Collections.singletonList(label)));state.close();layout(root);
            assertSame(containerParams,container.getLayoutParams());assertEquals(44,container.getHeight());assertEquals(44,wrapper.getHeight());
        } finally {activity.finish();}
    }

    @Test public void topContainerRestorationPreservesAHostHeightWrittenAfterRefresh() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity);MainTitleBarContainer container=new MainTitleBarContainer(activity);
            container.setId(0x7f0ab82b);FrameLayout.LayoutParams outer=new FrameLayout.LayoutParams(-1,44);root.addView(container,outer);
            FrameLayout owner=new FrameLayout(activity);owner.setId(0x7f0a2866);container.addView(owner,new FrameLayout.LayoutParams(-1,-1));
            activity.setContentView(root);layout(root);
            NavigationGlassLayout state=new NavigationGlassLayout(owner,true);state.refresh(Collections.emptyList());layout(root);
            assertEquals(64,container.getHeight());outer.height=82;state.close();layout(root);
            assertEquals(82,container.getHeight());
        } finally {activity.finish();}
    }

    @Test public void anUnknownAncestorWithTheSameResourceIdKeepsItsHostHeight() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity),unknown=new FrameLayout(activity),owner=new FrameLayout(activity);
            unknown.setId(0x7f0ab82b);owner.setId(0x7f0a2866);root.addView(unknown,new FrameLayout.LayoutParams(-1,44));
            unknown.addView(owner,new FrameLayout.LayoutParams(-1,-1));activity.setContentView(root);layout(root);
            NavigationGlassLayout state=new NavigationGlassLayout(owner,true);state.refresh(Collections.emptyList());layout(root);
            assertEquals(44,unknown.getHeight());state.close();
        } finally {activity.finish();}
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout parent=new FrameLayout(activity);
        final ViewGroup owner;
        final FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,48,Gravity.BOTTOM);
        final List<TextView> labels=new ArrayList<>();
        LinearLayout row;
        Fixture(boolean top) {
            owner=top?new RelativeLayout(activity):new FrameLayout(activity);
            parent.addView(owner,params);
            if(top) {
                for(int id:new int[]{0x7f0abca6,0x7f0abc82,0x7f0abc52}) {
                    FrameLayout frame=new FrameLayout(activity);frame.setId(id);
                    RelativeLayout.LayoutParams p=new RelativeLayout.LayoutParams(id==0x7f0abca6?-1:32,44);
                    p.addRule(RelativeLayout.CENTER_VERTICAL);
                    if(id==0x7f0abc82)p.addRule(RelativeLayout.ALIGN_PARENT_END);
                    owner.addView(frame,p);
                    if(id==0x7f0abca6) {
                        TextView label=label();frame.addView(label,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                    }
                }
            } else {
                row=new LinearLayout(activity);row.setId(0x7f0a7983);row.setOrientation(LinearLayout.HORIZONTAL);
                owner.addView(row,new FrameLayout.LayoutParams(-1,-1));
                for(int i=0;i<5;i++) {
                    FrameLayout item=new FrameLayout(activity);
                    row.addView(item,new LinearLayout.LayoutParams(0,-1,1));
                    item.addView(label(),new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                }
            }
            activity.setContentView(parent);layout(parent);
        }
        TextView label() {
            TextView text=new TextView(activity);text.setText("首页");text.setTextSize(TypedValue.COMPLEX_UNIT_PX,11);
            ColorDrawable icon=new ColorDrawable(0xff444444);icon.setBounds(0,0,20,20);
            text.setCompoundDrawables(null,icon,null,null);text.setCompoundDrawablePadding(3);
            labels.add(text);return text;
        }
        @Override public void close(){activity.finish();}
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,360,800);
    }
    private static void assertFourDirections(Activity activity,View root,ViewGroup parent,View owner) {
        LiquidGlassMotion motion=new LiquidGlassMotion(activity);
        try {
            motion.setTargets(Collections.singletonList(owner),root,null);
            for(float[] axis:new float[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                motion.applyFrame(new LiquidGlassMotion.Vector(axis[0],axis[1]));
                float distance=axis[0]!=0?owner.getTranslationX()*axis[0]:owner.getTranslationY()*axis[1];
                assertTrue("Each direction must keep substantial travel: "+axis[0]+","+axis[1]+" = "+distance,distance>=8);
                assertTrue(distance<=10.01f);
                RectF painted=new RectF(0,0,owner.getWidth(),owner.getHeight());owner.getMatrix().mapRect(painted);painted.offset(owner.getLeft(),owner.getTop());
                assertTrue(painted.left>=-.01f && painted.top>=-.01f && painted.right<=parent.getWidth()+.01f && painted.bottom<=parent.getHeight()+.01f);
            }
        }finally{motion.close();}
        assertEquals(0,owner.getTranslationX(),0);assertEquals(0,owner.getTranslationY(),0);
    }
    private static void tap(View root,int x,int y) {
        long now=android.os.SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        try{root.dispatchTouchEvent(down);root.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
    }
}
