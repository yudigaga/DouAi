package com.douai.module.feature;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.io.File;
import org.junit.Test;
public class CommentBookmarkStoreTest {
 @Test public void deduplicatesPerAccountAndPreservesCorruptFile() throws Exception {
  File dir=Files.createTempDirectory("comments").toFile();CommentBookmarkStore s=new CommentBookmarkStore(dir);
  CommentBookmarkStore.Entry e=new CommentBookmarkStore.Entry("21","31","9","甲","你好","","","","","","",1,false);
  s.add("11",e);s.add("11",e);assertEquals(1,s.list("11").size());assertEquals(0,s.list("12").size());
  try{s.add("",e);fail();}catch(IllegalArgumentException expected){}
  File f=new File(dir,"11.localV1");byte[] bad={1,2,3};Files.write(f.toPath(),bad);
  try{s.add("11",e);fail();}catch(java.io.IOException expected){}
  assertArrayEquals(bad,Files.readAllBytes(f.toPath()));
 }
 @Test public void oversizedRowDoesNotReplaceExistingData() throws Exception {
  File dir=Files.createTempDirectory("comments").toFile();CommentBookmarkStore s=new CommentBookmarkStore(dir);
  s.add("11",new CommentBookmarkStore.Entry("21","31","","","one","","","","","","",1,false));
  byte[] before=Files.readAllBytes(new File(dir,"11.localV1").toPath());
  try{s.add("11",new CommentBookmarkStore.Entry("21","32","","","中".repeat(6000),"","","","","","",1,false));fail();}
  catch(java.io.IOException expected){}
  assertArrayEquals(before,Files.readAllBytes(new File(dir,"11.localV1").toPath()));
 }
 @Test public void fullStoreRejectsExtraButAllowsDedupeAndStaleOwnerCannotCommit() throws Exception {
  File dir=Files.createTempDirectory("comments").toFile();CommentBookmarkStore s=new CommentBookmarkStore(dir);
  for(int i=1;i<=500;i++)s.add("11",new CommentBookmarkStore.Entry("21",""+i,"","","body","","","","","","",1,false));
  s.add("11",new CommentBookmarkStore.Entry("21","1","","","updated","","","","","","",2,false));
  assertEquals(500,s.list("11").size());
  try{s.add("11",new CommentBookmarkStore.Entry("21","501","","","extra","","","","","","",2,false));fail();}catch(java.io.IOException expected){}
  CommentBookmarkStore stale=new CommentBookmarkStore(dir,()->false);
  try{stale.remove("11","21","1");fail();}catch(java.io.IOException expected){}
  assertEquals(500,new CommentBookmarkStore(dir).list("11").size());
 }
}
