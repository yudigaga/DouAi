package com.douai.module.feature;
/** Full selected track export, independent of the UI query and 200-row display budget. */
public final class PlaybackSubtitleExport {
 private PlaybackSubtitleExport(){}
 public static String filename(PlaybackSubtitleDocument doc,boolean markdown){return ("字幕_"+doc.aid+"_"+doc.track).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").substring(0,Math.min(60,("字幕_"+doc.aid+"_"+doc.track).length()))+(markdown?".md":".txt");}
 public static String render(PlaybackSubtitleDocument doc,boolean markdown){
  StringBuilder out=new StringBuilder();out.append(markdown?"# 字幕全文\n\n":"字幕全文\n").append("作品：").append(doc.aid).append("\n字幕轨：").append(doc.track).append("\n共 ").append(doc.cues.size()).append(" 句；保留原句截断标记\n\n");
  for(PlaybackSubtitleDocument.Cue cue:doc.cues){
   if(markdown)out.append("## ").append(PlaybackSubtitleDocument.clock(cue.startMs)).append(" · ").append(cue.id).append("\n\n").append(cue.text.replace("\n","  \n"));
   else out.append(cue.text);
   out.append("\n\n");
  }return out.toString();
 }
}
