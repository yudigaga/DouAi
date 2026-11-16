package com.douai.module.feature;
import java.util.Objects;
final class ResumePlaybackController {
 private String account,aid; private long deadline; private boolean consumed;
 void enter(String a,String id,long now){if(Objects.equals(a,account)&&Objects.equals(id,aid))return;account=a;aid=id;deadline=now+8000;consumed=false;}
 void reset(){account=null;aid=null;consumed=true;}
 void cancel(){consumed=true;}
 boolean pending(String a,String id,long now){return !consumed&&now<deadline&&Objects.equals(account,a)&&Objects.equals(aid,id);}
 static boolean eligible(ResumeProgressStore.Entry e){return e!=null&&e.durationMs>=60000&&e.positionMs>=10000&&e.durationMs-e.positionMs>10000&&e.positionMs<e.durationMs*0.95;}
}
