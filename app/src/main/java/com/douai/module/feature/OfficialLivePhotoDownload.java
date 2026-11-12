package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Uses Douyin's actual LivePhoto encoder, then saves the encoded file through our destination. */
final class OfficialLivePhotoDownload {
    interface Encoder {
        Encoded encode(ClassLoader loader, File image, File video) throws Exception;
        boolean isLivePhoto(ClassLoader loader, File file) throws Exception;
    }

    static final class Encoded {
        final int code;
        final boolean savedToAlbum;
        final List<String> paths;
        Encoded(int code, boolean savedToAlbum, List<String> paths) {
            this.code = code;
            this.savedToAlbum = savedToAlbum;
            this.paths = paths == null ? Collections.emptyList() : new ArrayList<>(paths);
        }
    }

    static Encoder encoder = new HostEncoder();
    private OfficialLivePhotoDownload() { }

    static boolean start(Context source, Object aweme, int imageIndex, LocalOptions options,
                         Consumer<String> log) {
        if (source == null || aweme == null || options == null) return false;
        Context context = source.getApplicationContext();
        if (context == null) return false;
        final Plan plan;
        try { plan = plan(aweme, imageIndex, options); }
        catch (Exception failure) {
            toast(context, "当前实况缺少可下载的图片或动态视频");
            return false;
        }
        String key = "official-live-photo\n" + plan.imageUrl + '\n' + plan.videoUrl + '\n'
                + plan.name + '\n' + options.downloadSaveTree();
        Encoder selectedEncoder = encoder;
        com.douai.module.analytics.LocalAnalytics.FlowOperation flow = AnalyticsActionCapture.capture(source,aweme).operation("download");
        if (flow != null) { flow.stage("entry"); flow.stage("request"); }
        DownloadTasks.Task task = DownloadTasks.submit(context, key, plan.name, transfer -> {
            try { return save(context, plan, options.downloadSaveTree(), selectedEncoder, transfer); }
            catch (Exception failure) {
                if (!transfer.isCancelled() && !(failure instanceof java.util.concurrent.CancellationException)) {
                    if (log != null) try { log.accept("LivePhoto save failed: " + failure.getClass().getSimpleName()); }
                    catch (RuntimeException ignored) { }
                    toast(context, failure instanceof UnsupportedOperationException
                            ? failure.getMessage() : "实况保存失败，未改存图片或视频");
                }
                throw failure;
            }
        }, log, flow, 1);
        if (task == null) {
            toast(context, "媒体正在保存或下载队列已满");
            return false;
        }
        toast(context, "实况已加入下载任务，可在设置中查看或取消");
        return true;
    }

    static final class Plan {
        final String imageUrl, videoUrl, name;
        final ClassLoader loader;
        Plan(String imageUrl, String videoUrl, String name, ClassLoader loader) {
            this.imageUrl = imageUrl; this.videoUrl = videoUrl; this.name = name; this.loader = loader;
        }
    }

    static Plan plan(Object aweme, int index, LocalOptions options) {
        Object raw = DownloadActionHook.jsonValue(aweme, "images");
        if (!(raw instanceof List<?>) || index < 0 || index >= ((List<?>) raw).size()) {
            throw new IllegalArgumentException("实况图片序号无效");
        }
        Object image = ((List<?>) raw).get(index);
        if (!OfficialMediaModel.isLivePhoto(aweme, image)) {
            throw new IllegalArgumentException("当前图片不是实况");
        }
        boolean clean = options.enabled("remove_watermark", true);
        String imageUrl = clean ? DownloadActionHook.firstUrlValue(
                DownloadActionHook.jsonValue(image, "watermark_free_download_url_list")) : null;
        if (imageUrl == null) imageUrl = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
        if (imageUrl == null && !clean) imageUrl = DownloadActionHook.firstUrlValue(
                DownloadActionHook.jsonValue(image, "download_url_list"));
        String videoUrl = DownloadActionHook.findPlaybackVideoUrl(image);
        if (videoUrl == null && !clean) videoUrl = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(
                DownloadActionHook.jsonValue(image, "video"), "download_addr"));
        if (imageUrl == null || videoUrl == null) throw new IllegalArgumentException("实况来源不完整");
        return new Plan(imageUrl, videoUrl, MediaNaming.forAweme(aweme, options, "livephoto", index + 1, "jpg"),
                aweme.getClass().getClassLoader());
    }

    static String save(Context context, Plan plan, String tree, Encoder selectedEncoder,
                       DownloadTransfer transfer) throws Exception {
        transfer.check();
        File owned = Files.createTempDirectory(context.getCacheDir().toPath(), "douai-live-").toFile().getCanonicalFile();
        try {
            File image = new File(owned, "image.jpg");
            File video = new File(owned, "video.mp4");
            transfer.file(plan.name, 3);
            MediaStoreDownloader.downloadTemporary(plan.imageUrl, image, transfer);
            transfer.fileDone();
            transfer.file(plan.name, 3);
            MediaStoreDownloader.downloadTemporary(plan.videoUrl, video, transfer);
            transfer.fileDone();
            transfer.phase("正在编码实况");
            transfer.check();
            Encoded result = selectedEncoder.encode(plan.loader, image, video);
            transfer.evidence("live_photo_encoder_returned", "code=" + (result == null ? "null" : result.code)
                    + "; business_result=validating");
            transfer.check();
            File output = validatedOutput(result, plan.loader, selectedEncoder);
            transfer.evidence("live_photo_validated", "is_live_photo=true; bytes=" + output.length()
                    + "; output_ref=" + com.douai.module.diagnostics.DouAiLog.reference(output.getAbsolutePath()));
            transfer.check();
            String extension = extension(output);
            String name = MediaNaming.sanitize(plan.name.substring(0, plan.name.length() - 4), extension);
            transfer.file(name, 3);
            transfer.phase("正在保存实况");
            String message = MediaStoreDownloader.saveLocalImage(context, output, name,
                    "heic".equals(extension) ? "image/heic" : "image/jpeg", tree, transfer);
            return message;
        } finally {
            // The encoder's external outputs may belong to the host; only our fresh cache is owned.
            deleteOwned(owned, owned.getAbsolutePath());
        }
    }

    static File validatedOutput(Encoded result, ClassLoader loader, Encoder selectedEncoder) throws Exception {
        if (result == null || result.code != 0) throw new IOException("实况编码未成功");
        if (result.savedToAlbum) throw new UnsupportedOperationException("当前设备的实况编码不支持独立文件保存");
        if (result.paths.size() != 1 || result.paths.get(0) == null) {
            throw new UnsupportedOperationException("当前设备未返回可独立保存的实况文件");
        }
        File output = new File(result.paths.get(0)).getCanonicalFile();
        if (!output.isFile() || output.length() == 0 || !selectedEncoder.isLivePhoto(loader, output)) {
            throw new IOException("宿主未确认输出为实况照片");
        }
        extension(output);
        return output;
    }

    private static String extension(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "jpg";
        if (name.endsWith(".heic")) return "heic";
        throw new IOException("不支持的实况封装格式");
    }

    static void deleteOwned(File target, String root) {
        try {
            String path = target.getCanonicalPath();
            if (!path.equals(root) && !path.startsWith(root + File.separator)) return;
            File[] children = target.listFiles();
            if (children != null) for (File child : children) deleteOwned(child, root);
            target.delete();
        } catch (IOException | SecurityException ignored) { }
    }

    private static final class HostEncoder implements Encoder {
        private static HostAdaptation.Profile hostProfile() throws ReflectiveOperationException {
            HostAdaptation.Profile profile = HostAdaptation.currentProfile();
            if (profile == null) throw new ReflectiveOperationException("宿主实况编码适配版本未确认");
            return profile;
        }

        @Override public Encoded encode(ClassLoader loader, File image, File video) throws Exception {
            HostAdaptation.Profile profile = hostProfile();
            boolean legacy = profile == HostAdaptation.PROFILE_406;
            boolean version410 = profile == HostAdaptation.PROFILE_410;
            // This ROM branch encodes directly into its system album, not a portable file.
            Class<?> rom = Class.forName(legacy ? "X.0BDp" : version410 ? "X.0qS2" : "X.0eiB", true, loader);
            if (Boolean.TRUE.equals(invoke(rom.getMethod("LIZLLL"), null))) {
                throw new UnsupportedOperationException("当前设备的实况编码不支持独立文件保存");
            }
            Object album = album(loader);
            Class<?> contract = Class.forName(legacy ? "X.0PoP" : version410 ? "X.0UlC" : "X.0S4A", false, loader);
            Object result = invoke(contract.getMethod(legacy ? "Xi" : version410 ? "gj" : "hj", String.class, String.class, long.class),
                    album, image.getAbsolutePath(), video.getAbsolutePath(), 0L);
            if (result == null) throw new IOException("宿主实况编码器没有返回结果");
            Object paths = field(result, "LIZ");
            List<String> checked = new ArrayList<>();
            if (paths instanceof List<?>) for (Object path : (List<?>) paths) {
                if (!(path instanceof String)) throw new IOException("宿主实况文件路径无效");
                checked.add((String) path);
            }
            Object code = field(result, "LIZJ");
            Object saved = field(result, "LIZIZ");
            if (!(code instanceof Number) || !(saved instanceof Boolean)) throw new IOException("宿主实况结果不兼容");
            return new Encoded(((Number) code).intValue(), (Boolean) saved, checked);
        }

        @Override public boolean isLivePhoto(ClassLoader loader, File file) throws Exception {
            HostAdaptation.Profile profile = hostProfile();
            Class<?> contract = Class.forName(profile == HostAdaptation.PROFILE_406 ? "X.0PoP"
                    : profile == HostAdaptation.PROFILE_410 ? "X.0UlC" : "X.0S4A", false, loader);
            return Boolean.TRUE.equals(invoke(contract.getMethod("isLivePhoto", String.class),
                    album(loader), file.getAbsolutePath()));
        }

        private static Object album(ClassLoader loader) throws Exception {
            Class<?> serviceType = Class.forName("com.ss.android.ugc.aweme.services.IExternalService", true, loader);
            Object companion = serviceType.getField("Companion").get(null);
            Object service = invoke(companion.getClass().getMethod("getOrDefault"), companion);
            if (service == null) throw new IOException("宿主实况服务尚未就绪");
            Object album = invoke(serviceType.getMethod("albumService"), service);
            if (album == null) throw new IOException("宿主实况服务不可用");
            return album;
        }

        private static Object field(Object target, String name) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        }

        private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
            try { return method.invoke(target, arguments); }
            catch (java.lang.reflect.InvocationTargetException wrapper) {
                Throwable cause = wrapper.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("宿主实况服务调用失败", cause);
            }
        }
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try { Toast.makeText(context, message, Toast.LENGTH_SHORT).show(); }
            catch (RuntimeException ignored) { }
        });
    }
}
