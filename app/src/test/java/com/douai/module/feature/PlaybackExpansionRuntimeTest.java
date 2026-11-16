package com.douai.module.feature;
import android.app.Activity;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaybackExpansionRuntimeTest {
 NativeFollowLists accounts; PlaybackExpansionRuntime runtime; Fake host; Object holder=new Object();
 @Before public void setup()throws Exception {
  accounts=new NativeFollowLists(()->new NativeFollowLists.Account("1","sec"),(kind,q)->{throw new IllegalStateException();},null);
  HostTargets.field(NativeFollowLists.class,"active").set(null,accounts);host=new Fake();
  runtime=new PlaybackExpansionRuntime(host,new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(),"resume-test"));
  runtime.enter("2",holder,host.owner);
 }
 @After public void close()throws Exception{runtime.close();accounts.close();HostTargets.field(NativeFollowLists.class,"active").set(null,null);}
 void loop(){host.position=30000;runtime.action("loop_a");host.position=60000;runtime.action("loop_b");}
 @Test public void terminalCompletionLoopsOnceEvenWithoutFinalProgress(){
  loop();runtime.budget.startCount("1",1);host.position=59900;runtime.progress();
  assertEquals(0,host.seeks);host.ended=true;assertTrue(runtime.completion("2",1));assertEquals(1,host.seeks);assertEquals(30000,host.target);
  host.position=60000;runtime.progress();runtime.completion("2",1);assertEquals(1,host.seeks);assertEquals("剩余 1 条",runtime.budget.status(0));
 }
 @Test public void minimumOneSecondLoopRepeatsAfterReadbackWithoutDuplicateTerminalSeeks(){
  host.position=30000;runtime.action("loop_a");host.position=31000;runtime.action("loop_b");
  runtime.progress();assertEquals(1,host.seeks);assertEquals(30000,host.target);
  runtime.completion("2",1);runtime.progress();assertEquals(1,host.seeks);
  host.position=30000;runtime.progress();
  host.position=30500;runtime.progress();
  host.paused=true;host.position=31000;runtime.progress();runtime.completion("2",1);assertEquals(1,host.seeks);
  host.paused=false;runtime.progress();assertEquals(2,host.seeks);assertEquals(30000,host.target);
  runtime.completion("2",1);runtime.progress();assertEquals(2,host.seeks);
  host.position=30000;runtime.progress();host.position=30500;runtime.progress();
  host.position=31000;runtime.completion("2",1);assertEquals(3,host.seeks);
  runtime.completion("2",1);runtime.progress();assertEquals(3,host.seeks);
 }
 @Test public void confirmedReadbackRearmsEvenWhenNextProgressIsAlreadyAtB(){
  host.position=30000;runtime.action("loop_a");host.position=31000;runtime.action("loop_b");
  runtime.progress();assertEquals(1,host.seeks);
  host.position=30000;runtime.progress();host.position=31000;runtime.progress();assertEquals(2,host.seeks);
  runtime.completion("2",1);assertEquals(2,host.seeks);
 }
 @Test public void explicitPausedTerminalDoesNotSeek(){loop();host.paused=true;assertTrue(runtime.completion("2",1));assertEquals(0,host.seeks);}
 @Test public void stalePlayingDoesNotReleaseStoppedBudget(){
  runtime.budget.startCount("1",1);long old=runtime.stopGeneration();runtime.completion("2",1);
  host.paused=false;runtime.playing(old);assertTrue(runtime.blocks("2"));
  long current=runtime.stopGeneration();runtime.playing(current);assertTrue(runtime.blocks("2"));
  host.paused=true;runtime.paused(current);host.paused=false;runtime.playing(current);assertFalse(runtime.blocks("2"));
 }
 @Test public void sameCardReturnPreservesLoopButNewHolderOfSameAidCancels(){
  loop();runtime.suspend();runtime.enter("2",holder,host.owner);assertTrue(runtime.loop.isActiveFor("1","2",host.player));
  runtime.enter("2",new Object(),host.owner);assertFalse(runtime.loop.isActiveFor("1","2",host.player));
 }
 @Test public void configurationReplacementCancelsTransientSessionsWithoutPlayingOrDeletingData(){
  loop();runtime.budget.startCount("1",5);runtime.resume.reset();runtime.resume.enter("1","2",0);
  runtime.configurationReplaced();
  assertFalse(runtime.loop.isActiveFor("1","2",host.player));assertFalse(runtime.budget.isActive());
  assertFalse(runtime.resume.pending("1","2",1));assertEquals(0,host.seeks);assertFalse(host.paused);
 }
 private void awaitResumeIo() throws Exception {
  java.util.concurrent.ExecutorService io=(java.util.concurrent.ExecutorService)HostTargets.field(PlaybackExpansionRuntime.class,"io").get(runtime);
  io.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
 }
 private ResumeProgressStore resumeStore() throws Exception {return (ResumeProgressStore)HostTargets.field(PlaybackExpansionRuntime.class,"store").get(runtime);}
 @Test public void sameAidNewHolderFlushesOldVerifiedPositionBeforeReadingResume() throws Exception {
  host.mode="auto";host.position=40000;runtime.progress();awaitResumeIo();
  assertEquals(40000,resumeStore().get("1","2",System.currentTimeMillis()).positionMs);
  host.player=new Object();host.position=0;runtime.enter("2",new Object(),host.owner);awaitResumeIo();
  assertEquals(40000,resumeStore().get("1","2",System.currentTimeMillis()).positionMs);
  org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(1,host.seeks);assertEquals(40000,host.target);
 }
 @Test public void sameAidNewHolderWithoutOldSnapshotPreservesExistingResumeFile() throws Exception {
  long now=System.currentTimeMillis();resumeStore().save("1",new ResumeProgressStore.Entry("2",40000,60000,now),resumeStore().epoch("1"),now);
  host.mode="auto";host.player=new Object();host.position=0;runtime.enter("2",new Object(),host.owner);awaitResumeIo();
  assertEquals(40000,resumeStore().get("1","2",System.currentTimeMillis()).positionMs);
  org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(1,host.seeks);assertEquals(40000,host.target);
 }
 private void startTimer(){runtime.budget.startTimer("1",15,android.os.SystemClock.elapsedRealtime());}
 private void elapseSixteenMinutes(){org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(16));}
 @Test public void delayedBackgroundTimerDoesNotPauseSameCardOnReturn(){
  startTimer();runtime.suspend();host.foreground=false;elapseSixteenMinutes();
  host.foreground=true;runtime.enter("2",holder,host.owner);runtime.progress();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  assertEquals(0,host.pauseCalls);assertFalse(runtime.budget.isActive());assertFalse(runtime.blocks("2"));
 }
 @Test public void delayedBackgroundTimerDoesNotPauseNewHolderOnReturn(){
  startTimer();runtime.suspend();host.foreground=false;elapseSixteenMinutes();
  host.foreground=true;host.player=new Object();runtime.enter("2",new Object(),host.owner);runtime.progress();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  assertEquals(0,host.pauseCalls);assertFalse(runtime.budget.isActive());assertFalse(runtime.blocks("2"));
 }
 @Test public void backgroundDeliveredExpirationDoesNotCreateStopLatch(){
  startTimer();runtime.suspend();host.foreground=false;elapseSixteenMinutes();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();assertFalse(runtime.budget.isActive());
  host.foreground=true;runtime.enter("2",holder,host.owner);runtime.progress();
  assertEquals(0,host.pauseCalls);assertFalse(runtime.blocks("2"));
 }
 @Test public void existingForegroundStopLatchSurvivesBackgroundReturn(){
  runtime.budget.startCount("1",1);long before=runtime.stopGeneration();runtime.completion("2",1);
  assertEquals(1,host.pauseCalls);runtime.suspend();host.foreground=false;elapseSixteenMinutes();
  org.robolectric.shadows.ShadowLooper.idleMainLooper();
  host.foreground=true;runtime.enter("2",holder,host.owner);host.paused=false;runtime.playing(before);assertTrue(runtime.blocks("2"));
  host.paused=true;runtime.paused(runtime.stopGeneration());host.paused=false;runtime.playing(runtime.stopGeneration());assertFalse(runtime.blocks("2"));
 }
 @Test public void alreadyPausedTimerStopAllowsFreshVerifiedPlayWithoutDuplicatePauseNotification(){
  host.paused=true;startTimer();long before=runtime.stopGeneration();elapseSixteenMinutes();runtime.progress();
  assertTrue(runtime.blocks("2"));host.paused=false;runtime.playing(before);assertTrue(runtime.blocks("2"));
  runtime.playing(runtime.stopGeneration());assertFalse(runtime.blocks("2"));
 }
 static class Fake implements PlaybackExpansionRuntime.Host {
  Activity owner=Robolectric.buildActivity(Activity.class).setup().get();Object player=new Object();long position=10000,target;int seeks,pauseCalls;boolean paused,ended;boolean foreground=true;String mode="off";
  public PlaybackPositionAccess.Snapshot snapshot(){return foreground?new PlaybackPositionAccess.Snapshot("2",position,60000,player):null;}
  public Activity owner(){return foreground?owner:null;}public String mode(){return mode;}public boolean paused(PlaybackPositionAccess.Snapshot s){return paused||ended;}
  public boolean explicitlyPaused(PlaybackPositionAccess.Snapshot s){return paused;}
  public boolean seek(PlaybackPositionAccess.Snapshot s,long ms){seeks++;target=ms;return true;}
  public void pause(PlaybackPositionAccess.Snapshot s){pauseCalls++;paused=true;}public void stopped(){} public void notice(String text){} public void mode(String mode){this.mode=mode;}
 }
}
