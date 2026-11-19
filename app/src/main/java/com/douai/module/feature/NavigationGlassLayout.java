package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.method.TransformationMethod;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Owns only reversible geometry; navigation content and its interactions remain host-owned. */
public final class NavigationGlassLayout implements AutoCloseable {
    private static final int ROOT_VIEW=0x7f0a7983;
    private static final int[] TITLE_FRAMES={0x7f0abca6,0x7f0abc82,0x7f0abc52};
    private static final String TOP_UI="com.ss.android.ugc.aweme.homepage.tab.top.ui.";
    private static final String TOP_ITEM=TOP_UI+"tabstrip.item.HomeTopTabItemViewNew";
    private static final String TOP_CORNER="com.ss.android.ugc.aweme.homepage.topcorner.ui.TopCornerEntranceView";
    private static final int TOP_STRIP=0x7f0a4f62,TOP_SCROLL=0x7f0a5059,TOP_ROW=0x7f0a9ee0,TOP_MORE=0x7f0ad4ac;
    private static final int MAX_TOP_CAPACITY_NODES=256,MAX_TOP_CAPACITY_DEPTH=8;
    private final View owner;
    private final boolean top;
    private final Value left,up,right,down,minimumHeight;
    private final Map<View,Height> titleHeights=new IdentityHashMap<>();
    private final SparseBooleanArray topTabIds=new SparseBooleanArray();
    private final SparseIntArray topTabsByAnchor=new SparseIntArray();
    private Height titleContainer;
    private OwnerParams params;
    private ScrollMargins scrollMargins;
    private View compactScroll;
    private int compactScrollStartMargin,compactScrollEndMargin;
    private String topCapacityDiagnostic="result=not_evaluated";
    private boolean closed;

    public NavigationGlassLayout(View owner,boolean top) {
        this.owner=java.util.Objects.requireNonNull(owner);this.top=top;
        left=new Value(owner::getPaddingLeft,v->owner.setPadding(v,owner.getPaddingTop(),owner.getPaddingRight(),owner.getPaddingBottom()));
        up=new Value(owner::getPaddingTop,v->owner.setPadding(owner.getPaddingLeft(),v,owner.getPaddingRight(),owner.getPaddingBottom()));
        right=new Value(owner::getPaddingRight,v->owner.setPadding(owner.getPaddingLeft(),owner.getPaddingTop(),v,owner.getPaddingBottom()));
        down=new Value(owner::getPaddingBottom,v->owner.setPadding(owner.getPaddingLeft(),owner.getPaddingTop(),owner.getPaddingRight(),v));
        minimumHeight=new Value(owner::getMinimumHeight,owner::setMinimumHeight);
    }

    /** Returns true only when a layout field was written. Call after applying the current label style. */
    public boolean refresh(Collection<TextView> labels) {
        return refresh(labels,0);
    }

    /** Reserve both motion directions and keep the bottom capsule away from the screen edge. */
    public boolean refresh(Collection<TextView> labels,int motionMargin) {
        if(closed)return false;
        if(!(owner.getParent() instanceof ViewGroup))return releaseScrollMargins();
        ViewGroup parent=(ViewGroup)owner.getParent();
        int available=parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight();
        ViewGroup.LayoutParams current=owner.getLayoutParams();
        if(available<=0 || !(current instanceof ViewGroup.MarginLayoutParams))return releaseScrollMargins();
        if(params==null || params.raw!=current)params=new OwnerParams((ViewGroup.MarginLayoutParams)current);

        int paddingLeft=Math.max(dp(8),left.baseline()),paddingRight=Math.max(dp(8),right.baseline());
        int paddingTop=Math.max(dp(top?8:10),up.baseline()),paddingBottom=Math.max(dp(top?8:10),down.baseline());
        int contentWidth=0,contentHeight=0,labelCount=0;
        if(labels!=null)for(TextView label:labels) {
            if(label==null || label.getVisibility()!=View.VISIBLE || !descendant(label))continue;
            if(top && !visibleThroughOwner(label))continue;
            int[] size=naturalSize(label);contentWidth=Math.max(contentWidth,size[0]);
            contentHeight=Math.max(contentHeight,size[1]);labelCount++;
        }
        int contentMinimum=top?dp(44):0;
        boolean changed=false;
        if(top) {
            changed|=syncTitleContainer(parent);
            syncTitleFrames();
            for(Height height:titleHeights.values())if(height.view.getVisibility()!=View.GONE)
                contentMinimum=Math.max(contentMinimum,height.value.baseline());
        }
        int targetHeight=Math.max(dp(top?64:72),Math.max(minimumHeight.baseline(),params.height.baseline()));
        targetHeight=Math.max(targetHeight,saturatedSum(Math.max(contentMinimum,contentHeight),paddingTop,paddingBottom));

        int safeMargin=Math.max(0,Math.max(params.marginLeft.baseline(),params.marginRight.baseline()));
        if(params.marginStart!=null)safeMargin=Math.max(safeMargin,Math.max(params.marginStart.baseline(),params.marginEnd.baseline()));
        int maximumWidth=Math.max(0,available-2*Math.min(available/2,safeMargin));
        if(maximumWidth==0)return releaseScrollMargins()|changed;
        int baseWidth=params.width.baseline();
        if(baseWidth<=0)baseWidth=maximumWidth;else baseWidth=Math.min(baseWidth,maximumWidth);
        int minimumWidth=saturatedSum(contentWidth,paddingLeft,paddingRight);
        if(!top) {
            int items=itemCount(labelCount);
            long slots=(long)items*Math.max(dp(48),(long)contentWidth+dp(8));
            minimumWidth=(int)Math.min(Integer.MAX_VALUE,slots+paddingLeft+(long)paddingRight);
        }
        int targetWidth=Math.min(maximumWidth,Math.max(Math.max(1,baseWidth-dp(top?32:48)),minimumWidth));
        if(top) {
            compactScroll=null;
            int capacity=topCapacity(paddingLeft,paddingRight);
            if(capacity>=0) {
                int required=Math.max(owner.getMinimumWidth(),saturatedSum(capacity,paddingLeft,paddingRight));
                targetWidth=Math.max(1,Math.max(minimumWidth,required));
                if(targetWidth>maximumWidth) {
                    capacityFallback(owner,"capacity_exceeds_viewport");
                    compactScroll=null;
                    targetWidth=maximumWidth;
                }
            }
            changed|=syncScrollMargins();
        }
        int margin=(available-targetWidth)/2;
        changed|=params.apply(targetWidth,targetHeight,margin,Math.max(0,motionMargin),top?0:dp(16));
        changed|=left.apply(paddingLeft);changed|=up.apply(paddingTop);
        changed|=right.apply(paddingRight);changed|=down.apply(paddingBottom);
        changed|=minimumHeight.apply(targetHeight);
        if(top)for(Height height:titleHeights.values()) {
            if(height.view.getVisibility()!=View.GONE && height.value.apply(targetHeight-paddingTop-paddingBottom)) {
                height.view.requestLayout();changed=true;
            }
        }
        if(titleContainer!=null) {
            int originalHeight=titleContainer.value.baseline();
            if(originalHeight>0) {
                int margins=Math.max(0,params.raw.topMargin)+Math.max(0,params.raw.bottomMargin);
                int required=saturatedSum(targetHeight,parent.getPaddingTop(),parent.getPaddingBottom());
                required=saturatedSum(required,margins,0);
                if(titleContainer.value.apply(Math.max(originalHeight,required))){parent.requestLayout();changed=true;}
            }
        }
        if(changed)owner.requestLayout();
        return changed;
    }

    String topCapacityDiagnostic(){return top?topCapacityDiagnostic:"result=not_top";}

    private boolean descendant(View view) {
        for(int depth=0;view!=null && depth<=40;depth++) {
            if(view==owner)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }

    private boolean visibleThroughOwner(View view) {
        for(int depth=0;view!=null && depth<=40;depth++) {
            if(view.getVisibility()!=View.VISIBLE)return false;
            if(view==owner)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }

    /** Only the known title frames with native TOP slots can replace the legacy width floor. */
    private int topCapacity(int paddingLeft,int paddingRight) {
        topCapacityDiagnostic="result=fallback;reason=owner_not_view_group";
        if(!(owner instanceof ViewGroup))return -1;
        ViewGroup group=(ViewGroup)owner;
        if(titleContainer!=null && group instanceof RelativeLayout)for(Height frame:titleHeights.values()) {
            RelativeLayout.LayoutParams p=relativeParams(frame.view);
            if(frame.view.getId()==TITLE_FRAMES[0] && p!=null && p.getRule(RelativeLayout.CENTER_HORIZONTAL)!=0) {
                if(paddingLeft!=paddingRight)return capacityFallback(owner,"asymmetric_title_padding");
                return hostTopCapacity((RelativeLayout)group,(FrameLayout)frame.view);
            }
        }
        int[] remaining={MAX_TOP_CAPACITY_NODES};boolean[] nativeSlot={false};
        int width=0,seenFrames=0;
        for(int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);if(child.getVisibility()==View.GONE)continue;int frame=-1;
            for(int j=0;j<TITLE_FRAMES.length;j++)if(child.getId()==TITLE_FRAMES[j]){frame=j;break;}
            if(frame<0)return capacityFallback(child,"unknown_owner_child");
            if(!(child instanceof FrameLayout))return capacityFallback(child,"title_frame_type");
            if((seenFrames&(1<<frame))!=0)return capacityFallback(child,"duplicate_title_frame");
            seenFrames|=1<<frame;
            int occupied=contentCapacity(child,0,false,remaining,nativeSlot);
            if(occupied<0)return -1;
            width=saturatedSum(width,occupied,0);
        }
        if(!nativeSlot[0])return capacityFallback(owner,"no_native_top_slots");
        topCapacityDiagnostic="result=accepted;frames="+Integer.bitCount(seenFrames)+";slots="+width;
        return width;
    }

    private int hostTopCapacity(RelativeLayout title,FrameLayout center) {
        boolean rtl=title.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
        int start=0,end=0,seen=0;
        if(title.getChildCount()>TITLE_FRAMES.length || !startGravity(title))
            return capacityFallback(title,"host_title_children");
        for(int i=0;i<title.getChildCount();i++) {
            View mount=title.getChildAt(i);
            if(mount==center)continue;
            boolean atStart=mount.getId()==TITLE_FRAMES[2];
            int bit=atStart?1:2;
            if(!(mount instanceof FrameLayout) || (!atStart && mount.getId()!=TITLE_FRAMES[1]) || (seen&bit)!=0)
                return capacityFallback(mount,"host_corner_mount");
            seen|=bit;
            if(mount.getVisibility()==View.GONE)continue;
            RelativeLayout.LayoutParams p=relativeParams(mount);
            if(!edgeAnchored(p,rtl,atStart))
                return capacityFallback(mount,"host_corner_rules");
            if(mount.getLayoutDirection()!=title.getLayoutDirection())return capacityFallback(mount,"host_corner_direction");
            FrameLayout frame=(FrameLayout)mount;View corner=null;
            if(frame.getChildCount()>MAX_TOP_CAPACITY_NODES)return capacityFallback(frame,"tree_node_limit");
            for(int j=0;j<frame.getChildCount();j++) {
                View child=frame.getChildAt(j);
                if(child.getVisibility()==View.GONE)continue;
                if(corner!=null || !LiquidGlassTargets.hasClass(child,TOP_CORNER) || !stablePose(child))
                    return capacityFallback(child,"host_corner_content");
                corner=child;
            }
            int width;
            if(corner==null && p.width==ViewGroup.LayoutParams.WRAP_CONTENT && emptyPaint(frame))
                width=Math.max(frame.getMinimumWidth(),saturatedSum(frame.getPaddingLeft(),frame.getPaddingRight(),0));
            else width=stableWidth(frame,p);
            if(p.width==ViewGroup.LayoutParams.WRAP_CONTENT && corner!=null
                    && corner.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams child=(FrameLayout.LayoutParams)corner.getLayoutParams();
                if(!nonnegativeMargins(child))return capacityFallback(corner,"host_corner_margins");
                if(child.width>0) {
                    int intrinsic=saturatedSum(child.width,child.leftMargin,child.rightMargin);
                    width=Math.max(width,saturatedSum(intrinsic,frame.getPaddingLeft(),frame.getPaddingRight()));
                } else if((frame.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL)!=0)
                    return capacityFallback(frame,"host_corner_measured_too_small");
            }
            if(width<0 || !stablePose(frame) || !nonnegativeMargins(p))return capacityFallback(frame,"host_corner_width");
            int extent=saturatedSum(width,marginStart(p,rtl),marginEnd(p,rtl));
            if(atStart)start=extent;else end=extent;
        }
        int required=saturatedSum(start,end,0);
        String stripDetail="";
        if(center.getVisibility()!=View.GONE) {
            RelativeLayout.LayoutParams p=relativeParams(center);
            if(p==null || p.width!=ViewGroup.LayoutParams.MATCH_PARENT
                    || !horizontalRules(p,RelativeLayout.CENTER_HORIZONTAL,-1,RelativeLayout.TRUE) || !plainMount(center,p))
                return capacityFallback(center,"host_center_rules");
            if(center.getChildCount()==0 || (center.getChildCount()==1 && center.getChildAt(0).getVisibility()==View.GONE)) {
                if(!emptyPaint(center) || center.isClickable() || center.isLongClickable() || center.isFocusable()
                        || center.getMinimumWidth()!=0)return capacityFallback(center,"host_empty_center_paint");
            } else {
                if(center.getChildCount()!=1)return capacityFallback(center,"host_center_children");
                View strip=center.getChildAt(0);
                if(!(strip instanceof RelativeLayout) || strip.getId()!=TOP_STRIP
                        || !LiquidGlassTargets.hasClass(strip,TOP_UI+"tabstrip.container.MainTabStripNew"))
                    return capacityFallback(strip,"host_tab_strip");
                required=hostStripCapacity((RelativeLayout)strip,rtl,start,end);
                if(required<0)return -1;
                stripDetail=topCapacityDiagnostic;
            }
        }
        if(required==0)return capacityFallback(title,"no_native_top_slots");
        topCapacityDiagnostic="result=accepted;host=41;slots="+required+stripDetail;
        return required;
    }

    private int hostStripCapacity(RelativeLayout strip,boolean rtl,int startCorner,int endCorner) {
        ViewGroup.LayoutParams raw=strip.getLayoutParams();
        if(!(raw instanceof FrameLayout.LayoutParams) || raw.width!=ViewGroup.LayoutParams.MATCH_PARENT
                || !plainMount(strip,(FrameLayout.LayoutParams)raw) || !startGravity(strip)
                || strip.getLayoutDirection()!=(rtl?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR)
                || strip.getChildCount()!=2)return capacityFallback(strip,"host_strip_shape");
        View scroll=null,more=null;
        for(int i=0;i<2;i++) {
            View child=strip.getChildAt(i);
            if(child.getId()==TOP_SCROLL && child instanceof HorizontalScrollView
                    && LiquidGlassTargets.hasClass(child,TOP_UI+"tabstrip.scroll.MainTabStripScrollView"))scroll=child;
            if(child.getId()==TOP_MORE && child instanceof ImageView
                    && LiquidGlassTargets.hasClass(child,"com.ss.android.ugc.aweme.homepage.ui.view.antiburn.AntiBurnDuxImageView"))more=child;
        }
        if(scroll==null || more==null)return capacityFallback(strip,"host_strip_children");
        RelativeLayout.LayoutParams p=relativeParams(scroll);
        if(p==null || scroll.getVisibility()==View.GONE || p.width!=ViewGroup.LayoutParams.WRAP_CONTENT
                || !stablePose(scroll) || !nonnegativeMargins(p))return capacityFallback(scroll,"host_scroll_size");
        int beforeMore=rtl?RelativeLayout.RIGHT_OF:RelativeLayout.LEFT_OF;
        int anchor=p.getRule(RelativeLayout.START_OF)!=0?p.getRule(RelativeLayout.START_OF):p.getRule(beforeMore);
        int centered=p.getRule(RelativeLayout.CENTER_HORIZONTAL);
        if(anchor!=TOP_MORE || !horizontalRules(p,beforeMore,RelativeLayout.START_OF,TOP_MORE,
                RelativeLayout.CENTER_HORIZONTAL,centered) || p.alignWithParent)
            return capacityFallback(scroll,"host_scroll_rules");
        ViewGroup viewport=(ViewGroup)scroll;
        if(viewport.getChildCount()!=1 || !(viewport.getChildAt(0) instanceof RelativeLayout))
            return capacityFallback(scroll,"host_scroll_children");
        RelativeLayout row=(RelativeLayout)viewport.getChildAt(0);
        if(row.getId()!=TOP_ROW || !LiquidGlassTargets.hasClass(row,TOP_UI+"titlebar.MainTabRelativeLayout")
                || row.getLayoutDirection()!=strip.getLayoutDirection())return capacityFallback(row,"host_tab_row");
        int rowWidth=hostTabRowWidth(row,rtl);
        if(rowWidth<0)return -1;
        int width=Math.max(scroll.getMinimumWidth(),saturatedSum(rowWidth,scroll.getPaddingLeft(),scroll.getPaddingRight()));
        int startMargin=marginStart(p,rtl),endMargin=marginEnd(p,rtl);
        if(scrollMargins!=null && scrollMargins.matches(scroll)) {
            scrollMargins.adoptHostMargins();
            startMargin=scrollMargins.startBaseline(rtl);
            endMargin=scrollMargins.endBaseline(rtl);
        }
        if(startMargin<0 || endMargin<0)return capacityFallback(scroll,"host_scroll_size");
        topCapacityDiagnostic=";row="+rowWidth+";scroll="+width+";corners="+startCorner+"/"+endCorner
                +";scroll_margins="+startMargin+"/"+endMargin;
        if(more.getVisibility()!=View.GONE) {
            RelativeLayout.LayoutParams m=relativeParams(more);
            if(!edgeAnchored(m,rtl,false) || !stablePose(more) || !nonnegativeMargins(m) || m.width<=0)
                return capacityFallback(more,"host_more_rules");
            int outer=marginEnd(m,rtl);
            if(outer<endCorner)return capacityFallback(more,"host_more_corner_overlap");
            int end=saturatedSum(endMargin,saturatedSum(m.width,marginStart(m,rtl),outer),0);
            return saturatedSum(width,Math.max(startCorner,startMargin),end);
        }
        if(centered!=0) {
            if(!edgeAnchored(relativeParams(more),rtl,false)
                    || scroll.getLayoutDirection()!=strip.getLayoutDirection())return capacityFallback(scroll,"host_centered_scroll_rules");
            // The host adds the same screen-centering slack to both margins. The strip already centers the row.
            int trim=Math.min(Math.max(0,startMargin-startCorner),Math.max(0,endMargin-endCorner));
            int marginSpace=saturatedSum(startMargin-trim,endMargin-trim,0);
            if(trim>0) {
                compactScroll=scroll;
                compactScrollStartMargin=compactScrollEndMargin=marginSpace/2;
            }
            int corners=Math.max(startCorner,endCorner);
            int edgeSpace=Math.max(saturatedSum(corners,corners,0),marginSpace);
            topCapacityDiagnostic+=";trim="+trim;
            return saturatedSum(width,edgeSpace,0);
        }
        if(startMargin<startCorner)return capacityFallback(scroll,"host_start_corner_overlap");
        int trim=Math.min(Math.max(0,startMargin-startCorner),Math.max(0,endMargin-endCorner));
        if(trim>0) {
            startMargin-=trim;
            endMargin-=trim;
            compactScroll=scroll;
            compactScrollStartMargin=startMargin;
            compactScrollEndMargin=endMargin;
            topCapacityDiagnostic+=";trim="+trim;
        }
        return saturatedSum(width,startMargin,Math.max(endMargin,endCorner));
    }

    private boolean syncScrollMargins() {
        boolean changed=false;
        if(scrollMargins!=null && !scrollMargins.matches(compactScroll)) {
            changed=releaseScrollMargins();
        }
        if(compactScroll!=null) {
            if(scrollMargins==null)scrollMargins=new ScrollMargins(compactScroll);
            changed|=scrollMargins.apply(compactScrollStartMargin,compactScrollEndMargin);
        }
        return changed;
    }

    private boolean releaseScrollMargins() {
        if(scrollMargins==null)return false;
        boolean changed=scrollMargins.restore();
        scrollMargins=null;
        return changed;
    }

    @SuppressLint("ResourceType") // Douyin 41.0.0 id/6r7 is a host resource, not a module resource.
    private int hostTabRowWidth(RelativeLayout row,boolean rtl) {
        ViewGroup.LayoutParams raw=row.getLayoutParams();
        if(!(raw instanceof FrameLayout.LayoutParams) || raw.width!=ViewGroup.LayoutParams.WRAP_CONTENT
                || !stablePose(row) || !nonnegativeMargins((FrameLayout.LayoutParams)raw) || row.getVisibility()==View.GONE
                || row.getChildCount()>MAX_TOP_CAPACITY_NODES)return capacityFallback(row,"host_tab_row_shape");
        int width=0,indicatorWidth=0;
        int endOf=rtl?RelativeLayout.LEFT_OF:RelativeLayout.RIGHT_OF;
        topTabIds.clear();
        topTabsByAnchor.clear();
        for(int i=0;i<row.getChildCount();i++) {
            View tab=row.getChildAt(i);
            int id=tab.getId();
            if(id!=View.NO_ID && id!=0) {
                if(topTabIds.get(id))return capacityFallback(tab,"host_tab_chain");
                topTabIds.put(id,true);
            }
            boolean nativeTab=LiquidGlassTargets.hasClass(tab,TOP_ITEM);
            if(tab.getVisibility()==View.GONE && !nativeTab)continue;
            RelativeLayout.LayoutParams p=relativeParams(tab);
            if(id==0x7f0ad54f && nativeIndicator(tab,p)) {
                indicatorWidth=Math.max(indicatorWidth,p.width);continue;
            }
            if(!nativeTab || p==null || p.alignWithParent || id==View.NO_ID || id==0)
                return capacityFallback(tab,"host_tab_chain");
            int anchor=p.getRule(RelativeLayout.END_OF);
            if(anchor==0)anchor=p.getRule(endOf);
            if(anchor==id || topTabsByAnchor.get(anchor)!=0 || !horizontalRules(p,endOf,RelativeLayout.END_OF,anchor))
                return capacityFallback(tab,"host_tab_chain");
            topTabsByAnchor.put(anchor,id);
            if(tab.getVisibility()==View.GONE)continue;
            int slot=stableWidth(tab,p);
            if(slot<0 || !stablePose(tab) || !nonnegativeMargins(p))return capacityFallback(tab,"unresolved_native_slot_width");
            width=saturatedSum(width,slot,saturatedSum(marginStart(p,rtl),marginEnd(p,rtl),0));
        }
        int count=topTabsByAnchor.size();
        if(count==0)return capacityFallback(row,"no_native_top_slots");
        // The host changes child drawing order after wiring these RelativeLayout rules.
        int visited=0,next=topTabsByAnchor.get(0);
        while(next!=0 && visited<count) {
            visited++;
            next=topTabsByAnchor.get(next);
        }
        if(visited!=count || next!=0)return capacityFallback(row,"host_tab_chain");
        int content=Math.max(row.getMinimumWidth(),saturatedSum(Math.max(width,indicatorWidth),row.getPaddingLeft(),row.getPaddingRight()));
        ViewGroup.MarginLayoutParams margins=(ViewGroup.MarginLayoutParams)raw;
        return saturatedSum(content,marginStart(margins,rtl),marginEnd(margins,rtl));
    }

    private static RelativeLayout.LayoutParams relativeParams(View view) {
        return view.getLayoutParams() instanceof RelativeLayout.LayoutParams?(RelativeLayout.LayoutParams)view.getLayoutParams():null;
    }
    private static int stableWidth(View view,ViewGroup.LayoutParams p) {
        if(p.width>0)return Math.max(p.width,view.getMinimumWidth());
        if(p.width==ViewGroup.LayoutParams.WRAP_CONTENT && view.isLaidOut() && !view.isLayoutRequested() && view.getWidth()>0)
            return Math.max(view.getWidth(),view.getMinimumWidth());
        return -1;
    }
    private static boolean stablePose(View view) {
        return view.getMatrix().isIdentity() && view.getAnimation()==null && view.getRotationX()==0 && view.getRotationY()==0;
    }
    private static boolean plainMount(View view,ViewGroup.MarginLayoutParams p) {
        return stablePose(view) && nonnegativeMargins(p) && marginStart(p,false)==0 && marginEnd(p,false)==0
                && view.getPaddingLeft()==0 && view.getPaddingRight()==0 && view.getScrollX()==0;
    }
    private static boolean emptyPaint(View view) {
        Drawable bg=view.getBackground();
        return view.getForeground()==null && (bg==null || bg instanceof ColorDrawable && ((ColorDrawable)bg).getAlpha()==0);
    }
    private static boolean startGravity(RelativeLayout layout) {
        return (layout.getGravity()&Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)==Gravity.START;
    }
    private static boolean nonnegativeMargins(ViewGroup.MarginLayoutParams p) {
        // Child margins are the physical values already resolved by native layout.
        return p.leftMargin>=0 && p.rightMargin>=0;
    }
    private static int marginStart(ViewGroup.MarginLayoutParams p,boolean rtl) {
        return rtl?p.rightMargin:p.leftMargin;
    }
    private static int marginEnd(ViewGroup.MarginLayoutParams p,boolean rtl) {
        return rtl?p.leftMargin:p.rightMargin;
    }
    private static boolean edgeAnchored(RelativeLayout.LayoutParams p,boolean rtl,boolean start) {
        int physical=rtl==start?RelativeLayout.ALIGN_PARENT_RIGHT:RelativeLayout.ALIGN_PARENT_LEFT;
        int logical=start?RelativeLayout.ALIGN_PARENT_START:RelativeLayout.ALIGN_PARENT_END;
        return p!=null && (p.getRule(physical)!=0 || p.getRule(logical)!=0)
                && horizontalRules(p,physical,logical,RelativeLayout.TRUE);
    }
    private static boolean horizontalRules(RelativeLayout.LayoutParams p,int physical,int logical,int value) {
        return horizontalRules(p,physical,logical,value,-1,0);
    }
    private static boolean horizontalRules(RelativeLayout.LayoutParams p,int physical,int logical,int value,int extra,int extraValue) {
        for(int rule=RelativeLayout.LEFT_OF;rule<=RelativeLayout.ALIGN_PARENT_END;rule++) {
            int actual=p.getRule(rule);
            if(actual==0)continue;
            if(rule==physical || rule==logical) {if(actual!=value)return false;}
            else if(rule==extra) {if(actual!=extraValue)return false;}
            else if(rule<=RelativeLayout.RIGHT_OF || rule==RelativeLayout.ALIGN_LEFT || rule==RelativeLayout.ALIGN_RIGHT
                    || rule==RelativeLayout.ALIGN_PARENT_LEFT || rule==RelativeLayout.ALIGN_PARENT_RIGHT
                    || rule==RelativeLayout.CENTER_IN_PARENT || rule==RelativeLayout.CENTER_HORIZONTAL
                    || rule>=RelativeLayout.START_OF)return false;
        }
        return true;
    }
    @SuppressLint("ResourceType") // Douyin 41.0.0 id/6r8 and id/6r9 are host resources.
    private static boolean nativeIndicator(View view,RelativeLayout.LayoutParams p) {
        if(view.getClass()!=FrameLayout.class || p==null || p.width<=0
                || !horizontalRules(p,-1,-1,0) || !nonnegativeMargins(p)
                || view.isClickable() || view.isLongClickable() || view.isFocusable())return false;
        FrameLayout group=(FrameLayout)view;
        if(group.getChildCount()!=2)return false;
        int ids=0;
        for(int i=0;i<2;i++) {
            View child=group.getChildAt(i);
            if(child.getClass()!=View.class || child.isClickable() || child.isLongClickable() || child.isFocusable())return false;
            if(child.getId()==0x7f0ad550)ids|=1;
            if(child.getId()==0x7f0ad551)ids|=2;
        }
        return ids==3;
    }

    private int capacityFallback(View view,String reason) {
        ViewGroup.LayoutParams raw=view==null?null:view.getLayoutParams();
        topCapacityDiagnostic="result=fallback;reason="+reason
                +";class="+(view==null?"unknown":view.getClass().getName())
                +";id="+(view==null?"none":Integer.toHexString(view.getId()))
                +";layout="+(raw==null?"none":raw.width+"x"+raw.height);
        return -1;
    }

    /** Read capacity, never remeasure or rewrite a host child or use an expanded viewport as intrinsic width. */
    private int contentCapacity(View view,int depth,boolean hidden,int[] remaining,boolean[] nativeSlot) {
        boolean gone=hidden || view.getVisibility()==View.GONE;
        if(gone) {
            if(LiquidGlassTargets.hasClass(view,TOP_ITEM))nativeSlot[0]=true;
            return 0;
        }
        if(depth>MAX_TOP_CAPACITY_DEPTH)return capacityFallback(view,"tree_depth_limit");
        if(remaining[0]--<=0)return capacityFallback(view,"tree_node_limit");
        ViewGroup.LayoutParams raw=view.getLayoutParams();
        if(raw==null)return capacityFallback(view,"missing_layout_params");
        if(raw instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams)raw).weight!=0)
            return capacityFallback(view,"weighted_child");
        int margins=0;
        if(raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)raw;
            if(p.leftMargin<0 || p.rightMargin<0)return capacityFallback(view,"negative_margin");
            margins=saturatedSum(p.leftMargin,p.rightMargin,0);
        }
        if(!view.getMatrix().isIdentity())return capacityFallback(view,"transformed_child");
        if(view.getAnimation()!=null)return capacityFallback(view,"animated_child");
        if(view instanceof FrameLayout && !(view instanceof HorizontalScrollView)
                && (view.getScrollX()!=0 || view.getScrollY()!=0))return capacityFallback(view,"scrolled_frame");
        int width;
        if(LiquidGlassTargets.hasClass(view,TOP_ITEM)) {
            nativeSlot[0]=true;
            if(raw.width>0)width=raw.width;
            else if(raw.width==ViewGroup.LayoutParams.WRAP_CONTENT && view.isLaidOut()
                    && !view.isLayoutRequested() && view.getWidth()>0)width=view.getWidth();
            else return capacityFallback(view,"unresolved_native_slot_width");
        } else if(view instanceof LinearLayout) {
            LinearLayout row=(LinearLayout)view;
            if(row.getOrientation()!=LinearLayout.HORIZONTAL)return capacityFallback(view,"non_horizontal_row");
            if(row.getDividerDrawable()!=null)return capacityFallback(view,"row_divider");
            width=saturatedSum(view.getPaddingLeft(),view.getPaddingRight(),0);
            for(int i=0;i<row.getChildCount();i++) {
                int child=contentCapacity(row.getChildAt(i),depth+1,gone,remaining,nativeSlot);
                if(child<0)return -1;
                width=saturatedSum(width,child,0);
            }
            if(raw.width>0)width=Math.max(width,raw.width);
        } else if(view instanceof FrameLayout) {
            // HorizontalScrollView is a single-child FrameLayout; fillViewport can expand its child row.
            FrameLayout frame=(FrameLayout)view;
            if(frame.getChildCount()>1)return capacityFallback(view,"multi_child_frame");
            width=0;
            if(frame.getChildCount()==1) {
                width=contentCapacity(frame.getChildAt(0),depth+1,gone,remaining,nativeSlot);
                if(width<0)return -1;
            } else if(raw.width<=0 && view.getMinimumWidth()<=0)return capacityFallback(view,"empty_unbounded_frame");
            width=saturatedSum(width,view.getPaddingLeft(),view.getPaddingRight());
            if(raw.width>0)width=Math.max(width,raw.width);
        } else return capacityFallback(view,"unsupported_view_type");
        return saturatedSum(Math.max(width,view.getMinimumWidth()),margins,0);
    }

    @android.annotation.SuppressLint("ResourceType") // Verified root_view belongs to host 41.0.0, not module R.
    private int itemCount(int labels) {
        View row=owner.findViewById(ROOT_VIEW);
        if(row instanceof ViewGroup) {
            int count=0;ViewGroup group=(ViewGroup)row;
            for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i).getVisibility()!=View.GONE)count++;
            if(count>0)return count;
        }
        return Math.max(1,labels);
    }

    private void syncTitleFrames() {
        for(Iterator<Map.Entry<View,Height>> iterator=titleHeights.entrySet().iterator();iterator.hasNext();) {
            Height height=iterator.next().getValue();
            if(height.view.getParent()!=owner || height.view.getLayoutParams()!=height.raw){height.close();iterator.remove();}
        }
        if(!(owner instanceof ViewGroup))return;
        ViewGroup group=(ViewGroup)owner;
        for(int i=0;i<group.getChildCount();i++) {
            View child=group.getChildAt(i);
            if(!(child instanceof FrameLayout) || child.getLayoutParams()==null)continue;
            for(int id:TITLE_FRAMES)if(child.getId()==id && !titleHeights.containsKey(child))titleHeights.put(child,new Height(child));
        }
    }

    @android.annotation.SuppressLint("ResourceType") // Both IDs identify the verified 41.0.0 host layout, not module resources.
    private boolean syncTitleContainer(ViewGroup parent) {
        boolean verified=owner.getId()==0x7f0a2866 && parent.getId()==0x7f0ab82b
                && LiquidGlassTargets.hasClass(parent,"com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBarContainer")
                && parent.getLayoutParams()!=null;
        boolean changed=false;
        if(titleContainer!=null && (!verified || titleContainer.view!=parent || titleContainer.raw!=parent.getLayoutParams())) {
            changed=titleContainer.close();titleContainer=null;
        }
        if(verified && titleContainer==null)titleContainer=new Height(parent);
        return changed;
    }

    private static int[] naturalSize(TextView label) {
        CharSequence text=label.getText();TransformationMethod transformation=label.getTransformationMethod();
        if(transformation!=null)text=transformation.getTransformation(text,label);
        int textWidth=text==null || text.length()==0?0:ceil(Layout.getDesiredWidth(text,label.getPaint()));
        Paint.FontMetricsInt metrics=label.getPaint().getFontMetricsInt();
        int textHeight=label.getIncludeFontPadding()?metrics.bottom-metrics.top:metrics.descent-metrics.ascent;
        Layout layout=label.getLayout();
        if(layout!=null)textHeight=Math.max(textHeight,layout.getHeight());
        Drawable[] icons=label.getCompoundDrawables();
        int width=saturatedSum(textWidth,label.getCompoundPaddingLeft(),label.getCompoundPaddingRight());
        width=Math.max(width,saturatedSum(Math.max(drawableWidth(icons[1]),drawableWidth(icons[3])),label.getPaddingLeft(),label.getPaddingRight()));
        int lineHeight=Math.max(textHeight,Math.max(drawableHeight(icons[0]),drawableHeight(icons[2])));
        int height=saturatedSum(lineHeight,label.getCompoundPaddingTop(),label.getCompoundPaddingBottom());
        return new int[]{width,height};
    }
    private static int drawableWidth(Drawable drawable) {
        return drawable==null?0:Math.max(0,drawable.getBounds().width()>0?drawable.getBounds().width():drawable.getIntrinsicWidth());
    }
    private static int drawableHeight(Drawable drawable) {
        return drawable==null?0:Math.max(0,drawable.getBounds().height()>0?drawable.getBounds().height():drawable.getIntrinsicHeight());
    }
    private static int ceil(float value){return !Float.isFinite(value)?0:(int)Math.min(Integer.MAX_VALUE,Math.ceil(Math.max(0,value)));}
    private static int saturatedSum(int a,int b,int c){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,(long)a+b+c));}
    private int dp(int value){return Math.round(value*owner.getResources().getDisplayMetrics().density);}

    @Override public void close() {
        if(closed)return;closed=true;
        boolean changed=params!=null && owner.getLayoutParams()==params.raw && params.close();
        changed|=releaseScrollMargins();
        compactScroll=null;
        changed|=left.restore();changed|=up.restore();changed|=right.restore();changed|=down.restore();
        changed|=minimumHeight.restore();
        for(Height height:titleHeights.values())changed|=height.close();
        if(titleContainer!=null){changed|=titleContainer.close();titleContainer=null;}
        titleHeights.clear();if(changed)owner.requestLayout();
    }

    /** Track each scalar separately: host mutations on the same LayoutParams instance are new baselines. */
    private static final class Value {
        final IntSupplier read;final IntConsumer write;
        int original,last;boolean owned;
        Value(IntSupplier read,IntConsumer write){this.read=read;this.write=write;original=read.getAsInt();}
        int baseline() {
            int current=read.getAsInt();
            if(!owned || current!=last){original=current;owned=false;}
            return original;
        }
        boolean apply(int value) {
            int current=read.getAsInt();
            if(!owned || current!=last){original=current;owned=false;}
            if(current==value)return false;
            write.accept(value);last=value;owned=true;return true;
        }
        boolean restore() {
            boolean restore=owned && read.getAsInt()==last;owned=false;
            if(restore){write.accept(original);return true;}return false;
        }
    }

    private static final class Height {
        final View view;final ViewGroup.LayoutParams raw;final Value value;
        Height(View view){this.view=view;raw=view.getLayoutParams();value=new Value(()->raw.height,v->raw.height=v);}
        boolean close(){boolean changed=view.getLayoutParams()==raw && value.restore();if(changed)view.requestLayout();return changed;}
    }

    private static final class ScrollMargins {
        final View view;
        final RelativeLayout.LayoutParams raw;
        final boolean relative;
        int nativeStart,nativeEnd,logicalStart,logicalEnd;
        int appliedStart=-1,appliedEnd=-1;
        int appliedLeft=-1,appliedRight=-1;

        ScrollMargins(View view) {
            this.view=view;raw=(RelativeLayout.LayoutParams)view.getLayoutParams();
            relative=raw.isMarginRelative();
            adoptHostMargins();
        }
        boolean matches(View other) {
            return view==other && view.getLayoutParams()==raw && raw.isMarginRelative()==relative;
        }
        void adoptHostMargins() {
            boolean rtl=relative && view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            if(relative) {
                int start=raw.getMarginStart(),end=raw.getMarginEnd();
                // Host writes can precede resolution, and direction changes can re-resolve our own margins.
                if(appliedStart<0 || start!=appliedStart)nativeStart=logicalStart=start;
                if(appliedEnd<0 || end!=appliedEnd)nativeEnd=logicalEnd=end;
                int currentLeft=rtl?appliedEnd:appliedStart,currentRight=rtl?appliedStart:appliedEnd;
                if(appliedStart<0 || (raw.leftMargin!=appliedLeft && raw.leftMargin!=currentLeft))
                    if(rtl)nativeEnd=raw.leftMargin;else nativeStart=raw.leftMargin;
                if(appliedEnd<0 || (raw.rightMargin!=appliedRight && raw.rightMargin!=currentRight))
                    if(rtl)nativeStart=raw.rightMargin;else nativeEnd=raw.rightMargin;
            }else {
                if(appliedLeft<0 || raw.leftMargin!=appliedLeft)nativeStart=raw.leftMargin;
                if(appliedRight<0 || raw.rightMargin!=appliedRight)nativeEnd=raw.rightMargin;
            }
        }
        int startBaseline(boolean rtl) {
            return !relative && rtl?nativeEnd:nativeStart;
        }
        int endBaseline(boolean rtl) {
            return !relative && rtl?nativeStart:nativeEnd;
        }
        boolean apply(int start,int end) {
            appliedStart=start;appliedEnd=end;
            boolean rtl=view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            appliedLeft=rtl?end:start;appliedRight=rtl?start:end;
            return write(appliedLeft,appliedRight,start,end);
        }
        boolean restore() {
            if(!matches(view))return false;
            adoptHostMargins();
            boolean rtl=relative && view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            return write(rtl?nativeEnd:nativeStart,rtl?nativeStart:nativeEnd,logicalStart,logicalEnd);
        }
        private boolean write(int left,int right,int start,int end) {
            boolean updateLogical=relative && (raw.getMarginStart()!=start || raw.getMarginEnd()!=end);
            if(!updateLogical && raw.leftMargin==left && raw.rightMargin==right)return false;
            if(updateLogical) {
                raw.setMarginStart(start);raw.setMarginEnd(end);
                raw.resolveLayoutDirection(view.getLayoutDirection());
            }
            raw.leftMargin=left;raw.rightMargin=right;
            view.requestLayout();
            return true;
        }
    }

    private static final class OwnerParams {
        final ViewGroup.MarginLayoutParams raw;
        final Value width,height,marginLeft,marginRight,marginTop,marginBottom,marginStart,marginEnd,gravity;
        OwnerParams(ViewGroup.MarginLayoutParams raw) {
            this.raw=raw;width=new Value(()->raw.width,v->raw.width=v);height=new Value(()->raw.height,v->raw.height=v);
            marginLeft=new Value(()->raw.leftMargin,v->raw.leftMargin=v);marginRight=new Value(()->raw.rightMargin,v->raw.rightMargin=v);
            marginTop=new Value(()->raw.topMargin,v->raw.topMargin=v);marginBottom=new Value(()->raw.bottomMargin,v->raw.bottomMargin=v);
            marginStart=raw.isMarginRelative()?new Value(raw::getMarginStart,raw::setMarginStart):null;
            marginEnd=raw.isMarginRelative()?new Value(raw::getMarginEnd,raw::setMarginEnd):null;
            if(raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams frame=(FrameLayout.LayoutParams)raw;
                gravity=new Value(()->frame.gravity,v->frame.gravity=v);
            }else gravity=null;
        }
        boolean apply(int newWidth,int newHeight,int margin,int edge,int bottomInset) {
            boolean changed=width.apply(newWidth);changed|=height.apply(newHeight);
            changed|=marginLeft.apply(margin);changed|=marginRight.apply(margin);
            changed|=marginTop.apply(Math.max(marginTop.baseline(),edge));
            changed|=marginBottom.apply(Math.max(marginBottom.baseline(),saturatedSum(edge,bottomInset,0)));
            if(marginStart!=null){changed|=marginStart.apply(margin);changed|=marginEnd.apply(margin);}
            if(gravity!=null) {
                int base=gravity.baseline();if(base==-1)base=Gravity.TOP;
                changed|=gravity.apply((base&~Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)|Gravity.CENTER_HORIZONTAL);
            }
            // RelativeLayout resolves START/END destructively in getRule(); symmetric geometry keeps its logical rules intact.
            return changed;
        }
        boolean close() {
            boolean changed=width.restore();changed|=height.restore();changed|=marginLeft.restore();changed|=marginRight.restore();
            changed|=marginTop.restore();changed|=marginBottom.restore();
            if(marginStart!=null){changed|=marginStart.restore();changed|=marginEnd.restore();}
            if(gravity!=null)changed|=gravity.restore();
            return changed;
        }
    }
}
