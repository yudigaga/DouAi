package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import com.douai.module.ui.miuix.MiuixCommentSearch;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** 41.0.0 only. Reads the rows supplied to each panel's renderer, never a network response. */
public final class LoadedCommentSearchHook implements AutoCloseable {
    private static final String C="com.ss.android.ugc.aweme.comment.";
    private static final String K="com.douyin.kmp.comment.comment.commentpanellist.list.";
    private static volatile LoadedCommentSearchHook active;
    private final List<XposedInterface.HookHandle> handles=new ArrayList<>();
    private final LoadedCommentIndex index=new LoadedCommentIndex();
    private final Handler main=new Handler(Looper.getMainLooper());
    interface Types { Class<?> get(String name) throws ClassNotFoundException; }
    private final Types types;
    private final Method service, uid, extract;
    private final Field cid, aid, body, parent, root;
    private volatile boolean enabled,transcripts;
    private WeakReference<Object> panel=new WeakReference<>(null);
    private Panel current;
    private String account="",work="";
    private boolean pending,reset;
    private boolean emptyRefresh;
    private final CommentSearchRequest requests=new CommentSearchRequest();
    private Runnable awaiting;
    private AlertDialog dialog;
    private Runnable redraw;
    private MiuixCommentSearch search;
    private long loadDeadline;
    private boolean pageTimedOut;
    private long pageRequest,renderedRequest;
    private int sampledAdapterCount=-1;
    private PageState renderedPage;
    private final Runnable drain=() -> { synchronized(this){
        pending=false;
        if(enabled && sample(reset) && redraw!=null)redraw.run();
        reset=false;
    } };
    private final Runnable checkDialog=new Runnable(){public void run(){synchronized(LoadedCommentSearchHook.this){
        if(dialog==null)return;
        try {
            if(!valid()){clear();return;}
            List<?> displayed=current.displayed(panel.get());
            boolean changed=displayed!=null && displayed.size()!=sampledAdapterCount;
            // A result callback may precede the renderer's actual publication. Compare size cheaply;
            // unchanged lists are not reflected over on every tick, including during slow page loads.
            if(changed)sample(false);
            if(loadDeadline!=0){
                PageState state=lastPageState();
                if(state==PageState.END || (current.kmp && renderedRequest==pageRequest && state==PageState.MORE)){
                    loadDeadline=0;pageTimedOut=false;changed=true;
                }else if(SystemClock.elapsedRealtime()>=loadDeadline){loadDeadline=0;pageTimedOut=true;changed=true;}
            }
            if(redraw!=null && (changed || renderedPage!=lastPageState()))redraw.run();
        } catch(Exception | LinkageError unavailable){clear();return;}
        main.postDelayed(this,400);
    }}};

    public static LoadedCommentSearchHook install(XposedInterface framework,ClassLoader loader,Consumer<String> log) throws Exception {
        return install(framework,name->Class.forName(name,false,loader),log);
    }
    static LoadedCommentSearchHook install(XposedInterface framework,Types types,Consumer<String> log) throws Exception {
        LoadedCommentSearchHook hook=new LoadedCommentSearchHook(types);
        for(boolean kmp:new boolean[]{false,true}) {
            int start=hook.handles.size();
            try { hook.new Panel(kmp).install(framework); }
            catch(Exception | LinkageError failure) {
                while(hook.handles.size()>start) hook.handles.remove(hook.handles.size()-1).unhook();
                if(log!=null)log.accept("Comment reading "+(kmp?"KMP":"native")+" unavailable: "+failure.getClass().getSimpleName());
            }
        }
        if(hook.handles.isEmpty()) throw new IllegalStateException("No compatible comment panels");
        active=hook; return hook;
    }
    private LoadedCommentSearchHook(Types types) throws Exception {
        this.types=types;
        Class<?> comment=type(C+"model.Comment");
        extract=method(type("X.13Tl"),"getComment",comment);
        cid=field(comment,"cid",String.class); aid=field(comment,"awemeId",String.class);
        parent=field(comment,"replyId",String.class); root=field(comment,"rootCommentId",String.class);
        body=field(comment,"text",String.class);
        service=type("com.ss.android.ugc.aweme.account.AccountProxyService").getDeclaredMethod("userService");
        if(!Modifier.isStatic(service.getModifiers()))throw new NoSuchMethodException("userService");
        uid=service.getReturnType().getMethod("getCurUserId");
        if(uid.getReturnType()!=String.class) throw new NoSuchMethodException("getCurUserId");
    }
    public synchronized void setOptions(boolean searchEnabled,boolean transcriptEnabled) {
        if(enabled && !searchEnabled){cancelRequest();clear();}
        else if(enabled && transcripts!=transcriptEnabled){
            search=null;loadDeadline=0;pageTimedOut=false;
            index.open(account,work);
            if(dialog!=null){AlertDialog d=dialog;dialog=null;main.post(d::dismiss);}
            redraw=null;main.removeCallbacks(checkDialog);
            schedule(true);
        }
        enabled=searchEnabled;transcripts=transcriptEnabled;
    }
    private Class<?> type(String name)throws Exception{return types.get(name);}
    private static Method method(Class<?> c,String name,Class<?> result,Class<?>...args)throws Exception{
        Method m=c.getDeclaredMethod(name,args);
        if(m.getReturnType()!=result || Modifier.isStatic(m.getModifiers()))throw new NoSuchMethodException(name);
        m.setAccessible(true);return m;
    }
    private static Field field(Class<?> c,String name,Class<?> kind)throws Exception {
        Field f=c.getDeclaredField(name);
        if(f.getType()!=kind || Modifier.isStatic(f.getModifiers()))throw new NoSuchFieldException(name);
        f.setAccessible(true);return f;
    }
    private interface After {void run(Object receiver,List<Object> args)throws Exception;}
    private void after(XposedInterface api,Method m,After callback) {
        XposedInterface.HookHandle h=api.hook(m).setId("DouAi.ReadComments."+m.getDeclaringClass().getName()+"."+m.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain->{
                    Object result=chain.proceed();
                    if(enabled && Looper.myLooper()==Looper.getMainLooper()) {
                        try {synchronized(this){if(enabled)callback.run(chain.getThisObject(),chain.getArgs());}}
                        catch(Exception | LinkageError ignored){}
                    }
                    return result;
                });
        if(h==null)throw new IllegalStateException("Missing comment hook"); handles.add(h);
    }
    private String owner() throws Exception { return (String)uid.invoke(service.invoke(null)); }
    private void clear() {
        search=null;loadDeadline=0;pageTimedOut=false;sampledAdapterCount=-1;renderedPage=null;
        if(requests.bound(panel.get()))cancelRequest();
        emptyRefresh=false;
        main.removeCallbacks(drain);pending=false;reset=false;index.close();
        redraw=null;main.removeCallbacks(checkDialog);
        current=null;panel.clear();account="";work="";
        if(dialog!=null) {AlertDialog d=dialog;dialog=null;main.post(d::dismiss);}
    }
    private void schedule(boolean replace) {
        reset|=replace;
        if(!pending){pending=true;main.post(drain);}
    }
    private enum PageState { MORE, BUSY, END, UNAVAILABLE }
    private PageState lastPageState(){
        try{return current==null||panel.get()==null?PageState.UNAVAILABLE:current.page(panel.get(),false);}
        catch(Exception|LinkageError unavailable){return PageState.UNAVAILABLE;}
    }
    /** Work arriving on host workers can invalidate presentation. Never retain/merge response payloads;
     * recheck the captured fragment, generation, account and work on the main thread instead. */
    private void afterRender(XposedInterface api,Method m,After callback) {
        afterRender(api,m,null,callback);
    }
    private void afterRender(XposedInterface api,Method m,Function<Object,Boolean> emptyResult,After callback) {
        XposedInterface.HookHandle h=api.hook(m).setId("DouAi.ReadComments."+m.getDeclaringClass().getName()+"."+m.getName())
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain->{
                    if(!enabled)return chain.proceed();
                    long token,request;synchronized(this){token=index.token();request=loadDeadline==0?0:pageRequest;}
                    Object result=chain.proceed();
                    if(enabled){
                        Object receiver=chain.getThisObject();
                        Boolean empty=emptyResult==null?null:emptyResult.apply(chain.getArg(0));
                        Runnable read=()->{synchronized(this){try{
                            if(enabled && token==index.token() && valid())callback.run(receiver,Arrays.asList(empty,request));
                        }catch(Exception|LinkageError ignored){}}};
                        if(Looper.myLooper()==Looper.getMainLooper())read.run();else main.post(read);
                    }
                    return result;
                });
        if(h==null)throw new IllegalStateException("Missing comment render hook");handles.add(h);
    }
    private final class Panel {
        final boolean kmp;
        final Class<?> fragment;
        final Method id,visible;
        Method recycler,adapter,rows,scroll,vmRows,vmState,lazyReady,lazyValue;
        Field bridge,delegate,slot,vm,manager,controller,eventManager,nativeFragment,folded,hidden;
        Class<?> refreshEvent;
        Class<?> fetchType,fetchAbility;
        Method abilityForFragment,fetchNext,kmpHasMore,kmpNext,kmpValue;
        Class<?> kList,kProxy;
        Panel(boolean k)throws Exception {
            kmp=k; fragment=type(C+(k?"commentlist.kmp.KmpCommentListFragment":"ui.CommentListFragment"));
            id=method(fragment,k?"zM":"getAwemeId",String.class);
            visible=method(fragment,"isShowing",boolean.class);
        }
        void install(XposedInterface api)throws Exception {
            if(kmp){
                Class<?> b=type("X.0MbR"),d=type("X.0MbU"),lazy=type("kotlin.Lazy"),pvm=type(K+"KCmtListPanelViewModel"),
                        lvm=type(K+"KCmtListViewModel"),mgr=type("X.0Mff"),ctrl=type("X.0Mey"),handler=type("X.0Mh4");
                bridge=field(fragment,"U",b);delegate=field(b,"LIZJ",type("X.0Mbd"));slot=field(d,"LIZIZ",lazy);
                lazyReady=method(lazy,"isInitialized",boolean.class);lazyValue=method(lazy,"getValue",Object.class);
                vm=field(pvm,"l",lvm);manager=field(lvm,"M",mgr);controller=field(mgr,"LIZJ",type("X.0MAN"));
                Class<?> cell=type("X.0MXK");folded=field(cell,"u",boolean.class);hidden=field(cell,"z",boolean.class);
                eventManager=field(handler,"LIZIZ",type("X.0Mbe"));refreshEvent=type("X.0MhE");
                vmRows=method(mgr,"LIZLLL",List.class);
                Class<?> state=type("androidx.compose.foundation.lazy.LazyListState");
                vmState=method(lvm,"YD",state);scroll=method(state,"LIZLLL",void.class,int.class,int.class);
                try{
                    Class<?> pages=type("X.0MZo");
                    kList=type("com.bytedance.kmp.klist.KListViewModel");kProxy=type("X.0MZn");
                    kmpHasMore=pages.getMethod("hasMore");
                    kmpValue=type("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
                    Method next=pages.getMethod("Wl");
                    if(kmpHasMore.getReturnType()==boolean.class && next.getReturnType()==void.class)kmpNext=next;
                }catch(Exception|LinkageError missing){kmpNext=null;}
                afterRender(api,method(ctrl,"LJIIJJI",void.class,List.class),(o,a)->{
                    Object f=panel.get(); if(current!=this || f==null)return;
                    Object mgrNow=manager(f);if(mgrNow!=null && controller.get(mgrNow)==o){
                        if(loadDeadline!=0 && Long.valueOf(pageRequest).equals(a.get(1))){
                            renderedRequest=pageRequest;
                            PageState completedPage=page(f,false);
                            if(completedPage==PageState.MORE || completedPage==PageState.END){loadDeadline=0;pageTimedOut=false;}
                        }
                        schedule(false);
                    }
                });
                afterRender(api,method(handler,"LIZ",void.class,type("X.0Lzw")),event->{
                    if(!refreshEvent.isInstance(event))return null;
                    Object page=HostTargets.read(event,"LIZIZ"), items=HostTargets.read(page,"items");
                    return Long.valueOf(0L).equals(HostTargets.read(page,"total"))
                            && (items==null || items instanceof List<?> && ((List<?>)items).isEmpty());
                },(o,a)->{
                    Object f=panel.get();if(current==this && f!=null && a.get(0)!=null && eventManager.get(o)==manager(f)){
                        // The old list may remain visible until its replacement is delivered.
                        index.refresh(index.token());reset=true;
                        loadDeadline=0;pageTimedOut=false;emptyRefresh=Boolean.TRUE.equals(a.get(0));
                    }
                });
            } else {
                Class<?> rv=type("androidx.recyclerview.widget.RecyclerView"),ad=type(C+"adapter.CommentAdapter");
                recycler=method(fragment,"LLLILZLLLI",rv);adapter=method(rv,"getAdapter",type("androidx.recyclerview.widget.RecyclerView$Adapter"));
                rows=method(ad,"getData",List.class);scroll=method(rv,"scrollToPosition",void.class,int.class);
                Class<?> m=type(C+"commentlist.listcontainer.commentlist.refactor.CommentListRecyclerUiModule");
                nativeFragment=field(m,"g",type("androidx.fragment.app.Fragment"));
                afterRender(api,method(m,"LJI",void.class,type("X.13RB")),(o,a)->{
                    if(current==this && nativeFragment.get(o)==panel.get())schedule(false);
                });
                afterRender(api,method(m,"LJJJJZI",void.class,List.class,boolean.class),
                        rows->rows instanceof List<?> && ((List<?>)rows).isEmpty(),(o,a)->{
                    if(current==this && nativeFragment.get(o)==panel.get()){
                        emptyRefresh=Boolean.TRUE.equals(a.get(0));
                        loadDeadline=0;pageTimedOut=false;
                        schedule(true);
                    }
                });
                afterRender(api,method(m,"LJJJJLI",void.class,List.class,boolean.class),(o,a)->{
                    if(current==this && nativeFragment.get(o)==panel.get()){
                        // This is the host's onLoadMoreResult, including all-overlap/empty pages.
                        if(loadDeadline!=0 && Long.valueOf(pageRequest).equals(a.get(1))){loadDeadline=0;pageTimedOut=false;}
                        schedule(false);
                    }
                });
                try{
                    fetchType=type(C+"commentlist.listcontainer.commentlist.CommentListDataFetchHelper");
                    fetchAbility=type(C+"ui.viewmodels.IFetchViewModelAbility");
                    Method reg=type("X.11gi").getDeclaredMethod("LIZ",Class.class,type("androidx.lifecycle.LifecycleOwner"));
                    if(!Modifier.isStatic(reg.getModifiers()) || reg.getReturnType()!=type("com.ss.android.ugc.aweme.arch.ability.ICmtAbility"))
                        throw new NoSuchMethodException("comment ability registry");
                    fetchNext=method(fetchType,"loadMore",void.class);
                    abilityForFragment=reg;
                }catch(Exception|LinkageError missing){abilityForFragment=null;}
            }
            after(api,method(fragment,"onShow",void.class),(f,a)->enter(f));
            after(api,method(fragment,"onResume",void.class),(f,a)->{if(Boolean.TRUE.equals(visible.invoke(f))) enter(f);});
            after(api,method(fragment,"pk",void.class,type("X.11ue")),(f,a)->{
                if(panel.get()==f && !work.equals(id.invoke(f))){clear();if(Boolean.TRUE.equals(visible.invoke(f))) enter(f);}
            });
            After end=(f,a)->{if(panel.get()==f)clear();};
            after(api,method(fragment,"onDismiss",void.class,String.class),end);
            after(api,method(fragment,"onDestroyView",void.class),end);
            after(api,method(fragment,"onPause",void.class),end);
        }
        void enter(Object f)throws Exception {
            String a=owner(), w=(String)id.invoke(f);
            if(!LoadedCommentIndex.id(a)||!LoadedCommentIndex.id(w)){clear();return;}
            if(panel.get()!=f || !account.equals(a)||!work.equals(w)) {
                clear();panel=new WeakReference<>(f);current=this;account=a;work=w;index.open(a,w);
            }
            if(awaiting!=null) requests.bind(a,w,HostTargets.invoke(f,"getActivity"),f);
            schedule(false);
        }
        Object listVm(Object f)throws Exception {
            Object b=bridge.get(f); if(b==null)return null;
            Object d=delegate.get(b); if(!slot.getDeclaringClass().isInstance(d))return null;
            Object l=slot.get(d); if(l==null || !Boolean.TRUE.equals(lazyReady.invoke(l)))return null;
            Object p=lazyValue.invoke(l);return vm.getDeclaringClass().isInstance(p)?vm.get(p):null;
        }
        Object manager(Object f)throws Exception {Object value=listVm(f);return value==null?null:manager.get(value);}
        PageState page(Object f,boolean invoke)throws Exception{
            if(kmp){
                if(kmpNext==null)return PageState.UNAVAILABLE;
                Object entry=HostTargets.read(listVm(f),"g");
                Object list=kProxy.isInstance(entry)?HostTargets.read(entry,"b"):entry;
                if(!kList.isInstance(list) || !kmpNext.getDeclaringClass().isInstance(entry))return PageState.UNAVAILABLE;
                Object flow=HostTargets.read(list,"f");
                if(!kmpValue.getDeclaringClass().isInstance(flow))return PageState.UNAVAILABLE;
                Object phase=HostTargets.read(kmpValue.invoke(flow),"LJ");
                if(!(phase instanceof Enum<?>))return PageState.UNAVAILABLE;
                if(!"IDLE".equals(((Enum<?>)phase).name()))return PageState.BUSY;
                if(!Boolean.TRUE.equals(kmpHasMore.invoke(entry)))return PageState.END;
                if(invoke)kmpNext.invoke(entry);
                return PageState.MORE;
            }
            if(abilityForFragment==null)return PageState.UNAVAILABLE;
            Object helper=abilityForFragment.invoke(null,fetchAbility,f);
            if(!fetchType.isInstance(helper)||HostTargets.read(helper,"b")!=f || !work.equals(HostTargets.invoke(helper,"LJIIIZ")))
                return PageState.UNAVAILABLE;
            Object rv=recycler.invoke(f);Object ad=rv==null?null:adapter.invoke(rv);
            if(!rows.getDeclaringClass().isInstance(ad)||!Boolean.FALSE.equals(HostTargets.invoke(ad,"LJZ")))return PageState.UNAVAILABLE;
            Object request=HostTargets.read(HostTargets.invoke(helper,"LJIIJJI"),"LIZ");
            Object busy=HostTargets.invoke(request,"LJIIIZ");
            Object more=HostTargets.read(HostTargets.invoke(helper,"getData"),"hasMore");
            if(!(busy instanceof Boolean)||!(more instanceof Boolean))return PageState.UNAVAILABLE;
            if((Boolean)busy)return PageState.BUSY;
            if(!(Boolean)more)return PageState.END;
            if(invoke)fetchNext.invoke(helper);
            return PageState.MORE;
        }
        List<?> displayed(Object f)throws Exception{
            if(kmp){Object m=manager(f);return m==null?null:(List<?>)vmRows.invoke(m);}
            Object rv=recycler.invoke(f); Object ad=rv==null?null:adapter.invoke(rv);
            return rows.getDeclaringClass().isInstance(ad)?(List<?>)rows.invoke(ad):null;
        }
        boolean jump(Object f,int pos)throws Exception {
            Object target;
            if(kmp){Object value=listVm(f); target=value==null?null:vmState.invoke(value);}
            else target=recycler.invoke(f);
            if(target==null)return false;
            if(kmp)scroll.invoke(target,pos,0);else scroll.invoke(target,pos);
            return true;
        }
    }
    private boolean valid() throws Exception {
        Object f=panel.get();return enabled && f!=null && current!=null && work.equals(current.id.invoke(f))
                && account.equals(owner()) && Boolean.TRUE.equals(current.visible.invoke(f));
    }
    private boolean sample(boolean replace) {
        try {
            if(!valid()) {
                if(current!=null && panel.get()!=null && (!account.equals(owner()) || !work.equals(current.id.invoke(panel.get()))))clear();
                return false;
            }
            List<?> list=current.displayed(panel.get());if(list==null)return false;
            sampledAdapterCount=list.size();
            List<LoadedCommentIndex.Row> result=new ArrayList<>();
            boolean partial=list.size()>LoadedCommentIndex.SCAN_LIMIT;
            for(int i=0,n=Math.min(list.size(),LoadedCommentIndex.SCAN_LIMIT);i<n;i++){
                Object item=list.get(i);if(!extract.getDeclaringClass().isInstance(item))continue;
                if(current.kmp && current.hidden.getDeclaringClass().isInstance(item)
                        && (current.hidden.getBoolean(item) || current.folded.getBoolean(item)))continue;
                Object c=extract.invoke(item); if(c==null || !work.equals(aid.get(c)))continue;
                String ident=(String)cid.get(c);if(!LoadedCommentIndex.id(ident))continue;
                String parentId=(String)root.get(c);if(!LoadedCommentIndex.id(parentId))parentId=(String)parent.get(c);
                if(!LoadedCommentIndex.id(parentId)||ident.equals(parentId))parentId="";
                String txt=(String)body.get(c), speech=transcripts?CommentTranscript.read(c):null;
                partial|=(txt!=null && txt.length()>LoadedCommentIndex.TEXT_LIMIT)||(speech!=null && speech.length()>LoadedCommentIndex.TEXT_LIMIT);
                result.add(new LoadedCommentIndex.Row(ident,parentId,txt,speech,i));
                if(result.size()>LoadedCommentIndex.LIMIT){partial=true;break;}
            }
            if(replace)index.refresh(index.token());
            index.update(index.token(),result,partial);
            if(!result.isEmpty())emptyRefresh=false;
            return true;
        }catch(Exception | LinkageError ignored){return false;}
    }
    public static void show(Context context) {
        LoadedCommentSearchHook hook=active;
        if(hook==null){toast(context,"当前版本没有可用的评论面板");return;}
        synchronized(hook){hook.cancelRequest();hook.open(context);}
    }

    /** Opens the existing comment button once, then searches only this screen/account/work's rendered rows. */
    public static void request(Context context,String awemeId,BooleanSupplier isCurrent,BooleanSupplier openComments) {
        if(context==null)return;
        LoadedCommentSearchHook hook=active;
        if(hook==null){toast(context,"当前版本没有可用的评论面板");return;}
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            hook.main.post(()->{if(active==hook)hook.begin(context,awemeId,isCurrent,openComments);});
        } else hook.begin(context,awemeId,isCurrent,openComments);
    }
    private synchronized void begin(Context context,String awemeId,BooleanSupplier isCurrent,BooleanSupplier opener) {
        if(active!=this)return;
        Activity screen=activity(context);
        if(!enabled){cancelRequest();toast(context,"请先开启已加载评论查找");return;}
        if(screen==null || !alive(screen) || !LoadedCommentIndex.id(awemeId) || isCurrent==null || opener==null){
            cancelRequest();toast(context,"当前页面无法查找作品评论");return;
        }
        try {
            String uid=owner();
            if(!LoadedCommentIndex.id(uid)){cancelRequest();toast(context,"无法确认当前抖音账号");return;}
            if(!isCurrent.getAsBoolean()){cancelRequest();toast(context,"作品已切换，请重试");return;}
            if(awaiting!=null)main.removeCallbacks(awaiting);
            CommentSearchRequest.Ticket r=requests.begin(uid,awemeId,screen,SystemClock.elapsedRealtime());
            awaiting=()->checkRequest(r,screen,isCurrent,opener);
            awaiting.run();
        } catch(Exception | LinkageError failure) {cancelRequest();toast(context,"当前评论面板暂不可用");}
    }
    private synchronized void checkRequest(CommentSearchRequest.Ticket r,Activity screen,
                                           BooleanSupplier isCurrent,BooleanSupplier opener) {
        if(!requests.pending(r))return;
        try {
            String uid=owner();
            boolean sourceCurrent=enabled && active==this && alive(screen) && isCurrent.getAsBoolean();
            Object f=panel.get();
            if(!sourceCurrent || !r.account.equals(uid) || current==null || !r.work.equals(work)
                    || !uid.equals(account) || f==null || HostTargets.invoke(f,"getActivity")!=screen
                    || !work.equals(current.id.invoke(f))) f=null;
            boolean visible=f!=null && Boolean.TRUE.equals(current.visible.invoke(f));
            boolean ready=visible && sample(reset) && (index.size()>0 || emptyRefresh);
            // onShow can precede isShowing. If this request has seen onShow, never click the opener again.
            Object observed=visible || requests.bound(f) ? f : null;
            switch(requests.step(r,uid,sourceCurrent,SystemClock.elapsedRealtime(),observed,ready)) {
                case OPEN_COMMENTS:
                    boolean accepted=opener.getAsBoolean();
                    if(!requests.pending(r))return;
                    requests.opened(r,accepted);
                    if(!accepted){cancelRequest();toast(screen,"当前作品没有可用的评论入口，请重试");return;}
                    break;
                case WAIT: break;
                case SHOW:
                    cancelRequest();
                    if(dialog==null || !dialog.isShowing())openReady(screen);
                    return;
                case TIMEOUT:
                    cancelRequest();toast(screen,"评论尚未加载或该面板不支持查找，请稍后重试");return;
                case CANCEL: cancelRequest();return;
                default: return;
            }
            if(requests.pending(r))main.postDelayed(awaiting,120);
        } catch(Exception | LinkageError failure) {
            if(requests.pending(r)){cancelRequest();if(alive(screen))toast(screen,"未能打开评论查找，请重试");}
        }
    }
    private void cancelRequest(){
        requests.cancel();if(awaiting!=null)main.removeCallbacks(awaiting);awaiting=null;
    }
    private static boolean alive(Activity a){return !a.isFinishing()&&!a.isDestroyed();}
    private static Activity activity(Context ctx){
        for(int i=0;ctx instanceof ContextWrapper && i<24;i++){
            if(ctx instanceof Activity)return (Activity)ctx;
            ctx=((ContextWrapper)ctx).getBaseContext();
        }
        return null;
    }
    static boolean available(Object comment) {
        LoadedCommentSearchHook hook=active;
        if(hook==null || !hook.enabled || comment==null)return false;
        synchronized(hook){
            try {return hook.aid.getDeclaringClass().isInstance(comment) && hook.work.equals(hook.aid.get(comment)) && hook.valid();}
            catch(Exception | LinkageError unavailable){return false;}
        }
    }
    private void open(Context ctx) {
        if(!enabled){toast(ctx,"请先开启已加载评论查找");return;}
        if(!sample(reset)){toast(ctx,"请先打开当前作品的评论面板，等待评论加载后重试");return;}
        openReady(ctx);
    }
    private void openReady(Context ctx) {
        if(dialog!=null)dialog.dismiss();
        long token=index.token();
        MiuixCommentSearch view=new MiuixCommentSearch(ctx,q->{synchronized(this){
            if(token==index.token() && redraw!=null)redraw.run();}},
            id->{synchronized(this){if(token==index.token()&&dialog!=null)locate(ctx,token,id,dialog);}},
            ()->loadPage(token));
        view.setTranscriptSearch(transcripts);
        AlertDialog d=new MiuixDialogBuilder(ctx,true).setTitle("查找评论").setView(view.getView()).setNegativeButton("关闭",null).create();
        d.setOnDismissListener(v->{synchronized(this){
            view.close();
            if(dialog==d){dialog=null;search=null;redraw=null;loadDeadline=0;pageTimedOut=false;main.removeCallbacks(checkDialog);}
        }});
        dialog=d;search=view;loadDeadline=0;pageTimedOut=false;
        // The host worker can replace this panel while its render callback or a Compose input callback is queued.
        redraw=()->{synchronized(this){
            if(token!=index.token()||search!=view||dialog!=d)return;
            String q=view.query().trim();
            List<LoadedCommentIndex.Row> matches=index.find(q);
            ArrayList<MiuixCommentSearch.Entry> entries=new ArrayList<>(matches.size());
            for(LoadedCommentIndex.Row row:matches) entries.add(new MiuixCommentSearch.Entry(row.id,
                    row.root.isEmpty()?"评论":row.position<0?"楼中楼回复（已收起）":"楼中楼回复",row.match(q)));
            renderedPage=lastPageState();
            boolean capped=index.size()>=LoadedCommentIndex.LIMIT || sampledAdapterCount>=LoadedCommentIndex.SCAN_LIMIT;
            boolean busy=loadDeadline!=0||renderedPage==PageState.BUSY;
            String caption=capped?"已达查找上限":busy?"正在加载…":renderedPage==PageState.MORE?
                    (pageTimedOut?"未收到新评论，重试":"加载更多评论"):renderedPage==PageState.END?"没有更多评论":"此面板无法继续加载";
            view.render(String.format(Locale.ROOT,"已查 %d 条已加载评论，命中 %d 条%s",index.size(),matches.size(),
                    index.partial()?"（已达保留上限，部分未纳入）":""),entries,
                    !capped&&!busy&&renderedPage==PageState.MORE,busy,caption);
        }};
        redraw.run();d.show();main.removeCallbacks(checkDialog);main.postDelayed(checkDialog,400);
    }
    private synchronized void loadPage(long token){
        if(token!=index.token()||dialog==null||!dialog.isShowing()||loadDeadline!=0)return;
        try{
            if(!valid()){clear();return;}
            if(index.size()>=LoadedCommentIndex.LIMIT||sampledAdapterCount>=LoadedCommentIndex.SCAN_LIMIT)return;
            pageRequest++;renderedRequest=0;loadDeadline=SystemClock.elapsedRealtime()+10_000;pageTimedOut=false;
            if(current.page(panel.get(),true)!=PageState.MORE)loadDeadline=0;
        }catch(Exception | LinkageError failure){loadDeadline=0;pageTimedOut=true;}
        if(redraw!=null)redraw.run();
    }
    private synchronized void locate(Context ctx,long token,String wanted,AlertDialog d){
        try{
            if(index.token()!=token || !sample(reset)){d.dismiss();toast(ctx,"评论面板已变化，请重新查找");return;}
            for(LoadedCommentIndex.Row r:index.find("")) if(r.id.equals(wanted)){
                if(r.position>=0 && current.jump(panel.get(),r.position)){d.dismiss();return;}
                Uri uri=new Uri.Builder().scheme("aweme").authority("aweme").appendPath("detail").appendPath(work).appendQueryParameter("cid",wanted).build();
                d.dismiss();ctx.startActivity(new Intent(Intent.ACTION_VIEW,uri).setPackage("com.ss.android.ugc.aweme"));
                toast(ctx,"已请求打开评论；若只显示作品或根评论，请手动展开");return;
            }
            toast(ctx,"这条评论已不在当前结果中");
        }catch(Exception | LinkageError ignored){toast(ctx,"暂时无法定位这条评论");}
    }
    private static void toast(Context ctx,String s){Toast.makeText(ctx,s,Toast.LENGTH_SHORT).show();}
    @Override public synchronized void close(){
        enabled=false;cancelRequest();clear();if(active==this)active=null;
        for(XposedInterface.HookHandle h:handles)try{h.unhook();}catch(RuntimeException ignored){}
        handles.clear();
    }
}
