package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.LocalOptions;
import com.douai.module.ui.BuiltInNavigationIcons;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Window-owned static icons and navigation labels; host values are restored only while owned. */
public final class HomeUiCustomization implements AutoCloseable {
    private static final String TOP = "com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew";
    private static final String BOTTOM = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab";
    private static final String TOP_CORNER = "com.ss.android.ugc.aweme.homepage.topcorner.ui.TopCornerEntranceView";
    private static final String MAIN_TITLE_BAR = "com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar";
    private static final HomeNavigationMetadata NAVIGATION_METADATA=new HomeNavigationMetadata();
    private static final HostReflectionCache ACCESS=new HostReflectionCache();
    private final View root;
    private final Consumer<String> log;
    private final Map<String,Asset> assets = new HashMap<>();
    private final Map<ImageView,ImageState> images = new IdentityHashMap<>();
    private final Map<TextView,LabelState> labels = new IdentityHashMap<>();
    private final Map<View,NavigationState> navigation = new IdentityHashMap<>();
    private final Map<ViewGroup,NavigationGroup> navigationGroups = new IdentityHashMap<>();
    private final Map<View,NavigationItemVisibility> hiddenItems = new IdentityHashMap<>();
    private final Set<String> logged = new HashSet<>();
    private Map<String,Map<IconState,String>> icons = Collections.emptyMap();
    private Set<String> hiddenNavigation = Collections.emptySet();
    private Map<TextView,View> foundLabels = Collections.emptyMap();
    private String rawJson;
    private String rawHiddenJson;
    private boolean hideLabels, closed, refreshing;
    private int configurationRevision,observations,missingAssets,slowReports;
    private boolean discoveryLimited;
    private long imageWrites,titleWrites,nativeGeneration,nextObservationAt;
    private String lastObservation="";

    public static final class Target {
        public final String key, title;
        public final boolean navigation;
        private Target(String key, String title, boolean navigation) {
            this.key = key; this.title = title; this.navigation = navigation;
        }
    }

    public HomeUiCustomization(View root, LocalOptions options, Consumer<String> log) {
        this(root,options,log,false);
    }

    /** The controller discovers once, after installing all of its window observers. */
    HomeUiCustomization(View root,LocalOptions options,Consumer<String> log,boolean deferDiscovery) {
        if (root == null) throw new IllegalArgumentException("Missing customization root");
        this.root = root;
        this.log = log == null ? ignored -> { } : log;
        if(readOptions(options) && !deferDiscovery)refresh();
    }

    public void setOptions(LocalOptions options) {
        if(readOptions(options))refresh();
    }

    /** A controller publication follows this immediately with its single window refresh. */
    void setOptionsDeferred(LocalOptions options) { readOptions(options); }

    private boolean readOptions(LocalOptions options) {
        if (closed) return false;
        long workStarted=slowReports<6?SystemClock.elapsedRealtimeNanos():0;
        try {
            String json = options == null ? "{}" : options.text(HomeUiCustomizationSettings.ICONS, "{}");
            String hidden = options == null ? "[]" : options.text(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[]");
            boolean hide = options != null && options.enabled(HomeUiCustomizationSettings.HIDE_LABELS);
            boolean changed=false;
            if(!hidden.equals(rawHiddenJson)) {
                try {
                    Set<String> next=HomeUiCustomizationSettings.readHiddenNavigation(hidden);
                    changed=!next.equals(hiddenNavigation);hiddenNavigation=next;rawHiddenJson=hidden;
                }catch(IllegalArgumentException error){once("home_ui_customization: invalid_navigation_visibility");}
            }
            if (!json.equals(rawJson)) {
                try {
                    icons = HomeUiCustomizationSettings.readEncodedIcons(json);
                    rawJson = json;changed=true;
                } catch (Exception error) {
                    once("home_ui_customization: invalid_icon_configuration");
                    // An invalid artwork edit must not prevent restoring an independently hidden item.
                    if(changed)configurationRevision++;
                    return changed;
                }
            }
            if(hideLabels!=hide){hideLabels=hide;changed=true;}
            if(changed)configurationRevision++;
            return changed;
        } finally { reportSlowWork("configuration",workStarted); }
    }

    public void refresh() {
        refresh(LiquidGlassTargets.snapshot(root));
    }

    void refresh(LiquidGlassTargets.TreeSnapshot snapshot) {
        if (closed || refreshing) return;
        if(snapshot==null || snapshot.root!=root)snapshot=LiquidGlassTargets.snapshot(root);
        refreshing = true;
        long workStarted=slowReports<6?SystemClock.elapsedRealtimeNanos():0;
        try {
            nativeGeneration++;
            Set<String> wanted=encodedAssets();
            for(String encoded:wanted)asset(encoded);
            missingAssets=0;for(String encoded:wanted)if(!assets.containsKey(encoded))missingAssets++;
            List<View> nodes=snapshot.nodes;
            discoveryLimited=snapshot.truncated || nodes.size()>=4096;
            boolean complete=!discoveryLimited;
            Set<ImageView> foundImages = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<View> foundNavigation=Collections.newSetFromMap(new IdentityHashMap<>());
            Set<View> foundHidden=Collections.newSetFromMap(new IdentityHashMap<>());
            Map<TextView,View> currentLabels = new IdentityHashMap<>();
            for (View view : nodes) {
                if(LiquidGlassTargets.opaque(view))continue;
                if(hasClass(view,TOP_CORNER)) {
                    TopCornerTarget target=topCornerTarget(view);
                    if(target==null || !hiddenNavigation.contains(target.key)) {
                        NavigationItemVisibility previousHidden=hiddenItems.remove(view);
                        if(previousHidden!=null)previousHidden.close();
                    }
                    if(target!=null) {
                        NAVIGATION_METADATA.observe(target.key,target.key.equals("nav_top:homepage_right_search")?"搜索":"菜单",
                                view.isShown() && view.getWindowVisibility()==View.VISIBLE);
                        if(hiddenNavigation.contains(target.key)) {
                            foundHidden.add(view);
                            NavigationItemVisibility hidden=hiddenItems.get(view);
                            if(hidden==null){hidden=new NavigationItemVisibility(view);hiddenItems.put(view,hidden);}
                            hidden.hide();
                        }else if(configured(target.key))bindImage(target.image,target.key,view,foundImages);
                    }
                    continue;
                }
                boolean top = hasClass(view, TOP), bottom = hasClass(view, BOTTOM);
                if (top || bottom) {
                    try {
                        String tab = String.valueOf(view.getClass().getMethod(top ? "getCurrentTabId" : "getTabType").invoke(view));
                        Object content = view.getClass().getMethod(top ? "getContentView" : "getTabTitle").invoke(view);
                        if (!tab.isEmpty() && !"null".equals(tab)) {
                            String key = HomeUiCustomizationSettings.canonicalNavigationKey((top ? "nav_top:" : "nav_bottom:") + tab);
                            List<TextView> titles=navigationLabels(view,content,bottom);
                            observeNavigationMetadata(view,titles,content,key);
                            if(hiddenNavigation.contains(key)) {
                                foundHidden.add(view);
                                NavigationItemVisibility hidden=hiddenItems.get(view);
                                if(hidden==null){hidden=new NavigationItemVisibility(view);hiddenItems.put(view,hidden);}
                                hidden.hide();continue;
                            }
                            NavigationItemVisibility previousHidden=hiddenItems.remove(view);
                            if(previousHidden!=null)previousHidden.close();
                            foundNavigation.add(view);
                            NavigationState feedback=navigation.get(view);
                            if(feedback==null){feedback=new NavigationState(view);navigation.put(view,feedback);}
                            feedback.sources(titles,content instanceof View?(View)content:null);
                            for(TextView title:titles) {
                                currentLabels.put(title, view);
                                LabelState state = labels.get(title);
                                if (state == null) { state = new LabelState(title); labels.put(title, state); }
                                state.key=key; state.top=top;state.owner=view;
                                ImageView nativeIcon=bottom?nativeNavigationIcon(title):null;
                                state.externalIcon=nativeIcon!=null;
                                if(nativeIcon!=null && configured(key))bindImage(nativeIcon,key,view,title,foundImages);
                                if(state.apply(cached(view,title,key),top,hideLabels,state.externalIcon))titleWrites++;
                            }
                            if(content instanceof ImageView && within((View)content,view) && configured(key))
                                bindImage((ImageView)content,key,view,foundImages);
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        once("home_ui_customization: navigation_contract_unavailable");
                    }
                }
                if (!(view instanceof ImageView)) continue;
                ImageView image = (ImageView) view;
                if(insideHiddenNavigation(image))continue;
                IconState role=collectRole(image);
                String key = role!=null?"feed_collect":feedImageKey(image);
                if (key == null || !configured(key)) continue;
                if(role==null && "feed_collect".equals(key)) {
                    ImageState previous=images.get(image);
                    if(previous!=null && previous.owner==null && previous.originalId==0x7f0a2439 && "feed_collect".equals(previous.key)) {
                        previous.close();images.remove(image);continue;
                    }
                }
                bindImage(image,key,null,foundImages);
            }
            if(complete) {
                for(Iterator<Map.Entry<View,NavigationItemVisibility>> it=hiddenItems.entrySet().iterator();it.hasNext();) {
                    Map.Entry<View,NavigationItemVisibility> entry=it.next();
                    if(!foundHidden.contains(entry.getKey())){entry.getValue().close();it.remove();}
                }
                for (Iterator<Map.Entry<ImageView,ImageState>> it = images.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<ImageView,ImageState> entry = it.next();
                    if (!foundImages.contains(entry.getKey())) { entry.getValue().close(); it.remove(); }
                }
                for (Iterator<Map.Entry<TextView,LabelState>> it = labels.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<TextView,LabelState> entry = it.next();
                    if (!currentLabels.containsKey(entry.getKey())) { entry.getValue().close(); it.remove(); }
                }
                foundLabels=currentLabels;
                for(Iterator<Map.Entry<View,NavigationState>> it=navigation.entrySet().iterator();it.hasNext();) {
                    Map.Entry<View,NavigationState> entry=it.next();
                    if(!foundNavigation.contains(entry.getKey())){entry.getValue().close();it.remove();}
                }
                refreshNavigationGroups();
            } else {
                Map<TextView,View> retainedLabels=new IdentityHashMap<>();
                for(Map.Entry<TextView,View> entry:foundLabels.entrySet())
                    if(within(entry.getKey(),root))retainedLabels.put(entry.getKey(),entry.getValue());
                retainedLabels.putAll(currentLabels);
                foundLabels=retainedLabels;
            }
            syncNavigation();
            Set<Asset> retained=Collections.newSetFromMap(new IdentityHashMap<>());
            for(ImageState state:images.values())if(state.asset!=null)retained.add(state.asset);
            for (Iterator<Map.Entry<String,Asset>> it = assets.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String,Asset> entry = it.next();
                if (!wanted.contains(entry.getKey()) && !retained.contains(entry.getValue())) { entry.getValue().close(); it.remove(); }
            }
            report(true);
        } finally { refreshing = false; reportSlowWork("refresh",workStarted); }
    }

    /** Pre-draw ownership/state refresh; all discovery and decoding stays in refresh(). */
    public void sync() {
        if (closed || refreshing) return;
        refreshing=true;
        long workStarted=slowReports<6?SystemClock.elapsedRealtimeNanos():0;
        try {
            nativeGeneration++;
            for(Iterator<Map.Entry<View,NavigationItemVisibility>> it=hiddenItems.entrySet().iterator();it.hasNext();) {
                Map.Entry<View,NavigationItemVisibility> entry=it.next();
                View owner=entry.getKey();
                TopCornerTarget target=hasClass(owner,TOP_CORNER)?topCornerTarget(owner):null;
                if(!within(owner,root) || hasClass(owner,TOP_CORNER) && (target==null || !hiddenNavigation.contains(target.key))) {
                    entry.getValue().close();it.remove();
                }else entry.getValue().hide();
            }
            for (Iterator<Map.Entry<ImageView,ImageState>> it=images.entrySet().iterator();it.hasNext();) {
                Map.Entry<ImageView,ImageState> entry=it.next();
                ImageState state=entry.getValue();
                IconState role=collectRole(entry.getKey());
                boolean sameTarget;
                if(state.owner==null) {
                    sameTarget=role!=null && "feed_collect".equals(state.key)
                            || entry.getKey().getId()==state.originalId && state.key.equals(feedImageKey(entry.getKey()));
                }else if(hasClass(state.owner,TOP_CORNER)) {
                    TopCornerTarget target=topCornerTarget(state.owner);
                    sameTarget=target!=null && state.key.equals(target.key) && target.image==entry.getKey();
                }else sameTarget=within(state.owner,root) && within(entry.getKey(),state.owner) && within(state.selectionView,state.owner);
                if (!configured(state.key) || !sameTarget || !within(entry.getKey(),root)) { state.close(); it.remove(); }
                else {
                    Asset next=cached(state,role);
                    if(next==null)state.close();else if(state.apply(next))imageWrites++;
                }
            }
            for (Map.Entry<TextView,LabelState> entry:labels.entrySet()) {
                LabelState state=entry.getValue();
                if(within(entry.getKey(),root) && state.apply(cached(state.owner,entry.getKey(),state.key),state.top,hideLabels,state.externalIcon))titleWrites++;
            }
            syncNavigation();
            report(false);
        } finally { refreshing=false; reportSlowWork("sync",workStarted); }
    }

    public Collection<TextView> labels(View navigationOwner) {
        if (closed || navigationOwner == null) return Collections.emptyList();
        List<TextView> result = new ArrayList<>();
        for (TextView label : foundLabels.keySet()) if (within(label, navigationOwner)) result.add(label);
        return Collections.unmodifiableList(result);
    }

    public static List<Target> availableTargets() { return availableTargets(null); }

    /** Recent host metadata survives window close; a separate process only has defaults and saved keys. */
    public static List<Target> availableTargets(String iconJson) {
        return availableTargets(iconJson,"[]");
    }

    public static List<Target> availableTargets(String iconJson,String hiddenJson) {
        Map<String,Target> targets = new LinkedHashMap<>();
        add(targets,"feed_like","点赞",false);
        add(targets,"feed_comment","评论",false);
        add(targets,"feed_collect","收藏",false);
        add(targets,"feed_share","分享",false);
        add(targets,"feed_follow","头像关注",false);
        add(targets,"feed_mute","静音",false);
        List<String> savedKeys=new ArrayList<>();
        if (iconJson != null && iconJson.length()<=HomeUiCustomizationSettings.MAX_JSON_LENGTH) {
            try {
                // Discovery only reads bounded metadata; validation/decoding belongs to the actual consumer.
                JSONObject saved=new JSONObject(iconJson);
                Iterator<String> keys=saved.keys();
                for(int fields=0;keys.hasNext() && fields<HomeUiCustomizationSettings.MAX_ICONS+6;fields++)savedKeys.add(keys.next());
            } catch (Exception ignored) { }
        }
        try{savedKeys.addAll(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson));}
        catch(IllegalArgumentException ignored){ }
        for(var entry:NAVIGATION_METADATA.titles(savedKeys).entrySet())add(targets,entry.getKey(),entry.getValue(),true);
        return Collections.unmodifiableList(new ArrayList<>(targets.values()));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (ImageState state : images.values()) state.close();
        for (LabelState state : labels.values()) state.close();
        for(NavigationGroup group:navigationGroups.values())group.close();navigationGroups.clear();
        for(NavigationState state:navigation.values())state.close();navigation.clear();
        for(NavigationItemVisibility hidden:hiddenItems.values())hidden.close();hiddenItems.clear();
        images.clear(); labels.clear(); foundLabels = Collections.emptyMap();
        for (Asset asset : assets.values()) asset.close();
        assets.clear();
    }

    private Asset asset(String encoded) {
        if (encoded == null) return null;
        Asset cached = assets.get(encoded);
        if (cached != null) return cached;
        MediaMemoryBudget.Reservation reservation = null;
        try {
            byte[] bytes = BuiltInNavigationIcons.replaceLegacyArtwork(Base64.getDecoder().decode(encoded));
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 512 || bounds.outHeight > 512) return null;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while ((bounds.outWidth + options.inSampleSize - 1) / options.inSampleSize > 256
                    || (bounds.outHeight + options.inSampleSize - 1) / options.inSampleSize > 256) options.inSampleSize *= 2;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            reservation = MediaMemoryBudget.tryAcquire(bytes.length + 4L
                    * ((bounds.outWidth + options.inSampleSize - 1) / options.inSampleSize)
                    * ((bounds.outHeight + options.inSampleSize - 1) / options.inSampleSize));
            if (reservation == null) { once("home_ui_customization: image_budget_busy"); return null; }
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) return null;
            if (bitmap.getWidth()>256 || bitmap.getHeight()>256) { bitmap.recycle(); return null; }
            Asset asset = new Asset(bitmap, reservation); reservation = null;
            assets.put(encoded, asset);
            return asset;
        } catch (RuntimeException | OutOfMemoryError error) {
            once("home_ui_customization: icon_decode_failed");
            return null;
        } finally { if (reservation != null) reservation.close(); }
    }

    private static final class Asset implements AutoCloseable {
        final Bitmap bitmap;
        final MediaMemoryBudget.Reservation reservation;
        Asset(Bitmap bitmap, MediaMemoryBudget.Reservation reservation) { this.bitmap=bitmap; this.reservation=reservation; }
        Drawable drawable(View view) {
            return new BitmapDrawable(view.getResources(), bitmap) {
                @Override public boolean isStateful() { return true; }
                // Host TextView/ImageView tint applies to their original artwork, not user-authored RGBA pixels.
                @Override public void setTintList(ColorStateList tint) { }
                @Override public void setColorFilter(ColorFilter filter) { }
            };
        }
        Drawable drawable(ImageView view,String key) {
            int dp=key.startsWith("nav_top:")?20:24;
            int pixels=Math.max(1,Math.round(dp*view.getResources().getDisplayMetrics().density));
            return new FixedSizeBitmapDrawable(view,bitmap,pixels);
        }
        @Override public void close() { bitmap.recycle(); reservation.close(); }
    }

    private static final class FixedSizeBitmapDrawable extends BitmapDrawable {
        private final int size;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
        FixedSizeBitmapDrawable(ImageView view,Bitmap bitmap,int size) {
            super(view.getResources(),bitmap);this.size=size;
        }
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public boolean isStateful(){return true;}
        @Override public void draw(Canvas canvas) {
            Rect bounds=getBounds();if(bounds.isEmpty())return;
            Bitmap bitmap=getBitmap();
            float scale=Math.min((float)size/bitmap.getWidth(),(float)size/bitmap.getHeight());
            scale=Math.min(scale,Math.min((float)bounds.width()/bitmap.getWidth(),(float)bounds.height()/bitmap.getHeight()));
            float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;
            float left=bounds.exactCenterX()-width/2f,top=bounds.exactCenterY()-height/2f;
            canvas.drawBitmap(bitmap,null,new RectF(left,top,left+width,top+height),paint);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(Math.max(0,Math.min(255,alpha)));invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){ }
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private static final class ImageState implements AutoCloseable {
        final ImageView image;
        final String key;
        final int originalId;
        final View owner,selectionView;
        final boolean lottie;
        final Method compositionGetter;
        ImageView.ScaleType originalScaleType,appliedScaleType;
        boolean scaleTypeOwned;
        Drawable original, applied;
        Asset asset;
        boolean wasRunning, compositionUnavailable;
        ImageState(ImageView image,String key,View owner,View selectionView) {
            this.image=image; this.key=key;this.originalId=image.getId();this.owner=owner;this.selectionView=selectionView;
            originalScaleType=image.getScaleType();
            lottie=hasClass(image,"com.airbnb.lottie.LottieAnimationView");
            Method getter=null;
            if(lottie)try { getter=image.getClass().getMethod("getComposition"); }
            catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            compositionGetter=getter;
        }
        boolean apply(Asset next) {
            // The host setter cancels pending composition callbacks; let its load finish first.
            if(!compositionReady())return false;
            boolean changed=syncScaleType();
            Drawable current = image.getDrawable();
            int[] hostState=current!=null && (current!=applied || next!=asset || applied==null)?current.getState().clone():null;
            if (current != applied || applied == null) {
                original = current;
                wasRunning = original instanceof Animatable && ((Animatable) original).isRunning();
                if (wasRunning) ((Animatable) original).stop();
            }
            if (next != asset) { asset=next; applied=next.drawable(image,key); }
            if (image.getDrawable() != applied) {
                image.setImageDrawable(applied);
                if(hostState!=null && hasSelectedState(hostState) && !hasSelectedState(image.getDrawableState())) applied.setState(hostState);
                return true;
            }
            return changed;
        }
        private boolean syncScaleType() {
            ImageView.ScaleType current=image.getScaleType();
            if(scaleTypeOwned && current!=appliedScaleType) {originalScaleType=current;scaleTypeOwned=false;}
            else if(!scaleTypeOwned)originalScaleType=current;
            if(current==ImageView.ScaleType.CENTER || current==ImageView.ScaleType.CENTER_INSIDE
                    || current==ImageView.ScaleType.FIT_XY)return false;
            image.setScaleType(ImageView.ScaleType.CENTER);appliedScaleType=ImageView.ScaleType.CENTER;scaleTypeOwned=true;
            return true;
        }
        private boolean compositionReady() {
            if(!lottie)return true;
            if(compositionGetter==null || compositionUnavailable)return false;
            try { return compositionGetter.invoke(image)!=null; }
            catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                compositionUnavailable=true;return false;
            }
        }
        @Override public void close() {
            if (applied != null && image.getDrawable() == applied) {
                int[] hostState=applied.getState().clone();
                image.setImageDrawable(original);
                if(original!=null && hasSelectedState(hostState) && !hasSelectedState(image.getDrawableState()))original.setState(hostState);
                if (wasRunning && original instanceof Animatable) ((Animatable) original).start();
            }
            if(scaleTypeOwned && image.getScaleType()==appliedScaleType)image.setScaleType(originalScaleType);
            scaleTypeOwned=false;
            original=null; applied=null; asset=null; wasRunning=false;
        }
    }

    private static final class LabelState implements AutoCloseable {
        final TextView text;
        final Drawable[] original = new Drawable[4];
        final Drawable[] applied = new Drawable[4];
        float originalSize, appliedSize;
        int originalPadding, appliedPadding;
        boolean originalFont, appliedFont;
        Asset asset;
        Drawable icon;
        String key;
        View owner;
        boolean top,externalIcon;
        LabelState(TextView text) {
            this.text=text;
            System.arraycopy(text.getCompoundDrawables(),0,original,0,4);
            System.arraycopy(original,0,applied,0,4);
            originalSize=appliedSize=text.getTextSize();
            originalPadding=appliedPadding=text.getCompoundDrawablePadding();
            originalFont=appliedFont=text.getIncludeFontPadding();
        }
        boolean apply(Asset next, boolean top, boolean hide,boolean externalIcon) {
            Drawable[] current = text.getCompoundDrawables();
            for (int i=0;i<4;i++) if (current[i]!=applied[i]) original[i]=current[i];
            if (Float.compare(text.getTextSize(),appliedSize)!=0) originalSize=text.getTextSize();
            if (text.getCompoundDrawablePadding()!=appliedPadding) originalPadding=text.getCompoundDrawablePadding();
            if (text.getIncludeFontPadding()!=appliedFont) originalFont=text.getIncludeFontPadding();
            if (next!=asset || externalIcon && icon!=null || !externalIcon && next!=null && icon==null) {
                asset=next; icon=next==null || externalIcon?null:next.drawable(text);
            }
            if (icon!=null) { int edge=dp(text,top?20:24); icon.setBounds(0,0,edge,edge); }
            boolean changed=false;
            for (int i=0;i<4;i++) { applied[i]=i==1 && icon!=null?icon:original[i]; changed|=current[i]!=applied[i]; }
            if (changed) text.setCompoundDrawables(applied[0],applied[1],applied[2],applied[3]);
            appliedSize=hide && next!=null?0:Math.min(originalSize,TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,top?14:11,text.getResources().getDisplayMetrics()));
            if (Float.compare(text.getTextSize(),appliedSize)!=0) {text.setTextSize(TypedValue.COMPLEX_UNIT_PX,appliedSize);changed=true;}
            appliedPadding=icon==null?originalPadding:dp(text,3);
            if (text.getCompoundDrawablePadding()!=appliedPadding) {text.setCompoundDrawablePadding(appliedPadding);changed=true;}
            appliedFont=hide && next!=null?false:originalFont;
            if (text.getIncludeFontPadding()!=appliedFont) {text.setIncludeFontPadding(appliedFont);changed=true;}
            return changed;
        }
        @Override public void close() {
            Drawable[] current=text.getCompoundDrawables(); boolean changed=false;
            for(int i=0;i<4;i++) if(current[i]==applied[i] && current[i]!=original[i]) { current[i]=original[i]; changed=true; }
            if(changed)text.setCompoundDrawables(current[0],current[1],current[2],current[3]);
            if(Float.compare(text.getTextSize(),appliedSize)==0 && Float.compare(appliedSize,originalSize)!=0) text.setTextSize(TypedValue.COMPLEX_UNIT_PX,originalSize);
            if(text.getCompoundDrawablePadding()==appliedPadding && appliedPadding!=originalPadding)text.setCompoundDrawablePadding(originalPadding);
            if(text.getIncludeFontPadding()==appliedFont && appliedFont!=originalFont)text.setIncludeFontPadding(originalFont);
        }
    }

    private void bindImage(ImageView image,String key,View owner,Set<ImageView> found) {
        bindImage(image,key,owner,image,found);
    }
    private void bindImage(ImageView image,String key,View owner,View selectionView,Set<ImageView> found) {
        found.add(image);ImageState state=images.get(image);
        if(state!=null && (!key.equals(state.key) || state.owner!=owner || state.selectionView!=selectionView)){state.close();state=null;}
        if(state==null){state=new ImageState(image,key,owner,selectionView);images.put(image,state);}
        Asset next=cached(state,collectRole(image));if(next==null)state.close();else if(state.apply(next))imageWrites++;
    }
    /** Shared selection states use legacy day keys; persisted night artwork remains compatibility data. */
    private Set<String> encodedAssets() {
        Set<String> wanted=new HashSet<>();for(var entry:icons.entrySet())if(!hiddenNavigation.contains(entry.getKey()))
            for(var state:entry.getValue().entrySet())if(!state.getKey().night && state.getValue()!=null)wanted.add(state.getValue());
        return wanted;
    }
    private boolean insideHiddenNavigation(View view) {
        for(int depth=0;view!=null && depth<=64;depth++) {
            if(hiddenItems.containsKey(view))return true;
            if(view==root)break;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }
    private Asset cached(ImageState state,IconState role) {
        if(role==null || !"feed_collect".equals(state.key))return cached(state.owner,state.selectionView,state.key);
        Map<IconState,String> states=icons.get(state.key);
        return states==null?null:assets.get(states.get(role));
    }
    private Asset cached(View owner,View content,String key) {
        Map<IconState,String> states=icons.get(key);
        if(states==null)return null;
        NavigationState nativeState=navigation.get(owner);
        if(nativeState!=null) {
            nativeState.sample();
            return assets.get(states.get(IconState.of(false,nativeState.selected)));
        }
        Object delegate=owner!=null && hasClass(owner,TOP)?topDelegate(owner):null;
        return assets.get(states.get(IconState.of(false,selection(owner,content,delegate))));
    }
    private boolean selection(View owner,View content,Object delegate) {
        Boolean nativeSelected=topSelected(delegate);
        return nativeSelected!=null?nativeSelected:owner==null?selected(content)
                :owner.isSelected() || owner.isActivated() || content.isSelected() || content.isActivated();
    }
    private void syncNavigation() {
        for(Iterator<Map.Entry<View,NavigationState>> it=navigation.entrySet().iterator();it.hasNext();) {
            NavigationState state=it.next().getValue();
            if(!within(state.owner,root)){state.close();it.remove();}else state.sync();
        }
        for(Iterator<Map.Entry<ViewGroup,NavigationGroup>> it=navigationGroups.entrySet().iterator();it.hasNext();) {
            NavigationGroup group=it.next().getValue();
            if(!within(group.host,root)){group.close();it.remove();}else group.sync();
        }
    }
    /** Use only verified bars; the decor is never a common coordinate space for unrelated tabs. */
    private void refreshNavigationGroups() {
        Map<ViewGroup,List<NavigationState>> found=new IdentityHashMap<>();
        for(NavigationState state:navigation.values()) {
            state.group=null;
            String type=hasClass(state.owner,TOP)?"com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar":"X.1JgG";
            View ancestor=state.owner.getParent() instanceof View?(View)state.owner.getParent():null;
            for(int depth=0;ancestor!=null && depth<=64;depth++) {
                if(ancestor instanceof ViewGroup && hasClass(ancestor,type)) {
                    found.computeIfAbsent((ViewGroup)ancestor,ignored->new ArrayList<>()).add(state);break;
                }
                if(ancestor==root)break;
                ancestor=ancestor.getParent() instanceof View?(View)ancestor.getParent():null;
            }
        }
        for(Iterator<Map.Entry<ViewGroup,NavigationGroup>> it=navigationGroups.entrySet().iterator();it.hasNext();) {
            Map.Entry<ViewGroup,NavigationGroup> entry=it.next();List<NavigationState> members=found.get(entry.getKey());
            if(members==null || members.size()<2){entry.getValue().close();it.remove();}
        }
        for(Map.Entry<ViewGroup,List<NavigationState>> entry:found.entrySet())if(entry.getValue().size()>=2) {
            NavigationGroup group=navigationGroups.get(entry.getKey());
            if(group==null){group=new NavigationGroup(entry.getKey());navigationGroups.put(entry.getKey(),group);}
            group.members=entry.getValue();
            if(!group.unavailable)for(NavigationState state:group.members)state.group=group;
        }
    }
    private final class NavigationGroup {
        final ViewGroup host;
        final NavigationSelectionIndicator indicator;
        List<NavigationState> members=Collections.emptyList();
        boolean unavailable;
        NavigationGroup(ViewGroup host){this.host=host;indicator=new NavigationSelectionIndicator(host);}
        void sync() {
            if(unavailable)return;
            View selected=null;int color=Color.WHITE;boolean multiple=false;
            for(NavigationState state:members)if(navigation.get(state.owner)==state && state.group==this
                    && within(state.owner,host) && state.owner.isShown()) {
                state.sample();
                if(state.selected){if(selected!=null){multiple=true;break;}selected=state.owner;color=state.color;}
            }
            try {indicator.update(multiple?null:selected,color);}
            catch(RuntimeException|LinkageError failure) {
                unavailable=true;indicator.close();
                for(NavigationState state:members)state.group=null;
                once("home_ui_customization: navigation_indicator_unavailable");
            }
        }
        void close(){indicator.close();for(NavigationState state:members)if(state.group==this)state.group=null;members=Collections.emptyList();}
    }
    /** Metadata is discovered once; frame updates only revisit these known native titles and owners. */
    private final class NavigationState {
        final View owner;
        List<TextView> titles=Collections.emptyList();
        final Map<TextView,ImageView> externalIcons=new IdentityHashMap<>();
        View reported;
        NavigationGroup group;
        NavigationSelectionFeedback feedback;
        long sampledGeneration=Long.MIN_VALUE;
        boolean selected,night;
        int color;
        NavigationState(View owner){this.owner=owner;}
        void sources(List<TextView> titles,View reported) {
            this.titles=titles;this.reported=reported;externalIcons.clear();sampledGeneration=Long.MIN_VALUE;
            if(hasClass(owner,BOTTOM))for(TextView title:titles) {
                ImageView image=nativeNavigationIcon(title);if(image!=null)externalIcons.put(title,image);
            }
        }
        View content() {
            for(TextView title:titles)if(visibleWithin(title,owner))return title;
            for(Map.Entry<TextView,ImageView> entry:externalIcons.entrySet())if(visibleWithin(entry.getValue(),owner))return entry.getKey();
            if(reported!=null && within(reported,owner))return reported;
            return owner;
        }
        void sample() {
            if(sampledGeneration==nativeGeneration)return;
            sampledGeneration=nativeGeneration;
            View content=content();Object delegate=hasClass(owner,TOP)?topDelegate(owner):null;
            selected=selection(owner,content,delegate);night=night(delegate,content);
            color=content instanceof TextView?((TextView)content).getCurrentTextColor():night?Color.WHITE:0xff20252b;
        }
        void sync() {
            if(!owner.isAttachedToWindow() || owner.getWindowVisibility()!=View.VISIBLE || !owner.isShown()){close();return;}
            sample();
            if(group!=null && !within(owner,group.host))group=null;
            if(group!=null){close();return;}
            try {
                if(feedback==null)feedback=new NavigationSelectionFeedback(owner);
                feedback.update(selected,color);
            }catch(RuntimeException|LinkageError unavailable){close();once("home_ui_customization: navigation_feedback_unavailable");}
        }
        void close(){if(feedback!=null){try{feedback.close();}catch(RuntimeException|LinkageError ignored){ }feedback=null;}}
    }
    private static boolean visibleWithin(View view,View owner) {
        for(int depth=0;view!=null && depth<=64;depth++) {
            if(view.getVisibility()!=View.VISIBLE)return false;
            if(view==owner)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }
    private Object topDelegate(View owner) {
        try {
            Method getter=ACCESS.publicNoArgMethod(owner.getClass(),"getTabViewDelegate");
            return getter==null || Modifier.isStatic(getter.getModifiers())?null:getter.invoke(owner);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){once("home_ui_customization: native_delegate_unavailable");return null;}
    }
    private Boolean topSelected(Object delegate) {
        if(delegate!=null)try {
            Method method=ACCESS.publicNoArgMethod(delegate.getClass(),"LJIIZILJ");
            if(method!=null && method.getReturnType()==boolean.class && !Modifier.isStatic(method.getModifiers()))return (Boolean)method.invoke(delegate);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){once("home_ui_customization: native_selection_signal_unavailable");}
        return null;
    }
    private boolean night(Object delegate,View content) {
        if(delegate!=null)try {
            java.lang.reflect.Field field=ACCESS.field(delegate.getClass(),"A");Object provider=field==null?null:field.get(delegate);
            if(provider!=null) {
                Method method=ACCESS.publicNoArgMethod(provider.getClass(),"isDarkMode");
                if(method!=null && method.getReturnType()==boolean.class && !Modifier.isStatic(method.getModifiers()))return (Boolean)method.invoke(provider);
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {once("home_ui_customization: native_theme_signal_unavailable");}
        return (content.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    @android.annotation.SuppressLint("ResourceType") // These IDs name the verified 41 native titles, not module resources.
    private static List<TextView> navigationLabels(View owner,Object reported,boolean bottom) {
        List<TextView> result=new ArrayList<>();
        if(reported instanceof TextView && within((View)reported,owner))result.add((TextView)reported);
        if(bottom)for(int id:new int[]{0x7f0aaefe,0x7f0ab57a}) {
            View known=owner.findViewById(id);if(known instanceof TextView && !result.contains(known))result.add((TextView)known);
        }
        return result;
    }
    private static void observeNavigationMetadata(View owner,List<TextView> titles,Object reported,String key) {
        boolean ownerVisible=owner.isShown() && owner.getWindowVisibility()==View.VISIBLE;
        for(int pass=0;pass<2;pass++)for(TextView title:titles) {
            boolean visible=visibleWithin(title,owner);if(pass==0 && !visible)continue;
            String name=HomeNavigationMetadata.usableLabel(key,title.getText());
            if(!name.isEmpty()){NAVIGATION_METADATA.observe(key,name,ownerVisible && visible);return;}
        }
        View content=reported instanceof View && within((View)reported,owner)?(View)reported:null;
        NAVIGATION_METADATA.observe(key,content==null?null:content.getContentDescription(),
                ownerVisible && content!=null && visibleWithin(content,owner));
    }
    /** Verified 41 zlo's existing vertical image/title branch; never create a second icon above k. */
    @android.annotation.SuppressLint("ResourceType") // Verified zlo image/title IDs belong to the host APK.
    private static ImageView nativeNavigationIcon(TextView title) {
        if(title.getId()!=0x7f0ab57a || !(title.getParent() instanceof ViewGroup))return null;
        ViewGroup parent=(ViewGroup)title.getParent();if(parent.getId()!=0x7f0ab579)return null;
        View image=parent.findViewById(0x7f0ab578);
        return image instanceof ImageView && image.getParent()==parent?(ImageView)image:null;
    }
    private boolean configured(String key) { return icons.containsKey(key); }
    private void once(String message) { if (logged.size()<16 && logged.add(message))event(message); }
    private void event(String message){try{log.accept(message);}catch(RuntimeException|LinkageError ignored){/* Optional evidence must not affect host drawing. */}}
    private void report(boolean force) {
        if(observations>=32)return;
        long now=SystemClock.uptimeMillis();
        if(!force && now<nextObservationAt)return;
        nextObservationAt=now+1000;
        int hidden=0,tiny=0;for(View view:foundLabels.keySet()){if(!view.isShown())hidden++;if(view.getWidth()<dp(view,12)||view.getHeight()<dp(view,12))tiny++;}
        for(View view:images.keySet()){if(!view.isShown())hidden++;if(view.getWidth()<dp(view,12)||view.getHeight()<dp(view,12))tiny++;}
        String status="home_ui_customization: config=accepted;revision="+configurationRevision+";locations="+icons.size()
                +";hidden_configured="+hiddenNavigation.size()+";hidden_bound="+hiddenItems.size()
                +";titles="+labels.size()+";images="+images.size()+";image_writes="+imageWrites+";title_writes="+titleWrites
                +";hidden="+hidden+";small="+tiny+";assets_unavailable="+missingAssets+";discovery_limited="+discoveryLimited;
        if(!status.equals(lastObservation)){lastObservation=status;if(observations++<32)event(status);}
    }
    private void reportSlowWork(String phase,long started) {
        if(started==0 || slowReports>=6)return;
        long micros=(SystemClock.elapsedRealtimeNanos()-started)/1000;
        if(micros<16000)return;
        slowReports++;
        event("home_ui_customization: slow_ui_work;phase="+phase+";elapsed_us="+micros
                +";main_thread="+(Looper.myLooper()==Looper.getMainLooper())+";titles="+labels.size()
                +";images="+images.size()+";navigation_groups="+navigationGroups.size());
    }
    private static int dp(View view,int value) { return Math.round(value*view.getResources().getDisplayMetrics().density); }
    private static void add(Map<String,Target> targets,String key,String title,boolean navigation) { targets.put(key,new Target(key,title,navigation)); }
    private static final class TopCornerTarget {
        final String key;
        final ImageView image;
        TopCornerTarget(String key,ImageView image){this.key=key;this.image=image;}
    }
    @android.annotation.SuppressLint("ResourceType") // Only the verified owner's public getter authorizes this host image.
    private TopCornerTarget topCornerTarget(View owner) {
        if(!hasClass(owner,TOP_CORNER) || !within(owner,root) || owner==root)return null;
        View ancestor=owner.getParent() instanceof View?(View)owner.getParent():null;
        boolean titleBar=false;
        for(int depth=0;ancestor!=null && depth<=64;depth++) {
            if(hasClass(ancestor,MAIN_TITLE_BAR)){titleBar=true;break;}
            if(ancestor==root)break;
            ancestor=ancestor.getParent() instanceof View?(View)ancestor.getParent():null;
        }
        if(!titleBar)return null;
        try {
            Field field=ACCESS.field(owner.getClass(),"d");
            if(field==null || field.getType()!=String.class || !Modifier.isPublic(field.getModifiers())
                    || Modifier.isStatic(field.getModifiers()))return null;
            String identity=(String)field.get(owner);
            if(!"homepage_right_search".equals(identity) && !"homepage_left_sidebar".equals(identity)
                    && !"homepage_left_sidebar_jx".equals(identity))return null;
            Method getter=ACCESS.publicNoArgMethod(owner.getClass(),"getIconView");
            if(getter==null || !Modifier.isPublic(getter.getModifiers()) || Modifier.isStatic(getter.getModifiers())
                    || getter.getParameterTypes().length!=0 || !ImageView.class.isAssignableFrom(getter.getReturnType()))return null;
            Object content=getter.invoke(owner);
            if(!(content instanceof ImageView))return null;
            ImageView image=(ImageView)content;
            return image.getId()==0x7f0abc18 && within(image,owner) && within(image,root)
                    ?new TopCornerTarget("nav_top:"+identity,image):null;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {
            once("home_ui_customization: top_corner_contract_unavailable");return null;
        }
    }
    @android.annotation.SuppressLint("ResourceType") // Fixed artwork roles exist only in the verified direct-child pair.
    private IconState collectRole(ImageView image) {
        if(image.getId()!=0x7f0a2010 && image.getId()!=0x7f0a2439)return null;
        if(!within(image,root) || !(image.getParent() instanceof FrameLayout))return null;
        FrameLayout frame=(FrameLayout)image.getParent();
        if(frame.getId()!=0x7f0a240b || !(frame.getParent() instanceof ViewGroup))return null;
        ViewGroup owner=(ViewGroup)frame.getParent();
        if(!within(owner,root) || owner.getId()!=0x7f0a204e || !hasClass(owner,"androidx.constraintlayout.widget.ConstraintLayout"))return null;
        for(int i=0;i<owner.getChildCount();i++) {
            View child=owner.getChildAt(i);
            if(child.getId()==0x7f0a240b && child!=frame)return null;
        }
        View selected=null,normal=null;
        for(int i=0;i<frame.getChildCount();i++) {
            View child=frame.getChildAt(i);
            if(child.getId()==0x7f0a2010){if(selected!=null)return null;selected=child;}
            if(child.getId()==0x7f0a2439){if(normal!=null)return null;normal=child;}
        }
        if(!(selected instanceof ImageView) || !hasClass(selected,"com.airbnb.lottie.LottieAnimationView")
                || !(normal instanceof ImageView) || hasClass(normal,"com.airbnb.lottie.LottieAnimationView"))return null;
        return image==selected?IconState.of(false,true):image==normal?IconState.of(false,false):null;
    }
    @android.annotation.SuppressLint("ResourceType") // IDs belong to the verified host APK, not module R.
    private static String feedImageKey(ImageView image) {
        int id=image.getId();
        if(id==View.NO_ID) {
            // In the host's grouped like layout the named ID belongs to the owner, not its artwork.
            if(!(image.getParent() instanceof FrameLayout))return null;
            FrameLayout owner=(FrameLayout)image.getParent();
            if(owner.getId()!=0x7f0a30ca || !hasClass(owner,"com.ss.android.ugc.aweme.feed.widget.DiggAnimationLayout")
                    || !(owner.getParent() instanceof FrameLayout)
                    || ((FrameLayout)owner.getParent()).getId()!=0x7f0a30d6)return null;
            String type=image.getClass().getName();
            return "com.ss.android.ugc.aweme.feed.widget.DiggAnimationView".equals(type)
                    || "com.ss.android.ugc.aweme.feed.widget.DiggAnimationViewV2".equals(type)?"feed_like":null;
        }
        // Verified 41.0.0 image children, not the containing action slots.
        switch(id) {
            case 0x7f0a30ca:return "feed_like";
            case 0x7f0a254e:return "feed_comment";
            case 0x7f0a2010:return "feed_collect";
            case 0x7f0aa9f7:return "feed_share";
            case 0x7f0a461d:return "feed_follow";
            case 0x7f0a7ea4:return "feed_mute";
            default:return null;
        }
    }
    private static boolean selected(View view) {
        if(view==null)return false;
        if(view.isSelected() || view.isActivated())return true;
        Drawable drawable=view instanceof ImageView?((ImageView)view).getDrawable():null;
        return drawable!=null && hasSelectedState(drawable.getState());
    }
    private static boolean hasSelectedState(int[] states) {
        for(int state:states)if(state==android.R.attr.state_selected || state==android.R.attr.state_activated)return true;
        return false;
    }
    private static boolean hasClass(View view,String name) {
        for(Class<?> type=view.getClass();type!=null;type=type.getSuperclass())if(name.equals(type.getName()))return true;
        return false;
    }
    private static boolean within(View view,View owner) {
        for(int depth=0;view!=null && depth<=64;depth++) {
            if(view==owner)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }
}
