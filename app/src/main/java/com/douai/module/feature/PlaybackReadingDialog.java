package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.widget.Toast;
import com.douai.module.config.*;
import com.douai.module.ui.HostConfigFilePicker;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixPlaybackReading;
import com.douai.module.scan.HostAdaptation;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/** Explicit current-work reading, full-track export and bounded subtitle questions. */
public final class PlaybackReadingDialog {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"DouAi-subtitle");t.setDaemon(true);return t;});
    private static final ExecutorService CLEANUP=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"DouAi-subtitle-cleanup");t.setDaemon(true);return t;});
    private static java.lang.ref.WeakReference<Session> active=new java.lang.ref.WeakReference<>(null);
    private PlaybackReadingDialog(){}
    public static void dismiss(){Session session=active.get();if(session!=null){if(Looper.myLooper()==Looper.getMainLooper())session.close();else session.main.post(session::close);}}
    public static void show(Activity owner,Object aweme,BooleanSupplier current,LongPredicate seek){open(owner,aweme,current,current,seek,"");}
    public static void showExport(Activity owner,Object aweme,BooleanSupplier current,LongPredicate seek){open(owner,aweme,current,current,seek,"export");}
    public static void showAsk(Activity owner,Object aweme,BooleanSupplier current,LongPredicate seek){open(owner,aweme,current,current,seek,"ask");}
    public static void show(Activity owner,Object aweme,BooleanSupplier current,BooleanSupplier stableOwner,LongPredicate seek){open(owner,aweme,current,stableOwner,seek,"");}
    public static void showExport(Activity owner,Object aweme,BooleanSupplier current,BooleanSupplier stableOwner,LongPredicate seek){open(owner,aweme,current,stableOwner,seek,"export");}
    public static void showAsk(Activity owner,Object aweme,BooleanSupplier current,BooleanSupplier stableOwner,LongPredicate seek){open(owner,aweme,current,stableOwner,seek,"ask");}
    private static void open(Activity owner,Object aweme,BooleanSupplier current,BooleanSupplier stableOwner,LongPredicate seek,String action){
        if(!HostAdaptation.PROFILE_410.equals(HostAdaptation.currentProfile()))return;
        Session previous=active.get();if(previous!=null)previous.close();
        if(owner==null||owner.isFinishing()||owner.isDestroyed())return;
        Session session=new Session(owner,aweme,current,stableOwner,seek);active=new java.lang.ref.WeakReference<>(session);session.show();
        if(!action.isEmpty())session.selectTrack(action);
    }
    private static final class Track {
        final String name,format,url,key;
        Track(String name,String format,String url,int index){this.name=name;this.format=format;this.url=url;this.key=(index+1)+"_"+name;}
    }
    private static final class Session implements MiuixPlaybackReading.Actions {
        final Activity owner;final BooleanSupplier current,stableOwner;final LongPredicate seek;
        final String account,aid;final HostSettingsStore settings;
        final Handler main=new Handler(Looper.getMainLooper());
        final List<PlaybackReadingText.Line> chapters=new ArrayList<>();final List<Track> tracks=new ArrayList<>();
        List<PlaybackReadingText.Line> rows;PlaybackSubtitleDocument document;PlaybackSubtitleAiSource sent;
        List<PlaybackSubtitleDocument.Cue> verified=new ArrayList<>();
        MiuixPlaybackReading body;AlertDialog dialog,child;HostConfigFilePicker picker;
        MediaReadCancellation cancellation;Future<?> job;ChatCompletionClient client;
        AiModelSettings models;String modelsRaw="",selectedModel="",status="",coverage="",answer="";
        volatile boolean closed,safOwnedInterval;boolean safReturned;Uri savedForShare;boolean savedMarkdown;volatile int epoch;int generation;boolean busy;
        final Runnable watch=new Runnable(){public void run(){
            if(!valid()){close();return;}
            if(safOwnedInterval&&safReturned&&foreground()){safOwnedInterval=false;if(savedForShare!=null){Uri saved=savedForShare;savedForShare=null;share(saved,savedMarkdown);}}
            String raw=settings.getString(AiModelSettings.KEY,"");
            if(!raw.equals(modelsRaw)){cancel();readModels();render();}
            main.postDelayed(this,500);
        }};
        Session(Activity owner,Object aweme,BooleanSupplier current,LongPredicate seek){this(owner,aweme,current,current,seek);}
        Session(Activity owner,Object aweme,BooleanSupplier current,BooleanSupplier stableOwner,LongPredicate seek){
            this.owner=owner;this.current=current;this.stableOwner=stableOwner;this.seek=seek;
            String uid=NativeFollowLists.currentAccountUid();account=uid==null?"":uid;
            Object id=HostTargets.invoke(aweme,"getAid");aid=id instanceof String?(String)id:"";
            settings=new HostSettingsStore(owner);readModels();
            Object entries=HostTargets.invoke(aweme,"getMoreChapterList");
            if(entries instanceof Iterable<?>)for(Object entry:(Iterable<?>)entries){
                Object time=DownloadActionHook.jsonValue(entry,"timestamp");String title=text(entry,"desc"),keyword=text(entry,"key_word");
                if(!title.isEmpty()||!keyword.isEmpty())chapters.add(new PlaybackReadingText.Line(time instanceof Number&&((Number)time).longValue()>=0?((Number)time).longValue():-1,title.isEmpty()?keyword:title+(keyword.isEmpty()?"":" · "+keyword)));
                if(chapters.size()>=500)break;
            }
            Object raw=DownloadActionHook.jsonValue(DownloadActionHook.jsonValue(aweme,"cla_info"),"list");
            if(raw instanceof Iterable<?>)for(Object entry:(Iterable<?>)raw){
                String url=text(entry,"url"),format=text(entry,"format").toLowerCase(Locale.ROOT),name=text(entry,"language");
                if(!url.isEmpty())tracks.add(new Track(name.isEmpty()?"字幕轨 "+(tracks.size()+1):name,format,url,tracks.size()));if(tracks.size()>=16)break;
            }rows=chapters;
        }
        void show(){
            body=new MiuixPlaybackReading(owner,this);
            dialog=new MiuixDialogBuilder(owner,true).setTitle("章节与字幕").setView(body.getView()).setNegativeButton("关闭",null).create();
            dialog.setOnDismissListener(d->close());dialog.show();render();main.post(watch);
        }
        // SAF pauses the host; only its explicit document operation may use stable ownership.
        boolean valid(){try{return !closed&&!owner.isFinishing()&&!owner.isDestroyed()&&(safOwnedInterval?stableOwner:current).getAsBoolean()&&account.equals(NativeFollowLists.currentAccountUid());}catch(RuntimeException unavailable){return false;}}
        boolean foreground(){try{return current.getAsBoolean();}catch(RuntimeException unavailable){return false;}}
        boolean owns(PlaybackSubtitleDocument expected,int token){return valid()&&epoch==token&&document==expected;}
        void readModels(){modelsRaw=settings.getString(AiModelSettings.KEY,"");try{models=AiModelSettings.parse(modelsRaw);selectedModel=models.activeId;}catch(RuntimeException failure){models=null;selectedModel="";}}
        String modelCaption(){if(models!=null)for(AiModelConfig config:models.configs)if(config.id.equals(selectedModel))return "模型："+config.name+" · "+config.modelName;return "选择已配置模型";}
        void render(){if(closed||body==null)return;
            List<MiuixPlaybackReading.Row> display=new ArrayList<>();int at=0;
            for(PlaybackReadingText.Line line:rows)display.add(new MiuixPlaybackReading.Row((++at)+" · "+PlaybackSubtitleDocument.clock(line.millis),line.text));
            List<MiuixPlaybackReading.Row> refs=new ArrayList<>();
            for(PlaybackSubtitleDocument.Cue cue:verified)refs.add(new MiuixPlaybackReading.Row("点击跳到 "+PlaybackSubtitleDocument.clock(cue.startMs),cue.text));
            body.render(status.isEmpty()?"共 "+rows.size()+" 条；选择字幕后才能导出和提问":status,display,document!=null,busy,modelCaption(),coverage,answer,refs,generation);
        }
        public void chapters(){cancel();generation++;document=null;rows=chapters;status="章节；不会作为字幕证据发送";render();}
        public void track(){selectTrack("");}
        void selectTrack(String action){
            if(!valid()){close();return;}if(tracks.isEmpty()){toast("当前作品未提供字幕轨；画面内文字不在读取范围内");return;}
            String[] names=new String[tracks.size()];for(int i=0;i<names.length;i++)names[i]=tracks.get(i).name;
            child=new MiuixDialogBuilder(owner,true).setTitle("明确选择字幕轨").setItems(names,(d,at)->{if(valid())load(tracks.get(at),action);}).setNegativeButton("取消",null).show();
        }
        void load(Track track,String action){
            cancel();generation++;document=null;rows=Collections.emptyList();
            if(!"webvtt".equals(track.format)&&!"vtt".equals(track.format)){status="暂不支持此字幕格式："+track.format;render();return;}
            if(!AutomationImages.isMediaUrl(track.url)){status="字幕地址暂不支持";render();return;}
            final int token=epoch;final int revision=generation;MediaReadCancellation reading=new MediaReadCancellation();cancellation=reading;
            busy=true;status="正在读取「"+track.name+"」…";render();
            submit(()->{try{
                List<PlaybackReadingText.Line> loaded=PlaybackReadingText.webvtt(fetch(track.url,reading));
                PlaybackSubtitleDocument snapshot=new PlaybackSubtitleDocument(account,aid,track.key,revision,loaded);
                main.post(()->{if(valid()&&epoch==token){rows=loaded;document=snapshot;busy=false;status="已读取「"+track.name+"」共 "+loaded.size()+" 句（2 MiB / 5000句上限）";render();if("export".equals(action))export();}});
            }catch(Exception failure){failed(token,failure,"字幕读取失败，请重新选择轨道重试");}});
        }
        public void row(int index){
            if(!valid()||!foreground()||index<0||index>=rows.size())return;PlaybackReadingText.Line row=rows.get(index);
            child=new MiuixDialogBuilder(owner,true).setTitle("字幕 / 章节原句").setMessage(row.text)
                .setItems(row.millis<0?new String[]{"复制原文"}:new String[]{"跳到这一段","复制原文"},(d,which)->{
                    if(!valid()||!foreground()){close();return;}if(which==0&&row.millis>=0){if(!seek.test(row.millis))toast("当前播放器不匹配，无法定位");}
                    else{ClipboardManager clipboard=(ClipboardManager)owner.getSystemService(Activity.CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("字幕原文",row.text));}
                }).setNegativeButton("取消",null).show();
        }
        public void model(){
            if(!valid())return;cancel();readModels();if(models==null||models.configs.isEmpty()){toast("请先在模块设置中配置模型");return;}
            final List<AiModelConfig> displayed=models.configs;final String displayedRaw=modelsRaw;
            String[] names=new String[displayed.size()];for(int i=0;i<names.length;i++)names[i]=displayed.get(i).name;
            child=new MiuixDialogBuilder(owner,true).setTitle("选择本次字幕提问模型").setItems(names,(d,index)->{if(!valid())return;if(!displayedRaw.equals(settings.getString(AiModelSettings.KEY,""))){cancel();readModels();status="模型配置已变化，请重新选择";render();return;}if(index<0||index>=displayed.size())return;cancel();selectedModel=displayed.get(index).id;render();}).setNegativeButton("取消",null).show();
        }
        public void ask(String question,String from,String to){
            if(!valid()||document==null||busy||question.trim().isEmpty())return;
            final PlaybackSubtitleAiSource source;final AiModelSettings chosen;
            try{source=PlaybackSubtitleAiSource.select(document,Integer.parseInt(from)-1,Integer.parseInt(to));
                if(models==null)throw new IllegalArgumentException("请先配置模型");chosen=models.activate(selectedModel);chosen.selected();
            }catch(RuntimeException failure){status=failure instanceof NumberFormatException?"请输入有效起止句号":failure.getMessage();render();return;}
            cancel();sent=source;coverage=source.coverage();busy=true;status="正在向所选模型发送上述字幕范围…";render();
            final int token=epoch;final String requestModelsRaw=modelsRaw,requestModelId=selectedModel;final ChatCompletionClient requesting=new ChatCompletionClient();client=requesting;
            submit(()->{try{
                if(!owns(source.document,token)||!requestModelsRaw.equals(settings.getString(AiModelSettings.KEY,"")))throw new CancellationException();
                String result=requesting.reply("subtitle-question",question,Collections.emptyList(),chosen,PlaybackSubtitleAiSource.INSTRUCTION,false,source.evidence);
                main.post(()->{if(owns(source.document,token)&&requestModelId.equals(selectedModel)&&requestModelsRaw.equals(settings.getString(AiModelSettings.KEY,""))){busy=false;acceptAnswer(source,result);status="回答已返回；仅点击通过核验的原句才会跳播";render();}});
            }catch(Exception failure){failed(token,failure,"字幕提问失败，请检查模型配置后重试");}});
        }
        void acceptAnswer(PlaybackSubtitleAiSource source,String result){
            verified=new ArrayList<>();answer=result;
            try{
                String json=result.trim();if(json.startsWith("```")){int first=json.indexOf('\n'),last=json.lastIndexOf("```");if(first>=0&&last>first)json=json.substring(first+1,last);}
                JSONObject parsed=new JSONObject(json);answer=parsed.optString("answer",result);JSONArray refs=parsed.optJSONArray("references");int invalid=0;
                if(refs!=null)for(int i=0;i<Math.min(50,refs.length());i++){
                    JSONObject ref=refs.optJSONObject(i);PlaybackSubtitleDocument.Cue cue=ref==null?null:PlaybackSubtitleAnswerValidator.resolve(source,ref.optString("cueId"),ref.optString("quote"),account,aid,document.track,document.generation);
                    if(cue==null)invalid++;else if(!verified.contains(cue))verified.add(cue);
                }
                if(invalid>0)answer+="\n\n"+invalid+" 条引用无法核验，未提供跳转。";
            }catch(JSONException failure){answer=result+"\n\n引用无法核验，未提供跳转。";}
        }
        public void citation(int index){
            if(!valid()||!foreground()||sent==null||document==null||index<0||index>=verified.size())return;
            if(!modelsRaw.equals(settings.getString(AiModelSettings.KEY,""))){cancel();readModels();render();return;}
            PlaybackSubtitleDocument.Cue ref=verified.get(index);
            if(sent.document!=document||PlaybackSubtitleAnswerValidator.resolve(sent,ref.id,ref.text,account,aid,document.track,document.generation)==null)return;
            if(!seek.test(ref.startMs))toast("当前播放器不匹配，无法定位");
        }
        public void export(){
            if(!valid()||document==null||busy)return;final PlaybackSubtitleDocument snapshot=document;
            child=new MiuixDialogBuilder(owner,true).setTitle("导出已读取字幕全文（"+snapshot.cues.size()+"句）")
                .setItems(new String[]{"保存 TXT 纯文本","保存 Markdown 时间码"},(d,index)->save(snapshot,index==1)).setNegativeButton("取消",null).show();
        }
        void save(PlaybackSubtitleDocument snapshot,boolean markdown){
            if(!valid()||document!=snapshot)return;cancel();final int token=epoch;busy=true;status="请选择系统保存位置（取消可返回）";render();
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(markdown?"text/markdown":"text/plain").putExtra(Intent.EXTRA_TITLE,PlaybackSubtitleExport.filename(snapshot,markdown)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            safOwnedInterval=true;safReturned=false;
            picker=HostConfigFilePicker.launch(owner,intent,uri->{
                safReturned=true;picker=null;
                if(uri==null){if(owns(snapshot,token)){busy=false;status="已取消导出";render();}return;}
                if(!owns(snapshot,token)){cleanup(uri);return;}
                status="正在写入完整字幕…";render();
                submit(()->{try{
                    byte[] bytes=PlaybackSubtitleExport.render(snapshot,markdown).getBytes(StandardCharsets.UTF_8);
                    if(!owns(snapshot,token)||Thread.currentThread().isInterrupted())throw new CancellationException();
                    try(OutputStream output=owner.getContentResolver().openOutputStream(uri,"wt")){
                        if(output==null)throw new IOException("无法写入所选文档");
                        for(int at=0;at<bytes.length;at+=8192){if(!owns(snapshot,token)||Thread.currentThread().isInterrupted())throw new CancellationException();output.write(bytes,at,Math.min(8192,bytes.length-at));}output.flush();
                    }
                    if(!owns(snapshot,token))throw new CancellationException();
                    main.post(()->{if(owns(snapshot,token)){busy=false;status="已保存完整字幕，可在其他页面继续阅读文件";render();if(foreground())share(uri,markdown);else{savedForShare=uri;savedMarkdown=markdown;}}else cleanup(uri);});
                }catch(Exception failure){cleanup(uri);failed(token,failure,"导出失败，已请求清理未完成文档");}});
            });
            if(picker==null&&!safReturned){safOwnedInterval=false;busy=false;status="系统文件选择器暂不可用";render();}
        }
        void share(Uri uri,boolean markdown){
            child=new MiuixDialogBuilder(owner,true).setTitle("字幕文件已保存").setPositiveButton("分享文件",(d,which)->{
                Intent send=new Intent(Intent.ACTION_SEND).setType(markdown?"text/markdown":"text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(ClipData.newUri(owner.getContentResolver(),"字幕全文",uri));
                try{owner.startActivity(Intent.createChooser(send,"分享字幕全文"));}catch(RuntimeException unavailable){toast("没有可用的分享应用；文件仍已保存");}
            }).setNegativeButton("完成",null).show();
        }
        void cleanup(Uri uri){if(uri==null)return;try{CLEANUP.execute(()->{try{DocumentsContract.deleteDocument(owner.getContentResolver(),uri);}catch(Exception ignored){}});}catch(RejectedExecutionException ignored){}}
        void failed(int token,Exception failure,String fallback){main.post(()->{if(valid()&&epoch==token){busy=false;status=failure instanceof IllegalArgumentException?failure.getMessage():fallback;render();}});}
        void submit(Runnable action){try{job=WORK.submit(action);}catch(RejectedExecutionException full){busy=false;status="字幕任务忙，请稍后重试";render();}}
        public void cancel(){epoch++;safOwnedInterval=false;safReturned=false;savedForShare=null;if(cancellation!=null)cancellation.cancel();if(client!=null)client.cancelCurrentRequest();if(job!=null)job.cancel(true);if(picker!=null){picker.abandon(this::cleanup);picker=null;}WORK.purge();busy=false;sent=null;verified=new ArrayList<>();answer="";coverage="";status="已取消当前操作";render();}
        void close(){if(closed)return;closed=true;cancel();main.removeCallbacks(watch);if(child!=null&&child.isShowing())child.dismiss();if(dialog!=null&&dialog.isShowing())dialog.dismiss();if(body!=null)body.close();if(active.get()==this)active.clear();}
        void toast(String value){Toast.makeText(owner,value,Toast.LENGTH_SHORT).show();}
    }
    static String fetch(String url, MediaReadCancellation cancellation) throws Exception {
        cancellation.start(); String current = url;
        for(int hop=0;hop<4;hop++) {
            if(!AutomationImages.isMediaUrl(current)) throw new IllegalArgumentException("字幕地址不支持");
            HttpURLConnection connection = cancellation.open(URI.create(current));
            try {
                int code = connection.getResponseCode(); cancellation.check();
                if(code >= 300 && code < 400) {
                    String next = connection.getHeaderField("Location"); if(next == null) throw new IllegalArgumentException("无效跳转");
                    current = URI.create(current).resolve(next).toString(); continue;
                }
                if(code == 401 || code == 403 || code == 410) throw new IllegalArgumentException("字幕地址已不可用，请重新打开作品获取字幕");
                if(code != 200) throw new java.io.IOException("字幕请求失败");
                if(connection.getContentLengthLong() > PlaybackReadingText.MAX_CHARS) throw new IllegalArgumentException("字幕超过 2 MiB 读取上限");
                try(InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while((n=in.read(buffer)) != -1) { cancellation.check(); if(out.size()+n > PlaybackReadingText.MAX_CHARS) throw new IllegalArgumentException("字幕太大"); out.write(buffer,0,n); }
                    cancellation.check(); return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally { cancellation.close(connection); }
        }
        throw new IllegalArgumentException("跳转过多");
    }
    private static String text(Object object,String field) { Object v=DownloadActionHook.jsonValue(object,field); return v instanceof String ? ((String)v).trim() : ""; }
}
