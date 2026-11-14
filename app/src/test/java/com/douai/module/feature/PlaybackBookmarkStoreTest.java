package com.douai.module.feature;

import static org.junit.Assert.*;
import java.io.File;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.StringJoiner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PlaybackBookmarkStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    /** Mirrors the shipped V1 reader, independent of the current storage implementation. */
    private static String oldReader(File file)throws IOException{
        try(var in=new DataInputStream(new FileInputStream(file))){
            assertEquals(0x44504231,in.readInt());int count=in.readInt();StringJoiner lines=new StringJoiner("|");
            for(int i=0;i<count;i++){in.readUTF();String aid=in.readUTF();long ms=in.readLong();String memo=in.readUTF();
                lines.add(aid+"/"+ms+"/"+memo+"/"+in.readLong());}
            assertEquals("legacy reader sees no appended metadata or trailing fields",-1,in.read());return lines.toString();
        }
    }
    @Test public void oldReleaseCanAlwaysReadBookmarksEvenWhenOptionalMetadataCannotBeSaved()throws Exception{
        File dir=temporary.newFolder();var store=new PlaybackBookmarkStore(dir);File primary=new File(dir,"3.bookmarks");
        var first=store.add("3","5",400,"笔记",31,"作者","描述");
        assertEquals("5/400/笔记/31",oldReader(primary));
        assertTrue(store.edit("3",first.id,"新备注"));assertEquals("5/400/新备注/31",oldReader(primary));
        File blocked=new File(dir,"3.bookmark-details");Files.deleteIfExists(blocked.toPath());assertTrue(blocked.mkdir());
        Files.writeString(new File(blocked,"sentinel").toPath(),"occupied");
        store.add("3","6",500,"另一个",33,"B","作品说明");
        assertEquals("6/500/另一个/33|5/400/新备注/31",oldReader(primary));
        assertTrue(store.list("3","另一个").get(0).description.isEmpty());
        assertTrue(store.remove("3",first.id));assertEquals("6/500/另一个/33",oldReader(primary));
    }
    @Test public void oldFileIsReadWithoutRewriteAndVerifiedVisitEnrichesAllMatchingRowsOnly()throws Exception{
        File dir=temporary.newFolder(),file=new File(dir,"55.bookmarks"); String key=UUID.randomUUID().toString();
        try(var out=new DataOutputStream(new FileOutputStream(file))){
            out.writeInt(0x44504231);out.writeInt(1);out.writeUTF(key);out.writeUTF("123");out.writeLong(3300);out.writeUTF("笔记");out.writeLong(12);
        }
        byte[] before=Files.readAllBytes(file.toPath());var store=new PlaybackBookmarkStore(dir);
        var old=store.list("55","笔记").get(0);
        assertEquals("",old.author);assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        store.add("55","123",5500,"",13,"","");
        store.add("66","123",9000,"",14,"","");
        assertTrue(store.enrich("55","123","阿冬","关于调色的示例"));
        assertEquals(2,store.list("55","阿冬").size());assertEquals(2,store.list("55","调色").size());
        assertTrue(store.list("66","调色").isEmpty());
        var reopened=new PlaybackBookmarkStore(dir).list("55","笔记").get(0);
        assertEquals(key,reopened.id);assertEquals(12,reopened.created);assertEquals(3300,reopened.millis);
        assertFalse(store.enrich("55","123","覆盖现有昵称","覆盖现有文案"));
    }
    @Test public void unknownVersionCannotOverwriteOrRecreateOriginalAndNoLateThumbnailAfterLastDelete()throws Exception{
        File dir=temporary.newFolder();var store=new PlaybackBookmarkStore(dir);
        var first=store.add("55","9",1,"",1,"作者","说明");var second=store.add("55","9",2,"",2);
        byte[] jpg={1,2,3};
        assertTrue(store.saveCoverIfReferenced("55","9",jpg));File cover=store.cover("55","9");assertTrue(cover.isFile());
        assertFalse(store.cover("56","9").isFile());assertTrue(store.remove("55",first.id));assertTrue(cover.isFile());
        assertTrue(store.remove("55",second.id));assertFalse(cover.exists());
        assertFalse(new File(dir,"55.bookmark-details").exists());
        assertFalse(store.saveCoverIfReferenced("55","9",jpg));assertFalse(store.enrich("55","9","迟到","迟到"));
        assertFalse(cover.exists());assertTrue(store.list("55","").isEmpty());
        File file=new File(dir,"55.bookmarks");byte[] corrupt={13,12,11,10,0,0,0,0};Files.write(file.toPath(),corrupt);
        try{store.add("55","9",5,"",4);fail("unknown file format");}catch(IOException expected){}
        assertArrayEquals(corrupt,Files.readAllBytes(file.toPath()));
    }
    @Test public void multipleTimesSurviveRestartAndOtherAccountsCannotEdit() throws Exception {
        File dir=temporary.newFolder();
        PlaybackBookmarkStore store = new PlaybackBookmarkStore(dir);
        var a=store.add("55","99",11000,"调色",17);
        var b=store.add("55","99",24000,"调色参数",18);
        var reopen = new PlaybackBookmarkStore(dir);
        assertEquals(b.id,reopen.list("55","参数").get(0).id);
        assertTrue(reopen.list("56","").isEmpty());
        assertFalse(reopen.edit("56",a.id,"改名"));
        assertTrue(reopen.edit("55",a.id,"回听"));
        assertTrue(reopen.remove("55",b.id));
        assertEquals(11000,store.list("55","回听").get(0).millis);
        assertEquals(1,store.list("55","").size());
    }
    @Test public void pendingIsSingleUseAndOnlyNewestMayConsume() {
        BookmarkJump jump=new BookmarkJump();
        long a=jump.start("2","3",100,1000), b=jump.start("2","4",500,1001);
        assertEquals(-1,jump.consume(a,"2","3",1002));
        assertEquals(-1,jump.consume(b,"2","3",1002));
        assertEquals(500,jump.consume(b,"2","4",1003));
        assertEquals(-1,jump.consume(b,"2","4",1003));
        long c=jump.start("2","3",600,2000);
        assertEquals(-1,jump.consume(c,"5","3",2001));
        assertEquals(-1,jump.consume(c,"2","3",2002));
        long d=jump.start("5","3",600,3000);
        assertEquals(-1,jump.consume(d,"5","3",80000));
        long e=jump.start("2","3",650,4000);
        jump.cancel(e);
        assertEquals(-1,jump.consume(e,"2","3",4100));
    }
}
