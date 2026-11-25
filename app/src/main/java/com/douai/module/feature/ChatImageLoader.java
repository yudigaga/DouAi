package com.douai.module.feature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Reads only the selected document, normalizing size and orientation for inline model input. */
public final class ChatImageLoader {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_EDGE = 2048;

    private ChatImageLoader() { }

    public static ChatImage load(Context context, Uri uri) throws Exception {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalArgumentException("无法读取所选图片");
            return decode(read(stream));
        }
    }

    public static byte[] read(InputStream stream) throws Exception {
        return read(stream, null);
    }

    static byte[] read(InputStream stream, MediaReadCancellation cancellation) throws Exception {
        ChatMediaLoader.checkCancelled(cancellation);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            ChatMediaLoader.checkCancelled(cancellation);
            if (bytes.size() + count > MAX_SOURCE_BYTES) throw new IllegalArgumentException("原始图片不能超过 16 MB");
            bytes.write(buffer, 0, count);
        }
        ChatMediaLoader.checkCancelled(cancellation);
        return bytes.toByteArray();
    }

    public static ChatImage decode(byte[] source) throws Exception {
        return decode(source, null);
    }

    static ChatImage decode(byte[] source, MediaReadCancellation cancellation) throws Exception {
        ChatMediaLoader.checkCancelled(cancellation);
        if (source == null || source.length == 0 || source.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("图片为空或超过 16 MB");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, options);
        ChatMediaLoader.checkCancelled(cancellation);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 100_000_000L) {
            throw new IllegalArgumentException("图片格式或尺寸不支持");
        }
        int edge = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (edge / options.inSampleSize > MAX_EDGE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        long pixels = ((options.outWidth + options.inSampleSize - 1L) / options.inSampleSize)
                * ((options.outHeight + options.inSampleSize - 1L) / options.inSampleSize);
        try (MediaMemoryBudget.Reservation ignored = MediaMemoryBudget.acquire(pixels * 8L, cancellation)) {
            ChatMediaLoader.checkCancelled(cancellation);
            Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
            if (decoded == null) throw new IllegalArgumentException("无法解码所选图片");
            Bitmap opaque = null;
            try {
                ChatMediaLoader.checkCancelled(cancellation);
                Matrix orientation = orientation(source);
                ChatMediaLoader.checkCancelled(cancellation);
                RectF bounds = new RectF(0, 0, decoded.getWidth(), decoded.getHeight());
                orientation.mapRect(bounds);
                orientation.postTranslate(-bounds.left, -bounds.top);
                opaque = Bitmap.createBitmap(Math.round(bounds.width()), Math.round(bounds.height()), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(opaque);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(decoded, orientation, new Paint(Paint.FILTER_BITMAP_FLAG));
                ChatMediaLoader.checkCancelled(cancellation);
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                if (!opaque.compress(Bitmap.CompressFormat.JPEG, 85, jpeg)) throw new IllegalStateException("图片转换失败");
                ChatMediaLoader.checkCancelled(cancellation);
                if (jpeg.size() > ChatImage.MAX_BYTES) throw new IllegalArgumentException("转换后的图片超过 4 MB，请选择较小图片");
                return new ChatImage("image/jpeg", jpeg.toByteArray());
            } finally {
                if (opaque != null) opaque.recycle();
                decoded.recycle();
            }
        }
    }

    /** Share EXIF orientation without routing thumbnails through AI media encoding. */
    static Matrix orientation(byte[] source) {
        Matrix orientation = new Matrix();
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(source));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: orientation.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: orientation.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: orientation.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    orientation.setRotate(90); orientation.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: orientation.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    orientation.setRotate(270); orientation.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: orientation.setRotate(270); break;
                default: break;
            }
        } catch (java.io.IOException ignored) { }
        return orientation;
    }

    public static Bitmap thumbnail(ChatImage image) {
        byte[] bytes = java.util.Base64.getDecoder().decode(image.base64Data);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 128);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    /** Decode the locally retained image for display, without changing the stored bytes. */
    public static Bitmap localPreview(ChatImage image) {
        if (image == null || Thread.currentThread().isInterrupted()) return null;
        byte[] bytes = java.util.Base64.getDecoder().decode(image.base64Data);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 100_000_000L) return null;
        int edge = Math.max(options.outWidth, options.outHeight);
        int sample = 1;
        while ((edge + sample - 1L) / sample > MAX_EDGE) sample *= 2;
        long width = (options.outWidth + sample - 1L) / sample;
        long height = (options.outHeight + sample - 1L) / sample;
        try (MediaMemoryBudget.Reservation reservation = MediaMemoryBudget.tryAcquire(width * height * 4L)) {
            if (reservation == null || Thread.currentThread().isInterrupted()) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap != null && Thread.currentThread().isInterrupted()) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        }
    }
}
