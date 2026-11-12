package com.douai.module.analytics;
import android.database.sqlite.SQLiteDatabase;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class PortraitEvidenceTest {
 SQLiteDatabase db;
 @Before public void setup(){ db=SQLiteDatabase.create(null);
 db.execSQL("CREATE TABLE daily(day TEXT,views INTEGER)");
 db.execSQL("CREATE TABLE ranks(day TEXT,kind TEXT,item_key TEXT,label TEXT,count INTEGER)");
 db.execSQL("CREATE TABLE visits(token TEXT,work_hash TEXT,type TEXT,day TEXT,started INTEGER)");
 db.execSQL("CREATE TABLE visit_topics(token TEXT,topic TEXT)");
 db.execSQL("CREATE TABLE actions(token TEXT,feature TEXT,source TEXT,stage TEXT,day TEXT,ts INTEGER)");
 db.execSQL("CREATE TABLE collection_boundaries(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,enabled INTEGER)");
 }
 @After public void close(){db.close();}
 PortraitEvidence read(String start,String end){return PortraitEvidenceQuery.read(db,LocalDate.parse(start),LocalDate.parse(end));}
 @Test public void topicPersistenceUsesBrowseDaysAndMissingDaysAreUnknown(){
 for(int i=1;i<=7;i++){String d="2026-11-0"+i;db.execSQL("INSERT INTO daily VALUES (?,?)",new Object[]{d,i==7?120:2}); if(i<=6)db.execSQL("INSERT INTO ranks VALUES (?,'topic','A','A',2)",new Object[]{d});}
 db.execSQL("INSERT INTO ranks VALUES ('2026-11-07','topic','B','B',12)");
 db.execSQL("INSERT INTO daily VALUES ('2026-11-08',0)");
 PortraitEvidence p=read("2026-11-01","2026-11-09");assertEquals(7,p.browseDays);
 PortraitEvidence.Topic a=p.topics.stream().filter(t->t.label.equals("A")).findFirst().get();
 PortraitEvidence.Topic b=p.topics.stream().filter(t->t.label.equals("B")).findFirst().get();
 assertEquals(12,a.total);assertEquals(12,b.total);assertEquals(6,a.appearanceDays);assertEquals(1,b.appearanceDays);
 assertEquals(a.id,p.observations.get(0).parentMetricId);assertEquals(b.id,p.observations.get(0).secondaryMetricId);assertTrue(p.observations.get(0).title.contains("同样 12 次"));assertTrue(p.observations.get(0).body.contains("A"));assertTrue(p.observations.get(0).body.contains("B"));assertTrue(p.observations.get(0).body.contains("7 个浏览日"));
 assertNull(a.daily.get(8).share);assertNull(a.daily.get(7).share);assertEquals(.1,b.daily.get(6).share,1e-10);
 assertEquals(Long.valueOf(6),p.observations.get(0).numerator);assertEquals(Long.valueOf(7),p.observations.get(0).denominator);assertEquals("浏览日",p.observations.get(0).unit);
 }
 @Test public void persistentContrastIsSelectedBeforeTheEightTopicDisplayLimit(){
 for(int day=1;day<=6;day++){
     String date="2026-11-0"+day;
     db.execSQL("INSERT INTO daily VALUES (?,100)",new Object[]{date});
     db.execSQL("INSERT INTO ranks VALUES (?,'topic','常见','常见',2)",new Object[]{date});
 }
 for(int i=0;i<8;i++)db.execSQL("INSERT INTO ranks VALUES ('2026-11-06','topic',?,?,?)",new Object[]{"尖峰"+i,"尖峰"+i,20+i});
 db.execSQL("INSERT INTO ranks VALUES ('2026-11-06','topic','短暂','短暂',12)");
 PortraitEvidence p=read("2026-11-01","2026-11-06");
 PortraitEvidence.Observation o=p.observations.get(0);
 assertEquals("topic:常见",o.parentMetricId);assertEquals("topic:短暂",o.secondaryMetricId);
 assertEquals(8,p.topics.size());
 assertTrue(p.topics.stream().anyMatch(t->t.id.equals(o.parentMetricId)&&t.total==12&&t.appearanceDays==6));
 assertTrue(p.topics.stream().anyMatch(t->t.id.equals(o.secondaryMetricId)&&t.total==12&&t.appearanceDays==1));
 }
 @Test public void onlyRoutesFromClickSessionsAreAttributedToExploration(){
 db.execSQL("CREATE TABLE flow_events(id INTEGER PRIMARY KEY,day TEXT,ts INTEGER,operation_id TEXT,kind TEXT,stage TEXT,task INTEGER)");
 String[] sessions={"click","click","only-route","another-route"}, stages={"video_click","route_dispatched","route_dispatched","route_dispatched"};
 for(int i=0;i<sessions.length;i++)db.execSQL("INSERT INTO flow_events VALUES (?,'2026-11-01',?,?,'ai',?,0)",new Object[]{i,i,sessions[i],stages[i]});
 PortraitEvidence p=read("2026-11-01","2026-11-01");
 assertTrue(p.observations.get(0).body.contains("路由派发 1 个会话"));
 assertEquals(3,p.flowStages.stream().filter(s->s.stage.equals("route_dispatched")).findFirst().get().sessions);
 }
 @Test public void resumeAtExactWindowStartDoesNotCountEarlierPauseAsAGap(){
 db.execSQL("CREATE TABLE source_coverage(source TEXT,status TEXT,since INTEGER,trimmed_before INTEGER)");
 db.execSQL("INSERT INTO source_coverage VALUES ('visits','available',0,NULL),('events','available',0,NULL)");
 LocalDate first=LocalDate.parse("2026-11-01");
 long from=first.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
 db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,0),(?,1)",new Object[]{from-1000,from});
 for(int i=0;i<20;i++){String day=first.plusDays(i%3).toString();visit("yes"+i,"image",day,i<5,"A");visit("no"+i,"image",day,false,"B");}
 PortraitEvidence.ActionRate rate=read("2026-11-01","2026-11-03").actionRates.get(0);
 assertTrue(rate.eligible);assertEquals(25,rate.differencePoints,0);
 }
 @Test public void knownTrimBeforeWindowAllowsFullTableButUnknownOriginDoesNot(){
 db.execSQL("CREATE TABLE source_coverage(source TEXT,status TEXT,since INTEGER,trimmed_before INTEGER)");
 db.execSQL("INSERT INTO source_coverage VALUES ('visits','available',0,NULL),('events','available',0,1)");
 db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (0,1)");
 LocalDate first=LocalDate.parse("2026-11-01");
 for(int i=0;i<20;i++){String day=first.plusDays(i%3).toString();visit("yes"+i,"image",day,i<5,"A");visit("no"+i,"image",day,false,"B");}
 db.execSQL("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<29990) INSERT INTO actions SELECT NULL,'other','unknown','entry','2026-10-31',2 FROM n");
 assertTrue(read("2026-11-01","2026-11-03").actionRates.get(0).eligible);
 db.execSQL("UPDATE source_coverage SET status='unknown' WHERE source='events'");
 assertFalse(read("2026-11-01","2026-11-03").actionRates.get(0).eligible);
 }
 @Test public void droppedLinkedActionDisablesOnlyItsVisitDayWindow(){
 db.execSQL("CREATE TABLE source_coverage(source TEXT,status TEXT,since INTEGER,trimmed_before INTEGER)");
 db.execSQL("INSERT INTO source_coverage VALUES ('visits','available',0,NULL),('events','available',0,NULL)");
 db.execSQL("CREATE TABLE queue_gaps(day TEXT,source TEXT)");
 db.execSQL("INSERT INTO queue_gaps VALUES ('2026-11-05','events')");
 db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (0,1)");
 for(int d=1;d<=9;d++) for(int i=0;i<8;i++){String day="2026-11-0"+d;visit("a"+d+"-"+i,"image",day,true,"A");visit("b"+d+"-"+i,"image",day,false,"B");}
 assertTrue(read("2026-11-01","2026-11-03").actionRates.get(0).eligible);
 assertFalse(read("2026-11-04","2026-11-06").actionRates.get(0).eligible);
 assertTrue(read("2026-11-07","2026-11-09").actionRates.get(0).eligible);
 }
 void visit(String token,String type,String day,boolean positive,String topic){db.execSQL("INSERT INTO visits(token,work_hash,type,day,started) VALUES (?,?,?,?,0)",new Object[]{token,token,type,day});db.execSQL("INSERT INTO visit_topics VALUES (?,?)",new Object[]{token,topic});if(positive){db.execSQL("INSERT INTO actions VALUES (?,'save_media','manual','entry',?,0)",new Object[]{token,day});db.execSQL("INSERT INTO actions VALUES (?,'save_media','manual','entry',?,0)",new Object[]{token,day});}}
 @Test public void actionRatesDeduplicateAndExcludeUnknownTopics(){visit("a","short_video","2026-11-01",true,"A");visit("b","image","2026-11-01",false,"B");visit("c","image","2026-11-01",true,"");
 db.execSQL("INSERT INTO actions VALUES ('orphan','save_media','manual','entry','2026-11-01',0)");
 PortraitEvidence.ActionRate r=read("2026-11-01","2026-11-01").actionRates.stream().filter(x->x.topic.equals("A")&&x.action.equals("save_media:entry")).findFirst().get();assertEquals(1,r.withTopic.numerator);assertEquals(1,r.withTopic.denominator);assertEquals(0,r.withoutTopic.numerator);assertEquals(1,r.withoutTopic.denominator);assertFalse(r.eligible);
 }
 @Test public void overlappingTagsAndWindowUseDistinctSegmentsInBothCohorts(){
 visit("a","image","2026-10-14",true,"A");visit("ab","image","2026-10-15",true,"A");
 db.execSQL("INSERT INTO visit_topics VALUES ('ab','B'),('ab','B')");
 visit("b","image","2026-11-11",false,"B");visit("c","image","2026-11-12",false,"C");visit("untagged","image","2026-11-12",true,"");
 visit("expired","image","2026-10-13",true,"B");
 db.execSQL("INSERT INTO daily VALUES ('2026-10-13',1)");
 db.execSQL("INSERT INTO ranks VALUES ('2026-10-13','topic','B','B',1)");
 LocalDate today=LocalDate.parse("2026-11-12");
 PortraitEvidence p=PortraitEvidenceQuery.read(db,today.minusDays(89),today,today);
 assertEquals("2026-10-14",p.detailStartDate);assertEquals("2026-11-12",p.detailEndDate);
 assertEquals(1,p.topics.get(0).total);
 PortraitEvidence.ActionRate a=p.actionRates.stream().filter(r->r.topic.equals("A")&&r.action.equals("save_media:entry")).findFirst().get();
 PortraitEvidence.ActionRate b=p.actionRates.stream().filter(r->r.topic.equals("B")&&r.action.equals("save_media:entry")).findFirst().get();
 assertEquals(2,a.withTopic.numerator);assertEquals(2,a.withTopic.denominator);assertEquals(0,a.withoutTopic.numerator);assertEquals(2,a.withoutTopic.denominator);
 assertEquals(1,b.withTopic.numerator);assertEquals(2,b.withTopic.denominator);assertEquals(1,b.withoutTopic.numerator);assertEquals(2,b.withoutTopic.denominator);
 PortraitEvidence expired=PortraitEvidenceQuery.read(db,today.minusDays(90),today.minusDays(30),today);
 assertTrue(expired.detailStartDate.isEmpty()&&expired.detailEndDate.isEmpty());assertTrue(expired.actionRates.isEmpty());
 }
 @Test public void symmetricDecompositionGoldenCase(){
 db.execSQL("CREATE TABLE source_coverage(source TEXT,status TEXT,since INTEGER,trimmed_before INTEGER)");db.execSQL("INSERT INTO source_coverage VALUES (\"visits\",\"available\",0,NULL),(\"events\",\"available\",0,NULL)");
 long begin=LocalDate.parse("2026-10-28").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,1)",new Object[]{begin});
 for(int period=0;period<2;period++)for(int i=0;i<100;i++){boolean shortType=i<(period==0?80:40);int index=shortType?i:i-(period==0?80:40);visit(period+"-"+i,shortType?"short_video":"long_video",LocalDate.parse(period==0?"2026-10-28":"2026-11-04").plusDays(i%7).toString(),index%(shortType?10:10)<(shortType?1:3),"A");}
 PortraitEvidence.Decomposition d=read("2026-10-28","2026-11-11").decomposition;
 assertEquals(8,d.compositionPoints,1e-9);assertEquals(0,d.withinPoints,1e-9);assertEquals(d.differencePoints,d.compositionPoints+d.withinPoints,1e-9);
 }
 @Test public void noCommonSupportAndCollectionGapSuppressAttribution(){
 visit("old","short_video","2026-10-29",true,"A");visit("now","image","2026-11-05",true,"A");
 assertFalse(read("2026-10-28","2026-11-11").decomposition.available);
 db.execSQL("UPDATE visits SET type='short_video'");
 long ts=LocalDate.parse("2026-11-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
 db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,0)",new Object[]{ts});
 assertFalse(read("2026-10-28","2026-11-11").decomposition.available);
 assertNull(read("2026-10-28","2026-11-11").decomposition.compositionPoints);
 }
 @Test public void crossMidnightActionsBelongToVisitStartDayAndAutomaticDoesNotCount(){
 visit("a","image","2026-11-01",false,"A");visit("b","image","2026-11-01",false,"B");
 db.execSQL("INSERT INTO actions VALUES ('a','copy_text','manual','success','2026-11-02',0)");
 db.execSQL("INSERT INTO actions VALUES ('b','copy_text','automatic','success','2026-11-01',0)");
 PortraitEvidence.ActionRate rate=read("2026-11-01","2026-11-01").actionRates.stream().filter(r->r.topic.equals("A")&&r.action.equals("copy_text:success")).findFirst().get();
 assertEquals(1,rate.withTopic.numerator);assertEquals(0,rate.withoutTopic.numerator);
 }
 @Test public void frozenCollectionsCannotBeChanged(){
 visit("a","image","2026-11-01",false,"A");
 PortraitEvidence p=read("2026-11-01","2026-11-01");
 assertThrows(UnsupportedOperationException.class,()->p.actionRates.clear());
 assertFalse(p.decomposition.available);
 }
 void enableFamiliarityTables(){
 db.execSQL("ALTER TABLE visits ADD COLUMN source TEXT DEFAULT 'manual'");db.execSQL("ALTER TABLE visits ADD COLUMN visible_ms INTEGER DEFAULT 0");db.execSQL("ALTER TABLE visits ADD COLUMN ended INTEGER DEFAULT 1");db.execSQL("ALTER TABLE visits ADD COLUMN end_reason TEXT DEFAULT 'selection_changed'");
 db.execSQL("CREATE TABLE work_days(day TEXT,work_hash TEXT)");
 }
 @Test public void familiarityObservationReferencesExactGroupAndCountsWorkDays(){
 enableFamiliarityTables();
 LocalDate first=LocalDate.parse("2026-11-01");
 long since=first.minusDays(28).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
 db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,1)",new Object[]{since});
 visit("other","image",first.toString(),false,"");
 for(int i=0;i<20;i++){
     String day=first.plusDays(i%3).toString();
     visit("f"+i,"short_video",day,false,"");visit("u"+i,"short_video",day,false,"");
     db.execSQL("INSERT INTO work_days VALUES ('2026-10-21',?)",new Object[]{"f"+i});
 }
 db.execSQL("UPDATE visits SET visible_ms=1500 WHERE token LIKE 'f%'");
 db.execSQL("UPDATE visits SET visible_ms=9000 WHERE token LIKE 'u%'");
 PortraitEvidence p=read("2026-11-01","2026-11-03");
 PortraitEvidence.Observation o=p.observations.get(0);
 assertEquals("familiarity:short_video:manual",o.parentMetricId);
 assertEquals(Long.valueOf(20),o.numerator);assertEquals(Long.valueOf(40),o.denominator);assertEquals("作品日",o.unit);
 PortraitEvidence.Familiarity f=p.familiarity.stream().filter(g->g.id.equals(o.parentMetricId)).findFirst().get();
 assertEquals(1500,f.familiar.median,0);assertEquals(9000,f.unseen.median,0);
 }
 @Test public void familiarityUsesPrior28DaysAndFirstNaturalSegmentPerWorkDay(){
 enableFamiliarityTables();
 visit("a","image","2026-11-01",false,"A");visit("b","image","2026-11-01",false,"B");visit("c","image","2026-11-01",false,"C");visit("d","image","2026-11-01",false,"D");
 db.execSQL("UPDATE visits SET visible_ms=1000 WHERE token='a'");db.execSQL("UPDATE visits SET visible_ms=5000 WHERE token='b'");db.execSQL("UPDATE visits SET work_hash='a',visible_ms=9000,started=1 WHERE token='c'");db.execSQL("UPDATE visits SET end_reason='backup_restore' WHERE token='d'");
 db.execSQL("INSERT INTO work_days VALUES ('2026-10-25','a')");
 long begin=LocalDate.parse("2026-10-01").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,1)",new Object[]{begin});
 PortraitEvidence.Familiarity f=read("2026-11-01","2026-11-01").familiarity.get(0);
 assertEquals(1,f.familiar.n);assertEquals(1,f.unseen.n);assertEquals(1000,f.familiar.median,0);assertEquals(5000,f.unseen.median,0);assertEquals(2,f.excluded);assertFalse(f.eligible);assertFalse(read("2026-11-01","2026-11-01").observations.stream().anyMatch(o->o.type.equals("familiarity")));
 db.execSQL("DELETE FROM collection_boundaries");PortraitEvidence.Familiarity unknown=read("2026-11-01","2026-11-01").familiarity.get(0);assertEquals(0,unknown.familiar.n);assertEquals(0,unknown.unseen.n);assertFalse(unknown.eligible);
 }}
