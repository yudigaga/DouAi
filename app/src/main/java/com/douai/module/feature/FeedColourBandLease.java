package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/** Owns only the temporary paint suppression of the verified 41.0.0 free-space colours. */
final class FeedColourBandLease {
    private static final int OVERLAY_ROOT=0x7f0a3ffa, COLOUR_CONTAINER=0x7f0a3fac;
    private static final int MAX_TRANSLATED_CHILDREN=8;
    private static final String OVERLAY_CLASS="com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout";
    private WeakReference<View> hidden=new WeakReference<>(null);
    private WeakReference<ImageView> translatedTop=new WeakReference<>(null);
    private TopProof topProof;
    private TopWatch topWatch;
    private final float[] matrix=new float[9];
    private final Rect topVisible=new Rect(),cardVisible=new Rect();

    boolean sync(View card,int nativeWidth,boolean redundant) {
        return sync(card,nativeWidth,redundant,()->false);
    }

    @android.annotation.SuppressLint("ResourceType") // These are verified IDs from the original host APK.
    boolean sync(View card,int nativeWidth,boolean redundant,BooleanSupplier fullCoverage) {
        if(Build.VERSION.SDK_INT<29){restore();return false;}
        if(!redundant){suspend();return false;}
        View candidate=card.findViewById(COLOUR_CONTAINER);
        if(!(candidate instanceof FrameLayout) || candidate.getClass()!=FrameLayout.class
                || !(candidate.getParent() instanceof View)){restore();return false;}
        FrameLayout colours=(FrameLayout)candidate;
        View parent=(View)colours.getParent();
        if(!isOverlay(parent)){restore();return false;}
        if(isDecoration(colours,nativeWidth))return syncContainer(card,colours,parent,nativeWidth);

        restoreContainer();
        try {
            ImageView top=verifiedTop(card,colours,parent,nativeWidth);
            if(top==null){clearTopBinding();return false;}
            if(colours.getTransitionAlpha()==1f && safeTranslation(top,colours,card)
                    && fullCoverage.getAsBoolean() && watchTop(card,colours,parent,top,nativeWidth,fullCoverage)){
                return syncTranslatedTop(top);
            }
        } catch(RuntimeException | LinkageError ignored){/* Leave uncertain paint to the host. */}
        releaseTranslatedTop();
        return false;
    }

    @RequiresApi(29)
    private boolean syncContainer(View card,FrameLayout colours,View parent,int nativeWidth) {
        releaseTranslatedTop();
        View previous=hidden.get();
        if(previous!=colours)restoreContainer();
        boolean changed=false;
        if(colours.getTransitionAlpha()==1f){
            colours.setTransitionAlpha(0f);
            hidden=new WeakReference<>(colours);
            changed=previous!=colours;
        }else if(colours.getTransitionAlpha()!=0f)hidden.clear();
        if(hidden.get()==colours)rememberTop(card,colours,parent,nativeWidth);
        else topProof=null;
        return changed;
    }

    private static boolean isOverlay(View view) {
        return view.getId()==OVERLAY_ROOT && OVERLAY_CLASS.equals(view.getClass().getName());
    }

    private void rememberTop(View card,FrameLayout colours,View parent,int nativeWidth) {
        for(int i=0;i<colours.getChildCount();i++){
            View child=colours.getChildAt(i);
            BitmapDrawable drawable=topDrawable(child,nativeWidth,colours.getHeight());
            if(drawable==null || !child.isAttachedToWindow() || !child.isShown())continue;
            Bitmap bitmap=drawable.getBitmap();
            if(topProof==null || !topProof.matches(card,colours,parent,child,drawable,bitmap,nativeWidth)){
                topProof=new TopProof(card,colours,parent,(ImageView)child,drawable,bitmap,nativeWidth);
            }
            return;
        }
        topProof=null;
    }

    private ImageView verifiedTop(View card,FrameLayout colours,View parent,int nativeWidth) {
        TopProof proof=topProof;
        if(proof==null || colours.getParent()!=parent || colours.getId()!=COLOUR_CONTAINER || !isOverlay(parent)
                || !inCard(parent,card) || !colours.isShown() || colours.getHeight()<=0
                || colours.getChildCount()>MAX_TRANSLATED_CHILDREN)return null;
        ImageView view=proof.view.get();
        if(view==null || view.getParent()!=colours)return null;
        BitmapDrawable drawable=topDrawable(view,nativeWidth,colours.getHeight());
        if(drawable==null || !proof.matches(card,colours,parent,view,drawable,drawable.getBitmap(),nativeWidth))return null;
        for(int i=0;i<colours.getChildCount();i++){
            View child=colours.getChildAt(i);
            if(child!=view && topView(child,nativeWidth,colours.getHeight())!=null)return null;
        }
        return view;
    }

    private static BitmapDrawable topDrawable(View child,int width,int height) {
        ImageView band=topView(child,width,height);
        if(band==null || !(band.getDrawable() instanceof BitmapDrawable drawable)
                || drawable.getBitmap()==null || drawable.getBitmap().isRecycled())return null;
        return drawable;
    }

    private static ImageView topView(View child,int width,int height) {
        if(child.getClass()!=ImageView.class || child.getId()!=View.NO_ID || child.getVisibility()!=View.VISIBLE
                || child.isClickable() || child.isLongClickable() || child.isFocusable()
                || child.getBackground()!=null || child.getForeground()!=null
                || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams p))return null;
        ImageView band=(ImageView)child;
        if(p.gravity!=(Gravity.TOP|Gravity.CENTER_HORIZONTAL) || p.width!=width || p.height<=0
                || p.height>height/2 || (p.leftMargin|p.topMargin|p.rightMargin|p.bottomMargin)!=0
                || band.getScaleType()!=ImageView.ScaleType.CENTER_CROP
                || band.getDrawable()==null)return null;
        return band;
    }

    private boolean safeTranslation(View band,FrameLayout colours,View card) {
        if(!band.isAttachedToWindow() || !band.isShown() || band.getAnimation()!=null
                || band.getClipToOutline() || band.getClipBounds()!=null || band.getTop()!=0)return false;
        band.getMatrix().getValues(matrix);
        float y=matrix[Matrix.MTRANS_Y];
        return matrix[Matrix.MSCALE_X]==1f && matrix[Matrix.MSCALE_Y]==1f
                && matrix[Matrix.MSKEW_X]==0f && matrix[Matrix.MSKEW_Y]==0f
                && matrix[Matrix.MTRANS_X]==0f && matrix[Matrix.MPERSP_0]==0f
                && matrix[Matrix.MPERSP_1]==0f && matrix[Matrix.MPERSP_2]==1f
                && y>0f && y<band.getHeight() && y+band.getBottom()<=colours.getHeight()*.5f
                && band.getGlobalVisibleRect(topVisible) && card.getGlobalVisibleRect(cardVisible)
                && !topVisible.isEmpty() && cardVisible.contains(topVisible);
    }

    private static boolean inCard(View view,View card) {
        for(int depth=0;view!=null && depth<32;depth++){
            if(view==card)return true;
            view=view.getParent() instanceof View parent?parent:null;
        }
        return false;
    }

    private boolean watchTop(View card,FrameLayout colours,View parent,ImageView top,int width,BooleanSupplier coverage) {
        ViewTreeObserver tree=card.getViewTreeObserver();
        TopWatch watch=topWatch;
        if(watch!=null && watch.card==card && watch.colours==colours && watch.parent==parent
                && watch.view==top && watch.width==width && watch.tree==tree && tree.isAlive()){
            watch.coverage=coverage;
            return true;
        }
        releaseTranslatedTop();
        if(!tree.isAlive())return false;
        topWatch=new TopWatch(card,colours,parent,top,width,coverage,tree);
        tree.addOnPreDrawListener(topWatch);
        top.addOnAttachStateChangeListener(topWatch);
        return true;
    }

    @RequiresApi(29)
    private boolean syncTranslatedTop(ImageView view) {
        ImageView previouslyHidden=translatedTop.get();
        float alpha=view.getTransitionAlpha();
        if(alpha==1f){
            view.setTransitionAlpha(0f);
            if(previouslyHidden!=view)translatedTop=new WeakReference<>(view);
            return true;
        }
        if(alpha!=0f || previouslyHidden!=view)releaseTranslatedTop();
        return false;
    }

    private static final class TopProof {
        final WeakReference<View> card,parent;
        final WeakReference<FrameLayout> colours;
        final WeakReference<ImageView> view;
        final WeakReference<BitmapDrawable> drawable;
        final WeakReference<Bitmap> bitmap;
        final int width;

        TopProof(View card,FrameLayout colours,View parent,ImageView view,BitmapDrawable drawable,Bitmap bitmap,int width){
            this.card=new WeakReference<>(card);this.colours=new WeakReference<>(colours);
            this.parent=new WeakReference<>(parent);this.view=new WeakReference<>(view);
            this.drawable=new WeakReference<>(drawable);this.bitmap=new WeakReference<>(bitmap);
            this.width=width;
        }

        boolean matches(View card,FrameLayout colours,View parent,View view,BitmapDrawable drawable,Bitmap bitmap,int width){
            return this.card.get()==card && this.colours.get()==colours && this.parent.get()==parent
                    && this.view.get()==view && this.drawable.get()==drawable && this.bitmap.get()==bitmap && this.width==width;
        }
    }

    private final class TopWatch implements ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        final View card,parent;
        final FrameLayout colours;
        final ImageView view;
        final int width;
        final ViewTreeObserver tree;
        BooleanSupplier coverage;

        TopWatch(View card,FrameLayout colours,View parent,ImageView view,int width,
                 BooleanSupplier coverage,ViewTreeObserver tree){
            this.card=card;this.colours=colours;this.parent=parent;this.view=view;
            this.width=width;this.coverage=coverage;this.tree=tree;
        }

        @Override public boolean onPreDraw() {
            if(topWatch!=this)return true;
            if(Build.VERSION.SDK_INT<29){restore();return true;}
            try {
                if(verifiedTop(card,colours,parent,width)!=view){clearTopBinding();return true;}
                if(colours.getTransitionAlpha()==1f && coverage.getAsBoolean()){
                    if(safeTranslation(view,colours,card)){
                        syncTranslatedTop(view);
                        return true;
                    }
                    if(isDecoration(colours,width)){
                        syncContainer(card,colours,parent,width);
                        return true;
                    }
                }
            } catch(RuntimeException | LinkageError ignored){/* Leave uncertain paint to the host. */}
            releaseTranslatedTop();
            return true;
        }

        @Override public void onViewAttachedToWindow(View ignored){}

        @Override public void onViewDetachedFromWindow(View ignored){
            if(topWatch==this)clearTopBinding();
        }
    }

    private static boolean isDecoration(FrameLayout colours,int nativeWidth) {
        int n=colours.getChildCount(), edges=0;
        if(n==0 || n>2 || colours.getVisibility()!=View.VISIBLE || colours.getHeight()<=0
                || colours.getBackground()!=null || colours.getForeground()!=null
                || colours.isClickable() || colours.isFocusable() || colours.getAnimation()!=null
                || !colours.getMatrix().isIdentity())return false;
        for(int i=0;i<n;i++){
            View child=colours.getChildAt(i);
            if(child.getClass()!=ImageView.class || child.getId()!=View.NO_ID
                    || child.isClickable() || child.isLongClickable() || child.isFocusable()
                    || child.getBackground()!=null || child.getForeground()!=null
                    || child.getAnimation()!=null || !child.getMatrix().isIdentity()
                    || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams))return false;
            ImageView band=(ImageView)child;
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)band.getLayoutParams();
            if(band.getDrawable()==null || band.getScaleType()!=ImageView.ScaleType.CENTER_CROP
                    || p.width!=nativeWidth || p.height<=0 || p.height>colours.getHeight()/2
                    || (p.leftMargin|p.topMargin|p.rightMargin|p.bottomMargin)!=0)return false;
            int edge=p.gravity==(Gravity.TOP|Gravity.CENTER_HORIZONTAL)?1:
                    p.gravity==(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL)?2:0;
            if(edge==0 || (edges&edge)!=0)return false;
            edges|=edge;
        }
        return true;
    }

    private void restoreContainer() {
        View view=hidden.get();
        hidden.clear();
        if(Build.VERSION.SDK_INT>=29 && view!=null && view.getTransitionAlpha()==0f)view.setTransitionAlpha(1f);
    }

    void releaseTranslatedTop() {
        TopWatch watch=topWatch;
        topWatch=null;
        if(watch!=null){
            if(watch.tree.isAlive())watch.tree.removeOnPreDrawListener(watch);
            watch.view.removeOnAttachStateChangeListener(watch);
        }
        ImageView view=translatedTop.get();
        translatedTop.clear();
        if(Build.VERSION.SDK_INT>=29 && view!=null && view.getTransitionAlpha()==0f)view.setTransitionAlpha(1f);
    }

    void clearTopBinding() {
        topProof=null;
        releaseTranslatedTop();
    }

    void suspend() {
        restoreContainer();
        releaseTranslatedTop();
    }

    void restore() {
        restoreContainer();
        clearTopBinding();
    }
}
