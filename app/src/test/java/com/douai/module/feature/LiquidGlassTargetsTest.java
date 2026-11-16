package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildFrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Map;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class LiquidGlassTargetsTest {
    @Test public void navigationOwnsItsChildrenAndFeedControlsNeverReceiveGlass() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);
            MainTitleBar navigation=new MainTitleBar(a);
            TextView title=new TextView(a);title.setText("推荐");navigation.addView(title);
            root.addView(navigation,new FrameLayout.LayoutParams(320,56));
            for(int id:new int[]{0x7f0a113b,0x7f0a30de,0x7f0a2572,0x7f0a241d,
                    0x7f0a9743,0x7f0a8f46,0x7f0a3fb2,0x7f0a1f3c}) {
                FrameLayout feedControl=new FrameLayout(a);feedControl.setId(id);
                TextView count=new TextView(a);count.setText("4062");feedControl.addView(count);
                root.addView(feedControl,new FrameLayout.LayoutParams(61,90));
            }
            a.setContentView(root);layout(root);
            Map<View,Boolean> targets=LiquidGlassTargets.find(root);
            assertEquals("Only the navigation container may receive glass",1,targets.size());
            assertEquals(Boolean.TRUE,targets.get(navigation));
            assertFalse(targets.containsKey(title));assertFalse(targets.containsKey(root));
        } finally {a.finish();}
    }

    @Test public void ordinaryTextButtonsInputsImagesAndCardsStayOutsideTheGlassScope() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a),card=new FrameLayout(a);
            card.setBackgroundColor(Color.DKGRAY);card.addView(new Button(a));
            TextView text=new TextView(a);text.setText("视频描述");
            ImageView image=new ImageView(a);image.setClickable(true);
            for(View control:new View[]{card,text,new Button(a),new EditText(a),image})
                root.addView(control,new FrameLayout.LayoutParams(160,64));
            a.setContentView(root);layout(root);
            assertTrue("Non-navigation controls retain their host material",LiquidGlassTargets.find(root).isEmpty());
        } finally {a.finish();}
    }

    @Test public void hiddenOrTooDeepMediaCannotMakeItsNavigationAncestorAMotionTarget() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            root.addView(navigation,new FrameLayout.LayoutParams(320,56));
            SurfaceView hidden=new SurfaceView(a);hidden.setVisibility(View.GONE);navigation.addView(hidden);
            a.setContentView(root);layout(root);assertTrue(LiquidGlassTargets.find(root).isEmpty());
            navigation.removeAllViews();FrameLayout leaf=navigation;
            for(int i=0;i<42;i++){FrameLayout next=new FrameLayout(a);leaf.addView(next);leaf=next;}
            leaf.addView(new SurfaceView(a));
            LiquidGlassTargets.Selection selection=LiquidGlassTargets.select(root);
            assertTrue(selection.treeLimited);assertTrue(selection.targets.isEmpty());
        } finally {a.finish();}
    }

    @Test public void expandedNavigationStillRespectsPaintedBoundsAndTheActualViewport() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            root.addView(navigation,new FrameLayout.LayoutParams(320,56));a.setContentView(root);layout(root);
            assertTrue(LiquidGlassTargets.find(root).containsKey(navigation));
            navigation.setScaleY(5);
            assertTrue("A scaled navigation cover must be rejected",LiquidGlassTargets.find(root).isEmpty());
        } finally {a.finish();}
    }

    @Test public void anIncompleteTreeNeverThemesEvenAnEarlierNavigation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            root.addView(navigation,new FrameLayout.LayoutParams(320,56));
            for(int i=0;i<LiquidGlassTargets.MAX_NODES;i++)root.addView(new View(a));
            a.setContentView(root);layout(root);
            LiquidGlassTargets.Selection selection=LiquidGlassTargets.select(root);
            assertTrue(selection.treeLimited);assertTrue(selection.targets.isEmpty());
        } finally {a.finish();}
    }

    @Test public void onlyNavigationWithoutEditableOrSelectableContentCanMove() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            MainTitleBar navigation=new MainTitleBar(a);
            assertTrue(LiquidGlassTargets.movable(navigation));
            navigation.addView(new EditText(a));assertFalse(LiquidGlassTargets.movable(navigation));
            navigation.removeAllViews();TextView selection=new TextView(a);
            selection.setText("选择文本");selection.setTextIsSelectable(true);navigation.addView(selection);
            assertFalse(LiquidGlassTargets.movable(navigation));
            assertFalse("Ordinary controls never receive gravity",LiquidGlassTargets.movable(new Button(a)));
        } finally {a.finish();}
    }

    @Test public void motionIncludesNativeControlsAndVerifiedFeedGroupsWithoutExpandingGlass() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar nav=new MainTitleBar(a);
            TextView navText=text(a,"推荐");nav.addView(navText,new FrameLayout.LayoutParams(90,32));
            place(root,nav,10,10,320,56);
            FrameLayout bottom=(FrameLayout)Class.forName("X.1JgG").getConstructor(android.content.Context.class).newInstance(a);
            place(root,bottom,10,690,320,72);
            LinearLayoutCompat carrier=new LinearLayoutCompat(a);carrier.setId(0x7f0a15bb);carrier.setOrientation(LinearLayoutCompat.VERTICAL);
            TextView carrierText=text(a,"底栏内容");carrier.addView(carrierText,new LinearLayoutCompat.LayoutParams(200,30));place(root,carrier,10,570,290,70);
            FrameLayout metadata=new MeasureOnceCheckDrawChildFrameLayout(a);metadata.setId(0x7f0a3ffc);
            TextView description=text(a,"视频描述");metadata.addView(description,new FrameLayout.LayoutParams(-1,40));place(root,metadata,10,440,270,90);
            RelativeLayout right=new RelativeLayout(a);right.setId(0x7f0a4000);
            ImageView action=new ImageView(a);action.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            right.addView(action,new RelativeLayout.LayoutParams(40,40));place(root,right,310,250,44,300);
            Button button=new Button(a);button.setText("操作");place(root,button,10,170,110,48);
            TextView label=text(a,"标签");place(root,label,140,170,80,40);
            ImageView image=new ImageView(a);image.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.RED));place(root,image,250,170,40,40);
            ProgressBar progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(false);place(root,progress,10,260,200,18);
            a.setContentView(root);layout(root);
            List<View> targets=LiquidGlassTargets.motionTargets(root);
            for(View expected:new View[]{nav,bottom,carrier,metadata,right,button,label,image,progress})assertTrue(expected.getClass().getName(),targets.contains(expected));
            for(View nested:new View[]{root,navText,carrierText,description,action})assertFalse("A group and its child must not move twice",targets.contains(nested));
            Map<View,Boolean> glass=LiquidGlassTargets.find(root);assertEquals(2,glass.size());assertTrue(glass.containsKey(nav));assertTrue(glass.containsKey(bottom));
            assertFalse(glass.containsKey(button));assertFalse(glass.containsKey(metadata));assertEquals(0,button.getTranslationX(),0);
        } finally {a.finish();}
    }

    @Test public void motionProtectsMediaOpaqueInputsSubtitlesAndHostAnimationsWhileKeepingSafeSiblings() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);Button safe=new Button(a);place(root,safe,10,10,100,40);
            FrameLayout mediaGroup=new MeasureOnceCheckDrawChildFrameLayout(a);mediaGroup.setId(0x7f0a3ffc);
            SurfaceView media=new SurfaceView(a);media.setVisibility(View.GONE);mediaGroup.addView(media,new FrameLayout.LayoutParams(1,1));place(root,mediaGroup,10,80,200,60);
            FrameLayout webGroup=new FrameLayout(a);webGroup.setClickable(true);WebView web=new WebView(a);
            webGroup.addView(web,new FrameLayout.LayoutParams(60,40));place(root,webGroup,10,160,100,60);
            FrameLayout form=new FrameLayout(a);form.setClickable(true);EditText input=new EditText(a);
            form.addView(input,new FrameLayout.LayoutParams(80,40));place(root,form,10,240,100,50);
            TextView selectable=text(a,"可选择");selectable.setTextIsSelectable(true);place(root,selectable,140,240,100,40);
            TextView subtitle=text(a,"原生字幕");int subtitleId=a.getResources().getIdentifier("action_bar_subtitle","id","android");
            assertNotEquals("Framework fixture must carry a real subtitle resource name",0,subtitleId);subtitle.setId(subtitleId);place(root,subtitle,10,320,200,40);
            ImageView animated=new ImageView(a);animated.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.RED));
            AlphaAnimation animation=new AlphaAnimation(1,.5f);animation.setDuration(10000);animated.startAnimation(animation);place(root,animated,10,400,40,40);
            Button transientButton=new Button(a);transientButton.setHasTransientState(true);place(root,transientButton,100,400,100,40);
            a.setContentView(root);layout(root);List<View> targets=LiquidGlassTargets.motionTargets(root);
            assertTrue("One transient descendant must not freeze unrelated siblings",targets.contains(safe));
            for(View protectedView:new View[]{root,mediaGroup,media,webGroup,web,form,input,selectable,subtitle,animated,transientButton})assertFalse(protectedView.getClass().getName(),targets.contains(protectedView));
        } finally {a.finish();}
    }

    @Test public void motionSkipsInheritedInvisibilityPartialClippingAndLargeBackgrounds() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a),transparent=new FrameLayout(a);transparent.setAlpha(0);
            Button invisible=new Button(a);transparent.addView(invisible,new FrameLayout.LayoutParams(80,40));place(root,transparent,10,10,100,60);
            FrameLayout clipper=new FrameLayout(a);clipper.setClipChildren(true);Button clipped=new Button(a);
            FrameLayout.LayoutParams clippedPosition=new FrameLayout.LayoutParams(80,40);clippedPosition.topMargin=25;clipper.addView(clipped,clippedPosition);place(root,clipper,10,100,100,40);
            FrameLayout background=new FrameLayout(a);background.setClickable(true);place(root,background,0,0,360,800);
            Button visible=new Button(a);place(root,visible,150,180,120,40);
            Button outside=new Button(a);place(root,outside,-20,240,80,40);
            a.setContentView(root);layout(root);List<View> targets=LiquidGlassTargets.motionTargets(root);
            assertTrue(targets.contains(visible));
            for(View skipped:new View[]{transparent,invisible,clipper,clipped,background,outside})assertFalse(skipped.getClass().getName(),targets.contains(skipped));
        } finally {a.finish();}
    }

    @Test public void motionPrefersNonNestedGroupsAndLimitsTheNumberOfFallbackControls() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a),row=new FrameLayout(a);row.setClickable(true);
            TextView child=text(a,"组合内文字");row.addView(child,new FrameLayout.LayoutParams(90,30));place(root,row,10,10,140,44);
            for(int i=0;i<140;i++){TextView item=text(a,".");place(root,item,(i%24)*14,80+(i/24)*14,10,10);}
            a.setContentView(root);layout(root);List<View> targets=LiquidGlassTargets.motionTargets(root);
            assertTrue(targets.contains(row));assertFalse(targets.contains(child));assertTrue(targets.size()<=96);
            for(View target:targets)for(View ancestor=target.getParent() instanceof View?(View)target.getParent():null;ancestor!=null;ancestor=ancestor.getParent() instanceof View?(View)ancestor.getParent():null)assertFalse("Motion targets must not nest",targets.contains(ancestor));
        } finally {a.finish();}
    }

    @Test public void motionKeepsAnIconWithItsClickableOwnerWhenTheOwnerContainsAnAnimation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a),owner=new FrameLayout(a);owner.setClickable(true);
            ImageView icon=new ImageView(a);icon.setImageDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            owner.addView(icon,new FrameLayout.LayoutParams(32,32));
            ProgressBar spinner=new ProgressBar(a);spinner.setIndeterminate(true);owner.addView(spinner,new FrameLayout.LayoutParams(20,20));
            place(root,owner,10,10,100,48);Button sibling=new Button(a);place(root,sibling,150,10,100,48);
            a.setContentView(root);layout(root);List<View> targets=LiquidGlassTargets.motionTargets(root);
            assertTrue(targets.contains(sibling));assertFalse(targets.contains(owner));assertFalse(targets.contains(spinner));
            assertFalse("An icon must not leave its stationary clickable hitbox",targets.contains(icon));
        } finally {a.finish();}
    }

    @Test public void anIncompleteMotionInventoryFailsClosedBeforeSelectingEvenANavigation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);place(root,new MainTitleBar(a),0,0,320,56);
            for(int i=0;i<LiquidGlassTargets.MAX_NODES;i++)root.addView(new View(a));
            a.setContentView(root);layout(root);assertTrue(LiquidGlassTargets.motionTargets(root).isEmpty());
        } finally {a.finish();}
    }

    @Test @Config(qualifiers="w360dp-h800dp-mdpi")
    public void motionCanScanTheExactHostShapedDecorWithoutDisablingTheWindow() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            List<View> targets=LiquidGlassTargets.motionTargets(scene.activity.getWindow().getDecorView());
            assertTrue(targets.contains(scene.navigation));assertTrue(targets.contains(scene.carrier));
            assertTrue(targets.contains(scene.metadataOwner));assertTrue(targets.contains(scene.right));
        }
    }

    private static TextView text(Activity activity,String value){TextView view=new TextView(activity);view.setText(value);return view;}
    private static void place(FrameLayout parent,View child,int x,int y,int width,int height){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,height);p.leftMargin=x;p.topMargin=y;parent.addView(child,p);}

    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);
    }
}
