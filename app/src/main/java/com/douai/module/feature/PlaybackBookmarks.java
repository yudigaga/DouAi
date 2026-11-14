package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.widget.*;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixPlaybackBookmarks;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Host-side UI. No cross-process reads, playback history or automatic bookmark collection. */
public final class PlaybackBookmarks {
    interface Navigator { boolean open(Activity owner,String account,PlaybackBookmarkStore.Entry entry); }
    private static volatile Navigator navigation;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static String visibleAccount;
    private static java.lang.ref.WeakReference<MiuixPlaybackBookmarks> visibleBody=new java.lang.ref.WeakReference<>(null);
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"DouAi-bookmarks");t.setDaemon(true);return t;});
    static void attach(Navigator value){navigation=value;}
    static void detach(Navigator value){if(navigation==value)navigation=null;}
    static boolean enabled(){return HostAdaptation.currentProfile()==HostAdaptation.PROFILE_410;}
    static String account(){return NativeFollowLists.currentAccountUid();}
    private static boolean alive(Activity owner){return owner!=null&&!owner.isFinishing()&&!owner.isDestroyed();}
    private static void notice(Context owner,String text){Toast.makeText(owner,text,Toast.LENGTH_SHORT).show();}
    private static String checkedAccount(Activity owner){
        if(!alive(owner)||!enabled())return null;
        String uid=account(); if(PlaybackBookmarkStore.id(uid))return uid;
        notice(owner,"无法确认当前抖音账号");return null;
    }
    private interface Task<T>{T run(PlaybackBookmarkStore store)throws Exception;}
    private static <T> void submit(Activity owner,String uid,Task<T> task,Consumer<T> done){
        if(!alive(owner)||!uid.equals(account())){if(alive(owner))notice(owner,"账号已更换，请重新打开书签");return;}
        File dir=dir(owner);
        IO.execute(()->{
            try{
                T result=task.run(new PlaybackBookmarkStore(dir));
                owner.runOnUiThread(()->{if(alive(owner)&&uid.equals(account()))done.accept(result);});
            }catch(Exception failure){
                owner.runOnUiThread(()->{if(alive(owner)&&uid.equals(account()))
                    notice(owner,"书签读写失败，请检查存储空间或书签数量");});
            }
        });
    }
    private static File dir(Context owner){return new File(owner.getApplicationContext().getNoBackupFilesDir(),"douai-playback-bookmarks");}
    private static void cache(BookmarkWorkDetails details,File directory,String uid,String aid){
        details.cache(directory,uid,aid,IO,()->MAIN.post(()->{
            MiuixPlaybackBookmarks body=visibleBody.get();
            if(body!=null && uid.equals(visibleAccount) && uid.equals(account()))body.thumbnailReady(aid);
        }));
    }
    static void enrichVerified(Activity owner,String uid,String aid,Object aweme){
        if(!alive(owner)||!PlaybackBookmarkStore.id(uid)||!uid.equals(account()))return;
        BookmarkWorkDetails details=BookmarkWorkDetails.current(aweme,aid);
        if(details==null)return;
        File directory=dir(owner);
        IO.execute(()->{try{
            new PlaybackBookmarkStore(directory).enrich(uid,aid,details.author,details.description);
            cache(details,directory,uid,aid);
        }catch(Exception ignored){}});
    }
    static void add(Activity owner,PlaybackPositionAccess.Snapshot capture,Object aweme,BooleanSupplier current){
        if(capture==null){if(alive(owner))notice(owner,"当前视频没有可记录的播放位置");return;}
        String uid=checkedAccount(owner);if(uid==null)return;
        BookmarkWorkDetails details=BookmarkWorkDetails.current(aweme,capture.aid);
        if(details==null){notice(owner,"作品已切换，未保存");return;}
        editBox(owner,"记下 "+PlaybackBookmarkStore.time(capture.millis),"",memo->{
            if(!current.getAsBoolean()){notice(owner,"作品已切换，未保存");return;}
            submit(owner,uid,s->{var saved=s.add(uid,capture.aid,capture.millis,memo,System.currentTimeMillis(),details.author,details.description);
                        cache(details,s.directory,uid,capture.aid);return saved;},
                    entry->notice(owner,"已保存到本机书签"));
        });
    }
    public static void show(Activity owner){
        String uid=checkedAccount(owner);if(uid==null)return;
        submit(owner,uid,s->s.list(uid,""),rows->list(owner,uid,rows));
    }
    private static void editBox(Activity a,String title,String initial,Consumer<String> save){
        EditText input=MiuixFormControls.input(a,"备注（可留空）",initial,false);input.setSingleLine();
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PlaybackBookmarkStore.MAX_NOTE)});
        input.setText(initial);input.setSelection(input.length());
        new MiuixDialogBuilder(a,true).setTitle(title).setView(MiuixFormControls.viewFor(input)).setNegativeButton("取消",null)
                .setPositiveButton("保存",(d,w)->save.accept(input.getText().toString())).show();
    }
    private static void list(Activity a,String uid,List<PlaybackBookmarkStore.Entry> rows){
        List<MiuixPlaybackBookmarks.Row> items=new ArrayList<>(rows.size());
        PlaybackBookmarkStore store=new PlaybackBookmarkStore(dir(a));
        for(PlaybackBookmarkStore.Entry e:rows)items.add(new MiuixPlaybackBookmarks.Row(e.id,e.aid,PlaybackBookmarkStore.time(e.millis),
                e.note,e.author,e.description,store.cover(uid,e.aid)));
        AlertDialog[] active=new AlertDialog[1];
        MiuixPlaybackBookmarks body=new MiuixPlaybackBookmarks(a,items,id->{
            if(active[0]==null || !active[0].isShowing())return;
            active[0].dismiss();
            if(!uid.equals(account())){notice(a,"账号已更换");return;}
            for(PlaybackBookmarkStore.Entry e:rows)if(e.id.equals(id)){actions(a,uid,e);break;}
        });
        AlertDialog dialog=new MiuixDialogBuilder(a,true).setTitle("我的时间点书签").setView(body.getView()).setNegativeButton("关闭",null).create();
        active[0]=dialog;visibleAccount=uid;visibleBody=new java.lang.ref.WeakReference<>(body);
        // A delayed account switch must not leave another account's notes visible indefinitely.
        Handler main=new Handler(Looper.getMainLooper());
        Runnable guard=new Runnable(){public void run(){
            if(!alive(a)||!uid.equals(account())){dialog.dismiss();return;} main.postDelayed(this,800);}};
        dialog.setOnDismissListener(d->{main.removeCallbacks(guard);body.close();active[0]=null;
            if(visibleBody.get()==body){visibleBody.clear();visibleAccount=null;}});
        dialog.show();main.postDelayed(guard,800);
    }
    private static void actions(Activity a,String uid,PlaybackBookmarkStore.Entry e){
        new MiuixDialogBuilder(a,true).setTitle(e.label()).setItems(new String[]{"打开并回到这一刻","修改备注","删除"},(dialog,i)->{
            if(!uid.equals(account())){notice(a,"账号已更换");return;}
            if(i==0){
                Navigator nav=navigation;
                if(nav==null||!nav.open(a,uid,e))notice(a,"暂时无法定位，请稍后从书签重试");
            }else if(i==1)editBox(a,"修改备注",e.note,memo->submit(a,uid,s->s.edit(uid,e.id,memo),ok->show(a)));
            else new MiuixDialogBuilder(a,true).setTitle("删除此书签？").setMessage(e.label())
                    .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->submit(a,uid,s->s.remove(uid,e.id),ok->show(a))).show();
        }).setNegativeButton("返回",(d,w)->{if(uid.equals(account()))show(a);}).show();
    }
    static void route(Activity a,String aid)throws ReflectiveOperationException{
        if(!PlaybackBookmarkStore.id(aid))throw new IllegalArgumentException("aid");
        Class<?> router=Class.forName("com.bytedance.router.SmartRouter",false,a.getApplicationContext().getClassLoader());
        Object route=router.getMethod("buildRoute",Context.class,String.class).invoke(null,a,"aweme://aweme/detail/"+aid);
        route.getClass().getMethod("open").invoke(route);
    }
}
