package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixTemporaryFeedRules;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Publishes immutable rule snapshots; request hooks never perform file I/O. */
public final class TemporaryFeedRules {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DouAi-temporary-feed");t.setDaemon(true);return t;});
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static TemporaryFeedRuleStore store;
    private static String account="";
    private static long generation;
    private static List<TemporaryFeedRuleStore.Rule> cached;
    private static java.lang.ref.WeakReference<android.app.AlertDialog> visible=new java.lang.ref.WeakReference<>(null);
    private static long presentation;
    private TemporaryFeedRules(){}
    public static synchronized void initialize(Context context){
        if(store==null)store=new TemporaryFeedRuleStore(new File(context.getApplicationContext().getNoBackupFilesDir(),"douai-temporary-feed"));
    }
    public static final class Snapshot {
        final String account;final long generation;final List<TemporaryFeedRuleStore.Rule> rules;
        Snapshot(String a,long g,List<TemporaryFeedRuleStore.Rule> r){account=a;generation=g;rules=r;}
        boolean hides(Object work,long now){
            if(work==null||rules.isEmpty())return false;
            try {
                String author="",music="";
                boolean authors=false,musicRules=false;
                for(var rule:rules)if(rule.kind==TemporaryFeedRuleStore.Kind.AUTHOR)authors=true;else musicRules=true;
                if(authors){Object value=work.getClass().getMethod("getAuthorUid").invoke(work);if(value instanceof String)author=(String)value;}
                if(musicRules){Object value=HostTargets.field(work.getClass(),"music").get(work);
                    if(value!=null){Object id=HostTargets.field(value.getClass(),"mid").get(value);if(id instanceof String)music=(String)id;}}
                return TemporaryFeedRuleStore.matches(rules,author,music,now);
            } catch(ReflectiveOperationException failure){
                // FeedResponseFilter catches this and retains the entire original page.
                throw new IllegalStateException("Temporary filter model unavailable",failure);
            }
        }
    }
    static synchronized Snapshot snapshot(String uid){
        if(store==null||!PlaybackBookmarkStore.id(uid))return null;
        if(!account.equals(uid)){
            account=uid;cached=null;long token=++generation;
            IO.execute(()->{
                try{List<TemporaryFeedRuleStore.Rule> rows=store.list(uid,System.currentTimeMillis());
                    synchronized(TemporaryFeedRules.class){if(generation==token&&account.equals(uid))cached=rows;}}
                catch(Exception ignored){/* Unknown data must not remove a recommendation. */}
            });
        }
        return cached==null?null:new Snapshot(uid,generation,cached);
    }
    static synchronized boolean current(Snapshot s,String uid){return s!=null&&s.generation==generation&&s.account.equals(account)&&s.account.equals(uid);}
    private static String uid(){try{return NativeFollowLists.currentAccountUid();}catch(RuntimeException e){return "";}}
    private static boolean alive(Activity a){return a!=null&&!a.isFinishing()&&!a.isDestroyed();}
    private static boolean eligible(Activity a){return alive(a)&&HostAdaptation.currentProfile()==HostAdaptation.PROFILE_410&&PlaybackBookmarkStore.id(uid());}
    private static void toast(Activity a,String text){if(alive(a))Toast.makeText(a,text,Toast.LENGTH_SHORT).show();}
    private interface Mutation{void run(TemporaryFeedRuleStore s)throws Exception;}
    private static void mutate(Activity a,String owner,Mutation action,Runnable done){
        if(!eligible(a)||!owner.equals(uid()))return;initialize(a);
        synchronized(TemporaryFeedRules.class){generation++;cached=null;account="";}
        IO.execute(()->{
            try{action.run(store);MAIN.post(()->{if(eligible(a)&&owner.equals(uid()))done.run();});}
            catch(Exception e){MAIN.post(()->{if(alive(a)&&owner.equals(uid()))toast(a,"规则保存失败，原有规则已保留");});}
        });
    }
    public static void add(Activity a,Object work,boolean music,BooleanSupplier current){
        if(!eligible(a)){toast(a,"请从抖音内登录账号后使用");return;}String owner=uid();
        Object m=HostTargets.read(work,"music"),author=HostTargets.read(work,"author");
        Object raw=music?HostTargets.read(m,"mid"):HostTargets.invoke(work,"getAuthorUid");
        if(!(raw instanceof String)||!PlaybackBookmarkStore.id((String)raw)){toast(a,"当前作品未提供可用标识");return;}
        String id=(String)raw;Object label=HostTargets.read(music?m:author,music?"musicName":"nickname");
        String name=label instanceof String?(String)label:id;
        if(name.length()>200){int end=Character.isHighSurrogate(name.charAt(199))?199:200;name=name.substring(0,end);}String display=name;
        new MiuixDialogBuilder(a,true).setTitle(music?"临时过滤这首配乐":"临时过滤这位作者")
                .setItems(new String[]{"今天","7天"},(dialog,which)->{
                    if(!current.getAsBoolean()||!owner.equals(uid())){toast(a,"作品或账号已切换");return;}
                    long now=System.currentTimeMillis(),until;
                    if(which==0){Calendar next=Calendar.getInstance();next.setTimeInMillis(now);next.add(Calendar.DAY_OF_YEAR,1);next.set(Calendar.HOUR_OF_DAY,0);next.set(Calendar.MINUTE,0);next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);until=next.getTimeInMillis();}
                    else until=now+7*86_400_000L;
                    var rule=new TemporaryFeedRuleStore.Rule(music?TemporaryFeedRuleStore.Kind.MUSIC:TemporaryFeedRuleStore.Kind.AUTHOR,id,display,now,until);
                    mutate(a,owner,s->s.put(owner,rule,now),()->toast(a,"规则已保存；开启过滤总开关与临时过滤后对后续主推荐生效"));
                }).setNegativeButton("取消",null).show();
    }
    public static void show(Activity a){
        if(!eligible(a)){toast(a,"请从抖音内登录账号后使用");return;}initialize(a);String owner=uid();long token=++presentation;
        IO.execute(()->{try{List<TemporaryFeedRuleStore.Rule> rows=store.list(owner,System.currentTimeMillis());
            MAIN.post(()->{if(token!=presentation||!eligible(a)||!owner.equals(uid()))return;
                var old=visible.get();if(old!=null)old.dismiss();
                var body=new MiuixTemporaryFeedRules(a,rows,rule->new MiuixDialogBuilder(a,true).setTitle(rule.name)
                        .setItems(new String[]{"撤销这条规则"},(d,n)->mutate(a,owner,s->s.remove(owner,rule.key(),System.currentTimeMillis()),()->show(a)))
                        .setNegativeButton("取消",null).show());
                var dialog=new MiuixDialogBuilder(a,true).setTitle("临时过滤规则").setView(body.getView())
                        .setPositiveButton("清空当前账号",(d,n)->new MiuixDialogBuilder(a,true).setTitle("清空当前账号的临时规则？")
                                .setPositiveButton("清空",(yes,k)->mutate(a,owner,s->s.clear(owner),()->show(a))).setNegativeButton("取消",null).show())
                        .setNegativeButton("关闭",null).create();
                Runnable watch=new Runnable(){public void run(){if(!alive(a)||!owner.equals(uid()))dialog.dismiss();else if(dialog.isShowing())MAIN.postDelayed(this,500);}};
                dialog.setOnDismissListener(d->{MAIN.removeCallbacks(watch);body.close();if(visible.get()==dialog)visible.clear();});visible=new java.lang.ref.WeakReference<>(dialog);dialog.show();MAIN.post(watch);
            });
        }catch(Exception e){MAIN.post(()->toast(a,"规则读取失败，未改动本地数据"));}});
    }
}
