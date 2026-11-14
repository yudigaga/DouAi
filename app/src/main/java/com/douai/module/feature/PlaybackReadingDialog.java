package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.scan.HostAdaptation;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

/** Current-work chapter and subtitle reader; downloading happens only after selecting a track. */
public final class PlaybackReadingDialog {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> { Thread t = new Thread(r, "DouAi-subtitle"); t.setDaemon(true); return t; });
    private static java.lang.ref.WeakReference<Session> active = new java.lang.ref.WeakReference<>(null);
    private PlaybackReadingDialog() { }
    public static void show(Activity owner, Object aweme, BooleanSupplier isCurrent, LongPredicate seekMillis) {
        if (!HostAdaptation.PROFILE_410.equals(HostAdaptation.currentProfile())) return;
        Session previous = active.get();
        if (previous != null) previous.close();
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        Session session = new Session(owner, aweme, isCurrent, seekMillis);
        active = new java.lang.ref.WeakReference<>(session); session.show();
    }
    private static final class Track {
        final String name, format, url;
        Track(String name, String format, String url) { this.name = name; this.format = format; this.url = url; }
    }
    private static final class Session {
        final Activity owner;
        final BooleanSupplier current;
        final LongPredicate seek;
        final Handler main = new Handler(Looper.getMainLooper());
        final List<PlaybackReadingText.Line> chapters = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
        List<PlaybackReadingText.Line> rows;
        LinearLayout results;
        TextView status;
        EditText query;
        AlertDialog dialog;
        AlertDialog child;
        MediaReadCancellation cancellation;
        Future<?> job;
        boolean closed;
        int generation;
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (!valid()) { close(); return; } main.postDelayed(this, 500);
            }
        };
        Session(Activity owner, Object aweme, BooleanSupplier current, LongPredicate seek) {
            this.owner = owner; this.current = current; this.seek = seek;
            Object entries = HostTargets.invoke(aweme, "getMoreChapterList");
            if (entries instanceof Iterable<?>) for (Object entry : (Iterable<?>) entries) {
                Object time = DownloadActionHook.jsonValue(entry, "timestamp");
                String title = text(entry, "desc"), keyword = text(entry, "key_word");
                if (!title.isEmpty() || !keyword.isEmpty())
                    chapters.add(new PlaybackReadingText.Line(time instanceof Number && ((Number)time).longValue() >= 0
                            ? ((Number)time).longValue() : -1, title.isEmpty() ? keyword : title + (keyword.isEmpty() ? "" : " · " + keyword)));
                if (chapters.size() >= 500) break;
            }
            Object cla = DownloadActionHook.jsonValue(aweme, "cla_info");
            Object raw = DownloadActionHook.jsonValue(cla, "list");
            if (raw instanceof Iterable<?>) for (Object entry : (Iterable<?>) raw) {
                String url = text(entry, "url"), format = text(entry, "format").toLowerCase(Locale.ROOT);
                String name = text(entry, "language");
                if (!url.isEmpty()) tracks.add(new Track(name.isEmpty() ? "字幕轨 " + (tracks.size() + 1) : name, format, url));
                if (tracks.size() >= 16) break;
            }
            rows = chapters;
        }
        void show() {
            LinearLayout content = new LinearLayout(owner); content.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(owner.getResources().getDisplayMetrics().density * 16); content.setPadding(pad,pad,pad,pad);
            TextView chapter = new TextView(owner); chapter.setText(String.format(Locale.ROOT,"章节（%d）",chapters.size())); chapter.setPadding(0,pad,0,pad);
            chapter.setOnClickListener(v -> { cancelRead(); rows = chapters; render(); }); content.addView(chapter);
            TextView subtitles = new TextView(owner); subtitles.setText(String.format(Locale.ROOT,"选择字幕（%d 轨）",tracks.size())); subtitles.setPadding(0,pad,0,pad);
            subtitles.setOnClickListener(v -> selectTrack()); content.addView(subtitles);
            query = new EditText(owner); query.setSingleLine(true); query.setHint("搜索章节或字幕原话"); content.addView(query);
            status = new TextView(owner); content.addView(status);
            ScrollView scroll = new ScrollView(owner); results = new LinearLayout(owner); results.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(results); content.addView(scroll, new LinearLayout.LayoutParams(-1, Math.round(owner.getResources().getDisplayMetrics().heightPixels * .36f)));
            query.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
                public void onTextChanged(CharSequence s,int start,int before,int count) { render(); }
                public void afterTextChanged(Editable s) { }
            });
            dialog = new MiuixDialogBuilder(owner, true).setTitle("章节与字幕").setView(content)
                    .setNegativeButton("关闭", null).create();
            dialog.setOnDismissListener(d -> close()); dialog.show(); render(); main.post(watch);
        }
        boolean valid() {
            try { return !closed && !owner.isFinishing() && !owner.isDestroyed() && current.getAsBoolean(); }
            catch (RuntimeException unavailable) { return false; }
        }
        void render() {
            if (closed || status == null) return; results.removeAllViews();
            String needle = query.getText().toString().trim().toLowerCase(Locale.ROOT); int matches = 0;
            for (PlaybackReadingText.Line row : rows) {
                if (!row.text.toLowerCase(Locale.ROOT).contains(needle)) continue; matches++;
                if (matches > 200) continue;
                TextView item = new TextView(owner); item.setText(String.format(Locale.ROOT,"%s  %s",clock(row.millis),row.text));
                item.setPadding(0,20,0,20); item.setTextIsSelectable(false);
                item.setMinHeight(Math.round(48 * owner.getResources().getDisplayMetrics().density));
                item.setOnClickListener(v -> child = new MiuixDialogBuilder(owner, true).setTitle(row.text)
                        .setItems(row.millis < 0 ? new String[]{"复制原文"} : new String[]{"跳到这一段", "复制原文"}, (d, which) -> {
                            if (!valid()) { close(); return; }
                            if (which == 0 && row.millis >= 0) { if (!seek.test(row.millis)) toast("当前作品暂时不能定位"); }
                            else { ClipboardManager clipboard = (ClipboardManager) owner.getSystemService(Activity.CLIPBOARD_SERVICE);
                                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("原文",row.text)); }
                        }).setNegativeButton("取消", null).show()); results.addView(item);
            }
            status.setText(rows.isEmpty() ? "当前没有可读内容；可改选字幕或使用个人书签" : String.format(Locale.ROOT,"共 %d 条，命中 %d%s",rows.size(),matches,matches > 200 ? "（显示前 200 条）" : ""));
        }
        void selectTrack() {
            if (!valid()) { close(); return; }
            if (tracks.isEmpty()) { toast("当前作品未提供字幕轨；画面内的文字无法从这里读取"); return; }
            String[] names = new String[tracks.size()]; for(int i=0;i<names.length;i++) names[i] = tracks.get(i).name;
            child = new MiuixDialogBuilder(owner, true).setTitle("选择字幕语言").setItems(names, (d, at) -> { if(valid()) load(tracks.get(at)); })
                    .setNegativeButton("取消", null).show();
        }
        void load(Track track) {
            cancelRead(); rows = java.util.Collections.emptyList(); render();
            if (!"webvtt".equals(track.format) && !"vtt".equals(track.format)) { status.setText(String.format(Locale.ROOT,"暂不支持此字幕格式：%s",track.format)); return; }
            if (!AutomationImages.isMediaUrl(track.url)) { status.setText("字幕地址暂不支持"); return; }
            final int epoch = generation; MediaReadCancellation reading = new MediaReadCancellation(); cancellation = reading;
            status.setText(String.format(Locale.ROOT,"正在读取「%s」…",track.name));
            try { job = WORK.submit(() -> {
                try {
                    List<PlaybackReadingText.Line> loaded = PlaybackReadingText.webvtt(fetch(track.url,reading));
                    main.post(() -> { if (valid() && generation == epoch) { rows = loaded; render(); } });
                } catch (Exception failure) {
                    String message = failure instanceof IllegalArgumentException ? failure.getMessage()
                            : "字幕网络读取失败，请稍后重试";
                    main.post(() -> { if(valid() && generation == epoch) status.setText(message); });
                }
            }); } catch (java.util.concurrent.RejectedExecutionException busy) { status.setText("字幕读取忙，请稍后重试"); }
        }
        void cancelRead() { generation++; if(cancellation != null) cancellation.cancel(); if(job != null) job.cancel(true); WORK.purge(); }
        void close() {
            if(closed) return; closed = true; cancelRead(); main.removeCallbacks(watch);
            if(child != null && child.isShowing()) child.dismiss();
            if(dialog != null && dialog.isShowing()) dialog.dismiss(); if(active.get() == this) active.clear();
        }
        void toast(String value) { Toast.makeText(owner,value,Toast.LENGTH_SHORT).show(); }
    }
    static String fetch(String url, MediaReadCancellation cancellation) throws Exception {
        cancellation.start(); String current = url;
        for(int hop=0;hop<4;hop++) {
            if(!AutomationImages.isMediaUrl(current)) throw new IllegalArgumentException("字幕地址不支持");
            HttpURLConnection connection = cancellation.open(URI.create(current));
            try {
                int code = connection.getResponseCode(); cancellation.check();
                if(code >= 300 && code < 400) {
                    String next = connection.getHeaderField("Location"); if(next == null) throw new IllegalArgumentException("无效跳转");
                    current = URI.create(current).resolve(next).toString(); continue;
                }
                if(code == 401 || code == 403 || code == 410) throw new IllegalArgumentException("字幕地址已不可用，请重新打开作品获取字幕");
                if(code != 200) throw new java.io.IOException("字幕请求失败");
                if(connection.getContentLengthLong() > PlaybackReadingText.MAX_CHARS) throw new IllegalArgumentException("字幕超过 2 MiB 读取上限");
                try(InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while((n=in.read(buffer)) != -1) { cancellation.check(); if(out.size()+n > PlaybackReadingText.MAX_CHARS) throw new IllegalArgumentException("字幕太大"); out.write(buffer,0,n); }
                    cancellation.check(); return out.toString(StandardCharsets.UTF_8.name());
                }
            } finally { cancellation.close(connection); }
        }
        throw new IllegalArgumentException("跳转过多");
    }
    private static String text(Object object,String field) { Object v=DownloadActionHook.jsonValue(object,field); return v instanceof String ? ((String)v).trim() : ""; }
    private static String clock(long millis) { if(millis < 0) return "无时间码"; long s=millis/1000; return String.format(Locale.ROOT,"%d:%02d",s/60,s%60); }
}

