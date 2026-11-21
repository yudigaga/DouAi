package com.douai.module.feature;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import androidx.core.view.OneShotPreDrawListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Window-owned clearance for the verified 41.0.0 bottom UI dock; never owns video or transforms. */
public final class NavigationGlassClearance implements AutoCloseable {
    private static final int MAX_REVEAL_CONTROLS=96;
    private static final int CARRIER=0x7f0a15bb,PROGRESS=0x7f0a16b2,ABOVE=0x7f0a3ff1;
    private static final int METADATA=0x7f0a3ffc,RIGHT=0x7f0a4000,PARENT=0x7f0a01db;
    private static final int DANMAKU_ICON=0x7f0a2da7,DANMAKU_FRAME=0x7f0a2dab;
    private static final String DANMAKU_CLIP="com.ss.android.ugc.aweme.feed.danmaku.view.DanmakuVisibilityFrameLayout";
    private static final int DIGG_ANIMATION=0x7f0a30ca,DIGG_FRAME=0x7f0a30d6,DIGG_ACTION=0x7f0a30eb,DIGG_CLIP=0x7f0a30de;
    private final View root;
    private final Function<View,RectF> restingBounds;
    private final BiFunction<View,Integer,RectF> retainedBounds;
    private final Map<View,Branch> branches=new IdentityHashMap<>();
    private final Map<View,WindowProgress> windowProgress=new IdentityHashMap<>();
    private final Set<View> blocked=identities();
    private final Set<View> knownNavigation=identities();
    private long revision,signature;
    private int progressMotionMargin;
    void setProgressMotionMargin(int pixels){progressMotionMargin=Math.max(0,pixels);}
    private String fallbackDetail="none",syncState="not_run";
    private View pendingGeometryView;
    private boolean haveSignature,closed,syncing,discoveryWrite,compactReady,structureRejected;

    public NavigationGlassClearance(View root,Function<View,RectF> restingBounds) {
        this(root,restingBounds,null);
    }

    NavigationGlassClearance(View root,Function<View,RectF> restingBounds,BiFunction<View,Integer,RectF> retainedBounds) {
        this.root=java.util.Objects.requireNonNull(root);this.restingBounds=java.util.Objects.requireNonNull(restingBounds);
        this.retainedBounds=retainedBounds;
    }

    /** Called with the controller's bounded scan, never from an every-frame tree traversal. */
    public void discover(List<View> nodes) {
        if(closed)return;
        List<View> inventory=nodes==null?Collections.emptyList():nodes;
        Set<View> present=identities();present.addAll(inventory);
        knownNavigation.retainAll(present);
        for(View node:inventory)if(LiquidGlassTargets.floating(node))knownNavigation.add(node);
        Map<View,Branch> next=new IdentityHashMap<>();
        for(View node:inventory) {
            if(!(node instanceof ViewGroup) || !(node.getParent() instanceof RelativeLayout) || !within(node,root))continue;
            RelativeLayout parent=(RelativeLayout)node.getParent();
            if(parent.getId()!=PARENT || !(node.getLayoutParams() instanceof RelativeLayout.LayoutParams))continue;
            boolean carrier=node.getId()==CARRIER;
            boolean legacy=node.getId()==RIGHT && legacyShape(node);
            if(!carrier && !legacy)continue;
            Branch old=branches.get(node);
            if(old!=null && old.parent==parent && old.raw==node.getLayoutParams())next.put(node,old);
            else {
                if(old!=null)discoveryWrite|=old.restore();
                next.put(node,new Branch((ViewGroup)node,parent,carrier));
            }
        }
        for(Branch old:branches.values())if(!next.containsKey(old.owner))discoveryWrite|=old.restore();
        branches.clear();branches.putAll(next);
        for(Branch branch:branches.values())branch.cache(inventory,present);
        List<Branch> discovered=new ArrayList<>(branches.values());
        for(Branch outer:discovered)if(outer.carrier)for(Branch inner:discovered) {
            if(outer==inner || !outer.affected.contains(inner.owner))continue;
            for(View observer:inner.observed)if(!outer.observed.contains(observer))outer.observed.add(observer);
            if(branches.remove(inner.owner)!=null)discoveryWrite|=inner.restore();
        }
        for(Branch branch:branches.values())branch.cacheCapacityNodes();
        Map<View,WindowProgress> tracks=new IdentityHashMap<>();
        for(View node:inventory) {
            boolean cardOwned=false;
            for(Branch branch:branches.values())if(branch.affected.contains(node)){cardOwned=true;break;}
            if(cardOwned || !WindowProgress.matches(node))continue;
            WindowProgress old=windowProgress.get(node);
            if(old!=null && old.raw==node.getLayoutParams() && old.parent==node.getParent())tracks.put(node,old);
            else {
                if(old!=null)discoveryWrite|=old.restore();
                try {tracks.put(node,new WindowProgress(node));}
                catch(ReflectiveOperationException | RuntimeException ignored){/* Unknown host constraints remain native. */}
            }
        }
        for(WindowProgress old:windowProgress.values())if(!tracks.containsKey(old.view))discoveryWrite|=old.restore();
        windowProgress.clear();windowProgress.putAll(tracks);
        for(WindowProgress track:windowProgress.values())track.onDiscovery();
    }

    /** navigation contains already-validated navigation owners; true means a field write needs layout. */
    public boolean sync(Collection<View> navigation) {
        if(closed || syncing)return false;
        syncing=true;
        try {
            boolean changed=discoveryWrite;discoveryWrite=false;blocked.clear();fallbackDetail="none";syncState="evaluating";
            List<Nav> bottoms=new ArrayList<>();float ceiling=root.getPaddingTop()+gap();
            if(navigation!=null)for(View view:navigation) {
                if(view!=null && within(view,root))knownNavigation.add(view);
                if(view==null || !effectiveVisible(view) || !within(view,root))continue;
                RectF bounds=restingBounds.apply(view);
                if(!finite(bounds))continue;
                if(LiquidGlassTargets.hasClass(view,"com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar"))
                    ceiling=Math.max(ceiling,bounds.bottom+gap());
                else bottoms.add(new Nav(view,new RectF(bounds)));
            }

            boolean structural=false;
            List<Branch> activeBranches=new ArrayList<>();
            for(Branch branch:branches.values()) {
                if(!branchInViewport(branch)){changed|=branch.restore();continue;}
                activeBranches.add(branch);
                if(!branch.structureIntact()) {
                    compactReady=false;
                    changed|=branch.restore();structural=true;
                    block(bottoms,"structure",branch.owner);
                }
            }
            // A new discovery can validate the same live rules without changing their geometry hash.
            if(structural!=structureRejected){structureRejected=structural;revision++;}
            if(bottoms.isEmpty()) {
                for(Branch branch:branches.values())changed|=branch.restore();
                for(WindowProgress track:windowProgress.values())changed|=track.restore();
            }
            if(targetGeometryPending(activeBranches)){syncState="pending_target_layout";return changed;}
            if(!bottoms.isEmpty())for(WindowProgress track:windowProgress.values()) {
                if(track.intact() && effectiveVisible(track.view))changed|=track.compactPadding();
            }
            if(targetGeometryPending(activeBranches)){syncState="pending_target_layout";return changed;}

            // The top ceiling can change without a new View, viewport or footer layout.
            // Bottom capsule size is deliberately excluded: our own capacity fallback changes it.
            long input=mix(mix(mix(mix(17,root.getWidth()),root.getHeight()),progressMotionMargin),Math.round(ceiling));
            input=mix(input,activeBranches.size());
            for(View nav:knownNavigation)input=mix(mix(input,System.identityHashCode(nav)),effectiveVisible(nav)?1:0);
            List<Nav> footerObstacles=new ArrayList<>(bottoms);
            for(WindowProgress track:windowProgress.values()) {
                track.adoptHostMargin();input=mix(input,track.inputSignature());
                changed|=track.sync(bottoms,ceiling);
                if(track.reservedBounds!=null)footerObstacles.add(new Nav(track.view,new RectF(track.reservedBounds)));
            }
            List<Plan> plans=new ArrayList<>();
            for(Branch branch:activeBranches) {
                branch.adoptHostMargin();
                input=mix(input,branch.inputSignature());
                if(bottoms.isEmpty() || structural)continue;
                List<RectF> observations=branch.observations();
                if(branch.unknownBounds){block(bottoms,"unmeasurable",branch.unknownView);continue;}
                if(branch.unknownContent!=null){block(bottoms,"unmeasurable",branch.unknownContent);continue;}
                float extra=0,minTop=branch.contentTop;
                for(RectF box:observations)for(Nav nav:footerObstacles)if(horizontal(box,nav.bounds)) {
                    extra=Math.max(extra,box.bottom-(nav.bounds.top-(windowProgress.containsKey(nav.view)?progressGap():gap())));
                }
                extra=Math.max(0,extra);
                float scale=scaleY(branch.parent);
                if((!Float.isFinite(scale) || scale<=0) && branch.owned) {
                    block(bottoms,"parent_transform",branch.parent);
                    continue;
                }
                View protectedNode=branch.visibleProtected();
                boolean safe=!branch.unsafe && protectedNode==null && Float.isFinite(scale) && scale>0
                        && (extra==0 || minTop-extra>=ceiling);
                if(!safe && extra>0) {
                    String reason=branch.unsafe?"structure":protectedNode!=null?"protected_content"
                            :!Float.isFinite(scale) || scale<=0?"parent_transform":"capacity";
                    boolean firstFailure=blocked.isEmpty();
                    block(bottoms,reason,protectedNode!=null?protectedNode:branch.owner);
                    if(firstFailure && reason.equals("capacity"))fallbackDetail+=";needed="+extra+";min_content_top="+minTop+";ceiling="+ceiling;
                }else plans.add(new Plan(branch,extra,scale));
            }
            if(!haveSignature || input!=signature){signature=input;haveSignature=true;revision++;}
            if(!blocked.isEmpty())for(Branch branch:branches.values())changed|=branch.restore();
            else if(!bottoms.isEmpty())for(Plan plan:plans)changed|=plan.branch.apply(plan.extra,plan.scale);
            compactReady=blocked.isEmpty() && !bottoms.isEmpty();
            syncState=blocked.isEmpty()?"applied":"blocked";
            return changed;
        }finally{syncing=false;}
    }

    /** Page ancestry is independent of the footer margins we own; neighboring feed pages are preloaded. */
    private boolean branchInViewport(Branch branch) {
        if(!effectiveVisible(branch.parent))return false;
        RectF page=bounds(branch.parent);
        // Unknown page geometry remains conservative; only a reliable, fully offscreen page is excluded.
        return !finite(page) || intersectsRoot(page);
    }

    /** Requests propagate from empty placeholders; wait for actual geometry, not every descendant's request bit. */
    private boolean targetGeometryPending(Collection<Branch> activeBranches) {
        pendingGeometryView=null;
        for(View nav:knownNavigation)if(effectiveVisible(nav) && navigationLayoutPending(nav)){pendingGeometryView=nav;return true;}
        for(WindowProgress track:windowProgress.values())
            if(effectiveVisible(track.view) && track.layoutPending()){pendingGeometryView=track.view;return true;}
        for(Branch branch:activeBranches) {
            View owner=branch.owner;
            if(effectiveVisible(owner) && (!owner.isLaidOut() || owner.isLayoutRequested()
                    && (requestedSizePending(owner) || bottomAnchorPending(owner,branch.parent,branch.raw)))) {
                pendingGeometryView=owner;return true;
            }
            for(View view:branch.observed)if(effectiveVisible(view) && requestedSizePending(view)) {
                pendingGeometryView=view;return true;
            }
        }
        return false;
    }
    private static boolean requestedSizePending(View view) {
        if(!view.isLayoutRequested())return false;
        ViewGroup.LayoutParams params=view.getLayoutParams();
        // Zero is also used for weighted / constrained dimensions; it does not imply an unapplied size.
        return params==null || params.width>0 && params.width!=view.getWidth()
                || params.height>0 && params.height!=view.getHeight();
    }
    private static boolean bottomAnchorPending(View view,ViewGroup parent,ViewGroup.MarginLayoutParams params) {
        return !parent.isLaidOut() || (long)view.getBottom()
                !=(long)parent.getHeight()-parent.getPaddingBottom()-params.bottomMargin;
    }
    private static boolean navigationLayoutPending(View view) {
        if(!view.isLaidOut() || view.getWidth()<=0 || view.getHeight()<=0 || requestedSizePending(view))return true;
        if(!view.isLayoutRequested())return false;
        if(view.getParent() instanceof FrameLayout && view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout parent=(FrameLayout)view.getParent();FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)view.getLayoutParams();
            if(parent.getForeground()!=null)return true;
            int gravity=params.gravity==-1?Gravity.TOP:params.gravity&Gravity.VERTICAL_GRAVITY_MASK;
            if(gravity==Gravity.BOTTOM)return bottomAnchorPending(view,parent,params);
            if(gravity==Gravity.TOP)return (long)view.getTop()!=(long)parent.getPaddingTop()+params.topMargin;
        }
        // Unknown host placement rules retain the conservative wait.
        return true;
    }
    private RectF actualBounds(View view) {
        Matrix map=toRoot(view);if(map==null || view.getWidth()<=0 || view.getHeight()<=0)return null;
        RectF box=new RectF(0,0,view.getWidth(),view.getHeight());map.mapRect(box);return finite(box)?box:null;
    }
    /** Bounded structural geometry only. Drawable bounds are not claimed to be painted pixels. */
    String geometryDiagnostic(Collection<View> navigation) {
        StringBuilder out=new StringBuilder("density=").append(root.getResources().getDisplayMetrics().density)
            .append(";sync_state=").append(syncState).append(";input_revision=").append(revision)
            .append(";blocked_navigation=").append(blocked.size()).append(";root_layout_requested=").append(root.isLayoutRequested());
        if(pendingGeometryView!=null)out.append(";last_pending_target=").append(pendingGeometryView.getClass().getName())
                .append(':').append(Integer.toHexString(pendingGeometryView.getId()))
                .append(",layout_requested_now=").append(pendingGeometryView.isLayoutRequested());
        int count=0;
        for(WindowProgress track:windowProgress.values()) {
            if(!effectiveVisible(track.view) || count++>=2)continue;
            View view=track.view;
            out.append(";track=").append(Integer.toHexString(view.getId()))
                .append(",height=").append(view.getHeight()).append(",layout_height=").append(track.raw.height)
                .append(",shape_valid=").append(track.intact()).append(",constraints=").append(track.constraintsDiagnostic())
                .append(",original_height=").append(track.originalHeight)
                .append(",original_padding=").append(track.originalTop).append('/').append(track.originalBottom)
                .append(",padding=").append(view.getPaddingTop()).append('/').append(view.getPaddingBottom())
                .append(",bottom_margin=").append(track.raw.bottomMargin).append(",original_margin=").append(track.original)
                .append(",layout_requested=").append(view.isLayoutRequested())
                .append(",translation_y=").append(view.getTranslationY()).append(",bounds=").append(bounds(view))
                .append(",touch_extra=").append(track.touchTop).append('/').append(track.touchBottom)
                .append(",layout_mode=").append(track.layoutUsesView?"view":"native_hit")
                .append(",reserved=").append(track.reservedBounds);
            android.graphics.drawable.Drawable drawable=view instanceof android.widget.SeekBar
                ?((android.widget.SeekBar)view).getProgressDrawable():null;
            out.append(",drawable_bounds=").append(drawable==null?"unknown":drawable.getBounds());
        }
        count=0;
        if(navigation!=null)for(View view:navigation)if(count++<4)
            out.append(";navigation=").append(Integer.toHexString(view.getId())).append(':').append(restingBounds.apply(view)).append(",current=").append(actualBounds(view))
                .append(",visibility=").append(view.getVisibility()).append(",alpha=").append(view.getAlpha());
        count=0;
        int pages=0;
        for(Branch branch:branches.values()) {
            boolean active=branchInViewport(branch);
            if(pages++<4)out.append(";feed_scope=").append(Integer.toHexString(System.identityHashCode(branch.parent)))
                    .append(",owner=").append(Integer.toHexString(System.identityHashCode(branch.owner)))
                    .append(",page_bounds=").append(bounds(branch.parent)).append(",active=").append(active);
            if(active)for(View view:branch.observed)if(effectiveVisible(view) && count++<6)
                out.append(";upper_control=").append(Integer.toHexString(view.getId())).append(':').append(bounds(view))
                        .append(",instance=").append(Integer.toHexString(System.identityHashCode(view)))
                        .append(",page=").append(Integer.toHexString(System.identityHashCode(branch.parent)));
        }
        return out.toString();
    }
    /** The runtime evidence cap is 1024 characters; keep critical layout fields ahead of optional detail. */
    String geometrySummary(Collection<View> navigation) {
        StringBuilder out=new StringBuilder("density=").append(root.getResources().getDisplayMetrics().density)
                .append(";sync_state=").append(syncState).append(";input_revision=").append(revision)
                .append(";viewport=").append(root.getWidth()).append('x').append(root.getHeight())
                .append(";root_layout_requested=").append(root.isLayoutRequested()).append(";blocked_navigation=").append(blocked.size());
        if(pendingGeometryView!=null)out.append(";pending_target=").append(Integer.toHexString(pendingGeometryView.getId()));
        for(WindowProgress track:windowProgress.values())if(effectiveVisible(track.view)) {
            out.append(";track=").append(Integer.toHexString(track.view.getId())).append(",height=").append(track.view.getHeight())
                    .append(",layout_height=").append(track.raw.height).append(",padding=").append(track.view.getPaddingTop()).append('/').append(track.view.getPaddingBottom())
                    .append(",bottom_margin=").append(track.raw.bottomMargin).append(",original_margin=").append(track.original)
                    .append(",layout_requested=").append(track.view.isLayoutRequested()).append(",bounds=").append(bounds(track.view))
                    .append(",touch_extra=").append(track.touchTop).append('/').append(track.touchBottom)
                    .append(",touch_sample_known=").append(track.touchKnown)
                    .append(",routing=").append(WindowProgressTouchBridge.canRoute(track.view,root))
                    .append(",layout_mode=").append(track.layoutUsesView?"view":"native_hit").append(",reserved=").append(track.reservedBounds);
            break;
        }
        if(navigation!=null)for(int pass=0;pass<2;pass++)for(View nav:navigation) {
            boolean top=LiquidGlassTargets.hasClass(nav,"com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar");
            if(top!=(pass==1))continue;
            String entry=";navigation="+Integer.toHexString(nav.getId())+":"+restingBounds.apply(nav)
                    +",visibility="+nav.getVisibility()+",alpha="+nav.getAlpha();
            if(out.length()+entry.length()<=880)out.append(entry);
        }
        return out.toString();
    }
    String scopeDiagnostic() {
        StringBuilder out=new StringBuilder();
        for(Branch branch:branches.values())if(branchInViewport(branch)) {
            StringBuilder page=new StringBuilder(";page=").append(Integer.toHexString(System.identityHashCode(branch.parent)))
                    .append(",frame=").append(bounds(branch.parent)).append(",carrier_margin=").append(branch.raw.bottomMargin)
                    .append(",original_margin=").append(branch.original);
            for(View view:branch.observed)if(effectiveVisible(view))
                page.append(";upper_control=").append(Integer.toHexString(view.getId())).append(':').append(bounds(view));
            View lowest=null;RectF lowestBounds=null;
            for(View view:branch.capacityNodes)if(ownsContentBounds(view) && painted(view)) {
                RectF box=bounds(view);
                if(finite(box) && (lowestBounds==null || box.bottom>lowestBounds.bottom)){lowest=view;lowestBounds=box;}
            }
            if(lowest!=null)page.append(";lowest_content=").append(Integer.toHexString(lowest.getId())).append(':').append(lowestBounds);
            if(out.length()+page.length()<=880)out.append(page);
        }
        return out.length()==0?"pages=0":out.toString();
    }
    public long inputRevision(){return revision;}
    boolean retryReady(){return !closed && "applied".equals(syncState);}
    boolean awaitingTargetLayout(){return "pending_target_layout".equals(syncState);}

    void rememberHiddenControls(Set<View> hidden) {
        hidden.clear();
        Branch branch=singleRevealBranch();
        if(branch==null)return;
        for(View view:branch.capacityNodes) {
            if((branch.observed.contains(view) || ownsContentBounds(view)) && layoutHidden(view))hidden.add(view);
        }
    }

    private boolean layoutHidden(View view) {
        // GONE transitions request layout; opacity-only changes need not invalidate the cached snapshot.
        for(int depth=0;view!=null && view!=root && depth<=40;depth++) {
            if(view.getVisibility()==View.GONE)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }

    void retainRevealingControls(Set<View> previouslyHidden,Set<View> visible) {
        visible.clear();
        Branch branch=singleRevealBranch();
        if(branch==null || !branch.structureIntact())return;
        for(View view:branch.capacityNodes) {
            if(!previouslyHidden.contains(view) || !painted(view) || !view.isAttachedToWindow())continue;
            boolean protectedSubtree=false;
            for(View protectedView:branch.protectedNodes)if(within(protectedView,view) || within(view,protectedView)){
                protectedSubtree=true;break;
            }
            if(!protectedSubtree)visible.add(view);
        }
    }

    private Branch singleRevealBranch() {
        if(closed || branches.size()>8)return null;
        Branch selected=null;
        for(Branch branch:branches.values())if(branchInViewport(branch)) {
            if(selected!=null || branch.unsafe || branch.capacityNodes.size()>MAX_REVEAL_CONTROLS)return null;
            selected=branch;
        }
        return selected;
    }
    int windowProgressCount(){return windowProgress.size();}
    public String fallbackDetail(){return fallbackDetail;}

    /** Extra native seek padding must yield DOWN to the compact UI it would otherwise intercept. */
    void onTouch(MotionEvent event) {
        if(closed || event==null || event.getActionMasked()!=MotionEvent.ACTION_DOWN)return;
        int[] origin=new int[2];root.getLocationOnScreen(origin);
        float x=event.getRawX()-origin[0],y=event.getRawY()-origin[1];
        boolean occupied=false;
        for(View nav:knownNavigation)if(painted(nav) && contains(actualBounds(nav),x,y)){occupied=true;break;}
        if(!occupied)for(Branch branch:branches.values())if(branchInViewport(branch)) {
            for(View view:branch.capacityNodes)if(ownsContentBounds(view) && painted(view) && contains(actualBounds(view),x,y)) {
                occupied=true;break;
            }
            if(occupied)break;
        }
        for(WindowProgress track:windowProgress.values()) {
            boolean protect=track.routingActive && WindowProgressTouchBridge.canRoute(track.view,root)
                    && occupied && !contains(actualBounds(track.view),x,y);
            WindowProgressTouchBridge.setGestureProtected(track.view,root,protect);
        }
    }
    void onTouchDispatched(MotionEvent event) {
        if(event==null || event.getActionMasked()!=MotionEvent.ACTION_UP && event.getActionMasked()!=MotionEvent.ACTION_CANCEL)return;
        clearGesture();
    }
    void clearGesture(){for(WindowProgress track:windowProgress.values())WindowProgressTouchBridge.clearGesture(track.view);}
    private static boolean contains(RectF bounds,float x,float y){return finite(bounds) && bounds.contains(x,y);}
    private void block(List<Nav> navigation,String reason,View view) {
        if(navigation.isEmpty())return;
        if(blocked.isEmpty()) {
            fallbackDetail="reason="+reason+";class="+(view==null?"unknown":view.getClass().getName())
                    +";id="+(view==null?"none":Integer.toHexString(view.getId()));
            if(view!=null) {
                android.view.animation.Animation animation=view.getAnimation();
                fallbackDetail+=";instance="+Integer.toHexString(System.identityHashCode(view))+";size="+view.getWidth()+"x"+view.getHeight()
                        +";rotation_xy="+view.getRotationX()+"/"+view.getRotationY()
                        +";animation="+(animation==null?"none":animation.hasEnded()?"ended":"active");
                fallbackDetail+=";scale_xy="+view.getScaleX()+"/"+view.getScaleY()
                        +";laid_out="+view.isLaidOut()+";layout_requested="+view.isLayoutRequested()
                        +";transient="+view.hasTransientState()+";matrix_invertible="+invertible(view.getMatrix())
                        +";state_path="+statePath(view);
                int depth=0;
                for(View page=view;page!=null && depth++<40;page=page.getParent() instanceof View?(View)page.getParent():null) {
                    if(page.getId()==PARENT && page instanceof RelativeLayout) {
                        fallbackDetail+=";page="+Integer.toHexString(System.identityHashCode(page))+";page_bounds="+bounds(page);break;
                    }
                    if(page==root)break;
                }
            }
        }
        for(Nav nav:navigation)blocked.add(nav.view);
    }
    private static boolean invertible(Matrix matrix) {
        return matrix!=null && matrix.invert(new Matrix());
    }
    private String statePath(View view) {
        StringBuilder result=new StringBuilder();int depth=0;
        boolean danmaku=view.getId()==DANMAKU_ICON || view.getId()==DANMAKU_FRAME;
        for(View current=view;current!=null && depth<5;depth++) {
            if(depth>0)result.append('>');
            android.view.animation.Animation animation=current.getAnimation();
            result.append(current.getClass().getSimpleName()).append('#')
                    .append(Integer.toHexString(current.getId())).append('[')
                    .append(current.getWidth()).append('x').append(current.getHeight())
                    .append(",s=").append(current.getScaleX()).append('/').append(current.getScaleY())
                    .append(",t=").append(current.hasTransientState()?1:0)
                    .append(",l=").append(current.isLaidOut()?1:0)
                    .append(",r=").append(current.isLayoutRequested()?1:0)
                    .append(",a=").append(animation==null?0:animation.hasEnded()?1:2)
                    .append(",m=").append(invertible(current.getMatrix())?1:0);
            if(danmaku) {
                ViewGroup.LayoutParams size=current.getLayoutParams();
                result.append(",lp=").append(size==null?"none":size.width+"x"+size.height)
                        .append(",measured=").append(current.getMeasuredWidth()).append('x').append(current.getMeasuredHeight())
                        .append(",attached=").append(current.isAttachedToWindow()?1:0)
                        .append(",detaching=").append(current.isTemporarilyDetached()?1:0)
                        .append(",delegate=").append(current.getTouchDelegate()!=null?1:0)
                        .append(",identity=").append(current.getMatrix().isIdentity()?1:0);
                if(current instanceof ViewGroup)result.append(",clip=").append(((ViewGroup)current).getClipChildren()?1:0);
            }
            result.append(']');
            if(current==root)break;
            if(!(current.getParent() instanceof View))break;
            current=(View)current.getParent();
        }
        return result.toString();
    }
    /** A detached identity snapshot, safe while the controller closes rejected bindings. */
    public Set<View> blockedViews(){Set<View> copy=identities();copy.addAll(blocked);return Collections.unmodifiableSet(copy);}
    @Override public void close() {
        if(closed)return;closed=true;
        for(Branch branch:branches.values())branch.restore();branches.clear();blocked.clear();knownNavigation.clear();pendingGeometryView=null;compactReady=false;
        for(WindowProgress track:windowProgress.values())track.restore();windowProgress.clear();
    }

    /** Homepage DPro is fullscreen; only its bottom-constrained slider and linked hints may move. */
    private final class WindowProgress {
        final View view;final ViewGroup parent;final ViewGroup.MarginLayoutParams raw;
        final java.lang.reflect.Field bottom,top,below;
        int original,last;boolean owned;
        int originalHeight,lastHeight,originalTop,lastTop,originalBottom,lastBottom;
        boolean heightOwned,topOwned,bottomOwned;
        float touchTop,touchBottom;
        RectF reservedBounds;
        boolean routingActive,touchKnown,touchAttempted,layoutUsesView;
        long sampledRoutingEpoch=Long.MIN_VALUE;
        int sampledWidth=-1,sampledHeight=-1,sampledLeft,sampledTop,sampledRight,sampledBottom;
        OneShotPreDrawListener touchRefresh;
        WindowProgress(View view) throws ReflectiveOperationException {
            this.view=view;parent=(ViewGroup)view.getParent();raw=(ViewGroup.MarginLayoutParams)view.getLayoutParams();
            bottom=raw.getClass().getField("bottomToBottom");top=raw.getClass().getField("topToTop");below=raw.getClass().getField("topToBottom");
            original=raw.bottomMargin;
            originalHeight=raw.height;originalTop=view.getPaddingTop();originalBottom=view.getPaddingBottom();
        }
        @android.annotation.SuppressLint("ResourceType") // Exact Pro ID belongs to verified host 41.0.0, not module R.
        static boolean matches(View view) {
            return view.getId()==0x7f0a93b7
                    && LiquidGlassTargets.hasClass(view,"com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView")
                    && view.getParent() instanceof ViewGroup
                    && LiquidGlassTargets.hasClass((View)view.getParent(),"com.ss.android.ugc.aweme.o.seekbar.DProSeekBar")
                    && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams;
        }
        boolean intact() {
            if(!matches(view) || view.getParent()!=parent || view.getLayoutParams()!=raw
                    || raw.height==ViewGroup.LayoutParams.MATCH_PARENT || !within(view,root))return false;
            try{return bottom.getInt(raw)==0 && top.getInt(raw)==-1 && below.getInt(raw)==-1;}
            catch(IllegalAccessException | RuntimeException ignored){return false;}
        }
        String constraintsDiagnostic() {
            try{return bottom.getInt(raw)+"/"+top.getInt(raw)+"/"+below.getInt(raw);}
            catch(IllegalAccessException | RuntimeException ignored){return "unavailable";}
        }
        boolean layoutPending() {
            if(!view.isLaidOut() || view.getWidth()<=0 || view.getHeight()<=0 || requestedSizePending(view))return true;
            return view.isLayoutRequested() && (!intact() || bottomAnchorPending(view,parent,raw));
        }
        void adoptHostMargin(){if(!owned || raw.bottomMargin!=last){original=raw.bottomMargin;owned=false;}}
        boolean compactPadding() {
            if(!heightOwned || raw.height!=lastHeight){originalHeight=raw.height;heightOwned=false;}
            if(!topOwned || view.getPaddingTop()!=lastTop){originalTop=view.getPaddingTop();topOwned=false;}
            if(!bottomOwned || view.getPaddingBottom()!=lastBottom){originalBottom=view.getPaddingBottom();bottomOwned=false;}
            long content=(long)originalHeight-originalTop-originalBottom;
            if(originalHeight<=0 || originalTop<0 || originalBottom<0 || content<=0) {
                boolean restored=restorePadding();if(restored)refreshTouchAfterLayout();return restored;
            }
            // Native Pro height includes transparent touch padding; keep the content and a compact real hit area.
            int height=(int)Math.min(originalHeight,Math.max(content,Math.round(48*root.getResources().getDisplayMetrics().density)));
            int budget=height-(int)content;
            int upper=Math.min(originalTop,budget/2),lower=Math.min(originalBottom,budget-upper);
            upper=Math.min(originalTop,budget-lower);
            boolean changed=false;
            if(raw.height!=height){raw.height=height;view.setLayoutParams(raw);changed=true;}
            if(view.getPaddingTop()!=upper || view.getPaddingBottom()!=lower) {
                view.setPadding(view.getPaddingLeft(),upper,view.getPaddingRight(),lower);changed=true;
            }
            lastHeight=height;heightOwned=height!=originalHeight;
            lastTop=upper;topOwned=upper!=originalTop;lastBottom=lower;bottomOwned=lower!=originalBottom;
            if(changed)refreshTouchAfterLayout();return changed;
        }
        boolean restorePadding() {
            boolean changed=false;
            if(heightOwned && view.getLayoutParams()==raw && raw.height==lastHeight) {
                raw.height=originalHeight;view.setLayoutParams(raw);changed=true;
            }
            int upper=topOwned && view.getPaddingTop()==lastTop?originalTop:view.getPaddingTop();
            int lower=bottomOwned && view.getPaddingBottom()==lastBottom?originalBottom:view.getPaddingBottom();
            if(view.getPaddingTop()!=upper || view.getPaddingBottom()!=lower) {
                view.setPadding(view.getPaddingLeft(),upper,view.getPaddingRight(),lower);changed=true;
            }
            heightOwned=false;topOwned=false;bottomOwned=false;
            return changed;
        }
        RectF naturalBounds() {
            RectF box=bounds(view);float scale=scaleY(parent);
            if(box==null || !Float.isFinite(scale) || scale<=0)return null;
            // The full View (including native padding/thumb) owns layout; extra g owns gesture reach.
            if(!compactRouting()){box.top-=touchTop;box.bottom+=touchBottom;}
            if(owned && raw.bottomMargin==last)box.offset(0,(last-original)*scale);
            return box;
        }
        boolean compactRouting(){return compactReady && routingActive && touchKnown && WindowProgressTouchBridge.canRoute(view,root);}
        boolean touchShapeChanged() {
            return sampledWidth!=view.getWidth() || sampledHeight!=view.getHeight()
                    || sampledLeft!=view.getPaddingLeft() || sampledTop!=view.getPaddingTop()
                    || sampledRight!=view.getPaddingRight() || sampledBottom!=view.getPaddingBottom();
        }
        void onDiscovery() {
            // Late host component registration retries with the bounded scan, never every animation frame.
            if(routingActive && touchRefresh==null && (!touchKnown
                    || WindowProgressTouchBridge.isRoutingAvailable() && !WindowProgressTouchBridge.canRoute(view,root)))refreshTouchAfterLayout();
        }
        long inputSignature() {
            long value=mix(System.identityHashCode(view),original);value=mix(value,intact()?1:0);
            value=mix(value,effectiveVisible(view)?1:0);RectF box=naturalBounds();
            if(box!=null){value=mix(value,Math.round(box.top));value=mix(value,Math.round(box.bottom));}
            return value;
        }
        boolean sync(List<Nav> navigation,float ceiling) {
            if(navigation.isEmpty() || !intact() || !effectiveVisible(view)){reservedBounds=null;return restore();}
            routingActive=true;
            if(touchRefresh==null && (!touchAttempted || sampledRoutingEpoch!=WindowProgressTouchBridge.epoch() || touchShapeChanged()))refreshTouchAfterLayout();
            layoutUsesView=compactRouting();
            RectF box=naturalBounds();
            // Press/drag animations can temporarily obscure geometry; retain the current hitbox until they settle.
            if(box==null)return false;
            float extra=0;
            for(Nav nav:navigation)if(horizontal(box,nav.bounds))extra=Math.max(extra,box.bottom-nav.bounds.top+progressGap());
            if(box.top-extra<ceiling){reservedBounds=null;return restoreMargin();}
            reservedBounds=new RectF(box);reservedBounds.offset(0,-extra);
            float scale=scaleY(parent);long margin=(long)original+(long)Math.ceil(extra/scale);
            if(margin<Integer.MIN_VALUE || margin>Integer.MAX_VALUE || raw.bottomMargin==(int)margin)return false;
            raw.bottomMargin=(int)margin;last=(int)margin;owned=last!=original;view.setLayoutParams(raw);
            refreshTouchAfterLayout();return true;
        }
        boolean restore() {
            routingActive=false;layoutUsesView=false;WindowProgressTouchBridge.forget(view);
            boolean changed=restorePadding();reservedBounds=null;
            changed|=restoreMargin();
            if(changed)refreshTouchAfterLayout();return changed;
        }
        boolean restoreMargin() {
            boolean restore=owned && view.getLayoutParams()==raw && raw.bottomMargin==last;owned=false;
            if(!restore)return false;
            raw.bottomMargin=original;view.setLayoutParams(raw);refreshTouchAfterLayout();return true;
        }
        void refreshTouchAfterLayout() {
            if(touchRefresh!=null)touchRefresh.removeListener();
            touchRefresh=OneShotPreDrawListener.add(view,()->{
                touchRefresh=null;
                // Unrelated video/overlay relayouts must not starve the already-laid-out slider's native hit cache.
                if(layoutPending()){refreshTouchAfterLayout();return;}
                touchAttempted=true;sampledRoutingEpoch=WindowProgressTouchBridge.epoch();
                sampledWidth=view.getWidth();sampledHeight=view.getHeight();
                sampledLeft=view.getPaddingLeft();sampledTop=view.getPaddingTop();
                sampledRight=view.getPaddingRight();sampledBottom=view.getPaddingBottom();
                RectF hit=WindowProgressTouchBridge.refresh(view,root,!closed && routingActive);Matrix map=toRoot(view);
                if(hit==null || map==null){touchKnown=false;return;}
                RectF actual=new RectF(0,0,view.getWidth(),view.getHeight());map.mapRect(actual);
                if(!finite(actual)){touchKnown=false;return;}
                // Keep native reach for diagnostics and conservative fallback; routed layouts reserve the View.
                touchTop=Math.max(0,actual.top-hit.top);touchBottom=Math.max(0,hit.bottom-actual.bottom);
                touchKnown=true;
                if(!closed)view.invalidate();
            });
        }
    }

    private final class Branch {
        final ViewGroup owner;final RelativeLayout parent;final boolean carrier;
        final RelativeLayout.LayoutParams raw;
        final List<View> observed=new ArrayList<>(),affected=new ArrayList<>(),protectedNodes=new ArrayList<>(),capacityNodes=new ArrayList<>(),progressContent=new ArrayList<>();
        final List<RectF> recycledObstacles=new ArrayList<>();
        final List<Shape> shapes=new ArrayList<>();final List<Rule> rules=new ArrayList<>();
        final Map<Integer,View> siblings=new HashMap<>();
        int original,last;boolean owned,unsafe,unknownBounds;View unknownView;
        float contentTop;View unknownContent;
        Branch(ViewGroup owner,RelativeLayout parent,boolean carrier) {
            this.owner=owner;this.parent=parent;this.carrier=carrier;
            raw=(RelativeLayout.LayoutParams)owner.getLayoutParams();original=raw.bottomMargin;
        }
        void cacheCapacityNodes() {
            capacityNodes.clear();progressContent.clear();
            for(View node:affected)for(View observer:observed)if(within(node,observer)) {
                capacityNodes.add(node);
                if(observer.getId()==PROGRESS || observer.getId()==ABOVE)progressContent.add(node);
                break;
            }
        }
        void cache(List<View> inventory,Set<View> present) {
            observed.clear();affected.clear();protectedNodes.clear();shapes.clear();rules.clear();siblings.clear();unsafe=false;
            for(int i=0;i<parent.getChildCount();i++) {
                View sibling=parent.getChildAt(i);if(sibling.getId()!=View.NO_ID)siblings.put(sibling.getId(),sibling);
                if(sibling.getLayoutParams() instanceof RelativeLayout.LayoutParams)rules.add(new Rule(sibling));
            }
            List<View> roots=new ArrayList<>();roots.add(owner);
            if(carrier) {
                for(int i=0;i<owner.getChildCount();i++) {
                    View child=owner.getChildAt(i);if(child.getId()==PROGRESS || child.getId()==ABOVE)observed.add(child);
                }
                for(View sibling:siblings.values())if(sibling!=owner && reaches(sibling,owner.getId(),siblings)) {
                    roots.add(sibling);
                    if(sibling.getId()==METADATA || sibling.getId()==RIGHT)observed.add(sibling);
                }
            }else observed.add(owner);
            for(View node:inventory) {
                boolean moves=false;for(View scope:roots)if(within(node,scope)){moves=true;break;}
                if(!moves)continue;
                affected.add(node);if(protectedContent(node))protectedNodes.add(node);
                // Opaque renderers are discovery boundaries; their private children are intentionally absent.
                if(node instanceof ViewGroup && !LiquidGlassTargets.opaque(node)) {
                    Shape shape=new Shape((ViewGroup)node);shapes.add(shape);
                    for(View child:shape.children)if(!present.contains(child))unsafe=true;
                }
            }
            if(!present.contains(owner) || affected.size()>1024)unsafe=true;
        }
        boolean structureIntact() {
            if(owner.getParent()!=parent || owner.getLayoutParams()!=raw || !within(owner,root)
                    || (carrier?!carrierShape(owner):!legacyShape(owner)))return false;
            for(Shape shape:shapes)if(!shape.intact())return false;
            for(Rule rule:rules)if(!rule.intact())return false;
            return true;
        }
        void adoptHostMargin() {
            if(!owned || raw.bottomMargin!=last){original=raw.bottomMargin;owned=false;}
        }
        float rootShift(){return owned && owner.getLayoutParams()==raw && raw.bottomMargin==last?(last-original)*scaleY(parent):0;}
        View visibleProtected() {
            for(View view:protectedNodes)if(painted(view))return view;
            return null;
        }
        List<RectF> observations() {
            List<RectF> boxes=new ArrayList<>(recycledObstacles);float shift=rootShift();unknownBounds=false;unknownView=null;
            if(!Float.isFinite(shift))return boxes;
            for(View view:observed)if(effectiveVisible(view)) {
                if(view.getLayoutParams()!=null && view.getLayoutParams().height==ViewGroup.LayoutParams.MATCH_PARENT){unknownBounds=true;unknownView=view;continue;}
                RectF box=bounds(view);
                if(!finite(box)){if(view.getWidth()>0 && view.getHeight()>0){unknownBounds=true;unknownView=view;}continue;}
                if(view.getId()!=PROGRESS && view.getId()!=ABOVE && intersectsRoot(box)) {
                    box.offset(0,shift);boxes.add(box);
                }
            }
            for(View view:progressContent)if(ownsContentBounds(view) && effectiveVisible(view)) {
                RectF box=bounds(view);
                if(box==null)box=recycledDanmakuBounds(view);
                if(!finite(box)){if(view.getWidth()>0 && view.getHeight()>0){unknownBounds=true;unknownView=view;}continue;}
                if(!intersectsRoot(box))continue;
                box.offset(0,shift);boxes.add(box);
            }
            return boxes;
        }
        long inputSignature() {
            long value=mix(mix(System.identityHashCode(owner),System.identityHashCode(parent)),original);value=mix(value,unsafe?1:0);
            for(View view:protectedNodes)value=mix(mix(value,System.identityHashCode(view)),painted(view)?1:0);
            Matrix parentMap=toRoot(parent);
            if(parentMap!=null){float[] values=new float[9];parentMap.getValues(values);for(float matrixValue:values)value=mix(value,Float.floatToIntBits(matrixValue));}
            for(Shape shape:shapes)value=mix(value,shape.liveSignature());
            for(Rule rule:rules)value=mix(value,rule.liveSignature());
            float shift=rootShift();
            contentTop=Float.POSITIVE_INFINITY;unknownContent=null;recycledObstacles.clear();
            for(View view:capacityNodes) {
                boolean ownsBounds=ownsContentBounds(view);value=mix(value,ownsBounds?1:0);
                if(!ownsBounds)continue;
                value=mix(value,System.identityHashCode(view));boolean visible=painted(view);value=mix(value,visible?1:0);
                if(!visible || !Float.isFinite(shift))continue;
                RectF box=bounds(view);
                boolean recycled=box==null;
                if(recycled) {
                    box=recycledDanmakuBounds(view);
                    if(box==null)box=recycledDiggBounds(view);
                }
                boolean reliable=finite(box);
                boolean inRoot=reliable && intersectsRoot(box);
                value=mix(value,!reliable?1:recycled && inRoot?3:2);
                if(!reliable){unknownContent=view;continue;}
                box.offset(0,shift);
                value=mix(value,Math.round(box.left));value=mix(value,Math.round(box.right));
                value=mix(value,Math.round(box.top));value=mix(value,Math.round(box.bottom));
                if(intersectsRoot(box))contentTop=Math.min(contentTop,box.top);
                // Progress descendants already reserve their bounds in observations().
                if(recycled && inRoot && !progressContent.contains(view))recycledObstacles.add(box);
            }
            for(View view:observed) {
                value=mix(value,System.identityHashCode(view));boolean visible=effectiveVisible(view);value=mix(value,visible?1:0);
                if(view.getLayoutParams()!=null)value=mix(value,view.getLayoutParams().height);
                if(visible) {
                    RectF box=bounds(view);boolean reliable=finite(box) && Float.isFinite(shift);
                    value=mix(value,view.getWidth()<=0 || view.getHeight()<=0?0:reliable?2:1);
                    if(reliable) {
                        value=mix(value,Math.round(box.left));value=mix(value,Math.round(box.right));
                        value=mix(value,Math.round(box.top+shift));value=mix(value,Math.round(box.bottom+shift));
                    }
                }
            }
            return value;
        }
        boolean apply(float extra,float scale) {
            if(!structureIntact() || !Float.isFinite(extra) || !Float.isFinite(scale) || scale<=0)return false;
            long desired=(long)original+(long)Math.ceil(extra/scale);
            if(desired<Integer.MIN_VALUE || desired>Integer.MAX_VALUE)return false;
            int target=(int)desired;if(raw.bottomMargin==target)return false;
            raw.bottomMargin=target;last=target;owned=target!=original;
            owner.setLayoutParams(raw);return true;
        }
        boolean restore() {
            boolean restore=owned && owner.getLayoutParams()==raw && raw.bottomMargin==last;owned=false;
            if(!restore)return false;
            raw.bottomMargin=original;owner.setLayoutParams(raw);return true;
        }
    }

    private static boolean carrierShape(ViewGroup view) {
        if(!LiquidGlassTargets.hasClass(view,"androidx.appcompat.widget.LinearLayoutCompat")
                || !(view.getLayoutParams() instanceof RelativeLayout.LayoutParams))return false;
        RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)view.getLayoutParams();
        if(p.height!=ViewGroup.LayoutParams.WRAP_CONTENT || p.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM)!=RelativeLayout.TRUE)return false;
        try {
            if(((Number)view.getClass().getMethod("getOrientation").invoke(view)).intValue()!=LinearLayout.VERTICAL)return false;
        }catch(ReflectiveOperationException | RuntimeException failure){return false;}
        if(view.getChildCount()!=2)return false;
        boolean progress=false,above=false;
        for(int i=0;i<2;i++) {
            View child=view.getChildAt(i);
            if(!(child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))return false;
            progress|=child.getId()==PROGRESS;above|=child.getId()==ABOVE;
        }
        return progress && above;
    }
    private static boolean legacyShape(View view) {
        if(!(view instanceof RelativeLayout) || !(view.getLayoutParams() instanceof RelativeLayout.LayoutParams))return false;
        RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)view.getLayoutParams();
        return p.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM)==RelativeLayout.TRUE && p.getRule(RelativeLayout.ABOVE)==0;
    }
    private static boolean reaches(View view,int target,Map<Integer,View> siblings) {
        Set<View> seen=identities();
        for(int i=0;i<32 && view!=null && seen.add(view);i++) {
            if(!(view.getLayoutParams() instanceof RelativeLayout.LayoutParams))return false;
            int next=((RelativeLayout.LayoutParams)view.getLayoutParams()).getRule(RelativeLayout.ABOVE);
            if(next==target)return true;if(next==0)return false;view=siblings.get(next);
        }
        return false;
    }
    private static boolean protectedContent(View view) {
        String name=view.getClass().getName().toLowerCase(Locale.ROOT);
        return LiquidGlassTargets.isMedia(view) || LiquidGlassTargets.opaque(view)
                || name.contains("subtitle") || name.contains("caption");
    }
    private static boolean ownsContentBounds(View view) {
        // Transparent native wrappers contribute their descendants, while paint and hit areas keep their full bounds.
        return !(view instanceof ViewGroup) || view.getBackground()!=null || view.getForeground()!=null
                || !view.willNotDraw() || view.isClickable() || view.isLongClickable() || view.isFocusable();
    }
    private boolean effectiveVisible(View view) {
        for(int depth=0;view!=null && depth<=40;depth++) {
            if(view.getVisibility()!=View.VISIBLE || view.getAlpha()<=0)return false;
            if(view==root)return true;view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }
    private boolean painted(View view){return view.getWidth()>0 && view.getHeight()>0 && effectiveVisible(view);}

    /** The 41.0.0 vertical danmaku control clips its FrameLayout to that frame's bounds. */
    private RectF recycledDanmakuBounds(View view) {
        View icon=view;
        View frame=null;
        boolean clipTarget=view instanceof FrameLayout && view.getClass().getName().equals(DANMAKU_CLIP);
        if(view.getId()==DANMAKU_ICON && view.getParent() instanceof FrameLayout) {
            frame=(View)view.getParent();
        }else if(view.getId()==DANMAKU_FRAME && view.getClass()==FrameLayout.class) {
            frame=view;
        }else if(clipTarget) {
            FrameLayout group=(FrameLayout)view;
            for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i).getId()==DANMAKU_FRAME) {
                if(frame!=null)return null;
                frame=group.getChildAt(i);
            }
        }else return null;
        if(frame==null || frame.getClass()!=FrameLayout.class)return null;
        FrameLayout group=(FrameLayout)frame;
        icon=null;
        for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i).getId()==DANMAKU_ICON) {
            if(icon!=null)return null;
            icon=group.getChildAt(i);
        }
        if(icon==null || !icon.getClass().getName().equals("androidx.appcompat.widget.AppCompatImageView")
                || !attachedForBounds(icon) || icon.hasTransientState() || icon.getAnimation()!=null
                || !icon.getMatrix().isIdentity() || icon.getRotationX()!=0 || icon.getRotationY()!=0
                || !(icon.getLayoutParams() instanceof FrameLayout.LayoutParams))return null;
        if(frame.getClass()!=FrameLayout.class || frame.getId()!=DANMAKU_FRAME || !attachedForBounds(frame)
                || !(frame.getParent() instanceof FrameLayout))return null;
        FrameLayout clip=(FrameLayout)frame.getParent();
        if(!clip.getClass().getName().equals(DANMAKU_CLIP) || (clipTarget && clip!=view)
                || !attachedForBounds(clip) || !clip.getClipChildren() || clip.getTouchDelegate()!=null
                || clip.hasTransientState() || clip.getAnimation()!=null || !invertible(clip.getMatrix())
                || clip.getRotationX()!=0 || clip.getRotationY()!=0 || frame.getTouchDelegate()!=null)return null;
        ViewGroup.LayoutParams size=icon.getLayoutParams();
        if(size.width!=icon.getWidth() || size.height!=icon.getHeight())return null;
        if(clipTarget)return retainedDanmakuBounds(clip);
        RectF frameBounds=bounds(frame);
        if(frameBounds!=null || retainedBounds==null)return frameBounds;
        return retainedDanmakuBounds(frame);
    }

    private RectF retainedDanmakuBounds(View target) {
        if(retainedBounds==null)return null;
        // On feed rebind, the control and its plain mounting frames can retain size after losing layout flags.
        int retainedDepth=0;
        View current=target;
        for(int depth=1;current!=root && depth<=4;depth++) {
            if(depth>2 && current.getClass()!=FrameLayout.class)break;
            if(!attachedForBounds(current) || current.getTouchDelegate()!=null)return null;
            if(!current.isLaidOut()) {
                if(!hasRetainedFrameSize(current))return null;
                retainedDepth=depth;
            }
            if(!(current.getParent() instanceof View))return null;
            current=(View)current.getParent();
        }
        return retainedDepth==0?null:retainedBounds.apply(target,retainedDepth);
    }

    private static boolean hasRetainedFrameSize(View view) {
        if(!(view.getLayoutParams() instanceof FrameLayout.LayoutParams))return false;
        ViewGroup.LayoutParams size=view.getLayoutParams();
        int width=view.getWidth(),height=view.getHeight();
        return width>0 && height>0 && width==view.getMeasuredWidth() && height==view.getMeasuredHeight()
                && retainedDimension(size.width,width) && retainedDimension(size.height,height);
    }

    private static boolean retainedDimension(int declared,int actual) {
        return declared==ViewGroup.LayoutParams.MATCH_PARENT || declared==ViewGroup.LayoutParams.WRAP_CONTENT
                || declared==actual;
    }

    private static boolean attachedForBounds(View view) {
        return view.isAttachedToWindow() && !view.isTemporarilyDetached() && !view.isLayoutRequested();
    }

    /** A reused 41.0.0 like renderer can lose its layout flag while its action still clips it. */
    private RectF recycledDiggBounds(View view) {
        View animation=view;
        if(view.getId()==View.NO_ID && view.getClass().getName().equals("com.ss.android.ugc.aweme.feed.widget.DiggAnimationView")) {
            if(!stableUnlaidNode(view) || !(view.getParent() instanceof FrameLayout))return null;
            animation=(View)view.getParent();
        }
        if(animation.getId()!=DIGG_ANIMATION || !animation.getClass().getName().equals("com.ss.android.ugc.aweme.feed.widget.DiggAnimationLayout")
                || !stableUnlaidNode(animation)
                || !(animation.getParent() instanceof FrameLayout))return null;
        View frame=(View)animation.getParent();
        if(frame.getId()!=DIGG_FRAME || !frame.isLaidOut() || frame.isLayoutRequested()
                || !(frame.getParent() instanceof LinearLayout))return null;
        View action=(View)frame.getParent();
        if(action.getId()!=DIGG_ACTION || action.isLayoutRequested() || !(action.getParent() instanceof FrameLayout))return null;
        FrameLayout clip=(FrameLayout)action.getParent();
        if(clip.getId()!=DIGG_CLIP || clip.isLayoutRequested() || !clip.getClipChildren()
                || !clip.getClass().getName().equals("com.bytedance.highperformanceview.hpview.HPFrameLayout"))return null;
        return bounds(action);
    }
    private static boolean stableUnlaidNode(View view) {
        return !view.isLaidOut() && !view.isLayoutRequested() && !view.isTemporarilyDetached()
                && !view.hasTransientState() && view.getAnimation()==null && view.getMatrix().isIdentity()
                && view.getRotationX()==0 && view.getRotationY()==0;
    }
    private RectF bounds(View view) {
        if(view.getWidth()<=0 || view.getHeight()<=0)return null;
        RectF box=restingBounds.apply(view);return finite(box)?new RectF(box):null;
    }
    private float scaleY(View view) {
        Matrix map=toRoot(view);if(map==null)return Float.NaN;
        float[] values=new float[9];map.getValues(values);
        for(float value:values)if(!Float.isFinite(value))return Float.NaN;
        if(Math.abs(values[Matrix.MSKEW_X])>.0001f || Math.abs(values[Matrix.MSKEW_Y])>.0001f
                || Math.abs(values[Matrix.MPERSP_0])>.0001f || Math.abs(values[Matrix.MPERSP_1])>.0001f
                || Math.abs(values[Matrix.MPERSP_2]-1)>.0001f)return Float.NaN;
        return values[Matrix.MSCALE_Y]>0?values[Matrix.MSCALE_Y]:Float.NaN;
    }
    private Matrix toRoot(View view) {
        if(!within(view,root))return null;
        Matrix map=new Matrix();View current=view;
        for(int depth=0;current!=root && depth<40;depth++) {
            map.postConcat(current.getMatrix());
            if(!(current.getParent() instanceof View))return null;
            View parent=(View)current.getParent();
            map.postTranslate(current.getLeft()-parent.getScrollX(),current.getTop()-parent.getScrollY());current=parent;
        }
        return current==root?map:null;
    }
    private boolean intersectsRoot(RectF box){return box.right>0 && box.bottom>0 && box.left<root.getWidth() && box.top<root.getHeight();}
    private float progressGap(){return 4*root.getResources().getDisplayMetrics().density+progressMotionMargin;}
    private float gap(){return 14*root.getResources().getDisplayMetrics().density;}
    private static boolean horizontal(RectF a,RectF b){return a.right>b.left && a.left<b.right;}
    private static boolean finite(RectF box){return box!=null && !box.isEmpty() && Float.isFinite(box.left) && Float.isFinite(box.top) && Float.isFinite(box.right) && Float.isFinite(box.bottom);}
    private static boolean within(View view,View ancestor) {
        for(int depth=0;view!=null && depth<=40;depth++){if(view==ancestor)return true;view=view.getParent() instanceof View?(View)view.getParent():null;}return false;
    }
    private static long mix(long hash,long value){return (hash^value)*1099511628211L;}
    private static Set<View> identities(){return Collections.newSetFromMap(new IdentityHashMap<>());}
    private static final class Nav {
        final View view;final RectF bounds;Nav(View view,RectF bounds){this.view=view;this.bounds=bounds;}
    }
    private static final class Plan {
        final Branch branch;final float extra,scale;Plan(Branch branch,float extra,float scale){this.branch=branch;this.extra=extra;this.scale=scale;}
    }
    private static final class Shape {
        final ViewGroup group;final View[] children;
        Shape(ViewGroup group){this.group=group;children=new View[group.getChildCount()];for(int i=0;i<children.length;i++)children[i]=group.getChildAt(i);}
        boolean intact(){if(group.getChildCount()!=children.length)return false;for(int i=0;i<children.length;i++)if(group.getChildAt(i)!=children[i])return false;return true;}
        long liveSignature(){long hash=mix(System.identityHashCode(group),group.getChildCount());for(int i=0;i<Math.min(256,group.getChildCount());i++)hash=mix(hash,System.identityHashCode(group.getChildAt(i)));return hash;}
    }
    private static final class Rule {
        final View view;final int above,bottom;
        Rule(View view){this.view=view;RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)view.getLayoutParams();above=p.getRule(RelativeLayout.ABOVE);bottom=p.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM);}
        boolean intact(){if(!(view.getLayoutParams() instanceof RelativeLayout.LayoutParams))return false;RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)view.getLayoutParams();return p.getRule(RelativeLayout.ABOVE)==above && p.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM)==bottom;}
        long liveSignature(){if(!(view.getLayoutParams() instanceof RelativeLayout.LayoutParams))return 0;RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)view.getLayoutParams();return mix(mix(System.identityHashCode(view),p.getRule(RelativeLayout.ABOVE)),p.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM));}
    }
}
