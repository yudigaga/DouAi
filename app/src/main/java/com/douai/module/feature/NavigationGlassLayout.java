package com.douai.module.feature;

import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.method.TransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
    private final View owner;
    private final boolean top;
    private final Value left,up,right,down,minimumHeight;
    private final Map<View,Height> titleHeights=new IdentityHashMap<>();
    private Height titleContainer;
    private OwnerParams params;
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
        if(closed || !(owner.getParent() instanceof ViewGroup))return false;
        ViewGroup parent=(ViewGroup)owner.getParent();
        int available=parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight();
        ViewGroup.LayoutParams current=owner.getLayoutParams();
        if(available<=0 || !(current instanceof ViewGroup.MarginLayoutParams))return false;
        if(params==null || params.raw!=current)params=new OwnerParams((ViewGroup.MarginLayoutParams)current);

        int paddingLeft=Math.max(dp(8),left.baseline()),paddingRight=Math.max(dp(8),right.baseline());
        int paddingTop=Math.max(dp(top?8:10),up.baseline()),paddingBottom=Math.max(dp(top?8:10),down.baseline());
        int contentWidth=0,contentHeight=0,labelCount=0;
        if(labels!=null)for(TextView label:labels) {
            if(label==null || label.getVisibility()!=View.VISIBLE || !descendant(label))continue;
            int[] size=naturalSize(label);contentWidth=Math.max(contentWidth,size[0]);
            contentHeight=Math.max(contentHeight,size[1]);labelCount++;
        }
        int contentMinimum=top?dp(44):0;
        boolean changed=false;
        if(top) {
            changed|=syncTitleContainer(parent);
            syncTitleFrames();
            for(Height height:titleHeights.values())contentMinimum=Math.max(contentMinimum,height.value.baseline());
        }
        int targetHeight=Math.max(dp(top?64:72),Math.max(minimumHeight.baseline(),params.height.baseline()));
        targetHeight=Math.max(targetHeight,saturatedSum(Math.max(contentMinimum,contentHeight),paddingTop,paddingBottom));

        int safeMargin=Math.max(0,Math.max(params.marginLeft.baseline(),params.marginRight.baseline()));
        if(params.marginStart!=null)safeMargin=Math.max(safeMargin,Math.max(params.marginStart.baseline(),params.marginEnd.baseline()));
        int maximumWidth=Math.max(0,available-2*Math.min(available/2,safeMargin));
        if(maximumWidth==0)return changed;
        int baseWidth=params.width.baseline();
        if(baseWidth<=0)baseWidth=maximumWidth;else baseWidth=Math.min(baseWidth,maximumWidth);
        int minimumWidth=saturatedSum(contentWidth,paddingLeft,paddingRight);
        if(!top) {
            int items=itemCount(labelCount);
            long slots=(long)items*Math.max(dp(48),(long)contentWidth+dp(8));
            minimumWidth=(int)Math.min(Integer.MAX_VALUE,slots+paddingLeft+(long)paddingRight);
        }
        int targetWidth=Math.min(maximumWidth,Math.max(Math.max(1,baseWidth-dp(top?32:48)),minimumWidth));
        int margin=(available-targetWidth)/2;
        changed|=params.apply(targetWidth,targetHeight,margin,Math.max(0,motionMargin),top?0:dp(16));
        changed|=left.apply(paddingLeft);changed|=up.apply(paddingTop);
        changed|=right.apply(paddingRight);changed|=down.apply(paddingBottom);
        changed|=minimumHeight.apply(targetHeight);
        if(top)for(Height height:titleHeights.values()) {
            if(height.value.apply(targetHeight-paddingTop-paddingBottom)){height.view.requestLayout();changed=true;}
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

    private boolean descendant(View view) {
        for(int depth=0;view!=null && depth<=40;depth++) {
            if(view==owner)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
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
