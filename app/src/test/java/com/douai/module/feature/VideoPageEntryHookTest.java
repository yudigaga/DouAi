package com.douai.module.feature;

import android.app.Activity;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
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
 public static class Params { public Panel mFeedContext; public boolean forceDismissCover,isVideoCoverLoaded; public String eventType="detail"; public int holderType; public String getEventType(){return eventType;} public int getViewHolderType(){return holderType;} }
 public static class Holder { public FrameLayout w; public Params B2; public Work work; public Work getAweme(){return work;} public void selected(){} public void resume(){} public void pause(){} public void bind(Work work){this.work=work;} public void bind(Work work,int index){this.work=work;} public com.ss.android.ugc.aweme.feed.adapter.IFeedPlayerView player; public com.ss.android.ugc.aweme.feed.adapter.IFeedPlayerView getFeedPlayerView(){return player;} }
 public static class Panel { public Holder current; public Player V=new Player(); public Pager f; public View h; public Dialogs L0=new Dialogs(); public Holder currentHolder(){return current;} }
 public static class Dialogs { boolean open; public boolean comments(boolean includeTransition){return open;} }
 public static class DetailPanel extends Panel {}
 public static class Pager extends FrameLayout { int scroll; Runnable move; Pager(Activity a){super(a);} public int getScrollState(){return scroll;} @Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&move!=null)move.run();return true;} }
 public static class NativeCover extends android.widget.ImageView { final ImageBounds image=new ImageBounds(); NativeCover(Activity a){super(a);setImageDrawable(new android.graphics.drawable.ColorDrawable(0xff123456));} public ImageBounds getHierarchy(){return image;} }
 public static class ImageBounds { final RectF bounds=new RectF(0,0,400,800); public void getActualImageBounds(RectF out){out.set(bounds);} }
 public static class Player { public Dispatcher m=new Dispatcher(); }
 public static class Dispatcher { Runnable firstFrameAction; public void playing(String aid){} public void onRenderFirstFrame(String aid,Info info){if(firstFrameAction!=null)firstFrameAction.run();} }
 public static class Info {}
 public static class Model {}
 public static class Presenter { public Params videoItemParams; public Work mAweme; public View mCoverView; Runnable bindingAction; public void onBindSafe(Model m){if(bindingAction!=null)bindingAction.run();} public void mustUnbind(){} public void unBindSafe(){} }
 public static class Observer { public Presenter a; int calls; Observer(Presenter p){a=p;} public void onChanged(Object reason){calls++;a.mCoverView.setVisibility(View.GONE);} }
 public static class Listener { final Presenter presenter; final WeakReference<View> cover; public Listener(Presenter p,WeakReference<View> v,Info info){presenter=p;cover=v;} public void onComplete(android.net.Uri u,View v,Info i,android.graphics.drawable.Animatable a){if(v==cover.get())presenter.videoItemParams.isVideoCoverLoaded=true;} public void onFailed(android.net.Uri u,View v,Throwable t){} }
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

 @Test public void visibleIncomingFeedCoverWaitsForMatchingFirstFrame() throws Throwable {
  try(Fixture f=new Fixture(true)){
   assertTrue("Fixture must simulate a visible native cover and video with matching bounds",
           FeedCoverGeometry.matches(f.root,f.cover,f.holder.player.getVideoView()));
   f.load();f.beginSwipe();f.hide();
   assertEquals("An already loaded incoming cover must survive the native early hide",0,f.observer.calls);
   f.finishSwipe();f.panel.current=f.holder;f.select();
   f.frame(new Dispatcher());idle(50);assertEquals(0,f.observer.calls);
   f.frame(f.panel.V.m);idle(50);assertEquals(1,f.observer.calls);assertEquals(View.GONE,f.cover.getVisibility());
  }
 }

 @Test public void abortingTheManualSwipeReplaysTheOriginalHide() throws Throwable {
  try(Fixture f=new Fixture(true)){
   f.load();f.beginSwipe();f.hide();assertEquals(0,f.observer.calls);
   f.touch(MotionEvent.ACTION_CANCEL,250);assertEquals(1,f.observer.calls);
   idle(650);assertEquals(1,f.observer.calls);
  }
 }

 @Test public void nativeHideInsideFirstDraggingMoveKeepsTheIncomingCoverButATouchAloneCannot() throws Throwable {
  try(Fixture f=new Fixture(true)){
   f.load();
   f.touch(MotionEvent.ACTION_DOWN,400);
   f.panel.f.move=()->{
    f.panel.f.scroll=1;
    try{f.hide();}catch(Throwable failure){throw new AssertionError(failure);}
   };
   f.touch(MotionEvent.ACTION_MOVE,250);
   assertEquals(0,f.observer.calls);
   f.touch(MotionEvent.ACTION_CANCEL,250);
   assertEquals(1,f.observer.calls);
  }
  try(Fixture f=new Fixture(true)){
   f.load();
   f.touch(MotionEvent.ACTION_DOWN,400);
   f.touch(MotionEvent.ACTION_UP,400);
   f.hide();
   assertEquals(1,f.observer.calls);
  }
 }

 @Test public void openingCommentsReleasesTheManualCoverWithoutWaitingForAFrame() throws Throwable {
  try(Fixture f=new Fixture(true)){
   f.load();f.beginSwipe();f.hide();assertEquals(0,f.observer.calls);
   f.panel.L0.open=true;
   f.root.getViewTreeObserver().dispatchOnPreDraw();
   assertEquals(1,f.observer.calls);
  }
 }

 @Test public void recyclingTheIncomingHolderNeverReplaysTheOldNativeHide() throws Throwable {
  try(Fixture f=new Fixture(true)){
   f.load();f.beginSwipe();f.hide();assertEquals(0,f.observer.calls);
   Work other=new Work();other.aid="new";
   f.h.invoke(Holder.class.getMethod("bind",Work.class),f.holder,other);
   idle(650);
   assertEquals(0,f.observer.calls);assertEquals(View.VISIBLE,f.cover.getVisibility());
  }
 }

 @Test public void missingTinyAndUnselectedStationaryCoversRemainNative() throws Throwable {
  for(int variant=0;variant<3;variant++)try(Fixture f=new Fixture(true)){
   if(variant!=0)f.load();
   if(variant==1)((NativeCover)f.cover).image.bounds.set(0,500,120,800);
   if(variant!=2)f.beginSwipe();
   f.hide();assertEquals("variant="+variant,1,f.observer.calls);
  }
 }
 @Test public void manualFeedLeavesPanelCoverAndSpecialHolderHideNative() throws Throwable {
  for(boolean special:new boolean[]{false,true})try(Fixture f=new Fixture(true)){
   f.load();f.beginSwipe();
   if(special)f.holder.B2.holderType=18;else f.panel.h=new View(f.activity);
   f.hide();assertEquals(1,f.observer.calls);
  }
 }
 @Test public void coverGateDiagnosticIsManualOptInAndEmittedOnce() throws Throwable {
  boolean previous=DebugLog.isEnabled();
  List<String> events=new java.util.concurrent.CopyOnWriteArrayList<>();
  DebugLog.setEnabled(true);
  DouAiLog.setFrameworkSink((level,json)->{if(json.contains("\"event\":\"manual_swipe_cover_gate\""))events.add(json);});
  try {
   try(Fixture stationary=new Fixture(true)){stationary.load();stationary.hide();}
   try(Fixture f=new Fixture(true)){
    f.load();f.beginSwipe();((NativeCover)f.cover).image.bounds.set(0,500,120,800);
    f.hide();f.hide();assertEquals(2,f.observer.calls);
   }
   assertTrue(DouAiLog.awaitIdle(5000));assertEquals(1,events.size());
   assertTrue(events.get(0).contains("\"reason_code\":\"footprint_mismatch\""));
   assertTrue(events.get(0).contains("work=ref_"));
   assertFalse(events.get(0).contains("work=one"));
   DebugLog.setEnabled(false);
   try(Fixture f=new Fixture(true)){f.beginSwipe();f.hide();assertEquals(1,f.observer.calls);}
   assertTrue(DouAiLog.awaitIdle(5000));assertEquals(1,events.size());
  } finally {
   DouAiLog.setFrameworkSink(null);DebugLog.setEnabled(previous);
  }
 }
 static void idle(long ms){org.robolectric.shadows.ShadowLooper.getShadowMainLooper().idleFor(java.time.Duration.ofMillis(ms));}
 static class Fixture implements AutoCloseable {
  Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get(); Root root=new Root(activity); final View cover; final Panel panel; Holder holder=new Holder(); Presenter presenter=new Presenter(); Observer observer=new Observer(presenter); Listener listener; Harness h=new Harness(); Object hook;
  Fixture()throws Throwable {this(false);}
  Fixture(boolean feed)throws Throwable {Account.uid="account";HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);cover=feed?new NativeCover(activity):new View(activity);panel=feed?new Panel():new DetailPanel();
   if(feed){panel.f=new Pager(activity);panel.f.addView(root,new FrameLayout.LayoutParams(400,800));activity.setContentView(panel.f);panel.f.layout(0,0,400,800);}
   else activity.setContentView(root);
   SurfaceView render=new SurfaceView(activity);
   if(feed){root.addView(render,new FrameLayout.LayoutParams(400,800));holder.player=()->render;}
   root.addView(cover,new FrameLayout.LayoutParams(feed?400:200,feed?800:300));root.layout(0,feed?120:0,400,feed?920:800);cover.layout(0,0,feed?400:200,feed?800:300);
   if(feed)render.layout(0,0,400,800);
   holder.w=root;holder.B2=new Params();holder.B2.mFeedContext=panel;holder.B2.eventType=feed?"homepage_hot":"detail";holder.work=new Work();panel.current=feed?new Holder():holder;presenter.videoItemParams=holder.B2;presenter.mAweme=holder.work;presenter.mCoverView=cover;
   ClassLoader loader=new ClassLoader(getClass().getClassLoader()){@Override public Class<?> loadClass(String n)throws ClassNotFoundException {switch(n){case "com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter":return Presenter.class;case "com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter$observeVM$5":return Observer.class;case "X.0HLd":return Listener.class;case "X.0HLW":case "X.0Gf4":case "com.bytedance.lighten.core.ImageInfo":return Info.class;case "com.ss.android.ugc.aweme.kiwi.model.QModel":return Model.class;case "com.ss.android.ugc.aweme.detail.panel.DetailFragmentPanel":return DetailPanel.class;case "com.ss.android.ugc.aweme.feed.widget.CoverImageView":return NativeCover.class;case "com.ss.android.ugc.aweme.account.AccountProxyService":return Account.class;default:return super.loadClass(n);}}};
   try{hook=Class.forName("com.douai.module.feature.VideoPageEntryHook").getMethod("install",XposedInterface.class,HostScanReport.class,ClassLoader.class,boolean.class,java.util.function.Consumer.class).invoke(null,h.framework,report(),loader,true,(java.util.function.Consumer<String>)s->{});}catch(ClassNotFoundException missing){}catch(InvocationTargetException e){throw e.getCause();}
   if(feed)h.invoke(Holder.class.getMethod("bind",Work.class),holder,holder.work);
   bind();if(!feed)select();
  }
  void touch(int action,float y)throws Throwable{MotionEvent event=MotionEvent.obtain(0,10,action,50,y,0);try{h.invoke(Pager.class.getMethod("dispatchTouchEvent",MotionEvent.class),panel.f,event);}finally{event.recycle();}}
  void beginSwipe()throws Throwable{touch(MotionEvent.ACTION_DOWN,400);panel.f.scroll=1;touch(MotionEvent.ACTION_MOVE,250);}
  void finishSwipe()throws Throwable{touch(MotionEvent.ACTION_UP,250);panel.f.scroll=2;}
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
 static HostScanReport report()throws Exception {List<RuleScanResult> rs=new ArrayList<>();for(String[] pair:new String[][]{{"feed_holder_page_selected","selected"},{"feed_holder_resume","resume"},{"feed_holder_pause","pause"},{"feed_aweme_accessor","getAweme"}})add(rs,pair[0],Holder.class.getMethod(pair[1]));add(rs,"auto_slide_current_holder",Panel.class.getMethod("currentHolder"));add(rs,"component_player_playing",Dispatcher.class.getMethod("playing",String.class));add(rs,"feed_item_bind_aweme",Holder.class.getMethod("bind",Work.class));add(rs,"feed_item_bind_aweme_position",Holder.class.getMethod("bind",Work.class,int.class));add(rs,"auto_slide_pager_touch",Pager.class.getMethod("dispatchTouchEvent",MotionEvent.class));add(rs,"auto_slide_scroll_state",Pager.class.getMethod("getScrollState"));add(rs,"auto_slide_comments_showing",Dialogs.class.getMethod("comments",boolean.class));return new HostScanReport("test",rs,false);}
 static void add(List<RuleScanResult> rs,String id,Method m){rs.add(new RuleScanResult(id,"test",Collections.singletonList(new HostCandidate("test","classes.dex",HostTargets.descriptor(m.getDeclaringClass()),m.getName(),HostTargets.descriptor(m)))));}
 static class Harness {
  Map<Executable,XposedInterface.Hooker> hooks=new HashMap<>();
  XposedInterface framework=(XposedInterface)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.class},(p,m,a)->{if(!m.getName().equals("hook"))return null;Executable target=(Executable)a[0];return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.HookBuilder.class},(b,op,args)->{if(!op.getName().equals("intercept"))return b;hooks.put(target,(XposedInterface.Hooker)args[0]);return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.HookHandle.class},(v,c,x)->{if(c.getName().equals("unhook"))hooks.remove(target);return null;});});});
  Object invoke(Executable target,Object owner,Object...args)throws Throwable{XposedInterface.Hooker hook=hooks.get(target);if(hook==null)return original(target,owner,args);return hook.intercept((XposedInterface.Chain)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{XposedInterface.Chain.class},(p,m,a)->{switch(m.getName()){case "getThisObject":return owner;case "getExecutable":return target;case "getArg":return args[(Integer)a[0]];case "getArgs":return Arrays.asList(args);case "proceed":return original(target,owner,args);default:return null;}}));}
  Object original(Executable target,Object owner,Object[] args)throws Throwable{if(target instanceof Constructor)return null;try{return ((Method)target).invoke(owner,args);}catch(InvocationTargetException e){throw e.getCause();}}
 }
}
