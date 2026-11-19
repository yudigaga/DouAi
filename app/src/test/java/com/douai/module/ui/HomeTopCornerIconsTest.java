package com.douai.module.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import com.douai.module.feature.HomeUiCustomization;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.homepage.topcorner.ui.TopCornerEntranceView;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** In-process View ownership contracts; no LSPosed, device or business selected-state claim. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeTopCornerIconsTest {
    private static final String SEARCH="homepage_right_search";
    private static final String MENU="homepage_left_sidebar";
    private static final String MENU_JX="homepage_left_sidebar_jx";
    private static final String UNKNOWN="homepage_left_login";
    private Object metadataRegistry;
    private Map<String,Object> metadataBefore;

    @Before public void isolateNavigationMetadata() {
        metadataRegistry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        synchronized(metadataRegistry) {
            Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
            metadataBefore=new LinkedHashMap<>(observed);observed.clear();
        }
    }
    @After public void restoreNavigationMetadata() {
        try {
            for(android.app.Dialog dialog:ShadowDialog.getShownDialogs())if(dialog.isShowing())dialog.dismiss();
            ShadowLooper.idleMainLooper();
        } finally {
            if(metadataBefore!=null)synchronized(metadataRegistry) {
                Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
                observed.clear();observed.putAll(metadataBefore);
            }
        }
    }

    @Test public void verifiedCornerIdentitiesBecomeEditableTargetsWithTheirOwnBuiltins() throws Exception {
        try(Fixture fixture=new Fixture()) {
            fixture.corner(SEARCH,Color.RED);fixture.corner(MENU,Color.GREEN);
            fixture.corner(MENU_JX,Color.BLUE);fixture.corner(UNKNOWN,Color.YELLOW);
            fixture.start("{}");fixture.customization.refresh();
            Map<String,HomeUiCustomization.Target> targets=new LinkedHashMap<>();
            for(HomeUiCustomization.Target target:HomeUiCustomization.availableTargets("{}"))targets.put(target.key,target);
            for(String id:new String[]{SEARCH,MENU,MENU_JX}) {
                HomeUiCustomization.Target target=targets.get(key(id));
                assertNotNull("Verified entrance must be editable without saved overrides: "+id,target);
                assertTrue(target.navigation);
                assertTrue("Human semantic entrance title: "+id,id.equals(SEARCH)?target.title.contains("搜索"):
                        target.title.contains("菜单") || target.title.contains("侧边栏"));
            }
            assertFalse(targets.containsKey(key(UNKNOWN)));
            for(String id:new String[]{SEARCH,MENU,MENU_JX}) {
                BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition(key(id));
                assertNotNull("Verified entrance must have its own preset: "+id,entry);
                assertEquals(id.equals(SEARCH)?"top/09-search":"top/10-menu",entry.path);
            }
            assertNull(BuiltInNavigationIcons.entryForPosition(key(UNKNOWN)));
            assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:unknown_channel"));
        }
    }

    @Test public void cornerArtworkUsesEntranceIdentityAndRestoresLatestNativeImages() throws Exception {
        try(Fixture fixture=new Fixture()) {
            // Deliberately place menu before search: coordinates and identical image IDs cannot identify actions.
            Corner menu=fixture.corner(MENU,Color.GREEN),search=fixture.corner(SEARCH,Color.RED);
            Corner jx=fixture.corner(MENU_JX,Color.BLUE),unknown=fixture.corner(UNKNOWN,Color.YELLOW);
            ImageView foreign=new ImageView(fixture.activity);foreign.setId(0x7f0abc18);
            Drawable foreignNative=new ColorDrawable(Color.CYAN);foreign.setImageDrawable(foreignNative);
            fixture.bar.addView(foreign,new FrameLayout.LayoutParams(40,40));
            Corner invalid=fixture.corner(SEARCH,Color.MAGENTA);invalid.owner.e=foreign;
            Corner wrongId=fixture.corner(MENU,Color.GRAY);wrongId.image.setId(0x7f0abc19);
            Corner outside=fixture.corner(SEARCH,Color.BLACK);
            fixture.bar.removeView(outside.owner);fixture.root.addView(outside.owner);
            Attributes foreignAttributes=new Attributes(foreign);
            Corner[] all={menu,search,jx,unknown,invalid,wrongId,outside};
            for(Corner corner:all)corner.clickAndExpect(1);
            fixture.start(cornerConfiguration());

            assertPixels(search.image,"top/09-search.webp");
            assertPixels(menu.image,"top/10-menu.webp");assertPixels(jx.image,"top/10-menu.webp");
            for(Corner corner:new Corner[]{unknown,invalid,wrongId,outside})assertSame(corner.nativeImage,corner.image.getDrawable());
            assertSame(foreignNative,foreign.getDrawable());foreignAttributes.assertPreserved();
            for(Corner corner:all){corner.assertPreserved();corner.assertClicks(1);corner.clickAndExpect(2);}

            search.image.setPressed(true);search.image.setFocusableInTouchMode(true);assertTrue(search.image.requestFocus());
            menu.owner.setPressed(true);menu.owner.setFocusableInTouchMode(true);assertTrue(menu.owner.requestFocus());
            fixture.customization.refresh();fixture.customization.sync();
            assertPixels(search.image,"top/09-search.webp");assertPixels(menu.image,"top/10-menu.webp");
            assertPixels(jx.image,"top/10-menu.webp");

            Drawable latestSearch=new ColorDrawable(Color.CYAN),latestMenu=new ColorDrawable(Color.MAGENTA);
            Drawable latestJx=new ColorDrawable(Color.YELLOW);
            search.image.setImageDrawable(latestSearch);menu.image.setImageDrawable(latestMenu);jx.image.setImageDrawable(latestJx);
            fixture.customization.refresh();
            assertPixels(search.image,"top/09-search.webp");assertPixels(menu.image,"top/10-menu.webp");
            assertPixels(jx.image,"top/10-menu.webp");

            search.owner.d=UNKNOWN;fixture.customization.refresh();
            assertSame("Changed identity releases only the image previously owned",latestSearch,search.image.getDrawable());
            assertPixels(menu.image,"top/10-menu.webp");assertPixels(jx.image,"top/10-menu.webp");
            fixture.bar.removeView(menu.owner);fixture.customization.refresh();
            assertSame("Detached owner releases its latest host image",latestMenu,menu.image.getDrawable());
            assertPixels(jx.image,"top/10-menu.webp");
            fixture.configure("{}");
            assertSame(latestSearch,search.image.getDrawable());assertSame(latestMenu,menu.image.getDrawable());
            assertSame("Clearing overrides restores the latest, not initial native image",latestJx,jx.image.getDrawable());
            for(Corner corner:new Corner[]{unknown,invalid,wrongId,outside})assertSame(corner.nativeImage,corner.image.getDrawable());
            assertSame(foreignNative,foreign.getDrawable());foreignAttributes.assertPreserved();
            for(Corner corner:all){corner.assertPreserved();corner.assertClicks(2);corner.clickAndExpect(3);}
        }
    }

    @Test public void mutePlaceholderAndPressedStateDoNotInventBusinessSelection() throws Exception {
        try(Fixture fixture=new Fixture()) {
            FrameLayout muteParent=new FrameLayout(fixture.activity);muteParent.setId(0x7f0a7ea7);
            muteParent.setAlpha(.42f);muteParent.setVisibility(View.INVISIBLE);fixture.root.addView(muteParent);
            View placeholder=new View(fixture.activity);placeholder.setId(0x7f0a7ea4);
            Drawable placeholderBackground=new ColorDrawable(Color.RED);placeholder.setBackground(placeholderBackground);
            placeholder.setAlpha(.61f);placeholder.setVisibility(View.GONE);
            muteParent.addView(placeholder,new FrameLayout.LayoutParams(30,30));
            View sibling=new View(fixture.activity);sibling.setId(0x7f0a7ea5);
            sibling.setAlpha(.27f);sibling.setVisibility(View.GONE);muteParent.addView(sibling);
            Attributes parentAttributes=new Attributes(muteParent),placeholderAttributes=new Attributes(placeholder),siblingAttributes=new Attributes(sibling);
            String json=pair("{}","feed_mute","feed/06-mute");fixture.start(json);fixture.customization.refresh();
            assertSame(placeholder,muteParent.findViewById(0x7f0a7ea4));assertEquals(2,muteParent.getChildCount());
            assertSame(placeholderBackground,placeholder.getBackground());placeholderAttributes.assertPreserved();
            parentAttributes.assertPreserved();siblingAttributes.assertPreserved();

            muteParent.removeView(placeholder);
            ImageView image=new ImageView(fixture.activity);image.setId(0x7f0a7ea4);
            Drawable nativeImage=new ColorDrawable(Color.BLUE);image.setImageDrawable(nativeImage);
            image.setAlpha(.38f);image.setVisibility(View.INVISIBLE);
            muteParent.addView(image,new FrameLayout.LayoutParams(30,30));Attributes imageAttributes=new Attributes(image);
            fixture.customization.refresh();assertPixels(image,"feed/06-mute.webp");
            imageAttributes.assertPreserved();parentAttributes.assertPreserved();siblingAttributes.assertPreserved();
            // Only the host makes its hidden views visible; focus is tested after that transition.
            muteParent.setVisibility(View.VISIBLE);image.setVisibility(View.VISIBLE);
            fixture.layout();
            image.setPressed(true);image.setFocusableInTouchMode(true);assertTrue(image.requestFocus());
            fixture.customization.refresh();fixture.customization.sync();
            assertPixels(image,"feed/06-mute.webp");
            assertFalse(image.isSelected());assertFalse(image.isActivated());
            muteParent.setVisibility(View.INVISIBLE);image.setVisibility(View.INVISIBLE);fixture.customization.refresh();
            imageAttributes.assertPreserved();parentAttributes.assertPreserved();siblingAttributes.assertPreserved();
            Drawable latestNative=new ColorDrawable(Color.GREEN);image.setImageDrawable(latestNative);
            fixture.customization.refresh();assertPixels(image,"feed/06-mute.webp");fixture.configure("{}");
            assertSame(latestNative,image.getDrawable());assertSame(placeholderBackground,placeholder.getBackground());
            assertEquals(2,muteParent.getChildCount());assertSame(image,muteParent.findViewById(0x7f0a7ea4));
            imageAttributes.assertPreserved();parentAttributes.assertPreserved();siblingAttributes.assertPreserved();
        }
    }

    private static String key(String id){return "nav_top:"+id;}
    private static String cornerConfiguration() throws Exception {
        String json=pair("{}",key(SEARCH),"top/09-search");
        json=pair(json,key(MENU),"top/10-menu");return pair(json,key(MENU_JX),"top/10-menu");
    }
    private static String pair(String json,String key,String path) throws Exception {
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,fixtureBytes(path+".webp"));
        variants.put(IconState.DAY_SELECTED,fixtureBytes(path+"-active.webp"));
        return HomeUiCustomizationSettings.putIcons(json,key,variants);
    }
    private static byte[] fixtureBytes(String path) throws Exception {
        try(InputStream stream=HomeTopCornerIconsTest.class.getResourceAsStream("/META-INF/douai/supplied-icon-fixtures/"+path)) {
            assertNotNull("Independent supplied fixture: "+path,stream);return stream.readAllBytes();
        }
    }
    private static void assertPixels(ImageView image,String path) throws Exception {
        assertTrue("Expected bound BitmapDrawable for "+path,image.getDrawable() instanceof BitmapDrawable);
        String design=path.substring(0,path.length()-5);boolean selected=design.endsWith("-active");
        if(selected)design=design.substring(0,design.length()-7);
        byte[] bytes=BuiltInNavigationIcons.symbolForPath(design,selected);
        Bitmap expected=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(expected);
        try {
            assertTrue("Rendered vector symbol matches the selected builtin: "+path,expected.sameAs(((BitmapDrawable)image.getDrawable()).getBitmap()));
            int dp=design.startsWith("feed/")?24:design.startsWith("bottom/")?24:20;
            assertEquals("Built-in mark uses its role's optical size: "+path,
                    Math.round(dp*image.getResources().getDisplayMetrics().density),image.getDrawable().getIntrinsicWidth());
            assertEquals(ImageView.ScaleType.CENTER,image.getScaleType());
        }
        finally {expected.recycle();}
    }
    private static final class Attributes {
        final View view;final int visibility,width,height;final float alpha;final ViewGroup.LayoutParams layout;
        final int left,top,right,bottom;
        Attributes(View view) {
            this.view=view;visibility=view.getVisibility();alpha=view.getAlpha();layout=view.getLayoutParams();
            width=layout.width;height=layout.height;
            ViewGroup.MarginLayoutParams margins=layout instanceof ViewGroup.MarginLayoutParams?(ViewGroup.MarginLayoutParams)layout:null;
            left=margins==null?0:margins.leftMargin;top=margins==null?0:margins.topMargin;
            right=margins==null?0:margins.rightMargin;bottom=margins==null?0:margins.bottomMargin;
        }
        void assertPreserved() {
            assertEquals(visibility,view.getVisibility());assertEquals(alpha,view.getAlpha(),0f);assertSame(layout,view.getLayoutParams());
            assertEquals(width,layout.width);assertEquals(height,layout.height);
            if(layout instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins=(ViewGroup.MarginLayoutParams)layout;
                assertEquals(left,margins.leftMargin);assertEquals(top,margins.topMargin);
                assertEquals(right,margins.rightMargin);assertEquals(bottom,margins.bottomMargin);
            }
        }
    }
    private static final class Corner {
        final TopCornerEntranceView owner;final ImageView image;final View badge;final Drawable nativeImage;
        final Attributes ownerAttributes,imageAttributes,badgeAttributes;
        final AtomicInteger ownerClicks=new AtomicInteger(),imageClicks=new AtomicInteger();
        Corner(Activity activity,String id,int color,int position) {
            owner=new TopCornerEntranceView(activity,id);image=owner.getIconView();badge=owner.findViewById(0x7f0abc17);
            FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(48,64);layout.leftMargin=position*48;owner.setLayoutParams(layout);
            nativeImage=new ColorDrawable(color);image.setImageDrawable(nativeImage);
            owner.setAlpha(.73f);image.setAlpha(.58f);badge.setAlpha(.24f);badge.setVisibility(View.INVISIBLE);
            owner.setOnClickListener(ignored->ownerClicks.incrementAndGet());image.setOnClickListener(ignored->imageClicks.incrementAndGet());
            ownerAttributes=new Attributes(owner);imageAttributes=new Attributes(image);badgeAttributes=new Attributes(badge);
        }
        void assertPreserved() {
            ownerAttributes.assertPreserved();imageAttributes.assertPreserved();badgeAttributes.assertPreserved();
            assertSame(owner,image.getParent());assertSame(owner,badge.getParent());
        }
        void assertClicks(int count){assertEquals(count,ownerClicks.get());assertEquals(count,imageClicks.get());}
        void clickAndExpect(int count){assertTrue(owner.performClick());assertTrue(image.performClick());assertClicks(count);}
    }
    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> activityController=Robolectric.buildActivity(Activity.class).setup();
        final Activity activity=activityController.get();
        final SharedPreferences preferences=activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        final HostSettingsStore store;
        final FrameLayout root=new FrameLayout(activity);
        final MainTitleBar bar=new MainTitleBar(activity);
        HomeUiCustomization customization;
        Fixture() {
            preferences.edit().clear().commit();store=new HostSettingsStore(activity);
            root.addView(bar,new FrameLayout.LayoutParams(360,100));activity.setContentView(root);
        }
        Corner corner(String id,int color) {
            Corner corner=new Corner(activity,id,color,bar.getChildCount());bar.addView(corner.owner);
            return corner;
        }
        void start(String json) {
            store.putString(HomeUiCustomizationSettings.ICONS,json);
            layout();
            customization=new HomeUiCustomization(activity.getWindow().getDecorView(),LocalOptions.readFrom(preferences),ignored->{});
        }
        void layout() {
            View decor=activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
            decor.layout(0,0,360,800);
        }
        void configure(String json) {
            store.putString(HomeUiCustomizationSettings.ICONS,json);customization.setOptions(LocalOptions.readFrom(preferences));customization.refresh();
        }
        @Override public void close() {
            try {if(customization!=null)customization.close();}
            finally {activityController.pause().stop().destroy();ShadowLooper.idleMainLooper();}
        }
    }
}
