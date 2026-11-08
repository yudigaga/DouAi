package com.douai.module.feature;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Captures current-work URLs before loading bounded visual input on the analysis worker. */
final class CommentAiAnalysisMedia {
    private static final String SEARCH_SCOPE = "搜索得到的其他作品只能作为外部参考，不能当作当前作品的画面证据。";

    private CommentAiAnalysisMedia() { }

    static final class Source {
        final String kind;
        final List<String> galleryUrls;
        final String videoUrl;
        final String coverUrl;
        final String failure;

        private Source(String kind, List<String> galleryUrls, String videoUrl, String coverUrl, String failure) {
            this.kind = kind;
            this.galleryUrls = Collections.unmodifiableList(new ArrayList<>(galleryUrls));
            this.videoUrl = videoUrl;
            this.coverUrl = coverUrl;
            this.failure = failure;
        }
    }

    static final class Result {
        final List<ChatImage> images;
        final String document;
        final String kind;
        final String failure;

        private Result(List<ChatImage> images, String document, String kind, String failure) {
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.document = document + "\n" + SEARCH_SCOPE;
            this.kind = kind;
            this.failure = failure;
        }
    }

    /** No host model, mutable URL collection or network operation escapes this call. */
    static Source capture(Object aweme) {
        List<String> gallery = new ArrayList<>();
        try {
            Object rawGallery = DownloadActionHook.jsonValue(aweme, "images");
            if (rawGallery instanceof Iterable<?>) {
                for (Object image : (Iterable<?>) rawGallery) {
                    String url = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
                    if (url == null) {
                        url = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "download_url_list"));
                    }
                    if (url == null || !AutomationImages.isMediaUrl(url)) {
                        throw new IllegalArgumentException("Current-work image URL is unavailable");
                    }
                    gallery.add(url);
                    if (gallery.size() == ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            return new Source("gallery", Collections.emptyList(), null, null, failure.getClass().getSimpleName());
        }
        if (!gallery.isEmpty()) return new Source("gallery", gallery, null, null, "");

        String video = null, cover = null, failure = "";
        try { video = AutomationCommentMedia.videoUrl(aweme); }
        catch (RuntimeException | LinkageError unavailable) { failure = unavailable.getClass().getSimpleName(); }
        try {
            List<String> covers = AutomationCommentMedia.imageUrls(aweme);
            if (!covers.isEmpty()) cover = covers.get(0);
        } catch (RuntimeException | LinkageError unavailable) {
            if (failure.isEmpty()) failure = unavailable.getClass().getSimpleName();
        }
        if (video != null || cover != null) return new Source("video", Collections.emptyList(), video, cover, failure);
        return new Source("none", Collections.emptyList(), null, null,
                failure.isEmpty() ? "media_unavailable" : failure);
    }

    static Result load(Context context, Source source, MediaReadCancellation cancellation) throws Exception {
        try {
            cancellation.start();
            if (source == null) return unavailable("source_unavailable");
            if ("gallery".equals(source.kind)) {
                if (source.galleryUrls.isEmpty()) return unavailable(source.failure);
                List<ChatImage> images = AutomationImages.load(source.galleryUrls, cancellation);
                cancellation.check();
                return new Result(images, "本轮已提供当前作品图文中的 " + images.size()
                        + " 张图片，按作品中的顺序排列；最多提供 4 张，非全量图文内容，不能据此断言未提供的图片。"
                        + "未提供音频或语音。", "gallery", "");
            }

            String failure = source.failure;
            if (source.videoUrl != null) {
                File local = null;
                try {
                    local = AutomationCommentMedia.downloadVideo(context, source.videoUrl, cancellation);
                    ChatMediaLoader.Document video = ChatMediaLoader.loadVideoDocumentFile(local,
                            "当前作品视频", ChatCompletionClient.MAX_IMAGES_PER_MESSAGE, cancellation);
                    cancellation.check();
                    return new Result(video.images, "本轮已提供当前作品的视频抽样画面。\n" + video.text,
                            "video_frames", "");
                } catch (Exception unavailable) {
                    failure = failureKind(unavailable, cancellation);
                    if ("TimeoutException".equals(failure)) return unavailable(failure);
                } finally { ChatMediaLoader.deleteTemporary(local); }
            }
            if (source.coverUrl != null) {
                cancellation.check();
                List<ChatImage> images = AutomationImages.load(Collections.singletonList(source.coverUrl), cancellation);
                cancellation.check();
                return new Result(images, "视频抽样画面不可用，本轮仅提供当前作品的 1 张封面。"
                        + "封面不能用于确认具体舞蹈动作、完整过程或其余视频画面；未提供音频或语音。",
                        "cover", failure.isEmpty() ? "video_source_unavailable" : failure);
            }
            return unavailable(failure.isEmpty() ? "media_unavailable" : failure);
        } catch (Exception unavailable) {
            return unavailable(failureKind(unavailable, cancellation));
        }
    }

    private static String failureKind(Exception failure, MediaReadCancellation cancellation) throws Exception {
        if (failure instanceof CancellationException) throw (CancellationException) failure;
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        if (Thread.currentThread().isInterrupted()) {
            cancellation.cancel();
            throw new CancellationException("媒体读取已取消");
        }
        try { cancellation.check(); }
        catch (TimeoutException timeout) { return "TimeoutException"; }
        return failure.getClass().getSimpleName();
    }

    private static Result unavailable(String failure) {
        return new Result(Collections.emptyList(), "本轮未提供当前作品的图片或视频画面，媒体读取不可用。"
                + "请依据元数据和实际提供的文字资料回答，不能推断未看到的画面、动作或音频。",
                "none", failure);
    }
}
