package com.douai.module.feature;
import java.util.*;
/** Detached, immutable selected subtitle track; chapters are never mixed into this document. */
public final class PlaybackSubtitleDocument {
 public static final class Cue {
  public final String id,text; public final long startMs;
  Cue(String id,long startMs,String text){this.id=id;this.startMs=startMs;this.text=text;}
 }
 public final String account,aid,track; public final long generation; public final List<Cue> cues;
 PlaybackSubtitleDocument(String account,String aid,String track,long generation,List<PlaybackReadingText.Line> rows){
  if(account==null||account.isEmpty()||aid==null||aid.isEmpty()||track==null||track.isEmpty())throw new IllegalArgumentException("无法确认字幕所属账号或作品");
  if(rows.size()>PlaybackReadingText.MAX_LINES)throw new IllegalArgumentException("字幕超过5000句");
  this.account=account;this.aid=aid;this.track=track;this.generation=generation;
  List<Cue> copy=new ArrayList<>(rows.size());int size=0;
  for(PlaybackReadingText.Line row:rows){size+=row.text.length();if(size>PlaybackReadingText.MAX_CHARS)throw new IllegalArgumentException("字幕大小超限");
   copy.add(new Cue("g"+generation+"c"+(copy.size()+1),row.millis,row.text));}
  this.cues=Collections.unmodifiableList(copy);
 }
 public boolean matches(String account,String aid,String track,long generation){return this.account.equals(account)&&this.aid.equals(aid)&&this.track.equals(track)&&this.generation==generation;}
 public static String clock(long millis){if(millis<0)return "无时间码";return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",millis/3600000,millis/60000%60,millis/1000%60,millis%1000);}
}
