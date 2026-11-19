package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Verify supplied raw artwork against independent fixtures and the actual Android decoder. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BuiltInNavigationIconsTest {
    @Test public void knownBottomPositionsResolveTheirOwnDefaultAndUnknownNeverBorrowADesign() {
        String[][] defaults={{"homepage_home","HOME","bottom/01-home"},{"homepage_familiar","FAMILIAR","bottom/02-friends"},
                {"homepage_notification","NOTIFICATION","bottom/04-messages"},{"homepage_profile","USER","bottom/05-profile"}};
        for(String[] row:defaults) {
            BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition("nav_bottom:"+row[0]);
            assertNotNull(entry);assertEquals(row[2],entry.path);
            assertSame(entry,BuiltInNavigationIcons.entryForPosition("nav_bottom:"+row[1]));
        }
        for(String key:new String[]{null,"feed_sound","feed_like_selected",
                "nav_top:unknown_channel","nav_bottom:unknown_tab"})assertNull(BuiltInNavigationIcons.entryForPosition(key));
    }
    @Test public void verifiedTopIdsKeepRecommendAndFeaturedDistinctWithoutGuessingOtherChannels() {
        String[][] exact={{"nav_top:homepage_hot","top/01-recommend"},{"nav_top:homepage_hot_container","top/01-recommend"},
                {"nav_top:homepage_follow","top/02-following"},{"nav_top:homepage_mediumvideo","top/03-featured"},
                {"nav_top:homepage_pad_hot","top/04-trending"},{"nav_top:homepage_nearby","top/05-nearby"},
                {"nav_top:homepage_tablive","top/06-live"},{"nav_top:homepage_mall","top/07-shop"},
                {"nav_top:homepage_playlet_stream","top/08-drama"},{"nav_top:homepage_familiar","bottom/02-friends"},{"nav_bottom:homepage_publish","bottom/03-create"},
                {"nav_bottom:PUBLISH","bottom/03-create"},{"nav_bottom:homepage_follow","top/02-following"},
                {"nav_bottom:FOLLOW","top/02-following"},{"nav_bottom:homepage_nearby","top/05-nearby"},
                {"nav_bottom:NEARBY","top/05-nearby"},{"nav_bottom:homepage_mall","top/07-shop"},{"nav_bottom:MALL","top/07-shop"}};
        for(String[] row:exact){assertNotNull(row[0],BuiltInNavigationIcons.entryForPosition(row[0]));assertEquals(row[1],BuiltInNavigationIcons.entryForPosition(row[0]).path);}
        assertNotSame(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_hot"),BuiltInNavigationIcons.entryForPosition("nav_top:homepage_mediumvideo"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_groupon"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_theater"));
    }
    @Test public void feedDefaultsResolveDistinctArtworkAndRemainSeparateFromNavigationFollowing() {
        String[][] exact={{"feed_like","feed/01-like"},{"feed_comment","feed/02-comment"},
                {"feed_collect","feed/03-collect"},{"feed_share","feed/04-share"},
                {"feed_follow","feed/05-follow"},{"feed_mute","feed/06-mute"}};
        Set<String> paths=new HashSet<>();
        for(String[] row:exact) {
            BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition(row[0]);
            assertNotNull(row[0],entry);assertEquals(row[1],entry.path);assertTrue(paths.add(entry.path));
        }
        assertNotSame(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_follow"),
                BuiltInNavigationIcons.entryForPosition("feed_follow"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_search"));
        assertNull(BuiltInNavigationIcons.entryForPosition("nav_top:homepage_menu"));
    }
    @Test public void twoStylesUseSuppliedSelectionWithNightAsCompatibilityAliases() {
        assertArrayEquals(new BuiltInNavigationIcons.Style[]{BuiltInNavigationIcons.Style.NORMAL,
                BuiltInNavigationIcons.Style.SELECTED},BuiltInNavigationIcons.Style.values());
        assertEquals(BuiltInNavigationIcons.Style.NORMAL,BuiltInNavigationIcons.styleFor(IconState.DAY_NORMAL));
        assertEquals(BuiltInNavigationIcons.Style.SELECTED,BuiltInNavigationIcons.styleFor(IconState.DAY_SELECTED));
        assertEquals(BuiltInNavigationIcons.Style.NORMAL,BuiltInNavigationIcons.styleFor(IconState.NIGHT_NORMAL));
        assertEquals(BuiltInNavigationIcons.Style.SELECTED,BuiltInNavigationIcons.styleFor(IconState.NIGHT_SELECTED));
        assertThrows(IllegalArgumentException.class,()->BuiltInNavigationIcons.styleFor(null));
    }
    @Test public void publicSymbolBytesCannotMutateTheSharedGeneratedCache() throws Exception {
        byte[] expected=BuiltInNavigationIcons.symbolForPath("top/01-recommend",false).clone();
        byte[] exposed=BuiltInNavigationIcons.symbolForPath("top/01-recommend",false);
        exposed[0]^=1;
        assertArrayEquals(expected,BuiltInNavigationIcons.symbolForPath("top/01-recommend",false));
    }
    @Test public void allFortyFourVariantsAreTransparentWhiteSymbolsWithOneConsistentCanvas() throws Exception {
        String[] designs={"top/01-recommend","top/02-following","top/03-featured","top/04-trending","top/05-nearby",
                "top/06-live","top/07-shop","top/08-drama","top/09-search","top/10-menu",
                "bottom/01-home","bottom/02-friends","bottom/03-create","bottom/04-messages","bottom/05-profile",
                "feed/01-like","feed/02-comment","feed/03-collect","feed/04-share","feed/05-follow","feed/06-mute","feed/07-sound"};
        Set<String> paths=new HashSet<>();
        for(BuiltInNavigationIcons.Entry entry:BuiltInNavigationIcons.ENTRIES)assertTrue(paths.add(entry.path));
        assertEquals(new HashSet<>(Arrays.asList(designs)),paths);assertEquals(22,BuiltInNavigationIcons.ENTRIES.size());
        Set<String> resources=new HashSet<>();
        for(BuiltInNavigationIcons.Entry entry:BuiltInNavigationIcons.ENTRIES)for(boolean selected:new boolean[]{false,true}) {
            String name=entry.path+(selected?"-active":"")+".webp";
            assertTrue(resources.add(name));byte[] expected;
            IconState day=selected?IconState.DAY_SELECTED:IconState.DAY_NORMAL;
            IconState night=selected?IconState.NIGHT_SELECTED:IconState.NIGHT_NORMAL;
            byte[] source;
            try(InputStream stream=BuiltInNavigationIcons.open(entry,BuiltInNavigationIcons.styleFor(day))) {
                source=stream.readAllBytes();
            }
            try(InputStream stream=BuiltInNavigationIcons.open(entry,BuiltInNavigationIcons.styleFor(night))) {
                assertArrayEquals("Legacy night alias: "+name,source,stream.readAllBytes());
            }
            assertTrue(source.length<=32768);
            assertArrayEquals("Generated WebP remains byte-stable: "+name,source,HomeIconImport.convert(source.clone()));
            Bitmap bitmap=BitmapFactory.decodeByteArray(source,0,source.length);assertNotNull(name,bitmap);
            try {
                assertEquals(256,bitmap.getWidth());assertEquals(256,bitmap.getHeight());
                assertEquals("Transparent canvas corner: "+name,0,Color.alpha(bitmap.getPixel(0,0)));
                int minX=256,minY=256,maxX=-1,maxY=-1,visible=0;
                for(int y=0;y<256;y++)for(int x=0;x<256;x++) {
                    int color=bitmap.getPixel(x,y);
                    if(Color.alpha(color)==0)continue;
                    visible++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    assertTrue("Built-in symbol is white: "+name+" @"+x+","+y,
                            Color.red(color)>=235 && Color.green(color)>=235 && Color.blue(color)>=235);
                }
                assertTrue("Symbol has visible pixels: "+name,visible>0);
                assertTrue("No backplate fills the icon edges: "+name+" bounds="+minX+","+minY+"-"+maxX+","+maxY,
                        minX>=12 && minY>=12 && maxX<=243 && maxY<=243);
                assertTrue("Artwork occupancy stays symbol-sized: "+name,visible<256*256*0.5f);
            }finally{bitmap.recycle();}
        }
        assertEquals(44,resources.size());
    }

    @Test public void oldBundledArtworkIsRerenderedForDisplayWithoutRewritingItsStoredBytes() throws Exception {
        BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition("nav_top:homepage_hot");
        byte[] legacy;
        try(InputStream fixture=getClass().getResourceAsStream("/META-INF/douai/supplied-icon-fixtures/top/01-recommend.webp")) {
            assertNotNull(fixture);legacy=fixture.readAllBytes();
        }
        byte[] saved=legacy.clone();
        byte[] rendered=BuiltInNavigationIcons.replaceLegacyArtwork(legacy);
        byte[] expected;
        try(InputStream stream=BuiltInNavigationIcons.open(entry,BuiltInNavigationIcons.Style.NORMAL)) {expected=stream.readAllBytes();}
        assertArrayEquals(expected,rendered);
        assertArrayEquals("Migration is render-only",saved,legacy);
        byte[] custom=new byte[]{1,2,3,4};
        assertSame("Unrecognized user bytes stay untouched",custom,BuiltInNavigationIcons.replaceLegacyArtwork(custom));
    }
}
