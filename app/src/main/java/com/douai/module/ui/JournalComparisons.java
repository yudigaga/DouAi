package com.douai.module.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.douai.module.analytics.AnalyticsSnapshot.Rank;
import com.douai.module.analytics.PortraitSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Shared, labelled comparisons for one frozen phone/print edition. */
final class JournalComparisons {
    private static final int INK = 0xff21344a, MUTED = 0xff536471;
    private static final int[] SERIES = {0xffbd482d, 0xff28638a, 0xff7854a4, 0xff247563};
    private static final int[] NIGHT_SERIES = {0xffff9675,0xff86cced,0xffddb6ff,0xff7de1c9};
    private JournalComparisons() {}
    private static int tint(Context c, boolean paper, int color) {
        if (paper || !ModuleTheme.isDark(c)) return color;
        if (color == INK) return 0xffeef2f8;
        if (color == MUTED) return 0xffc1c9d3;
        for (int i=0;i<SERIES.length;i++) if (color==SERIES[i]) return NIGHT_SERIES[i];
        return color;
    }

    static void rhythm(Context c, LinearLayout parent, long[][] hours, boolean paper) {
        parent = figure(c,parent,paper);
        long[][] groups = new long[2][24];
        long[] totals = new long[2];
        for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) {
            int g = d < 5 ? 0 : 1;
            groups[g][h] += hours[d][h]; totals[g] += hours[d][h];
        }
        label(c,parent,"工作日与周末 · 24 小时",18,INK,paper,true);
        if (totals[0] == 0 && totals[1] == 0) {
            label(c,parent,"所选时段没有浏览记录。",14,MUTED,paper,false); return;
        }
        label(c,parent,"同一纵轴：每小时占各自分组浏览次数的比例。",13,MUTED,paper,false);
        String[] names = {"工作日","周末"};
        double max=0;
        StringBuilder accessible = new StringBuilder("按各组总浏览次数换算；");
        for (int g=0;g<2;g++) {
            if (totals[g]==0) { label(c,parent,names[g]+"：无浏览样本",14,MUTED,paper,false); continue; }
            int peak=0;
            for (int h=0;h<24;h++) {
                if (groups[g][h]>groups[g][peak]) peak=h;
                max=Math.max(max, (double)groups[g][h]/totals[g]);
                accessible.append(names[g]).append(h).append("时 ").append(groups[g][h]).append('/').append(totals[g]).append("；");
            }
            label(c,parent,String.format(Locale.ROOT,"%s  %02d时峰值 · %d/%d 次（%.1f%%）",names[g],peak,groups[g][peak],totals[g],100.0*groups[g][peak]/totals[g]),14,SERIES[g],paper,true);
        }
        final double top=Math.max(.1,Math.ceil(max*10-1e-9)/10);
        Plot plot=new Plot(c,paper) {
            @Override protected void onDraw(Canvas cv) {
                float l=dp(42),r=getWidth()-dp(14),t=dp(16),b=getHeight()-dp(26);
                if(r<=l || b<=t) return;
                for(int i=0;i<=2;i++) {
                    float y=b-(b-t)*i/2;
                    line(cv,l,y,r,y,grid(),1); word(cv,String.format(Locale.ROOT,"%.0f%%",100*top*i/2),l-dp(5),y+dp(4),Paint.Align.RIGHT);
                }
                for(int h:new int[]{0,6,12,18,23}) word(cv,h+"时",l+(r-l)*h/23,b+dp(18),Paint.Align.CENTER);
                for(int g=0;g<2;g++) {
                    if(totals[g]==0) continue;
                    float px=0,py=0;
                    for(int h=0;h<24;h++) {
                        float x=l+(r-l)*h/23, y=b-(b-t)*(float)(groups[g][h]/(totals[g]*top));
                        if(h>0) line(cv,px,py,x,y,series(g),g==0?2.5f:1.5f);
                        if(h%3==0) dot(cv,x,y,series(g),g>0);
                        px=x;py=y;
                    }
                }
            }
        };
        mount(c,parent,plot,"analytics.journal.hour-profiles",accessible.toString(),146);
    }

    static void ranks(Context c, LinearLayout parent, PortraitSnapshot.Comparison window, boolean paper, JournalTopicColors colors) {
        parent = figure(c,parent,paper);
        label(c,parent,"话题名次 · 前期到本期",18,INK,paper,true);
        if(window==null || window.previousStart.isEmpty()) {label(c,parent,"缺少可比较的前期。",14,MUTED,paper,false);return;}
        label(c,parent,"前期 "+window.previousStart+" — "+window.previousEnd+"\n本期 "+window.currentStart+" — "+window.currentEnd,13,MUTED,paper,false);
        List<Rank> before=named(window.previousTopics),after=named(window.currentTopics);
        if(before.isEmpty() || after.isEmpty()) {label(c,parent,"一侧没有可识别话题，不画名次迁移。",14,MUTED,paper,false);return;}
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        for(int i=0;i<Math.min(2,before.size());i++)keys.add(before.get(i).label);
        for(int i=0;i<Math.min(2,after.size());i++)keys.add(after.get(i).label);
        List<String> names=new ArrayList<>(keys);
        int[][] positions=new int[names.size()][2];
        int[] inks=new int[names.size()];
        String[] symbols={"●","■","◆","▲"};
        int ceiling=2;
        StringBuilder desc=new StringBuilder("左为前期，右为本期。");
        for(int i=0;i<names.size();i++) {
            String name=names.get(i);
            int a=position(before,name),b=position(after,name);
            positions[i][0]=a; positions[i][1]=b; ceiling=Math.max(ceiling,Math.max(a,b));
            String row=name+"   "+rankValue(before,name,a)+"  →  "+rankValue(after,name,b);
            inks[i]=colors.color(name,!paper && ModuleTheme.isDark(c));
            label(c,parent,symbols[i]+"  "+row,14,inks[i],paper,true);desc.append(row).append('；');
        }
        final int max=ceiling;
        Plot plot=new Plot(c,paper) {
            @Override protected void onDraw(Canvas cv) {
                float l=dp(50),r=getWidth()-dp(32),t=dp(20),b=getHeight()-dp(26);
                if(r<=l || b<=t) return;
                for(int idx=1;idx<=max;idx++) {
                    float y=t+(b-t)*(idx-1)/(max-1);
                    line(cv,l,y,r,y,grid(),1);word(cv,"#"+idx,l-dp(6),y+dp(4),Paint.Align.RIGHT);
                }
                for(int i=0;i<positions.length;i++) {
                    int a=positions[i][0],z=positions[i][1];
                    float y1=t+(b-t)*(a-1)/(max-1),y2=t+(b-t)*(z-1)/(max-1);
                    if(window.previousComplete && a>0 && z>0)
                        line(cv,l+dp(tieInset(positions,i,0)),y1,r-dp(tieInset(positions,i,1)),y2,inks[i],2);
                }
                for(int i=0;i<positions.length;i++) for(int side=0;side<2;side++) {
                    int rank=positions[i][side];
                    if(rank<=0)continue;
                    float inset=dp(tieInset(positions,i,side));
                    marker(cv,side==0?l+inset:r-inset,t+(b-t)*(rank-1)/(max-1),inks[i],i);
                }
                word(cv,"前期",l,b+dp(18),Paint.Align.CENTER);word(cv,"本期",r,b+dp(18),Paint.Align.CENTER);
            }
        };
        mount(c,parent,plot,"analytics.journal.rank-lines",desc.toString(),Math.max(122,42+23*max));
        label(c,parent,"按出现次数排名，同次数并列；未入摘要不等于零。"+(!window.previousComplete?"前期覆盖不足，只并列保留记录。":""),13,MUTED,paper,false);
    }
    // Move only along the time axis: tied endpoints keep exactly the same rank ordinate.
    private static int tieInset(int[][] ranks,int row,int side) {
        int inset=0;
        boolean joined=ranks[row][1-side]>0;
        for(int i=0;i<ranks.length;i++)if(ranks[i][side]==ranks[row][side]){
            boolean otherJoined=ranks[i][1-side]>0;
            // Keep unpaired dots beyond the line ends so a nearby line cannot imply a migration.
            if((joined && !otherJoined) || (joined==otherJoined && i<row))inset+=12;
        }
        return inset;
    }
    private static LinearLayout figure(Context c,LinearLayout parent,boolean paper) {
        if(!paper)return parent;
        LinearLayout group=new LinearLayout(c);group.setOrientation(LinearLayout.VERTICAL);
        group.setTag("report.keepTogether");parent.addView(group,-1,-2);
        return group;
    }
    private static List<Rank> named(List<Rank> ranks) {
        List<Rank> result=new ArrayList<>();
        for(int i=0;i<ranks.size();i++) {
            Rank r=ranks.get(i);
            if(i==8 && r.label.matches("其他（[0-9]+ 类）")) continue;
            result.add(r);
        }
        return result;
    }
    static int position(List<Rank> ranks,String name) {
        for(Rank r:ranks) if(r.label.equals(name)) {
            int pos=1;for(Rank x:ranks)if(x.count>r.count)pos++;return pos;
        }
        return 0;
    }
    private static String rankValue(List<Rank> ranks,String name,int pos) {
        for(Rank r:ranks)if(r.label.equals(name))return "#"+pos+"（"+r.count+" 次）";
        return "未入摘要";
    }
    private static void label(Context c,LinearLayout parent,String content,int sp,int color,boolean paper,boolean medium) {
        TextView t=new TextView(c);t.setText(content);t.setTextSize(sp);t.setTextColor(tint(c,paper,color));
        if(medium)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setTextIsSelectable(true);int y=(int)(c.getResources().getDisplayMetrics().density*4);t.setPadding(0,y,0,y);parent.addView(t,-1,-2);
    }
    private static void mount(Context c,LinearLayout parent,View plot,String tag,String desc,int height) {
        plot.setTag(tag);plot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);plot.setContentDescription(desc);
        parent.addView(plot,-1,Math.round(c.getResources().getDisplayMetrics().density*height));
    }
    private abstract static class Plot extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final boolean paper;
        final android.graphics.Path shape=new android.graphics.Path();
        Plot(Context c,boolean paper){super(c);this.paper=paper;}
        float dp(float v){return v*getResources().getDisplayMetrics().density;}
        boolean dark(){return !paper && ModuleTheme.isDark(getContext());}
        int series(int i){return tint(getContext(),paper,SERIES[i]);}
        int grid(){return dark()?0xff46535d:0xffd7dde0;}
        void word(Canvas c,String s,float x,float y,Paint.Align align){p.setStyle(Paint.Style.FILL);p.setColor(dark()?0xffe3e9eb:MUTED);p.setTextAlign(align);p.setTextSize(dp(11));c.drawText(s,x,y,p);}
        void line(Canvas c,float x,float y,float xx,float yy,int color,float weight){p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(weight));c.drawLine(x,y,xx,yy,p);}
        void dot(Canvas c,float x,float y,int color,boolean ring){p.setColor(color);p.setStyle(ring?Paint.Style.STROKE:Paint.Style.FILL);p.setStrokeWidth(dp(2));c.drawCircle(x,y,dp(ring?4:3),p);}
        void marker(Canvas c,float x,float y,int color,int kind) {
            float size=dp(4);p.setColor(color);p.setStyle(Paint.Style.FILL);
            if(kind==0){c.drawCircle(x,y,size,p);return;}
            if(kind==1){c.drawRect(x-size,y-size,x+size,y+size,p);return;}
            shape.reset();shape.moveTo(x,y-size);
            if(kind==2){shape.lineTo(x+size,y);shape.lineTo(x,y+size);shape.lineTo(x-size,y);}
            else{shape.lineTo(x+size,y+size);shape.lineTo(x-size,y+size);}
            shape.close();c.drawPath(shape,p);
        }
    }
}
