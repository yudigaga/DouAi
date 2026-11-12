package com.douai.module.analytics;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class AnalyticsSchema3Test {
 @Rule public TemporaryFolder temporary = new TemporaryFolder();
 @Test public void migrationPreservesOldRowsWithUnknownMetadata() throws Exception {
  File dir = temporary.newFolder();
  SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(dir,"usage.db"), null);
  db.execSQL("CREATE TABLE visits(token TEXT PRIMARY KEY,work_hash TEXT NOT NULL,type TEXT NOT NULL,day TEXT NOT NULL,hour INTEGER NOT NULL,source TEXT NOT NULL,started INTEGER NOT NULL,visible_ms INTEGER NOT NULL,ended INTEGER NOT NULL,end_reason TEXT NOT NULL)");
  db.execSQL("INSERT INTO visits VALUES ('old','hash','image','2026-11-11',1,'unknown',0,123,0,'')");
  db.setVersion(2); db.close();
  try(LocalAnalytics store = new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.setEnabled(true); store.snapshot(1);
   db = SQLiteDatabase.openDatabase(new File(dir,"usage.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
   assertEquals(4, db.getVersion());
   try(Cursor c=db.rawQuery("SELECT author_hash,duration_band,image_band,is_ad,visible_ms,ended FROM visits",null)) {
    assertTrue(c.moveToFirst()); for(int i=0;i<4;i++) assertTrue(c.isNull(i));
    assertEquals(123,c.getLong(4)); assertEquals(0,c.getInt(5));
   } finally {db.close();}
  }
 }
 @Test public void newBackupUsesIndependentVersionThreeAndPreservesLegacyReader() throws Exception {
  JSONObject value=AnalyticsBackup.capture(null,System.currentTimeMillis(),0).put("context",new JSONObject().put("purpose","").put("feedback",""));
  assertEquals(3,value.getInt("version"));
  assertTrue(value.getJSONObject("tables").has("flow_events"));
  assertTrue(value.getJSONObject("tables").has("source_coverage"));
  AnalyticsBackup.inspect(value);
  value.put("version",1); value.getJSONObject("tables").remove("queue_gaps"); value.getJSONObject("tables").remove("flow_events");value.getJSONObject("tables").remove("source_coverage");
  assertEquals(0,AnalyticsBackup.inspect(value).rowCount);
 }
 @Test public void operationsFreezeOriginalVisitDeduplicateAndRejectClearedGeneration() throws Exception {
  File dir=temporary.newFolder();
  try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.setEnabled(true);
   String original=store.beginVisit(store.generation(),"a","image","author","",List.of(),false,0,"unknown");
   LocalAnalytics.FlowOperation op=store.beginOperation("a","download");
   store.beginVisit(store.generation(),"b","image",null,"",List.of(),false,0,"unknown");
   op.stage("accepted"); op.stage("accepted"); op.stage("output_closed",1);op.stage("output_closed",2);
   JSONObject data=store.exportBackup();
   org.json.JSONArray rows=data.getJSONObject("tables").getJSONArray("flow_events");
   assertEquals(3,rows.length());for(int i=0;i<rows.length();i++) assertEquals(original,rows.getJSONArray(i).getString(2));
   store.clear();op.stage("task_end");assertEquals(0,store.exportBackup().getJSONObject("tables").getJSONArray("flow_events").length());
  }
 }
 @Test public void newAiClicksSurviveSameTimestampAndCallbackRetriesAcrossBackup() throws Exception {
  File dir=temporary.newFolder();Clock clock=Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC);
  try(LocalAnalytics store=new LocalAnalytics(dir,clock)){
   store.setEnabled(true);
   LocalAnalytics.FlowOperation op=store.beginOperation(null,"ai");
   op.stage("generated");op.stage("generated");
   op.click("more_click");op.click("more_click");
   var first=op.click("video_click");first.dispatched();first.dispatched();
   var second=op.click("video_click");second.dispatched();
   JSONObject backup=store.exportBackup().put("context",new JSONObject().put("purpose","").put("feedback",""));
   assertEquals(7,backup.getJSONObject("tables").getJSONArray("flow_events").length());
   store.replaceBackup(AnalyticsBackup.inspect(backup),()->{});
   PortraitEvidence e=store.snapshot(7).portrait.evidence;
   assertFalse(e.hasLegacyAiStages);
   assertTrue(e.flowPaths.stream().anyMatch(x->x.from.equals("more_click")&&x.to.equals("more_click")&&x.sessions==1));
   assertTrue(e.flowPaths.stream().anyMatch(x->x.from.equals("route_dispatched")&&x.to.equals("video_click")&&x.sessions==1));
   assertTrue(e.flowStages.stream().allMatch(x->x.tasks==0&&x.sessions==1));
  }
 }
 @Test public void v2BackupHasFirstSeenStagesButNoOrderAndNewSessionStillFormsPaths() throws Exception {
  Clock clock=Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC);
  JSONObject data=AnalyticsBackup.capture(null,clock.millis(),0).put("version",2).put("context",new JSONObject().put("purpose","").put("feedback",""));
  JSONObject tables=data.getJSONObject("tables");tables.remove("queue_gaps");
  for(int i=0;i<2;i++)tables.getJSONArray("flow_events").put(new org.json.JSONArray().put(i+1).put("00000000-0000-0000-0000-000000000001").put(JSONObject.NULL).put("ai").put(i==0?"video_click":"route_dispatched").put(0).put("2026-11-11").put(clock.millis()));
  try(LocalAnalytics store=new LocalAnalytics(temporary.newFolder(),clock)){
   store.setEnabled(true);store.replaceBackup(AnalyticsBackup.inspect(data),()->{});
   PortraitEvidence old=store.snapshot(1).portrait.evidence;assertTrue(old.hasLegacyAiStages);assertTrue(old.flowPaths.isEmpty());assertEquals(2,old.flowStages.size());
   store.beginOperation(null,"ai").click("video_click").dispatched();
   PortraitEvidence mixed=store.snapshot(1).portrait.evidence;assertTrue(mixed.hasLegacyAiStages);assertEquals(1,mixed.flowPaths.size());assertEquals(1,mixed.flowPaths.get(0).sessions);
  }
 }
 @Test public void missingSequenceNeverBridgesClippedOrLostClicks() throws Exception {
  Clock clock=Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC);
  try(LocalAnalytics store=new LocalAnalytics(temporary.newFolder(),clock)){
   store.setEnabled(true);LocalAnalytics.FlowOperation op=store.beginOperation(null,"ai");
   op.stage("generated");op.click("more_click");op.click("video_click").dispatched();
   JSONObject backup=store.exportBackup().put("context",new JSONObject().put("purpose","").put("feedback",""));
   org.json.JSONArray rows=backup.getJSONObject("tables").getJSONArray("flow_events");
   rows.remove(1); // dropped event between generated and the video click
   store.replaceBackup(AnalyticsBackup.inspect(backup),()->{});
   PortraitEvidence e=store.snapshot(1).portrait.evidence;
   assertEquals(1,e.flowPaths.size());assertEquals("video_click",e.flowPaths.get(0).from);assertEquals("route_dispatched",e.flowPaths.get(0).to);
   rows.getJSONArray(1).put(8,1);assertThrows(IllegalArgumentException.class,()->AnalyticsBackup.inspect(backup));
  }
 }
 @Test public void legacyDroppedCounterHasNoInventedDayAndUnknownCoverageRestartsOnNewEvidence() throws Exception {
  Clock clock=Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"),ZoneOffset.UTC);
  long captured=clock.millis()-86400000;
  JSONObject backup=AnalyticsBackup.capture(null,captured,1).put("version",2).put("context",new JSONObject().put("purpose","").put("feedback",""));
  JSONObject t=backup.getJSONObject("tables");t.remove("queue_gaps");
  t.getJSONArray("source_coverage").put(new org.json.JSONArray().put("visits").put("unknown").put(0).put(1));
  try(LocalAnalytics store=new LocalAnalytics(temporary.newFolder(),clock)){
   store.setEnabled(true);store.replaceBackup(AnalyticsBackup.inspect(backup),()->{});
   store.beginVisit(store.generation(),"fresh","image",null,"",List.of(),false,1,"manual");
   t=store.exportBackup().getJSONObject("tables");assertEquals(0,t.getJSONArray("queue_gaps").length());
   boolean bounded=false,recovered=false;
   for(int i=0;i<t.getJSONArray("meta").length();i++){var r=t.getJSONArray("meta").getJSONArray(i);if(r.getString(0).equals("legacy_drop_through"))bounded=r.getLong(1)==captured;}
   for(int i=0;i<t.getJSONArray("source_coverage").length();i++){var r=t.getJSONArray("source_coverage").getJSONArray(i);if(r.getString(0).equals("visits"))recovered=r.getString(1).equals("available")&&r.getLong(2)==clock.millis()&&r.getLong(3)==1;}
   assertTrue(bounded);assertTrue(recovered);
  }
 }
 @Test public void nullableMetadataAndBoundariesArePersistedWithoutRawAuthor() throws Exception {
  File dir=temporary.newFolder();
  try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.setEnabled(true);
   store.beginVisit(store.generation(),"a","image","private-author","",List.of(),(Boolean)null,15000,"unknown",6);
   org.json.JSONArray row=store.exportBackup().getJSONObject("tables").getJSONArray("visits").getJSONArray(0);
   assertEquals(64,row.getString(10).length());assertFalse(row.toString().contains("private-author"));
   assertEquals("15_59s",row.getString(11));assertEquals("6_10",row.getString(12));assertTrue(row.isNull(13));
   assertNull(LocalAnalytics.durationBand(0));assertEquals("under_15s",LocalAnalytics.durationBand(14999));
   assertEquals("1_5m",LocalAnalytics.durationBand(60000));assertEquals("5m_plus",LocalAnalytics.durationBand(300000));
  }
 }
 @Test public void legacyLogicalVisitStaysUnknownAndRestoreDoesNotInventNaturalEnding() throws Exception {
  JSONObject value=AnalyticsBackup.capture(null,System.currentTimeMillis(),0).put("version",1).put("context",new JSONObject().put("purpose","").put("feedback",""));
  JSONObject tables=value.getJSONObject("tables");tables.remove("queue_gaps");tables.remove("flow_events");tables.remove("source_coverage");
  String token="00000000-0000-0000-0000-000000000001";
  tables.getJSONArray("visits").put(new org.json.JSONArray().put(token).put("a".repeat(64)).put("image").put("2026-11-11").put(0).put("unknown").put(0).put(123).put(0).put(""));
  AnalyticsBackup.Snapshot snapshot=AnalyticsBackup.inspect(value);
  File dir=temporary.newFolder();try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.replaceBackup(snapshot,()->{});
   org.json.JSONArray row=store.exportBackup().getJSONObject("tables").getJSONArray("visits").getJSONArray(0);
   assertEquals("backup_restore",row.getString(9));assertTrue(row.isNull(10));assertTrue(row.isNull(11));assertTrue(row.isNull(12));assertTrue(row.isNull(13));assertFalse(store.isEnabled());
  }
 }
 @Test public void deferredOperationUsesCapturedAttributionAndRejectsOldGeneration() throws Exception {
  File dir=temporary.newFolder();try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.setEnabled(true);long generation=store.generation();
   String token=store.beginVisit(generation,"a","image",null,"",List.of(),false,0,"unknown");
   store.beginVisit(generation,"b","image",null,"",List.of(),false,0,"unknown");
   LocalAnalytics.FlowOperation flow=store.beginOperation(generation,token,"download");assertNotNull(flow);flow.stage("request");
   assertEquals(token,store.exportBackup().getJSONObject("tables").getJSONArray("flow_events").getJSONArray(0).getString(2));
   store.clear();assertNull(store.beginOperation(generation,token,"download"));
  }
 }
 @Test public void duplicateFlowStageOrChangedOriginIsRejectedBeforeRestore() throws Exception {
  JSONObject value=AnalyticsBackup.capture(null,System.currentTimeMillis(),0).put("context",new JSONObject().put("purpose","").put("feedback",""));
  org.json.JSONArray rows=value.getJSONObject("tables").getJSONArray("flow_events");
  String op="00000000-0000-0000-0000-000000000001";
  rows.put(new org.json.JSONArray().put(1).put(op).put(JSONObject.NULL).put("download").put("request").put(0).put("2026-11-11").put(0).put(JSONObject.NULL));
  rows.put(new org.json.JSONArray().put(2).put(op).put(JSONObject.NULL).put("download").put("request").put(0).put("2026-11-11").put(0).put(JSONObject.NULL));
  assertThrows(IllegalArgumentException.class,()->AnalyticsBackup.inspect(value));
  rows.getJSONArray(1).put(4,"task_end").put(3,"ai");
  assertThrows(IllegalArgumentException.class,()->AnalyticsBackup.inspect(value));
 }
 @Test public void restoreMarksMissingIntervalAndClearLazilyCreatesNewBoundary() throws Exception {
  long now=Instant.parse("2026-11-11T00:00:00Z").toEpochMilli(),captured=now-3600000;
  JSONObject payload=AnalyticsBackup.capture(null,captured,0).put("context",new JSONObject().put("purpose","").put("feedback",""));
  payload.getJSONObject("tables").getJSONArray("collection_boundaries").put(new org.json.JSONArray().put(1).put(captured-3600000).put(1));
  File dir=temporary.newFolder();try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))) {
   store.setEnabled(true);store.replaceBackup(AnalyticsBackup.inspect(payload),()->{});
   org.json.JSONArray boundaries=store.exportBackup().getJSONObject("tables").getJSONArray("collection_boundaries");
   assertEquals(3,boundaries.length());assertEquals(captured,boundaries.getJSONArray(1).getLong(1));assertEquals(0,boundaries.getJSONArray(1).getInt(2));assertEquals(now,boundaries.getJSONArray(2).getLong(1));assertEquals(1,boundaries.getJSONArray(2).getInt(2));
   store.clear();assertFalse(new File(dir,"usage.db").exists());
   store.recordFeature("copy_text","复制");
   boundaries=store.exportBackup().getJSONObject("tables").getJSONArray("collection_boundaries");
   assertEquals(1,boundaries.length());assertEquals(now,boundaries.getJSONArray(0).getLong(1));assertEquals(1,boundaries.getJSONArray(0).getInt(2));
  }
 }
 @Test public void sharedEventCapacityDeletesOnlyOldestExcessWithWatermark() throws Exception {
  File dir=temporary.newFolder();try(LocalAnalytics store=new LocalAnalytics(dir,Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"),ZoneOffset.UTC))) {
   store.setEnabled(true);store.snapshot(1);
   java.lang.reflect.Field field=LocalAnalytics.class.getDeclaredField("database");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(store);
   db.beginTransaction();try {for(int i=1;i<=30001;i++) db.execSQL("INSERT INTO actions(day,feature,source,stage,ts) VALUES ('2026-11-11','save_media','manual','entry',?)",new Object[]{i});db.setTransactionSuccessful();}finally{db.endTransaction();}
   java.lang.reflect.Method trim=LocalAnalytics.class.getDeclaredMethod("trimEvents",SQLiteDatabase.class);trim.setAccessible(true);trim.invoke(null,db);
   try(Cursor c=db.rawQuery("SELECT COUNT(*),MIN(ts),MAX(ts) FROM actions",null)) {assertTrue(c.moveToFirst());assertEquals(30000,c.getInt(0));assertEquals(2,c.getLong(1));assertEquals(30001,c.getLong(2));}
   try(Cursor c=db.rawQuery("SELECT trimmed_before FROM source_coverage WHERE source='events'",null)) {assertTrue(c.moveToFirst());assertEquals(1,c.getLong(0));}
   trim.invoke(null,db);
   try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM discarded_events",null)) {assertTrue(c.moveToFirst());assertEquals(0,c.getInt(0));}
  }
 }
}
