package com.douai.module.feature;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiIconFixtures;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.feed.widget.DiggAnimationLayout;
import com.ss.android.ugc.aweme.feed.widget.DiggAnimationView;
import com.ss.android.ugc.aweme.feed.widget.DiggAnimationViewV2;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab.OwnerSelectedTab;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew.NativeThemeTop;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Map;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.zip.ZipFile;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.douai.module.scan.HostApkTestProfile;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeUiCustomizationTest {
    private Object metadataRegistry;
    private Map<String,Object> metadataBefore;
    @Before public void isolateProcessNavigationMetadata() {
        metadataRegistry=org.robolectric.util.ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        Map<String,Object> observed=org.robolectric.util.ReflectionHelpers.getField(metadataRegistry,"observed");
        synchronized(metadataRegistry){metadataBefore=new LinkedHashMap<>(observed);observed.clear();}
    }
    @After public void restoreProcessNavigationMetadata() {
        if(metadataRegistry==null || metadataBefore==null)return;
        Map<String,Object> observed=org.robolectric.util.ReflectionHelpers.getField(metadataRegistry,"observed");
        synchronized(metadataRegistry){observed.clear();observed.putAll(metadataBefore);}
    }
    @Test public void original41FactoryAndGetterRetainTheRawBottomTypeContractUsedByTheFixtures() throws Exception {
        String configured=System.getenv("DOUAI_TEST_APK");org.junit.Assume.assumeTrue(configured!=null && !configured.isBlank());
        File apk=new File(configured);org.junit.Assume.assumeTrue(HostApkTestProfile.is410(apk));
        String factory="Lcom/ss/android/ugc/aweme/homepage/tab/bottom/ui/tabbar/item/inflate/HomeBottomTabInflateFactory;";
        String bottom="Lcom/ss/android/ugc/aweme/homepage/tab/ui/bottom/BottomTab;",base="LX/1Jh4;",mapping="LX/1Jlu;",idsType="LX/1Jlt;";
        java.util.Set<String> wanted=new HashSet<>(List.of(factory,bottom,base,mapping,idsType));Map<String,ClassDef> types=new HashMap<>();
        try(ZipFile zip=new ZipFile(apk)) {
            for(var entry:HostApkTestProfile.dexEntries(zip)) {
                try(var input=new BufferedInputStream(zip.getInputStream(entry))) {
                    for(ClassDef type:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),input).getClasses())
                        if(wanted.remove(type.getType()))types.put(type.getType(),com.android.tools.smali.dexlib2.immutable.ImmutableClassDef.of(type));
                }
                if(wanted.isEmpty())break;
            }
        }
        assertTrue(wanted.toString(),wanted.isEmpty());assertEquals(base,types.get(bottom).getSuperclass());
        assertEquals("Landroid/widget/FrameLayout;",types.get(base).getSuperclass());
        List<String> getter=hostTokens(hostMethod(types.get(base),"getTabType"));assertTrue(getter.contains(base+"->b"));
        for(var method:types.get(bottom).getMethods())assertNotEquals("getTabType",method.getName());
        int rawRegister=-1,constructors=0;boolean awaitingRaw=false;
        for(var instruction:hostMethod(types.get(factory),"LIZ").getImplementation().getInstructions()) {
            if(awaitingRaw) {
                assertEquals(com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_OBJECT,instruction.getOpcode());
                rawRegister=((OneRegisterInstruction)instruction).getRegisterA();awaitingRaw=false;
            }
            if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof MethodReference method) {
                if(method.getDefiningClass().equals(mapping) && method.getName().equals("LIZLLL"))awaitingRaw=true;
                if(method.getDefiningClass().equals(bottom) && method.getName().equals("<init>")) {
                    assertTrue(rawRegister>=0);assertEquals(rawRegister,((FiveRegisterInstruction)instruction).getRegisterE());constructors++;
                }
            }
        }
        assertTrue("The native factory must construct BottomTab with its mapped raw type",constructors>0);
        List<String> mapTokens=hostTokens(hostMethod(types.get(mapping),"<clinit>"));
        List<String> idTokens=hostTokens(hostMethod(types.get(idsType),"<clinit>"));
        String[] fields={"HOMEPAGE_HOME","HOMEPAGE_FAMILIAR","HOMEPAGE_NOTIFICATION","HOMEPAGE_PROFILE"};
        String[] raw={"HOME","FAMILIAR","NOTIFICATION","USER"};
        String[] ids={"homepage_home","homepage_familiar","homepage_notification","homepage_profile"};
        for(int i=0;i<fields.length;i++) {
            int field=mapTokens.indexOf("LX/1Jlt;->"+fields[i]);assertTrue(fields[i],field>=0);
            List<String> tail=mapTokens.subList(field+1,mapTokens.size());int id=tail.indexOf("LX/1Jlt;->getId"),value=tail.indexOf(raw[i]);
            assertTrue(fields[i]+" maps its public ID to "+raw[i],id>=0 && value>id);
            assertTrue(tail.subList(value+1,tail.size()).contains("LX/1fhM;->put"));
            int enumName=idTokens.indexOf(fields[i]),stored=idTokens.indexOf(idsType+"->"+fields[i]);
            assertTrue(fields[i]+" keeps its public homepage ID",enumName>=0 && stored>enumName && idTokens.subList(enumName+1,stored).contains(ids[i]));
            assertEquals("nav_bottom:"+ids[i],
                    HomeUiCustomizationSettings.canonicalNavigationKey("nav_bottom:"+raw[i]));
        }
    }
    private static com.android.tools.smali.dexlib2.iface.Method hostMethod(ClassDef type,String name) {
        for(var method:type.getMethods())if(method.getName().equals(name))return method;
        throw new AssertionError(type.getType()+"->"+name);
    }
    private static List<String> hostTokens(com.android.tools.smali.dexlib2.iface.Method method) {
        List<String> tokens=new ArrayList<>();assertNotNull(method.getImplementation());
        for(var instruction:method.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref) {
            if(ref.getReference() instanceof FieldReference field)tokens.add(field.getDefiningClass()+"->"+field.getName());
            else if(ref.getReference() instanceof MethodReference target)tokens.add(target.getDefiningClass()+"->"+target.getName());
            else if(ref.getReference() instanceof StringReference text)tokens.add(text.getString());
        }
        return tokens;
    }
    @Test public void allFourDefaultBottomLocationsMatchNativeFactoryTypesAcrossBothSelectionStatesAndThemes() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        String[] ids={"homepage_home","homepage_familiar","homepage_notification","homepage_profile"};
        String[] raw={"HOME","FAMILIAR","NOTIFICATION","USER"};
        BottomTab[] tabs=new BottomTab[ids.length];Drawable[] originals=new Drawable[ids.length];String json="{}";
        for(int i=0;i<ids.length;i++) {
            tabs[i]=new BottomTab(a,raw[i],ids[i]);root.addView(tabs[i]);assertEquals(raw[i],tabs[i].getTabType());
            originals[i]=new ColorDrawable(Color.MAGENTA);originals[i].setBounds(0,0,8,8);
            tabs[i].getTabTitle().setCompoundDrawables(null,originals[i],null,null);
            json=HomeUiCustomizationSettings.putIcons(json,"nav_bottom:"+ids[i],fourIcons());
        }
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            for(IconState state:IconState.values()) {
                for(BottomTab tab:tabs)tab.setSelected(state.selected);setNight(root,state.night);custom.sync();custom.sync();
                for(BottomTab tab:tabs)assertEquals(tab.getTabType()+" / "+state,state.selected?Color.GREEN:Color.BLUE,drawColor(tab.getTabTitle().getCompoundDrawables()[1]));
            }
            for(int i=0;i<ids.length;i++) {
                final String canonical="nav_bottom:"+ids[i],nativeKey="nav_bottom:"+raw[i];
                assertEquals(1,HomeUiCustomization.availableTargets(json).stream().filter(target->target.key.equals(canonical)).count());
                assertFalse(HomeUiCustomization.availableTargets(json).stream().anyMatch(target->target.key.equals(nativeKey)));
            }
            custom.setOptions(options("{}",false));
            for(int i=0;i<tabs.length;i++)assertSame(originals[i],tabs[i].getTabTitle().getCompoundDrawables()[1]);
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void rawOnlySavedBottomLocationsAppearOnceAndStillApplyWithoutChangingUnrelatedDynamicIds() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        BottomTab profile=new BottomTab(a,"USER","我"),dynamic=new BottomTab(a,"USER_extra","动态");root.addView(profile);root.addView(dynamic);
        org.json.JSONObject values=new org.json.JSONObject();
        values.put("nav_bottom:USER",java.util.Base64.getEncoder().encodeToString(HomeUiIconFixtures.webp(8,8,Color.BLUE)));
        values.put("nav_bottom:USER_extra",java.util.Base64.getEncoder().encodeToString(HomeUiIconFixtures.webp(8,8,Color.GREEN)));
        String json=values.toString();HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertEquals(Color.BLUE,drawColor(profile.getTabTitle().getCompoundDrawables()[1]));
            assertEquals(Color.GREEN,drawColor(dynamic.getTabTitle().getCompoundDrawables()[1]));
            assertEquals(1,HomeUiCustomization.availableTargets(json).stream().filter(target->target.key.equals("nav_bottom:homepage_profile")).count());
            assertFalse(HomeUiCustomization.availableTargets(json).stream().anyMatch(target->target.key.equals("nav_bottom:USER")));
            assertTrue(HomeUiCustomization.availableTargets(json).stream().anyMatch(target->target.key.equals("nav_bottom:USER_extra")));
        } finally {custom.close();a.finish();}
    }
    private static LocalOptions options(String json,boolean hide) {
        return new LocalOptions(Map.of(HomeUiCustomizationSettings.ICONS,json,
                HomeUiCustomizationSettings.HIDE_LABELS,hide));
    }
    private static String icon(String json,String key,int color) {
        return HomeUiCustomizationSettings.putIcon(json,key,HomeUiIconFixtures.webp(8,8,color));
    }
    @Test public void navigationUsesOwnerSelectionAcrossBothThemesWithOnlyTwoPreloadedStates() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        OwnerSelectedTab tab=new OwnerSelectedTab(a);root.addView(tab);TextView title=tab.getTabTitle();
        assertHostOwner(tab);
        int originalMode=root.getResources().getConfiguration().uiMode;
        Map<IconState,byte[]> variants=fourIcons();String json=HomeUiCustomizationSettings.putIcons("{}","nav_bottom:homepage_home",variants);
        setNight(root,false);HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        long cached=variants.get(IconState.DAY_NORMAL).length+variants.get(IconState.DAY_SELECTED).length+2L*8*8*4;
        try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES-cached)) {
            assertNotNull(occupied);
            for(IconState state:IconState.values()) {
                tab.setSelected(state.selected);setNight(root,state.night);assertFalse(title.isSelected());custom.sync();
                assertEquals(state.selected?Color.GREEN:Color.BLUE,drawColor(title.getCompoundDrawables()[1]));
            }
            Drawable latest=new ColorDrawable(Color.MAGENTA);title.setCompoundDrawables(null,latest,null,null);
            custom.sync();assertEquals(Color.GREEN,drawColor(title.getCompoundDrawables()[1]));
            custom.close();assertSame(latest,title.getCompoundDrawables()[1]);
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void topImageContentCanSwitchStatesAndRestoreWithoutReplacingItsBusinessView() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView image=new ImageView(a);Drawable original=new ColorDrawable(Color.MAGENTA);image.setImageDrawable(original);
        HomeTopTabItemViewNew top=new HomeTopTabItemViewNew(a,"homepage_hot",image);root.addView(top);
        java.util.concurrent.atomic.AtomicInteger clicks=new java.util.concurrent.atomic.AtomicInteger();image.setOnClickListener(view->clicks.incrementAndGet());
        String json=HomeUiCustomizationSettings.putIcons("{}","nav_top:homepage_hot",fourIcons());
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,true),null);
        try {
            assertEquals(Color.BLUE,drawColor(image.getDrawable()));assertTrue(custom.labels(top).isEmpty());
            top.setSelected(true);custom.sync();assertEquals(Color.GREEN,drawColor(image.getDrawable()));
            assertTrue(image.performClick());assertEquals(1,clicks.get());assertSame(image,top.getContentView());
            custom.setOptions(options(HomeUiCustomizationSettings.removeIcon(json,"nav_top:homepage_hot",IconState.DAY_SELECTED),true));
            assertSame(original,image.getDrawable());top.setSelected(false);custom.sync();assertEquals(Color.BLUE,drawColor(image.getDrawable()));
            custom.close();assertSame(original,image.getDrawable());
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void verifiedTopSelectionWinsOverOwnerAndThemeChangesDoNotReplaceArtwork() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        TextView title=new TextView(a);title.setText("推荐");NativeThemeTop top=new NativeThemeTop(a,title);root.addView(top);
        assertHostOwner(top);
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);top.r.A.dark=true;
        HomeUiCustomization custom=new HomeUiCustomization(root,options(HomeUiCustomizationSettings.putIcons("{}","nav_top:homepage_hot",fourIcons()),false),null);
        try {
            assertEquals(Color.BLUE,drawColor(title.getCompoundDrawables()[1]));
            top.r.A.dark=false;custom.sync();assertEquals(Color.BLUE,drawColor(title.getCompoundDrawables()[1]));
            top.r.selected=true;assertFalse(top.isSelected());custom.sync();assertEquals(Color.GREEN,drawColor(title.getCompoundDrawables()[1]));
            top.r.A.dark=true;custom.sync();assertEquals(Color.GREEN,drawColor(title.getCompoundDrawables()[1]));
            top.setSelected(true);top.r.selected=false;custom.sync();assertEquals(Color.BLUE,drawColor(title.getCompoundDrawables()[1]));
            top.r.A.unavailable=true;top.r.selectionUnavailable=true;custom.sync();assertEquals(Color.GREEN,drawColor(title.getCompoundDrawables()[1]));
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void customPixelsIgnoreHostTintWhileNativeTintAlphaAndOtherDrawablesRemainOwnedByHost() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        BottomTab tab=new BottomTab(a,"homepage_home","首页");root.addView(tab);TextView title=tab.getTabTitle();
        Drawable left=new ColorDrawable(Color.WHITE);left.setBounds(0,0,8,8);title.setCompoundDrawables(left,null,null,null);
        ColorStateList tint=ColorStateList.valueOf(Color.RED);title.setCompoundDrawableTintList(tint);title.setAlpha(.42f);
        ImageView comment=new ImageView(a);comment.setId(0x7f0a254e);comment.setImageTintList(tint);
        Drawable original=new ColorDrawable(Color.WHITE);comment.setImageDrawable(original);root.addView(comment);
        String json=icon(icon("{}","nav_bottom:homepage_home",Color.BLUE),"feed_comment",Color.GREEN);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertEquals(Color.BLUE,drawColor(title.getCompoundDrawables()[1]));assertEquals(Color.GREEN,drawColor(comment.getDrawable()));
            assertSame(tint,title.getCompoundDrawableTintList());assertSame(tint,comment.getImageTintList());
            assertSame(left,title.getCompoundDrawables()[0]);assertEquals(Color.RED,drawColor(left));assertEquals(.42f,title.getAlpha(),0);
            title.setSelected(true);custom.sync();assertEquals(Color.BLUE,drawColor(title.getCompoundDrawables()[1]));
            custom.close();assertNull(title.getCompoundDrawables()[1]);assertSame(original,comment.getDrawable());
            assertSame(tint,title.getCompoundDrawableTintList());assertEquals(.42f,title.getAlpha(),0);
        } finally {custom.close();a.finish();}
    }
    @Test public void imageIconsUseOneFeedGlyphSizeWithoutChangingHitboxesOrCustomColors() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a),comment=new ImageView(a);
        like.setId(0x7f0a30ca);comment.setId(0x7f0a254e);
        like.setScaleType(ImageView.ScaleType.CENTER_CROP);comment.setScaleType(ImageView.ScaleType.FIT_XY);
        Drawable nativeLike=new ColorDrawable(Color.DKGRAY),nativeComment=new ColorDrawable(Color.LTGRAY);
        like.setImageDrawable(nativeLike);comment.setImageDrawable(nativeComment);
        FrameLayout.LayoutParams likeParams=new FrameLayout.LayoutParams(135,135),commentParams=new FrameLayout.LayoutParams(83,83);
        root.addView(like,likeParams);root.addView(comment,commentParams);
        byte[] red=HomeUiIconFixtures.webp(8,8,Color.RED),blue=HomeUiIconFixtures.webp(8,8,Color.BLUE);
        String json=HomeUiCustomizationSettings.putIcon("{}","feed_like",red);
        json=HomeUiCustomizationSettings.putIcon(json,"feed_comment",blue);
        a.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
        root.layout(0,0,360,800);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            int glyph=Math.round(24*a.getResources().getDisplayMetrics().density);
            assertEquals(ImageView.ScaleType.CENTER,like.getScaleType());assertEquals("Fixed-size drawing honors FIT_XY bounds without changing the host mode",ImageView.ScaleType.FIT_XY,comment.getScaleType());
            assertEquals(glyph,like.getDrawable().getIntrinsicWidth());assertEquals(glyph,comment.getDrawable().getIntrinsicWidth());
            assertEquals(Color.RED,drawColor(like.getDrawable()));assertEquals(Color.BLUE,drawColor(comment.getDrawable()));
            assertArrayEquals(new int[]{glyph,glyph},drawnBounds(like));assertArrayEquals(new int[]{glyph,glyph},drawnBounds(comment));
            assertSame(likeParams,like.getLayoutParams());assertSame(commentParams,comment.getLayoutParams());
            assertEquals(135,likeParams.width);assertEquals(83,commentParams.width);
            assertArrayEquals("Rendering does not rewrite saved custom artwork",red,HomeUiCustomizationSettings.readIcon(json,"feed_like",IconState.DAY_NORMAL));
            assertArrayEquals("Rendering does not rewrite saved custom artwork",blue,HomeUiCustomizationSettings.readIcon(json,"feed_comment",IconState.DAY_NORMAL));
        } finally {
            custom.close();
            assertEquals(ImageView.ScaleType.CENTER_CROP,like.getScaleType());assertEquals(ImageView.ScaleType.FIT_XY,comment.getScaleType());
            assertSame(nativeLike,like.getDrawable());assertSame(nativeComment,comment.getDrawable());
            a.finish();
        }
    }
    @Test public void goneInvisibleAndZeroAlphaFeedIconsKeepHostGeometryAndRestoreLatestArtwork() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);like.setScaleType(ImageView.ScaleType.CENTER_CROP);
        like.setAlpha(0f);like.setVisibility(View.GONE);Drawable initial=new ColorDrawable(Color.DKGRAY);like.setImageDrawable(initial);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(72,72);root.addView(like,params);a.setContentView(root);
        layoutRoot(root,360,800);
        String json=icon("{}","feed_like",Color.BLUE);HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            Drawable themed=like.getDrawable();assertNotSame("GONE targets are still customized without changing visibility",initial,themed);
            assertEquals(View.GONE,like.getVisibility());assertEquals(0f,like.getAlpha(),0f);assertSame(params,like.getLayoutParams());
            like.setVisibility(View.INVISIBLE);like.setAlpha(.42f);like.requestLayout();layoutRoot(root,360,800);custom.refresh();custom.sync();
            assertEquals(View.INVISIBLE,like.getVisibility());assertEquals(.42f,like.getAlpha(),0f);assertSame(params,like.getLayoutParams());
            assertEquals(72,like.getWidth());assertEquals(72,like.getHeight());
            like.setVisibility(View.VISIBLE);like.setAlpha(1f);like.requestLayout();layoutRoot(root,360,800);custom.refresh();custom.sync();
            assertEquals(View.VISIBLE,like.getVisibility());assertEquals(1f,like.getAlpha(),0f);assertSame(params,like.getLayoutParams());
            assertEquals(Color.BLUE,drawColor(like.getDrawable()));
            like.setAlpha(0f);custom.sync();assertSame("A visible alpha-zero placeholder keeps its icon override",themed,like.getDrawable());
            assertEquals(View.VISIBLE,like.getVisibility());assertEquals(0f,like.getAlpha(),0f);assertSame(params,like.getLayoutParams());
            like.setAlpha(1f);custom.sync();
            Drawable latest=new ColorDrawable(Color.GREEN);like.setImageDrawable(latest);custom.sync();assertEquals(Color.BLUE,drawColor(like.getDrawable()));
            custom.setOptions(options("{}",false));
            assertSame("Switching the theme off restores the latest host drawable",latest,like.getDrawable());
            assertEquals(View.VISIBLE,like.getVisibility());assertEquals(1f,like.getAlpha(),0f);assertSame(params,like.getLayoutParams());
            assertEquals(ImageView.ScaleType.CENTER_CROP,like.getScaleType());
        } finally {custom.close();a.finish();}
    }
    @Test public void likeContainerStylesItsIdlessNativeChildAndRestoresItWhenReparented() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        FrameLayout likeFrame=new FrameLayout(a);likeFrame.setId(0x7f0a30d6);root.addView(likeFrame);
        DiggAnimationLayout layout=new DiggAnimationLayout(a);layout.setId(0x7f0a30ca);likeFrame.addView(layout);
        DiggAnimationView child=new DiggAnimationView(a);Drawable host=new ColorDrawable(Color.RED);
        child.setImageDrawable(host);child.setClickable(true);layout.addView(child);
        ImageView unrelated=new ImageView(a);Drawable unrelatedHost=new ColorDrawable(Color.MAGENTA);
        unrelated.setImageDrawable(unrelatedHost);layout.addView(unrelated);
        FrameLayout lookalike=new FrameLayout(a);lookalike.setId(0x7f0a30ca);likeFrame.addView(lookalike);
        DiggAnimationView wrongParent=new DiggAnimationView(a);Drawable wrongHost=new ColorDrawable(Color.YELLOW);
        wrongParent.setImageDrawable(wrongHost);lookalike.addView(wrongParent);
        a.setContentView(root);layoutRoot(root,360,800);
        String json=HomeUiCustomizationSettings.putIcons("{}","feed_like",fourIcons());
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertEquals(View.NO_ID,child.getId());
            assertEquals(Color.BLUE,drawColor(child.getDrawable()));
            child.setSelected(true);custom.sync();assertEquals(Color.GREEN,drawColor(child.getDrawable()));
            assertTrue(child.isClickable());assertSame(unrelatedHost,unrelated.getDrawable());assertSame(wrongHost,wrongParent.getDrawable());
            layout.removeView(child);root.addView(child);custom.sync();assertSame(host,child.getDrawable());
            DiggAnimationView replacement=new DiggAnimationView(a);Drawable latest=new ColorDrawable(Color.CYAN);
            replacement.setImageDrawable(latest);layout.addView(replacement);
            custom.refresh();assertEquals(Color.BLUE,drawColor(replacement.getDrawable()));
            custom.close();assertSame(latest,replacement.getDrawable());assertSame(host,child.getDrawable());
        } finally {custom.close();a.finish();}
    }
    @Test public void likeContainerWaitsForItsIdlessLottieChildBeforeReplacingArtwork() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        FrameLayout likeFrame=new FrameLayout(a);likeFrame.setId(0x7f0a30d6);root.addView(likeFrame);
        DiggAnimationLayout layout=new DiggAnimationLayout(a);layout.setId(0x7f0a30ca);likeFrame.addView(layout);
        DiggAnimationViewV2 child=new DiggAnimationViewV2(a);
        Drawable placeholder=new ColorDrawable(Color.GRAY),host=new ColorDrawable(Color.RED);
        child.setHostPlaceholder(placeholder);layout.addView(child);
        a.setContentView(root);layoutRoot(root,360,800);
        String json=HomeUiCustomizationSettings.putIcons("{}","feed_like",fourIcons());
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertSame(placeholder,child.getDrawable());
            child.completeComposition(host);assertNotNull(child.getComposition());
            child.setSelected(true);custom.sync();assertEquals(Color.GREEN,drawColor(child.getDrawable()));
            custom.close();assertSame(host,child.getDrawable());
        } finally {custom.close();a.finish();}
    }
    private static int[] drawnBounds(ImageView image) {
        Bitmap bitmap=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            image.draw(new Canvas(bitmap));int left=bitmap.getWidth(),top=bitmap.getHeight(),right=-1,bottom=-1;
            for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)
                if(Color.alpha(bitmap.getPixel(x,y))>0){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
            return new int[]{right-left+1,bottom-top+1};
        }finally{bitmap.recycle();}
    }
    private static void layoutRoot(View root,int width,int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
    }
    @Test public void explicitNativeStateCanLeaveAndReenterLegacySelectedFeedAcrossBothThemes() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);root.addView(like);
        Drawable original=new ColorDrawable(Color.RED);like.setImageDrawable(original);
        String json=icon(icon("{}","feed_like",Color.BLUE),"feed_like_selected",Color.GREEN);
        json=HomeUiCustomizationSettings.removeIcon(json,"feed_like",IconState.DAY_SELECTED);
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,true);like.setSelected(true);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertSame(original,like.getDrawable());setNight(root,false);custom.sync();assertSame(original,like.getDrawable());
            like.setSelected(false);custom.sync();assertEquals(Color.BLUE,drawColor(like.getDrawable()));
            setNight(root,true);custom.sync();assertEquals(Color.BLUE,drawColor(like.getDrawable()));
            like.setSelected(true);custom.sync();assertSame(original,like.getDrawable());like.setSelected(false);custom.sync();
            assertEquals(Color.BLUE,drawColor(like.getDrawable()));
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void drawableOnlyFeedSelectionSurvivesThemeSwapsAndRepeatedCachedFrames() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);root.addView(like);
        Drawable host=new ColorDrawable(Color.RED);like.setImageDrawable(host);host.setState(new int[]{android.R.attr.state_selected});
        assertFalse(like.isSelected());assertFalse(like.isActivated());
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(HomeUiCustomizationSettings.putIcons("{}","feed_like",fourIcons()),false),null);
        try {
            for(boolean night:new boolean[]{false,true,false,true}) {
                setNight(root,night);custom.sync();assertEquals(Color.GREEN,drawColor(like.getDrawable()));
                custom.sync();custom.sync();assertEquals(Color.GREEN,drawColor(like.getDrawable()));
            }
            custom.close();assertSame(host,like.getDrawable());assertFalse(like.isSelected());assertFalse(like.isActivated());
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void restoringStatefulNativeArtworkRetainsDrawableOnlySelectionAcrossExplicitNullFrames() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);root.addView(like);
        StateListDrawable nativeIcon=new StateListDrawable();Drawable nativeSelected=new ColorDrawable(Color.MAGENTA);
        nativeIcon.addState(new int[]{android.R.attr.state_selected},nativeSelected);nativeIcon.addState(new int[0],new ColorDrawable(Color.RED));
        like.setImageDrawable(nativeIcon);nativeIcon.setState(new int[]{android.R.attr.state_enabled,android.R.attr.state_selected});
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        String selectedJson=HomeUiCustomizationSettings.putIcons("{}","feed_like",fourIcons());
        String json=HomeUiCustomizationSettings.removeIcon(selectedJson,"feed_like",IconState.DAY_SELECTED);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertFalse(like.isSelected());assertFalse(like.isActivated());assertSame(nativeIcon,like.getDrawable());assertSame(nativeSelected,nativeIcon.getCurrent());
            for(int i=0;i<3;i++) {
                setNight(root,true);custom.sync();assertSame(nativeIcon,like.getDrawable());assertSame(nativeSelected,nativeIcon.getCurrent());
                custom.sync();custom.sync();assertSame(nativeIcon,like.getDrawable());assertSame(nativeSelected,nativeIcon.getCurrent());
                custom.setOptions(options(selectedJson,false));assertEquals(Color.GREEN,drawColor(like.getDrawable()));
                setNight(root,false);custom.sync();custom.sync();assertEquals(Color.GREEN,drawColor(like.getDrawable()));
                custom.setOptions(options(json,false));assertSame(nativeIcon,like.getDrawable());assertSame(nativeSelected,nativeIcon.getCurrent());
            }
            custom.close();assertSame(nativeIcon,like.getDrawable());assertSame(nativeSelected,nativeIcon.getCurrent());
            assertFalse(like.isSelected());assertFalse(like.isActivated());
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void verifiedDoubleLayerBottomNavigationUsesItsOneExistingImageAndTracksBothNativeTitles() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        BottomTab tab=new BottomTab(a,"homepage_home","首页");root.addView(tab,new FrameLayout.LayoutParams(-1,100));
        TextView primary=tab.getTabTitle();primary.setId(0x7f0aaefe);primary.setVisibility(View.GONE);
        android.widget.LinearLayout branch=new android.widget.LinearLayout(a);branch.setId(0x7f0ab579);branch.setOrientation(android.widget.LinearLayout.VERTICAL);
        ImageView image=new ImageView(a);image.setId(0x7f0ab578);Drawable original=new ColorDrawable(Color.MAGENTA);image.setImageDrawable(original);
        int edge=Math.round(28*a.getResources().getDisplayMetrics().density);
        branch.addView(image,new android.widget.LinearLayout.LayoutParams(edge,edge));
        TextView alternate=new TextView(a);alternate.setId(0x7f0ab57a);alternate.setText("首页");alternate.setTextSize(10);alternate.setAlpha(.6f);
        branch.addView(alternate,new android.widget.LinearLayout.LayoutParams(-2,-2));tab.addView(branch);
        String json=icon("{}","nav_bottom:homepage_home",Color.BLUE);java.util.ArrayList<String> logs=new java.util.ArrayList<>();
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),logs::add);
        try {
            assertEquals(Color.BLUE,drawColor(image.getDrawable()));assertNull(alternate.getCompoundDrawables()[1]);
            assertTrue(custom.labels(tab).contains(primary));assertTrue(custom.labels(tab).contains(alternate));
            assertEquals(edge,image.getLayoutParams().height);assertSame(branch,image.getParent());assertEquals(.6f,alternate.getAlpha(),0);
            assertEquals(View.GONE,primary.getVisibility());assertEquals(View.VISIBLE,branch.getVisibility());
            int count=logs.size();for(int i=0;i<30;i++)custom.sync();assertEquals("Stable pre-draws must not repeat application evidence",count,logs.size());
            assertTrue(logs.stream().anyMatch(line->line.contains("config=accepted") && line.contains("image_writes=1")));
            assertTrue(logs.stream().allMatch(line->line.length()<=1024));
            custom.setOptions(options(json,true));assertEquals(0,alternate.getTextSize(),0);assertNull(alternate.getCompoundDrawables()[1]);
            primary.setVisibility(View.VISIBLE);branch.setVisibility(View.GONE);custom.sync();assertNotNull(primary.getCompoundDrawables()[1]);
            custom.close();assertSame(original,image.getDrawable());assertNull(primary.getCompoundDrawables()[1]);
            assertTrue(alternate.getTextSize()>0);assertEquals(.6f,alternate.getAlpha(),0);
            assertEquals(View.VISIBLE,primary.getVisibility());assertEquals(View.GONE,branch.getVisibility());
        } finally {custom.close();a.finish();}
    }
    @Test public void nativeDoubleLayerImageUsesItsVerifiedTitlesSelectionAcrossBothThemesRatherThanItsOwnFlags() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        OwnerSelectedTab tab=new OwnerSelectedTab(a);root.addView(tab);tab.getTabTitle().setId(0x7f0aaefe);tab.getTabTitle().setVisibility(View.GONE);
        assertHostOwner(tab);
        android.widget.LinearLayout branch=new android.widget.LinearLayout(a);branch.setId(0x7f0ab579);branch.setOrientation(android.widget.LinearLayout.VERTICAL);
        ImageView image=new ImageView(a);image.setId(0x7f0ab578);Drawable original=new ColorDrawable(Color.MAGENTA);image.setImageDrawable(original);branch.addView(image);
        TextView title=new TextView(a);title.setId(0x7f0ab57a);title.setText("首页");branch.addView(title);tab.addView(branch);
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(HomeUiCustomizationSettings.putIcons("{}","nav_bottom:homepage_home",fourIcons()),false),null);
        try {
            for(IconState state:IconState.values()) {
                title.setSelected(state.selected);setNight(root,state.night);assertFalse(tab.isSelected());assertFalse(image.isSelected());
                custom.sync();custom.sync();assertEquals(state.selected?Color.GREEN:Color.BLUE,drawColor(image.getDrawable()));assertNull(title.getCompoundDrawables()[1]);
            }
            custom.close();assertSame(original,image.getDrawable());
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void navigationFeedbackKeepsOneForegroundAndImmediateIconStateWithoutGeometryChanges() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        OwnerSelectedTab tab=new OwnerSelectedTab(a);FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(140,100);position.leftMargin=20;position.topMargin=30;
        root.addView(tab,position);Drawable original=new ColorDrawable(Color.DKGRAY);tab.setForeground(original);tab.setAlpha(.7f);
        ImageView feed=new ImageView(a);feed.setId(0x7f0a254e);Drawable feedForeground=new ColorDrawable(Color.RED);feed.setForeground(feedForeground);root.addView(feed);
        a.setContentView(root);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);
        assertHostOwner(tab);assertVisibleWindow(tab);
        int originalMode=root.getResources().getConfiguration().uiMode;setNight(root,false);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(HomeUiCustomizationSettings.putIcons("{}","nav_bottom:homepage_home",fourIcons()),false),null);
        try {
            Drawable wrapper=tab.getForeground();assertTrue(wrapper instanceof LayerDrawable);assertEquals(2,((LayerDrawable)wrapper).getNumberOfLayers());assertSame(original,((LayerDrawable)wrapper).getDrawable(0));
            android.graphics.Rect bounds=new android.graphics.Rect(tab.getLeft(),tab.getTop(),tab.getRight(),tab.getBottom());
            assertEquals(Color.BLUE,drawColor(tab.getTabTitle().getCompoundDrawables()[1]));
            tab.setSelected(true);custom.sync();assertEquals("The icon changes on the same update, before any animation clock advances",Color.GREEN,drawColor(tab.getTabTitle().getCompoundDrawables()[1]));
            assertSame(wrapper,tab.getForeground());setNight(root,true);custom.sync();assertEquals(Color.GREEN,drawColor(tab.getTabTitle().getCompoundDrawables()[1]));
            for(int i=0;i<8;i++)custom.sync();assertSame(wrapper,tab.getForeground());assertSame(feedForeground,feed.getForeground());
            assertSame(position,tab.getLayoutParams());assertEquals(140,position.width);assertEquals(100,position.height);assertEquals(20,position.leftMargin);assertEquals(30,position.topMargin);
            assertEquals(bounds,new android.graphics.Rect(tab.getLeft(),tab.getTop(),tab.getRight(),tab.getBottom()));assertEquals(.7f,tab.getAlpha(),0);assertEquals(0,tab.getTranslationX(),0);assertEquals(0,tab.getTranslationY(),0);
            tab.setVisibility(View.INVISIBLE);custom.sync();assertSame(original,tab.getForeground());tab.setVisibility(View.VISIBLE);custom.sync();assertTrue(tab.getForeground() instanceof LayerDrawable);
            custom.close();assertSame(original,tab.getForeground());assertSame(feedForeground,feed.getForeground());
        } finally {root.getResources().getConfiguration().uiMode=originalMode;custom.close();a.finish();}
    }
    @Test public void navigationWithoutCustomIconsStillReceivesFeedbackAndRemovedOwnersReleaseIt() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);OwnerSelectedTab tab=new OwnerSelectedTab(a);
        root.addView(tab,new FrameLayout.LayoutParams(140,100));Drawable original=new ColorDrawable(Color.DKGRAY);tab.setForeground(original);a.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);
        assertHostOwner(tab);assertVisibleWindow(tab);
        HomeUiCustomization custom=new HomeUiCustomization(root,options("{}",false),null);
        try {
            Drawable wrapper=tab.getForeground();assertTrue(wrapper instanceof LayerDrawable);assertNull(tab.getTabTitle().getCompoundDrawables()[1]);
            tab.setSelected(true);custom.sync();assertSame(wrapper,tab.getForeground());root.removeView(tab);custom.sync();assertSame(original,tab.getForeground());
            root.addView(tab,new FrameLayout.LayoutParams(140,100));custom.refresh();assertTrue(tab.getForeground() instanceof LayerDrawable);
            custom.close();assertSame(original,tab.getForeground());
        } finally {custom.close();a.finish();}
    }
    private static Map<IconState,byte[]> fourIcons() {
        Map<IconState,byte[]> result=new EnumMap<>(IconState.class);
        for(IconState state:IconState.values())result.put(state,HomeUiIconFixtures.webp(8,8,stateColor(state)));
        return result;
    }
    private static int stateColor(IconState state) {
        switch(state){case DAY_NORMAL:return Color.BLUE;case DAY_SELECTED:return Color.GREEN;case NIGHT_NORMAL:return Color.WHITE;default:return Color.YELLOW;}
    }
    private static void setNight(View view,boolean night) {
        Configuration config=view.getResources().getConfiguration();
        config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(night?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
    }
    private static int drawColor(Drawable drawable) {
        assertNotNull(drawable);Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
        android.graphics.Rect old=new android.graphics.Rect(drawable.getBounds());
        try {drawable.setBounds(0,0,16,16);drawable.draw(new Canvas(bitmap));return bitmap.getPixel(8,8);}
        finally {drawable.setBounds(old);bitmap.recycle();}
    }
    private static void assertHostOwner(View owner) {
        assertFalse("A host fixture must be discoverable, outside the module-owned UI boundary: "+owner.getClass().getName(),LiquidGlassTargets.opaque(owner));
    }
    private static void assertVisibleWindow(View owner) {
        assertTrue("Feedback fixture must be attached",owner.isAttachedToWindow());
        assertTrue("Feedback fixture and its ancestors must be shown",owner.isShown());
        assertEquals("Feedback fixture needs a visible real window",View.VISIBLE,owner.getWindowVisibility());
    }
    @Test public void addsAnIconAboveUnchangedBottomTextAndRestoresOriginalPresentation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root=new FrameLayout(a);BottomTab tab=new BottomTab(a,"homepage_home","首页");root.addView(tab);
        TextView label=tab.getTabTitle();label.setTextSize(TypedValue.COMPLEX_UNIT_SP,16);
        float size=label.getTextSize();Drawable left=new ColorDrawable(Color.RED);left.setBounds(0,0,4,4);
        label.setCompoundDrawables(left,null,null,null);label.setCompoundDrawablePadding(7);
        label.setPadding(1,2,3,4);label.setIncludeFontPadding(true);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon("{}","nav_bottom:homepage_home",Color.BLUE),false),null);
        try {
            custom.refresh();assertEquals("首页",label.getText().toString());assertSame(left,label.getCompoundDrawables()[0]);
            assertNotNull(label.getCompoundDrawables()[1]);assertTrue(label.getTextSize()<size);
            assertEquals(24,Math.round(label.getCompoundDrawables()[1].getBounds().width()/a.getResources().getDisplayMetrics().density));
            assertTrue(custom.labels(root).contains(label));assertEquals(4,label.getPaddingBottom());
            Drawable applied=label.getCompoundDrawables()[1];custom.refresh();assertSame(applied,label.getCompoundDrawables()[1]);
            custom.setOptions(options("{}",false));
            assertTrue(label.getTextSize()<=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,11,a.getResources().getDisplayMetrics()));
            assertSame(left,label.getCompoundDrawables()[0]);assertNull(label.getCompoundDrawables()[1]);
            assertEquals(7,label.getCompoundDrawablePadding());assertTrue(label.getIncludeFontPadding());
            custom.close();assertEquals(size,label.getTextSize(),0);
        } finally {custom.close();a.finish();}
    }
    @Test public void hidesOnlyLabelsWithCustomIconsAndNeverChangesTheirTextOrClickState() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        TextView title=new TextView(a);title.setText("推荐");title.setClickable(true);
        HomeTopTabItemViewNew top=new HomeTopTabItemViewNew(a,"homepage_hot",title);root.addView(top);
        BottomTab friends=new BottomTab(a,"homepage_familiar","朋友");root.addView(friends);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon("{}","nav_top:homepage_hot",Color.BLUE),true),null);
        try {
            custom.refresh();assertEquals(0,title.getTextSize(),0);assertEquals("推荐",title.getText().toString());
            assertFalse(title.getIncludeFontPadding());assertTrue(title.isClickable());assertEquals(View.VISIBLE,title.getVisibility());
            assertTrue(friends.getTabTitle().getTextSize()>0);assertEquals("朋友",friends.getTabTitle().getText().toString());
            custom.setOptions(options("{}",true));assertTrue(title.getTextSize()>0);assertEquals("推荐",title.getText().toString());
        } finally {custom.close();a.finish();}
    }
    @Test public void feedOverridesTargetImageChildrenAndSwitchOptionalSelectedImages() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView slot=new ImageView(a);slot.setId(0x7f0a30de);Drawable slotOriginal=new ColorDrawable(Color.GRAY);slot.setImageDrawable(slotOriginal);root.addView(slot);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);Drawable original=new ColorDrawable(Color.RED);like.setImageDrawable(original);root.addView(like);
        String json=icon(icon("{}","feed_like",Color.BLUE),"feed_like_selected",Color.GREEN);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            custom.refresh();Drawable regular=like.getDrawable();assertNotSame(original,regular);assertSame(slotOriginal,slot.getDrawable());
            like.setSelected(true);custom.refresh();Drawable selected=like.getDrawable();assertNotSame(regular,selected);
            like.setSelected(false);custom.refresh();assertNotSame(selected,like.getDrawable());
            Drawable latestHost=new ColorDrawable(Color.YELLOW);like.setImageDrawable(latestHost);custom.refresh();assertNotSame(latestHost,like.getDrawable());
            custom.setOptions(options("{}",false));assertSame(latestHost,like.getDrawable());
        } finally {custom.close();a.finish();}
    }
    @Test public void doesNotClobberHostReplacementOnCloseOrMistakeAnimationContentForALabel() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView content=new ImageView(a);HomeTopTabItemViewNew top=new HomeTopTabItemViewNew(a,"activity_tab",content);root.addView(top);
        ImageView share=new ImageView(a);share.setId(0x7f0aa9f7);root.addView(share);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon("{}","feed_share",Color.BLUE),true),null);
        custom.refresh();Drawable host=new ColorDrawable(Color.MAGENTA);share.setImageDrawable(host);
        custom.close();assertSame(host,share.getDrawable());assertTrue(custom.labels(top).isEmpty());a.finish();
    }
    @Test public void neverEnlargesASmallerLabelAndPreservesNewHostTextSizeOnClose() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        BottomTab tab=new BottomTab(a,"homepage_profile","我");root.addView(tab);TextView title=tab.getTabTitle();
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP,8);float small=title.getTextSize();
        HomeUiCustomization custom=new HomeUiCustomization(root,options("{}",false),null);
        custom.refresh();assertEquals(small,title.getTextSize(),0);title.setTextSize(TypedValue.COMPLEX_UNIT_SP,10);
        float host=title.getTextSize();custom.close();assertEquals(host,title.getTextSize(),0);a.finish();
    }
    @Test public void enumeratesSavedDynamicTargetsWithoutInventingTopChannels() {
        String json=icon("{}","nav_top:runtime_channel",Color.BLUE);
        assertTrue(HomeUiCustomization.availableTargets(json).stream().anyMatch(t->t.key.equals("nav_top:runtime_channel")));
        assertTrue(HomeUiCustomization.availableTargets().stream().anyMatch(t->t.key.equals("nav_bottom:homepage_home")));
        assertFalse(HomeUiCustomization.availableTargets().stream().anyMatch(t->t.key.equals("nav_bottom:homepage_publish")));
    }
    @Test public void dynamicTargetMetadataRemainsAvailableWhenNoImageDecodeMemoryCanBeReserved() {
        String json=HomeUiCustomizationSettings.putIcons("{}","nav_top:runtime_four_states",fourIcons());
        try(MediaMemoryBudget.Reservation full=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(full);
            assertTrue(HomeUiCustomization.availableTargets(json).stream().anyMatch(target->target.key.equals("nav_top:runtime_four_states")));
        }
    }
    @Test public void cachedSyncObservesHostAndSelectionChangesWithoutANewDecodeReservation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);root.addView(like);
        byte[] normal=HomeUiIconFixtures.webp(8,8,Color.BLUE),selected=HomeUiIconFixtures.webp(8,8,Color.GREEN);
        String json=HomeUiCustomizationSettings.putIcon("{}","feed_like",normal);
        json=HomeUiCustomizationSettings.putIcon(json,"feed_like_selected",selected);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        long cachedBytes=normal.length+selected.length+2L*8*8*4;
        try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES-cachedBytes)) {
            assertNotNull(occupied);Drawable regular=like.getDrawable();like.setActivated(true);custom.sync();
            assertNotSame(regular,like.getDrawable());like.setActivated(false);custom.sync();
            Drawable host=new ColorDrawable(Color.YELLOW);like.setImageDrawable(host);custom.sync();assertNotSame(host,like.getDrawable());
            custom.close();assertSame(host,like.getDrawable());
        } finally {custom.close();a.finish();}
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)){assertNotNull(all);}
    }
    @Test public void samplesLargeIconsAndRestoresAPreviouslyRunningHostAnimation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView follow=new ImageView(a);follow.setId(0x7f0a461d);root.addView(follow);
        RunningDrawable host=new RunningDrawable();host.start();follow.setImageDrawable(host);
        String json=HomeUiCustomizationSettings.putIcon("{}","feed_follow",HomeUiIconFixtures.webp(512,512,Color.BLUE));
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try {
            assertFalse(host.isRunning());assertTrue(((BitmapDrawable)follow.getDrawable()).getBitmap().getWidth()<=256);
            custom.setOptions(options("{}",false));assertSame(host,follow.getDrawable());assertTrue(host.isRunning());
        } finally {custom.close();a.finish();}
    }
    @Test public void removingASelectedOverrideFallsBackToNormalAndInvalidOptionsRetainTheLastValidIcon() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView collect=new ImageView(a);collect.setId(0x7f0a2010);collect.setSelected(true);root.addView(collect);
        String normal=icon("{}","feed_collect",Color.BLUE);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon(normal,"feed_collect_selected",Color.GREEN),false),null);
        try {
            Drawable selected=collect.getDrawable();custom.setOptions(options(normal,false));assertNotSame(selected,collect.getDrawable());
            Drawable fallback=collect.getDrawable();custom.setOptions(options("[]",true));assertSame(fallback,collect.getDrawable());
            collect.setActivated(true);custom.sync();assertSame(fallback,collect.getDrawable());
        } finally {custom.close();a.finish();}
    }
    @Test public void selectedOnlyOverridesCanEnterLeaveAndReenterThroughCachedSync() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        ImageView like=new ImageView(a);like.setId(0x7f0a30ca);root.addView(like);
        Drawable host=new ColorDrawable(Color.RED);like.setImageDrawable(host);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon("{}","feed_like_selected",Color.GREEN),false),null);
        try {
            assertSame(host,like.getDrawable());like.setSelected(true);custom.sync();assertNotSame(host,like.getDrawable());
            like.setSelected(false);custom.sync();assertSame(host,like.getDrawable());
            like.setActivated(true);custom.sync();assertNotSame(host,like.getDrawable());
            custom.close();assertSame(host,like.getDrawable());
        } finally {custom.close();a.finish();}
    }
    @Test public void pendingLottieCompositionFinishesBeforeCachedSyncAppliesTheStaticOverride() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        com.airbnb.lottie.LottieAnimationView follow=new com.airbnb.lottie.LottieAnimationView(a);
        follow.setId(0x7f0a461d);root.addView(follow);Drawable placeholder=new ColorDrawable(Color.GRAY);
        follow.setHostPlaceholder(placeholder);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(icon("{}","feed_follow",Color.BLUE),false),null);
        try {
            assertSame(placeholder,follow.getDrawable());custom.sync();assertSame(placeholder,follow.getDrawable());
            RunningDrawable loaded=new RunningDrawable();loaded.start();follow.completeComposition(loaded);
            custom.sync();assertNotSame(loaded,follow.getDrawable());assertFalse(loaded.isRunning());
            custom.close();assertSame(loaded,follow.getDrawable());assertTrue(loaded.isRunning());
        } finally {custom.close();a.finish();}
    }
    @Test public void unreadableLottieCompositionSkipsOnlyThatInstance() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(a);
        com.airbnb.lottie.LottieAnimationView animation=new com.airbnb.lottie.LottieAnimationView(a);
        animation.setId(0x7f0a461d);animation.failCompositionGetter();root.addView(animation);
        Drawable host=new ColorDrawable(Color.RED);animation.setHostPlaceholder(host);
        ImageView comment=new ImageView(a);comment.setId(0x7f0a254e);root.addView(comment);
        String json=icon(icon("{}","feed_follow",Color.BLUE),"feed_comment",Color.GREEN);
        HomeUiCustomization custom=new HomeUiCustomization(root,options(json,false),null);
        try { custom.sync();assertSame(host,animation.getDrawable());assertNotNull(comment.getDrawable()); }
        finally {custom.close();a.finish();}
    }
    private static final class RunningDrawable extends Drawable implements Animatable {
        private boolean running;
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void start() { running=true; }
        @Override public void stop() { running=false; }
        @Override public boolean isRunning() { return running; }
    }
}
