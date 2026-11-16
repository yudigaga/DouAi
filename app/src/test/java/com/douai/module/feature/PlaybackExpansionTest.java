package com.douai.module.feature;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackExpansionTest {
 @Test public void loopWaitsForReadbackAndNeverSeeksPaused(){
  PlaybackLoopController c=new PlaybackLoopController(); Object p=new Object();
  assertTrue(c.markA("1",new PlaybackPositionAccess.Snapshot("2",30000,60000,p)));
  assertTrue(c.markB("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,p)));
  assertEquals(-1,c.target("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,p),true,0));
  assertEquals(30000,c.target("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,p),false,1));
  assertEquals(-1,c.target("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,p),false,2));
  c.onExplicitSeek("1","2",50000); assertFalse(c.isActiveFor("1","2",p));
 }
 @Test public void failedLoopReadbackStopsInsteadOfRetrying(){
  PlaybackLoopController c=new PlaybackLoopController();Object p=new Object();
  c.markA("1",new PlaybackPositionAccess.Snapshot("2",30000,60000,p));c.markB("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,p));
  assertEquals(30000,c.target("1",new PlaybackPositionAccess.Snapshot("2",41000,60000,p),false,1));
  assertEquals(-1,c.target("1",new PlaybackPositionAccess.Snapshot("2",45000,60000,p),false,2001));assertFalse(c.isActiveFor("1","2",p));
 }
 @Test public void loopRejectsShortIntervalAndAnotherPlayer(){
  PlaybackLoopController c=new PlaybackLoopController();Object p=new Object();c.markA("1",new PlaybackPositionAccess.Snapshot("2",30000,60000,p));
  assertFalse(c.markB("1",new PlaybackPositionAccess.Snapshot("2",30500,60000,p)));
  assertFalse(c.markB("1",new PlaybackPositionAccess.Snapshot("2",40000,60000,new Object())));
 }
 @Test public void resumeEligibilityUsesAllDurationThresholds(){
  assertTrue(ResumePlaybackController.eligible(new ResumeProgressStore.Entry("2",10000,60000,0)));
  assertFalse(ResumePlaybackController.eligible(new ResumeProgressStore.Entry("2",10000,59999,0)));
  assertFalse(ResumePlaybackController.eligible(new ResumeProgressStore.Entry("2",9000,60000,0)));
  assertFalse(ResumePlaybackController.eligible(new ResumeProgressStore.Entry("2",50000,60000,0)));
 }
 @Test public void completionBudgetCountsUniqueWorksAndExpiresOnce(){
  PlaybackStopBudget b=new PlaybackStopBudget(); b.startCount("1",2);
  assertFalse(b.onVerifiedCompletion("1","2",1)); assertFalse(b.onVerifiedCompletion("1","2",2));
  assertTrue(b.onVerifiedCompletion("1","3",3)); assertFalse(b.onVerifiedCompletion("1","4",4));
  b.startTimer("1",15,100); assertFalse(b.expire("1",900099)); assertTrue(b.expire("1",900100)); assertFalse(b.expire("1",900101));
 }
 @Test public void explicitSeekConsumesPendingResume(){
  ResumePlaybackController c=new ResumePlaybackController();c.enter("1","2",0);c.cancel();
  assertFalse(c.pending("1","2",100));c.enter("1","2",200);assertFalse(c.pending("1","2",300));
  c.enter("1","3",400);assertTrue(c.pending("1","3",500));assertFalse(c.pending("1","3",8400));
 }
}
