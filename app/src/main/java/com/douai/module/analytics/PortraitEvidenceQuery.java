package com.douai.module.analytics;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Reads retained evidence; older rows lack sequence and loss-location evidence. */
final class PortraitEvidenceQuery {
    private static final String[] ACTIONS={"save_media:entry","copy_text:success"};
    private static final List<String> LIMITS=List.of("仅表示保留片段中记录到的操作；明细最多30天，历史完整性未知。","话题可重叠；无标签片段不进入话题对照。无记录不等于没有行为。");
    static PortraitEvidence read(SQLiteDatabase db,LocalDate start,LocalDate end){
        return read(db,start,end,end);
    }
    static PortraitEvidence read(SQLiteDatabase db,LocalDate start,LocalDate end,LocalDate today){
        if(end.isBefore(start))throw new IllegalArgumentException("Invalid evidence window");
        if(start.isBefore(end.minusDays(364)))start=end.minusDays(364);
        String[] range={start.toString(),end.toString()};
        Map<String,Long> views=new TreeMap<>();
        try(Cursor c=db.rawQuery("SELECT day,SUM(views) FROM daily WHERE day BETWEEN ? AND ? GROUP BY day",range)){while(c.moveToNext())views.put(c.getString(0),c.getLong(1));}
        int browseDays=(int)views.values().stream().filter(v->v>0).count();
        Map<String,Map<String,Long>> counts=new TreeMap<>();Map<String,String> labels=new HashMap<>();
        try(Cursor c=db.rawQuery("SELECT item_key,MAX(label),day,SUM(count) FROM ranks WHERE kind='topic' AND day BETWEEN ? AND ? GROUP BY item_key,day ORDER BY item_key,day",range)){
            while(c.moveToNext()){String key=c.getString(0);labels.put(key,c.getString(1));counts.computeIfAbsent(key,k->new HashMap<>()).put(c.getString(2),c.getLong(3));}}
        List<PortraitEvidence.Topic> ranked=new ArrayList<>();
        for(var entry:counts.entrySet()){
            long total=0,peak=0;int appearances=0;
            for(var day:entry.getValue().entrySet()){
                long n=day.getValue();total+=n;peak=Math.max(peak,n);
                if(n>0&&views.getOrDefault(day.getKey(),0L)>0)appearances++;
            }
            ranked.add(new PortraitEvidence.Topic("topic:"+entry.getKey(),labels.get(entry.getKey()),total,appearances,browseDays,total==0?0:(double)peak/total,List.of()));
        }
        ranked.sort(Comparator.comparingLong((PortraitEvidence.Topic t)->t.total).reversed().thenComparing(t->t.id));
        List<PortraitEvidence.Topic> focus=chooseTopicFocus(ranked,browseDays);
        // Select from the whole population. Only materialise daily series for the displayed examples.
        Set<PortraitEvidence.Topic> displayed=new HashSet<>(focus);
        for(PortraitEvidence.Topic t:ranked){if(displayed.size()==8)break;displayed.add(t);}
        List<PortraitEvidence.Topic> topics=new ArrayList<>();
        for(PortraitEvidence.Topic t:ranked)if(displayed.contains(t)){
            Map<String,Long> perDay=counts.get(t.id.substring("topic:".length()));
            List<PortraitEvidence.TopicDay> days=new ArrayList<>();
            for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1)){
                String date=d.toString();days.add(new PortraitEvidence.TopicDay(date,perDay.getOrDefault(date,0L),views.get(date)));
            }
            topics.add(new PortraitEvidence.Topic(t.id,t.label,t.total,t.appearanceDays,browseDays,t.peakDayShare,days));
        }
        // Fetch complete bounded raw relations once, never reconstruct from ranked joint tiles.
        LocalDate detailStart=start.isBefore(today.minusDays(29))?today.minusDays(29):start;
        String[] detailRange={detailStart.toString(),end.toString()};
        // Include an adjacent comparison week when the selected view is only seven days.
        LocalDate scanStart=detailStart;
        if(java.time.temporal.ChronoUnit.DAYS.between(start,end)>=6){
            LocalDate last=end.isBefore(today)?end:today.minusDays(1),first=last.minusDays(13);
            if(!first.isBefore(today.minusDays(29))&&first.isBefore(scanStart))scanStart=first;
        }
        Samples samples=new Samples(db,scanStart,end);
        Gaps gaps=new Gaps(db);
        boolean detailCovered=covered(db,detailStart,end)&&retained(db,detailStart)&&!gaps.affectsActions(detailStart,end);
        List<String> actionLimits=new ArrayList<>(LIMITS);
        if(!detailCovered) actionLimits.add("采集起点、队列丢失或明细裁剪影响本窗口；仅展示保留样本。");
        List<PortraitEvidence.ActionRate> rates=samples.actionRates(db,detailRange,detailCovered,actionLimits);
        PortraitEvidence.Decomposition decomposition=decompose(db,start,end,today,gaps,samples);
        List<PortraitEvidence.Observation> selected=new ArrayList<>();
        if(focus.size()==1){
            PortraitEvidence.Topic t=focus.get(0);
            String title=t.label+"："+t.total+" 次，覆盖 "+t.appearanceDays+" / "+browseDays+" 个浏览日";
            selected.add(new PortraitEvidence.Observation(t.id,"topic",title,"逐日次数按当天总浏览换算；单日峰值占本话题 "+Math.round(t.peakDayShare*100)+"%。", "有浏览的自然日","浏览日",range[0],range[1],(long)t.appearanceDays,(long)browseDays,null,t.id,"topic:"+range[0]+":"+range[1],List.of("按日汇总；各话题可能重叠，不能相加。")));
        }
        if(focus.size()==2)addTopicContrast(focus.get(0),focus.get(1),browseDays,range,selected);
        rates.stream().filter(r->r.eligible).sorted(Comparator.comparingDouble((PortraitEvidence.ActionRate r)->Math.abs(r.differencePoints)).reversed().thenComparing(r->r.id)).findFirst().ifPresent(r->selected.add(new PortraitEvidence.Observation(r.id,"action",r.topic+"与其他已识别话题的"+r.label+"记录",r.startDate+" 至 "+r.endDate+"：带「"+r.topic+"」标签的片段中，"+r.label+" "+r.withTopic.numerator+"/"+r.withTopic.denominator+"；不含该话题但有其他已识别话题的片段中，"+r.label+" "+r.withoutTopic.numerator+"/"+r.withoutTopic.denominator+"。","不含此话题且有其他已识别话题的片段","片段",r.startDate,r.endDate,r.withTopic.numerator,r.withTopic.denominator,r.withTopic.uniqueWorks,r.id,"visits:"+r.startDate+":"+r.endDate,r.coverage)));
        addExploration(db,detailRange,selected);
        if(decomposition.available && selected.size()<3)selected.add(new PortraitEvidence.Observation("decomposition:save_media:entry","change","保存入口记录率的两期变化",String.format(java.util.Locale.ROOT,"%s–%s：%d/%d（%.1f%%）→%d/%d（%.1f%%）；构成 %+.2f 个百分点，类内 %+.2f 个百分点，总差 %+.2f 个百分点。",decomposition.previousStart,decomposition.currentEnd,decomposition.previous.numerator,decomposition.previous.denominator,100*decomposition.previous.rate,decomposition.current.numerator,decomposition.current.denominator,100*decomposition.current.rate,decomposition.compositionPoints,decomposition.withinPoints,decomposition.differencePoints),"相邻完整7日窗","百分点",decomposition.previousStart,decomposition.currentEnd,null,null,null,"save_media:entry","visits:"+decomposition.previousStart+":"+decomposition.currentEnd,List.of(decomposition.limitation)));
        List<String> coverage=new ArrayList<>(LIMITS);
        if(gaps.any(detailStart,end))coverage.add("此明细窗口可能有队列拒绝的记录；仅按保留数据展示。");
        List<PortraitEvidence.StageCount> stages=new ArrayList<>();List<PortraitEvidence.FlowPath> paths=new ArrayList<>();
        boolean legacyAi=readFlows(db,detailRange,stages,paths);
        List<PortraitEvidence.Familiarity> familiarity=readFamiliarity(db,detailRange,samples,gaps);
        for(PortraitEvidence.Familiarity f:familiarity)if(f.eligible&&selected.size()<3){selected.add(new PortraitEvidence.Observation(f.id,"familiarity","近期重逢与可见停留", "同类型、同到达来源：近期遇见 "+f.familiar.n+" 个作品日，中位 "+f.familiar.median+" 毫秒；没有保留相遇记录 "+f.unseen.n+" 个作品日，中位 "+f.unseen.median+" 毫秒；排除 "+f.excluded+" 段。", "前28天作品日记录","作品日",detailRange[0],detailRange[1],f.familiar.n,f.familiar.n+f.unseen.n,null,f.id,"work_days:"+detailRange[0]+":"+detailRange[1],f.coverage));break;}
        boolean hasDetailWindow=!detailStart.isAfter(end);
        return new PortraitEvidence(range[0],range[1],browseDays,topics,rates,decomposition,selected,coverage,stages,paths,familiarity,hasDetailWindow?detailRange[0]:"",hasDetailWindow?detailRange[1]:"",legacyAi);
    }
    private static List<PortraitEvidence.Topic> chooseTopicFocus(List<PortraitEvidence.Topic> ranked,int browseDays){
        if(ranked.isEmpty()||browseDays==0)return List.of();
        Comparator<PortraitEvidence.Topic> byDays=Comparator.comparingInt((PortraitEvidence.Topic t)->t.appearanceDays).thenComparingLong(t->t.total).thenComparing(t->t.id,Comparator.reverseOrder());
        if(browseDays>=PortraitEvidence.MIN_BROWSE_DAYS){
            // Totals descend: among earlier topics within 20%, only the min/max day counts can win.
            NavigableSet<PortraitEvidence.Topic> comparable=new TreeSet<>(byDays);
            int left=0,best=1;PortraitEvidence.Topic broad=null,brief=null;
            for(PortraitEvidence.Topic t:ranked){
                if(t.total<PortraitEvidence.MIN_TOPIC_COUNT)break;
                while(!comparable.isEmpty()&&t.total<.8*ranked.get(left).total)comparable.remove(ranked.get(left++));
                if(!comparable.isEmpty())for(PortraitEvidence.Topic other:List.of(comparable.first(),comparable.last())){
                    int difference=Math.abs(other.appearanceDays-t.appearanceDays);
                    if(difference>best){best=difference;broad=other.appearanceDays>t.appearanceDays?other:t;brief=broad==t?other:t;}
                }
                comparable.add(t);
            }
            if(broad!=null)return List.of(broad,brief);
        }
        return List.of(Collections.max(ranked,byDays));
    }
    private static void addTopicContrast(PortraitEvidence.Topic broad,PortraitEvidence.Topic brief,int browseDays,String[] range,List<PortraitEvidence.Observation> selected){
        String title=(broad.total==brief.total?"同样 "+broad.total+" 次":"次数相近")+"，一个贯穿 "+broad.appearanceDays+" 天，一个集中在 "+brief.appearanceDays+" 天";
        String body=broad.label+"："+broad.total+" 次，出现在 "+broad.appearanceDays+" / "+browseDays+" 个浏览日；"+brief.label+"："+brief.total+" 次，出现在 "+brief.appearanceDays+" / "+browseDays+" 个浏览日。比较基于同一范围的 "+browseDays+" 个浏览日；话题可能重叠，次数不相加。";
        selected.add(new PortraitEvidence.Observation("topic:contrast:"+broad.id+":"+brief.id,"topic",title,body,brief.label,"浏览日",range[0],range[1],(long)broad.appearanceDays,(long)browseDays,null,broad.id,"topic:"+range[0]+":"+range[1],List.of("这是出现节奏的描述，不代表偏好强度或统计显著。"),brief.id));
    }    private static void addExploration(SQLiteDatabase db,String[] range,List<PortraitEvidence.Observation> observations){
        if(scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='flow_events'")==0)return;
        Set<String> clicks=new HashSet<>(),follow=new HashSet<>(),video=new HashSet<>(),routes=new HashSet<>();
        try(Cursor c=db.rawQuery("SELECT operation_id,stage FROM flow_events WHERE kind='ai' AND day BETWEEN ? AND ? ORDER BY ts,id",range)){
            while(c.moveToNext()){String op=c.getString(0),stage=c.getString(1);if(stage.equals("followup_click")||stage.equals("more_click")||stage.equals("video_click"))clicks.add(op);if(stage.equals("followup_click"))follow.add(op);if(stage.equals("video_click"))video.add(op);if(stage.equals("route_dispatched"))routes.add(op);}}
        if(clicks.isEmpty()||observations.size()>=3)return;
        routes.retainAll(clicks);
        observations.add(new PortraitEvidence.Observation("exploration:click_sessions","exploration","主动继续探索的 AI 会话："+clicks.size()+" 次","追问点击 "+follow.size()+" 个会话，相关视频点击 "+video.size()+" 个会话；路由派发 "+routes.size()+" 个会话，不代表播放。","仅实际点击会话；不假定经过固定顺序","会话",range[0],range[1],(long)clicks.size(),null,null,"ai:flow_events","ai:"+range[0]+":"+range[1],List.of("未确认推荐可见，不计算曝光转化率。","无来源会话不归入后来刷到的作品；会话阶段可以分叉。")));
    }

    private static boolean readFlows(SQLiteDatabase db,String[] range,List<PortraitEvidence.StageCount> stages,List<PortraitEvidence.FlowPath> paths){
        if(!hasTable(db,"flow_events"))return false;
        boolean hasSeq=false, legacy=false;
        try(Cursor c=db.rawQuery("PRAGMA table_info(flow_events)",null)){while(c.moveToNext())if("seq".equals(c.getString(1)))hasSeq=true;}
        Map<String,Set<String>> stageSessions=new TreeMap<>(),stageTasks=new TreeMap<>(),edgeSessions=new TreeMap<>();
        String prevOp=null, prevStage=null; long prevSeq=0;
        String seq=hasSeq?"seq":"NULL";
        try(Cursor c=db.rawQuery("SELECT operation_id,stage,kind,task,"+seq+" FROM flow_events WHERE day BETWEEN ? AND ? ORDER BY operation_id,"+seq+",id",range)){
            while(c.moveToNext()){
                String op=c.getString(0), stage=c.getString(1), kind=c.getString(2), key=kind+"\n"+stage;
                stageSessions.computeIfAbsent(key,k->new HashSet<>()).add(op);
                if(kind.equals("download")&&c.getInt(3)>0)stageTasks.computeIfAbsent(key,k->new HashSet<>()).add(op+"\n"+c.getInt(3));
                if(!kind.equals("ai"))continue;
                if(c.isNull(4)){legacy=true;continue;}
                long current=c.getLong(4);
                if(op.equals(prevOp)&&current==prevSeq+1) edgeSessions.computeIfAbsent(prevStage+"\n"+stage,k->new HashSet<>()).add(op);
                prevOp=op; prevStage=stage; prevSeq=current;
            }
        }
        for(String key:stageSessions.keySet()){String[] pair=key.split("\n",2);stages.add(new PortraitEvidence.StageCount(pair[0],pair[1],stageSessions.get(key).size(),stageTasks.getOrDefault(key,Set.of()).size()));}
        for(String edge:edgeSessions.keySet()){String[] pair=edge.split("\n",2);paths.add(new PortraitEvidence.FlowPath(pair[0],pair[1],edgeSessions.get(edge).size()));}
        return legacy;
    }
    private static List<PortraitEvidence.Familiarity> readFamiliarity(SQLiteDatabase db,String[] range,Samples samples,Gaps gaps){
        if(!samples.hasVisitMetadata||samples.rows.isEmpty()||range[0].compareTo(range[1])>0)return List.of();
        LocalDate start=LocalDate.parse(range[0]),end=LocalDate.parse(range[1]),historyStart=start.minusDays(28);
        Map<String,Boolean> dayCoverage=historyCoverage(db,start,end,gaps);
        // At most 58 calendar dates (30 retained + 28 history). Keep bits only for works
        // that actually occur in the visit window; historic work-day rows are streamed.
        Map<String,Integer> offsets=new HashMap<>();
        for(LocalDate d=historyStart;!d.isAfter(end);d=d.plusDays(1))offsets.put(d.toString(),offsets.size());
        long[] history=new long[samples.works.size()],chosen=new long[history.length];
        try(Cursor c=db.rawQuery("SELECT work_hash,day FROM work_days WHERE day BETWEEN ? AND ?",new String[]{historyStart.toString(),range[1]})){
            while(c.moveToNext()){
                Integer w=samples.works.get(c.getString(0));
                if(w!=null)history[w]|=1L<<offsets.get(c.getString(1));
            }
        }
        Map<String,List<Double>> familiar=new TreeMap<>(),unseen=new TreeMap<>();Map<String,Long> excluded=new HashMap<>();
        Map<String,BitSet> sampleWorks=new HashMap<>(),sampleDays=new HashMap<>();
        for(Row row:samples.rows){
            if(row.day.compareTo(range[0])<0)continue;
            String key=row.type+"\n"+row.source;
            familiar.computeIfAbsent(key,k->new ArrayList<>());unseen.computeIfAbsent(key,k->new ArrayList<>());
            int offset=offsets.get(row.day),w=row.workIndex;
            long bit=1L<<offset;
            if(w<0||!row.natural||!Boolean.TRUE.equals(dayCoverage.get(row.day))||(chosen[w]&bit)!=0){
                excluded.merge(key,1L,Long::sum);continue;
            }
            chosen[w]|=bit;
            boolean seen=(history[w]&(((1L<<28)-1)<<(offset-28)))!=0;
            (seen?familiar:unseen).get(key).add((double)row.visible);
            String sampleKey=key+"\n"+seen;
            sampleWorks.computeIfAbsent(sampleKey,k->new BitSet()).set(w);
            sampleDays.computeIfAbsent(sampleKey,k->new BitSet()).set(offset);
        }
        List<PortraitEvidence.Familiarity> result=new ArrayList<>();
        for(String key:familiar.keySet()){String[] parts=key.split("\n",2);PortraitEvidence.Distribution a=distribution(familiar.get(key)),b=distribution(unseen.get(key));boolean eligible=a.n>=PortraitEvidence.MIN_GROUP_VISITS&&b.n>=PortraitEvidence.MIN_GROUP_VISITS&&sampleWorks.getOrDefault(key+"\ntrue",new BitSet()).cardinality()>=PortraitEvidence.MIN_GROUP_WORKS&&sampleWorks.getOrDefault(key+"\nfalse",new BitSet()).cardinality()>=PortraitEvidence.MIN_GROUP_WORKS&&sampleDays.getOrDefault(key+"\ntrue",new BitSet()).cardinality()>=PortraitEvidence.MIN_GROUP_DAYS&&sampleDays.getOrDefault(key+"\nfalse",new BitSet()).cardinality()>=PortraitEvidence.MIN_GROUP_DAYS&&!parts[1].equals("unknown")&&!parts[1].isEmpty();result.add(new PortraitEvidence.Familiarity(parts[0],parts[1],a,b,excluded.getOrDefault(key,0L),eligible,List.of("按此前28天的作品记录分组；同一作品在同一天只取首次因切换作品而自然结束的可见片段。","可见毫秒不是完播率；未结束、恢复合成结束、覆盖未知及重复片段均排除。")));}
        return result;
    }
    // Fold collection transitions into at most 30 history windows. An event exactly
    // at the left edge establishes the initial state; a later disable is always a gap.
    private static Map<String,Boolean> historyCoverage(SQLiteDatabase db,LocalDate start,LocalDate end,Gaps gaps){
        Map<String,Boolean> result=new HashMap<>();
        if(start.isAfter(end))return result;
        int n=(int)java.time.temporal.ChronoUnit.DAYS.between(start,end)+1;
        long[] from=new long[n],until=new long[n];boolean[] initial=new boolean[n],gap=new boolean[n];
        ZoneId zone=ZoneId.systemDefault();
        for(int i=0;i<n;i++){
            LocalDate d=start.plusDays(i);
            from[i]=d.minusDays(28).atStartOfDay(zone).toInstant().toEpochMilli();
            until[i]=d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        try(Cursor c=db.rawQuery("SELECT enabled FROM collection_boundaries WHERE ts<? ORDER BY ts DESC,id DESC LIMIT 1",new String[]{Long.toString(from[0])})){
            if(c.moveToFirst()&&c.getInt(0)==1)Arrays.fill(initial,true);
        }
        try(Cursor c=db.rawQuery("SELECT ts,enabled FROM collection_boundaries WHERE ts>=? AND ts<? ORDER BY ts,id",new String[]{Long.toString(from[0]),Long.toString(until[n-1])})){
            while(c.moveToNext()){
                long ts=c.getLong(0);boolean enabled=c.getInt(1)==1;
                for(int i=0;i<n;i++){
                    if(ts<=from[i])initial[i]=enabled;
                    else if(ts<until[i]&&!enabled)gap[i]=true;
                }
            }
        }
        for(int i=0;i<n;i++){
            LocalDate day=start.plusDays(i);
            boolean complete=initial[i]&&!gap[i]&&!gaps.matches(day.minusDays(28),day.minusDays(1),Set.of("history","visit_history"))
                    &&!gaps.matches(day,day,Set.of("visit_history","visits","dwell"));
            result.put(day.toString(),complete);
        }
        return result;
    }
    private static PortraitEvidence.Distribution distribution(List<Double> samples){samples.sort(Double::compare);return new PortraitEvidence.Distribution(samples.size(),quantile(samples,.5),quantile(samples,.25),quantile(samples,.75));}
    private static Double quantile(List<Double> samples,double fraction){if(samples.isEmpty())return null;double position=(samples.size()-1)*fraction;int low=(int)position,high=(int)Math.ceil(position);return samples.get(low)+(samples.get(high)-samples.get(low))*(position-low);}    private static boolean retained(SQLiteDatabase db,LocalDate start){
        if(scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE name='source_coverage'")==0)return false;
        long since=start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();int known=0;
        try(Cursor c=db.rawQuery("SELECT source,status,since,trimmed_before FROM source_coverage WHERE source IN ('visits','events')",null)){
            while(c.moveToNext()){if(!"available".equals(c.getString(1))||c.getLong(2)>since||(!c.isNull(3)&&c.getLong(3)>=since))return false;known++;}}
        return known==2;
    }    private static boolean covered(SQLiteDatabase db,LocalDate start,LocalDate end){
        long from=start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),until=end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        boolean enabledAtStart=false;
        try(Cursor c=db.rawQuery("SELECT ts,enabled FROM collection_boundaries WHERE ts<? ORDER BY ts,id",new String[]{Long.toString(until)})){
            while(c.moveToNext()){
                if(c.getLong(0)<=from)enabledAtStart=c.getInt(1)==1;
                else if(c.getInt(1)==0)return false;
            }}
        return enabledAtStart;
    }    private static boolean adequate(PortraitEvidence.Group g){return g.denominator>=PortraitEvidence.MIN_GROUP_VISITS&&g.uniqueWorks>=PortraitEvidence.MIN_GROUP_WORKS&&g.sampleDays>=PortraitEvidence.MIN_GROUP_DAYS;}
    private static final class Row {
        final int index,workIndex,dayIndex;
        final String day,type;
        boolean saved,copied,tagged,linkable,natural;
        String source;long visible;
        Row(int index,int workIndex,int dayIndex,String day,String type){
            this.index=index;this.workIndex=workIndex;this.dayIndex=dayIndex;this.day=day;this.type=type;
        }
    }
    private static final class Acc {
        long saved,copied,total;
        final BitSet works=new BitSet(),days=new BitSet();
        void add(Row row){
            total++;if(row.saved)saved++;if(row.copied)copied++;
            if(row.workIndex>=0)works.set(row.workIndex);
            days.set(row.dayIndex);
        }
        PortraitEvidence.Group group(){return group(0);}
        PortraitEvidence.Group group(int action){return new PortraitEvidence.Group(action==0?saved:copied,total,works.cardinality(),days.cardinality());}
    }
    private static final class Samples {
        final List<Row> rows=new ArrayList<>();
        final Map<String,Row> byToken=new HashMap<>();
        final Map<String,Integer> works=new HashMap<>();
        final boolean hasVisitMetadata;
        Samples(SQLiteDatabase db,LocalDate start,LocalDate end){
            String[] range={start.toString(),end.toString()};
            Map<String,Integer> days=new HashMap<>();
            Set<String> columns=new HashSet<>();
            try(Cursor c=db.rawQuery("PRAGMA table_info(visits)",null)){while(c.moveToNext())columns.add(c.getString(1));}
            hasVisitMetadata=columns.containsAll(List.of("source","visible_ms","ended","end_reason","started"))
                    &&scalar(db,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='work_days'")!=0;
            String sql="SELECT token,work_hash,type,day"+(hasVisitMetadata?",source,visible_ms,ended,end_reason":"")
                    +" FROM visits WHERE day BETWEEN ? AND ?"+(hasVisitMetadata?" ORDER BY started,token":"");
            try(Cursor c=db.rawQuery(sql,range)){
                while(c.moveToNext()){
                    String work=c.getString(1),day=c.getString(3),token=c.getString(0);
                    Row r=new Row(rows.size(),work==null||work.isEmpty()?-1:works.computeIfAbsent(work,k->works.size()),
                            days.computeIfAbsent(day,k->days.size()),day,c.getString(2));
                    if(hasVisitMetadata){
                        r.source=c.getString(4);r.visible=c.getLong(5);
                        r.natural=c.getInt(6)==1&&"selection_changed".equals(c.getString(7));
                    }
                    rows.add(r);
                    if(token!=null&&!token.isEmpty()){r.linkable=true;byToken.put(token,r);}
                }
            }
            // A segment can have several identical events; membership in each numerator is boolean.
            try(Cursor c=db.rawQuery("SELECT a.token,a.feature FROM visits v JOIN actions a ON a.token=v.token WHERE v.day BETWEEN ? AND ? AND a.source='manual' AND ((a.feature='save_media' AND a.stage='entry') OR (a.feature='copy_text' AND a.stage='success'))",range)){
                while(c.moveToNext()){
                    Row r=byToken.get(c.getString(0));if(r==null)continue;
                    if("save_media".equals(c.getString(1)))r.saved=true;else r.copied=true;
                }
            }
        }
        List<PortraitEvidence.ActionRate> actionRates(SQLiteDatabase db,String[] range,boolean covered,List<String> limits){
            Map<String,List<Row>> tags=new HashMap<>();
            try(Cursor c=db.rawQuery("SELECT t.topic,t.token FROM visits v JOIN visit_topics t ON t.token=v.token WHERE v.day BETWEEN ? AND ? AND t.topic<>'' GROUP BY t.topic,t.token",range)){
                while(c.moveToNext()){
                    Row r=byToken.get(c.getString(1));if(r==null)continue;
                    r.tagged=true;tags.computeIfAbsent(c.getString(0),k->new ArrayList<>()).add(r);
                }
            }
            List<String> topics=new ArrayList<>(tags.keySet());
            topics.sort(Comparator.comparingInt((String topic)->tags.get(topic).size()).reversed().thenComparing(topic->topic));
            List<PortraitEvidence.ActionRate> result=new ArrayList<>();
            BitSet members=new BitSet();
            for(String topic:topics.subList(0,Math.min(topics.size(),8))){
                members.clear();for(Row row:tags.get(topic))members.set(row.index);
                Acc yes=new Acc(),no=new Acc();
                for(Row row:rows)if(row.tagged)(members.get(row.index)?yes:no).add(row);
                for(int i=0;i<ACTIONS.length;i++){
                    PortraitEvidence.Group a=yes.group(i),b=no.group(i);
                    boolean eligible=adequate(a)&&adequate(b)&&a.numerator+b.numerator>=PortraitEvidence.MIN_POSITIVES&&covered;
                    result.add(new PortraitEvidence.ActionRate("action:"+topic+":"+ACTIONS[i],topic,ACTIONS[i],
                            i==0?"保存入口":"复制成功",a,b,eligible,limits,range[0],range[1]));
                }
            }
            return result;
        }
    }
    private static boolean hasTable(SQLiteDatabase db,String table){
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})){return c.moveToFirst();}
    }
    private static final class Gaps {
        final NavigableMap<LocalDate,Set<String>> days=new TreeMap<>();
        LocalDate legacyThrough;
        Gaps(SQLiteDatabase db){
            if(hasTable(db,"queue_gaps"))try(Cursor c=db.rawQuery("SELECT day,source FROM queue_gaps",null)){
                while(c.moveToNext())days.computeIfAbsent(LocalDate.parse(c.getString(0)),k->new HashSet<>()).add(c.getString(1));
            }
            if(hasTable(db,"meta"))try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key='legacy_drop_through'",null)){
                if(c.moveToFirst())legacyThrough=java.time.Instant.ofEpochMilli(c.getLong(0)).atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }
        boolean matches(LocalDate from,LocalDate until,Set<String> sources){
            if(from.isAfter(until))return false;
            if(legacyThrough!=null&&!from.isAfter(legacyThrough))return true;
            for(Set<String> day:days.subMap(from,true,until,true).values())
                if(sources==null||!Collections.disjoint(day,sources))return true;
            return false;
        }
        boolean any(LocalDate from,LocalDate until){return matches(from,until,null);}
        boolean affectsActions(LocalDate from,LocalDate until){return matches(from,until,Set.of("events","visits","visit_history"));}
    }

    private static long scalar(SQLiteDatabase db,String sql){try(Cursor c=db.rawQuery(sql,null)){return c.moveToFirst()?c.getLong(0):0;}}
    private static PortraitEvidence.Decomposition decompose(SQLiteDatabase db,LocalDate start,LocalDate end,LocalDate today,Gaps gaps,Samples samples){
        if(start.equals(end))return PortraitEvidence.Decomposition.empty("单日范围不跨日比较");
        LocalDate last=end.isBefore(today)?end:today.minusDays(1),first=last.minusDays(13),mid=last.minusDays(6);
        if(java.time.temporal.ChronoUnit.DAYS.between(start,end)<6)return PortraitEvidence.Decomposition.empty("所选范围不足7日");
        if(first.isBefore(today.minusDays(29)))return PortraitEvidence.Decomposition.empty("比较超出30天明细保留范围");
        if(start.isAfter(first)&&!start.equals(mid)&&!start.equals(mid.plusDays(1)))return PortraitEvidence.Decomposition.empty("所选范围不能支持相邻两周");
        Map<String,Acc> old=new TreeMap<>(),now=new TreeMap<>();Acc oldTotal=new Acc(),newTotal=new Acc();
        String since=first.toString(),through=last.toString(),split=mid.toString();
        Set<String> knownTypes=Set.of("short_video","long_video","live_photo","image","live");
        for(Row r:samples.rows){
            if(!r.linkable||r.day.compareTo(since)<0||r.day.compareTo(through)>0)continue;
            boolean prev=r.day.compareTo(split)<0;
            String type=knownTypes.contains(r.type)?r.type:"other";
            (prev?old:now).computeIfAbsent(type,k->new Acc()).add(r);
            (prev?oldTotal:newTotal).add(r);
        }
        Set<String> types=new TreeSet<>(old.keySet());types.addAll(now.keySet());boolean common=oldTotal.total>0&&newTotal.total>0&&old.keySet().equals(now.keySet());
        boolean complete=covered(db,first,last);
        List<PortraitEvidence.Term> terms=new ArrayList<>();double composition=0,within=0;
        for(String type:types){PortraitEvidence.Group a=old.getOrDefault(type,new Acc()).group(),b=now.getOrDefault(type,new Acc()).group();double mix=0,inside=0;if(common){double w0=(double)a.denominator/oldTotal.total,w1=(double)b.denominator/newTotal.total;mix=100*(w1-w0)*(b.rate+a.rate)/2;inside=100*(b.rate-a.rate)*(w1+w0)/2;}composition+=mix;within+=inside;terms.add(new PortraitEvidence.Term(type,a,b,mix,inside));}
        boolean enough=adequate(oldTotal.group())&&adequate(newTotal.group())&&oldTotal.saved+newTotal.saved>=PortraitEvidence.MIN_POSITIVES;
        boolean retained=retained(db,first);
        boolean loss=gaps.affectsActions(first,last);
        boolean available=common&&!loss&&complete&&enough&&retained;
        String limitation=!common?"两期作品类型缺少共同支持，仅显示原始计数。":loss?"此比较窗可能缺少被队列拒绝的记录，仅并列保留样本。":!complete?"无法确认比较起点采集状态或期间关闭过采集，仅并列保留样本。":!retained?"明细保留水位未知或比较窗包含已裁剪记录，仅并列样本。":!enough?"两期保留样本未达到比较门槛，仅并列计数。":"仅对保留片段作描述性分解；历史完整性未知，不代表因果归因。";
        return new PortraitEvidence.Decomposition(first.toString(),mid.minusDays(1).toString(),mid.toString(),last.toString(),available,oldTotal.group(),newTotal.group(),available?composition:null,available?within:null,terms,limitation);
    }
}
