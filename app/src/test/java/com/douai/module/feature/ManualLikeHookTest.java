package com.douai.module.feature;

import android.app.Activity;
import com.douai.module.config.FeatureSettings;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public final class ManualLikeHookTest {
    public static final class Work {
        public String aid = "101";
        public Object userDigg = 0;
        public String getAid() { return aid; }
    }
    public static final class Holder {
        public Work work;
        public Work getAweme() { return work; }
    }
    public static final class Panel {
        public final Holder holder = new Holder();
        public Holder getCurrentViewHolderP() { return holder; }
    }
    public static final class Params { public final Panel mFeedContext = new Panel(); }
    public static final class QContext {
        Activity owner;
        public Activity activity() { return owner; }
    }
    public static final class VM {
        public Work b = new Work(); int calls;
        public void JD(Activity owner,String source,Object callback) { calls++; }
    }
    public static final class Presenter {
        public Params model = new Params();
        public final QContext context = new QContext();
        public final VM vm = new VM();
        public VM viewModel() { return vm; }
        public QContext getQContext() { return context; }
        Presenter(Activity owner) { context.owner=owner; model.mFeedContext.holder.work=vm.b; }
    }
    private static AutoLikeHook hook() throws Exception {
        Constructor<?> ctor=AutoLikeHook.class.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        return (AutoLikeHook)ctor.newInstance(ManualLikeHookTest.class.getClassLoader(),
                VM.class.getMethod("JD",Activity.class,String.class,Object.class), null,
                Presenter.class.getMethod("viewModel"),VM.class.getField("b"),FeatureSettings.defaults(),
                (java.util.function.Consumer<String>)s -> { });
    }
    private static void bind(AutoLikeHook hook, Presenter p) throws Exception {
        Class<?> chain=io.github.libxposed.api.XposedInterface.Chain.class;
        Object call=Proxy.newProxyInstance(chain.getClassLoader(),new Class<?>[]{chain},
                (proxy,method,args) -> method.getName().equals("getThisObject") ? p : null);
        Method bind=AutoLikeHook.class.getDeclaredMethod("afterBind",chain); bind.setAccessible(true);
        bind.invoke(hook,call);
    }
    @Test public void manualTapWorksWithAutomationOffAndNeverUnlikesOrRepeatsPendingRequest() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        Presenter p=new Presenter(activity);
        try (AutoLikeHook hook=hook()) {
            bind(hook,p); assertEquals(0,p.vm.calls);
            hook.requestManualLike(activity,"101"); hook.requestManualLike(activity,"101");
            assertEquals(1,p.vm.calls);
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
            p.vm.b.userDigg=1; hook.requestManualLike(activity,"101");
            p.vm.b.userDigg=null; hook.requestManualLike(activity,"101");
            p.vm.b.userDigg=-1; hook.requestManualLike(activity,"101");
            assertEquals(1,p.vm.calls);
            p.vm.b.userDigg=0; hook.requestManualLike(activity,"101");
            assertEquals(2,p.vm.calls);
        } finally { activity.finish(); }
    }
    @Test public void oldPresenterRecycledWorkForeignOwnerAndPreloadedCardAreSkipped() throws Exception {
        Activity owner=Robolectric.buildActivity(Activity.class).setup().get();
        Activity foreign=Robolectric.buildActivity(Activity.class).setup().get();
        Presenter p=new Presenter(owner), detached=new Presenter(owner);
        try (AutoLikeHook hook=hook()) {
            bind(hook,p);
            p.model.mFeedContext.holder.work=new Work(); // same ID but stale model
            hook.requestManualLike(owner,"101");
            assertEquals(0,p.vm.calls);
            p.model.mFeedContext.holder.work=p.vm.b;
            hook.requestManualLike(foreign,"101");
            p.vm.b.aid="102"; hook.requestManualLike(owner,"101");
            assertEquals(0,p.vm.calls);
            bind(hook,detached);
            detached.model.mFeedContext.holder.work=null;
            hook.requestManualLike(owner,"102");
            assertEquals(1,p.vm.calls); assertEquals(0,detached.vm.calls);
            hook.close(); hook.requestManualLike(owner,"102");
            assertEquals(1,p.vm.calls);
        } finally { owner.finish(); foreign.finish(); }
    }
}
