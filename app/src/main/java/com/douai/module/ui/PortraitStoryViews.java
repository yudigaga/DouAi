package com.douai.module.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.analytics.PortraitEvidence;
import com.douai.module.analytics.PortraitEvidence.*;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.function.Consumer;

/** The screen and paper compose the same stories from one frozen snapshot. */
final class PortraitStoryViews {
    private static final int NAVY=0xFF14243B, CREAM=0xFFFFF7E8, CORAL=0xFFFF986B, PARCHMENT=0xFFFFF5E6;
    private final Context context;
    private final PortraitEvidence data;
    private final PortraitReportViews.Host host;
    private final boolean paper,dark;
    private final JournalTopicColors topicColors;
    private final int ink,muted,orange,blue,rule;
    PortraitStoryViews(Context context,PortraitEvidence data,PortraitReportViews.Host host){this(context,data,host,false);}
    PortraitStoryViews(Context context,PortraitEvidence data,PortraitReportViews.Host host,boolean paper){
        this(context,data,host,paper,new JournalTopicColors(data,List.of(),null));
    }
    PortraitStoryViews(Context context,PortraitEvidence data,PortraitReportViews.Host host,boolean paper,JournalTopicColors colors){
        topicColors=colors;
        this.context=context;this.data=data;this.host=host;this.paper=paper;dark=!paper && ModuleTheme.isDark(context);
        ink=dark?0xFFF0F2F7:NAVY;muted=dark?0xFFBDC8D6:0xFF4A596B;
        orange=dark?CORAL:0xFFA33817;blue=dark?0xFF9EC7FF:0xFF244D78;rule=dark?0xFF59687A:0xFFB7C2CF;
    }
    static void appendPaper(Context c,PortraitEvidence data,PortraitReportViews.Host host){
        PortraitStoryViews story=new PortraitStoryViews(c,data,host,true);story.overview();story.chapters();
    }
    void chapters(){
        content(host.section("遇见了什么"));actions(host.section("什么让我动手"));changes(host.section("与上期相比"));
    }
    void overview(){
        LinearLayout section=host.section("这段时间的发现");
        LinearLayout cover=column(section);cover.setPadding(dp(18),dp(15),dp(18),dp(17));
        cover.setTag("report.keepTogether");ModuleTheme.preserveColors(cover);
        int fg=paper?NAVY:CREAM,accent=paper?orange:CORAL,soft=paper?muted:0xFFD1D9E4,grid=paper?0xFFC4BDB2:0xFF536476;
        cover.setBackgroundColor(paper?PARCHMENT:NAVY);
        TextView mast=text(cover,"个人内容年志",16,true,fg);mast.setLetterSpacing(.12f);
        text(cover,range(data.startDate,data.endDate)+"   /   "+data.browseDays+" 个浏览日",13,false,soft);
        rule(cover,grid,9,10);
        Observation first=data.observations.isEmpty()?null:data.observations.get(0);
        Topic a=first==null?null:findTopic(first.parentMetricId), b=a==null?null:findTopic(first.secondaryMetricId);
        if(a!=null){
            SpannableStringBuilder title=new SpannableStringBuilder();
            int firstInk=topicColors.color(a.label,!paper),secondInk=b==null?firstInk:topicColors.color(b.label,!paper);
            seriesTitle(title,a,b==null?"":"●  ",firstInk,fg);
            if(b!=null){title.append('\n');seriesTitle(title,b,"○  ",secondInk,soft);}
            TextView headline=text(cover,title,29,true,fg);
            headline.setTag("analytics.story.headline");headline.setLineSpacing(dp(2),1.02f);
            if(hasCounts(a) || hasCounts(b)){
                text(cover,hasGaps(a) || hasGaps(b)?"逐日出现次数 · 缺记录处留空":"逐日出现次数",13,true,soft);
                addSeries(cover,a,b,false,firstInk,secondInk,grid,soft,137);
            }
            if(b!=null && (!a.daily.isEmpty() || !b.daily.isEmpty())){
                String equal=a.total==b.total?"出现次数相同，":"";
                text(cover,equal+"出现天数相差 "+Math.abs(a.appearanceDays-b.appearanceDays)+" 天。",15,true,fg);
            }
        }else{
            TextView headline=text(cover,first!=null?first.title:data.browseDays>0?"记录里的 "+data.browseDays+" 个浏览日":"还没有可读的浏览记录",28,true,fg);
            headline.setTag("analytics.story.headline");
            if(first!=null){
                if(!metricCover(cover,first,accent,fg,soft))text(cover,first.body,15,false,soft);
            }
            else text(cover,data.browseDays>0?"本期尚无可比较的话题。":"有记录的日期将在这里呈现。",15,false,soft);
        }
        if(!paper){
            TextView detail=coverage(section);
            toggle(section,detail,"查看依据与覆盖范围","收起依据与覆盖范围");
        }
        host.analysis(section,"portrait");
    }
    private TextView coverage(LinearLayout section){
        StringBuilder evidence=new StringBuilder();
        for(Observation o:data.observations){
            if(evidence.length()>0)evidence.append("\n\n");
            evidence.append(o.title).append("\n").append(o.body);
            if(!o.startDate.isEmpty())evidence.append("\n").append(range(o.startDate,o.endDate));
            for(String limit:o.coverage)evidence.append("\n").append(limit);
        }
        for(String limit:data.coverage){if(evidence.length()>0)evidence.append("\n");evidence.append(limit);}
        if(evidence.length()==0)evidence.append("本期没有额外依据。");
        TextView detail=text(section,evidence,13,false,muted);detail.setTag("analytics.story.coverage");return detail;
    }
    /** Only draw a non-topic lead if the chosen observation maps back to its own series. */
    private boolean metricCover(LinearLayout cover,Observation observation,int accent,int fg,int soft){
        for(ActionRate r:data.actionRates)if(r.id.equals(observation.parentMetricId)){
            for(int i=0;i<2;i++){
                Group g=i==0?r.withTopic:r.withoutTopic;int color=i==0?accent:fg;
                text(cover,(i==0?"有此话题":"其他已识别话题")+"   "+group(g),15,true,color);
                if(g.rate!=null)mark(cover,0,g.rate,null,0,1,color,false);
            }
            if(r.withTopic.rate!=null || r.withoutTopic.rate!=null)axis(cover,"0%","100%",soft);
            text(cover,range(observation.startDate,observation.endDate),13,false,soft);
            return true;
        }
        for(Familiarity f:data.familiarity)if(f.id.equals(observation.parentMetricId)){
            text(cover,typeName(f.type)+" · "+sourceName(f.source),15,true,fg);
            double max=Math.max(f.familiar.q3==null?0:f.familiar.q3,f.unseen.q3==null?0:f.unseen.q3);
            for(int i=0;i<2;i++){
                Distribution d=i==0?f.familiar:f.unseen;int color=i==0?accent:fg;
                text(cover,(i==0?"近期遇见过":"没有保留的相遇记录")+"   "+d.n+" 个作品日",15,true,color);
                if(d.n>0){text(cover,"中位 "+seconds(d.median),14,false,soft);
                    if(max>0 && d.q1!=null && d.q3!=null)mark(cover,d.q1,d.q3,d.median,0,max,color,false);}
            }
            if(max>0){axis(cover,"0 秒",seconds(max),soft);text(cover,"粗线为中间一半，圆点为中位数。",13,false,soft);}
            text(cover,range(observation.startDate,observation.endDate),13,false,soft);
            return true;
        }
        return false;
    }
    private static boolean knownCount(TopicDay d){return d.count>0 || (d.views!=null && d.views>0);}
    private static boolean hasCounts(Topic t){if(t!=null)for(TopicDay d:t.daily)if(knownCount(d))return true;return false;}
    private static boolean hasGaps(Topic t){if(t!=null)for(TopicDay d:t.daily)if(!knownCount(d))return true;return false;}
    private Topic findTopic(String id){for(Topic t:data.topics)if(t.id.equals(id))return t;return null;}
    private static void seriesTitle(SpannableStringBuilder s,Topic t,String marker,int color,int secondary){
        int start=s.length();s.append(marker).append(t.label);s.setSpan(new ForegroundColorSpan(color),start,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        start=s.length();s.append("   ").append(String.valueOf(t.total)).append(" 次 / ").append(String.valueOf(t.appearanceDays)).append(" 天");
        s.setSpan(new RelativeSizeSpan(.52f),start,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(secondary),start,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    void content(LinearLayout section){
        if(data.topics.isEmpty())note(section,"本期暂无可识别的话题。");
        else{
            List<Topic> featured=new ArrayList<>(2);
            for(Observation o:data.observations)if(o.type.equals("topic")){
                Topic a=findTopic(o.parentMetricId),b=findTopic(o.secondaryMetricId);
                if(a!=null){featured.add(a);if(b!=null && a!=b)featured.add(b);break;}
            }
            if(featured.isEmpty())featured.add(data.topics.get(0));
            for(Topic t:featured)topic(section,t);
            if(data.topics.size()>featured.size())remaining(section,"其余 "+(data.topics.size()-featured.size())+" 个话题",extra->{
                for(Topic t:data.topics)if(!featured.contains(t)){
                    text(extra,t.label+"   "+t.total+" 次 · "+t.appearanceDays+" / "+t.browseDays+" 个浏览日",15,true,ink);
                    if(!paper && !t.daily.isEmpty())remaining(extra,"查看「"+t.label+"」逐日趋势",panel->topic(panel,t));
                }
            });
        }
        host.analysis(section,"topics");
    }
    private void topic(LinearLayout section,Topic t){
        LinearLayout group=block(section);
        int topicInk=topicColors.color(t.label,dark);
        title(group,t.label);text(group,t.total+" 次出现   /   "+t.appearanceDays+" 个出现日   /   "+t.browseDays+" 个浏览日",14,true,muted);
        if(!hasCounts(t)){note(group,"没有可用的逐日出现次数。");return;}
        text(group,"每日出现次数",13,true,topicInk);
        addSeries(group,t,null,false,topicInk,topicInk,rule,muted,78);
        boolean share=false;for(TopicDay d:t.daily)if(d.share!=null){share=true;break;}
        if(share){
            if(paper)group=block(section); // An independent figure may start on the following sheet.
            text(group,(paper?t.label+" · ":"")+"占当天浏览的比例",13,true,topicInk);
            addSeries(group,t,null,true,topicInk,topicInk,rule,muted,72);
        }else note(group,"浏览分母未知，无法计算每日占比。");
        note(group,"按自然日排列；没有可用记录的位置留空。");
        if(!paper){
            LinearLayout detail=column(section);
            Button button=control(section,"查看逐日明细");int[] page={-1};
            button.setOnClickListener(v->{int pages=(t.daily.size()+30)/31;page[0]=page[0]>=pages-1?-1:page[0]+1;
                detail.removeAllViews();if(page[0]<0){button.setText("查看逐日明细");return;}
                int start=page[0]*31,end=Math.min(start+31,t.daily.size());
                for(int i=start;i<end;i++){TopicDay day=t.daily.get(i);boolean unknown=day.views==null || day.views<=0;
                    text(detail,day.date+"   "+(unknown && day.count==0?"无出现次数记录":day.count+" 次")+" / "+(unknown?"浏览分母未知":day.views+" 次浏览 · "+percent(day.share)),13,false,muted);}
                button.setText("逐日明细 "+(start+1)+"–"+end+" / "+t.daily.size()+" 天 · "+(page[0]==pages-1?"收起":"下一页"));});
        }
    }
    private void addSeries(LinearLayout parent,Topic a,Topic b,boolean share,int c1,int c2,int guide,int labels,int height){
        if(data.startDate.isEmpty() || data.endDate.isEmpty())return;
        JournalSeriesChart chart=new JournalSeriesChart(context,a,b,share,data.startDate,data.endDate,c1,c2,guide,labels);
        parent.addView(chart,new LinearLayout.LayoutParams(-1,dp(height)+Math.round(chartFont()*1.6f)));
    }

    void actions(LinearLayout section){
        if(!data.actionRates.isEmpty() || !data.familiarity.isEmpty() || !data.flowStages.isEmpty() || !data.flowPaths.isEmpty())
            dates(section,data.detailStartDate,data.detailEndDate);
        if(data.actionRates.isEmpty())note(section,"暂无可关联到话题的主动操作片段。");
        else{
            ActionRate lead=null;
            for(Observation o:data.observations)for(ActionRate r:data.actionRates)
                if(r.id.equals(o.parentMetricId) && positives(r)>0)lead=r;
            if(lead==null)for(ActionRate r:data.actionRates){
                if(positives(r)==0)continue;
                if(lead==null || (r.eligible && !lead.eligible) || (r.eligible==lead.eligible && positives(r)>positives(lead)))lead=r;
            }
            if(lead!=null)rate(section,lead);
            else note(section,"保留片段中没有记录到这些操作。");
            ActionRate featured=lead;
            int count=data.actionRates.size()-(lead==null?0:1);
            if(count>0){
                Consumer<LinearLayout> summaries=area->{
                    LinkedHashSet<String> limits=new LinkedHashSet<>();
                    for(ActionRate r:data.actionRates)if(r!=featured){compactRate(area,r);limits.addAll(r.coverage);}
                    for(String limit:limits)note(area,limit);
                };
                if(count==1 && lead==null)summaries.accept(section);
                else remaining(section,"其余操作对照 · "+count+" 组",summaries);
            }
        }
        if(!data.flowStages.isEmpty() || !data.flowPaths.isEmpty())flow(section);
        if(!data.familiarity.isEmpty()){
            subhead(section,"再次遇见，停留有何不同");
            Familiarity selected=null;
            for(Observation o:data.observations)for(Familiarity f:data.familiarity)if(f.id.equals(o.parentMetricId))selected=f;
            if(selected==null)for(Familiarity f:data.familiarity){
                if(selected==null || (f.eligible && !selected.eligible) || (f.eligible==selected.eligible && f.familiar.n+f.unseen.n>selected.familiar.n+selected.unseen.n))selected=f;
            }
            Familiarity featured=selected;familiarity(section,featured);
            if(data.familiarity.size()>1)remaining(section,"其他停留对照 · "+(data.familiarity.size()-1)+" 组",more->{
                for(Familiarity f:data.familiarity)if(f!=featured){
                    text(more,typeName(f.type)+" · "+sourceName(f.source),15,true,ink);
                    note(more,"近期遇见："+distribution(f.familiar)+"\n没有保留相遇记录："+distribution(f.unseen)+"\n排除 "+f.excluded+" 个片段。"+(f.eligible?"":"样本或历史不足。"));
                }
            });
        }
        host.analysis(section,"features");
    }
    private static long positives(ActionRate r){return r.withTopic.numerator+r.withoutTopic.numerator;}
    private void compactRate(LinearLayout g,ActionRate r){
        text(g,r.topic+" · "+r.label,15,true,ink);
        note(g,"有此话题 "+group(r.withTopic)+" · "+r.withTopic.uniqueWorks+" 部 / "+r.withTopic.sampleDays+" 天\n其他已识别话题 "+group(r.withoutTopic)+" · "+r.withoutTopic.uniqueWorks+" 部 / "+r.withoutTopic.sampleDays+" 天");
        if(r.eligible && r.differencePoints!=null)note(g,"相差 "+points(r.differencePoints));
        if(!r.startDate.isEmpty() && (!r.startDate.equals(data.detailStartDate)||!r.endDate.equals(data.detailEndDate)))dates(g,r.startDate,r.endDate);
    }
    private void rate(LinearLayout section,ActionRate r){
        LinearLayout g=block(section);
        title(g,r.topic+" · "+r.label);
        if(!r.startDate.isEmpty() && (!r.startDate.equals(data.detailStartDate)||!r.endDate.equals(data.detailEndDate)))dates(g,r.startDate,r.endDate);
        actionGroup(g,"有此话题",r.withTopic,orange);
        actionGroup(g,"其他已识别话题",r.withoutTopic,blue);
        if(r.withTopic.rate!=null || r.withoutTopic.rate!=null)axis(g,"0%","100%");
        if(r.eligible && r.differencePoints!=null)text(g,"相差 "+points(r.differencePoints),18,true,ink);
        else note(g,"样本或覆盖不足，只列出已记录的片段。");
        note(g,"有此话题 "+r.withTopic.uniqueWorks+" 部作品 / "+r.withTopic.sampleDays+" 天；对照 "+r.withoutTopic.uniqueWorks+" 部作品 / "+r.withoutTopic.sampleDays+" 天");
        if(!r.coverage.isEmpty())note(g,String.join("\n",r.coverage));
    }
    private void actionGroup(LinearLayout g,String name,Group item,int color){
        text(g,name+"   "+group(item),15,true,color);
        if(item.rate!=null)mark(g,0,item.rate,null,0,1,color,false);
    }
    private void flow(LinearLayout section){
        subhead(section,"刷到之后，继续探索");
        if(data.hasLegacyAiStages)note(section,"旧记录仅保留各阶段首见，无法还原相邻路径。");
        for(StageCount stage:data.flowStages){
            text(section,kindName(stage.kind)+" · "+stageName(stage.stage)+"   "+stage.sessions+(stage.kind.equals("ai")?" 个会话":" 次独立操作")
                +(stage.kind.equals("download")&&stage.tasks>0?" / "+stage.tasks+" 个独立任务":""),15,false,ink);
        }
        if(!data.flowPaths.isEmpty()){
            text(section,"会话中实际相邻的两步",15,true,blue);
            List<FlowPath> paths=new ArrayList<>(data.flowPaths);
            paths.sort((a,b)->Long.compare(b.sessions,a.sessions));
            long max=paths.get(0).sessions;
            for(FlowPath edge:paths.subList(0,Math.min(3,paths.size()))){
                text(section,pathLabel(edge),15,false,ink);
                if(max>0)mark(section,0,edge.sessions,null,0,max,blue,false);
            }
            if(max>0)axis(section,"0",max+" 个会话");
            if(paths.size()>3)remaining(section,"其他相邻阶段 · "+(paths.size()-3)+" 条",area->{
                for(FlowPath edge:paths.subList(3,paths.size()))note(area,pathLabel(edge));
            });
        }
        note(section,"同一会话可经过多个阶段，不能相加。没有曝光分母，不计算转化率。");
    }
    private static String pathLabel(FlowPath edge){return stageName(edge.from)+" → "+stageName(edge.to)+"   "+edge.sessions+" 个会话";}
    private static String distribution(Distribution d){return d.n==0?"无可用样本":d.n+" 个作品日，中位 "+seconds(d.median)+"，中间一半 "+seconds(d.q1)+" — "+seconds(d.q3);}
    private void familiarity(LinearLayout section,Familiarity f){
        LinearLayout g=block(section);title(g,typeName(f.type)+" · "+sourceName(f.source));
        double max=0;for(Distribution d:new Distribution[]{f.familiar,f.unseen})if(d.n>0 && d.q3!=null)max=Math.max(max,d.q3);
        if(max>0)max=Math.max(1000,Math.ceil(max/1000)*1000);
        dist(g,"近 28 天遇见过",f.familiar,max,orange);dist(g,"没有保留的相遇记录",f.unseen,max,blue);
        if(max>0){axis(g,"0 秒",seconds(max));note(g,"粗线：中间一半样本；圆点：中位数。");}
        if(!f.eligible)note(g,"样本或历史不足，不比较停留差异。");
        note(g,"排除 "+f.excluded+" 个片段。可见停留不等于完播。");
        for(String limit:f.coverage)note(g,limit);
    }
    private void dist(LinearLayout g,String label,Distribution d,double max,int color){
        text(g,label+"   "+(d.n==0?"无可用样本":d.n+" 个作品日"),15,true,color);
        if(d.n==0)return;
        note(g,"中位 "+seconds(d.median)+"，中间一半 "+seconds(d.q1)+" — "+seconds(d.q3));
        if(max>0 && d.q1!=null && d.q3!=null)mark(g,d.q1,d.q3,d.median,0,max,color,false);
    }

    void changes(LinearLayout section){
        Decomposition d=data.decomposition;
        if(!d.previousStart.isEmpty()){
            title(section,"保存入口记录率");
            note(section,"前期 "+range(d.previousStart,d.previousEnd)+"   "+group(d.previous));
            note(section,"本期 "+range(d.currentStart,d.currentEnd)+"   "+group(d.current));
        }
        if(d.available && d.differencePoints!=null && d.compositionPoints!=null && d.withinPoints!=null){
            LinearLayout g=block(section);
            text(g,"两期相差 "+points(d.differencePoints),23,true,ink);
            double start=d.previous.rate*100, mix=start+d.compositionPoints, end=d.current.rate*100;
            double low=Math.floor(Math.min(0,mix)/5)*5,high=Math.max(5,Math.ceil(Math.max(start,Math.max(mix,end))/5)*5);
            step(g,"前期记录率   "+percent(d.previous.rate),0,start,low,high,blue);
            step(g,"内容构成   "+points(d.compositionPoints),start,mix,low,high,orange);
            step(g,"类内变化   "+points(d.withinPoints),mix,end,low,high,orange);
            step(g,"本期记录率   "+percent(d.current.rate),0,end,low,high,blue);
            axis(g,Math.round(low)+"%",Math.round(high)+"%");note(g,"各行共用记录率刻度。中间两项从上一行的终点起算，尖端标记变化方向。");
        }else if(d.differencePoints!=null)text(section,"保留样本率相差 "+points(d.differencePoints)+"；不作变化归因。",15,true,ink);
        note(section,d.limitation);if(d.available)host.analysis(section,"changes");
        if(paper){subhead(section,"依据与覆盖范围");coverage(section);}
    }
    private void step(LinearLayout g,String label,double from,double to,double low,double high,int color){
        text(g,label,15,true,color);mark(g,from,to,null,low,high,color,true);
    }
    private void mark(LinearLayout parent,double from,double to,Double point,double low,double high,int color,boolean arrow){
        View view=new View(context){
            final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            final android.graphics.Path path=new android.graphics.Path();
            @Override protected void onDraw(Canvas c){
                float l=dp(5),r=getWidth()-dp(5),y=getHeight()/2f;
                float a=l+(float)((from-low)/(high-low))*(r-l),b=l+(float)((to-low)/(high-low))*(r-l);
                p.setColor(rule);p.setStrokeWidth(dp(1));c.drawLine(l,y,r,y,p);c.drawLine(l,y-dp(2),l,y+dp(2),p);c.drawLine(r,y-dp(2),r,y+dp(2),p);
                p.setColor(color);float thick=dp(point==null?5:7);
                if(a!=b){
                    c.drawRect(Math.min(a,b),y-thick/2,Math.max(a,b),y+thick/2,p);
                    if(arrow){float stem=b+Math.signum(a-b)*Math.min(dp(7),Math.abs(a-b));path.reset();path.moveTo(b,y);path.lineTo(stem,y-dp(6));path.lineTo(stem,y+dp(6));path.close();c.drawPath(path,p);}
                }else if(point==null){c.drawCircle(b,y,dp(2),p);}
                if(point!=null)c.drawCircle(l+(float)((point-low)/(high-low))*(r-l),y,dp(5),p);
            }
        };
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(view,new LinearLayout.LayoutParams(-1,dp(18)));
    }
    private void axis(LinearLayout parent,String a,String b){axis(parent,a,b,muted);}
    private void axis(LinearLayout parent,String a,String b,int color){
        LinearLayout row=new LinearLayout(context);parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
        for(int i=0;i<2;i++){TextView label=new TextView(context);label.setText(i==0?a:b);label.setTextColor(color);label.setTextSize(12);label.setGravity(i==0?Gravity.START:Gravity.END);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));ModuleTheme.preserveColors(label);}
    }
    private static String typeName(String s){return s.matches("[a-z_]+")?LocalAnalytics.typeLabel(s):s;}
    private static String sourceName(String s){switch(s){case "manual":return "手动到达";case "automatic":return "自动到达";case "system":return "系统到达";case "unknown":case "":return "来源未知";default:return s;}}
    private static String kindName(String s){switch(s){case "ai":return "继续探索";case "download":return "下载过程";case "playback":return "播放状态";case "speed":return "播放速度";case "backward":return "主动回划";default:return "其他操作";}}
    private static String stageName(String s){
        switch(s){case "followup_click":return "主动追问";case "more_click":return "请求更多";case "video_click":return "点击相关作品";case "route_dispatched":return "派发打开请求";
        case "generated":return "生成推荐";case "visible":return "确认可见";case "dispatched":return "界面已派发";case "entry":return "打开入口";case "request":return "发起请求";case "accepted":return "任务已受理";case "task_end":return "任务结束";case "output_closed":return "文件流写出并关闭";case "cancelled":return "已取消";case "failure":return "失败";case "manual_select":return "手动选择倍率";case "restore":return "手动恢复原速";case "inherited":return "继承倍率";case "requested":return "请求调速";case "readback":return "倍率读回确认";case "playing":return "开始播放回调";case "paused":return "暂停回调";case "ended":return "播放结束回调";case "confirmed":return "切换确认";default:return "未识别阶段";}
    }
    static String points(double v){return (v>0?"+":v<0?"−":"")+String.format(Locale.ROOT,"%.1f",Math.abs(v))+" 个百分点";}
    private static String seconds(Double ms){return ms==null?"未知":String.format(Locale.ROOT,"%.1f 秒",ms/1000);}
    private static String percent(double n){return String.format(Locale.ROOT,"%.1f%%",n*100);}
    private static String group(Group g){return g.rate==null?"无可用片段，比例未知":g.numerator+"/"+g.denominator+" 段 · "+percent(g.rate);}
    private static String range(String a,String b){return a.equals(b)?a:a+" — "+b;}
    private void dates(LinearLayout g,String a,String b){if(!a.isEmpty() && !b.isEmpty() && a.compareTo(b)<=0)note(g,"明细范围  "+range(a,b));else note(g,"此范围没有可用明细日期。");}
    private void note(LinearLayout parent,String s){if(!s.isEmpty())text(parent,s,13,false,muted);}
    private void title(LinearLayout parent,String s){text(parent,s,20,true,ink);}
    private void subhead(LinearLayout parent,String s){
        LinearLayout heading=paper?column(parent):parent;
        rule(heading,rule,18,9);text(heading,s,21,true,ink);
        if(paper)heading.setTag("report.keepWithNext");
    }
    private void remaining(LinearLayout parent,String name,Consumer<LinearLayout> body){
        if(paper){text(parent,name,16,true,ink).setTag("report.keepWithNext");body.accept(parent);return;}
        Button b=control(parent,name);LinearLayout panel=column(parent);panel.setVisibility(View.GONE);
        b.setOnClickListener(v->{boolean show=panel.getVisibility()!=View.VISIBLE;if(show && panel.getChildCount()==0)body.accept(panel);panel.setVisibility(show?View.VISIBLE:View.GONE);b.setText(show?"收起"+name:name);});
    }
    private void toggle(LinearLayout parent,View content,String open,String close){
        content.setVisibility(View.GONE);Button b=control(parent,open);b.setContentDescription(open+"，已收起");
        b.setOnClickListener(v->{boolean show=content.getVisibility()!=View.VISIBLE;content.setVisibility(show?View.VISIBLE:View.GONE);b.setText(show?close:open);b.setContentDescription(show?close+"，已展开":open+"，已收起");});
    }
    private Button control(LinearLayout parent,String s){Button b=host.button(context);b.setAllCaps(false);b.setText(s);b.setMinHeight(dp(48));parent.addView(host.controlView(b),new LinearLayout.LayoutParams(-1,-2));return b;}
    private LinearLayout column(LinearLayout parent){LinearLayout child=new LinearLayout(context);child.setOrientation(LinearLayout.VERTICAL);parent.addView(child,new LinearLayout.LayoutParams(-1,-2));return child;}
    private LinearLayout block(LinearLayout parent){LinearLayout g=column(parent);g.setTag("report.keepTogether");g.setPadding(0,dp(8),0,dp(9));return g;}
    private void rule(LinearLayout parent,int color,int top,int bottom){View v=new View(context);v.setBackgroundColor(color);ModuleTheme.preserveColors(v);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(1));lp.setMargins(0,dp(top),0,dp(bottom));parent.addView(v,lp);}
    private TextView text(LinearLayout g,CharSequence s,int size,boolean bold,int color){
        TextView t=new TextView(context);t.setText(s);t.setTextColor(color);t.setTextSize(size);t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        t.setIncludeFontPadding(false);t.setGravity(Gravity.START);t.setTextIsSelectable(true);t.setLineSpacing(dp(2),1.05f);t.setPadding(0,dp(3),0,dp(3));
        ModuleTheme.preserveColors(t);g.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;
    }
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private float chartFont(){return android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,11,context.getResources().getDisplayMetrics());}
}
