package com.douai.module.feature;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The feed hook supplies fresh ownership checks for every player operation. */
final class PlaybackExpansionRuntime implements AutoCloseable {
 interface Host {
  PlaybackPositionAccess.Snapshot snapshot(); Activity owner(); String mode();
  boolean paused(PlaybackPositionAccess.Snapshot s); boolean seek(PlaybackPositionAccess.Snapshot s,long ms);
  default boolean explicitlyPaused(PlaybackPositionAccess.Snapshot s){return paused(s);}
  void pause(PlaybackPositionAccess.Snapshot s); void stopped(); void notice(String text); void mode(String mode);
 }
 final PlaybackLoopController loop=new PlaybackLoopController();
 final PlaybackStopBudget budget=new PlaybackStopBudget();
 final ResumePlaybackController resume=new ResumePlaybackController();
 private final Host host;private final ResumeProgressStore store;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DouAi-resume");t.setDaemon(true);return t;});
 private String account,aid;private long epoch,lastSave=-5000;private boolean closed,loading,blocked;
 private ResumeProgressStore.Entry saved,lastVerified;private android.app.AlertDialog prompt;
 private final Runnable tick=new Runnable(){public void run(){if(closed)return;checkTimer();checkResume();if(budget.isActive()||resume.pending(account,aid,SystemClock.elapsedRealtime()))main.postDelayed(this,200);}};
 PlaybackExpansionRuntime(Host host,File dir){this.host=host;store=new ResumeProgressStore(dir);main.post(tick);}
 private volatile long stopVersion;
 private boolean pauseObserved,suspended;
 private java.lang.ref.WeakReference<Object> entryHolder=new java.lang.ref.WeakReference<>(null),entryOwner=new java.lang.ref.WeakReference<>(null);
 long stopGeneration(){return stopVersion;}
 void paused(long token){PlaybackPositionAccess.Snapshot s=host.snapshot();if(blocked&&token==stopVersion&&owns(s)&&host.paused(s))pauseObserved=true;}
 void playing(long token){PlaybackPositionAccess.Snapshot s=host.snapshot();if(blocked&&token==stopVersion&&pauseObserved&&owns(s)&&!host.paused(s)){blocked=false;pauseObserved=false;}}
 void suspend(){sample(true);resume.cancel();dismissPrompt();loop.suspend();epoch++;suspended=true;}
 void enter(String id){enter(id,null,null);}
 void enter(String id,Object holder,Object owner){
  // Consume an elapsed background deadline before this entry becomes eligible for pause.
  if(suspended)checkTimer();
  String uid=PlaybackBookmarks.account();
  if(java.util.Objects.equals(uid,account)&&java.util.Objects.equals(id,aid)&&entryHolder.get()==holder&&entryOwner.get()==owner){suspended=false;return;}
  leave(false); if(!java.util.Objects.equals(uid,account))budget.cancel();
  entryHolder=new java.lang.ref.WeakReference<>(holder);entryOwner=new java.lang.ref.WeakReference<>(owner);suspended=false;resume.reset();
  account=uid;aid=id;lastVerified=null;blocked=false;epoch++;resume.enter(uid,id,SystemClock.elapsedRealtime());saved=null;loading=false;main.removeCallbacks(tick);main.post(tick);
  if(!PlaybackBookmarkStore.id(uid)||!PlaybackBookmarkStore.id(id)||"off".equals(host.mode())){resume.cancel();return;}
  long token=epoch;loading=true;
  io.execute(()->{ResumeProgressStore.Entry result=null;try{result=store.get(uid,id,System.currentTimeMillis());}catch(Exception ignored){}
   ResumeProgressStore.Entry value=result;main.post(()->{if(!closed&&token==epoch&&uid.equals(PlaybackBookmarks.account())){saved=value;loading=false;checkResume();}});
  });
 }
 void explicit(String id,long ms){epoch++;resume.cancel();dismissPrompt();loop.onExplicitSeek(PlaybackBookmarks.account(),id,ms);}
 void leave(){leave(true);}
 private void leave(boolean captureCurrent){if(captureCurrent)sample(true);else flush(true);resume.cancel();dismissPrompt();loop.cancel();epoch++;}
 void optionsChanged(){if("off".equals(host.mode())){resume.cancel();dismissPrompt();}}
 void configurationReplaced(){
  epoch++;loading=false;saved=null;resume.cancel();dismissPrompt();loop.cancel();budget.cancel();main.removeCallbacks(tick);
 }
 private boolean owns(PlaybackPositionAccess.Snapshot s){return s!=null&&java.util.Objects.equals(account,PlaybackBookmarks.account())&&java.util.Objects.equals(aid,s.aid);}
 private void dismissPrompt(){if(prompt!=null){prompt.dismiss();prompt=null;}}
 private void checkResume(){
  if(closed||suspended||loading||blocked||"off".equals(host.mode())||!resume.pending(account,aid,SystemClock.elapsedRealtime()))return;
  if(!ResumePlaybackController.eligible(saved)){resume.cancel();return;}
  PlaybackPositionAccess.Snapshot s=host.snapshot();if(!owns(s)||host.owner()==null)return;
  if(loop.isActiveFor(account,aid,s.player)){resume.cancel();return;}
  // Automatic restore waits for a playing player; the explicit prompt button may seek while paused.
  if("auto".equals(host.mode())&&host.paused(s))return;
  resume.cancel();long token=epoch;long position=saved.positionMs;String uid=account;
  java.lang.ref.WeakReference<Object> player=new java.lang.ref.WeakReference<>(s.player);
  if("auto".equals(host.mode())){host.seek(s,position);return;}
  prompt=new MiuixDialogBuilder(host.owner(),true).setTitle("恢复上次进度？")
   .setMessage("上次看到 "+PlaybackBookmarkStore.time(position)).setNegativeButton("从头观看",null)
   .setPositiveButton("恢复",(d,w)->{PlaybackPositionAccess.Snapshot current=host.snapshot();
    if(!closed&&token==epoch&&uid.equals(PlaybackBookmarks.account())&&owns(current)&&current.player==player.get()&&!blocked)host.seek(current,position);
   }).show();
 }
 void progress(){
  if(closed||suspended)return;checkTimer();PlaybackPositionAccess.Snapshot s=host.snapshot();if(!owns(s))return;
  sample(false);if(blocked)return;
  boolean active=loop.isActiveFor(account,aid,s.player);
  long target=loop.target(account,s,host.paused(s),SystemClock.elapsedRealtime());
  if(target>=0&&!host.seek(s,target)){loop.cancel();host.notice("循环定位失败，已取消 A-B");}
  else if(active&&!loop.isActiveFor(account,aid,s.player))host.notice("未确认循环定位，已取消 A-B");
  checkResume();
 }
 private void sample(boolean force){
  if("off".equals(host.mode())||!PlaybackBookmarkStore.id(account))return;
  PlaybackPositionAccess.Snapshot s=host.snapshot();
  if(owns(s))lastVerified=new ResumeProgressStore.Entry(s.aid,s.millis,s.duration,System.currentTimeMillis());
  flush(force);
 }
 private void flush(boolean force){
  if("off".equals(host.mode())||!PlaybackBookmarkStore.id(account))return;
  long now=SystemClock.elapsedRealtime();if(lastVerified==null||(!force&&now-lastSave<5000))return;
  // A departing holder may already be rebound: flush the last verified primitive snapshot.
  if(!java.util.Objects.equals(account,PlaybackBookmarks.account()))return;
  lastSave=now;String uid=account;long generation=store.epoch(uid);ResumeProgressStore.Entry e=lastVerified;
  io.execute(()->{try{store.save(uid,e,generation,System.currentTimeMillis());}catch(Exception ignored){}});
 }
 private void checkTimer(){
  if(!java.util.Objects.equals(account,PlaybackBookmarks.account())){budget.cancel();resume.cancel();loop.cancel();return;}
  if(budget.expire(account,SystemClock.elapsedRealtime()))stop();
 }
 private void stop(){
  stopVersion++;host.stopped();resume.cancel();dismissPrompt();loop.cancel();
  PlaybackPositionAccess.Snapshot s=host.snapshot();
  // Background expiry cancels automatic work but must not create a latch on the returning page.
  if(suspended||!owns(s)||host.owner()==null)return;
  blocked=true;pauseObserved=host.explicitlyPaused(s);
  host.pause(s);
 }
 boolean completion(String id,long session){
  checkTimer();PlaybackPositionAccess.Snapshot s=host.snapshot();if(suspended||!owns(s)||!s.aid.equals(id))return false;
  if(blocked)return true;
  if(loop.isActiveFor(account,id,s.player)){
   long target=loop.target(account,s,host.explicitlyPaused(s),SystemClock.elapsedRealtime(),true);
   if(target>=0&&!host.seek(s,target)){loop.cancel();host.notice("循环定位失败，已取消 A-B");}
   return true;
  }
  if(budget.onVerifiedCompletion(account,id,session)){stop();return true;}return false;
 }
 boolean blocks(String id){checkTimer();PlaybackPositionAccess.Snapshot s=host.snapshot();return owns(s)&&s.aid.equals(id)&&(blocked||loop.isActiveFor(account,id,s.player));}

 void action(String action){
  Activity owner=host.owner();if(owner==null)return;PlaybackPositionAccess.Snapshot s=host.snapshot();
  switch(action){
   case "loop_a":resume.cancel();dismissPrompt();host.notice(loop.markA(PlaybackBookmarks.account(),s)?"已设 A 点":"当前位置不能设 A 点");break;
   case "loop_b":resume.cancel();dismissPrompt();host.notice(loop.markB(PlaybackBookmarks.account(),s)?"A-B 循环已开启":"请先设 A，B 须至少晚 1 秒");break;
   case "loop_cancel":loop.cancel();host.notice("已取消 A-B 循环");break;
   case "resume_settings":new MiuixDialogBuilder(owner,true).setTitle("断点续播")
    .setItems(new String[]{"关闭","提示恢复","自动恢复","清空当前账号进度"},(d,i)->{
     if(i<3){host.mode(new String[]{"off","prompt","auto"}[i]);optionsChanged();}
     else {String uid=PlaybackBookmarks.account();epoch++;resume.cancel();dismissPrompt();saved=null;lastVerified=null;if(PlaybackBookmarkStore.id(uid))io.execute(()->{try{store.clear(uid);}catch(Exception ignored){}});}
    }).setNegativeButton("关闭",null).show();break;
   case "stop_budget":new MiuixDialogBuilder(owner,true).setTitle("观看暂停 · "+budget.status(SystemClock.elapsedRealtime()))
    .setItems(new String[]{"15 分钟后暂停","30 分钟后暂停","60 分钟后暂停","看完 5 条暂停","看完 10 条暂停","看完 20 条暂停","取消"},(d,i)->{
     String uid=PlaybackBookmarks.account();blocked=false;
     if(i<3)budget.startTimer(uid,new int[]{15,30,60}[i],SystemClock.elapsedRealtime());else if(i<6)budget.startCount(uid,new int[]{5,10,20}[i-3]);else budget.cancel();
     main.removeCallbacks(tick);main.post(tick);host.notice(budget.status(SystemClock.elapsedRealtime()));
    }).setNegativeButton("关闭",null).show();break;
   default:break;
  }
 }
 @Override public void close(){if(closed)return;leave();closed=true;main.removeCallbacks(tick);budget.cancel();io.shutdown();}
}
