package com.douai.module.analytics;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.LocalDate;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class AIFlowAnalyticsTest {
 @Test public void generatedOnlyIsNotExplorationAndClicksCountSessionsNotEvents(){
  try(SQLiteDatabase db=SQLiteDatabase.create(null)){
   db.execSQL("CREATE TABLE daily(day TEXT,views INTEGER)");db.execSQL("CREATE TABLE ranks(day TEXT,kind TEXT,item_key TEXT,label TEXT,count INTEGER)");db.execSQL("CREATE TABLE visits(token TEXT,work_hash TEXT,type TEXT,day TEXT)");db.execSQL("CREATE TABLE visit_topics(token TEXT,topic TEXT)");db.execSQL("CREATE TABLE actions(token TEXT,feature TEXT,source TEXT,stage TEXT)");db.execSQL("CREATE TABLE collection_boundaries(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,enabled INTEGER)");
   db.execSQL("CREATE TABLE flow_events(id INTEGER PRIMARY KEY,operation_id TEXT,kind TEXT,stage TEXT,day TEXT,ts INTEGER,task INTEGER DEFAULT 0)");
   db.execSQL("INSERT INTO flow_events(id,operation_id,kind,stage,day,ts) VALUES (1,'prefetch','ai','generated','2026-11-11',1)");
   LocalDate date=LocalDate.parse("2026-11-11");
   assertTrue(PortraitEvidenceQuery.read(db,date,date).hasStories());
   assertFalse(PortraitEvidence.empty(date.toString(),date.toString()).hasStories());
   assertFalse(PortraitEvidenceQuery.read(db,date,date).observations.stream().anyMatch(o->o.type.equals("exploration")));
   db.execSQL("INSERT INTO flow_events(id,operation_id,kind,stage,day,ts) VALUES (2,'clicked','ai','followup_click','2026-11-11',2)");db.execSQL("INSERT INTO flow_events(id,operation_id,kind,stage,day,ts) VALUES (3,'clicked','ai','video_click','2026-11-11',3)");db.execSQL("INSERT INTO flow_events(id,operation_id,kind,stage,day,ts) VALUES (4,'clicked','ai','route_dispatched','2026-11-11',4)");
   PortraitEvidence.Observation o=PortraitEvidenceQuery.read(db,date,date).observations.stream().filter(x->x.type.equals("exploration")).findFirst().get();
   assertEquals(1L,o.numerator.longValue());assertNull(o.denominator);assertTrue(o.body.contains("不代表播放"));
   PortraitEvidence e=PortraitEvidenceQuery.read(db,date,date);assertEquals(4,e.flowStages.size());assertTrue("Old stages are first occurrences, not adjacent events",e.flowPaths.isEmpty());
   db.execSQL("INSERT INTO flow_events VALUES (5,\"download-op\",\"download\",\"task_end\",\"2026-11-11\",5,0),(6,\"download-op\",\"download\",\"task_end\",\"2026-11-11\",6,1),(7,\"download-op\",\"download\",\"task_end\",\"2026-11-11\",7,2)");
   db.execSQL("INSERT INTO flow_events VALUES (8,'cancelled-before-task','download','entry','2026-11-11',8,0)");
   PortraitEvidence.StageCount entry=PortraitEvidenceQuery.read(db,date,date).flowStages.stream().filter(x->x.kind.equals("download")&&x.stage.equals("entry")).findFirst().get();assertEquals(1,entry.sessions);assertEquals(0,entry.tasks);
   PortraitEvidence.StageCount download=PortraitEvidenceQuery.read(db,date,date).flowStages.stream().filter(x->x.kind.equals("download")&&x.stage.equals("task_end")).findFirst().get();assertEquals(1,download.sessions);assertEquals(2,download.tasks);
  }
 }
}
