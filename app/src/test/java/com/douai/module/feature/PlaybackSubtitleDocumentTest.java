package com.douai.module.feature;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PlaybackSubtitleDocumentTest {
 private PlaybackSubtitleDocument doc(int n) {
  List<PlaybackReadingText.Line> lines=new ArrayList<>();
  for(int i=0;i<n;i++)lines.add(new PlaybackReadingText.Line(1250,"中文 "+i+"\n第二行"));
  return new PlaybackSubtitleDocument("account","aid","track",7,lines);
 }
 @Test public void fullExportKeeps300CuesAndRepeatedTimes() {
  PlaybackSubtitleDocument d=doc(300); String md=PlaybackSubtitleExport.render(d,true);
  assertEquals(300,d.cues.size());assertTrue(md.contains("中文 299"));assertTrue(md.contains("00:00:01.250"));
  assertNotEquals(d.cues.get(0).id,d.cues.get(1).id);
  assertTrue(PlaybackSubtitleExport.render(d,false).contains("第二行"));
 }
 @Test public void overlongSelectionRequiresExplicitRange() {
  PlaybackSubtitleDocument d=doc(3000);
  try {PlaybackSubtitleAiSource.select(d,0,d.cues.size());fail();}catch(IllegalArgumentException expected){}
  PlaybackSubtitleAiSource s=PlaybackSubtitleAiSource.select(d,200,210);
  assertTrue(s.evidence.length()<=16000);assertFalse(s.evidence.contains("中文 0\n"));
 }
 @Test public void citationsNeedSentCueExactQuoteAndCurrentIdentity() {
  PlaybackSubtitleDocument d=doc(300);PlaybackSubtitleAiSource s=PlaybackSubtitleAiSource.select(d,0,2);
  String id=d.cues.get(0).id;
  assertNotNull(PlaybackSubtitleAnswerValidator.resolve(s,id,d.cues.get(0).text,"account","aid","track",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,id,"伪造","account","aid","track",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,d.cues.get(2).id,d.cues.get(2).text,"account","aid","track",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,id,d.cues.get(0).text,"account","other","track",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,id,d.cues.get(0).text,"account","aid","track",8));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,id,d.cues.get(0).text,"other","aid","track",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,id,d.cues.get(0).text,"account","aid","other",7));
  assertNull(PlaybackSubtitleAnswerValidator.resolve(s,"fake_at_99999",d.cues.get(0).text,"account","aid","track",7));
 }
 @Test public void parserMarksLongCueTruncation() {
  String body="WEBVTT\n\n00:01.000 --> 00:02.000\n"+"字".repeat(4100);
  assertTrue(PlaybackReadingText.webvtt(body).get(0).text.endsWith("[原句截断]"));
 }
 @Test public void snapshotCopiesRowsAndDoesNotAdmitUntimedReferences() {
  List<PlaybackReadingText.Line> rows=new ArrayList<>();rows.add(new PlaybackReadingText.Line(-1,"无时间句"));
  PlaybackSubtitleDocument d=new PlaybackSubtitleDocument("account","aid","a/b:轨",1,rows);rows.clear();
  assertEquals(1,d.cues.size());assertFalse(PlaybackSubtitleExport.filename(d,true).contains("/"));
  try{d.cues.clear();fail();}catch(UnsupportedOperationException expected){}
  PlaybackSubtitleAiSource source=PlaybackSubtitleAiSource.select(d,0,1);
  assertNull(PlaybackSubtitleAnswerValidator.resolve(source,d.cues.get(0).id,"无时间句","account","aid","a/b:轨",1));
 }
 @Test public void evidenceBudgetIncludesCueIdentifiersAndSeparators() {
  PlaybackSubtitleDocument d=new PlaybackSubtitleDocument("a","v","t",1,List.of(new PlaybackReadingText.Line(0,"字".repeat(15995))));
  try{PlaybackSubtitleAiSource.select(d,0,1);fail();}catch(IllegalArgumentException expected){}
 }
}
