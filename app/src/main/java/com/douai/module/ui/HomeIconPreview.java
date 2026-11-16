package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.feature.MediaMemoryBudget;

/** A worker-decoded WebP preview whose reservation follows the displayed bitmap. */
final class HomeIconPreview implements AutoCloseable {
    private final Bitmap bitmap;
    private final MediaMemoryBudget.Reservation reservation;
    private boolean closed;

    private HomeIconPreview(Bitmap bitmap,MediaMemoryBudget.Reservation reservation) {
        this.bitmap=bitmap;this.reservation=reservation;
    }

    static HomeIconPreview decode(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>HomeUiCustomizationSettings.MAX_ICON_BYTES)
            throw new IllegalArgumentException("图标预览数据无效");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0 || bounds.outHeight<=0 || bounds.outWidth>512 || bounds.outHeight>512)
            throw new IllegalArgumentException("图标预览尺寸无效");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
        while((bounds.outWidth+options.inSampleSize-1)/options.inSampleSize>256
                || (bounds.outHeight+options.inSampleSize-1)/options.inSampleSize>256)options.inSampleSize*=2;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        long memory=bytes.length+4L*((bounds.outWidth+options.inSampleSize-1)/options.inSampleSize)
                *((bounds.outHeight+options.inSampleSize-1)/options.inSampleSize);
        MediaMemoryBudget.Reservation reservation=MediaMemoryBudget.tryAcquire(memory);
        if(reservation==null)throw new IllegalArgumentException("图片内存繁忙，请稍后重试预览");
        Bitmap bitmap=null;
        try {
            bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(bitmap==null || bitmap.getWidth()>256 || bitmap.getHeight()>256)
                throw new IllegalArgumentException("无法解码图标预览");
            HomeIconPreview preview=new HomeIconPreview(bitmap,reservation);bitmap=null;reservation=null;
            return preview;
        }catch(OutOfMemoryError error){throw new IllegalArgumentException("图片内存不足，请稍后重试预览");}
        finally {
            if(bitmap!=null)bitmap.recycle();
            if(reservation!=null)reservation.close();
        }
    }

    Bitmap bitmap(){return bitmap;}

    @Override public void close() {
        if(closed)return;closed=true;bitmap.recycle();reservation.close();
    }
}
