package com.douai.module.feature;
/** Model-generated timestamps are never inputs to this validator. */
public final class PlaybackSubtitleAnswerValidator {
 private PlaybackSubtitleAnswerValidator(){}
 public static PlaybackSubtitleDocument.Cue resolve(PlaybackSubtitleAiSource s,String id,String quote,String account,String aid,String track,long generation){
  if(s==null||!s.document.matches(account,aid,track,generation))return null;
  PlaybackSubtitleDocument.Cue cue=s.cues.get(id);
  return cue!=null&&cue.startMs>=0&&cue.text.equals(quote)?cue:null;
 }
}
