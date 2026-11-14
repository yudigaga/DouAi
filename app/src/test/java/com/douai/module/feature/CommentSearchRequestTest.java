package com.douai.module.feature;

import static com.douai.module.feature.CommentSearchRequest.Step.*;
import static org.junit.Assert.*;
import org.junit.Test;

public class CommentSearchRequestTest {
    private final CommentSearchRequest requests=new CommentSearchRequest();
    private final Object screen=new Object(),panel=new Object();
    @Test public void opensOnceAndWaitsForVisiblePanelWithActualRowsOrConfirmedEmpty() {
        var r=requests.begin("11","22",screen,100);
        assertEquals(OPEN_COMMENTS,requests.step(r,"11",true,101,null,false));
        requests.opened(r,true);
        assertEquals(WAIT,requests.step(r,"11",true,200,null,false));
        assertEquals(WAIT,requests.step(r,"11",true,300,panel,false));
        assertEquals(SHOW,requests.step(r,"11",true,400,panel,true));
        assertEquals(STALE,requests.step(r,"11",true,401,panel,true));
        var already=requests.begin("11","22",screen,900);
        assertEquals(SHOW,requests.step(already,"11",true,900,panel,true));
    }
    @Test public void repeatedTapWhileOpeningKeepsOneOpenerAndOnlyLatestCanShow() {
        var old=requests.begin("11","22",screen,0);
        assertEquals(OPEN_COMMENTS,requests.step(old,"11",true,0,null,false));
        requests.opened(old,true);
        var latest=requests.begin("11","22",screen,500);
        assertEquals(STALE,requests.step(old,"11",true,501,panel,true));
        assertEquals(WAIT,requests.step(latest,"11",true,501,null,false));
        assertEquals(SHOW,requests.step(latest,"11",true,700,panel,true));
        var a=requests.begin("11","22",screen,701);
        var b=requests.begin("11","33",screen,702);
        assertEquals(STALE,requests.step(a,"11",true,703,panel,true));
        assertEquals(OPEN_COMMENTS,requests.step(b,"11",true,703,null,false));
    }
    @Test public void leaveCloseAccountSwitchAndTimeoutCannotOpenAStaleDialog() {
        var leave=requests.begin("11","22",screen,0);
        assertEquals(CANCEL,requests.step(leave,"11",false,10,panel,true));
        var switched=requests.begin("11","22",screen,100);
        assertEquals(CANCEL,requests.step(switched,"other",true,101,panel,true));
        var close=requests.begin("11","22",screen,200);
        assertEquals(WAIT,requests.step(close,"11",true,201,panel,false));
        assertEquals(CANCEL,requests.step(close,"11",true,202,new Object(),true));
        var timed=requests.begin("11","22",screen,1000);
        assertEquals(TIMEOUT,requests.step(timed,"11",true,9000,panel,true));
        var retry=requests.begin("11","22",screen,9100);
        assertEquals(OPEN_COMMENTS,requests.step(retry,"11",true,9100,null,false));
        requests.cancel();
        assertEquals(STALE,requests.step(retry,"11",true,9101,panel,true));
    }
    @Test public void closingPanelBetweenOnShowAndNextPollCancelsOnlyItsOwnScreenAndWork() {
        Object oldPanel=new Object();
        var first=requests.begin("11","21",screen,0);
        assertEquals(OPEN_COMMENTS,requests.step(first,"11",true,0,null,false));
        var latest=requests.begin("11","22",screen,10);
        requests.bind("11","21",screen,oldPanel);
        requests.bind("11","22",new Object(),oldPanel);
        requests.bind("99","22",screen,oldPanel);
        assertFalse(requests.bound(oldPanel));
        requests.bind("11","22",screen,panel);
        assertTrue(requests.bound(panel));
        assertEquals(WAIT,requests.step(latest,"11",true,11,panel,false));
        requests.cancel();
        assertEquals(STALE,requests.step(latest,"11",true,12,panel,true));
    }
    @Test public void failedOpenerIsImmediatelyRetryableAndLateResultCannotCancelRetry(){
        var a=requests.begin("11","22",screen,0);
        assertEquals(OPEN_COMMENTS,requests.step(a,"11",true,0,null,false));
        requests.opened(a,false);
        assertFalse(requests.pending(a));
        var b=requests.begin("11","22",screen,1);
        assertEquals(OPEN_COMMENTS,requests.step(b,"11",true,1,null,false));
        requests.opened(b,true);requests.opened(a,false);
        assertEquals(WAIT,requests.step(b,"11",true,2,null,false));
    }
}
