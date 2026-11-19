package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.container.MainTabStripNew;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew.NativeThemeTop;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.scroll.MainTabStripScrollView;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTabRelativeLayout;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBarContainer;
import com.ss.android.ugc.aweme.homepage.topcorner.ui.TopCornerEntranceView;
import com.ss.android.ugc.aweme.homepage.ui.view.antiburn.AntiBurnDuxImageView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Synthetic host layout/input contracts; these fixtures do not claim to reproduce host XML. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class NavigationGlassTopShrinkTest {
    private Object registry;
    private Map<String,Object> namesBefore;
    private String windowsBefore;

    @Before public void isolateNativeWindowsAndNavigationMetadata() {
        registry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        Map<String,Object> names=ReflectionHelpers.getField(registry,"observed");
        synchronized(registry){namesBefore=new LinkedHashMap<>(names);names.clear();}
        windowsBefore=System.getProperty("robolectric.areWindowsMarkedVisible");
        System.setProperty("robolectric.areWindowsMarkedVisible","true");
    }
    @After public void restoreNativeWindowsAndNavigationMetadata() {
        try {
            if(windowsBefore==null)System.clearProperty("robolectric.areWindowsMarkedVisible");
            else System.setProperty("robolectric.areWindowsMarkedVisible",windowsBefore);
        } finally {
            if(registry!=null && namesBefore!=null) {
                Map<String,Object> names=ReflectionHelpers.getField(registry,"observed");
                synchronized(registry){names.clear();names.putAll(namesBefore);}
            }
        }
    }

    @Test public void host41StripShrinksSixControlsWithoutOverlappingEitherCorner() {
        for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL})try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,direction,12,12);
            for(String key:List.of("homepage_hot","homepage_follow","homepage_mall","homepage_short_drama","homepage_nearby","homepage_hotspot"))
                host.tab(key);
            s.start(false);
            assertEquals(direction,s.owner.getLayoutDirection());
            assertEquals(312,s.owner.getWidth());
            try(NavigationItemVisibility first=new NavigationItemVisibility(s.tabs.get(1).item);
                NavigationItemVisibility second=new NavigationItemVisibility(s.tabs.get(3).item)) {
                first.hide();second.hide();s.refresh();
                assertEquals(240,s.owner.getWidth()); // Four 36 px slots, 40 px on each side, and owner padding.
                assertTrue(s.geometry.topCapacityDiagnostic().contains("result=accepted"));
                Rect start=bounds(s.owner,s.menu),end=bounds(s.owner,s.search);
                assertEquals(direction==View.LAYOUT_DIRECTION_LTR,start.left<end.left);
                for(int index:new int[]{0,2,4,5}) {
                    Tab kept=s.tabs.get(index);
                    assertContained(s.owner,kept.item);assertIconFits(kept.title);
                    Rect hit=bounds(s.owner,kept.item);
                    assertFalse(Rect.intersects(hit,start));assertFalse(Rect.intersects(hit,end));
                }
                tapAt(s.root,s.tabs.get(2).item);ShadowLooper.idleMainLooper();layout(s.root);
                assertEquals(1,s.tabs.get(2).clicks);
                assertFalse(s.geometry.refresh(s.labels()));
            }
            s.refresh();s.refresh();assertEquals(312,s.owner.getWidth());
            assertEquals(12,host.scrollParams.leftMargin);assertEquals(12,host.scrollParams.rightMargin);
            s.restore();assertEquals(-1,s.ownerParams.width);assertEquals(44,s.centerParams.height);
        }
    }

    @Test public void host41SixControlsDoNotReserveFullScreenCenteringSpace() {
        for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL})try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,direction,90,90);
            for(int i=0;i<4;i++)host.tab("tab_"+i);
            s.start(false);
            assertEquals("Four 36px slots, two 40px edge reserves and two 8px insets",240,s.owner.getWidth());
            View viewport=(View)host.row.getParent();
            assertEquals(144,viewport.getWidth());
            assertEquals(0,viewport.getScrollX());
            Rect menu=bounds(s.owner,s.menu),search=bounds(s.owner,s.search);
            for(Tab tab:s.tabs) {
                assertContained(s.owner,tab.item);
                assertIconFits(tab.title);
                Rect hit=bounds(s.owner,tab.item);
                assertFalse(Rect.intersects(hit,menu));
                assertFalse(Rect.intersects(hit,search));
                tapAt(s.root,tab.item);
            }
            FrameLayout indicator=(FrameLayout)host.row.getChildAt(0);
            for(Tab selected:List.of(s.tabs.get(0),s.tabs.get(3))) {
                Rect slot=bounds(host.row,selected.item);
                indicator.setTranslationX(slot.centerX()-indicator.getWidth()/2-indicator.getLeft());
                Rect painted=new Rect();
                assertTrue(indicator.getChildAt(0).getGlobalVisibleRect(painted));
                assertEquals(indicator.getChildAt(0).getWidth(),painted.width());
            }
            tapAt(s.root,s.menu.getChildAt(0));
            tapAt(s.root,s.search.getChildAt(0));
            ShadowLooper.idleMainLooper();
            assertEquals(1,s.menuClicks);
            assertEquals(1,s.searchClicks);
            for(Tab tab:s.tabs)assertEquals(1,tab.clicks);
            assertFalse(s.geometry.refresh(s.labels()));

            // Host rewrites the logical and resolved margins on the same LayoutParams.
            host.scrollParams.setMarginStart(96);
            host.scrollParams.leftMargin=direction==View.LAYOUT_DIRECTION_LTR?96:host.scrollParams.leftMargin;
            host.scrollParams.rightMargin=direction==View.LAYOUT_DIRECTION_RTL?96:host.scrollParams.rightMargin;
            viewport.requestLayout();
            layout(s.root);
            s.refresh();
            assertEquals(246,s.owner.getWidth()); // The asymmetric six pixels are native.
            assertEquals(144,viewport.getWidth());

            host.scrollParams.setMarginStart(60);
            s.refresh(); // The native logical write has not been resolved into physical margins yet.
            assertEquals(254,s.owner.getWidth()); // 144 + 32 + 62 + 16.
            assertEquals(144,viewport.getWidth());
            int reversed=direction==View.LAYOUT_DIRECTION_LTR?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR;
            s.root.setLayoutDirection(reversed);s.owner.setLayoutDirection(reversed);
            s.refresh();s.refresh();
            assertEquals(reversed,viewport.getLayoutDirection());
            assertEquals(254,s.owner.getWidth());
            assertEquals(144,viewport.getWidth());

            s.restore();
            assertSame(host.scrollParams,viewport.getLayoutParams());
            assertEquals(60,host.scrollParams.getMarginStart());
            assertEquals(90,host.scrollParams.getMarginEnd());
            assertEquals(reversed==View.LAYOUT_DIRECTION_LTR?60:90,host.scrollParams.leftMargin);
            assertEquals(reversed==View.LAYOUT_DIRECTION_LTR?90:60,host.scrollParams.rightMargin);
        }
    }

    @Test public void host41UncenteredSixControlsDiscardOnlySharedSlackBeyondCorners() {
        for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL})try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,direction,328,335);
            host.scrollParams.removeRule(RelativeLayout.CENTER_HORIZONTAL);
            for(int i=0;i<6;i++)host.tab("tab_"+i);
            s.start(false);

            assertTrue(s.geometry.topCapacityDiagnostic(),s.geometry.topCapacityDiagnostic().contains("result=accepted"));
            assertEquals(305,s.owner.getWidth());
            assertEquals(216,((View)host.row.getParent()).getWidth());
            assertEquals(33,host.scrollParams.getMarginStart());
            assertEquals(40,host.scrollParams.getMarginEnd());
            Rect menu=bounds(s.owner,s.menu),search=bounds(s.owner,s.search);
            for(Tab tab:s.tabs) {
                assertContained(s.owner,tab.item);
                assertFalse(Rect.intersects(bounds(s.owner,tab.item),menu));
                assertFalse(Rect.intersects(bounds(s.owner,tab.item),search));
                tapAt(s.root,tab.item);
            }
            tapAt(s.root,s.menu.getChildAt(0));tapAt(s.root,s.search.getChildAt(0));
            ShadowLooper.idleMainLooper();
            for(Tab tab:s.tabs)assertEquals(1,tab.clicks);
            assertEquals(1,s.menuClicks);assertEquals(1,s.searchClicks);

            int reversed=direction==View.LAYOUT_DIRECTION_LTR?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR;
            s.root.setLayoutDirection(reversed);s.owner.setLayoutDirection(reversed);
            s.refresh();s.refresh();layout(s.root);
            assertEquals(reversed,((View)host.row.getParent()).getLayoutDirection());
            assertEquals(s.geometry.topCapacityDiagnostic(),305,s.owner.getWidth());
            s.restore();
            layout(s.root);
            assertEquals(328,host.scrollParams.getMarginStart());
            assertEquals(335,host.scrollParams.getMarginEnd());
            assertEquals(reversed==View.LAYOUT_DIRECTION_LTR?328:335,host.scrollParams.leftMargin);
            assertEquals(reversed==View.LAYOUT_DIRECTION_LTR?335:328,host.scrollParams.rightMargin);
        }
    }

    @Test public void host41ReleasesCompactedScrollMarginsWhenTheViewportDisappears() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,90,90);
            for(int i=0;i<4;i++)host.tab("tab_"+i);
            s.start(false);
            assertEquals(240,s.owner.getWidth());
            layout(s.root,0);
            s.geometry.refresh(s.labels());
            assertEquals(90,host.scrollParams.getMarginStart());
            assertEquals(90,host.scrollParams.getMarginEnd());
        }
    }

    @Test public void host41ReservesInvisibleSlotsAndTheMoreButtonAndGuardsUnknownContent() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,44,8);
            Tab visible=host.tab("homepage_hot"),transparent=host.tab("homepage_follow"),invisible=host.tab("homepage_mall");
            transparent.item.setAlpha(0);invisible.item.setVisibility(View.INVISIBLE);
            host.more.setVisibility(View.VISIBLE);
            s.start(false);
            assertEquals(248,s.owner.getWidth()); // 16 + 44 + (3 * 36) + 8 + 32 + 40.
            Rect hit=bounds(s.owner,visible.item);
            assertFalse(Rect.intersects(hit,bounds(s.owner,s.menu)));
            assertFalse(Rect.intersects(hit,bounds(s.owner,host.more)));
            tapAt(s.root,host.more);ShadowLooper.idleMainLooper();layout(s.root);assertEquals(1,host.moreClicks);
            invisible.item.setVisibility(View.GONE);s.refresh();s.refresh();assertEquals(212,s.owner.getWidth());
            View unknown=new View(s.activity);unknown.setId(0x7f0affee);
            host.row.addView(unknown,new RelativeLayout.LayoutParams(54,44));
            s.refresh();
            assertTrue(s.geometry.topCapacityDiagnostic().contains("result=fallback"));
            assertTrue(s.owner.getWidth()>212);
            host.row.removeView(unknown);s.refresh();
            RelativeLayout.LayoutParams more=(RelativeLayout.LayoutParams)host.more.getLayoutParams();
            more.removeRule(RelativeLayout.ALIGN_PARENT_END);more.removeRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            host.more.setLayoutParams(more);s.refresh();s.refresh();
            assertTrue(s.geometry.topCapacityDiagnostic().contains("reason=host_more_rules"));
        }
    }

    @Test public void host41ShrinksWithTabsInReverseDrawingOrder() {
        for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL})try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,direction,12,12);
            for(int i=0;i<6;i++)host.tab("tab_"+i).params.width=36;
            // TopTabViewManager builds the positioning rules first, then reverses the drawing order.
            for(int i=s.tabs.size()-1;i>=0;i--)s.tabs.get(i).item.bringToFront();
            assertSame(s.tabs.get(5).item,host.row.getChildAt(1)); // The indicator is still first.
            s.start(false);
            assertEquals(312,s.owner.getWidth());
            try(NavigationItemVisibility first=new NavigationItemVisibility(s.tabs.get(1).item);
                NavigationItemVisibility second=new NavigationItemVisibility(s.tabs.get(3).item)) {
                first.hide();second.hide();s.refresh();
                assertEquals(240,s.owner.getWidth());
                for(int index:new int[]{0,2,4,5}) {
                    View tab=s.tabs.get(index).item;
                    assertContained(s.owner,tab);
                    assertFalse(Rect.intersects(bounds(s.owner,tab),bounds(s.owner,s.menu)));
                    assertFalse(Rect.intersects(bounds(s.owner,tab),bounds(s.owner,s.search)));
                    tapAt(s.root,tab);
                }
                tapAt(s.root,s.menu.getChildAt(0));tapAt(s.root,s.search.getChildAt(0));ShadowLooper.idleMainLooper();
                assertEquals(1,s.menuClicks);assertEquals(1,s.searchClicks);
                for(int i=0;i<6;i++)assertEquals(i==1 || i==3?0:1,s.tabs.get(i).clicks);
            }
        }
    }

    @Test public void host41DoesNotShrinkIfTheTabRulesFork() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,12,12);
            Tab first=host.tab("first");
            host.tab("second");
            Tab third=host.tab("third");
            RelativeLayout.LayoutParams rules=(RelativeLayout.LayoutParams)third.params;
            rules.addRule(RelativeLayout.RIGHT_OF,first.item.getId());
            rules.addRule(RelativeLayout.END_OF,first.item.getId());
            third.item.setLayoutParams(rules);
            s.start(false);
            assertTrue(s.geometry.topCapacityDiagnostic().contains("reason=host_tab_chain"));
            assertEquals(328,s.owner.getWidth());
        }
    }

    @Test public void host41EmptyMountDoesNotKeepAnOtherwiseKnownTopBarWide() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,12,12);
            s.center.removeView(host.strip);
            s.start(false);
            assertTrue(s.geometry.topCapacityDiagnostic().contains("result=accepted"));
            assertEquals(88,s.owner.getWidth()); // The two edge targets and padding still fit.
            assertFalse(Rect.intersects(bounds(s.owner,s.menu),bounds(s.owner,s.search)));
            s.center.setBackgroundColor(0xff120000);s.refresh();
            assertTrue("A painted full-width mount must keep the fallback width",
                    s.geometry.topCapacityDiagnostic().contains("result=fallback"));
            assertTrue(s.owner.getWidth()>88);
        }
    }

    @Test public void host41CornerCanGrowAfterAnEmptyCenterHasShrunkTheOwner() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,12,12);
            s.center.removeView(host.strip);s.menu.setVisibility(View.GONE);s.start(false);
            assertEquals(56,s.owner.getWidth());
            View target=s.search.getChildAt(0);
            ViewGroup.LayoutParams p=target.getLayoutParams();p.width=64;target.setLayoutParams(p);
            s.refresh();s.refresh();
            assertEquals(80,s.owner.getWidth());assertContained(s.owner,target);
            assertFalse(s.geometry.refresh(s.labels()));
        }
    }

    @Test public void host41CapacityBeyondTheViewportUsesFallback() {
        try(Scene s=new Scene(-1,44,true)) {
            HostStrip host=new HostStrip(s,View.LAYOUT_DIRECTION_LTR,12,12);
            for(int i=0;i<6;i++)host.tab("tab_"+i);
            s.start(false);
            layout(s.root,220);s.geometry.refresh(s.labels());layout(s.root,220);
            assertEquals(220,s.owner.getWidth());
            assertTrue(s.geometry.topCapacityDiagnostic().contains("reason=capacity_exceeds_viewport"));
        }
    }

    @Test public void t1CanonicalItemHidingShrinksTheOuterOwnerToItsRemainingNativeSlot() {
        try(Scene s=new Scene(-1,44,false)) {
            Tab kept=s.tab("homepage_hot",64,20,20,11);
            Tab follow=s.tab("homepage_follow",64,20,20,11);
            Tab mall=s.tab("homepage_mall",64,20,20,11);
            kept.item.r.selected=true;s.start(true);
            assertTrue("A fully understood native slot tree should expose its accepted capacity",
                    s.geometry.topCapacityDiagnostic().contains("result=accepted"));
            int originalWidth=s.owner.getWidth();
            assertTrue(originalWidth>=3*64+16);assertEquals(3,s.labels().size());
            s.hide("[\"nav_top:homepage_follow\",\"nav_top:homepage_mall\"]");
            assertEquals(View.GONE,follow.item.getVisibility());assertEquals(View.GONE,mall.item.getVisibility());
            assertFalse(follow.item.isClickable());assertFalse(mall.item.isEnabled());
            assertEquals(1,s.labels().size());assertTrue(kept.item.r.selected);assertFalse(follow.item.r.selected);
            assertSame(kept.item,s.row.getChildAt(0));assertEquals("A",kept.title.getText().toString());
            assertContained(s.owner,kept.item);assertContained(s.owner,kept.title);assertIconFits(kept.title);
            tapAt(s.root,kept.item);assertEquals(2,kept.events);ShadowLooper.idleMainLooper();layout(s.root);
            assertEquals(1,kept.clicks);assertEquals(0,follow.clicks);assertEquals(0,mall.clicks);
            assertEquals("T1: one 64px click slot plus two 8px insets",80,s.owner.getWidth());
            assertTrue(s.owner.getWidth()<originalWidth);assertEquals(64,s.owner.getHeight());
            assertFalse(s.geometry.refresh(s.labels()));
            s.hide("[\"nav_top:homepage_hot\",\"nav_top:homepage_follow\",\"nav_top:homepage_mall\"]");
            assertTrue(s.labels().isEmpty());
            for(Tab tab:s.tabs){assertEquals(View.GONE,tab.item.getVisibility());assertFalse(tab.item.isClickable());assertFalse(tab.item.isEnabled());}
            assertEquals(View.VISIBLE,s.owner.getVisibility());
            assertTrue(s.owner.getWidth()>=s.owner.getPaddingLeft()+s.owner.getPaddingRight());
            assertTrue("All known slots hidden must not re-expand the bar",s.owner.getWidth()<=80);
            assertTrue(s.owner.getHeight()>=64);
            s.hide("[]");assertTrue(s.owner.getWidth()>=3*64+16);assertTrue(follow.item.isClickable());
            s.restore();assertSame(s.ownerParams,s.owner.getLayoutParams());assertEquals(-1,s.ownerParams.width);
            assertEquals(-1,s.ownerParams.height);assertEquals(44,s.centerParams.height);assertEquals(44,s.containerParams.height);
            assertEquals(View.VISIBLE,follow.item.getVisibility());assertSame(follow.params,follow.item.getLayoutParams());
        }
    }

    @Test public void t2ContentDominatedWidthCanShrinkWithoutClippingOrLosingHostWrites() {
        try(Scene s=new Scene(120,44,false)) {
            Tab large=s.tab("homepage_hot",240,240,20,0),small=s.tab("homepage_follow",64,20,20,0);
            small.item.setVisibility(View.GONE);s.start(false);int expanded=s.owner.getWidth();
            assertTrue(expanded>=240+16);assertContained(s.owner,large.title);assertIconFits(large.title);
            large.item.setVisibility(View.GONE);small.item.setVisibility(View.VISIBLE);s.refresh();
            assertTrue("T2: removed wide content must release its allocation",s.owner.getWidth()<expanded);
            assertTrue(s.owner.getWidth()>=64+16);assertTrue(s.owner.getWidth()<=120);
            assertContained(s.owner,small.item);assertContained(s.owner,small.title);assertIconFits(small.title);
            assertFalse(s.geometry.refresh(s.labels()));
            s.restore();assertSame(s.ownerParams,s.owner.getLayoutParams());assertEquals(120,s.ownerParams.width);
            assertEquals(-1,s.ownerParams.height);assertEquals(44,s.containerParams.height);
            try(NavigationGlassLayout again=new NavigationGlassLayout(s.owner,true)) {
                again.refresh(Collections.singletonList(small.title));layout(s.root);
                s.ownerParams.width=180;s.owner.setMinimumHeight(91);s.centerParams.height=59;
            }
            assertEquals(180,s.ownerParams.width);assertEquals(91,s.owner.getMinimumHeight());assertEquals(59,s.centerParams.height);
        }
    }

    @Test public void goneUnknownDescendantDoesNotHoldTheTopCapacityAtItsOldWidth() {
        try(Scene s=new Scene(-1,44,false)) {
            Tab kept=s.tab("homepage_hot",64,20,20,11);
            Tab follow=s.tab("homepage_follow",64,20,20,11);
            Tab mall=s.tab("homepage_mall",64,20,20,11);
            TextView gone=new TextView(s.activity);gone.setVisibility(View.GONE);
            s.row.addView(gone,new LinearLayout.LayoutParams(120,44));
            s.start(true);int original=s.owner.getWidth();
            assertTrue(original>=3*64+16);
            assertTrue(s.geometry.topCapacityDiagnostic().contains("result=accepted"));
            s.hide("[\"nav_top:homepage_follow\",\"nav_top:homepage_mall\"]");
            assertEquals(View.GONE,follow.item.getVisibility());assertEquals(View.GONE,mall.item.getVisibility());
            assertEquals(80,s.owner.getWidth());assertTrue(s.owner.getWidth()<original);
            assertContained(s.owner,kept.item);assertTrue(s.geometry.topCapacityDiagnostic().contains("result=accepted"));
        }
    }

    @Test public void t3GoneTallFrameStopsSettingTheCapsuleHeightAndRestoresConditionally() {
        try(Scene s=new Scene(-1,96,false)) {
            s.tab("homepage_hot",64,20,20,0);
            FrameLayout shortFrame=new FrameLayout(s.activity);shortFrame.setId(0x7f0abc82);
            RelativeLayout.LayoutParams shortParams=new RelativeLayout.LayoutParams(32,44);
            shortParams.addRule(RelativeLayout.ALIGN_PARENT_END);shortParams.addRule(RelativeLayout.CENTER_VERTICAL);
            s.owner.addView(shortFrame,shortParams);
            Tab kept=s.tab(shortFrame,"homepage_follow",32,20,20,0);s.start(false);
            assertEquals(112,s.owner.getHeight());assertEquals(112,s.container.getHeight());
            s.center.setVisibility(View.GONE);s.refresh();
            assertFalse(s.tabs.get(0).title.isShown());assertEquals(View.VISIBLE,s.tabs.get(0).title.getVisibility());
            assertEquals(1,s.labels().size());assertContained(s.owner,kept.title);assertIconFits(kept.title);
            assertEquals("T3: the GONE 96px frame must not hold the owner at 112px",64,s.owner.getHeight());
            assertEquals(64,s.container.getHeight());assertFalse(s.geometry.refresh(s.labels()));
            TextView stale=s.tabs.get(0).title;
            ColorDrawable tallIcon=new ColorDrawable(0xff777777);tallIcon.setBounds(0,0,20,100);
            stale.setCompoundDrawables(null,tallIcon,null,null);
            List<TextView> staleLabels=java.util.Arrays.asList(stale,kept.title);
            s.geometry.refresh(staleLabels);layout(s.root);
            assertEquals(View.VISIBLE,stale.getVisibility());assertFalse(stale.isShown());
            assertEquals("T3: a stale label below a GONE ancestor must not expand height to 116px",64,s.owner.getHeight());
            assertEquals(64,s.container.getHeight());assertIconFits(kept.title);
            assertFalse(s.geometry.refresh(staleLabels));
            s.centerParams.height=100;s.restore();
            assertEquals(100,s.centerParams.height);assertEquals(44,shortParams.height);assertEquals(44,s.containerParams.height);
            assertSame(s.ownerParams,s.owner.getLayoutParams());assertEquals(-1,s.ownerParams.height);
        }
    }

    @Test public void t4ContentHeightCanShrinkWhileTheVerifiedParentKeepsTheRealTouchArea() {
        try(Scene s=new Scene(-1,44,false)) {
            Tab tall=s.tab("homepage_hot",48,20,100,0),kept=s.tab("homepage_follow",48,20,20,0);
            final int[] clicks={0},events={0};
            tall.title.setOnClickListener(ignored->clicks[0]++);
            tall.title.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN || event.getActionMasked()==MotionEvent.ACTION_UP)events[0]++;return false;});
            Drawable sameIcon=tall.title.getCompoundDrawables()[1];assertNotNull(sameIcon);
            assertEquals(100,sameIcon.getBounds().height());assertEquals(0,tall.title.getCompoundDrawablePadding());
            assertEquals("",tall.title.getText().toString());assertEquals(0f,tall.title.getTextSize(),0f);
            assertFalse(tall.title.getIncludeFontPadding());
            int nativeTextHeight;
            tall.title.setCompoundDrawables(null,null,null,null);
            try {
                tall.title.measure(View.MeasureSpec.makeMeasureSpec(48,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                nativeTextHeight=tall.title.getMeasuredHeight();
            } finally {tall.title.setCompoundDrawables(null,sameIcon,null,null);}
            assertSame(sameIcon,tall.title.getCompoundDrawables()[1]);
            assertTrue("Native text-only height must be nonnegative: "+nativeTextHeight,nativeTextHeight>=0);
            assertTrue("The retained 20px icon and 16px insets must still fit in 64px: h="+nativeTextHeight,nativeTextHeight<=28);
            int expectedHeight=100+nativeTextHeight+16;
            String fixture="synthetic fixture T4: text_only_measuredHeight="+nativeTextHeight
                    +";icon=100;gap=0;owner_top=8;owner_bottom=8;expectedHeight="+expectedHeight;
            System.out.println(fixture);
            s.start(false);
            assertEquals(fixture+";owner",expectedHeight,s.owner.getHeight());
            assertEquals(fixture+";verified_parent",expectedHeight,s.container.getHeight());
            assertContained(s.owner,tall.title);assertIconFits(tall.title);
            Rect area=bounds(s.root,tall.title);assertTrue(area.centerY()>44);
            tap(s.root,area.centerX(),area.centerY());assertEquals(2,events[0]);
            ShadowLooper.idleMainLooper();assertEquals(1,clicks[0]);layout(s.root);
            assertFalse(s.geometry.refresh(s.labels()));
            try(NavigationItemVisibility hidden=new NavigationItemVisibility(tall.item)) {
                hidden.hide();s.refresh();assertEquals(64,s.owner.getHeight());assertEquals(64,s.container.getHeight());
                assertContained(s.owner,kept.title);assertIconFits(kept.title);assertFalse(s.geometry.refresh(s.labels()));
            }
            s.restore();assertSame(s.containerParams,s.container.getLayoutParams());assertEquals(44,s.containerParams.height);
            assertEquals(-1,s.ownerParams.height);assertEquals(44,s.centerParams.height);
        }
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(activity),unknown=new FrameLayout(activity);unknown.setId(0x7f0ab82b);
            RelativeLayout owner=new RelativeLayout(activity);owner.setId(0x7f0a2866);
            FrameLayout.LayoutParams unknownParams=new FrameLayout.LayoutParams(-1,44);
            root.addView(unknown,unknownParams);unknown.addView(owner,new FrameLayout.LayoutParams(-1,-1));
            activity.setContentView(root);layout(root);
            try(NavigationGlassLayout geometry=new NavigationGlassLayout(owner,true)) {
                geometry.refresh(Collections.emptyList());layout(root);assertEquals(44,unknownParams.height);
            }
            assertEquals(44,unknownParams.height);
        } finally {activity.finish();ShadowLooper.idleMainLooper();}
    }

    @Test public void t5SideControlsScrollAndRtlSurviveHiddenItemShrinkAndUnknownCapacity() {
        int[] widths=new int[2];int index=0;
        for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL}) {
            try(Scene s=new Scene(-1,44,true)) {
                s.root.setLayoutDirection(direction);s.owner.setLayoutDirection(direction);
                Tab kept=s.tab("homepage_hot",64,20,20,11),follow=s.tab("homepage_follow",64,20,20,11);
                Tab mall=s.tab("homepage_mall",64,20,20,11);s.start(true);
                int[] centerRules=s.centerParams.getRules().clone();
                s.hide("[\"nav_top:homepage_follow\",\"nav_top:homepage_mall\"]");
                assertEquals(1,s.labels().size());assertEquals(View.GONE,follow.item.getVisibility());assertEquals(View.GONE,mall.item.getVisibility());
                assertEquals(View.VISIBLE,s.menu.getVisibility());assertEquals(View.VISIBLE,s.search.getVisibility());
                assertTrue(s.scroll.isFillViewport());assertTrue(s.scroll.getWidth()>=64);
                assertEquals(32,s.menu.getWidth());assertEquals(32,s.search.getWidth());
                assertContained(s.owner,s.menu);assertContained(s.owner,s.search);assertContained(s.center,kept.item);assertIconFits(kept.title);
                if(direction==View.LAYOUT_DIRECTION_LTR)assertTrue(s.menu.getLeft()<s.search.getLeft());
                else assertTrue(s.menu.getLeft()>s.search.getLeft());
                tapAt(s.root,s.menu);tapAt(s.root,s.search);tapAt(s.root,kept.item);
                assertEquals(2,kept.events);ShadowLooper.idleMainLooper();layout(s.root);
                assertEquals(1,s.menuClicks);assertEquals(1,s.searchClicks);assertEquals(1,kept.clicks);
                assertEquals(0,follow.clicks);assertEquals(0,mall.clicks);assertArrayEquals(centerRules,s.centerParams.getRules());
                widths[index++]=s.owner.getWidth();assertFalse(s.geometry.refresh(s.labels()));
                s.restore();assertSame(s.ownerParams,s.owner.getLayoutParams());assertEquals(-1,s.ownerParams.width);
                assertEquals(0,s.ownerParams.getMarginStart());assertEquals(0,s.ownerParams.getMarginEnd());
                assertEquals(44,s.centerParams.height);assertArrayEquals(centerRules,s.centerParams.getRules());
                assertTrue(follow.item.isClickable());assertSame(follow.params,follow.item.getLayoutParams());
            }
        }
        try(Scene s=new Scene(-1,44,true)) {
            s.tab("homepage_hot",64,20,20,11);s.tab("homepage_follow",64,20,20,11);s.tab("homepage_mall",64,20,20,11);
            FrameLayout unknown=new FrameLayout(s.activity);View nativeButton=new View(s.activity);final int[] clicks={0};
            unknown.setId(0x7f0affee);
            nativeButton.setOnClickListener(ignored->clicks[0]++);unknown.addView(nativeButton,new FrameLayout.LayoutParams(-1,-1));
            RelativeLayout.LayoutParams unknownParams=new RelativeLayout.LayoutParams(90,20);unknownParams.leftMargin=208;unknownParams.topMargin=14;
            s.owner.addView(unknown,unknownParams);s.start(true);
            int requiredRight=bounds(s.owner,unknown).right+s.owner.getPaddingRight();
            s.hide("[\"nav_top:homepage_follow\",\"nav_top:homepage_mall\"]");
            assertTrue("Unknown native content must retain its measured capacity",s.owner.getWidth()>=requiredRight);
            assertTrue("A conservative width fallback must name its first unsupported child",
                    s.geometry.topCapacityDiagnostic().contains("reason=unknown_owner_child"));
            assertTrue(s.geometry.topCapacityDiagnostic().contains("id=7f0affee"));
            assertSame(unknownParams,unknown.getLayoutParams());assertEquals(208,unknownParams.leftMargin);assertContained(s.owner,unknown);
            tapAt(s.root,nativeButton);ShadowLooper.idleMainLooper();assertEquals(1,clicks[0]);layout(s.root);
        }
        assertArrayEquals("T5: 32+64+32px native controls plus two 8px insets",new int[]{144,144},widths);
    }

    private static final class Tab {
        final NativeThemeTop item;final TextView title;final ViewGroup.LayoutParams params;
        int clicks,events;
        Tab(Activity activity,ViewGroup parent,String id,int width,int iconWidth,int iconHeight,int textSize) {
            title=new TextView(activity);title.setText(textSize==0?"":"A");title.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
            title.setGravity(Gravity.CENTER);title.setIncludeFontPadding(false);
            ColorDrawable icon=new ColorDrawable(0xff777777);icon.setBounds(0,0,iconWidth,iconHeight);
            title.setCompoundDrawables(null,icon,null,null);title.setCompoundDrawablePadding(textSize==0?0:3);
            item=new NativeThemeTop(activity,id,title);title.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
            item.setOnClickListener(ignored->clicks++);
            item.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN || event.getActionMasked()==MotionEvent.ACTION_UP)events++;return false;});
            params=parent instanceof LinearLayout?new LinearLayout.LayoutParams(width,-1):parent instanceof RelativeLayout
                    ?new RelativeLayout.LayoutParams(width,-1):new FrameLayout.LayoutParams(width,-1,Gravity.CENTER);
            parent.addView(item,params);
        }
    }

    /** 41.0.0 r/zm1 and r/zm3 hierarchy after the native reparent, with small deterministic widths. */
    private static final class HostStrip {
        final MainTabStripNew strip;
        final MainTabRelativeLayout row;
        final AntiBurnDuxImageView more;
        final RelativeLayout.LayoutParams scrollParams=new RelativeLayout.LayoutParams(-2,-1);
        final Scene scene;
        int previous,moreClicks;
        HostStrip(Scene scene,int direction,int startMargin,int endMargin) {
            this.scene=scene;
            scene.root.setLayoutDirection(direction);scene.owner.setLayoutDirection(direction);
            scene.center.removeAllViews();scene.center.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            scene.centerParams.removeRule(RelativeLayout.END_OF);scene.centerParams.removeRule(RelativeLayout.START_OF);
            scene.centerParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            corner(scene.menu,"homepage_left_sidebar",32,ignored->scene.menuClicks++);
            corner(scene.search,"homepage_right_search",40,ignored->scene.searchClicks++);
            strip=new MainTabStripNew(scene.activity);strip.setId(0x7f0a4f62);
            scene.center.addView(strip,new FrameLayout.LayoutParams(-1,-1));
            more=new AntiBurnDuxImageView(scene.activity);more.setId(0x7f0ad4ac);
            more.setOnClickListener(ignored->moreClicks++);more.setVisibility(View.GONE);
            RelativeLayout.LayoutParams moreParams=new RelativeLayout.LayoutParams(32,32);
            moreParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            moreParams.addRule(RelativeLayout.ALIGN_PARENT_END);moreParams.addRule(RelativeLayout.CENTER_VERTICAL);
            moreParams.setMarginEnd(40);strip.addView(more,moreParams);
            MainTabStripScrollView scroll=new MainTabStripScrollView(scene.activity);scroll.setId(0x7f0a5059);
            scrollParams.addRule(RelativeLayout.LEFT_OF,more.getId());
            scrollParams.addRule(RelativeLayout.START_OF,more.getId());
            scrollParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            scrollParams.setMarginStart(startMargin);scrollParams.setMarginEnd(endMargin);
            strip.addView(scroll,scrollParams);
            row=new MainTabRelativeLayout(scene.activity);row.setId(0x7f0a9ee0);
            scroll.addView(row,new FrameLayout.LayoutParams(-2,-1));
            FrameLayout indicator=new FrameLayout(scene.activity);indicator.setId(0x7f0ad54f);
            indicator.setTranslationX(22);indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            RelativeLayout.LayoutParams indicatorParams=new RelativeLayout.LayoutParams(21,-2);
            indicatorParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);row.addView(indicator,indicatorParams);
            for(int id:new int[]{0x7f0ad550,0x7f0ad551}) {
                View paint=new View(scene.activity);paint.setId(id);
                indicator.addView(paint,new FrameLayout.LayoutParams(8,2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
            }
        }
        private void corner(FrameLayout mount,String key,int width,View.OnClickListener listener) {
            mount.getLayoutParams().width=-2;mount.setClickable(false);
            TopCornerEntranceView target=new TopCornerEntranceView(scene.activity,key);
            target.setOnClickListener(listener);mount.addView(target,new FrameLayout.LayoutParams(width,-1));
        }
        Tab tab(String key) {
            Tab tab=scene.tab(row,key,-2,20,20,0);tab.item.setId(View.generateViewId());
            tab.item.setMinimumWidth(36);
            RelativeLayout.LayoutParams params=(RelativeLayout.LayoutParams)tab.params;
            if(previous!=0) {
                params.addRule(RelativeLayout.RIGHT_OF,previous);
                params.addRule(RelativeLayout.END_OF,previous);
            }
            previous=tab.item.getId();
            return tab;
        }
    }

    private static final class Scene implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final MainTitleBarContainer container=new MainTitleBarContainer(activity);
        final RelativeLayout owner=new RelativeLayout(activity);
        final FrameLayout center=new FrameLayout(activity);
        final LinearLayout row=new LinearLayout(activity);
        final FrameLayout.LayoutParams containerParams=new FrameLayout.LayoutParams(-1,44);
        final FrameLayout.LayoutParams ownerParams;
        final RelativeLayout.LayoutParams centerParams;
        final List<Tab> tabs=new ArrayList<>();
        final FrameLayout menu,search;final HorizontalScrollView scroll;
        int menuClicks,searchClicks;
        HomeUiCustomization custom;NavigationGlassLayout geometry;
        Scene(int width,int centerHeight,boolean sides) {
            container.setId(0x7f0ab82b);owner.setId(0x7f0a2866);center.setId(0x7f0abca6);
            root.addView(container,containerParams);ownerParams=new FrameLayout.LayoutParams(width,-1,Gravity.TOP|Gravity.START);
            ownerParams.setMarginStart(0);ownerParams.setMarginEnd(0);container.addView(owner,ownerParams);
            centerParams=new RelativeLayout.LayoutParams(-1,centerHeight);centerParams.addRule(RelativeLayout.CENTER_VERTICAL);
            if(sides) {
                menu=new FrameLayout(activity);menu.setId(0x7f0abc52);search=new FrameLayout(activity);search.setId(0x7f0abc82);
                RelativeLayout.LayoutParams first=new RelativeLayout.LayoutParams(32,44);first.addRule(RelativeLayout.ALIGN_PARENT_START);first.addRule(RelativeLayout.CENTER_VERTICAL);
                RelativeLayout.LayoutParams last=new RelativeLayout.LayoutParams(32,44);last.addRule(RelativeLayout.ALIGN_PARENT_END);last.addRule(RelativeLayout.CENTER_VERTICAL);
                owner.addView(menu,first);owner.addView(search,last);
                menu.setOnClickListener(ignored->menuClicks++);search.setOnClickListener(ignored->searchClicks++);
                centerParams.addRule(RelativeLayout.END_OF,menu.getId());centerParams.addRule(RelativeLayout.START_OF,search.getId());
                scroll=new HorizontalScrollView(activity);scroll.setFillViewport(true);scroll.setHorizontalScrollBarEnabled(false);
                center.addView(scroll,new FrameLayout.LayoutParams(-1,-1));scroll.addView(row,new FrameLayout.LayoutParams(-2,-1));
            } else {
                menu=null;search=null;scroll=null;center.addView(row,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));
            }
            row.setOrientation(LinearLayout.HORIZONTAL);owner.addView(center,centerParams);
        }
        Tab tab(String id,int width,int iconWidth,int iconHeight,int textSize){return tab(row,id,width,iconWidth,iconHeight,textSize);}
        Tab tab(ViewGroup parent,String id,int width,int iconWidth,int iconHeight,int textSize) {
            Tab tab=new Tab(activity,parent,id,width,iconWidth,iconHeight,textSize);tabs.add(tab);return tab;
        }
        void start(boolean customize) {
            activity.setContentView(root);layout(root);
            if(customize)custom=new HomeUiCustomization(root,options("[]"),null);
            geometry=new NavigationGlassLayout(owner,true);refresh();
        }
        Collection<TextView> labels() {
            if(custom!=null)return custom.labels(owner);
            List<TextView> result=new ArrayList<>();for(Tab tab:tabs)if(tab.title.isShown())result.add(tab.title);return result;
        }
        void refresh(){geometry.refresh(labels());layout(root);}
        void hide(String hidden){custom.setOptions(options(hidden));refresh();}
        void restore(){if(custom!=null){custom.close();custom=null;}if(geometry!=null){geometry.close();geometry=null;}layout(root);}
        @Override public void close(){try{restore();}finally{activity.finish();ShadowLooper.idleMainLooper();}}
    }
    private static LocalOptions options(String hidden){return new LocalOptions(Map.of(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden));}
    private static void layout(View root) {
        layout(root,360);
    }
    private static void layout(View root,int width) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,800);
    }
    private static Rect bounds(ViewGroup ancestor,View child) {
        Rect result=new Rect(0,0,child.getWidth(),child.getHeight());ancestor.offsetDescendantRectToMyCoords(child,result);return result;
    }
    private static void assertContained(ViewGroup ancestor,View child) {
        Rect area=bounds(ancestor,child);assertTrue(area.width()>0 && area.height()>0);
        assertTrue(area.left>=0 && area.top>=0 && area.right<=ancestor.getWidth() && area.bottom<=ancestor.getHeight());
    }
    private static void assertIconFits(TextView title) {
        Drawable icon=title.getCompoundDrawables()[1];assertNotNull(icon);
        assertTrue(icon.getBounds().width()<=title.getWidth()-title.getPaddingLeft()-title.getPaddingRight());
        int textHeight=title.getLayout()==null?0:title.getLayout().getHeight();
        int gap=title.getText().length()==0?0:title.getCompoundDrawablePadding();
        assertTrue(icon.getBounds().height()+textHeight+gap+title.getPaddingTop()+title.getPaddingBottom()<=title.getHeight());
    }
    private static void tapAt(ViewGroup root,View target){Rect area=bounds(root,target);tap(root,area.centerX(),area.centerY());}
    private static void tap(View root,int x,int y) {
        long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0);
        try{root.dispatchTouchEvent(down);root.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}
    }
}
