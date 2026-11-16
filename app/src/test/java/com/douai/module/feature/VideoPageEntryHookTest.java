package com.douai.module.feature;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import com.douai.module.scan.*;
import io.github.libxposed.api.XposedInterface;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.*;
import java.lang.ref.WeakReference;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
public class VideoPageEntryHookTest {
 public static class Work { String aid="one"; public String getAid(){return aid;} public int getAwemeType(){return 0;} public boolean isLive(){return false;} public boolean getAd(){return false;} }
 public static class Params { public Panel mFeedContext; public boolean forceDismissCover; }
 public static class Holder { public FrameLayout w; public Params B2; public Work work; public Work getAweme(){return work;} public void selected(){} public void resume(){} public void pause(){} }
 public static class Panel { public Holder current; public Player V=new Player(); public Holder currentHolder(){return current;} }
 public static class Player { public Dispatcher m=new Dispatcher(); }
 public static class Dispatcher { Runnable firstFrameAction; public void playing(String aid){} public void onRenderFirstFrame(String aid,Info info){if(firstFrameAction!=null)firstFrameAction.run();} }
 public static class Info {}
 public static class Model {}
 public static class Presenter { public Params videoItemParams; public Work mAweme; public View mCoverView; Runnable bindingAction; public void onBindSafe(Model m){if(bindingAction!=null)bindingAction.run();} public void mustUnbind(){} public void unBindSafe(){} }
 public static class Observer { public Presenter a; int calls; Observer(Presenter p){a=p;} public void onChanged(Object reason){calls++;a.mCoverView.setVisibility(View.GONE);} }
 public static class Listener { public Listener(Presenter p,WeakReference<View> v,Info info){} public void onComplete(android.net.Uri u,View v,Info i,android.graphics.drawable.Animatable a){} public void onFailed(android.net.Uri u,View v,Throwable t){} }
 public static class Account { static String uid="account"; public static Account userService(){return new Account();} public String getCurUserId(){return uid;} }
 static class Root extends FrameLayout { Root(Activity a){super(a);} @Override public boolean hasWindowFocus(){return true;} }
 @Test public void loadedCoverWaitsForRealFrameAndNativeHideReplaysOnce() throws Throwable {try(Fixture f=new Fixture()) { f.load();f.hide();assertEquals("Confirmed target cover must survive premature native hide",View.VISIBLE,f.cover.getVisibility());assertEquals(0,f.observer.calls);f.frame(f.panel.V.m);assertEquals(View.VISIBLE,f.cover.getVisibility());idle(50);assertEquals(View.GONE,f.cover.getVisibility());assertEquals(1,f.observer.calls); }}
 @Test public void unconfirmedCoverNeverHeld()throws Throwable{try(Fixture f=new Fixture()){f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void staleLoadAfterRebindCannotConfirmNewCover()throws Throwable{try(Fixture f=new Fixture()){Listener old=f.listener;f.bind();f.complete(old,f.cover);f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void wrongCompletionViewNeverConfirmsCover()throws Throwable{try(Fixture f=new Fixture()){f.complete(f.listener,new View(f.activity));f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void wrongDispatcherCannotReleaseCover()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.frame(new Dispatcher());idle(50);assertEquals(0,f.observer.calls);f.frame(f.panel.V.m);idle(50);assertEquals(1,f.observer.calls);}}
 @Test public void renderBeforeLoadAndHideNeverRearms()throws Throwable{try(Fixture f=new Fixture()){f.frame(f.panel.V.m);f.load();f.select();f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void repeatedHidesKeepFirstAbsoluteDeadline()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();idle(450);f.hide();idle(151);assertEquals(1,f.observer.calls);assertEquals(View.GONE,f.cover.getVisibility());}}
 @Test public void rebindCancelsPendingReplay()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.bind();idle(650);assertEquals(0,f.observer.calls);assertEquals(View.VISIBLE,f.cover.getVisibility());}}
 @Test public void disableReleasesSameBindingAndDoesNotRearmOnEnable()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.enabled(false);assertEquals(1,f.observer.calls);f.enabled(true);f.cover.setVisibility(View.VISIBLE);f.hide();assertEquals(2,f.observer.calls);}}
 @Test public void forceDismissAlwaysRunsNative()throws Throwable{try(Fixture f=new Fixture()){f.load();f.presenter.videoItemParams.forceDismissCover=true;f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void currentHolderMismatchRunsNative()throws Throwable{try(Fixture f=new Fixture()){f.load();f.panel.current=new Holder();f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void detachReleasesPendingAndCannotRearm()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();((FrameLayout)f.cover.getParent()).removeView(f.cover);idle(650);assertEquals(1,f.observer.calls);}}
 @Test public void pauseReleasesPending()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.h.invoke(Holder.class.getMethod("pause"),f.holder);assertEquals(1,f.observer.calls);}}
 @Test public void closeReplaysAndUnhooks()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.closeHook();assertEquals(1,f.observer.calls);assertTrue(f.h.hooks.isEmpty());}}
 @Test public void constructorAndCompletionInsideBindAreNotResetAfterProceed()throws Throwable{try(Fixture f=new Fixture()){f.presenter.bindingAction=()->{try{f.listener=new Listener(f.presenter,new WeakReference<>(f.cover),new Info());f.h.invoke(Listener.class.getConstructor(Presenter.class,WeakReference.class,Info.class),f.listener,f.presenter,new WeakReference<>(f.cover),new Info());f.load();}catch(Throwable e){throw new RuntimeException(e);}};f.h.invoke(Presenter.class.getMethod("onBindSafe",Model.class),f.presenter,new Model());f.hide();assertEquals(0,f.observer.calls);}}
 @Test public void selectedAfterBindAndLoadCanAcquireMatchingHolder()throws Throwable{try(Fixture f=new Fixture()){f.panel.current=null;f.bind();f.load();f.panel.current=f.holder;f.select();f.hide();assertEquals(0,f.observer.calls);}}
 @Test public void pausedActivityReleasesNativeCover()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();((android.app.Application.ActivityLifecycleCallbacks)f.hook).onActivityPaused(f.activity);assertEquals(1,f.observer.calls);}}
 @Test public void bothUnbindPathsCancelRatherThanReplayIntoNextWork()throws Throwable{for(String name:new String[]{"mustUnbind","unBindSafe"})try(Fixture f=new Fixture()){f.load();f.hide();f.h.invoke(Presenter.class.getMethod(name),f.presenter);idle(650);assertEquals(0,f.observer.calls);}}
 @Test public void bindAgeLimitsHoldingEvenIfSuccessfullyLoaded()throws Throwable{try(Fixture f=new Fixture()){f.load();idle(1501);f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void explicitNativeGoneNeverResurrects()throws Throwable{try(Fixture f=new Fixture()){f.load();f.cover.setVisibility(View.GONE);f.hide();assertEquals(1,f.observer.calls);assertEquals(View.GONE,f.cover.getVisibility());}}
 @Test public void accountChangeCannotHoldOrReplayOldObserver()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();Account.uid="other";idle(650);assertEquals(0,f.observer.calls);}}
 @Test public void failureAfterCompletionInvalidatesLoad()throws Throwable{try(Fixture f=new Fixture()){f.load();f.h.invoke(Listener.class.getMethod("onFailed",android.net.Uri.class,View.class,Throwable.class),f.listener,null,f.cover,new Exception());f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void wrongPanelDoesNotHold()throws Throwable{try(Fixture f=new Fixture()){f.load();f.holder.B2.mFeedContext=new Panel();f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void directCommentsRemainAboveTheNativeCardWithoutAnyOverlay()throws Throwable{try(Fixture f=new Fixture()){View comments=new View(f.activity);f.root.addView(comments);int children=f.root.getChildCount();f.load();f.hide();assertEquals(children,f.root.getChildCount());assertSame(comments,f.root.getChildAt(children-1));assertEquals(View.VISIBLE,comments.getVisibility());}}
 @Test public void losingWindowFocusReleasesPendingObserver()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();org.robolectric.util.ReflectionHelpers.callInstanceMethod(f.cover.getViewTreeObserver(),"dispatchOnWindowFocusChange",org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class,false));assertEquals(1,f.observer.calls);}}
 @Test public void leavingBeforeHideCannotRearmOnReturn()throws Throwable{try(Fixture f=new Fixture()){f.load();f.h.invoke(Holder.class.getMethod("pause"),f.holder);f.select();f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void repeatedNormalHideAfterFrameStillWaitsForDrawingOpportunity()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.frame(f.panel.V.m);f.hide("HideCover when Progress");assertEquals(0,f.observer.calls);idle(50);assertEquals(1,f.observer.calls);}}
 @Test public void realFrameBeforeSelectionPreventsLaterLease()throws Throwable{try(Fixture f=new Fixture()){f.panel.current=null;f.bind();f.frame(f.panel.V.m);f.load();f.panel.current=f.holder;f.select();f.hide();assertEquals(1,f.observer.calls);}}
 @Test public void unknownAndSuppressionReasonsAlwaysRunNatively()throws Throwable{for(Object reason:new Object[]{null,"unknown","onResumePlay","surfaceViewSelect"})try(Fixture f=new Fixture()){f.load();f.hide(reason);assertEquals("reason="+reason,1,f.observer.calls);}}
 @Test public void suppressionCancelsPendingWithoutLaterDuplicateReplay()throws Throwable{for(boolean rendered:new boolean[]{false,true})for(Object reason:new Object[]{null,"unknown","onResumePlay","surfaceViewSelect"})try(Fixture f=new Fixture()){f.load();f.hide();if(rendered)f.frame(f.panel.V.m);f.hide(reason);assertEquals("reason="+reason,1,f.observer.calls);idle(650);assertEquals(1,f.observer.calls);}}
 @Test public void pendingLeaseCanOutliveBindingAgeGateWithoutExtendingDeadline()throws Throwable{try(Fixture f=new Fixture()){f.load();idle(1400);f.hide();idle(101);f.hide("HideCover when PreRender");assertEquals(0,f.observer.calls);idle(500);assertEquals(1,f.observer.calls);}}
 @Test public void forceDismissInterruptsPendingDrawingWait()throws Throwable{try(Fixture f=new Fixture()){f.load();f.hide();f.frame(f.panel.V.m);f.presenter.videoItemParams.forceDismissCover=true;f.hide();assertEquals(1,f.observer.calls);idle(650);assertEquals(1,f.observer.calls);}}
 @Test public void unselectedFrameFromWrongDispatcherDoesNotBlockLaterValidLease()throws Throwable{try(Fixture f=new Fixture()){f.panel.current=null;f.bind();f.frame(new Dispatcher());f.load();f.panel.current=f.holder;f.select();f.hide();assertEquals(0,f.observer.calls);}}
 @Test public void nativeHideInsideFirstFrameDispatchReplaysAfterDrawingOpportunity()throws Throwable{try(Fixture f=new Fixture()){f.load();f.panel.V.m.firstFrameAction=()->{try{f.hide();}catch(Throwable e){throw new RuntimeException(e);}};f.frame(f.panel.V.m);assertEquals(0,f.observer.calls);idle(50);assertEquals(1,f.observer.calls);}}
 static void idle(long ms){org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(java.time.Duration.ofMillis(ms));}
 static class Fixture implements AutoCloseable {
  Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get(); Root root=new Root(activity); View cover=new View(activity); Panel panel=new Panel(); Holder holder=new Holder(); Presenter presenter=new Presenter(); Observer observer=new Observer(presenter); Listener listener; Harness h=new Harness(); Object hook;
  Fixture()throws Throwable {Account.uid="account";HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);root.addView(cover,new FrameLayout.LayoutParams(200,300));activity.setContentView(root);root.layout(0,0,400,800);cover.layout(0,0,200,300);holder.w=root;holder.B2=new Params();holder.B2.mFeedContext=panel;holder.work=new Work();panel.current=holder;presenter.videoItemParams=holder.B2;presenter.mAweme=holder.work;presenter.mCoverView=cover;
   ClassLoader loader=new ClassLoader(getClass().getClassLoader()){@Override public Class<?> loadClass(String n)throws ClassNotFoundException {switch(n){case "com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter":return Presenter.class;case "com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter$observeVM$5":return Observer.class;case "X.0HLd":return Listener.class;case "X.0HLW":case "X.0Gf4":case "com.bytedance.lighten.core.ImageInfo":return Info.class;case "com.ss.android.ugc.aweme.kiwi.model.QModel":return Model.class;case "com.ss.android.ugc.aweme.detail.panel.DetailFragmentPanel":return Panel.class;case "com.ss.android.ugc.aweme.account.AccountProxyService":return Account.class;default:return super.loadClass(n);}}};
   try{hook=Class.forName("com.douai.module.feature.VideoPageEntryHook").getMethod("install",XposedInterface.class,HostScanReport.class,ClassLoader.class,boolean.class,java.util.function.Consumer.class).invoke(null,h.framework,report(),loader,true,(java.util.function.Consumer<String>)s->{});}catch(ClassNotFoundException missing){}catch(InvocationTargetException e){throw e.getCause();}
   bind();select();
  }
  void bind()throws Throwable{h.invoke(Presenter.class.getMethod("onBindSafe",Model.class),presenter,new Model());listener=new Listener(presenter,new WeakReference<>(cover),new Info());h.invoke(Listener.class.getConstructor(Presenter.class,WeakReference.class,Info.class),listener,presenter,new WeakReference<>(cover),new Info());}
  void select()throws Throwable{h.invoke(Holder.class.getMethod("selected"),holder);}
  void complete(Listener l,View v)throws Throwable{h.invoke(Listener.class.getMethod("onComplete",android.net.Uri.class,View.class,Info.class,android.graphics.drawable.Animatable.class),l,null,v,new Info(),null);}
  void load()throws Throwable{complete(listener,cover);}
  void hide()throws Throwable{hide("HideCover when Playing");}
  void hide(Object reason)throws Throwable{h.invoke(Observer.class.getMethod("onChanged",Object.class),observer,reason);}
  void frame(Dispatcher d)throws Throwable{h.invoke(Dispatcher.class.getMethod("onRenderFirstFrame",String.class,Info.class),d,"one",new Info());}
  void enabled(boolean value)throws Exception{if(hook!=null)hook.getClass().getMethod("setEnabled",boolean.class).invoke(hook,value);}
  void closeHook()throws Exception{if(hook!=null)((AutoCloseable)hook).close();}
  public void close()throws Exception{closeHook();activity.finish();HostAdaptation.setCurrentProfile(null);}
 }
 static HostScanReport report()throws Exception {List<RuleScanResult> rs=new ArrayList<>();for(String[] pair:new String[][]{{"feed_holder_page_selected","selected"},{"feed_holder_resume","resume"},{"feed_holder_pause","pause"},{"feed_aweme_accessor","getAweme"}})add(rs,pair[0],Holder.class.getMethod(pair[1]));add(rs,"auto_slide_current_holder",Panel.class.getMethod("currentHolder"));add(rs,"component_player_playing",Dispatcher.class.getMethod("playing",String.class));return new HostScanReport("test",rs,false);}
 static void add(List<RuleScanResult> rs,String id,Method m){rs.add(new RuleScanResult(id,"test",Collections.singletonList(new HostCandidate("test","classes.dex",HostTargets.descriptor(m.getDeclaringClass()),m.getName(),HostTargets.descriptor(m)))));}
 static class Harness {
  Map<Executable,XposedInterface.Hooker> hooks=new HashMap<>();
  XposedInterface framework=(XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.class},(p,m,a)->{if(!m.getName().equals("hook"))return null;Executable target=(Executable)a[0];return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.HookBuilder.class},(b,op,args)->{if(!op.getName().equals("intercept"))return b;hooks.put(target,(XposedInterface.Hooker)args[0]);return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.HookHandle.class},(v,c,x)->{if(c.getName().equals("unhook"))hooks.remove(target);return null;});});});
  Object invoke(Executable target,Object owner,Object...args)throws Throwable{XposedInterface.Hooker hook=hooks.get(target);if(hook==null)return original(target,owner,args);return hook.intercept((XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.Chain.class},(p,m,a)->{switch(m.getName()){case "getThisObject":return owner;case "getExecutable":return target;case "getArg":return args[(Integer)a[0]];case "getArgs":return Arrays.asList(args);case "proceed":return original(target,owner,args);default:return null;}}));}
  Object original(Executable target,Object owner,Object[] args)throws Throwable{if(target instanceof Constructor)return null;try{return ((Method)target).invoke(owner,args);}catch(InvocationTargetException e){throw e.getCause();}}
 }
}
