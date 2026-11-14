package com.douai.module.feature;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** Snapshots only a verified current model. URLs live in this short-lived request, never in the database. */
final class BookmarkWorkDetails {
    final String author,description;
    final List<String> covers;
    BookmarkWorkDetails(String author,String description,List<String> covers) {
        this.author=PlaybackBookmarkStore.clip(author,PlaybackBookmarkStore.MAX_AUTHOR);
        this.description=PlaybackBookmarkStore.clip(description,PlaybackBookmarkStore.MAX_DESCRIPTION);this.covers=covers;
    }
    static BookmarkWorkDetails current(Object aweme,String expected) {
        if(expected==null||!expected.equals(HostTargets.invoke(aweme,"getAid")))return null;
        Object user=HostTargets.invoke(aweme,"getAuthor");if(user==null)user=DownloadActionHook.jsonValue(aweme,"author");
        List<String> covers;
        try{covers=HostDouyinSearch.covers(aweme);}catch(RuntimeException|LinkageError unavailable){covers=Collections.emptyList();}
        return new BookmarkWorkDetails(text(user,"getNickname","nickname"),text(aweme,"getDesc","desc"),covers);
    }
    private static String text(Object obj,String getter,String field) {
        Object v=HostTargets.invoke(obj,getter);if(!(v instanceof String)||((String)v).isEmpty())v=DownloadActionHook.jsonValue(obj,field);
        return v instanceof String?(String)v:"";
    }
    private static final Set<String> pending=ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor coversIo=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(7),r->{Thread t=new Thread(r,"DouAi-bookmark-cover");t.setDaemon(true);return t;});
    /** Called by serialized bookmark IO only. Do not hold the store worker while awaiting a CDN response. */
    void cache(File dir,String uid,String aid,Executor storeIo,Runnable refreshed) {
        if(covers.isEmpty())return;
        String key=uid+":"+aid;
        try{
            if(pending.contains(key)||!new PlaybackBookmarkStore(dir).needsCover(uid,aid)||!pending.add(key))return;
            coversIo.execute(()->{
                byte[] bytes=null;
                try{Bitmap input=new VideoCoverLoader.Request(new MediaReadCancellation(7_000)).load(covers);
                    try{bytes=thumbnail(input);}finally{if(input!=null)input.recycle();}
                }catch(Exception|OutOfMemoryError ignored){}
                final byte[] image=bytes;
                storeIo.execute(()->{try{
                    if(image!=null && new PlaybackBookmarkStore(dir).saveCoverIfReferenced(uid,aid,image))refreshed.run();
                }catch(Exception ignored){}finally{pending.remove(key);}});
            });
        }catch(Exception unavailable){pending.remove(key);}
    }
    static byte[] thumbnail(Bitmap input){
        if(input==null || input.getWidth()<=0||input.getHeight()<=0)return null;
        float scale=Math.min(1f,128f/Math.max(input.getWidth(),input.getHeight()));
        Bitmap small=Bitmap.createScaledBitmap(input,Math.max(1,Math.round(input.getWidth()*scale)),
                Math.max(1,Math.round(input.getHeight()*scale)),true);
        try{ByteArrayOutputStream buf=new ByteArrayOutputStream();
            for(int quality:new int[]{78,56,34}){
                buf.reset();if(small.compress(Bitmap.CompressFormat.JPEG,quality,buf)&&buf.size()<=PlaybackBookmarkStore.MAX_COVER_BYTES)return buf.toByteArray();
            }
            return null;
        }finally{if(small!=input)small.recycle();}
    }
}
