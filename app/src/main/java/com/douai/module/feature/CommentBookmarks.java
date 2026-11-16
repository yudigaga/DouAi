package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.ui.miuix.MiuixCommentBookmarks;
import com.douai.module.ui.miuix.MiuixCommentDetails;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Explicit local excerpts only; never retains a host comment or a media URL. */
public final class CommentBookmarks {
 private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DouAi-comment-bookmarks");t.setDaemon(true);return t;});
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private CommentBookmarks(){}
 static boolean enabled(){return HostAdaptation.currentProfile()==HostAdaptation.PROFILE_410;}
 private static boolean alive(Activity a){return a!=null&&!a.isFinishing()&&!a.isDestroyed();}
 private static String account(){return NativeFollowLists.currentAccountUid();}
 private static boolean current(Activity a,String uid){return alive(a)&&uid.equals(account());}
 private static void notice(Context a,String text){Toast.makeText(a,text,Toast.LENGTH_LONG).show();}
 private static Activity activity(Context c){for(int i=0;i<24&&c instanceof ContextWrapper;i++){if(c instanceof Activity)return (Activity)c;c=((ContextWrapper)c).getBaseContext();}return null;}
 static String text(Object c,String name){Object value=HostTargets.read(c,name);return value instanceof String?(String)value:"";}
 private static String userText(Object c,String getter){Object v=HostTargets.invoke(c,getter);return v instanceof String?(String)v:"";}
 static boolean available(Object comment){return enabled()&&LoadedCommentIndex.id(text(comment,"cid"))&&LoadedCommentIndex.id(text(comment,"awemeId"));}
 static CommentBookmarkStore.Entry capture(Object comment,long now){
  if(comment==null||!LoadedCommentIndex.id(text(comment,"cid"))||!LoadedCommentIndex.id(text(comment,"awemeId")))return null;
  Object user=HostTargets.read(comment,"user");String speech=CommentTranscript.read(comment);
  String[] raw={text(comment,"awemeId"),text(comment,"cid"),userText(user,"getUid"),userText(user,"getNickname"),text(comment,"text"),speech==null?"":speech,
   text(comment,"rootCommentId"),text(comment,"replyToReplyId"),text(comment,"replyToUserId"),text(comment,"replyToUserName"),text(comment,"replyToReplyCommentText")};
  boolean truncated=false;for(int i=0;i<raw.length;i++){int limit=(i==4||i==5||i==10)?1200:(i==3||i==9)?160:80;
   if(raw[i].length()>limit){truncated=true;int n=Character.isHighSurrogate(raw[i].charAt(limit-1))?limit-1:limit;raw[i]=raw[i].substring(0,n);}}
  return new CommentBookmarkStore.Entry(raw[0],raw[1],raw[2],raw[3],raw[4],raw[5],raw[6],raw[7],raw[8],raw[9],raw[10],now,truncated);
 }
 private interface Task<T>{T run(CommentBookmarkStore store)throws Exception;}
 private static <T> void submit(Activity a,String uid,Task<T> task,Consumer<T> done){
  if(!current(a,uid))return;
  File directory=new File(a.getApplicationContext().getNoBackupFilesDir(),"douai-comment-bookmarks");
  IO.execute(()->{if(!current(a,uid))return;try{
   T value=task.run(new CommentBookmarkStore(directory,()->current(a,uid)));
   MAIN.post(()->{if(current(a,uid))done.accept(value);});
  }catch(Exception failure){MAIN.post(()->{if(current(a,uid))notice(a,"评论收藏读写失败；原文件保留。"+(failure.getMessage()==null?"":failure.getMessage()));});}});
 }
 static void save(Context context,Object comment,String selectedAccount){
  Activity a=activity(context);String uid=account();
  if(!alive(a)||!enabled())return;if(!LoadedCommentIndex.id(uid)){notice(a,"无法确认当前抖音账号");return;}
  if(!uid.equals(selectedAccount)){notice(a,"账号已更换，请重新选择评论");return;}
  CommentBookmarkStore.Entry entry=capture(comment,System.currentTimeMillis());
  if(entry==null){notice(a,"无法确认所选评论，未收藏");return;}
  submit(a,uid,s->{s.add(uid,entry);return entry;},e->notice(a,e.truncated?"已收藏评论（长文已截断）":"已收藏评论到本机当前账号"));
 }
 public static void show(Activity a){
  if(!alive(a))return;if(!enabled()){notice(a,"当前版本不支持评论收藏");return;}
  String uid=account();if(!LoadedCommentIndex.id(uid)){notice(a,"无法确认当前抖音账号");return;}
  submit(a,uid,s->s.list(uid),rows->list(a,uid,rows));
 }
 private static String excerpt(CommentBookmarkStore.Entry e){
  String value=e.text.isEmpty()?e.asr:e.text;
  return value.isEmpty()?"纯媒体评论（仅保存定位信息，未下载媒体）":value;
 }
 private static String details(CommentBookmarkStore.Entry e){
  StringBuilder out=new StringBuilder("作品 ").append(e.aid).append("\n评论 ").append(e.cid);
  if(!e.author.isEmpty())out.append("\n作者 ").append(e.author);
  if(!e.authorUid.isEmpty())out.append("（").append(e.authorUid).append("）");
  out.append("\n\n").append(excerpt(e));
  if(!e.asr.isEmpty()&&!e.text.isEmpty())out.append("\n\n已有转写：\n").append(e.asr);
  if(!e.rootCid.isEmpty())out.append("\n\n根评论 ").append(e.rootCid);
  if(!e.replyCid.isEmpty())out.append("\n被回复评论 ").append(e.replyCid);
  if(!e.replyAuthor.isEmpty()||!e.replyUid.isEmpty())out.append("\n被回复作者 ").append(e.replyAuthor).append(" ").append(e.replyUid);
  if(!e.replyText.isEmpty())out.append("\n被回复正文：\n").append(e.replyText);
  else out.append("\n未下发的回复上下文未保存");
  out.append("\n\n保存于 ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.ROOT).format(new Date(e.savedAt)));
  if(e.truncated)out.append("\n长文已截断");return out.toString();
 }
 private static void guard(AlertDialog d,Activity a,String uid,Runnable dispose){
  Runnable check=new Runnable(){public void run(){if(!current(a,uid)){d.dismiss();return;}MAIN.postDelayed(this,600);}};
  d.setOnDismissListener(x->{MAIN.removeCallbacks(check);dispose.run();});d.show();MAIN.postDelayed(check,600);
 }
 private static void list(Activity a,String uid,List<CommentBookmarkStore.Entry> rows){
  List<MiuixCommentBookmarks.Entry> display=new ArrayList<>();
  for(CommentBookmarkStore.Entry e:rows)display.add(new MiuixCommentBookmarks.Entry(e.aid+":"+e.cid,e.author.isEmpty()?"评论 "+e.cid:e.author,excerpt(e),details(e)));
  AlertDialog[] holder=new AlertDialog[1];
  MiuixCommentBookmarks body=new MiuixCommentBookmarks(a,display,key->{if(!current(a,uid))return;holder[0].dismiss();
   for(CommentBookmarkStore.Entry e:rows)if(key.equals(e.aid+":"+e.cid)){detail(a,uid,e);return;}});
  holder[0]=new MiuixDialogBuilder(a,true).setTitle("评论收藏").setView(body.getView()).setNegativeButton("关闭",null).create();
  guard(holder[0],a,uid,body::close);
 }
 private static void detail(Activity a,String uid,CommentBookmarkStore.Entry e){
  AlertDialog d=new MiuixDialogBuilder(a,true).setTitle("评论摘录").setView(MiuixCommentDetails.transcript(a,details(e)))
   .setPositiveButton("回到评论",(dialog,w)->{if(current(a,uid))navigate(a,uid,e);})
   .setNeutralButton("删除",(dialog,w)->{if(!current(a,uid))return;
    AlertDialog confirm=new MiuixDialogBuilder(a,true).setTitle("删除此评论收藏？").setMessage(excerpt(e)).setNegativeButton("取消",null)
     .setPositiveButton("删除",(x,y)->{if(current(a,uid))submit(a,uid,s->{s.remove(uid,e.aid,e.cid);return true;},ok->show(a));}).create();guard(confirm,a,uid,()->{});})
   .setNegativeButton("返回",(dialog,w)->{if(current(a,uid))show(a);}).create();guard(d,a,uid,()->{});
 }
 private static void navigate(Activity a,String uid,CommentBookmarkStore.Entry e){
  if(!current(a,uid))return;
  if(LoadedCommentSearchHook.locateBookmark(a,uid,e.aid,e.cid))return;
  try{Uri uri=new Uri.Builder().scheme("aweme").authority("aweme").appendPath("detail").appendPath(e.aid).appendQueryParameter("cid",e.cid).build();
   a.startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage("com.ss.android.ugc.aweme"));
   notice(a,"已请求打开作品评论；原评论可能不可见或已删除，摘录仍保留");
  }catch(RuntimeException failure){notice(a,"无法打开原评论，收藏摘录仍保留");}
 }
}
