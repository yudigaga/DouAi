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
    @Test public void allFortyFourVariantsUseTheApprovedPaletteAndOneTransparentCanvas() throws Exception {
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
            assertTrue(resources.add(name));
            boolean rainbow=selected && !entry.path.startsWith("feed/");
            int expected=expectedColor(entry.path,selected);
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
                int minX=256,minY=256,maxX=-1,maxY=-1,visible=0,opaque=0;
                for(int y=0;y<256;y++)for(int x=0;x<256;x++) {
                    int color=bitmap.getPixel(x,y);
                    if(Color.alpha(color)==0)continue;
                    visible++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    if(Color.alpha(color)<250)continue;
                    opaque++;
                    // API 28 encodes lossy WebP; antialiased edges are checked through the silhouette instead.
                    if(!rainbow && !nearColor(expected,color))
                        fail("Unexpected artwork color in "+name+": 0x"+Integer.toHexString(color));
                }
                assertTrue("Artwork has solid rather than translucent strokes: "+name,opaque>visible/2);
                assertTrue("No backplate fills the icon edges: "+name+" bounds="+minX+","+minY+"-"+maxX+","+maxY,
                        minX>=12 && minY>=12 && maxX<=243 && maxY<=243);
                assertTrue("Artwork occupancy stays symbol-sized: "+name,visible<256*256*0.65f);
                if(rainbow)assertDiagonalRainbow(name,bitmap,minX,minY,maxX,maxY,opaque);
            }finally{bitmap.recycle();}
        }
        assertEquals(44,resources.size());
    }

    @Test public void selectionChangesTheSilhouetteAtRenderedIconSizes() throws Exception {
        for(BuiltInNavigationIcons.Entry entry:BuiltInNavigationIcons.ENTRIES) {
            int size=entry.path.startsWith("top/")?20:24;
            int[] normal=renderedPixels(entry.path,false,size),selected=renderedPixels(entry.path,true,size);
            int normalCutoff=relativeAlphaCutoff(normal),selectedCutoff=relativeAlphaCutoff(selected);
            int difference=0,union=0;
            for(int i=0;i<normal.length;i++) {
                boolean n=Color.alpha(normal[i])>=normalCutoff,s=Color.alpha(selected[i])>=selectedCutoff;
                if(n || s)union++;
                if(n!=s)difference++;
            }
            assertTrue("Both states need a visible silhouette: "+entry.path,normalCutoff>0 && selectedCutoff>0 && union>0);
            assertTrue("Opacity or color alone is not a distinct "+size+"px silhouette for "+entry.path
                    +" ("+difference+"/"+union+" pixels differ)",difference*10>=union);
        }
    }

    private static int expectedColor(String path,boolean selected) {
        if(!selected)return Color.WHITE;
        if("feed/01-like".equals(path))return 0xFFFF687D;
        if("feed/03-collect".equals(path))return 0xFFFFD475;
        return Color.WHITE;
    }

    private static boolean nearColor(int expected,int actual) {
        return Math.abs(Color.red(expected)-Color.red(actual))<=16
                && Math.abs(Color.green(expected)-Color.green(actual))<=16
                && Math.abs(Color.blue(expected)-Color.blue(actual))<=16;
    }

    private static void assertDiagonalRainbow(String name,Bitmap bitmap,int left,int top,int right,int bottom,int opaque) {
        // Project onto the SVG's top-left to bottom-right axis in its object bounding box.
        int[] stops={0xFFFF697E,0xFFFFD674,0xFF75E7B5,0xFF73C7FF,0xFFC29AFF};
        double[] offsets={0,.25,.50,.76,1};
        int matches=0;
        for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++) {
            int color=bitmap.getPixel(x,y);
            if(Color.alpha(color)<250)continue;
            double t=((x-left)/(double)(right-left)+(y-top)/(double)(bottom-top))/2;
            int upper=1;
            while(t>offsets[upper] && upper<offsets.length-1)upper++;
            int a=stops[upper-1],b=stops[upper];
            double f=(t-offsets[upper-1])/(offsets[upper]-offsets[upper-1]);
            int expected=Color.rgb(blend(Color.red(a),Color.red(b),f),
                    blend(Color.green(a),Color.green(b),f),blend(Color.blue(a),Color.blue(b),f));
            if(nearColor(expected,color))matches++;
        }
        assertTrue("Approved diagonal rainbow survives WebP for "+name+" ("+matches+"/"+opaque+" pixels match)",
                matches>=opaque*.90);
    }

    private static int blend(int a,int b,double f) {
        return (int)Math.round(a+(b-a)*f);
    }

    private static int[] renderedPixels(String path,boolean selected,int size) throws Exception {
        byte[] bytes=BuiltInNavigationIcons.symbolForPath(path,selected);
        Bitmap source=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        assertNotNull(path,source);
        Bitmap small=Bitmap.createScaledBitmap(source,size,size,true);
        try {
            int[] pixels=new int[size*size];
            small.getPixels(pixels,0,size,0,0,size,size);
            return pixels;
        }finally{small.recycle();source.recycle();}
    }

    private static int relativeAlphaCutoff(int[] pixels) {
        int peak=0;
        for(int pixel:pixels)peak=Math.max(peak,Color.alpha(pixel));
        return Math.round(peak*0.65f);
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
