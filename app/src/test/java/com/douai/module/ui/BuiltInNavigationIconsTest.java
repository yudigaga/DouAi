package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Verify the authored sources against the Android renderer used by the actual import path. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BuiltInNavigationIconsTest {
    @Test public void knownBottomPositionsResolveTheirOwnDefaultAndUnknownOrFeedNeverBorrowADesign() {
        String[][] defaults={{"homepage_home","HOME","bottom/01-home"},{"homepage_familiar","FAMILIAR","bottom/02-friends"},
                {"homepage_notification","NOTIFICATION","bottom/04-messages"},{"homepage_profile","USER","bottom/05-profile"}};
        for(String[] row:defaults) {
            BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition("nav_bottom:"+row[0]);
            assertNotNull(entry);assertEquals(row[2],entry.path);
            assertSame(entry,BuiltInNavigationIcons.entryForPosition("nav_bottom:"+row[1]));
        }
        for(String key:new String[]{null,"feed_like","feed_comment","feed_collect","feed_share","feed_follow","feed_mute",
                "nav_top:unknown_channel","nav_bottom:unknown_tab"})assertNull(BuiltInNavigationIcons.entryForPosition(key));
    }
    @Test public void verifiedTopIdsKeepRecommendAndFeaturedDistinctWithoutGuessingOtherChannels() {
        String[][] exact={{"nav_top:homepage_hot","top/01-recommend"},{"nav_top:homepage_hot_container","top/01-recommend"},
                {"nav_top:homepage_follow","top/02-following"},{"nav_top:homepage_mediumvideo","top/03-featured"},
                {"nav_top:homepage_pad_hot","top/04-trending"},{"nav_top:homepage_nearby","top/05-nearby"},
                {"nav_top:homepage_tablive","top/06-live"},{"nav_top:homepage_mall","top/07-shop"},
                {"nav_top:homepage_familiar","bottom/02-friends"},{"nav_bottom:homepage_publish","bottom/03-create"},
                {"nav_bottom:PUBLISH","bottom/03-create"},{"nav_bottom:homepage_follow","top/02-following"},
                {"nav_bottom:FOLLOW","top/02-following"},{"nav_bottom:homepage_nearby","top/05-nearby"},
                {"nav_bottom:NEARBY","top/05-nearby"},{"nav_bottom:homepage_mall","top/07-shop"},{"nav_bottom:MALL","top/07-shop"}};
        for(String[] row:exact){assertNotNull(row[0],BuiltInNavigationIcons.entryForPosition(row[0]));assertEquals(row[1],BuiltInNavigationIcons.entryForPosition(row[0]).path);}
        assertNotSame(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_hot"),BuiltInNavigationIcons.entryForPosition("nav_top:homepage_mediumvideo"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_groupon"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_theater"));
    }
    @Test public void stateDefaultsChooseInkForDayWhiteForNightAndFilledForSelection() throws Exception {
        assertEquals(BuiltInNavigationIcons.Style.INK_OUTLINE,BuiltInNavigationIcons.styleFor(IconState.DAY_NORMAL));
        assertEquals(BuiltInNavigationIcons.Style.INK_FILLED,BuiltInNavigationIcons.styleFor(IconState.DAY_SELECTED));
        assertEquals(BuiltInNavigationIcons.Style.WHITE_OUTLINE,BuiltInNavigationIcons.styleFor(IconState.NIGHT_NORMAL));
        assertEquals(BuiltInNavigationIcons.Style.WHITE_FILLED,BuiltInNavigationIcons.styleFor(IconState.NIGHT_SELECTED));
        for(IconState state:IconState.values())try(InputStream source=BuiltInNavigationIcons.open(
                BuiltInNavigationIcons.ENTRIES.get(10),BuiltInNavigationIcons.styleFor(state))) {
            byte[] bytes=HomeIconImport.convert(source.readAllBytes());
            Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);assertNotNull(bitmap);
            try {
                int center=bitmap.getPixel(128,128);
                if(state.selected) {
                    assertEquals(255,Color.alpha(center));
                    if(state.night)assertTrue(Color.red(center)>250 && Color.green(center)>250 && Color.blue(center)>250);
                    else assertTrue(Color.red(center)<50 && Color.green(center)<50 && Color.blue(center)<60);
                }else assertEquals(Color.TRANSPARENT,center);
            }finally{bitmap.recycle();}
        }
    }
    @Test public void allSixtyModuleBundledVariantsBecomeBoundedTransparentWebp() throws Exception {
        String[] designs={"top/01-recommend","top/02-following","top/03-featured","top/04-trending","top/05-nearby",
                "top/06-live","top/07-shop","top/08-drama","top/09-search","top/10-menu",
                "bottom/01-home","bottom/02-friends","bottom/03-create","bottom/04-messages","bottom/05-profile"};
        Set<String> resources=new HashSet<>();
        for(String color:new String[]{"white","ink"})for(String design:designs)for(String suffix:new String[]{"","-active"}) {
            String resource="/META-INF/douai/navigation-icons/"+color+"/"+design+suffix+".svg";
            assertTrue(resources.add(resource));byte[] source;
            try(InputStream stream=getClass().getResourceAsStream(resource)) {
                assertNotNull("Module-owned resource is available independently of host assets: "+resource,stream);
                source=stream.readAllBytes();
            }
            assertTrue(source.length<=2048);
            String xml=new String(source,StandardCharsets.UTF_8);
            assertTrue(xml.contains("viewBox=\"0 0 24 24\""));
            assertTrue(xml.contains(color.equals("white")?"#FFFFFF":"#20252B"));
            byte[] webp=HomeIconImport.convert(source);assertTrue(webp.length<=32768);
            Bitmap bitmap=BitmapFactory.decodeByteArray(webp,0,webp.length);assertNotNull(resource,bitmap);
            try {
                assertEquals(256,bitmap.getWidth());assertEquals(256,bitmap.getHeight());
                assertEquals(Color.TRANSPARENT,bitmap.getPixel(0,0));assertEquals(Color.TRANSPARENT,bitmap.getPixel(255,255));
                boolean visible=false;
                for(int y=0;y<256 && !visible;y++)for(int x=0;x<256;x++)if(Color.alpha(bitmap.getPixel(x,y))>0){visible=true;break;}
                assertTrue("The built-in drawing remains visible: "+resource,visible);
            }finally{bitmap.recycle();}
        }
        assertEquals(60,resources.size());
    }
}
