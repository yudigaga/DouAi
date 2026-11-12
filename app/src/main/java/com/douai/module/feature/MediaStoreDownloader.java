package com.douai.module.feature;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;
import com.douai.module.diagnostics.DouAiLog;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** User-initiated media saving through MediaStore, with DownloadManager fallback before API 29. */
public final class MediaStoreDownloader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L;
    static DownloadTransfer.Connections connections = uri -> (HttpURLConnection) uri.toURL().openConnection();
    interface OutputStreams { OutputStream open(ContentResolver resolver, Uri target) throws Exception; }
    static OutputStreams outputs = (resolver, target) -> resolver.openOutputStream(target, "w");

    private MediaStoreDownloader() { }

    public static boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean download(Context sourceContext, String url, String fileName,
                                   boolean audio, Consumer<String> log) {
        return download(sourceContext, url, fileName, audio, "", log);
    }

    public static boolean download(Context sourceContext, String url, String fileName,
                                   boolean audio, String tree, Consumer<String> log) {
        return download(sourceContext,url,fileName,audio,tree,log,null,0);
    }
    static boolean download(Context sourceContext,String url,String fileName,boolean audio,String tree,Consumer<String> log,
            com.douai.module.analytics.LocalAnalytics.FlowOperation flow,int taskIndex) {
        return download(sourceContext, url, fileName,
                audio ? "audio/mpeg" : "video/mp4",
                audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES,
                audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                tree, log, flow, taskIndex);
    }

    public static boolean downloadImage(Context sourceContext, String url, String fileName,
                                        Consumer<String> log) {
        return downloadImage(sourceContext, url, fileName, "", log);
    }

    public static boolean downloadImage(Context sourceContext, String url, String fileName,
                                        String tree, Consumer<String> log) {
        return downloadImage(sourceContext,url,fileName,tree,log,null,0);
    }
    static boolean downloadImage(Context sourceContext,String url,String fileName,String tree,Consumer<String> log,
            com.douai.module.analytics.LocalAnalytics.FlowOperation flow,int taskIndex) {
        String mimeType = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                ? "image/png" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".webp")
                ? "image/webp" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".gif")
                ? "image/gif" : fileName != null
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".heic")
                ? "image/heic" : "image/jpeg";
        return download(sourceContext, url, fileName, mimeType, Environment.DIRECTORY_PICTURES,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, tree, log, flow, taskIndex);
    }

    private static boolean download(Context sourceContext, String url, String fileName,
                                    String mimeType, String directory, Uri collection,
                                    String tree, Consumer<String> log, com.douai.module.analytics.LocalAnalytics.FlowOperation flow, int taskIndex) {
        if (sourceContext == null || fileName == null || fileName.isEmpty()
                || fileName.contains("/") || fileName.contains("\\") || !isHttpUrl(url)) {
            DouAiLog.begin("media_download", "user_download").finish(DouAiLog.Outcome.SKIPPED,
                    "invalid_input", "context=" + (sourceContext != null) + "; valid_url=" + isHttpUrl(url));
            return false;
        }
        Context context = sourceContext.getApplicationContext();
        if (context == null) context = sourceContext;
        Context targetContext = context;
        String key = url + '\n' + fileName + '\n' + collection + '\n' + tree;
        if (DownloadTasks.submit(targetContext, key, fileName, transfer -> {
            transfer.file(fileName, 1);
            return tree == null || tree.isEmpty()
                    ? save(targetContext, url, fileName, mimeType, directory, collection, transfer)
                    : saveDocument(targetContext, url, fileName, mimeType, tree, transfer);
        }, log, flow, taskIndex) == null) {
            safeLog(log, "Media save request was deduplicated or the queue is full");
            toast(sourceContext, "媒体正在保存或下载队列已满");
            return false;
        }
        toast(sourceContext, "已加入下载任务，可在设置中查看进度或取消");
        return true;
    }

    private static String save(Context context, String mediaUrl, String fileName,
                             String mimeType, String directory, Uri collection,
                             DownloadTransfer transfer) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            HttpURLConnection check = openBundleMedia(mediaUrl, transfer);
            close(check, transfer);
            return saveWithDownloadManager(context, mediaUrl, fileName, mimeType, directory, transfer);
        }
        Uri target = null;
        HttpURLConnection connection = null;
        try {
            connection = openBundleMedia(mediaUrl, transfer);

            ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory + "/DouAi");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = resolver.insert(collection, values);
                if (target == null) throw new IllegalStateException("MediaStore 无法创建记录");
                copyResponse(connection, resolver, target, transfer);
                transfer.phase("正在保存");
                transfer.check();
                ContentValues completed = new ContentValues();
                completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(target, completed, null, null) < 1) throw new IllegalStateException("无法完成文件保存");
                transfer.fileDone();
                transfer.complete();
                transfer.saved("mediastore", target.toString(), transfer.bytes);
                target = null;
                return "媒体已保存到 " + directory + "/DouAi";
        } catch (Exception failure) {
            if (target != null) {
                try {
                    context.getContentResolver().delete(target, null, null);
                } catch (RuntimeException ignored) {
                    // Best-effort cleanup of an incomplete MediaStore row.
                }
            }
            transfer.check();
            throw failure;
        } finally {
            close(connection, transfer);
        }
    }

    private static void copyResponse(HttpURLConnection connection, ContentResolver resolver,
                                     Uri target, DownloadTransfer transfer) throws Exception {
        try (DownloadTransfer.Streams streams = transfer.streams()) {
            InputStream input = streams.add(connection.getInputStream());
            OutputStream output = streams.add(outputs.open(resolver, target));
            copyMedia(input, output, connection.getContentLengthLong(), transfer);
        }
    }

    /** Downloads an owned intermediate without publishing or completing the surrounding task. */
    static void downloadTemporary(String url, File target, DownloadTransfer transfer) throws Exception {
        if (target == null) throw new IllegalArgumentException("临时文件无效");
        transfer.check();
        // Atomic creation also ensures cleanup can never remove a pre-existing file.
        Files.createFile(target.toPath());
        HttpURLConnection connection = null;
        boolean saved = false;
        try {
            connection = openBundleMedia(url, transfer);
            try (DownloadTransfer.Streams streams = transfer.streams()) {
                InputStream input = streams.add(connection.getInputStream());
                OutputStream output = streams.add(new FileOutputStream(target));
                copyMedia(input, output, connection.getContentLengthLong(), transfer);
            }
            transfer.check();
            saved = true;
            transfer.evidence("intermediate_downloaded", "bytes=" + target.length() + "; published=false");
        } catch (Exception failure) {
            transfer.check(); throw failure;
        } finally {
            close(connection, transfer);
            if (!saved) target.delete();
        }
    }

    /** Final task step: copies encoded motion-photo bytes and commits cancellation before publication. */
    static String saveLocalImage(Context context, File source, String fileName, String mimeType,
                                 String tree, DownloadTransfer transfer) throws Exception {
        if (context == null || source == null || !source.isFile()
                || source.length() <= 0 || source.length() > MAX_DOWNLOAD_BYTES) {
            throw new IllegalArgumentException("待保存图片为空、无效或过大");
        }
        if (mimeType == null || !mimeType.matches("image/[A-Za-z0-9.+-]+")) {
            throw new IllegalArgumentException("图片格式无效");
        }
        String name = MediaNaming.sanitize(fileName == null ? "" : fileName, "");
        if (name.isEmpty()) throw new IllegalArgumentException("文件名无效");
        long length = source.length();
        transfer.check();
        transfer.phase("正在保存");
        transfer.size(length);
        ContentResolver resolver = context.getContentResolver();
        Uri target = null;
        File legacyTarget = null;
        boolean document = tree != null && !tree.isEmpty();
        try {
            transfer.check();
            if (document) {
                target = DownloadDocumentTree.create(resolver, DownloadDocumentTree.root(tree), mimeType, name);
            } else if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DouAi");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IllegalStateException("MediaStore 无法创建记录");
            } else {
                File parent = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "DouAi");
                if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IllegalStateException("无法创建图片目录");
                }
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                String suffix = dot > 0 ? name.substring(dot) : "";
                for (int index = 0; legacyTarget == null; index++) {
                    transfer.check();
                    File candidate = new File(parent, index == 0 ? name : stem + "_" + index + suffix);
                    if (candidate.createNewFile()) legacyTarget = candidate;
                }
            }
            try (DownloadTransfer.Streams streams = transfer.streams()) {
                InputStream input = streams.add(new FileInputStream(source));
                OutputStream output = streams.add(legacyTarget == null
                        ? outputs.open(resolver, target) : new FileOutputStream(legacyTarget));
                copyMedia(input, output, length, transfer);
            }
            // The final output is now closed. Commit against cancellation before publishing it.
            transfer.complete();
            if (!document && target != null) {
                ContentValues completed = new ContentValues();
                completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(target, completed, null, null) < 1) {
                    throw new IllegalStateException("无法完成文件保存");
                }
            }
            transfer.fileDone();
            transfer.check();
            if (legacyTarget != null) {
                try {
                    MediaScannerConnection.scanFile(context, new String[]{legacyTarget.getAbsolutePath()},
                            new String[]{mimeType}, null);
                } catch (RuntimeException ignored) { /* The saved image remains usable without indexing. */ }
            }
            transfer.saved(document ? "document_tree" : legacyTarget != null ? "legacy_file" : "mediastore",
                    legacyTarget != null ? legacyTarget.getAbsolutePath() : target.toString(), length);
            target = null;
            legacyTarget = null;
            return document ? "媒体已保存到所选目录" : "媒体已保存到 Pictures/DouAi";
        } catch (Exception failure) {
            if (document) DownloadDocumentTree.delete(resolver, target);
            else if (target != null) {
                try { resolver.delete(target, null, null); }
                catch (RuntimeException ignored) { }
            }
            if (legacyTarget != null) legacyTarget.delete();
            transfer.check(); throw failure;
        }
    }

    /** Saves one parser result together; failures remove all rows created for this bundle. */
    static void saveThirdPartyBundle(Context context, ThirdPartyDownloadPlan plan) throws Exception {
        saveThirdPartyBundle(context, plan, "");
    }

    static void saveThirdPartyBundle(Context context, ThirdPartyDownloadPlan plan, String tree) throws Exception {
        saveThirdPartyBundle(context, plan, tree, new DownloadTransfer());
    }

    static void saveThirdPartyBundle(Context context, ThirdPartyDownloadPlan plan,
                                     String tree, DownloadTransfer transfer) throws Exception {
        transfer.start();
        String folder = plan.baseName + "_" + System.currentTimeMillis() + "_"
                + Long.toHexString(System.nanoTime());
        if (tree != null && !tree.isEmpty()) {
            saveDocumentBundle(context, plan, tree, folder, transfer);
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            saveLegacyBundle(context, plan, folder, transfer);
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        List<Uri> created = new ArrayList<>();
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/DouAi/" + folder;
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                transfer.file(asset.name, plan.assets.size() + 1);
                HttpURLConnection connection = openBundleMedia(asset.url, transfer);
                try {
                    Uri target = createBundleRow(resolver, relativePath, asset.name, asset.mimeType);
                    created.add(target);
                    copyResponse(connection, resolver, target, transfer);
                    transfer.fileDone();
                } finally { close(connection, transfer); }
            }
            transfer.file(plan.baseName + ".txt", plan.assets.size() + 1);
            Uri metadata = createBundleRow(resolver, relativePath, plan.baseName + ".txt", "text/plain");
            created.add(metadata);
            try (DownloadTransfer.Streams streams = transfer.streams()) {
                OutputStream output = streams.add(outputs.open(resolver, metadata));
                writeMetadata(output, plan.metadata, transfer);
            }
            transfer.fileDone();
            transfer.phase("正在保存");
            ContentValues completed = new ContentValues();
            completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
            for (Uri target : created) {
                transfer.check();
                if (resolver.update(target, completed, null, null) < 1) {
                    throw new IllegalStateException("无法完成文件保存");
                }
            }
            transfer.complete();
            for (Uri output : created) transfer.saved("mediastore_bundle", output.toString(), -1);
        } catch (Exception failure) {
            for (Uri target : created) {
                try { resolver.delete(target, null, null); }
                catch (RuntimeException ignored) { }
            }
            transfer.check(); throw failure;
        }
    }

    private static String saveDocument(Context context, String url, String name, String mime,
                                      String tree, DownloadTransfer transfer) throws Exception {
        Uri target = null;
        HttpURLConnection connection = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri root = DownloadDocumentTree.root(tree);
            connection = openBundleMedia(url, transfer);
            target = DownloadDocumentTree.create(resolver, root, mime, name);
            copyResponse(connection, resolver, target, transfer);
            transfer.fileDone();
            transfer.complete();
            transfer.saved("document_tree", target.toString(), transfer.bytes);
            target = null;
            return "媒体已保存到所选目录";
        } catch (Exception failure) {
            DownloadDocumentTree.delete(context.getContentResolver(), target);
            transfer.check(); throw failure;
        } finally {
            close(connection, transfer);
        }
    }

    private static void saveDocumentBundle(Context context, ThirdPartyDownloadPlan plan,
                                           String tree, String folder, DownloadTransfer transfer) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri root = DownloadDocumentTree.root(tree);
        Uri directory = DownloadDocumentTree.create(resolver, root,
                android.provider.DocumentsContract.Document.MIME_TYPE_DIR, folder);
        List<Uri> created = new ArrayList<>();
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                transfer.file(asset.name, plan.assets.size() + 1);
                HttpURLConnection connection = openBundleMedia(asset.url, transfer);
                try {
                    Uri target = DownloadDocumentTree.create(resolver, directory, asset.mimeType, asset.name);
                    created.add(target);
                    copyResponse(connection, resolver, target, transfer);
                    transfer.fileDone();
                } finally { close(connection, transfer); }
            }
            transfer.file(plan.baseName + ".txt", plan.assets.size() + 1);
            Uri metadata = DownloadDocumentTree.create(resolver, directory, "text/plain", plan.baseName + ".txt");
            created.add(metadata);
            try (DownloadTransfer.Streams streams = transfer.streams()) {
                OutputStream output = streams.add(outputs.open(resolver, metadata));
                writeMetadata(output, plan.metadata, transfer);
            }
            transfer.fileDone();
            transfer.complete();
            for (Uri output : created) transfer.saved("document_bundle", output.toString(), -1);
        } catch (Exception failure) {
            for (Uri target : created) DownloadDocumentTree.delete(resolver, target);
            DownloadDocumentTree.delete(resolver, directory);
            transfer.check(); throw failure;
        }
    }

    @android.annotation.TargetApi(29)
    private static Uri createBundleRow(ContentResolver resolver, String path, String name, String mime) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri result = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (result == null) throw new IllegalStateException("无法创建下载文件");
        return result;
    }

    private static void saveLegacyBundle(Context context, ThirdPartyDownloadPlan plan, String folder,
                                         DownloadTransfer transfer) throws Exception {
        File parent = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DouAi");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("无法创建下载目录");
        }
        File directory = new File(parent, folder);
        Files.createDirectory(directory.toPath());
        List<File> created = new ArrayList<>();
        try {
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) {
                transfer.file(asset.name, plan.assets.size() + 1);
                HttpURLConnection connection = openBundleMedia(asset.url, transfer);
                try {
                    File target = new File(directory, asset.name);
                    Files.createFile(target.toPath());
                    created.add(target);
                    try (DownloadTransfer.Streams streams = transfer.streams()) {
                        InputStream input = streams.add(connection.getInputStream());
                        OutputStream output = streams.add(new FileOutputStream(target));
                        copyMedia(input, output, connection.getContentLengthLong(), transfer);
                    }
                    transfer.fileDone();
                } finally { close(connection, transfer); }
            }
            transfer.file(plan.baseName + ".txt", plan.assets.size() + 1);
            File metadata = new File(directory, plan.baseName + ".txt");
            Files.createFile(metadata.toPath());
            created.add(metadata);
            try (DownloadTransfer.Streams streams = transfer.streams()) {
                OutputStream output = streams.add(new FileOutputStream(metadata));
                writeMetadata(output, plan.metadata, transfer);
            }
            transfer.fileDone();
            transfer.complete();
            for (File output : created) transfer.saved("legacy_bundle", output.getAbsolutePath(), output.length());
        } catch (Exception failure) {
            for (File file : created) file.delete();
            directory.delete();
            transfer.check(); throw failure;
        }
        String[] paths = new String[created.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = created.get(i).getAbsolutePath();
        try { MediaScannerConnection.scanFile(context, paths, null, null); }
        catch (RuntimeException ignored) { /* Completed files remain saved if gallery indexing is unavailable. */ }
    }

    private static HttpURLConnection openBundleMedia(String url, DownloadTransfer transfer) throws Exception {
        transfer.check();
        if (!isHttpUrl(url)) throw new IllegalArgumentException("下载地址无效");
        HttpURLConnection connection = connections.open(URI.create(url));
        try {
            transfer.attach(connection);
            connection.setConnectTimeout(transfer.timeout(15_000));
            connection.setReadTimeout(transfer.timeout(30_000));
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) DouAi");
            int status = connection.getResponseCode();
            transfer.evidence("http_response", "status=" + status + "; result=transport_only; source_ref=" + DouAiLog.reference(url));
            if (status < 200 || status >= 300) throw new IllegalStateException("媒体请求失败：HTTP " + status);
            String type = connection.getContentType();
            if (type != null) {
                type = type.toLowerCase(java.util.Locale.ROOT);
                if (type.startsWith("text/") || type.contains("json") || type.contains("xml")
                        || type.contains("mpegurl") || type.contains("dash+xml")) {
                    throw new IllegalStateException("下载字段返回了非媒体内容");
                }
            }
            if (connection.getContentLengthLong() > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
            transfer.size(connection.getContentLengthLong());
            transfer.check();
            return connection;
        } catch (Exception failure) {
            close(connection, transfer);
            transfer.check(); throw failure;
        }
    }

    private static void copyMedia(InputStream input, OutputStream output, long expected,
                                   DownloadTransfer transfer) throws Exception {
        long copied = 0;
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            transfer.check();
            copied += count;
            if (copied > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
            output.write(buffer, 0, count);
            transfer.advance(count);
        }
        transfer.check();
        if (copied == 0) throw new IllegalStateException("下载内容为空");
        if (expected >= 0 && copied != expected) throw new IllegalStateException("媒体文件未下载完整");
    }

    private static void writeMetadata(OutputStream output, String text, DownloadTransfer transfer) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        transfer.size(bytes.length);
        transfer.check(); output.write(bytes); transfer.advance(bytes.length);
    }

    private static void close(HttpURLConnection connection, DownloadTransfer transfer) {
        if (connection == null) return;
        transfer.detach(connection);
        try { connection.disconnect(); } catch (RuntimeException ignored) { }
    }

    private static String saveWithDownloadManager(Context context, String url, String name, String mime,
                                                   String directory, DownloadTransfer transfer) throws Exception {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) throw new IllegalStateException("系统下载服务不可用");
        transfer.check();
        long id = manager.enqueue(new DownloadManager.Request(Uri.parse(url)).setTitle(name).setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(directory, "DouAi/" + name));
        transfer.evidence("system_download_queued", "download_ref=" + DouAiLog.reference(Long.toString(id))
                + "; business_result=pending");
        boolean completed = false;
        toast(context, "已加入系统下载队列");
        transfer.onStop(() -> manager.remove(id));
        try {
            while (true) {
                transfer.check();
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("系统下载任务已移除");
                    long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (bytes > MAX_DOWNLOAD_BYTES || total > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("媒体文件过大");
                    transfer.size(total);
                    transfer.advance(Math.max(0, bytes - transfer.bytes));
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        if (bytes <= 0 || total >= 0 && bytes != total) throw new IllegalStateException("系统下载文件不完整");
                        transfer.fileDone(); transfer.complete(); completed = true;
                        transfer.saved("download_manager_status_successful", Long.toString(id), bytes);
                        return "媒体已保存到 " + directory + "/DouAi";
                    }
                    if (status == DownloadManager.STATUS_FAILED) throw new IllegalStateException("系统下载失败");
                }
                Thread.sleep(250);
            }
        } finally {
            transfer.onStop(null);
            if (!completed) manager.remove(id);
        }
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // UI feedback is best-effort; the file operation remains authoritative.
            }
        });
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? "请稍后重试" : message;
    }

    private static void safeLog(Consumer<String> log, String message) {
        if (log == null) return;
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Logging must not change the download result.
        }
    }
}
