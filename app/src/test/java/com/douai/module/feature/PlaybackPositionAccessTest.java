package com.douai.module.feature;

import static org.junit.Assert.*;
import org.junit.Test;

public class PlaybackPositionAccessTest {
    @Test public void seekUsesPercentAndClampsWithoutReachingNextCard() throws Exception {
        Fake player = new Fake();
        PlaybackPositionAccess.Snapshot p = new PlaybackPositionAccess.Snapshot("10",12_000,30_000,player);
        assertTrue(PlaybackPositionAccess.seek(p,p,2_000, Fake.class.getMethod("seek",float.class)));
        assertEquals(6.6667, player.last, .001);
        assertEquals(0f,PlaybackPositionAccess.percent(-8000,30000),0);
        assertTrue(PlaybackPositionAccess.percent(50000,30000)<100f);
        assertFalse(PlaybackPositionAccess.seek(p,new PlaybackPositionAccess.Snapshot("11",0,30000,player),
                1000,Fake.class.getMethod("seek",float.class)));
        assertFalse(PlaybackPositionAccess.seek(p,new PlaybackPositionAccess.Snapshot("10",0,30000,new Fake()),
                1000,Fake.class.getMethod("seek",float.class)));
        assertFalse(PlaybackPositionAccess.seek(p,null,1000,Fake.class.getMethod("seek",float.class)));
        assertEquals(1,player.calls);
        assertTrue(Float.isNaN(PlaybackPositionAccess.percent(10,0)));
    }
    public static class Fake { int calls; float last; public void seek(float percent){calls++;last=percent;} }
}
