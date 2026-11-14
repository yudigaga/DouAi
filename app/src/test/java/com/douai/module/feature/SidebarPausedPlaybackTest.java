package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public class SidebarPausedPlaybackTest {
    final Object owner=new Object(), holder=new Object(), player=new Object();
    final SidebarPausedPlayback state=new SidebarPausedPlayback();
    @Test public void priorPausedProtectsCloseSettingsAndReturnReadbackButNotNextUserPlay(){
        state.capture(owner,holder,player,"18",true);
        assertFalse(state.guards(owner,holder,player,"18"));
        assertTrue(state.enter(owner,holder,player,"18",10));
        assertTrue(state.guards(owner,holder,player,"18")); // closeDrawer's immediate resume
        assertTrue(state.keep(true,20)); // settings displayed
        assertTrue(state.keep(true,14000));
        assertTrue(state.keep(false,14010)); // dismissal can precede the host's resume callback
        assertTrue(state.guards(owner,holder,player,"18"));
        assertFalse(state.keep(false,14800));
        assertFalse(state.guards(owner,holder,player,"18"));
    }
    @Test public void nativeSidebarAutoPauseIsNeverRetainedAndChangedCardsNeverInheritPause(){
        state.capture(owner,holder,player,"18",false);
        assertFalse(state.enter(owner,holder,player,"18",0));
        state.capture(owner,holder,player,"18",true);
        assertFalse(state.enter(owner,new Object(),player,"18",0));
        state.capture(owner,holder,player,"18",true);
        assertTrue(state.enter(owner,holder,player,"18",0));
        assertFalse(state.guards(owner,holder,player,"19"));
        assertFalse(state.guards(new Object(),holder,player,"18"));
        assertFalse(state.guards(owner,holder,new Object(),"18"));
        state.clear(); // explicit feed touch or leaving the Activity
        assertFalse(state.guards(owner,holder,player,"18"));
    }
    @Test public void missingSettingsWindowNeverLeavesAStickyGuard(){
        state.capture(owner,holder,player,"18",true);
        state.enter(owner,holder,player,"18",0);
        assertFalse(state.keep(false,5000));
        assertFalse(state.guards(owner,holder,player,"18"));
    }
}
