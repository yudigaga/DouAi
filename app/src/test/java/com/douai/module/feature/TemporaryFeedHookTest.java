package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the real response hook rather than a second copy of its predicate. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=28)
public class TemporaryFeedHookTest {
    NativeFollowLists accounts; Object previousAccount;
    AtomicReference<String> account = new AtomicReference<>("1");
    SeenWorkStore seen; SeenWorkHook hook;
    Map<String,Object> previous = new HashMap<>();
    long now;

    @Before public void setup() throws Exception {
        previousAccount=HostTargets.field(NativeFollowLists.class,"active").get(null);
        accounts=new NativeFollowLists(()->new NativeFollowLists.Account(account.get(),"sec"),
                (kind,query)->{throw new IllegalStateException();},null);
        HostTargets.field(NativeFollowLists.class,"active").set(null,accounts);
        for(String name:List.of("store","account","generation","cached"))
            previous.put(name,HostTargets.field(TemporaryFeedRules.class,name).get(null));
        now=System.currentTimeMillis();
        HostTargets.field(TemporaryFeedRules.class,"store").set(null,
                new TemporaryFeedRuleStore(Files.createTempDirectory("temporary-rules").toFile()));
        HostTargets.field(TemporaryFeedRules.class,"account").set(null,"1");
        HostTargets.field(TemporaryFeedRules.class,"generation").setLong(null,10);
        HostTargets.field(TemporaryFeedRules.class,"cached").set(null,List.of(
                new TemporaryFeedRuleStore.Rule(TemporaryFeedRuleStore.Kind.AUTHOR,"22","作者",now-1000,now+60000)));
        seen=new SeenWorkStore(Files.createTempDirectory("seen-hook").toFile(),Runnable::run,()->now);
        Constructor<SeenWorkHook> ctor=SeenWorkHook.class.getDeclaredConstructor(SeenWorkStore.class,
                java.util.function.Supplier.class,java.util.function.Consumer.class,Class.class,
                Field.class,Field.class,Field.class,Field.class,Field.class);
        ctor.setAccessible(true);
        hook=ctor.newInstance(seen,(java.util.function.Supplier<android.app.Activity>)()->null,
                (java.util.function.Consumer<String>)ignored->{},Fragment.class,
                Fragment.class.getField("panel"),Request.class.getField("model"),Model.class.getField("fragment"),
                Page.class.getField("items"),Work.class.getField("aid"));
    }
    @After public void close() throws Exception {
        if(hook!=null)hook.close();
        if(accounts!=null)accounts.close();
        HostTargets.field(NativeFollowLists.class,"active").set(null,previousAccount);
        for(var entry:previous.entrySet())HostTargets.field(TemporaryFeedRules.class,entry.getKey()).set(null,entry.getValue());
    }
    void options(boolean enabled,boolean seenEnabled,boolean allowEmpty) {
        hook.setOptions(enabled,new LocalOptions(Map.of("hide_seen_works",seenEnabled,
                "temporary_feed_rules_enabled",true,"filter_interrupt",!allowEmpty)));
    }
    Page request(String source,List<Object> rows,Runnable duringNativeRequest) throws Throwable {
        Page page=new Page();page.items=rows;
        Request request=new Request(new Model(new Fragment(new Panel(source))));
        XposedInterface.Chain chain=(XposedInterface.Chain)Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{XposedInterface.Chain.class},(proxy,method,args)->{
                    if(method.getName().equals("getThisObject"))return request;
                    if(method.getName().equals("proceed")){duringNativeRequest.run();return page;}
                    return null;
                });
        Method callback=SeenWorkHook.class.getDeclaredMethod("request",XposedInterface.Chain.class);
        callback.setAccessible(true);
        try{return (Page)callback.invoke(hook,chain);}catch(InvocationTargetException e){throw e.getCause();}
    }
    @Test public void temporaryFilterWorksWithoutSeenRecordingAndKeepsOtherSourcesUntouched() throws Throwable {
        options(true,false,false);
        Work hidden=new Work("2","22"),kept=new Work("3","33");
        List<Object> rows=List.of(hidden,kept);
        assertEquals(List.of(kept),request("homepage_hot",rows,()->{}).items);
        for(String source:List.of("homepage_follow","personal_homepage","search_result","collection"))
            assertSame(rows,request(source,rows,()->{}).items);
        options(false,false,false);
        assertSame(rows,request("homepage_hot",rows,()->{}).items);
    }
    @Test public void combinedConditionsApplyOneEmptyPageGuardAndPreserveOrder() throws Throwable {
        options(true,true,false);long token=seen.token("1");seen.visible(token,"2");
        Work seenWork=new Work("2","33"),temporary=new Work("3","22"),kept=new Work("4","44");
        List<Object> allHidden=List.of(seenWork,temporary);
        assertSame(allHidden,request("homepage_hot",allHidden,()->{}).items);
        assertEquals(List.of(kept),request("homepage_hot",List.of(seenWork,kept,temporary),()->{}).items);
        options(true,true,true);
        assertTrue(request("homepage_hot",allHidden,()->{}).items.isEmpty());
    }
    @Test public void accountOptionsAndRuleChangesDuringRequestRejectOldResult() throws Throwable {
        options(true,false,true);List<Object> rows=List.of(new Work("2","22"));
        assertSame(rows,request("homepage_hot",rows,()->account.set("9")).items);
        account.set("1");
        assertSame(rows,request("homepage_hot",rows,()->options(false,false,true)).items);
        options(true,false,true);
        assertSame(rows,request("homepage_hot",rows,()->{
            try{HostTargets.field(TemporaryFeedRules.class,"generation").setLong(null,11);}
            catch(Exception e){throw new AssertionError(e);}
        }).items);
    }
    @Test public void reflectionFailurePreservesTheWholeNativePage() throws Throwable {
        options(true,false,true);
        List<Object> rows=List.of(new Work("2","22"),new Work("3","33"){
            @Override public String getAuthorUid(){throw new IllegalStateException("unavailable native model");}
        });
        assertSame(rows,request("homepage_hot",rows,()->{}).items);
    }
    public static class Work {public String aid;String author;Work(String a,String u){aid=a;author=u;}public String getAuthorUid(){return author;}}
    public static class Page {public List<Object> items;}
    public static class Panel {String source;Panel(String s){source=s;}public String getEventType(){return source;}}
    public static class Fragment {public Panel panel;Fragment(Panel p){panel=p;}}
    public static class Model {public final Fragment fragment;Model(Fragment f){fragment=f;}}
    public static class Request {public final Model model;Request(Model m){model=m;}}
}
