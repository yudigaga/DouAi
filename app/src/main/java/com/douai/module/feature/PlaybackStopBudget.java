package com.douai.module.feature;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
/** One-shot elapsed-time or unique completion budget. Never persisted. */
final class PlaybackStopBudget {
 private String account; private int remaining; private long deadline; private boolean active;
 private final Set<String> seen=new HashSet<>();
 void startCount(String a,int n){cancel();if(!PlaybackBookmarkStore.id(a)||n<=0)return;account=a;remaining=n;active=true;}
 void startTimer(String a,int minutes,long now){cancel();if(!PlaybackBookmarkStore.id(a)||(minutes!=15&&minutes!=30&&minutes!=60))return;account=a;deadline=now+minutes*60000L;active=true;}
 boolean onVerifiedCompletion(String a,String id,long epoch){if(!check(a)||remaining<=0||!PlaybackBookmarkStore.id(id)||!seen.add(id))return false;if(--remaining>0)return false;active=false;return true;}
 boolean expire(String a,long now){if(!check(a)||deadline==0||now<deadline)return false;active=false;return true;}
 private boolean check(String a){if(!Objects.equals(a,account)){cancel();return false;}return active;}
 void cancel(){active=false;account=null;remaining=0;deadline=0;seen.clear();}
 boolean isActive(){return active;}
 String status(long now){return !active?"未启动":deadline>0?"剩余 "+Math.max(0,(deadline-now+999)/1000)+" 秒":"剩余 "+remaining+" 条";}
}
