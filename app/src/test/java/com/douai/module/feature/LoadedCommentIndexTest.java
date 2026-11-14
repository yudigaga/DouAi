package com.douai.module.feature;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class LoadedCommentIndexTest {
    private static LoadedCommentIndex.Row row(String id, String root, String text, String asr, int pos) {
        return new LoadedCommentIndex.Row(id, root, text, asr, pos);
    }
    @Test public void refreshCollapseOverlapAndTranscript() {
        LoadedCommentIndex ix = new LoadedCommentIndex();
        long token = ix.open("11","21");
        ix.update(token, Arrays.asList(row("1","","same","",1), row("2","","same","",2),
                row("1","","same","",3), row("3","1","","Quiet voice",4)), false);
        assertEquals(3, ix.size()); assertEquals(2,ix.find("same").size());
        assertEquals("3",ix.find("QUIET").get(0).id);
        ix.update(token, Arrays.asList(row("1","","same","",0),row("2","","same","",1)), false);
        assertEquals(-1,ix.find("quiet").get(0).position);
        ix.refresh(token);
        ix.update(token, Collections.singletonList(row("2","","same","",0)), false);
        assertTrue(ix.find("quiet").isEmpty()); assertEquals(1, ix.size());
    }
    @Test public void futurePanelRefusesPriorRowsAndMarksPartialCoverage() {
        LoadedCommentIndex ix = new LoadedCommentIndex();
        long a=ix.open("11","21"), b=ix.open("12","22");
        ix.update(b, Collections.singletonList(row("2","","b","",0)), true);
        ix.update(a, Collections.singletonList(row("1","","a","",0)), false);
        assertTrue(ix.partial()); assertTrue(ix.find("a").isEmpty());
        ix.close(); ix.update(b, Collections.singletonList(row("2","","b","",0)), false);
        assertEquals(0,ix.size());
    }
    @Test public void capacityAndRootRemovalNeverResurrectOldText() {
        LoadedCommentIndex ix=new LoadedCommentIndex(); long token=ix.open("1","2");
        java.util.List<LoadedCommentIndex.Row> rows=new java.util.ArrayList<>();
        for(int i=1;i<=LoadedCommentIndex.LIMIT+1;i++)rows.add(row(""+i,"","text","",i));
        ix.update(token,rows,false); assertTrue(ix.partial());assertEquals(LoadedCommentIndex.LIMIT,ix.size());
        ix.update(token,Arrays.asList(row("1","","one","",1),row("3","1","reply","",2)),false);
        ix.update(token,Collections.singletonList(row("9","","nine","",1)),false);
        assertTrue(ix.find("reply").isEmpty());
    }
}
