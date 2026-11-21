package com.douai.module.feature;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Compares the native cover's painted rect with the video plane in the same card. */
final class FeedCoverGeometry {
    private static final HostReflectionCache METHODS = new HostReflectionCache();

    private FeedCoverGeometry() { }

    static boolean matches(View card, View cover, View render) {
        if (!(card instanceof ViewGroup) || !(cover instanceof ImageView)
                || !(render instanceof SurfaceView || render instanceof TextureView)
                || !card.isAttachedToWindow() || !cover.isAttachedToWindow() || !render.isAttachedToWindow()) return false;
        ImageView image=(ImageView)cover;
        if (image.getDrawable()==null) return false;
        try {
            Object hierarchy=HostTargets.invoke(image,"getHierarchy");
            Method actual=hierarchy==null?null:METHODS.declaredMethod(hierarchy.getClass(),
                    "getActualImageBounds","(Landroid/graphics/RectF;)V");
            if (actual==null || !Modifier.isPublic(actual.getModifiers()) || Modifier.isStatic(actual.getModifiers())
                    || actual.getReturnType()!=void.class) return false;
            RectF coverBounds=new RectF(), videoBounds=new RectF(0,0,render.getWidth(),render.getHeight());
            float[] values=new float[9];
            // The CoverImageView itself can be full-size while Fresco paints a corner thumbnail.
            actual.invoke(hierarchy,coverBounds);
            if (!valid(coverBounds) || !map(image.getImageMatrix(),coverBounds,values)
                    || render instanceof TextureView && !((TextureView)render).getTransform(new Matrix()).isIdentity()) return false;
            coverBounds.offset(image.getPaddingLeft(),image.getPaddingTop());
            if (!coverBounds.intersect(image.getPaddingLeft(),image.getPaddingTop(),
                    image.getWidth()-image.getPaddingRight(),image.getHeight()-image.getPaddingBottom())) return false;
            if (!inCard(cover,card,coverBounds,values) || !inCard(render,card,videoBounds,values)) return false;
            float tolerance=2f*card.getResources().getDisplayMetrics().density;
            return Math.abs(coverBounds.left-videoBounds.left)<=tolerance
                    && Math.abs(coverBounds.top-videoBounds.top)<=tolerance
                    && Math.abs(coverBounds.right-videoBounds.right)<=tolerance
                    && Math.abs(coverBounds.bottom-videoBounds.bottom)<=tolerance;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static boolean inCard(View view, View card, RectF bounds, float[] values) {
        for (int depth=0; depth<=32; depth++) {
            if (!valid(bounds) || view.getClipToOutline() || view.getAnimation()!=null) return false;
            Rect clip=view.getClipBounds();
            if (clip!=null && !bounds.intersect(clip.left,clip.top,clip.right,clip.bottom)) return false;
            if (view==card) return bounds.intersect(card.getPaddingLeft(),card.getPaddingTop(),
                    card.getWidth()-card.getPaddingRight(),card.getHeight()-card.getPaddingBottom());
            if (!(view.getParent() instanceof ViewGroup)) return false;
            ViewGroup parent=(ViewGroup)view.getParent();
            if (!map(view.getMatrix(),bounds,values)) return false;
            bounds.offset(view.getLeft()-parent.getScrollX(),view.getTop()-parent.getScrollY());
            if (parent.getClipToPadding() && !bounds.intersect(parent.getPaddingLeft(),parent.getPaddingTop(),
                    parent.getWidth()-parent.getPaddingRight(),parent.getHeight()-parent.getPaddingBottom())) return false;
            if (parent.getClipChildren() && !bounds.intersect(0,0,parent.getWidth(),parent.getHeight())) return false;
            view=parent;
        }
        return false;
    }

    private static boolean map(Matrix matrix, RectF bounds, float[] values) {
        if (!matrix.isIdentity()) {
            matrix.getValues(values);
            if (values[Matrix.MSCALE_X]<=0 || values[Matrix.MSCALE_Y]<=0
                    || values[Matrix.MSKEW_X]!=0 || values[Matrix.MSKEW_Y]!=0
                    || values[Matrix.MPERSP_0]!=0 || values[Matrix.MPERSP_1]!=0 || values[Matrix.MPERSP_2]!=1) return false;
            matrix.mapRect(bounds);
        }
        return valid(bounds);
    }

    private static boolean valid(RectF bounds) {
        return !bounds.isEmpty() && Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom);
    }
}
