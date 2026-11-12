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
            for(int i=0;i<14;i++) {
                LocalDate day=LocalDate.of(2026,10,26).plusDays(i);
                clock.value=day.atTime(i==13?21:8,0).atZone(clock.zone).toInstant();
                int count=i<7?3:i==13?12:2;
                for(int j=0;j<count;j++) {
                    String tag=i<7?(j==2?"摄影":"旅行"):(i==13?"赛事":"摄影");
                    String id="fixture-work-"+i+"-"+j;
                    String token=store.beginVisit(store.generation(),id,"short_video","author-"+(j%2),
                        j%2==0?"秋水":"远山",java.util.List.of(tag),false,35000,"manual");
                    if(j==0)store.recordAction(store.generation(),token,id,"copy_text","复制文案","manual","success");
                    store.finishVisit(token,3000,"selection_changed");
                }
            }
            return store.snapshot(rangeDays);
        }
    }
}
