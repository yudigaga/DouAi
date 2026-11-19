package com.douai.module.analytics;

import java.io.File;
import java.time.*;

/** Synthetic visits feed the same store and queries as the module; no device records are read. */
public final class JournalExample {
    private JournalExample() {}
    public static AnalyticsSnapshot twoWeeks(File directory) {
        return twoWeeks(directory, 7);
    }
    public static AnalyticsSnapshot twoWeeks(File directory, int rangeDays) {
        class Time extends Clock {
            final ZoneId zone = ZoneId.of("Asia/Shanghai");
            Instant value = LocalDate.of(2026,10,26).atStartOfDay(zone).toInstant();
            public ZoneId getZone(){return zone;}
            public Clock withZone(ZoneId z){return Clock.fixed(value,z);}
            public Instant instant(){return value;}
        }
        Time clock = new Time();
        try(LocalAnalytics store = new LocalAnalytics(directory,clock)) {
            store.setEnabled(true);
            int expectedTotal=0,expectedWindow=0,acceptedTokens=0;
            int windowStart=rangeDays<=0?0:Math.max(0,14-Math.min(rangeDays,14));
            for(int i=0;i<14;i++) {
                LocalDate day=LocalDate.of(2026,10,26).plusDays(i);
                clock.value=day.atTime(i==13?21:8,0).atZone(clock.zone).toInstant();
                int count=i<7?3:i==13?12:2;
                expectedTotal+=count;
                if(i>=windowStart)expectedWindow+=count;
                for(int j=0;j<count;j++) {
                    String tag=i<7?(j==2?"摄影":"旅行"):(i==13?"赛事":"摄影");
                    String id="fixture-work-"+i+"-"+j;
                    String token=store.beginVisit(store.generation(),id,"short_video","author-"+(j%2),
                        j%2==0?"秋水":"远山",java.util.List.of(tag),false,35000,"manual");
                    if(token!=null)acceptedTokens++;
                    if(j==0)store.recordAction(store.generation(),token,id,"copy_text","复制文案","manual","success");
                    store.finishVisit(token,3000,"selection_changed");
                }
            }
            AnalyticsSnapshot snapshot;
            try { snapshot=store.snapshot(rangeDays); }
            catch(RuntimeException failure) {
                throw fixtureFailure("snapshot threw "+failure.getClass().getName()+": "+failure.getMessage(),
                        directory,store,clock,expectedTotal,expectedWindow,acceptedTokens,null,failure);
            }
            if(!store.isEnabled() || acceptedTokens!=expectedTotal || snapshot.totalViews!=expectedWindow
                    || snapshot.portrait.capturedVisits!=expectedWindow || snapshot.portrait.finishedVisits!=expectedWindow)
                throw fixtureFailure("snapshot counts did not match the seed",directory,store,clock,
                        expectedTotal,expectedWindow,acceptedTokens,snapshot,null);
            return snapshot;
        }
    }
    private static IllegalStateException fixtureFailure(String reason,File directory,LocalAnalytics store,
            Clock clock,int expectedTotal,int expectedWindow,int acceptedTokens,AnalyticsSnapshot snapshot,Throwable cause) {
        String result=snapshot==null?"<none>":"views="+snapshot.totalViews+", captured="
                +snapshot.portrait.capturedVisits+", finished="+snapshot.portrait.finishedVisits;
        String message="Synthetic journal fixture failed: "+reason+", expected_total="+expectedTotal
                +", expected_window="+expectedWindow+", visit_tokens="+acceptedTokens+", result="+result
                +", enabled="+store.isEnabled()+", transactions="+store.writeTransactionCount()
                +", database_exists="+new File(directory,"usage.db").isFile()
                +", interrupted="+Thread.currentThread().isInterrupted()+", clock_date="
                +clock.instant().atZone(clock.getZone()).toLocalDate()+", zone="+clock.getZone()
                +", causes="+causeChain(cause);
        return cause==null?new IllegalStateException(message):new IllegalStateException(message,cause);
    }
    private static String causeChain(Throwable failure) {
        StringBuilder result=new StringBuilder();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(result.length()>0)result.append(" <- ");
            result.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
        }
        return result.length()==0?"<none>":result.toString();
    }
}
