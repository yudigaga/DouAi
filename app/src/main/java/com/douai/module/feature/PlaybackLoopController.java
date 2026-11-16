package com.douai.module.feature;
import java.lang.ref.WeakReference;
import java.util.Objects;
/** Session-only A/B interval; a missing seek readback stops the interval instead of retrying. */
final class PlaybackLoopController {
 private String account,aid; private WeakReference<Object> player=new WeakReference<>(null);
 private long a,b=-1,waitingUntil;private boolean boundaryArmed;
 boolean markA(String account,PlaybackPositionAccess.Snapshot s){
  cancel(); if(!PlaybackBookmarkStore.id(account)||s==null||s.duration-s.millis<1000)return false;
  this.account=account;aid=s.aid;player=new WeakReference<>(s.player);a=s.millis;return true;
 }
 boolean markB(String account,PlaybackPositionAccess.Snapshot s){
  if(s==null||!owns(account,s.aid,s.player)||s.millis-a<1000||s.millis>s.duration)return false;
  b=s.millis;waitingUntil=0;boundaryArmed=true;return true;
 }
 private boolean owns(String account,String aid,Object player){return Objects.equals(this.account,account)&&Objects.equals(this.aid,aid)&&player!=null&&this.player.get()==player;}
 boolean isActiveFor(String account,String aid,Object player){return b>=0&&owns(account,aid,player);}
 long target(String account,PlaybackPositionAccess.Snapshot s,boolean paused,long now){return target(account,s,paused,now,false);}
 void suspend(){waitingUntil=0;}
 long target(String account,PlaybackPositionAccess.Snapshot s,boolean paused,long now,boolean completed){
  if(s==null)return -1;
  if(!owns(account,s.aid,s.player)){cancel();return -1;}
  if(b<0||paused)return -1;
  if(waitingUntil!=0){
   // Only real progress confirms the previous seek; duplicate completion never rearms it.
   if(!completed&&s.millis>=a&&s.millis<Math.min(b,a+1000)){waitingUntil=0;boundaryArmed=true;}
   else if(now>=waitingUntil)cancel();
   return -1;
  }
  if(!completed&&s.millis>=a&&s.millis<b)boundaryArmed=true;
  if((!completed&&s.millis<b)||!boundaryArmed)return -1;waitingUntil=now+2000;boundaryArmed=false;return a;
 }
 void onExplicitSeek(String account,String aid,long ms){if(Objects.equals(this.account,account)&&Objects.equals(this.aid,aid)&&(ms<a||ms>b))cancel();}
 void cancel(){account=null;aid=null;player.clear();b=-1;waitingUntil=0;}
}
