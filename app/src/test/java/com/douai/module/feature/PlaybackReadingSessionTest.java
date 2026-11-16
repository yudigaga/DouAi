package com.douai.module.feature;
import android.app.*;
import android.content.Intent;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;
import com.douai.module.config.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(org.robolectric.RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class PlaybackReadingSessionTest {
 Class<?> type;Object session;PickerActivity owner;AtomicBoolean foreground=new AtomicBoolean(true),stable=new AtomicBoolean(true);
 @Before public void setup()throws Exception {
  owner=Robolectric.buildActivity(PickerActivity.class).setup().get();type=Class.forName(PlaybackReadingDialog.class.getName()+"$Session");
  Constructor<?> ctor=type.getDeclaredConstructor(Activity.class,Object.class,java.util.function.BooleanSupplier.class,java.util.function.BooleanSupplier.class,java.util.function.LongPredicate.class);ctor.setAccessible(true);
  session=ctor.newInstance(owner,new Work(),(java.util.function.BooleanSupplier)foreground::get,(java.util.function.BooleanSupplier)stable::get,(java.util.function.LongPredicate)ms->false);
 }
 @After public void close()throws Exception {call("close");owner.finish();}
 Object get(String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(session);}
 void set(String name,Object v)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(session,v);}
 Object call(String name)throws Exception{Method m=type.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(session);}
 @Test public void safPauseKeepsSameDocumentUntilRealOwnershipChanges()throws Exception {
  PlaybackSubtitleDocument doc=new PlaybackSubtitleDocument("a","v","t",1,List.of(new PlaybackReadingText.Line(1,"原句")));set("document",doc);
  Method save=type.getDeclaredMethod("save",PlaybackSubtitleDocument.class,boolean.class);save.setAccessible(true);save.invoke(session,doc,false);
  assertNotNull(get("picker"));foreground.set(false);((Runnable)get("watch")).run();
  assertFalse("normal system picker pause must keep document alive",(Boolean)get("closed"));
  stable.set(false);((Runnable)get("watch")).run();assertTrue((Boolean)get("closed"));
 }
 @Test public void oldModelChooserCannotIndexReplacedModelList()throws Exception {
  AiModelConfig a=AiModelConfig.fromJson(new JSONObject().put("id","a").put("name","A"));
  AiModelConfig b=AiModelConfig.fromJson(new JSONObject().put("id","b").put("name","B"));
  HostSettingsStore store=(HostSettingsStore)get("settings");store.putString(AiModelSettings.KEY,new AiModelSettings(List.of(a,b),"a").serialize());
  call("model");AlertDialog chooser=(AlertDialog)get("child");
  store.putString(AiModelSettings.KEY,new AiModelSettings(List.of(a),"a").serialize());call("readModels");
  chooser.getListView().performItemClick(null,1,1);
  assertEquals("a",get("selectedModel"));
 }
 public static class Work {public String getAid(){return "v";}public Object getMoreChapterList(){return null;}}
 public static class PickerActivity extends Activity {
  public final ActivityResultRegistry registry=new ActivityResultRegistry(){public <I,O>void onLaunch(int code,ActivityResultContract<I,O> contract,I input,ActivityOptionsCompat options){}};
  public ActivityResultRegistry getActivityResultRegistry(){return registry;}
 }
}
