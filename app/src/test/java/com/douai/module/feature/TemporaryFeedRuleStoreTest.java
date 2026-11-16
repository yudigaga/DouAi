package com.douai.module.feature;

import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TemporaryFeedRuleStoreTest {
    @Rule public TemporaryFolder directory=new TemporaryFolder();
    @Test public void accountFilesRoundTripExactIdsAndExpiryNeverRenewsOnRead()throws Exception{
        TemporaryFeedRuleStore s=new TemporaryFeedRuleStore(directory.getRoot());
        s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","同名",100,200),100);
        s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.MUSIC,"22","配乐",100,300),100);
        assertEquals(2,s.list("1",150).size());assertEquals(0,s.list("2",150).size());
        assertTrue(TemporaryFeedRuleStore.matches(s.list("1",150),"11","0",150));
        assertFalse(TemporaryFeedRuleStore.matches(s.list("1",150),"12","0",150));
        assertFalse(TemporaryFeedRuleStore.matches(s.list("1",200),"11","0",200));
        assertEquals(1,new TemporaryFeedRuleStore(directory.getRoot()).list("1",200).size());
        assertEquals(0,s.list("1",300).size());
    }
    @Test public void upsertRemoveAndClearAreAccountLocal()throws Exception{
        TemporaryFeedRuleStore s=new TemporaryFeedRuleStore(directory.getRoot());
        s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","旧",100,200),100);
        s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","新",100,300),100);
        s.put("2",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","另一账号",100,300),100);
        assertEquals(1,s.list("1",150).size());assertEquals("新",s.list("1",150).get(0).name);
        s.remove("1","AUTHOR:11",150);assertTrue(s.list("1",150).isEmpty());
        s.clear("1");assertEquals(1,s.list("2",150).size());
    }
    @Test public void invalidIdsAndExpiredRulesCannotBeSaved()throws Exception{
        TemporaryFeedRuleStore s=new TemporaryFeedRuleStore(directory.getRoot());
        assertThrows(IllegalArgumentException.class,()->s.put("../1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","",1,200),100));
        assertThrows(IllegalArgumentException.class,()->s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.MUSIC,"0","",1,200),100));
        assertThrows(IllegalArgumentException.class,()->s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","",1,100),100));
    }
    @Test public void corruptExistingFileIsNotOverwrittenByMutation()throws Exception{
        TemporaryFeedRuleStore s=new TemporaryFeedRuleStore(directory.getRoot());
        s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"11","",1,200),100);
        assertEquals(1,directory.getRoot().listFiles().length);
        var path=directory.getRoot().listFiles()[0].toPath();Files.write(path,new byte[]{1,2,3});
        assertThrows(java.io.IOException.class,()->s.put("1",new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"12","",1,200),100));
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(path));
        s.clear("1");assertTrue(s.list("1",100).isEmpty());
    }
}
