package com.douai.module.feature;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class ResumeProgressStoreTest {
 @Rule public TemporaryFolder dir=new TemporaryFolder();
 @Test public void accountIsolationClearRejectsQueuedWrites()throws Exception {
  ResumeProgressStore s=new ResumeProgressStore(dir.newFolder()); long epoch=s.epoch("1");
  s.save("1",new ResumeProgressStore.Entry("2",20000,100000,100),epoch,100);
  assertNotNull(s.get("1","2",100));assertNull(s.get("3","2",100));
  s.clear("1");s.save("1",new ResumeProgressStore.Entry("2",20000,100000,100),epoch,100);
  assertNull(s.get("1","2",100));
 }
 @Test public void capacityEvictsOldestEntryWithoutMixingAccounts()throws Exception {
  java.io.File folder=dir.newFolder();
  try(java.io.DataOutputStream out=new java.io.DataOutputStream(new java.io.FileOutputStream(new java.io.File(folder,"1.resume")))) {
   out.writeInt(0x44525031);out.writeInt(2000);
   for(int i=0;i<2000;i++){out.writeUTF(Integer.toString(10000+i));out.writeLong(20000);out.writeLong(100000);out.writeLong(i);}
  }
  ResumeProgressStore s=new ResumeProgressStore(folder);s.save("1",new ResumeProgressStore.Entry("2",20000,100000,3000),0,3000);
  assertNull(s.get("1","10000",3000));assertNotNull(s.get("1","10001",3000));assertNotNull(s.get("1","2",3000));
  try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(new java.io.File(folder,"1.resume")))) {in.readInt();assertEquals(2000,in.readInt());}
 }
 @Test public void expiredProgressIsNotReturned()throws Exception {
  ResumeProgressStore s=new ResumeProgressStore(dir.newFolder());s.save("1",new ResumeProgressStore.Entry("2",20000,100000,100),0,100);
  assertNotNull(s.get("1","2",100));assertNull(s.get("1","2",100+30L*86400000));
 }
}
