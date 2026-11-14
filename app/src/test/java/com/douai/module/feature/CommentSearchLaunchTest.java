package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Exercise the production request API through registered native panel callbacks and an Android dialog. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35) @LooperMode(LooperMode.Mode.PAUSED)
public class CommentSearchLaunchTest {
    public static final class Account {
        public static Account userService(){return new Account();}
        public String getCurUserId(){return "11";}
    }
    public static final class Comment {
        public String cid="1",awemeId="22",text="needle",replyId,rootCommentId;
    }
    public static class Cell {
        final Comment comment=new Comment();
        Cell(){} Cell(int cid,String text){comment.cid=""+cid;comment.text=text;}
        public Comment getComment(){return comment;}
    }
    public static class Adapter {}
    public static final class Rows extends Adapter {
        final List<Object> data=new ArrayList<>();
        public List<?> getData(){return data;}
        public boolean LJZ(){return false;}
    }
    public static final class Recycler {
        final Rows rows=new Rows();
        public Adapter getAdapter(){return rows;}
        public void scrollToPosition(int position){}
    }
    public static class Fragment {}
    public static final class NativePanel extends Fragment {
        final Activity owner; final Recycler recycler=new Recycler();
        String aid="22";
        NativePanel(Activity owner){this.owner=owner;}
        public Activity getActivity(){return owner;}
        public String getAwemeId(){return aid;}
        public boolean isShowing(){return true;}
        public Recycler LLLILZLLLI(){return recycler;}
        public void onShow(){} public void onResume(){} public void onPause(){}
        public void pk(Event ignored){} public void onDismiss(String reason){} public void onDestroyView(){}
    }
    public static final class Event {}
    public static final class Module {
        public Fragment g;
        public void LJI(Event event){} public void LJJJJZI(List<?> data,boolean mode){}
        public void LJJJJLI(List<?> data,boolean more){}
    }
    public static class FetchAbility {}
    public static final class Registry {
        static Fetch fetch;
        public static Object LIZ(Class<?> ability,Fragment fragment){return fetch;}
    }
    public static final class Page { public boolean hasMore=true; }
    public static final class Request { public boolean LJIIIZ(){return false;} }
    public static final class State { public Request LIZ=new Request(); }
    public static final class Fetch extends FetchAbility {
        public Fragment b; final State state=new State(); final Page page=new Page(); int loads;
        public String LJIIIZ(){return "22";} public State LJIIJJI(){return state;}
        public Page getData(){return page;} public void loadMore(){loads++;}
    }
    public static final class KCell extends Cell {public boolean u,z; KCell(int id){super(id,"row "+id);} }
    public static final class KPanel extends Fragment {
        final Activity owner; final KBridge U=new KBridge();
        KPanel(Activity owner){this.owner=owner;}
        public String zM(){return "22";}public boolean isShowing(){return true;}public Activity getActivity(){return owner;}
        public void onShow(){}public void onResume(){}public void pk(Event e){}public void onPause(){}
        public void onDismiss(String why){}public void onDestroyView(){}
    }
    public static class KDelegateBase {}
    public static final class KBridge { public KDelegateBase LIZJ=new KDelegate(); }
    public static final class KDelegate extends KDelegateBase {public KLazy LIZIZ=new KLazy();}
    public static final class KLazy {
        final KPanelVm value=new KPanelVm();public boolean isInitialized(){return true;}public Object getValue(){return value;}
    }
    public static final class KPanelVm{public KVm l=new KVm();}
    public static final class Scroll {public void LIZLLL(int position,int offset){}}
    public static final class KVm{public KManager M=new KManager();public Pages g=new PageProxy();public Scroll YD(){return new Scroll();}}
    public interface Pages {boolean hasMore();void Wl();}
    public static final class PageProxy implements Pages{
        public Pages b=new KList();public boolean hasMore(){return b.hasMore();}public void Wl(){b.Wl();}
    }
    public enum Phase {IDLE,LOAD_MORE}
    public static final class StateValue{public Phase LJ=Phase.IDLE;}
    public static final class Flow{public Object getValue(){return value;}final StateValue value=new StateValue();}
    public static final class KList implements Pages{
        public Flow f=new Flow();boolean hasMore=true;int loads;
        public boolean hasMore(){return hasMore;}public void Wl(){loads++;f.value.LJ=Phase.LOAD_MORE;}
    }
    public static class KManagerBase{}
    public static class ControllerBase{}
    public static final class Controller extends ControllerBase{public void LJIIJJI(List<?> list){}}
    public static final class KManager extends KManagerBase {
        public ControllerBase LIZJ=new Controller(); List<Cell> rows=new ArrayList<>();
        public List<?> LIZLLL(){return rows;}
    }
    public static class HandlerEvent{}
    public static final class RefreshEvent extends HandlerEvent{}
    public static final class Events{public KManagerBase LIZIZ; public void LIZ(HandlerEvent event){}}
    static Class<?> resolveKmp(String name)throws ClassNotFoundException {
        final String k="com.douyin.kmp.comment.comment.commentpanellist.list.";
        Map<String,Class<?>> m=Map.ofEntries(
            Map.entry("com.ss.android.ugc.aweme.comment.commentlist.kmp.KmpCommentListFragment",KPanel.class),
            Map.entry("X.0MbR",KBridge.class),Map.entry("X.0Mbd",KDelegateBase.class), Map.entry("X.0MbU",KDelegate.class),
            Map.entry("kotlin.Lazy",KLazy.class),Map.entry(k+"KCmtListPanelViewModel",KPanelVm.class),Map.entry(k+"KCmtListViewModel",KVm.class),
            Map.entry("X.0Mff",KManager.class),Map.entry("X.0Mey",Controller.class),Map.entry("X.0MAN",ControllerBase.class),
            Map.entry("X.0Mbe",KManagerBase.class),Map.entry("X.0Mh4",Events.class),Map.entry("X.0MhE",RefreshEvent.class),
            Map.entry("X.0Lzw",HandlerEvent.class),Map.entry("X.0MXK",KCell.class),
            Map.entry("androidx.compose.foundation.lazy.LazyListState",Scroll.class),
            Map.entry("X.0MZo",Pages.class),Map.entry("X.0MZn",PageProxy.class),
            Map.entry("kotlinx.coroutines.flow.StateFlow",Flow.class),Map.entry("com.bytedance.kmp.klist.KListViewModel",KList.class));
        Class<?> r=m.getOrDefault(name,TYPES.get(name));if(r==null)throw new ClassNotFoundException(name);return r;
    }
    static final Map<String,Class<?>> TYPES=Map.ofEntries(
        Map.entry("com.ss.android.ugc.aweme.comment.model.Comment",Comment.class),Map.entry("X.13Tl",Cell.class),
        Map.entry("com.ss.android.ugc.aweme.account.AccountProxyService",Account.class),
        Map.entry("com.ss.android.ugc.aweme.comment.ui.CommentListFragment",NativePanel.class),
        Map.entry("androidx.recyclerview.widget.RecyclerView",Recycler.class),
        Map.entry("androidx.recyclerview.widget.RecyclerView$Adapter",Adapter.class),
        Map.entry("com.ss.android.ugc.aweme.comment.adapter.CommentAdapter",Rows.class),
        Map.entry("com.ss.android.ugc.aweme.comment.commentlist.listcontainer.commentlist.refactor.CommentListRecyclerUiModule",Module.class),
        Map.entry("androidx.fragment.app.Fragment",Fragment.class),Map.entry("X.13RB",Event.class),Map.entry("X.11ue",Event.class),
        Map.entry("androidx.lifecycle.LifecycleOwner",Fragment.class),Map.entry("X.11gi",Registry.class),
        Map.entry("com.ss.android.ugc.aweme.arch.ability.ICmtAbility",Object.class),
        Map.entry("com.ss.android.ugc.aweme.comment.ui.viewmodels.IFetchViewModelAbility",FetchAbility.class),
        Map.entry("com.ss.android.ugc.aweme.comment.commentlist.listcontainer.commentlist.CommentListDataFetchHelper",Fetch.class));

    static <T> T mock(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
    final Map<Method,XposedInterface.Hooker> hooks=new HashMap<>();
    final XposedInterface api=mock(XposedInterface.class,(p,m,a)->{
        if(!m.getName().equals("hook"))return null;
        Method target=(Method)a[0];
        return mock(XposedInterface.HookBuilder.class,(b,build,params)->{
            if(!build.getName().equals("intercept"))return b;
            hooks.put(target,(XposedInterface.Hooker)params[0]);
            return mock(XposedInterface.HookHandle.class,(h,op,args)->null);
        });
    });
    void fire(Object receiver,Method method,Object... args)throws Throwable{
        hooks.get(method).intercept(mock(XposedInterface.Chain.class,
            (p,m,a)->m.getName().equals("getThisObject")?receiver:m.getName().equals("getArgs")?Arrays.asList(args):
                    m.getName().equals("getArg")?args[(Integer)a[0]]:null));
    }
    void shown(NativePanel panel)throws Throwable{fire(panel,NativePanel.class.getMethod("onShow"));}
    static Object field(Object owner,String name)throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    static String status(Object view)throws Exception{
        return (String)((androidx.compose.runtime.State<?>)field(view,"status$delegate")).getValue();
    }
    @Test public void failureCanRetryThenDelayedRendererOpensTheActualSearchDialogOnce()throws Throwable{
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        NativePanel panel=new NativePanel(a);
        try(LoadedCommentSearchHook hook=LoadedCommentSearchHook.install(api,name->{
            Class<?> type=TYPES.get(name);if(type==null)throw new ClassNotFoundException(name);return type;
        },null)){
            AtomicInteger clicks=new AtomicInteger();
            LoadedCommentSearchHook.request(a,"22",()->true,()->{clicks.incrementAndGet();return true;});
            assertEquals(0,clicks.get());
            hook.setOptions(true,false);
            LoadedCommentSearchHook.request(a,"22",()->true,()->{clicks.incrementAndGet();return false;});
            assertEquals(1,clicks.get());assertTrue(ShadowToast.getTextOfLatestToast().contains("没有可用"));
            LoadedCommentSearchHook.request(a,"22",()->true,()->{clicks.incrementAndGet();return true;});
            LoadedCommentSearchHook.request(a,"22",()->true,()->{clicks.incrementAndGet();return true;});
            assertEquals(2,clicks.get());
            shown(panel);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(350));
            assertNull(field(hook,"dialog"));
            panel.recycler.rows.data.add(new Cell());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(350));
            AlertDialog dialog=(AlertDialog)field(hook,"dialog");assertNotNull(dialog);assertTrue(dialog.isShowing());
            LoadedCommentSearchHook.request(a,"22",()->true,()->{clicks.incrementAndGet();return true;});
            assertSame(dialog,field(hook,"dialog"));assertEquals(2,clicks.get());
            dialog.dismiss();
        }finally{a.finish();}
    }
    @Test public void explicitPageAppends20To40OnSameQueryAndDialogAndOldPanelCannotReplaceIt()throws Throwable{
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        NativePanel panel=new NativePanel(a);
        Module module=new Module();module.g=panel;
        Fetch fetch=new Fetch();Registry.fetch=fetch;fetch.b=panel;
        try(LoadedCommentSearchHook hook=LoadedCommentSearchHook.install(api,name->{
            Class<?> type=TYPES.get(name);if(type==null)throw new ClassNotFoundException(name);return type;
        },null)){
            hook.setOptions(true,false);
            for(int i=1;i<=20;i++)panel.recycler.rows.data.add(new Cell(i,i==13?"needle":"plain"));
            shown(panel);
            LoadedCommentSearchHook.request(a,"22",()->true,()->{fail("already showing");return false;});
            AlertDialog dialog=(AlertDialog)field(hook,"dialog");
            Object view=field(hook,"search");assertNotNull(view);
            // The companion Compose UI suite drives the actual editor/button. Drive their callbacks
            // here against an installed hook, native helper, adapter and a real persistent dialog.
            @SuppressWarnings("unchecked")
            var draft=(androidx.compose.runtime.MutableState<String>)field(view,"draft$delegate");draft.setValue("needle");
            @SuppressWarnings("unchecked")
            var changed=(java.util.function.Consumer<String>)field(view,"changed");changed.accept("needle");
            assertTrue(status(view),status(view).contains("已查 20 条已加载评论，命中 1 条"));
            Runnable more=(Runnable)field(view,"loadMore");
            more.run();more.run();assertEquals(1,fetch.loads);
            Method append=Module.class.getMethod("LJJJJLI",List.class,boolean.class);
            // An entire page overlaps earlier CIDs. Its native completion is still a completed page.
            for(int i=1;i<=20;i++)panel.recycler.rows.data.add(new Cell(i,"duplicate"));
            fire(module,append,List.copyOf(panel.recycler.rows.data),true);
            ShadowLooper.idleMainLooper();
            assertTrue(status(view).contains("已查 20 条已加载评论，命中 1 条"));
            more.run();more.run();assertEquals(2,fetch.loads);
            panel.recycler.rows.data.add(new Cell(20,"plain")); // overlapping host page
            for(int i=21;i<=40;i++)panel.recycler.rows.data.add(new Cell(i,i==31?"needle again":"more"));
            fetch.page.hasMore=false;
            Thread worker=new Thread(()->{try{fire(module,append,List.of(),false);}catch(Throwable err){throw new AssertionError(err);}});
            worker.start();worker.join();
            ShadowLooper.idleMainLooper();
            assertSame(dialog,field(hook,"dialog"));assertSame(view,field(hook,"search"));
            assertEquals("needle",draft.getValue());
            assertTrue(status(view),status(view).contains("已查 40 条已加载评论，命中 2 条"));
            more.run();assertEquals(2,fetch.loads);
            panel.recycler.rows.data.clear();panel.recycler.rows.data.add(new Cell(77,"needle after refresh"));
            fire(module,Module.class.getMethod("LJJJJZI",List.class,boolean.class),List.of(new Comment()),false);
            ShadowLooper.idleMainLooper();
            assertTrue(status(view),status(view).contains("已查 1 条已加载评论，命中 1 条"));
            NativePanel b=new NativePanel(a);b.aid="33";Cell newWork=new Cell(88,"different");newWork.comment.awemeId="33";
            b.recycler.rows.data.add(newWork);shown(b);ShadowLooper.idleMainLooper();
            LoadedCommentSearchHook.request(a,"33",()->true,()->false);Object nextView=field(hook,"search");
            fire(module,append,List.of(),true);ShadowLooper.idleMainLooper();
            assertNotSame(view,nextView);assertTrue(status(nextView),status(nextView).contains("已查 1 条已加载评论，命中 1 条"));
            more.run();assertEquals(2,fetch.loads);
        }finally{Registry.fetch=null;a.finish();}
    }
    @Test public void kmpLoadMoreUsesOnlyBoundControllerAndHonorsHostBusyAndEndStates()throws Throwable{
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();KPanel p=new KPanel(activity);
        KVm vm=((KDelegate)p.U.LIZJ).LIZIZ.value.l;KList page=(KList)((PageProxy)vm.g).b;
        try(LoadedCommentSearchHook hook=LoadedCommentSearchHook.install(api,CommentSearchLaunchTest::resolveKmp,null)){
            for(int i=1;i<=20;i++)vm.M.rows.add(new KCell(i));
            hook.setOptions(true,false);fire(p,KPanel.class.getMethod("onShow"));ShadowLooper.idleMainLooper();
            LoadedCommentSearchHook.request(activity,"22",()->true,()->false);
            Object view=field(hook,"search");Runnable more=(Runnable)field(view,"loadMore");
            page.f.value.LJ=Phase.LOAD_MORE;more.run();assertEquals(0,page.loads);
            page.f.value.LJ=Phase.IDLE;more.run();more.run();assertEquals(1,page.loads);
            KCell invisible=new KCell(90);invisible.z=true;vm.M.rows.add(invisible);
            Method rendered=Controller.class.getMethod("LJIIJJI",List.class);
            fire(vm.M.LIZJ,rendered,vm.M.rows); // publication precedes IDLE
            ShadowLooper.idleMainLooper();
            more.run();assertEquals(1,page.loads);
            page.f.value.LJ=Phase.IDLE;
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(420));
            assertTrue(status(view).contains("已查 20 条"));
            more.run();more.run();assertEquals(2,page.loads);
            for(int i=21;i<=40;i++)vm.M.rows.add(new KCell(i));
            KCell hidden=new KCell(41);hidden.z=true;vm.M.rows.add(hidden);
            page.f.value.LJ=Phase.IDLE;page.hasMore=false;
            fire(vm.M.LIZJ,rendered,vm.M.rows);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(450));
            assertSame(view,field(hook,"search"));assertTrue(status(view),status(view).contains("已查 40 条"));
            more.run();assertEquals(2,page.loads);
        }finally{activity.finish();}
    }
}
