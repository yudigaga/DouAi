package com.douai.module.analytics;

import java.util.List;

/** Frozen local observations. Nullable numbers mean unknown, never zero. */
public final class PortraitEvidence {
    public static final int MIN_BROWSE_DAYS=3, MIN_TOPIC_COUNT=5, MIN_GROUP_VISITS=20,
            MIN_GROUP_WORKS=10, MIN_GROUP_DAYS=3, MIN_POSITIVES=5;
    public final String startDate,endDate,detailStartDate,detailEndDate;
    public final int browseDays;
    public final List<Topic> topics;
    public final List<ActionRate> actionRates;
    public final Decomposition decomposition;
    public final List<Observation> observations;
    public final List<String> coverage;
    public final List<StageCount> flowStages;
    public final List<FlowPath> flowPaths;
    public final boolean hasLegacyAiStages;
    public final List<Familiarity> familiarity;
    public PortraitEvidence(String startDate,String endDate,int browseDays,List<Topic> topics,
            List<ActionRate> actionRates,Decomposition decomposition,List<Observation> observations,List<String> coverage){
        this(startDate,endDate,browseDays,topics,actionRates,decomposition,observations,coverage,List.of(),List.of(),List.of());
    }
    public PortraitEvidence(String startDate,String endDate,int browseDays,List<Topic> topics,List<ActionRate> actionRates,Decomposition decomposition,List<Observation> observations,List<String> coverage,List<StageCount> flowStages,List<FlowPath> flowPaths,List<Familiarity> familiarity){
        this(startDate,endDate,browseDays,topics,actionRates,decomposition,observations,coverage,flowStages,flowPaths,familiarity,startDate,endDate);
    }
    PortraitEvidence(String startDate,String endDate,int browseDays,List<Topic> topics,List<ActionRate> actionRates,Decomposition decomposition,List<Observation> observations,List<String> coverage,List<StageCount> flowStages,List<FlowPath> flowPaths,List<Familiarity> familiarity,String detailStartDate,String detailEndDate){
        this(startDate,endDate,browseDays,topics,actionRates,decomposition,observations,coverage,flowStages,flowPaths,familiarity,detailStartDate,detailEndDate,false);
    }
    PortraitEvidence(String startDate,String endDate,int browseDays,List<Topic> topics,List<ActionRate> actionRates,Decomposition decomposition,List<Observation> observations,List<String> coverage,List<StageCount> flowStages,List<FlowPath> flowPaths,List<Familiarity> familiarity,String detailStartDate,String detailEndDate,boolean hasLegacyAiStages){
        this.hasLegacyAiStages=hasLegacyAiStages;
        this.detailStartDate=detailStartDate;this.detailEndDate=detailEndDate;
        this.flowStages=List.copyOf(flowStages);this.flowPaths=List.copyOf(flowPaths);this.familiarity=List.copyOf(familiarity);
        this.startDate=startDate;this.endDate=endDate;this.browseDays=browseDays;this.topics=List.copyOf(topics);
        this.actionRates=List.copyOf(actionRates);this.decomposition=decomposition;
        this.observations=List.copyOf(observations);this.coverage=List.copyOf(coverage);
    }
    public boolean hasStories(){return browseDays>0||!topics.isEmpty()||!actionRates.isEmpty()||!observations.isEmpty()||!flowStages.isEmpty()||!flowPaths.isEmpty()||!familiarity.isEmpty();}
    public static PortraitEvidence empty(String start,String end){return new PortraitEvidence(start,end,0,List.of(),List.of(),Decomposition.empty("尚无可比明细"),List.of(),List.of("尚无可用记录"));}
    public static final class StageCount {
        public final String kind,stage;public final long sessions,tasks;
        public StageCount(String stage,long sessions){this("ai",stage,sessions,0);}
        public StageCount(String kind,String stage,long sessions,long tasks){this.kind=kind;this.stage=stage;this.sessions=sessions;this.tasks=tasks;}
    }
    public static final class FlowPath {
        public final String from,to;public final long sessions;
        public FlowPath(String from,String to,long sessions){this.from=from;this.to=to;this.sessions=sessions;}
    }
    public static final class Distribution {
        public final long n;public final Double median,q1,q3;
        public Distribution(long n,Double median,Double q1,Double q3){this.n=n;this.median=median;this.q1=q1;this.q3=q3;}
    }
    public static final class Familiarity {
        public final String id,type,source;public final Distribution familiar,unseen;public final long excluded;public final boolean eligible;public final List<String> coverage;
        public Familiarity(String type,String source,Distribution familiar,Distribution unseen,long excluded,boolean eligible,List<String> coverage){this.id="familiarity:"+type+":"+source;this.type=type;this.source=source;this.familiar=familiar;this.unseen=unseen;this.excluded=excluded;this.eligible=eligible;this.coverage=List.copyOf(coverage);}
    }    public static final class TopicDay {
        public final String date; public final long count; public final Long views; public final Double share;
        public TopicDay(String date,long count,Long views){this.date=date;this.count=count;this.views=views;share=views==null||views<=0?null:(double)count/views;}
    }
    public static final class Topic {
        public final String id,label; public final long total; public final int appearanceDays,browseDays;
        public final double peakDayShare; public final List<TopicDay> daily;
        public Topic(String id,String label,long total,int appearanceDays,int browseDays,double peakDayShare,List<TopicDay> daily){this.id=id;this.label=label;this.total=total;this.appearanceDays=appearanceDays;this.browseDays=browseDays;this.peakDayShare=peakDayShare;this.daily=List.copyOf(daily);}
    }
    public static final class Group {
        public final long numerator,denominator,uniqueWorks; public final int sampleDays; public final Double rate;
        public Group(long n,long total,long works,int days){numerator=n;denominator=total;uniqueWorks=works;sampleDays=days;rate=total==0?null:(double)n/total;}
    }
    public static final class ActionRate {
        public final String id,topic,action,label,startDate,endDate; public final Group withTopic,withoutTopic;
        public final Double differencePoints; public final boolean eligible; public final List<String> coverage;
        public ActionRate(String id,String topic,String action,String label,Group withTopic,Group withoutTopic,boolean eligible,List<String> coverage){this(id,topic,action,label,withTopic,withoutTopic,eligible,coverage,"","");}
        public ActionRate(String id,String topic,String action,String label,Group withTopic,Group withoutTopic,boolean eligible,List<String> coverage,String startDate,String endDate){this.startDate=startDate;this.endDate=endDate;this.id=id;this.topic=topic;this.action=action;this.label=label;this.withTopic=withTopic;this.withoutTopic=withoutTopic;this.eligible=eligible;this.coverage=List.copyOf(coverage);differencePoints=withTopic.rate==null||withoutTopic.rate==null?null:100*(withTopic.rate-withoutTopic.rate);}
    }
    public static final class Term {
        public final String type; public final Group previous,current; public final double compositionPoints,withinPoints;
        public Term(String type,Group previous,Group current,double compositionPoints,double withinPoints){this.type=type;this.previous=previous;this.current=current;this.compositionPoints=compositionPoints;this.withinPoints=withinPoints;}
    }
    public static final class Decomposition {
        public final String previousStart,previousEnd,currentStart,currentEnd,limitation;
        public final boolean available; public final Group previous,current; public final Double compositionPoints,withinPoints,differencePoints;
        public final List<Term> terms;
        public Decomposition(String ps,String pe,String cs,String ce,boolean available,Group previous,Group current,Double compositionPoints,Double withinPoints,List<Term> terms,String limitation){previousStart=ps;previousEnd=pe;currentStart=cs;currentEnd=ce;this.available=available;this.previous=previous;this.current=current;this.compositionPoints=compositionPoints;this.withinPoints=withinPoints;differencePoints=previous.rate==null||current.rate==null?null:100*(current.rate-previous.rate);this.terms=List.copyOf(terms);this.limitation=limitation;}
        public static Decomposition empty(String reason){return new Decomposition("","","","",false,new Group(0,0,0,0),new Group(0,0,0,0),null,null,List.of(),reason);}
    }
    public static final class Observation {
        public final String id,type,title,body,comparison,unit,startDate,endDate,parentMetricId,sampleId,secondaryMetricId;
        public final Long numerator,denominator,sampleWorks; public final List<String> coverage;
        public Observation(String id,String type,String title,String body,String comparison,String unit,String startDate,String endDate,Long numerator,Long denominator,Long sampleWorks,String parentMetricId,String sampleId,List<String> coverage){this(id,type,title,body,comparison,unit,startDate,endDate,numerator,denominator,sampleWorks,parentMetricId,sampleId,coverage,"");}
        public Observation(String id,String type,String title,String body,String comparison,String unit,String startDate,String endDate,Long numerator,Long denominator,Long sampleWorks,String parentMetricId,String sampleId,List<String> coverage,String secondaryMetricId){this.secondaryMetricId=secondaryMetricId;this.id=id;this.type=type;this.title=title;this.body=body;this.comparison=comparison;this.unit=unit;this.startDate=startDate;this.endDate=endDate;this.numerator=numerator;this.denominator=denominator;this.sampleWorks=sampleWorks;this.parentMetricId=parentMetricId;this.sampleId=sampleId;this.coverage=List.copyOf(coverage);}
    }
}
