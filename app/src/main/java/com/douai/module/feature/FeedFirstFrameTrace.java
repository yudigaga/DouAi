package com.douai.module.feature;

import java.lang.ref.WeakReference;

/** Main-thread correlation of view and renderer observations, not compositor presentation. */
final class FeedFirstFrameTrace {
    private static final long MAX_EARLY_MS=1000L;
    private static final long MAX_RENDER_WAIT_MS=5000L;
    interface Sink { void event(String stage,String aid,long sinceSelectionMs); }

    private final Sink sink;
    private WeakReference<Object> dispatcher=new WeakReference<>(null);
    private WeakReference<Object> earlyDispatcher=new WeakReference<>(null);
    private String aid,earlyAid;
    private long selectedAt,earlyAt;
    private boolean drew,reportedRenderer;

    FeedFirstFrameTrace(Sink sink){this.sink=sink;}

    boolean matches(Object owner,String work){
        return owner!=null && dispatcher.get()==owner && aid!=null && aid.equals(work);
    }

    void selected(Object owner,String work,long now){
        deselected();
        if(owner==null || work==null || work.isEmpty())return;
        dispatcher=new WeakReference<>(owner);aid=work;selectedAt=now;
        sink.event("selected",aid,0);
        long delta=earlyAt-now;
        if(earlyDispatcher.get()==owner && work.equals(earlyAid) && delta<=0 && delta>=-MAX_EARLY_MS){
            reportedRenderer=true;
            sink.event("renderer_before_selection",aid,delta);
            earlyDispatcher.clear();earlyAid=null;
        }else if(delta< -MAX_EARLY_MS){earlyDispatcher.clear();earlyAid=null;}
    }

    void drawOpportunity(long now){
        if(aid==null || drew || now<selectedAt)return;
        drew=true;
        sink.event("draw_opportunity",aid,now-selectedAt);
    }

    void rendered(Object owner,String work,long now){
        if(owner==null || work==null || work.isEmpty())return;
        if(matches(owner,work)){
            if(reportedRenderer || now<selectedAt-MAX_EARLY_MS || now-selectedAt>MAX_RENDER_WAIT_MS)return;
            reportedRenderer=true;
            sink.event(now<selectedAt?"renderer_before_selection":"renderer_callback",aid,now-selectedAt);
        }else if(earlyAid==null || now>=earlyAt){
            earlyDispatcher=new WeakReference<>(owner);earlyAid=work;earlyAt=now;
        }
    }

    void deselected(){dispatcher.clear();aid=null;drew=false;reportedRenderer=false;}
    void clear(){deselected();earlyDispatcher.clear();earlyAid=null;}
}
