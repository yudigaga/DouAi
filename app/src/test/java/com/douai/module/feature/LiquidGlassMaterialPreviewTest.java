package com.douai.module.feature;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.Assert.*;

/** Synthetic native-view fixture; this is not a screenshot of the real host. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiquidGlassMaterialPreviewTest {
    @Test public void nativeNavigationPreviewLeavesEveryFeedActionPixelUnchanged() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        ArrayList<LiquidGlassController.Binding> bindings=new ArrayList<>();
        Bitmap before=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);
        Bitmap after=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);
        HomeUiCustomization customization=null;LiquidGlassBackdrop backdrop=null;
        try {
            FrameLayout root=new FrameLayout(a);
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF263B55,0xFF92735C}));
            MainTitleBar top=new MainTitleBar(a);
            FrameLayout bottom=(FrameLayout)Class.forName("X.1JgG").getConstructor(android.content.Context.class).newInstance(a);
            LinearLayout topRow=row(a),bottomRow=row(a);bottomRow.setId(0x7f0a7983);
            top.addView(topRow,new FrameLayout.LayoutParams(-1,-1));bottom.addView(bottomRow,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams topPosition=new FrameLayout.LayoutParams(-1,44,Gravity.TOP);topPosition.topMargin=26;root.addView(top,topPosition);
            FrameLayout.LayoutParams bottomPosition=new FrameLayout.LayoutParams(-1,48,Gravity.BOTTOM);bottomPosition.bottomMargin=18;root.addView(bottom,bottomPosition);
            String icons="{}";ArrayList<TextView> topLabels=new ArrayList<>(),bottomLabels=new ArrayList<>();
            String[] topTitles={"关注","精选","商城","推荐"};
            int[] topIcons={android.R.drawable.ic_menu_myplaces,android.R.drawable.star_big_on,android.R.drawable.ic_menu_search,android.R.drawable.ic_media_play};
            for(int i=0;i<topTitles.length;i++) {
                TextView title=label(a,topTitles[i],18);topLabels.add(title);
                HomeTopTabItemViewNew item=new HomeTopTabItemViewNew(a,"preview_"+i,title);
                title.setLayoutParams(new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                topRow.addView(item,new LinearLayout.LayoutParams(0,-1,1));
                icons=HomeUiCustomizationSettings.putIcon(icons,"nav_top:preview_"+i,icon(a,topIcons[i]));
            }
            String[] bottomTitles={"首页","朋友","发布","消息","我"};
            String[] bottomIds={"homepage_home","homepage_familiar","preview_publish","homepage_notification","homepage_profile"};
            int[] bottomIcons={android.R.drawable.ic_menu_view,android.R.drawable.ic_menu_myplaces,android.R.drawable.ic_input_add,android.R.drawable.ic_dialog_email,android.R.drawable.ic_menu_myplaces};
            for(int i=0;i<bottomTitles.length;i++) {
                BottomTab tab=new BottomTab(a,bottomIds[i],bottomTitles[i]);TextView title=tab.getTabTitle();bottomLabels.add(title);
                title.setTextColor(Color.WHITE);title.setTextSize(16);title.setGravity(Gravity.CENTER);
                title.setLayoutParams(new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                bottomRow.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
                icons=HomeUiCustomizationSettings.putIcon(icons,"nav_bottom:"+bottomIds[i],icon(a,bottomIcons[i]));
            }
            int[] ids={0x7f0a30de,0x7f0a2572,0x7f0a9743};
            int[] feedIcons={android.R.drawable.btn_star_big_on,android.R.drawable.ic_dialog_email,android.R.drawable.ic_menu_share};
            FrameLayout[] actions=new FrameLayout[3];
            for(int i=0;i<3;i++) {
                FrameLayout action=new FrameLayout(a);action.setId(ids[i]);actions[i]=action;
                ImageView image=new ImageView(a);image.setImageResource(feedIcons[i]);image.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                place(action,image,8,4,44,44);TextView count=label(a,new String[]{"4062","360","4402"}[i],12);
                place(action,count,0,52,61,24);place(root,action,287,350+i*100,61,90);
            }
            a.setContentView(root);layout(root);root.draw(new Canvas(before));
            Map<View,Boolean> targets=LiquidGlassTargets.find(root);assertTrue(targets.containsKey(top));assertTrue(targets.containsKey(bottom));
            for(FrameLayout action:actions)assertFalse("Feed actions are outside the glass scope",targets.containsKey(action));
            customization=new HomeUiCustomization(root,new LocalOptions(Map.of(HomeUiCustomizationSettings.ICONS,icons)),null);
            backdrop=new LiquidGlassBackdrop(root,null);
            LiquidGlassController.Binding topBinding=new LiquidGlassController.Binding(top,true,backdrop);
            LiquidGlassController.Binding bottomBinding=new LiquidGlassController.Binding(bottom,true,backdrop);
            bindings.add(topBinding);bindings.add(bottomBinding);
            topBinding.refreshLayout(customization.labels(top));bottomBinding.refreshLayout(customization.labels(bottom));
            layout(root);root.draw(new Canvas(after));
            assertTrue(top.getWidth()<360);assertTrue(bottom.getWidth()<360);
            assertTrue(top.getHeight()>=64);assertTrue(bottom.getHeight()>=72);
            verifyLabels(top,topLabels,14);verifyLabels(bottom,bottomLabels,11);
            assertEquals("Video centre remains unchanged",before.getPixel(170,280),after.getPixel(170,280));
            for(int y=350;y<640;y++)for(int x=287;x<348;x++)
                assertEquals("Feed icons and counts retain their original rendering",before.getPixel(x,y),after.getPixel(x,y));
                File preview=new File(System.getProperty("java.io.tmpdir"),"DouAi-glass-"+com.douai.module.BuildConfig.VERSION_NAME+"-native-preview.png");
            try(FileOutputStream output=new FileOutputStream(preview)){assertTrue(after.compress(Bitmap.CompressFormat.PNG,100,output));}
            System.out.println("Navigation preview: "+preview.getAbsolutePath());
        } finally {
            for(LiquidGlassController.Binding binding:bindings)binding.close();
            if(customization!=null)customization.close();if(backdrop!=null)backdrop.close();
            before.recycle();after.recycle();a.finish();
        }
    }
    private static void verifyLabels(FrameLayout owner,ArrayList<TextView> labels,int maximumSp) {
        for(TextView label:labels) {
            assertNotNull("The imported icon sits above its label",label.getCompoundDrawables()[1]);
            assertNull(label.getCompoundDrawables()[0]);assertTrue(label.getTextSize()<=maximumSp+.01f);
            assertTrue(label.getTextSize()>0);Rect bounds=new Rect(0,0,label.getWidth(),label.getHeight());
            owner.offsetDescendantRectToMyCoords(label,bounds);
            assertTrue(bounds.left>=0 && bounds.top>=0 && bounds.right<=owner.getWidth() && bounds.bottom<=owner.getHeight());
        }
    }
    private static byte[] icon(Activity a,int resource) {
        Bitmap image=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);
        try {
            Drawable drawable=a.getDrawable(resource).mutate();drawable.setTint(Color.WHITE);drawable.setBounds(0,0,24,24);drawable.draw(new Canvas(image));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS,100,bytes));return bytes.toByteArray();
        } finally {image.recycle();}
    }
    private static LinearLayout row(Activity a) {LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);return row;}
    private static TextView label(Activity a,String title,int sp) {
        TextView text=new TextView(a);text.setText(title);text.setTextSize(sp);text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);text.setShadowLayer(1,0,1,0x99000000);return text;
    }
    private static void place(FrameLayout parent,View child,int x,int y,int width,int height) {
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width,height);p.leftMargin=x;p.topMargin=y;parent.addView(child,p);
    }
    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,360,800);
    }
}
