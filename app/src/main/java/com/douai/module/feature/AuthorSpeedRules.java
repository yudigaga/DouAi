package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.douai.module.config.PlaybackSpeedPresets;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixAuthorSpeedRules;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class AuthorSpeedRules {
 private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DouAi-author-speed");t.setDaemon(true);return t;});
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static AuthorSpeedRuleStore store;
 private static String account="";
 private static List<AuthorSpeedRuleStore.Rule> cached=List.of();
 private static long generation;
 private static Runnable changed;
 private static long presentation;
 private static java.lang.ref.WeakReference<android.app.AlertDialog> visible=new java.lang.ref.WeakReference<>(null);
 private AuthorSpeedRules(){}
 public static synchronized void initialize(Context c){if(store==null)store=new AuthorSpeedRuleStore(new File(c.getApplicationContext().getNoBackupFilesDir(),"douai-author-speed"));}
 static synchronized void listen(Runnable listener){changed=listener;}
 private static void publishChange(){Runnable r; synchronized(AuthorSpeedRules.class){r=changed;}if(r!=null)MAIN.post(r);}
 static synchronized Float findSpeed(String uid,String author){
  if(store==null||!PlaybackBookmarkStore.id(uid))return null;
  if(!uid.equals(account)){account=uid;cached=List.of();long token=++generation;
   IO.execute(()->{try{var rows=store.list(uid);synchronized(AuthorSpeedRules.class){if(token!=generation||!uid.equals(account))return;cached=rows;}publishChange();}catch(Exception ignored){}});
  }
  for(var r:cached)if(r.id.equals(author))return r.speed;return null;
 }
 private static String uid(){return PlaybackBookmarks.account();}
 private static boolean alive(Activity a){return a!=null&&!a.isFinishing()&&!a.isDestroyed();}
 private static boolean eligible(Activity a){return alive(a)&&HostAdaptation.currentProfile()==HostAdaptation.PROFILE_410&&PlaybackBookmarkStore.id(uid());}
 private static void toast(Activity a,String t){if(alive(a))Toast.makeText(a,t,Toast.LENGTH_SHORT).show();}
 private interface Mutation{void run()throws Exception;}
 private static void mutate(Activity a,String owner,Mutation mutation,Runnable done){
  if(!eligible(a)||!owner.equals(uid()))return;
  synchronized(AuthorSpeedRules.class){generation++;account="";cached=List.of();}publishChange();
  IO.execute(()->{try{mutation.run();MAIN.post(()->{if(owner.equals(uid())&&eligible(a)){synchronized(AuthorSpeedRules.class){account="";}findSpeed(owner,"");done.run();}});}catch(Exception e){MAIN.post(()->{toast(a,"保存失败，原规则已保留");synchronized(AuthorSpeedRules.class){account="";}findSpeed(uid(),"");});}});
 }
 public static void add(Activity a,Object work,BooleanSupplier current){
  if(!eligible(a)){toast(a,"请在支持的抖音版本内登录后使用");return;}initialize(a);String owner=uid();
  Object raw=HostTargets.invoke(work,"getAuthorUid");if(!(raw instanceof String)||!PlaybackBookmarkStore.id((String)raw)){toast(a,"当前作品没有可用作者UID");return;}
  String id=(String)raw;Object label=HostTargets.read(HostTargets.read(work,"author"),"nickname");String name=label instanceof String?(String)label:id;
  edit(a,owner,id,name.length()>200?name.substring(0,200):name,current,()->toast(a,"规则已保存；开启作者独立倍速后生效"));
 }
 private static void edit(Activity a,String owner,String id,String name,BooleanSupplier current,Runnable done){
  new MiuixDialogBuilder(a,true).setTitle(name+" · 作者倍速").setItems(PlaybackSpeedPresets.labels(),(d,n)->{
   if(!current.getAsBoolean()||!owner.equals(uid())){toast(a,"作品或账号已切换");return;}
   mutate(a,owner,()->store.put(owner,new AuthorSpeedRuleStore.Rule(id,name,Float.parseFloat(PlaybackSpeedPresets.values()[n]))),done);
  }).setNegativeButton("取消",null).show();
 }
 public static void show(Activity a){
  if(!eligible(a)){toast(a,"请在支持的抖音版本内登录后使用");return;}initialize(a);String owner=uid();long viewToken=++presentation;
  IO.execute(()->{try{var rows=store.list(owner);MAIN.post(()->{if(viewToken!=presentation||!eligible(a)||!owner.equals(uid()))return;
   var previous=visible.get();if(previous!=null)previous.dismiss();
   var body=new MiuixAuthorSpeedRules(a,rows,r->new MiuixDialogBuilder(a,true).setTitle(r.name)
    .setItems(new String[]{"修改倍速","删除规则"},(d,n)->{if(n==0)edit(a,owner,r.id,r.name,()->owner.equals(uid()),()->show(a));else mutate(a,owner,()->store.remove(owner,r.id),()->show(a));}).setNegativeButton("取消",null).show());
   var dialog=new MiuixDialogBuilder(a,true).setTitle("作者独立倍速 · "+rows.size()+" / 500").setView(body.getView())
    .setPositiveButton("清空当前账号",(d,n)->new MiuixDialogBuilder(a,true).setTitle("清空当前账号作者倍速规则？").setPositiveButton("清空",(x,y)->mutate(a,owner,()->store.clear(owner),()->show(a))).setNegativeButton("取消",null).show()).setNegativeButton("关闭",null).create();
   Runnable watch=new Runnable(){public void run(){if(!alive(a)||!owner.equals(uid()))dialog.dismiss();else if(dialog.isShowing())MAIN.postDelayed(this,500);}};
   dialog.setOnDismissListener(d->{MAIN.removeCallbacks(watch);body.close();if(visible.get()==dialog)visible.clear();});visible=new java.lang.ref.WeakReference<>(dialog);dialog.show();MAIN.post(watch);
  });}catch(Exception e){MAIN.post(()->toast(a,"规则读取失败，未修改数据"));}});
 }
}
