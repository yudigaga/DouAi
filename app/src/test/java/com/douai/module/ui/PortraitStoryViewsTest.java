package com.douai.module.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PortraitStoryViewsTest {
    @Test public void emptyEvidenceStillShowsCoverageAndReadableFallback() throws Exception {
        Class<?> evidence = Class.forName("com.douai.module.analytics.PortraitEvidence");
        Method empty = evidence.getDeclaredMethod("empty", String.class, String.class);
        Object data = empty.invoke(null, "2026-11-01", "2026-11-07");
        LinearLayout root = new LinearLayout(RuntimeEnvironment.getApplication());
        root.setOrientation(LinearLayout.VERTICAL);
        PortraitReportViews.Host host = new PortraitReportViews.Host() {
            public LinearLayout section(String title) {
                LinearLayout section = new LinearLayout(root.getContext());
                section.setOrientation(LinearLayout.VERTICAL); root.addView(section); return section;
            }
            public void analysis(LinearLayout section, String key) {}
        };
        Class<?> views = Class.forName("com.douai.module.ui.PortraitStoryViews");
        Object story = views.getDeclaredConstructor(Context.class, evidence, PortraitReportViews.Host.class)
                .newInstance(root.getContext(), data, host);
        views.getDeclaredMethod("overview").invoke(story);
        assertNotNull(root.findViewWithTag("analytics.story.coverage"));
        assertNotNull(root.findViewWithTag("analytics.story.headline"));
        assertNull(root.findViewWithTag("analytics.story.chart"));
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue(root.getMeasuredHeight() > 0);
    }
    @Test public void signedPointsRetainDirectionAndUnit() throws Exception {
        Method format = Class.forName("com.douai.module.ui.PortraitStoryViews")
                .getDeclaredMethod("points", double.class);
        assertEquals("+8.0 个百分点", format.invoke(null, 8d));
        assertEquals("−3.5 个百分点", format.invoke(null, -3.5d));
        assertEquals("0.0 个百分点", format.invoke(null, 0d));
    }
    @Test public void threeGraphicsKeepDenominatorsAndDirectionsAtNarrowWidth() {
        com.douai.module.analytics.PortraitEvidence.Group before=new com.douai.module.analytics.PortraitEvidence.Group(14,100,40,7);
        com.douai.module.analytics.PortraitEvidence.Group after=new com.douai.module.analytics.PortraitEvidence.Group(22,100,40,7);
        com.douai.module.analytics.PortraitEvidence.Decomposition change=new com.douai.module.analytics.PortraitEvidence.Decomposition(
            "2026-10-28","2026-11-03","2026-11-04","2026-11-10",true,before,after,10d,-2d,java.util.List.of(),"仅保留片段");
        com.douai.module.analytics.PortraitEvidence evidence=new com.douai.module.analytics.PortraitEvidence("2026-10-28","2026-11-10",14,
            java.util.List.of(new com.douai.module.analytics.PortraitEvidence.Topic("topic","中文长标题中文长标题中文长标题中文长标题",5,3,14,.5,
                java.util.List.of(new com.douai.module.analytics.PortraitEvidence.TopicDay("2026-11-01",5,10L)))),
            java.util.List.of(new com.douai.module.analytics.PortraitEvidence.ActionRate("rate","中文话题","save","保存入口",after,before,true,java.util.List.of())),
            change,java.util.List.of(),java.util.List.of("保留记录"));
        LinearLayout root=new LinearLayout(RuntimeEnvironment.getApplication());root.setOrientation(LinearLayout.VERTICAL);
        PortraitReportViews.Host host=new PortraitReportViews.Host(){
            public LinearLayout section(String name){LinearLayout block=new LinearLayout(root.getContext());block.setOrientation(LinearLayout.VERTICAL);root.addView(block);return block;}
            public void analysis(LinearLayout section,String key){}
        };
        new PortraitStoryViews(root.getContext(),evidence,host).chapters();
        root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
        String content=content(root);
        assertTrue(content.contains("22/100"));assertTrue(content.contains("14/100"));
        assertTrue(content.contains("+10.0 个百分点"));assertTrue(content.contains("−2.0 个百分点"));
        android.widget.Button details=findButton(root);assertNotNull(details);details.performClick();
        assertTrue(content(root).contains("5 次 / 10"));
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(320,root.getMeasuredHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));bitmap.recycle();
    }
    private static String content(View view){
        StringBuilder value=new StringBuilder();
        if(view instanceof android.widget.TextView)value.append(((android.widget.TextView)view).getText());
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)value.append(content(group.getChildAt(i)));}
        return value.toString();
    }
    private static android.widget.Button findButton(View view){if(view instanceof android.widget.Button)return (android.widget.Button)view;if(view instanceof android.view.ViewGroup){var group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){var found=findButton(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static int nodes(View view){int n=1;if(view instanceof android.view.ViewGroup){var group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)n+=nodes(group.getChildAt(i));}return n;}
    private static int series(View v){
        if(v instanceof JournalSeriesChart)return 1;
        int n=0;if(v instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++)n+=series(group.getChildAt(i));return n;
    }
    @Test public void yearOfEightTopicsBuildsBoundedNodesAndCreatesOnlyOneDetailPageOnDemand(){
        java.util.List<com.douai.module.analytics.PortraitEvidence.Topic> topics=new java.util.ArrayList<>();
        for(int j=0;j<8;j++){java.util.List<com.douai.module.analytics.PortraitEvidence.TopicDay> days=new java.util.ArrayList<>();for(int i=0;i<365;i++)days.add(new com.douai.module.analytics.PortraitEvidence.TopicDay(java.time.LocalDate.of(2025,11,11).plusDays(i).toString(),1,10L));topics.add(new com.douai.module.analytics.PortraitEvidence.Topic("t"+j,"话题"+j,365,365,365,1d/365,days));}
        var evidence=new com.douai.module.analytics.PortraitEvidence("2025-11-11","2026-11-10",365,topics,java.util.List.of(),com.douai.module.analytics.PortraitEvidence.Decomposition.empty("未知"),java.util.List.of(),java.util.List.of());
        for(boolean paper:new boolean[]{false,true}){
            LinearLayout root=new LinearLayout(RuntimeEnvironment.getApplication());root.setOrientation(LinearLayout.VERTICAL);
            PortraitReportViews.Host host=new PortraitReportViews.Host(){public LinearLayout section(String name){LinearLayout block=new LinearLayout(root.getContext());block.setOrientation(LinearLayout.VERTICAL);root.addView(block);return block;}public void analysis(LinearLayout section,String key){}};
            if(paper)PortraitStoryViews.appendPaper(root.getContext(),evidence,host);else new PortraitStoryViews(root.getContext(),evidence,host).chapters();
            assertTrue("Default tree must not grow with daily rows: "+nodes(root),nodes(root)<150);
            assertEquals("One representative topic uses separate count and share plots",2,series(root));
            assertTrue(content(root).contains("其余 7 个话题"));
            if(paper)assertTrue(content(root).contains("话题7   365 次 · 365 / 365 个浏览日"));
            if(!paper){int before=nodes(root);android.widget.Button button=findButton(root);button.performClick();assertEquals(before+31,nodes(root));button.performClick();assertEquals(before+31,nodes(root));}
        }
    }
    @Test public void selectedTopicMatchesMetricAndAiSlotsAreUnique() {
        var a=new com.douai.module.analytics.PortraitEvidence.Topic("a","A排行更高",20,1,3,1,java.util.List.of());
        var b=new com.douai.module.analytics.PortraitEvidence.Topic("b","B持续出现",12,3,3,.4,java.util.List.of());
        var observation=new com.douai.module.analytics.PortraitEvidence.Observation("b","topic","B持续出现12次","本地观察","浏览日","次","2026-11-01","2026-11-03",12L,3L,null,"b","sample",java.util.List.of());
        var evidence=new com.douai.module.analytics.PortraitEvidence("2026-11-01","2026-11-03",3,java.util.List.of(a,b),java.util.List.of(),com.douai.module.analytics.PortraitEvidence.Decomposition.empty("不足"),java.util.List.of(observation),java.util.List.of());
        LinearLayout root=new LinearLayout(RuntimeEnvironment.getApplication());
        java.util.List<String> slots=new java.util.ArrayList<>();
        PortraitReportViews.Host host=new PortraitReportViews.Host(){
            public LinearLayout section(String name){LinearLayout block=new LinearLayout(root.getContext());root.addView(block);return block;}
            public void analysis(LinearLayout section,String key){slots.add(key);}
        };
        PortraitStoryViews story=new PortraitStoryViews(root.getContext(),evidence,host);story.overview();
        assertFalse(content(root).contains("A排行更高"));assertTrue(content(root).contains("B持续出现"));
        story.chapters();
        assertEquals(java.util.List.of("portrait","topics","features"),slots);
        var contrast=new com.douai.module.analytics.PortraitEvidence.Observation("contrast","topic","两个话题的反差","本地观察","浏览日","次","2026-11-01","2026-11-03",12L,3L,null,"b","sample",java.util.List.of(),"a");
        var contrasting=new com.douai.module.analytics.PortraitEvidence("2026-11-01","2026-11-03",3,java.util.List.of(a,b),java.util.List.of(),evidence.decomposition,java.util.List.of(contrast),java.util.List.of());
        root.removeAllViews();new PortraitStoryViews(root.getContext(),contrasting,host).overview();
        assertTrue(content(root).contains("●  B持续出现   12 次 / 3 天"));assertTrue(content(root).contains("○  A排行更高   20 次 / 1 天"));
        assertNull(root.findViewWithTag("analytics.story.chart")); // No daily observations to plot.
    }
    @Test public void sharedCalendarPreservesRealZerosMissingDaysAndUnpairedDates(){
        Context c=RuntimeEnvironment.getApplication();
        var a=new com.douai.module.analytics.PortraitEvidence.Topic("a","A",4,1,3,.5,java.util.List.of(
                new com.douai.module.analytics.PortraitEvidence.TopicDay("2026-11-01",0,10L),
                new com.douai.module.analytics.PortraitEvidence.TopicDay("2026-11-02",0,null),
                new com.douai.module.analytics.PortraitEvidence.TopicDay("2026-11-04",4,null)));
        var b=new com.douai.module.analytics.PortraitEvidence.Topic("b","B",2,1,3,.5,java.util.List.of(
                new com.douai.module.analytics.PortraitEvidence.TopicDay("2026-11-02",2,20L)));
        var count=new JournalSeriesChart(c,a,b,false,"2026-11-01","2026-11-04",1,2,3,4);
        var share=new JournalSeriesChart(c,a,b,true,"2026-11-01","2026-11-04",1,2,3,4);
        assertEquals(0d,count.value(0,0),0);assertEquals(0d,share.value(0,0),0);
        assertTrue(Double.isNaN(count.value(0,1)));assertTrue(Double.isNaN(count.value(0,2)));
        assertEquals(4,count.value(0,3),0);assertTrue(Double.isNaN(share.value(0,3)));
        assertTrue(Double.isNaN(count.value(1,0)));assertEquals(2,count.value(1,1),0);
        assertEquals(.1,share.value(1,1),1e-10);
    }

    @Test public void missingDenominatorAndEmptyDistributionsNeverGetZeroAxes(){
        var none=new com.douai.module.analytics.PortraitEvidence.Group(0,0,0,0);
        var dist=new com.douai.module.analytics.PortraitEvidence.Distribution(0,null,null,null);
        var data=new com.douai.module.analytics.PortraitEvidence("2026-11-01","2026-11-07",0,java.util.List.of(),
            java.util.List.of(new com.douai.module.analytics.PortraitEvidence.ActionRate("r","城市","save","保存入口",none,none,false,java.util.List.of())),
            com.douai.module.analytics.PortraitEvidence.Decomposition.empty("不足"),java.util.List.of(),java.util.List.of(),
            java.util.List.of(),java.util.List.of(),java.util.List.of(new com.douai.module.analytics.PortraitEvidence.Familiarity("short_video","unknown",dist,dist,3,false,java.util.List.of())));
        for(boolean paper:new boolean[]{false,true}){
            var root=new LinearLayout(RuntimeEnvironment.getApplication());root.setOrientation(LinearLayout.VERTICAL);
            var host=new PortraitReportViews.Host(){public LinearLayout section(String name){return root;}public void analysis(LinearLayout s,String k){}};
            new PortraitStoryViews(root.getContext(),data,host,paper).actions(root);
            String all=content(root);
            assertTrue(all.contains("比例未知"));assertTrue(all.contains("无可用样本"));assertTrue(all.contains("排除 3 个片段"));
            assertFalse(all.contains("0/0"));assertFalse(all.contains("100%"));assertFalse(all.contains("0 秒"));
        }
    }
    @Test public void actionChapterLeadsWithRecordedActionsAndKeepsZeroRowsReachable(){
        var zero=new com.douai.module.analytics.PortraitEvidence.Group(0,40,30,7);
        var yes=new com.douai.module.analytics.PortraitEvidence.Group(10,40,30,7);
        var a=new com.douai.module.analytics.PortraitEvidence.ActionRate("a","A话题","copy","复制成功",zero,zero,false,java.util.List.of());
        var b=new com.douai.module.analytics.PortraitEvidence.ActionRate("b","B话题","save","保存入口",yes,zero,true,java.util.List.of());
        var data=new com.douai.module.analytics.PortraitEvidence("2026-11-01","2026-11-07",7,java.util.List.of(),
                java.util.List.of(a,b),com.douai.module.analytics.PortraitEvidence.Decomposition.empty("不足"),java.util.List.of(),java.util.List.of());
        var root=new LinearLayout(RuntimeEnvironment.getApplication());root.setOrientation(LinearLayout.VERTICAL);
        var host=new PortraitReportViews.Host(){public LinearLayout section(String name){return root;}public void analysis(LinearLayout s,String k){}};
        new PortraitStoryViews(root.getContext(),data,host).actions(root);
        assertTrue(content(root).contains("B话题 · 保存入口"));assertFalse(content(root).contains("A话题 · 复制成功"));
        assertTrue(content(root).contains("10/40"));findButton(root).performClick();
        assertTrue(content(root).contains("A话题 · 复制成功"));assertTrue(content(root).contains("0/40"));
    }

    @Test public void sampledPreviewUsesTheQueryForBothScreenAndPaper() throws Exception {
        var evidence=sampledEvidence();
        var first=evidence.observations.get(0);
        assertEquals("topic:walk",first.parentMetricId);
        assertEquals("topic:photo",first.secondaryMetricId);
        for(int width:new int[]{320,360,412}){
            renderPreview(evidence,width,1,false,false);
            renderPreview(evidence,width,1,false,true);
        }
        renderPreview(evidence,412,1.5f,false,false);
        renderPreview(evidence,412,2,false,false);
        renderPreview(evidence,320,2,false,false);
        renderPreview(evidence,412,1,true,false);
    }

    // Insert observations into SQLite and use the production query to select the contrast.
    // The fixture is illustrative; no real person's browsing history is included.
    private static com.douai.module.analytics.PortraitEvidence sampledEvidence() throws Exception{
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null)){
            db.execSQL("CREATE TABLE daily(day TEXT,views INTEGER)");
            db.execSQL("CREATE TABLE ranks(day TEXT,kind TEXT,item_key TEXT,label TEXT,count INTEGER)");
            db.execSQL("CREATE TABLE visits(token TEXT,work_hash TEXT,type TEXT,day TEXT,started INTEGER,source TEXT,visible_ms INTEGER,ended INTEGER,end_reason TEXT)");
            db.execSQL("CREATE TABLE visit_topics(token TEXT,topic TEXT)");
            db.execSQL("CREATE TABLE actions(token TEXT,feature TEXT,source TEXT,stage TEXT,day TEXT,ts INTEGER)");
            db.execSQL("CREATE TABLE collection_boundaries(id INTEGER PRIMARY KEY,ts INTEGER,enabled INTEGER)");
            db.execSQL("CREATE TABLE work_days(day TEXT,work_hash TEXT)");
            db.execSQL("CREATE TABLE flow_events(id INTEGER PRIMARY KEY,operation_id TEXT,kind TEXT,stage TEXT,task INTEGER,day TEXT,ts INTEGER,seq INTEGER)");
            db.execSQL("CREATE TABLE source_coverage(source TEXT,status TEXT,since INTEGER,trimmed_before INTEGER)");
            db.execSQL("INSERT INTO collection_boundaries VALUES(1,0,1)");
            db.execSQL("INSERT INTO source_coverage VALUES ('visits','available',0,NULL),('events','available',0,NULL)");
            var start=java.time.LocalDate.of(2026,11,1);
            int[] counts={3,2,5,2,1,5,0};
            for(int i=0;i<7;i++){
                String day=start.plusDays(i).toString();
                db.execSQL("INSERT INTO daily VALUES (?,30)",new Object[]{day});
                if(counts[i]>0)db.execSQL("INSERT INTO ranks VALUES (?,'topic','walk','城市漫步',?)",new Object[]{day,counts[i]});
                if(i==4)db.execSQL("INSERT INTO ranks VALUES (?,'topic','photo','胶片摄影',18)",new Object[]{day});
                for(int j=0;j<30;j++){
                    String tok=i+"-"+j, work=(j<12?"repeat-"+j:"first-"+tok);
                    String topic=j<counts[i]?"城市漫步":i==4 && j<counts[i]+18?"胶片摄影":"";
                    db.execSQL("INSERT INTO visits VALUES (?,?,'short_video',?,?,'manual',?,1,'selection_changed')",
                        new Object[]{tok,work,day,j,(j<12?8500:1800)+j*230});
                    db.execSQL("INSERT INTO work_days VALUES (?,?)",new Object[]{day,work});
                    if(!topic.isEmpty())db.execSQL("INSERT INTO visit_topics VALUES (?,?)",new Object[]{tok,topic});
                    if(!topic.isEmpty() && j%3==0)db.execSQL("INSERT INTO actions VALUES (?,'save_media','manual','entry',?,?)",new Object[]{tok,day,j});
                }
            }
            for(int k=0;k<2;k++){
                db.execSQL("INSERT INTO flow_events(operation_id,kind,stage,task,day,ts,seq) VALUES (?,'ai','followup_click',0,'2026-11-04',1,1)",new Object[]{"s"+k});
                db.execSQL("INSERT INTO flow_events(operation_id,kind,stage,task,day,ts,seq) VALUES (?,'ai','video_click',0,'2026-11-04',2,2)",new Object[]{"s"+k});
            }
            Class<?> q=Class.forName("com.douai.module.analytics.PortraitEvidenceQuery");
            Method read=q.getDeclaredMethod("read",android.database.sqlite.SQLiteDatabase.class,java.time.LocalDate.class,java.time.LocalDate.class,java.time.LocalDate.class);
            read.setAccessible(true);
            return (com.douai.module.analytics.PortraitEvidence)read.invoke(null,db,start,start.plusDays(6),start.plusDays(7));
        }
    }
    private static void renderPreview(com.douai.module.analytics.PortraitEvidence data,int width,float scale,boolean night,boolean paper) throws Exception{
        RuntimeEnvironment.setQualifiers("w"+width+"dp-h800dp-"+(night?"night":"notnight")+"-mdpi");
        RuntimeEnvironment.setFontScale(scale);
        Context c=RuntimeEnvironment.getApplication();
        LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(night?0xFF101821:0xFFFFFDFA);root.setPadding(12,12,12,12);
        PortraitReportViews.Host host=new PortraitReportViews.Host(){
            public LinearLayout section(String name){
                LinearLayout block=new LinearLayout(c);block.setOrientation(LinearLayout.VERTICAL);root.addView(block);
                if(!name.equals("这段时间的发现")){
                    android.widget.TextView heading=new android.widget.TextView(c);heading.setText(name);heading.setTextSize(23);
                    heading.setPadding(0,18,0,5);heading.setTextColor(night?0xFFFFF7E8:0xFF14243B);block.addView(heading);}
                return block;
            }
            public void analysis(LinearLayout section,String key){}
        };
        var story=new PortraitStoryViews(c,data,host,paper);story.overview();story.chapters();
        String all=content(root);
        assertTrue(all.contains("城市漫步   18 次 / 6 天"));assertTrue(all.contains("胶片摄影   18 次 / 1 天"));
        assertTrue(all.contains("主动追问 → 点击相关作品   2 个会话"));assertTrue(all.contains("个作品日"));
        assertFalse(all.contains("0/0"));assertFalse(all.contains("NaN"));assertFalse(all.contains("/未知"));
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        root.layout(0,0,width,root.getMeasuredHeight());
        android.widget.TextView head=root.findViewWithTag("analytics.story.headline");
        assertTrue(head.getLineCount()>=2);
        android.widget.TextView detail=root.findViewWithTag("analytics.story.coverage");
        if(scale>=1.5f)assertTrue("13sp body must grow; Android 14+ scales large headlines nonlinearly: "+detail.getTextSize(),detail.getTextSize()>16);
        android.text.Layout layout=head.getLayout();
        assertNotNull(layout);
        for(int i=0;i<layout.getLineCount();i++){
            assertEquals(0,layout.getEllipsisCount(i));
            assertTrue(layout.getLineRight(i)<=head.getWidth()+1);
        }
        String suffix="-demo-"+width+(scale!=1?"-font"+scale:"")+(night?"-night":"");
        java.io.File dir=new java.io.File(System.getProperty("portrait.preview.dir","build/reports/portrait-preview"));assertTrue(dir.isDirectory()||dir.mkdirs());
        save(root,new java.io.File(dir,(paper?"paper":"screen")+suffix+".png"),root.getHeight());
        // Same native measurement and pixels as the long report; one viewport for visual review.
        save(root,new java.io.File(dir,(paper?"paper":"screen")+suffix+"-first.png"),Math.min(800,root.getHeight()));
    }
    private static void save(LinearLayout root,java.io.File file,int height)throws Exception{
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),height,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){
            root.draw(new android.graphics.Canvas(bitmap));assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));
        }finally{bitmap.recycle();}
    }
}
