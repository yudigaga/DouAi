package com.douai.module.feature;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
public class AuthorSpeedRuleStoreTest {
 @Test public void priorityIncludesExplicitOneAndSyntheticBaseline(){
  assertEquals(1.5f,AuthorSpeedRuleStore.choose(null,1.5f,2f,true),0);
  assertEquals(1f,AuthorSpeedRuleStore.choose(null,1f,2f,true),0);
  assertEquals(2f,AuthorSpeedRuleStore.choose(null,null,2f,true),0);
  assertEquals(1f,AuthorSpeedRuleStore.choose(1f,1.5f,2f,true),0);
  assertEquals(1f,AuthorSpeedRuleStore.choose(null,null,null,true),0);
  assertNull(AuthorSpeedRuleStore.choose(null,null,null,false));
 }
 @Test public void storesByAccountAndUidNotDisplayName()throws Exception{
  var s=new AuthorSpeedRuleStore(Files.createTempDirectory("author-speed").toFile());
  s.put("1",new AuthorSpeedRuleStore.Rule("10","same",1.5f));s.put("1",new AuthorSpeedRuleStore.Rule("20","same",1f));
  assertEquals(2,s.list("1").size()); assertTrue(s.list("2").isEmpty());
  s.remove("1","10");assertEquals(1f,s.list("1").get(0).speed,0);
  try{s.put("1",new AuthorSpeedRuleStore.Rule("30","bad",1.6f));fail();}catch(IllegalArgumentException expected){}
  assertEquals(1,s.list("1").size());s.clear("1");assertTrue(s.list("1").isEmpty());
 }
 @Test public void boundedStoreRejectsOverflowWithoutLosingPreviousRules()throws Exception{
  var s=new AuthorSpeedRuleStore(Files.createTempDirectory("author-cap").toFile());
  for(int i=0;i<500;i++)s.put("1",new AuthorSpeedRuleStore.Rule(String.valueOf(i),"n",1f));
  try{s.put("1",new AuthorSpeedRuleStore.Rule("501","overflow",2f));fail();}catch(java.io.IOException expected){}
  assertEquals(500,s.list("1").size());s.put("1",new AuthorSpeedRuleStore.Rule("1","edited",2f));assertEquals(500,s.list("1").size());
 }
 @Test public void corruptVersionIsNeverOverwrittenByAnUpsert()throws Exception{
  var dir=Files.createTempDirectory("author-corrupt");var bytes=new byte[]{1,2,3,4};Files.write(dir.resolve("1.speed"),bytes);
  var s=new AuthorSpeedRuleStore(dir.toFile());
  try{s.put("1",new AuthorSpeedRuleStore.Rule("10","n",1f));fail();}catch(java.io.IOException expected){}
  assertArrayEquals(bytes,Files.readAllBytes(dir.resolve("1.speed")));
  try{s.list("../1");fail();}catch(IllegalArgumentException expected){}
 }
}
