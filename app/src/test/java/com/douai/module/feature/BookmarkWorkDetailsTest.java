package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BookmarkWorkDetailsTest {
    public static final class Author {public String getNickname(){return "林野";}}
    public static final class Work {
        public String getAid(){return "123";}public String getDesc(){return "秋天的山路";}public Author getAuthor(){return new Author();}
    }
    @Test public void foreignWorkCannotHydrateAndExistingMetadataIsBounded(){
        assertNull(BookmarkWorkDetails.current(new Work(),"124"));
        var actual=BookmarkWorkDetails.current(new Work(),"123");
        assertEquals("林野",actual.author);assertEquals("秋天的山路",actual.description);assertTrue(actual.covers.isEmpty());
        var longText=new BookmarkWorkDetails("a".repeat(100),"b".repeat(400),Collections.emptyList());
        assertEquals(PlaybackBookmarkStore.MAX_AUTHOR,longText.author.length());assertEquals(PlaybackBookmarkStore.MAX_DESCRIPTION,longText.description.length());
    }
    @Test public void cacheEncodesAPortableSmallProportionalJpegWithoutRecyclingCallerBitmap(){
        Bitmap source=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888);source.eraseColor(0xff4c7891);
        byte[] jpeg=BookmarkWorkDetails.thumbnail(source);assertNotNull(jpeg);assertFalse(source.isRecycled());
        assertTrue(jpeg.length<=PlaybackBookmarkStore.MAX_COVER_BYTES);
        var read=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        assertNotNull(read);assertEquals(128,read.getHeight());assertEquals(85,read.getWidth());
        read.recycle();source.recycle();
    }
}
