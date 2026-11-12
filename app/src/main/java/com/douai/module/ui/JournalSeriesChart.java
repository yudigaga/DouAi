package com.douai.module.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import com.douai.module.analytics.PortraitEvidence.Topic;
import com.douai.module.analytics.PortraitEvidence.TopicDay;
import java.time.LocalDate;
import java.util.Locale;

/** A date is always a position on the calendar, even when a series has no value there. */
final class JournalSeriesChart extends View {
    private final boolean share;
    private final int firstInk, secondInk, guide, labels;
    private final LocalDate start, end;
    private final int days;
    private final double[][] values;
    private final double ceiling;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path trace = new Path(), area = new Path();
    private final float density, font;
    private final DashPathEffect dash;

    JournalSeriesChart(Context c, Topic a, Topic b, boolean share, String from, String to,
            int firstInk, int secondInk, int guide, int labels) {
        super(c);
        this.share=share;
        this.firstInk=firstInk; this.secondInk=secondInk; this.guide=guide; this.labels=labels;
        start=LocalDate.parse(from); end=LocalDate.parse(to);
        days=(int)(end.toEpochDay()-start.toEpochDay()+1);
        density=c.getResources().getDisplayMetrics().density;
        font=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,11,c.getResources().getDisplayMetrics());
        dash=new DashPathEffect(new float[]{6*density,4*density},0);
        values=new double[b==null?1:2][days];
        double max=0;
        for(int k=0;k<values.length;k++) {
            java.util.Arrays.fill(values[k],Double.NaN);
            for(TopicDay d:(k==0?a:b).daily) {
                int i=(int)(LocalDate.parse(d.date).toEpochDay()-start.toEpochDay());
                if(i<0 || i>=days) continue;
                // The rank count is observed even when its browse denominator is absent.
                // A synthetic zero in a day without browses is not an observed zero.
                Double value=share?d.share:(d.count>0 || (d.views!=null && d.views>0)?Double.valueOf(d.count):null);
                if(value!=null){values[k][i]=value;max=Math.max(max,value);}
            }
        }
        ceiling=share?Math.max(.1,Math.ceil(max*10)/10):Math.max(1,Math.ceil(max));
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        setTag("analytics.story.chart");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        StringBuilder desc=new StringBuilder(share?"每日占当日浏览比例":"每日话题出现次数").append("，").append(from).append(" 至 ").append(to);
        appendSummary(desc,a); if(b!=null)appendSummary(desc,b);
        desc.append("。空缺：没有可用记录。逐日数值见内容章。");
        setContentDescription(desc.toString());
    }
    private void appendSummary(StringBuilder s,Topic t){s.append("；").append(t.label).append("：").append(t.total).append(" 次，出现于 ").append(t.appearanceDays).append(" 个浏览日");}
    // Package-visible for tests checking the actual plotted coordinates, not an independent summary.
    double value(int series,int day) { return values[series][day]; }
    private String tick(double v){return share?Math.round(v*100)+"%":String.valueOf(Math.round(v));}
    private void label(Canvas c,String s,float x,float y,Paint.Align align) {
        paint.setStyle(Paint.Style.FILL);paint.setPathEffect(null);paint.setColor(labels);paint.setTextAlign(align);paint.setTextSize(font);c.drawText(s,x,y,paint);
    }
    private void rule(Canvas c,float x,float y,float r) {paint.setColor(guide);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);c.drawLine(x,y,r,y,paint);}
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        paint.setTextSize(font);
        float left=paint.measureText(tick(ceiling))+8*density,right=getWidth()-5*density,top=font,base=getHeight()-font*1.65f-7*density;
        float height=base-top;
        if(right<=left||height<=0)return;
        rule(c,left,base,right);rule(c,left,top,right);
        label(c,tick(ceiling),left-6*density,top+font*.32f,Paint.Align.RIGHT);
        label(c,tick(0),left-6*density,base+font*.32f,Paint.Align.RIGHT);
        if(days>1 && ((share && ceiling>=.2)||(!share && ceiling>=4 && ceiling%2==0))) {
            rule(c,left,base-height/2,right);label(c,tick(ceiling/2),left-6*density,base-height/2+font*.32f,Paint.Align.RIGHT);
        }
        for(int k=0;k<values.length;k++){
            int color=k==0?firstInk:secondInk;
            int index=0;
            while(index<days){
                while(index<days && Double.isNaN(values[k][index]))index++;
                int begin=index;
                while(index<days && !Double.isNaN(values[k][index]))index++;
                if(begin==index)break;
                trace.reset();float x0=x(begin,left,right),last=x0;
                for(int i=begin;i<index;i++){
                    last=x(i,left,right);float y=base-(float)(height*values[k][i]/ceiling);
                    if(i==begin)trace.moveTo(last,y);else trace.lineTo(last,y);
                }
                if(k==0 && index-begin>1){
                    area.set(trace);area.lineTo(last,base);area.lineTo(x0,base);area.close();
                    paint.setStyle(Paint.Style.FILL);paint.setColor((color&0x00FFFFFF)|0x20000000);c.drawPath(area,paint);
                }
                paint.setStyle(Paint.Style.STROKE);paint.setColor(color);paint.setStrokeWidth((k==0?3:2.5f)*density);
                paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);paint.setPathEffect(k==1?dash:null);c.drawPath(trace,paint);paint.setPathEffect(null);
                for(int i=begin;i<index;i++){
                    if(days>18 && index-begin>1 && i!=begin && i!=index-1)continue;
                    float xx=x(i,left,right),yy=base-(float)(height*values[k][i]/ceiling);
                    // Hollow over filled on coincident observations keeps both symbols visible.
                    float radius=(k==0?3f:5.2f)*density;
                    paint.setStyle(k==0?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(1.8f*density);
                    c.drawCircle(xx,yy,radius,paint);
                }
            }
        }
        paint.setTextSize(font);
        float baseline=getHeight()-font*.23f;
        label(c,date(start),left,baseline,Paint.Align.LEFT);
        if(days>1 && right-left>paint.measureText(date(start))+paint.measureText(date(end))+8*density)label(c,date(end),right,baseline,Paint.Align.RIGHT);
        if(days>4){
            int mid=(days-1)/2;String s=date(start.plusDays(mid));float center=x(mid,left,right);
            if(center-paint.measureText(s)/2>left+paint.measureText(date(start))+6*density && center+paint.measureText(s)/2<right-paint.measureText(date(end))-6*density)
                label(c,s,center,baseline,Paint.Align.CENTER);
        }
    }
    private float x(int i,float l,float r){return days<=1?(l+r)/2:l+(r-l)*i/(days-1);}
    private static String date(LocalDate d){return String.format(Locale.ROOT,"%02d/%02d",d.getMonthValue(),d.getDayOfMonth());}
}
