package com.douai.module.feature;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** PROFILE_410: glass belongs to navigation; native controls may move without changing material. */
final class LiquidGlassTargets {
    static final int MAX_NODES=4096, MAX_TARGETS=192;
    private static final int MAX_MOTION_TARGETS=96;
    private LiquidGlassTargets() { }

    static Map<View,Boolean> find(View root) {return select(root).targets;}

    static final class Selection {
        final Map<View,Boolean> targets;final boolean treeLimited,targetLimited;
        Selection(Map<View,Boolean> targets,boolean treeLimited,boolean targetLimited){
            this.targets=targets;this.treeLimited=treeLimited;this.targetLimited=targetLimited;
        }
    }
    static final class TreeSnapshot {
        final View root;
        final List<View> nodes;
        final boolean truncated;
        private TreeSnapshot(View root,List<View> nodes,boolean truncated) {
            this.root=root;this.nodes=Collections.unmodifiableList(nodes);this.truncated=truncated;
        }
    }
    static TreeSnapshot snapshot(View root) {
        List<View> nodes=new ArrayList<>();boolean[] truncated={false};collect(root,nodes,0,truncated);
        return new TreeSnapshot(root,nodes,truncated[0]);
    }
    static Selection select(View root) {return select(root,snapshot(root),true);}
    static Selection select(View root,boolean activityWindow) {return select(root,snapshot(root),activityWindow);}
    static Selection select(View root,TreeSnapshot snapshot,boolean activityWindow) {
        if(root==null)return new Selection(Collections.emptyMap(),false,false);
        if(snapshot==null || snapshot.root!=root)return new Selection(Collections.emptyMap(),true,false);
        if(snapshot.truncated)return new Selection(Collections.emptyMap(),true,false);
        List<View> nodes=snapshot.nodes;
        Set<View> mediaAncestors=Collections.newSetFromMap(new IdentityHashMap<>());
        for(View node:nodes) if(isMedia(node) || opaque(node)) {
            for(View v=node;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null) {
                mediaAncestors.add(v);if(v==root)break;
            }
        }
        Map<View,Boolean> result=new LinkedHashMap<>();
        for(View view:nodes) if(view!=root && view.getVisibility()==View.VISIBLE && floating(view) && !mediaAncestors.contains(view)) {
            if(result.size()>=MAX_TARGETS)break;
            if(geometrySafe(view,root,true,activityWindow) && !ownedAncestor(view,result))result.put(view,true);
        }
        return new Selection(result,false,result.size()>=MAX_TARGETS);
    }

    /** Called by the bounded discovery scan, never by the animation frame callback. */
    static List<View> motionTargets(View root) {return motionTargets(root,snapshot(root));}
    static List<View> motionTargets(View root,TreeSnapshot snapshot) {
        if(root==null || snapshot==null || snapshot.root!=root || snapshot.truncated)return Collections.emptyList();
        List<View> nodes=snapshot.nodes;
        Set<View> protectedTrees=identities(),unsafeGroups=identities();
        for(View node:nodes) {
            View parent=node.getParent() instanceof View?(View)node.getParent():null;
            if(protectedTrees.contains(parent) || motionProtected(node)) {
                protectedTrees.add(node);
                for(View current=node;current!=null;current=current.getParent() instanceof View?(View)current.getParent():null) {
                    unsafeGroups.add(current);if(current==root)break;
                }
            }
        }
        List<View> result=new ArrayList<>();Set<View> selected=identities(),selectedAncestors=identities();
        // Host groups own their children, then small clickable groups own their native labels/icons.
        for(int pass=0;pass<3;pass++)for(View view:nodes) {
            boolean semantic=motionGroup(view);
            boolean candidate=pass==0?semantic:pass==1?view instanceof ViewGroup && view.isClickable() && !scrollContainer(view)
                    :!(view instanceof ViewGroup) && (view instanceof TextView || view instanceof ImageView
                    || view instanceof ProgressBar || view.isClickable());
            if(!candidate || view==root || protectedTrees.contains(view) || unsafeGroups.contains(view)
                    || selected.contains(view) || selectedAncestors.contains(view) || motionAncestor(view,selected)
                    || !semantic && smallClickableAncestor(view,root)
                    || !motionGeometrySafe(view,root,semantic))continue;
            result.add(view);selected.add(view);
            for(View parent=view.getParent() instanceof View?(View)view.getParent():null;parent!=null;
                    parent=parent.getParent() instanceof View?(View)parent.getParent():null) {
                selectedAncestors.add(parent);if(parent==root)break;
            }
            if(result.size()>=MAX_MOTION_TARGETS)return result;
        }
        return result;
    }

    private static boolean motionGroup(View view) {
        if(floating(view))return true;
        if(!(view instanceof ViewGroup))return false;
        int id=view.getId();
        return id==0x7f0a15bb && hasClass(view,"androidx.appcompat.widget.LinearLayoutCompat")
                || id==0x7f0a3ffc && hasClass(view,"com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildFrameLayout")
                || id==0x7f0a4000 && view instanceof android.widget.RelativeLayout;
    }

    private static boolean motionProtected(View view) {
        if(isMedia(view) || opaque(view) || view instanceof EditText
                || view instanceof TextView && (((TextView)view).getEditableText()!=null || ((TextView)view).isTextSelectable())
                || view.getAnimation()!=null || subtitle(view))return true;
        if(view instanceof ImageView && ((ImageView)view).getDrawable() instanceof Animatable
                && ((Animatable)((ImageView)view).getDrawable()).isRunning())return true;
        if(view instanceof ProgressBar && ((ProgressBar)view).isIndeterminate())return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            if(group.getLayoutTransition()!=null && group.getLayoutTransition().isRunning())return true;
            // ViewGroup propagates children's transient state; it must not freeze unrelated siblings.
            if(view.hasTransientState()) {
                for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i).hasTransientState())return false;
                return true;
            }
            return false;
        }
        return view.hasTransientState();
    }

    private static boolean subtitle(View view) {
        for(Class<?> type=view.getClass();type!=null;type=type.getSuperclass())
            if(subtitleName(type.getName()))return true;
        if(view.getId()!=View.NO_ID)try {
            return subtitleName(view.getResources().getResourceEntryName(view.getId()));
        }catch(RuntimeException ignored){
            // Host IDs can be absent from this window's resource table; class-name protection still applies.
        }
        return false;
    }
    private static boolean subtitleName(String value) {
        String name=value.toLowerCase(Locale.ROOT);
        return name.contains("subtitle") || name.contains("caption") || name.contains("lyric") || name.contains("lrc");
    }
    private static boolean scrollContainer(View view) {
        return view instanceof android.widget.ScrollView || view instanceof android.widget.HorizontalScrollView
                || view instanceof android.widget.AbsListView || hasClass(view,"androidx.recyclerview.widget.RecyclerView")
                || hasClass(view,"androidx.viewpager.widget.ViewPager") || hasClass(view,"androidx.viewpager2.widget.ViewPager2");
    }
    private static boolean motionAncestor(View view,Set<View> selected) {
        for(View parent=view.getParent() instanceof View?(View)view.getParent():null;parent!=null;
                parent=parent.getParent() instanceof View?(View)parent.getParent():null)if(selected.contains(parent))return true;
        return false;
    }
    private static boolean smallClickableAncestor(View view,View root) {
        for(View parent=view.getParent() instanceof View?(View)view.getParent():null;parent!=null && parent!=root;
                parent=parent.getParent() instanceof View?(View)parent.getParent():null) {
            if(!(parent instanceof ViewGroup) || !parent.isClickable() || scrollContainer(parent))continue;
            boolean semantic=motionGroup(parent);
            if(parent.getWidth()>0 && parent.getHeight()>0
                    && (long)parent.getWidth()*parent.getHeight()<=(long)root.getWidth()*root.getHeight()*(semantic?.35d:.25d)
                    && parent.getHeight()<=root.getHeight()*(semantic?.9d:.35d))return true;
        }
        return false;
    }
    private static Set<View> identities(){return Collections.newSetFromMap(new IdentityHashMap<>());}

    private static boolean motionGeometrySafe(View view,View root,boolean semantic) {
        int width=view.getWidth(),height=view.getHeight(),viewportWidth=root.getWidth(),viewportHeight=root.getHeight();
        if(width<=0 || height<=0 || viewportWidth<=0 || viewportHeight<=0 || !view.isLaidOut() || !root.isLaidOut())return false;
        double maxArea=(long)viewportWidth*viewportHeight*(semantic?.35d:.25d);
        if((long)width*height>maxArea || height>viewportHeight*(semantic?.9d:.35d))return false;
        RectF bounds=new RectF(0,0,width,height);View current=view;
        for(int depth=0;depth<=40;depth++) {
            if(current.getVisibility()!=View.VISIBLE || !Float.isFinite(current.getAlpha()) || current.getAlpha()<=0)return false;
            Rect clip=current.getClipBounds();
            if(clip!=null && !contains(new RectF(clip),bounds))return false;
            if(current==root)return contains(new RectF(0,0,viewportWidth,viewportHeight),bounds)
                    && bounds.width()*(double)bounds.height()<=maxArea
                    && bounds.height()<=viewportHeight*(semantic?.9d:.35d);
            current.getMatrix().mapRect(bounds);
            if(!(current.getParent() instanceof ViewGroup))return false;
            ViewGroup parent=(ViewGroup)current.getParent();
            bounds.offset(current.getLeft()-parent.getScrollX(),current.getTop()-parent.getScrollY());
            boolean padding=parent.getClipToPadding() && (parent.getPaddingLeft()!=0 || parent.getPaddingTop()!=0
                    || parent.getPaddingRight()!=0 || parent.getPaddingBottom()!=0);
            if(parent.getClipChildren() || padding) {
                RectF visible=new RectF(padding?parent.getPaddingLeft():0,padding?parent.getPaddingTop():0,
                        parent.getWidth()-(padding?parent.getPaddingRight():0),parent.getHeight()-(padding?parent.getPaddingBottom():0));
                if(!contains(visible,bounds))return false;
            }
            current=parent;
        }
        return false;
    }
    private static boolean contains(RectF outer,RectF inner) {
        return Float.isFinite(inner.left) && Float.isFinite(inner.top) && Float.isFinite(inner.right) && Float.isFinite(inner.bottom)
                && !inner.isEmpty() && inner.left>=outer.left-.01f && inner.top>=outer.top-.01f
                && inner.right<=outer.right+.01f && inner.bottom<=outer.bottom+.01f;
    }

    static boolean geometrySafe(View view,View root,boolean floating) {return geometrySafe(view,root,floating,true);}
    static boolean geometrySafe(View view,View root,boolean floating,boolean activityWindow) {
        int width=view.getWidth(),height=view.getHeight();
        if(root.getWidth()<=0 || root.getHeight()<=0 || width<=0 || height<=0
                || !root.isLaidOut() || !view.isLaidOut())return false;
        // An Activity must respect its split-screen viewport; other windows may use the display.
        android.util.DisplayMetrics display=root.getResources().getDisplayMetrics();
        int viewportWidth=activityWindow?root.getWidth():Math.max(root.getWidth(),display.widthPixels);
        int viewportHeight=activityWindow?root.getHeight():Math.max(root.getHeight(),display.heightPixels);
        long area=(long)width*height,viewportArea=(long)viewportWidth*viewportHeight;
        if(area>viewportArea*.35d || height>viewportHeight*(floating?.22d:.35d))return false;
        android.graphics.Matrix transform=new android.graphics.Matrix();View current=view;
        for(int depth=0;depth<=40;depth++) {
            transform.postConcat(current.getMatrix());
            if(current==root) {
                android.graphics.RectF painted=new android.graphics.RectF(0,0,width,height);transform.mapRect(painted);
                return Float.isFinite(painted.width()) && Float.isFinite(painted.height()) && !painted.isEmpty()
                        && painted.width()*(double)painted.height()<=viewportArea*.35d
                        && painted.height()<=viewportHeight*(floating?.22d:.35d);
            }
            if(!(current.getParent() instanceof View))return false;
            View parent=(View)current.getParent();
            transform.postTranslate(current.getLeft()-parent.getScrollX(),current.getTop()-parent.getScrollY());current=parent;
        }
        return false;
    }

    static boolean floating(View view) {
        return hasClass(view,"com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar")
                || hasClass(view,"X.1JgG");
    }

    static List<View> nodes(View root) {
        return snapshot(root).nodes;
    }

    private static void collect(View view,List<View> nodes,int depth,boolean[] truncated) {
        if(view==null)return;
        if(nodes.size()>=MAX_NODES || depth>40){truncated[0]=true;return;}
        nodes.add(view);
        if(opaque(view))return;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) {
                collect(group.getChildAt(i),nodes,depth+1,truncated);
                if(truncated[0])break;
            }
        }
    }

    static boolean opaque(View view) {
        String name=view.getClass().getName();
        return view instanceof WebView || name.startsWith("com.lynx.") || name.startsWith("com.bytedance.lynx.")
                || hasClass(view,"androidx.compose.ui.platform.AbstractComposeView")
                || name.startsWith("com.douai.module.");
    }

    static boolean isMedia(View view) {return view instanceof SurfaceView || view instanceof TextureView;}
    static boolean movable(View view) {
        if(!floating(view))return false;
        for(View child:nodes(view))if(child instanceof android.widget.EditText
                || child instanceof TextView && (((TextView)child).getEditableText()!=null
                || ((TextView)child).isTextSelectable()))return false;
        return true;
    }
    static boolean hasClass(View view,String name) {
        for(Class<?> type=view.getClass();type!=null;type=type.getSuperclass())if(name.equals(type.getName()))return true;
        return false;
    }
    private static boolean ownedAncestor(View view,Map<View,Boolean> targets) {
        for(View v=view.getParent() instanceof View?(View)view.getParent():null;v!=null;
                v=v.getParent() instanceof View?(View)v.getParent():null)if(targets.containsKey(v))return true;
        return false;
    }
}
