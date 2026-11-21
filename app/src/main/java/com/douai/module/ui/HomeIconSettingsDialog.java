package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.HomeUiCustomization;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Two-state editors own their asynchronous previews and pending configuration publication. */
final class HomeIconSettingsDialog implements AutoCloseable {
    interface Files {
        InputStream read(Uri uri) throws Exception;
        default InputStream builtIn(BuiltInNavigationIcons.Entry entry,BuiltInNavigationIcons.Style style) throws Exception {
            return BuiltInNavigationIcons.open(entry,style);
        }
    }
    interface Picker { AutoCloseable launch(Activity activity,Intent intent,Consumer<Uri> selected); }
    private interface Source { InputStream open() throws Exception; }
    private static final IconState[] EDITABLE_STATES={IconState.DAY_NORMAL,IconState.DAY_SELECTED};
    private static final ThreadPoolExecutor WORKER=executor("DouAi-home-icons",4);
    private static final ThreadPoolExecutor CLOSER=executor("DouAi-home-icons-close",4);
    private final Context context;
    private final SettingsStore store;
    private final boolean miuix;
    private final BiConsumer<Dialog,Runnable> presenter;
    private final Files files;
    private final Picker picker;
    private final Executor worker;
    private final Activity activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout rows;
    private final TextView status;
    private final AlertDialog dialog;
    private AutoCloseable selection;
    private Operation operation;
    private Editor editor;
    private long generation;
    private volatile boolean closed;

    HomeIconSettingsDialog(Context context,SettingsStore store,boolean miuix,BiConsumer<Dialog,Runnable> presenter) {
        this(context,store,miuix,presenter,uri->context.getContentResolver().openInputStream(uri),HostConfigFilePicker::launch,WORKER);
    }

    HomeIconSettingsDialog(Context baseContext,SettingsStore store,boolean miuix,BiConsumer<Dialog,Runnable> presenter,
                           Files files,Picker picker,Executor worker) {
        context=ModuleTheme.themedContext(baseContext);this.store=store;this.miuix=miuix;
        this.presenter=presenter;this.files=files;this.picker=picker;this.worker=worker;activity=activity(context);
        LinearLayout body=column();body.setPadding(dp(12),dp(4),dp(12),dp(12));
        body.addView(text("每个位置可分别设置未选中、已选中两态图标，日夜共用，并查看实际 WebP 预览。内置图标按位置匹配未选中线框和选中实心图形，可一键保存；已保存的旧图标、自定义图标与夜间数据在主动应用前保持原样。导入的 SVG / WebP 保留原色。SVG 源文件不超过 256 KiB，最长边转为256像素；最终静态 WebP 不超过32 KiB。导航项可整项隐藏或显示；右侧 Feed 仅保留图片替换。",13),matchWrap());
        status=text("请选择图标位置。上导航保留最近识别的首页频道；未出现时，请先返回首页，再从抖音内打开此页。",13);
        status.setTag("home.icons.status");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0,dp(12),0,dp(8));body.addView(status,matchWrap());
        rows=column();body.addView(rows,matchWrap());
        dialog=builder("首页图标").setView(scrolling(body)).setNegativeButton("返回",null).create();
        dialog.setOnDismissListener(ignored->close());render();
    }

    AlertDialog dialog(){return dialog;}

    private void render() {
        if(closed)return;
        String json;
        try{json=json();}catch(RuntimeException error){json=null;setStatus("无法读取图标配置，原图标已保留。");}
        JSONObject saved;
        try{saved=new JSONObject(json==null?"{}":json);}catch(Exception error){saved=new JSONObject();}
        String hiddenJson="[]";Set<String> hidden=Collections.emptySet();
        try{hiddenJson=hiddenJson();hidden=HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson);}
        catch(RuntimeException error){hiddenJson="[]";setStatus("无法读取导航显示设置，请重新打开；当前设置已保留。");}
        rows.removeAllViews();
        for(HomeUiCustomization.Target target:HomeUiCustomization.availableTargets(json,hiddenJson)) {
            boolean legacySelected=("feed_like".equals(target.key) || "feed_collect".equals(target.key))
                    && saved.has(target.key+"_selected");
            boolean custom=saved.has(target.key) || legacySelected;
            if(!custom && target.navigation)for(java.util.Iterator<String> keys=saved.keys();keys.hasNext();) {
                if(target.key.equals(HomeUiCustomizationSettings.canonicalNavigationKey(keys.next()))){custom=true;break;}
            }
            String value=target.navigation && hidden.contains(target.key)?"已隐藏 · 可显示与编辑":custom?"已配置 · 双态编辑与预览":"原图 · 双态编辑与预览";
            View row;
            if(miuix)row=new MiuixSettingsViews.Option(context,target.title,"",value,true,()->choose(target)).getView();
            else {
                LinearLayout nativeRow=column();nativeRow.setMinimumHeight(dp(56));
                nativeRow.setPadding(dp(12),dp(10),dp(12),dp(10));
                nativeRow.addView(text(target.title,15),matchWrap());nativeRow.addView(text(value,13),matchWrap());
                nativeRow.setBackgroundResource(android.R.drawable.list_selector_background);
                nativeRow.setOnClickListener(ignored->choose(target));row=nativeRow;
            }
            row.setTag("home.icons.target."+target.key);rows.addView(row,matchWrap());
        }
        if(editor!=null)editor.refreshVisibility();
    }

    private void choose(HomeUiCustomization.Target target) {
        if(closed)return;stop();
        if(editor!=null){Editor old=editor;editor=null;old.close();old.dialog.dismiss();}
        Editor next=new Editor(target);editor=next;presenter.accept(next.dialog,next::close);next.load();
    }

    private void chooseState(Editor owner,IconState state) {
        if(!owner.live())return;long ticket=generation;
        AtomicBoolean chosen=new AtomicBoolean();
        String[] actions=BuiltInNavigationIcons.entryForPosition(owner.target.key)==null
                ?new String[]{"导入 SVG / WebP","恢复此状态原图"}
                :new String[]{"导入 SVG / WebP","恢复此状态原图","使用内置图标并保存"};
        Dialog choice=builder(owner.target.title+" · "+stateTitle(state))
                .setItems(actions,(selected,which)->{
                    if(!owner.live() || !current(ticket) || !chosen.compareAndSet(false,true)){selected.dismiss();return;}
                    selected.dismiss();
                    if(which==0)importIcon(owner.target,state);
                    else if(which==1)restoreIcon(owner.target,state);
                    else if(which==2)applyBuiltIn(owner,state);
                }).setNegativeButton("取消",null).create();
        presenter.accept(choice,()->chosen.set(true));
    }

    private void applyBuiltIn(Editor owner,IconState state) {
        if(!owner.live())return;
        BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition(owner.target.key);
        if(entry==null){setStatus("此位置暂无内置图标，可导入 SVG / WebP 自定义。");return;}
        stop();long ticket=generation;
        if(state==null) {
            Map<IconState,Source> sources=new EnumMap<>(IconState.class);
            for(IconState variant:EDITABLE_STATES)sources.put(variant,()->files.builtIn(entry,BuiltInNavigationIcons.styleFor(variant)));
            queue(owner.target,sources,false,ticket,true);
        }else queue(owner.target,state,()->files.builtIn(entry,BuiltInNavigationIcons.styleFor(state)),ticket,true);
    }

    private void importIcon(HomeUiCustomization.Target target,IconState state) {
        stop();if(closed)return;long ticket=generation;
        if(activity==null || activity.isFinishing() || activity.isDestroyed()) {
            setStatus("当前页面无法打开文件选择器，请重新打开设置。");return;
        }
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/webp","image/svg+xml","application/xml","text/xml","application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setStatus("请选择 SVG 或静态 WebP：SVG 源文件不超过256 KiB，WebP 不超过32 KiB。");
        AtomicBoolean received=new AtomicBoolean();AutoCloseable launched;
        try {
            launched=picker.launch(activity,intent,uri->{
                received.set(true);Runnable result=()->{
                    if(!current(ticket))return;selection=null;
                    if(uri==null){setStatus("已取消选择，原图标已保留。");return;}
                    queue(target,state,()->files.read(uri),ticket,false);
                };
                if(Looper.myLooper()==main.getLooper())result.run();else main.post(result);
            });
        }catch(RuntimeException failure){setStatus("无法打开文件选择器，原图标已保留。");return;}
        if(!current(ticket) || received.get())quietlyClose(launched);else selection=launched;
        if(launched==null && !received.get())setStatus("无法打开文件选择器，请重新打开设置。");
    }

    private void restoreIcon(HomeUiCustomization.Target target,IconState state) {
        stop();if(!closed)queue(target,state,null,generation,false);
    }

    private void queue(HomeUiCustomization.Target target,IconState state,Source source,long ticket,boolean builtIn) {
        Map<IconState,Source> sources=new EnumMap<>(IconState.class);sources.put(state,source);
        queue(target,sources,false,ticket,builtIn);
    }

    private void restoreAll(HomeUiCustomization.Target target) {
        stop();if(!closed)queue(target,null,true,generation,false);
    }

    private void toggleVisibility(Editor owner) {
        if(!owner.live() || !owner.target.navigation)return;
        stop();long ticket=generation;String previous;boolean hide;
        try {
            previous=hiddenJson();
            hide=!HomeUiCustomizationSettings.readHiddenNavigation(previous).contains(owner.target.key);
        }catch(RuntimeException failure){setStatus("无法读取导航显示设置，请重新打开；原设置已保留。");return;}
        Operation pending=new Operation();operation=pending;
        setStatus(hide?"正在隐藏此导航项…":"正在显示此导航项…");
        pending.future=new FutureTask<>(()->{
            try {
                pending.check();String next=HomeUiCustomizationSettings.setNavigationHidden(previous,owner.target.key,hide);pending.check();
                main.post(()->publishVisibility(pending,ticket,previous,next,hide,owner.target.title));
            }catch(CancellationException ignored) {
            }catch(Exception | OutOfMemoryError failure) {
                String detail=failure instanceof IllegalArgumentException && failure.getMessage()!=null?failure.getMessage():"无法保存导航显示设置，请重试";
                main.post(()->{if(current(ticket) && operation==pending){operation=null;setStatus(detail+"；原设置已保留。");}});
            }
            return null;
        });
        try{worker.execute(pending.future);}catch(RuntimeException busy){pending.cancel();operation=null;setStatus("导航显示设置繁忙，请稍后重试；原设置已保留。");}
    }

    private void publishVisibility(Operation pending,long ticket,String previous,String next,boolean hide,String title) {
        if(!current(ticket) || operation!=pending || pending.cancelled)return;
        operation=null;
        try {
            if(!previous.equals(hiddenJson())){setStatus("导航显示设置已变化，请重新操作；当前设置已保留。");render();return;}
            if(!next.equals(previous))store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,next);
            if(!current(ticket))return;
            setStatus((hide?"已隐藏导航项 · ":"已显示导航项 · ")+title+"；图标设置保持不变。");render();
        }catch(RuntimeException failure){if(!closed)setStatus("保存导航显示设置失败，请重试；原设置已保留。");}
    }

    private void queue(HomeUiCustomization.Target target,Map<IconState,Source> sources,boolean restoreAll,long ticket,boolean builtIn) {
        String previous;
        try{previous=json();}catch(RuntimeException failure){setStatus("无法读取图标配置，原图标已保留。");return;}
        Operation pending=new Operation();operation=pending;
        boolean restore=restoreAll || sources.values().iterator().next()==null;
        setStatus(restore?"正在恢复原图…":builtIn?"正在读取内置图标并生成实际预览…":"正在读取并检查图标…");
        pending.future=new FutureTask<>(()->{
            PreviewSet ready=null;
            try {
                pending.check();String next;boolean conversion=false;
                if(restoreAll)next=HomeUiCustomizationSettings.removeIcon(previous,target.key);
                else if(restore)next=HomeUiCustomizationSettings.removeIcon(previous,target.key,sources.keySet().iterator().next());
                else {
                    Map<IconState,byte[]> converted=new EnumMap<>(IconState.class);
                    for(Map.Entry<IconState,Source> variant:sources.entrySet()) {
                        pending.check();byte[] input=readBounded(pending,variant.getValue());
                        byte[] output=HomeIconImport.convert(input);conversion|=output!=input;converted.put(variant.getKey(),output);
                    }
                    next=HomeUiCustomizationSettings.putIcons(previous,target.key,converted);
                }
                pending.check();
                String message=(restore?"已恢复原图设置 · ":builtIn?"已保存内置图标 · ":conversion?"已转换并导入 · ":"已导入 · ")+target.title+"；返回首页查看。";
                if(builtIn)ready=PreviewSet.decode(next,target.key,pending);
                pending.check();PreviewSet previews=ready;ready=null;
                if(!main.post(()->publish(pending,ticket,previous,next,message,previews)))quietlyClose(previews);
            }catch(CancellationException ignored) {
            }catch(Exception | OutOfMemoryError failure) {
                String detail=reason(failure);
                main.post(()->{if(current(ticket) && operation==pending){operation=null;setStatus(detail+"；已保留原图标。");}});
            }finally{quietlyClose(ready);}
            return null;
        });
        try{worker.execute(pending.future);}catch(RuntimeException full){pending.cancel();operation=null;setStatus("图标读取繁忙，请稍后重试；已保留原图标。");}
    }

    private byte[] readBounded(Operation pending,Source source) throws Exception {
        try(InputStream stream=pending.attach(source.open());ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            if(stream==null)throw new java.io.IOException("Missing document stream");
            byte[] buffer=new byte[4096];int total=0;
            while(true) {
                pending.check();int count=stream.read(buffer,0,Math.min(buffer.length,HomeIconImport.MAX_INPUT_BYTES+1-total));
                if(count<0)break;
                if(count==0){int single=stream.read();if(single<0)break;output.write(single);total++;}
                else{output.write(buffer,0,count);total+=count;}
                if(total>HomeIconImport.MAX_INPUT_BYTES)throw new IllegalArgumentException("SVG 源文件不能超过256 KiB；静态 WebP 不能超过32 KiB");
            }
            return output.toByteArray();
        }finally{pending.detach();}
    }

    private void publish(Operation pending,long ticket,String previous,String next,String message,PreviewSet previews) {
        try {
            if(!current(ticket) || operation!=pending || pending.cancelled)return;
            operation=null;
            if(!previous.equals(json())){setStatus("图标设置已变化，请重新操作；当前图标已保留。");return;}
            if(!next.equals(previous))store.putString(HomeUiCustomizationSettings.ICONS,next);
            if(!current(ticket))return;
            setStatus(message);render();
            if(editor!=null) {
                if(previews!=null && editor.target.key.equals(previews.key) && next.equals(json())) {
                    editor.showSaved(previews);previews=null;
                }else editor.load();
            }
        }catch(RuntimeException failure){if(!closed)setStatus("保存失败，请重试；已保留原图标。");}
        finally{quietlyClose(previews);}
    }

    private final class Editor implements AutoCloseable {
        final HomeUiCustomization.Target target;
        final PreviewGrid grid;
        final TextView notice;
        final TextView visibilityStatus;
        final Button visibilityButton;
        final AlertDialog dialog;
        Operation previewOperation;
        boolean disposed;
        Editor(HomeUiCustomization.Target target) {
            this.target=target;LinearLayout body=column();body.setPadding(dp(12),dp(8),dp(12),dp(12));
            body.addView(text("内置图标按当前位置匹配未选中线框和选中实心图形。两态日夜共用；已保存的旧图标、自定义图片与夜间兼容数据在主动应用前保持原样。点按可导入保留原色的自定义图标或恢复原图。下方显示已保存图片的实际 WebP 预览。",13),matchWrap());
            if(target.navigation) {
                visibilityStatus=text("",13);visibilityStatus.setTag("home.icons.visibility.status");
                visibilityStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(visibilityStatus,matchWrap());
                visibilityButton=action("隐藏此导航项","home.icons.visibility",()->toggleVisibility(this));body.addView(visibilityButton,matchWrap());
            }else {visibilityStatus=null;visibilityButton=null;}
            grid=new PreviewGrid(state->chooseState(this,state));body.addView(grid.view,matchWrap());
            Button builtIn=action("使用内置图标并保存","home.icons.builtin.all",()->applyBuiltIn(this,null));
            builtIn.setEnabled(BuiltInNavigationIcons.entryForPosition(target.key)!=null);body.addView(builtIn,matchWrap());
            if(!builtIn.isEnabled()) {
                TextView unavailable=text("此位置暂无内置图标，可导入 SVG / WebP 自定义。",13);unavailable.setTag("home.icons.builtin.unavailable");body.addView(unavailable,matchWrap());
            }
            body.addView(action("恢复此位置原图","home.icons.restore.all",()->restoreAll(target)),matchWrap());
            if(target.navigation)body.addView(text("恢复此位置原图只恢复图片，不改变此导航项的隐藏状态。",13),matchWrap());
            notice=text("预览显示当前配置；保存后请返回首页查看。",13);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(notice,matchWrap());
            dialog=builder(target.title).setView(scrolling(body)).setNegativeButton("返回",null).create();
            grid.restoreColors();dialog.setOnDismissListener(ignored->close());refreshVisibility();
        }
        boolean live(){return !closed && !disposed && editor==this;}
        void refreshVisibility() {
            if(visibilityButton==null)return;
            try {
                boolean hidden=HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).contains(target.key);
                visibilityButton.setEnabled(true);visibilityButton.setText(hidden?"显示此导航项":"隐藏此导航项");
                visibilityStatus.setText(hidden?"显示设置：此导航项已隐藏（图标、文字和点击区）。显示后仍使用下方已保存的双态图标。":"显示设置：未隐藏此导航项。隐藏会移除图标、文字和点击区；图标设置保留。");
            }catch(RuntimeException failure){visibilityButton.setEnabled(false);visibilityStatus.setText("无法读取导航显示设置，请重新打开；当前设置已保留。");}
        }
        void showSaved(PreviewSet previews) {
            if(previewOperation!=null){previewOperation.cancel();previewOperation=null;}
            try{grid.bind(previews,"已保存 WebP");}
            catch(RuntimeException failure){grid.failed("预览不可用，请重新打开。");throw failure;}
        }
        void load() {
            if(!live())return;
            if(previewOperation!=null)previewOperation.cancel();grid.loading();
            String snapshot;
            try{snapshot=json();}catch(RuntimeException failure){grid.failed("无法读取配置，请重新打开。");return;}
            Operation pending=new Operation();previewOperation=pending;
            pending.future=new FutureTask<>(()->{
                PreviewSet ready=null;
                try {
                    ready=PreviewSet.decode(snapshot,target.key,pending);pending.check();
                    PreviewSet result=ready;ready=null;
                    if(!main.post(()->{
                        if(!live() || previewOperation!=pending || pending.cancelled){result.close();return;}
                        previewOperation=null;
                        try {
                            if(!snapshot.equals(json())){result.close();load();return;}
                            grid.bind(result,"已保存 WebP");
                        }catch(RuntimeException failure){result.close();grid.failed("预览不可用，请重新打开。");}
                    }))result.close();
                }catch(CancellationException ignored) {
                }catch(Exception | OutOfMemoryError failure) {
                    String detail=reason(failure);
                    main.post(()->{if(live() && previewOperation==pending){previewOperation=null;grid.failed(detail);notice.setText(detail);}});
                }finally{quietlyClose(ready);}
                return null;
            });
            try{worker.execute(pending.future);}catch(RuntimeException busy){pending.cancel();previewOperation=null;grid.failed("预览繁忙，请稍后重试。");}
        }
        @Override public void close() {
            if(disposed)return;disposed=true;
            if(previewOperation!=null){previewOperation.cancel();previewOperation=null;}
            grid.close();if(editor==this){editor=null;stop();}
        }
    }

    @android.annotation.SuppressLint("SetTextI18n") // State/source labels follow the existing Chinese-only settings surface.
    private final class PreviewGrid implements AutoCloseable {
        final LinearLayout view=column();
        final Map<IconState,LinearLayout> cells=new EnumMap<>(IconState.class);
        final Map<IconState,TextView> titles=new EnumMap<>(IconState.class), details=new EnumMap<>(IconState.class);
        final Map<IconState,ImageView> images=new EnumMap<>(IconState.class);
        PreviewSet held;
        PreviewGrid(Consumer<IconState> clicked) {
            LinearLayout line=new LinearLayout(context);line.setOrientation(LinearLayout.HORIZONTAL);
            view.addView(line,matchWrap());
            for(IconState state:EDITABLE_STATES) {
                LinearLayout cell=column();cell.setGravity(Gravity.CENTER);cell.setMinimumHeight(dp(124));
                cell.setPadding(dp(8),dp(8),dp(8),dp(8));cell.setTag("home.icons.state."+state.key);
                ModuleTheme.preserveColors(cell);cells.put(state,cell);
                TextView title=text(stateTitle(state),14);title.setGravity(Gravity.CENTER);titles.put(state,title);cell.addView(title,matchWrap());
                ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                image.setTag("home.icons.preview."+state.key);images.put(state,image);
                LinearLayout.LayoutParams imageSize=new LinearLayout.LayoutParams(dp(36),dp(36));
                imageSize.topMargin=dp(8);imageSize.bottomMargin=dp(4);cell.addView(image,imageSize);
                TextView detail=text("正在读取预览…",12);detail.setGravity(Gravity.CENTER);detail.setMinLines(2);
                detail.setTag("home.icons.preview.status."+state.key);details.put(state,detail);cell.addView(detail,matchWrap());
                if(clicked!=null){cell.setFocusable(true);cell.setContentDescription(stateTitle(state)+"，编辑图标");cell.setOnClickListener(ignored->clicked.accept(state));}
                LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(0,-2,1);size.setMargins(dp(4),dp(8),dp(4),0);line.addView(cell,size);
            }
            restoreColors();
        }
        void restoreColors() {
            for(IconState state:EDITABLE_STATES) {
                cells.get(state).setBackgroundColor(0xff20252b);
                titles.get(state).setTextColor(0xffffffff);
                details.get(state).setTextColor(0xffd9e0e5);
            }
        }
        void loading(){clear();for(TextView detail:details.values())detail.setText("正在读取预览…");}
        void failed(String message){clear();for(TextView detail:details.values())detail.setText(message);}
        void bind(PreviewSet next,String source) {
            clear();held=next;
            for(IconState state:EDITABLE_STATES) {
                HomeIconPreview preview=next.images.get(state);ImageView image=images.get(state);
                if(preview==null)image.setImageDrawable(null);else image.setImageBitmap(preview.bitmap());
                image.setVisibility(preview==null?View.GONE:View.VISIBLE);
                image.setContentDescription(stateTitle(state)+" · "+source);
                details.get(state).setText(preview==null?"宿主原图\n无法读取预览":source);
            }
            restoreColors();
        }
        private void clear() {
            for(ImageView image:images.values()){image.setImageDrawable(null);image.setVisibility(View.GONE);}
            if(held!=null){held.close();held=null;}
        }
        @Override public void close(){clear();}
    }

    private static final class PreviewSet implements AutoCloseable {
        final String key;
        final Map<IconState,HomeIconPreview> images=new EnumMap<>(IconState.class);
        PreviewSet(String key){this.key=key;}
        static PreviewSet decode(String json,String key,Operation pending) {
            PreviewSet result=new PreviewSet(key);
            try {
                pending.check();Map<IconState,String> variants=HomeUiCustomizationSettings.readEncodedIcons(json).get(key);
                for(IconState state:EDITABLE_STATES) {
                    pending.check();String encoded=variants==null?null:variants.get(state);
                    if(encoded!=null)result.images.put(state,HomeIconPreview.decode(Base64.getDecoder().decode(encoded)));
                }
                pending.check();return result;
            }catch(RuntimeException | Error failure){result.close();throw failure;}
        }
        @Override public void close(){for(HomeIconPreview image:images.values())image.close();images.clear();}
    }

    private String json(){return store.getString(HomeUiCustomizationSettings.ICONS,"{}");}
    private String hiddenJson(){return store.getString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[]");}
    private boolean current(long ticket){return !closed && ticket==generation;}
    private void setStatus(String value){status.setText(value);if(editor!=null)editor.notice.setText(value);}
    private void stop() {
        generation++;quietlyClose(selection);selection=null;
        if(operation!=null){operation.cancel();operation=null;}
    }
    @Override public void close() {
        if(closed)return;closed=true;stop();
        if(editor!=null){Editor old=editor;editor=null;old.close();old.dialog.dismiss();}
    }
    private MiuixDialogBuilder builder(String title){MiuixDialogBuilder builder=new MiuixDialogBuilder(context,miuix);builder.setTitle(title);return builder;}
    private static String stateTitle(IconState state){return state.selected?"已选中":"未选中";}
    private TextView text(String value,int size){TextView text=new TextView(context);text.setText(value);text.setTextSize(size);text.setTextColor(ModuleTheme.color(context,0xff313b43));return text;}
    private Button action(String title,String tag,Runnable clicked){Button button=new Button(context);button.setText(title);button.setAllCaps(false);button.setTag(tag);button.setMinHeight(dp(48));button.setOnClickListener(ignored->clicked.run());return button;}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(context);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private ScrollView scrolling(View child){ScrollView scroll=new ScrollView(context);scroll.addView(child);return scroll;}
    private static LinearLayout.LayoutParams matchWrap(){return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private static String reason(Throwable failure){return failure instanceof IllegalArgumentException && failure.getMessage()!=null?failure.getMessage():"无法读取或预览图标，请重试";}
    private static Activity activity(Context context) {
        for(int i=0;i<20 && context!=null;i++) {
            if(context instanceof Activity)return (Activity)context;
            if(!(context instanceof ContextWrapper))return null;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)return null;context=next;
        }
        return null;
    }
    private static ThreadPoolExecutor executor(String name,int capacity) {
        return new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;});
    }
    private static void quietlyClose(AutoCloseable closeable){if(closeable!=null)try{closeable.close();}catch(Exception ignored){}}
    private static final class Operation {
        FutureTask<Void> future;volatile boolean cancelled;private InputStream stream;
        void check(){if(cancelled || Thread.currentThread().isInterrupted())throw new CancellationException();}
        synchronized InputStream attach(InputStream opened) {
            if(cancelled){quietlyClose(opened);throw new CancellationException();}stream=opened;return opened;
        }
        synchronized void detach(){stream=null;}
        void cancel() {
            cancelled=true;if(future!=null){future.cancel(true);WORKER.remove(future);}
            InputStream opened;synchronized(this){opened=stream;stream=null;}
            if(opened!=null)try{CLOSER.execute(()->quietlyClose(opened));}catch(RuntimeException ignored){/* The reader still closes its stream in finally. */}
        }
    }
}
