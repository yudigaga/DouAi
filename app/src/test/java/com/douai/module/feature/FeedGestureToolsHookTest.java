package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import io.github.libxposed.api.XposedInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public final class FeedGestureToolsHookTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    @org.junit.After public void cancelDownloadsStartedByTheTest() {
        DownloadTaskTestIdle.afterTest();
    }
    public static final class Aweme {
        final String aid;
        public String share_url;
        public DownloadActionHookTest.VideoFixture video;
        public List<DownloadActionHookTest.ImageFixture> images;
        Aweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }
    public static final class ItemParams { public Panel mFeedContext; }
    public static final class Holder {
        public FrameLayout w;
        public ItemParams A2;
        public String aid = "video-1";
        Aweme downloadAweme;
        public Aweme getAweme() { return downloadAweme == null ? new Aweme(aid) : downloadAweme; }
        public void selected() { }
        public void resume() { }
        public void pause() { }
    }
    public static final class Panel {
        public Holder current;
        public IPlayerManager currentPlayer = new PlayerManager(new FakeFeedWrapper(1.25f));
        int nativeDoubleTaps;
        View playControl;
        int hidePlayControlCalls;
        int showPlayControlCalls;
        public Holder currentHolder() { return current; }
        public IPlayerManager player() { return currentPlayer; }
        public void doubleTap(MotionEvent event) { nativeDoubleTaps++; }
        public void togglePlayback(Aweme aweme, boolean fromTap, boolean updateUi) {
            FakeFeedWrapper delegate = ((PlayerManager) currentPlayer).delegate;
            delegate.playing = !delegate.playing;
        }
        public void hidePlayControl() {
            hidePlayControlCalls++;
            if (playControl != null) playControl.setVisibility(View.GONE);
        }
        public void showPlayControl() {
            showPlayControlCalls++;
            if (playControl != null) playControl.setVisibility(View.VISIBLE);
        }
    }
    public interface IPlayerInfo {
        float getSpeed();
        default boolean isPlaying() { return true; }
    }
    public interface IPlayerManager extends IPlayerInfo {
        void setSpeed(float speed);
        void resumePlay();
        String play();
        default void pausePlay() { }
    }
    public static final class FakeFeedWrapper implements IPlayerManager {
        float speed;
        int speedChanges;
        boolean playing = true;
        int resumeCalls;
        int playCalls;
        int pauseCalls;
        boolean failPause;
        Runnable pauseUiAction;
        FakeFeedWrapper(float speed) { this.speed = speed; }
        public float getSpeed() { return speed; }
        public boolean isPlaying() { return playing; }
        public void setSpeed(float value) { speed = value; speedChanges++; }
        public void resumePlay() { resumeCalls++; playing = true; }
        public String play() { playCalls++; playing = true; return "started"; }
        public void pausePlay() {
            if (failPause) throw new IllegalStateException("pause unavailable");
            pauseCalls++;
            playing = false;
            if (pauseUiAction != null) pauseUiAction.run();
        }
    }
    public static final class PlayerManager implements IPlayerManager {
        final FakeFeedWrapper delegate;
        PlayerManager(FakeFeedWrapper delegate) { this.delegate = delegate; }
        @Override public float getSpeed() { return delegate.getSpeed(); }
        @Override public boolean isPlaying() { return delegate.isPlaying(); }
        @Override public void setSpeed(float speed) { delegate.setSpeed(speed); }
        @Override public void resumePlay() { delegate.resumePlay(); }
        @Override public String play() { return delegate.play(); }
        @Override public void pausePlay() { delegate.pausePlay(); }
    }
    public static final class DelayedPlayerManager implements IPlayerManager {
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean deferPlayback;
        float actualSpeed = 1.25f;
        float reportedSpeed = 1.25f;
        int speedChanges;
        int revision;
        DelayedPlayerManager(boolean deferPlayback) { this.deferPlayback = deferPlayback; }
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return reportedSpeed; }
        @Override public void setSpeed(float speed) {
            speedChanges++;
            int request = ++revision;
            if (!deferPlayback) actualSpeed = speed;
            main.postDelayed(() -> {
                if (request != revision) return;
                actualSpeed = speed;
                reportedSpeed = speed;
            }, 400);
        }
    }
    public static final class UnreportedPlayerManager implements IPlayerManager {
        float actualSpeed = 1f;
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return 0f; }
        @Override public void setSpeed(float speed) { actualSpeed = speed; }
    }
    public static final class FixedEngineReadbackPlayerManager implements IPlayerManager {
        final float engineReported;
        float lastRequested = 1f;
        FixedEngineReadbackPlayerManager(float engineReported) { this.engineReported = engineReported; }
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return engineReported; }
        @Override public void setSpeed(float speed) { lastRequested = speed; }
    }
    public static final class FrameRateReadbackPlayerManager implements IPlayerManager {
        float actualSpeed = 1f;
        final List<Float> writes = new ArrayList<>();
        @Override public void resumePlay() { }
        @Override public String play() { return null; }
        @Override public float getSpeed() { return 30f; }
        @Override public void setSpeed(float speed) { actualSpeed = speed; writes.add(speed); }
    }
    public static final class TouchLayout extends View {
        public float lastRawX;
        public float lastRawY;
        public boolean discardCurrentEvent;
        public int nativeUpCalls;
        public boolean enableLongClick = true;
        public Runnable longPressRunnable;
        public int mTimeInterval = 500;
        Runnable nativeDownAction;
        TouchLayout(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return super.dispatchTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && nativeDownAction != null) {
                nativeDownAction.run();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                nativeUpCalls++;
                if (discardCurrentEvent) {
                    discardCurrentEvent = false;
                    return false;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    || event.getActionMasked() == MotionEvent.ACTION_DOWN) discardCurrentEvent = false;
            return true;
        }
    }
    public static final class LongPress implements Runnable {
        public final TouchLayout a;
        int nativeCalls;
        LongPress(TouchLayout layout) { a = layout; }
        @Override public void run() { nativeCalls++; }
    }
    public static final class NativeDetector {
        final Handler main = new Handler(Looper.getMainLooper());
        final List<Integer> events = new ArrayList<>();
        final List<Float> xs = new ArrayList<>();
        final List<Float> ys = new ArrayList<>();
        final List<Long> eventTimes = new ArrayList<>();
        final String name;
        final List<String> dispatchOrder;
        final Runnable restoreSpeed;
        NativeDetector(String name, List<String> dispatchOrder, Runnable restoreSpeed) {
            this.name = name;
            this.dispatchOrder = dispatchOrder;
            this.restoreSpeed = restoreSpeed;
        }
        public void input(MotionEvent event) {
            events.add(event.getActionMasked());
            xs.add(event.getX());
            ys.add(event.getY());
            eventTimes.add(event.getEventTime());
            dispatchOrder.add(name + ":" + event.getActionMasked());
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Deterministic stand-in for detector-owned work that outlives child UP.
                main.postDelayed(restoreSpeed, 900);
            }
        }
        void clear() { main.removeCallbacksAndMessages(null); }
    }
    public static final class NativeContainer extends FrameLayout {
        final NativeDetector detector;
        NativeContainer(Context context, NativeDetector detector) { super(context); this.detector = detector; }
        public NativeDetector getDetector() { return detector; }
    }
    public static final class LegacyNativeContainer extends FrameLayout {
        final NativeDetector detector;
        LegacyNativeContainer(Context context, NativeDetector detector) { super(context); this.detector = detector; }
        public NativeDetector getDetector() { return detector; }
    }
    private static final class FeedRoot extends FrameLayout {
        boolean focused = true;
        FeedRoot(Context context) { super(context); }
        @Override public boolean hasWindowFocus() { return focused; }
    }

    @Test @Config(sdk = 28)
    public void gestureMenuSaveUsesOnlyThirdPartyAndNeverFallsBackAfterFailure() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"id\":\"gesture_work\",\"text\":\"caption\",\"video\":[\""
                            + server.url("/third.mp4") + "\"]}}"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4")
                    .setBody("third-party-video"));
            Map<String, Object> values = new HashMap<>();
            values.put("long_press_top_left", "menu");
            values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            values.put(ThirdPartyDownloadSettings.DIRECT, true);
            values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true);
            values.put(ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse") + "?ID=75107541&link={url}");
            values.put(ThirdPartyDownloadSettings.APP_SECRET, "old-secret");
            values.put(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
            values.put(ThirdPartyDownloadSettings.NAMING_FIELDS, "[\"data.id\"]");
            values.put(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE);
            DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
            try (Fixture f = new Fixture(values)) {
                Aweme work = new Aweme("7696151596120622001");
                work.share_url = "https://v.douyin.com/XVo-IAGSo2s/";
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("/parse", request.getRequestUrl().encodedPath());
                assertEquals(work.share_url, request.getRequestUrl().queryParameter("link"));
                assertEquals("75107541", request.getRequestUrl().queryParameter("ID"));
                assertNull(request.getRequestUrl().queryParameter("app_id"));
                assertNull(request.getHeader("Sign"));
                assertEquals("/third.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已保存");
                assertEquals(3, documents.names.size());
                assertEquals(2, documents.files.size());
                assertTrue(documents.names.containsValue("gesture_work.txt"));

                server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(503));
                clickSaveFromGestureMenu(f);
                assertEquals("/parse", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getRequestUrl().encodedPath());
                awaitDownloadToast("第三方下载失败");
                assertEquals(3, server.getRequestCount());
                android.app.DownloadManager manager = (android.app.DownloadManager)
                        f.activity.getSystemService(Context.DOWNLOAD_SERVICE);
                org.robolectric.shadows.ShadowDownloadManager downloads = org.robolectric.shadow.api.Shadow.extract(manager);
                assertEquals(0, downloads.getRequestCount());
            }
        }
    }

    @Test @Config(sdk = 28)
    public void gestureMenuSaveInOfficialModeNeverRequestsConfiguredThirdPartyApi() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("official-video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "official",
                    ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/parse") + "?url={url}"))) {
                org.robolectric.shadows.ShadowApplication application =
                        org.robolectric.shadow.api.Shadow.extract(f.activity.getApplication());
                application.grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                Aweme work = new Aweme("work");
                work.share_url = "https://v.douyin.com/XVo-IAGSo2s/";
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals(0, server.getRequestCount());
                confirmOfficialVideo();
                assertEquals("/official.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已加入系统下载队列");
                assertEquals(1, server.getRequestCount());
                android.app.DownloadManager manager = (android.app.DownloadManager)
                        f.activity.getSystemService(Context.DOWNLOAD_SERVICE);
                org.robolectric.shadows.ShadowDownloadManager downloads = org.robolectric.shadow.api.Shadow.extract(manager);
                assertEquals(1, downloads.getRequestCount());
            }
        }
    }

    private static void clickSaveFromGestureMenu(Fixture fixture) throws Throwable {
        fixture.longPress(0, 0);
        android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("保存媒体", dialog.getListView().getAdapter().getItem(1));
        dialog.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
    }

    private static android.app.AlertDialog officialChoices() {
        android.app.AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static void confirmOfficialVideo() {
        OfficialDownloadTestUi.openGroup("视频");
        android.app.AlertDialog dialog = officialChoices();
        assertEquals("保存视频", dialog.getListView().getAdapter().getItem(0));
        dialog.getListView().performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();
    }

    @Test @Config(sdk = 28)
    public void officialGestureSaveUsesTheSameCustomDirectoryAsThirdParty() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("official-video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "official",
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE))) {
                Aweme work = new Aweme("work");
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/official.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals(0, server.getRequestCount());
                confirmOfficialVideo();
                assertEquals("/official.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("媒体已保存到所选目录");
                assertEquals(1, documents.names.size());
                String id = documents.files.keySet().iterator().next();
                assertEquals("root", documents.parents.get(id));
                assertEquals("official-video", java.nio.file.Files.readString(documents.files.get(id).toPath()));
            }
        }
    }

    @Test public void directOfficialGestureWaitsForChoiceAndCancelDoesNotDownload() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "save_media"))) {
                Aweme work = new Aweme("work");
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/video.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                f.longPress(0, 0);
                android.app.AlertDialog dialog = officialChoices();
                assertEquals("视频", dialog.getListView().getAdapter().getItem(0));
                assertEquals(0, server.getRequestCount());
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
                ShadowLooper.idleMainLooper();
                assertFalse(dialog.isShowing());
                assertEquals(0, server.getRequestCount());
            }
        }
    }

    @Test @Config(sdk = 28)
    public void officialDefaultBehaviorSavesVideoWithoutOpeningChoices() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("direct-video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "save_media",
                    LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true,
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE))) {
                Aweme work = new Aweme("work");
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/video.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                android.app.AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
                f.longPress(0, 0);
                assertSame(previous, ShadowAlertDialog.getLatestAlertDialog());
                assertEquals("/video.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("媒体已保存到所选目录");
                assertEquals(1, documents.files.size());
                assertEquals("direct-video", java.nio.file.Files.readString(documents.files.values().iterator().next().toPath()));
            }
        }
    }

    @Test @Config(sdk = 28)
    public void officialDefaultBehaviorSavesAllPicturesUsingTheExistingNamingAndDirectory() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "image/png").setBody("picture"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "image/png").setBody("picture"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "save_media",
                    LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, true,
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE,
                    "download_custom_name", true, "download_name_template", "#index#"))) {
                Aweme work = new Aweme("work");
                DownloadActionHookTest.ImageFixture first = new DownloadActionHookTest.ImageFixture();
                first.url_list = List.of(server.url("/first.png").toString());
                DownloadActionHookTest.ImageFixture second = new DownloadActionHookTest.ImageFixture();
                second.url_list = List.of(server.url("/second.png").toString());
                work.images = List.of(first, second);
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/wrong-video.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                android.app.AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
                f.longPress(0, 0);
                assertSame(previous, ShadowAlertDialog.getLatestAlertDialog());
                assertEquals(java.util.Set.of("/first.png", "/second.png"), java.util.Set.of(
                        server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath(),
                        server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath()));
                awaitDownloadToast("媒体已保存到所选目录");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (documents.files.size() < 2 && System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
                }
                assertEquals(java.util.Set.of("1.png", "2.png"), new java.util.HashSet<>(documents.names.values()));
                assertEquals(2, server.getRequestCount());
            }
        }
    }

    @Test public void officialChoiceRejectsReboundWorkAndClosesOnPauseOrSettingChange() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "save_media"))) {
                Aweme work = new Aweme("work");
                work.video = new DownloadActionHookTest.VideoFixture();
                work.video.play_addr = new DownloadActionHookTest.UrlFixture(server.url("/video.mp4").toString());
                f.holder.downloadAweme = work;
                f.select();
                f.longPress(0, 0);
                android.app.AlertDialog rebound = officialChoices();
                f.holder.downloadAweme = new Aweme("replacement");
                rebound.getListView().performItemClick(null, 0, 0);
                assertEquals(0, server.getRequestCount());
                f.holder.downloadAweme = work;
                f.longPress(0, 0);
                android.app.AlertDialog paused = officialChoices();
                f.hook.onActivityPaused(f.activity);
                ShadowLooper.idleMainLooper();
                assertFalse(paused.isShowing());
                f.hook.onActivityResumed(f.activity);
                ShadowLooper.idleMainLooper();
                f.longPress(0, 0);
                android.app.AlertDialog changed = officialChoices();
                f.hook.setOptions(new LocalOptions(Collections.emptyMap()));
                ShadowLooper.idleMainLooper();
                assertFalse(changed.isShowing());
                assertEquals(0, server.getRequestCount());
            }
        }
    }

    @Test @Config(sdk = 28)
    public void parameterlessThirdPartyApiIsRequestedExactlyWithoutRequiringAWorkLink() throws Throwable {
        DownloadDocumentTreeTest.RecordingDocuments documents = DownloadDocumentTreeTest.register();
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
            server.start();
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"id\":\"fixed_api\",\"video\":[\""
                            + server.url("/media.mp4") + "\"]}}"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "video/mp4").setBody("video"));
            try (Fixture f = new Fixture(Map.of("long_press_top_left", "menu", "download_method", "third_party",
                    ThirdPartyDownloadSettings.DIRECT, true,
                    ThirdPartyDownloadSettings.GET_TEMPLATE, server.url("/latest").toString(),
                    LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE))) {
                f.select();
                clickSaveFromGestureMenu(f);
                assertEquals("/latest", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                assertEquals("/media.mp4", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS).getPath());
                awaitDownloadToast("已保存");
                assertEquals(2, documents.files.size());
                assertTrue(documents.names.containsValue("fixed_api.txt"));
            }
        }
    }

    private static void awaitDownloadToast(String expected) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            ShadowLooper.idleMainLooper();
            String toast = ShadowToast.getTextOfLatestToast();
            if (toast != null && toast.contains(expected)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
        assertTrue("Expected " + expected + ", received " + ShadowToast.getTextOfLatestToast(), false);
    }

    @Test public void lateInstallationRecoversForegroundFromSelectedFocusedHolder() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            // The Activity resumed before install, so no module lifecycle resume callback is sent.
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void alreadySelectedHolderIsRecoveredFromHostCurrentHolderCallback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void alreadySelectedHolderIsRecoveredWhenNativeReadsItsAweme() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void backgroundModelReadRecoversOnlyAfterReturningToMainThread() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try { f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder); }
                catch (Throwable error) { failure.set(error); }
            });
            reader.start();
            reader.join(2000);
            assertFalse(reader.isAlive());
            assertNull(failure.get());
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void offscreenHostReadDoesNotPreventLaterForegroundRecovery() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_left", "toggle_components"))) {
            f.root.focused = false;
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(0, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
            f.root.focused = true;
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            ShadowLooper.idleMainLooper();
            f.longPress(0, 0);
            assertEquals(1, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
        }
    }

    @Test public void hiddenAlternativeTouchLayoutDoesNotMakeActiveLayoutAmbiguous() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            TouchLayout hidden = new TouchLayout(f.activity);
            hidden.setVisibility(View.GONE);
            f.root.addView(hidden);
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void twoVisibleLayoutsUseTheTouchLayerThatScheduledTheRunnable() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.root.addView(new TouchLayout(f.activity));
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void nativeLongPressSwitchDoesNotDisableConfiguredModuleAction() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "toggle_components"))) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            f.touch.enableLongClick = false;
            f.longPress(2, 2);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void interceptNativeLongPressConsumesUnconfiguredActionAndRelease() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true))) {
            f.select();
            f.longPress(1, 1);

            assertEquals(0, f.press.nativeCalls);
            assertTrue("The intercepted long press must consume the pending release",
                    f.touch.discardCurrentEvent);

            MotionEvent up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 150, 300, 0);
            try {
                assertEquals(Boolean.FALSE, f.harness.invoke(
                        TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), f.touch, up));
            } finally { up.recycle(); }
            assertEquals("An intercepted release must not reach the host touch handler", 0,
                    f.touch.nativeUpCalls);
            assertFalse(f.touch.discardCurrentEvent);
        }
    }

    @Test public void nativeLongPressRemainsAvailableWhenInterceptionIsDisabled() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.select();
            f.longPress(1, 1);
            assertEquals(1, f.press.nativeCalls);
            assertFalse(f.touch.discardCurrentEvent);
        }
    }

    @Test public void nativeLongPressInterceptionReservesUnassignedHoldsInEveryRegion() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true), true)) {
            f.select();
            Handler nativeHandler = new Handler(Looper.getMainLooper());
            f.touch.nativeDownAction = () -> nativeHandler.postDelayed(
                    f.touch.longPressRunnable, f.touch.mTimeInterval);
            for (int region = 0; region < FeedGestureToolsHook.REGION_IDS.length; region++) {
                int x = region % 3 * 100 + 50;
                int y = region / 3 * 200 + 100;
                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, x, y);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, x, y);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
                assertTrue("Unassigned " + FeedGestureToolsHook.REGION_IDS[region]
                        + " must not initialize a child or ancestor native recognizer",
                        f.nativeDispatchOrder.isEmpty());
                assertEquals(0, f.press.nativeCalls);
                assertEquals(1.25f, f.player.getSpeed(), 0f);
            }
        }
    }

    @Test public void nativeLongPressInterceptionStillHandsUnassignedTapsAndDragsBack() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
            assertTrue(f.nativeDispatchOrder.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 300);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);

            f.nativeDispatchOrder.clear();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
            assertTrue(f.nativeDispatchOrder.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_MOVE, 150, 200);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 200);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:2", "legacy:2", "video:2", "child:1", "legacy:1", "video:1"),
                    f.nativeDispatchOrder);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
            assertEquals(0, f.press.nativeCalls);
            assertEquals(1f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void disablingNativeLongPressInterceptionPreservesAncestorAndNativeHold() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            Handler nativeHandler = new Handler(Looper.getMainLooper());
            f.touch.nativeDownAction = () -> nativeHandler.postDelayed(
                    f.touch.longPressRunnable, f.touch.mTimeInterval);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0"), f.nativeDispatchOrder);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            assertEquals(1, f.press.nativeCalls);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 300);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);
            assertEquals(1, f.touch.nativeUpCalls);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals(1f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void actualLongPressCallbackRoutesEveryCellAndPreservesUnconfiguredNeighbor() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            for (int region = 0; region < 9; region++) {
                f.hook.setOptions(new LocalOptions(Map.of("long_press_"
                        + FeedGestureToolsHook.REGION_IDS[region], "toggle_components")));
                f.longPress(region % 3, region / 3);
                assertEquals(FeedGestureToolsHook.REGION_IDS[region], region + 1, f.componentToggles);
                int neighbor = (region + 1) % 9;
                f.longPress(neighbor % 3, neighbor / 3);
                assertEquals(region + 1, f.press.nativeCalls);
                assertEquals(region + 1, f.componentToggles);
            }
        }
    }

    @Test public void backgroundAndForeignLayoutKeepNativeLongPress() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.hook.onActivityResumed(f.activity);
            f.select();
            LongPress foreign = new LongPress(new TouchLayout(f.activity));
            f.harness.invoke(LongPress.class.getMethod("run"), foreign);
            assertEquals(1, foreign.nativeCalls);
            f.hook.onActivityPaused(f.activity);
            f.root.focused = false;
            f.longPress(1, 1);
            assertEquals(1, f.press.nativeCalls);
            assertEquals(0, f.componentToggles);
        }
    }

    @Test public void pausedHolderCannotBeRevivedByStaleCurrentHolderCallbacks() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_middle_center", "toggle_components"))) {
            f.select();
            f.harness.invoke(Holder.class.getMethod("pause"), f.holder);
            f.harness.invoke(Panel.class.getMethod("currentHolder"), f.panel);
            f.harness.invoke(Holder.class.getMethod("getAweme"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(1, 1);
            assertEquals(0, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            f.longPress(1, 1);
            assertEquals(1, f.componentToggles);
            assertEquals(1, f.press.nativeCalls);
        }
    }

    @Test public void doubleTapUsesTheSameForegroundAndGridAndNonePreservesNative() throws Throwable {
        try (Fixture f = new Fixture(Map.of("double_tap_bottom_left", "toggle_components"))) {
            f.select();
            f.doubleTap(0, 2);
            assertEquals(1, f.componentToggles);
            assertEquals(0, f.panel.nativeDoubleTaps);
            f.doubleTap(1, 2);
            assertEquals(1, f.panel.nativeDoubleTaps);
            f.hook.close();
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test public void bottomRightLongPressImmediatelyAppliesTheConfiguredNumericSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(0, f.press.nativeCalls);
            assertEquals(3f, f.player.getSpeed(), 0f);
            assertEquals(1, f.player.delegate.speedChanges);
            assertNull("The configured speed action must not open another selector",
                    ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void configuredLongPressConsumesTheHostReleaseAfterRunningItsAction() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 2);
            f.longPress(2, 2);

            assertEquals(3f, f.player.getSpeed(), 0f);
            assertTrue("A configured long press must consume the host release", f.touch.discardCurrentEvent);
            MotionEvent up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 250, 550, 0);
            try {
                assertEquals(Boolean.FALSE, f.harness.invoke(
                        TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), f.touch, up));
            } finally { up.recycle(); }
            assertEquals("The native host release must not run after a module action", 0,
                    f.touch.nativeUpCalls);
        }
    }

    @Test public void unsetNumericValueAppliesTheSharedDefaultSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(2f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void outOfRangeConfiguredValuesUseTheNearestSupportedPreset() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "8"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(4f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "0.1")));
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(0.75f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void manuallyRequestedSpeedWorksWhileAutomaticSpeedIsDisabled() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            f.select();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void longPressTogglesConfiguredSpeedAndNormalWithToastForBothAutoFlags() throws Throwable {
        for (boolean automatic : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "3", "auto_speed", automatic))) {
                f.select();
                f.longPress(2, 2);
                assertEquals(3f, f.player.getSpeed(), 0f);
                assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
                assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
                f.longPress(2, 2);
                assertEquals(1f, f.player.getSpeed(), 0f);
                assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
                assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
                f.longPress(2, 2);
                assertEquals(3f, f.player.getSpeed(), 0f);
                assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void heldSpeedGesturePreventsNativeDownFromSchedulingALateSpeedRestore() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false))) {
            f.player.delegate.speed = 1f;
            f.select();
            Handler nativeHandler = new Handler(Looper.getMainLooper());
            int[] nativeDownCalls = {0};
            f.touch.nativeDownAction = () -> {
                nativeDownCalls[0]++;
                nativeHandler.postDelayed(f.touch.longPressRunnable, f.touch.mTimeInterval);
                // Model teardown owned by DOWN, independent of the consumed long-press
                // runnable and UP. Its exact delay is a fixture assumption, not a host contract.
                nativeHandler.postDelayed(() -> f.player.delegate.setSpeed(1f),
                        f.touch.mTimeInterval + 400L);
            };

            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(500));
            assertEquals(2f, f.player.getSpeed(), 0f);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals("倍速锁定目标：2x", ShadowToast.getTextOfLatestToast());
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals("A confirmed toggle must survive teardown scheduled by native DOWN",
                    2f, f.player.getSpeed(), 0f);
            assertEquals("A claimed speed hold must not initialize the native gesture", 0,
                    nativeDownCalls[0]);

            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals(0, nativeDownCalls[0]);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void heldSpeedShortTapReplaysTheOriginalDownAndUpWithoutChangingSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false))) {
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            List<Float> nativeXs = new ArrayList<>();
            List<Float> nativeYs = new ArrayList<>();
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            int[] nativeDownCalls = {0};
            f.touch.nativeDownAction = () -> nativeDownCalls[0]++;
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                nativeXs.add(event.getRawX() - location[0]);
                nativeYs.add(event.getRawY() - location[1]);
                return false;
            });

            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 500);
            assertTrue("Reserve the candidate hold until it becomes a tap or long press",
                    nativeEvents.isEmpty());
            assertEquals(0, nativeDownCalls[0]);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchTouch(MotionEvent.ACTION_UP, 252, 502);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), nativeEvents);
            assertEquals(Arrays.asList(250f, 252f), nativeXs);
            assertEquals(Arrays.asList(500f, 502f), nativeYs);
            assertEquals(1, nativeDownCalls[0]);
            assertEquals(1, f.touch.nativeUpCalls);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(600));
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void heldSpeedMoveReplaysTheOriginalDownBeforeHandingBackTheDrag() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false))) {
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            List<Float> nativeYs = new ArrayList<>();
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            int[] nativeDownCalls = {0};
            f.touch.nativeDownAction = () -> nativeDownCalls[0]++;
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                nativeYs.add(event.getRawY() - location[1]);
                return false;
            });

            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 500);
            assertTrue(nativeEvents.isEmpty());
            assertEquals(0, nativeDownCalls[0]);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchTouch(MotionEvent.ACTION_MOVE, 250, 400);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), nativeEvents);
            assertEquals(Arrays.asList(500f, 400f), nativeYs);
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 390);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_UP), nativeEvents);
            assertEquals(Arrays.asList(500f, 400f, 390f), nativeYs);
            assertEquals(1, nativeDownCalls[0]);
            assertEquals(1, f.touch.nativeUpCalls);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(600));
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void nativeLongPressInterceptionDoesNotRequireAnAvailablePlayer() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true,
                "long_press_bottom_right", "speed"), true)) {
            f.panel.currentPlayer = null;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 500);
            assertTrue("Global native blocking must not depend on a usable speed player",
                    f.nativeDispatchOrder.isEmpty());
            assertEquals(0, f.press.nativeCalls);
            assertEquals("当前播放器不可用", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void heldSpeedGestureAlsoBlocksAncestorDetectorTeardown() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            assertEquals(2f, f.player.getSpeed(), 0f);
            assertEquals("倍速锁定目标：2x", ShadowToast.getTextOfLatestToast());
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals("An ancestor must not undo a child-owned speed toggle after confirmation",
                    2f, f.player.getSpeed(), 0f);
            assertTrue("Neither ancestor may initialize a detector or receive an orphan UP",
                    f.nativeDispatchOrder.isEmpty());
            assertTrue(f.nativeDetector.events.isEmpty());
            assertTrue(f.legacyNativeDetector.events.isEmpty());
            // A detector shared by multiple ancestors can receive the same terminal twice.
            MotionEvent repeatedUp = MotionEvent.obtain(f.nativeDownTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP, 250, 500, 0);
            try {
                f.dispatchDetector(f.nativeDetector, f.nativeContainer, repeatedUp);
                f.dispatchDetector(f.legacyNativeDetector, f.legacyNativeContainer, repeatedUp);
            } finally { repeatedUp.recycle(); }
            assertTrue("Repeated ancestor terminal events must remain discarded",
                    f.nativeDispatchOrder.isEmpty());
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void heldSpeedShortTapReplaysChildAndAncestorDownBeforeUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            long downAt = SystemClock.uptimeMillis();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            assertTrue(f.nativeDispatchOrder.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 252, 502);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);
            assertEquals(Arrays.asList(263f, 265f), f.legacyNativeDetector.xs);
            assertEquals(Arrays.asList(523f, 525f), f.legacyNativeDetector.ys);
            assertEquals(Arrays.asList(274f, 276f), f.nativeDetector.xs);
            assertEquals(Arrays.asList(540f, 542f), f.nativeDetector.ys);
            assertEquals(Arrays.asList(downAt, downAt + 100), f.nativeDetector.eventTimes);
            assertEquals(Arrays.asList(downAt, downAt + 100), f.legacyNativeDetector.eventTimes);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void heldSpeedMoveReplaysChildAndAncestorDownBeforeMove() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            long downAt = SystemClock.uptimeMillis();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            assertTrue(f.nativeDispatchOrder.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_MOVE, 250, 400);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:2", "legacy:2", "video:2"), f.nativeDispatchOrder);
            assertEquals(Arrays.asList(523f, 423f), f.legacyNativeDetector.ys);
            assertEquals(Arrays.asList(540f, 440f), f.nativeDetector.ys);
            assertEquals(Arrays.asList(downAt, downAt + 100), f.nativeDetector.eventTimes);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 390);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:2", "legacy:2", "video:2", "child:1", "legacy:1", "video:1"),
                    f.nativeDispatchOrder);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals(0, f.press.nativeCalls);
        }
    }

    @Test public void confirmedSpeedMoveDoesNotInitializeAncestorDetectors() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_MOVE, 250, 400);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 400);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals(2f, f.player.getSpeed(), 0f);
            assertTrue("Movement after claiming a hold must not replay any native DOWN",
                    f.nativeDispatchOrder.isEmpty());
        }
    }

    @Test public void lostSpeedUpDoesNotBlockTheNextAncestorLongPress() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            assertEquals(2f, f.player.getSpeed(), 0f);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3500));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
            assertEquals("A fresh DOWN after a lost UP must still toggle the existing speed off",
                    1f, f.player.getSpeed(), 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertTrue(f.nativeDispatchOrder.isEmpty());
        }
    }

    @Test public void changedSpeedConfigurationDoesNotSendAnOrphanAncestorUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.player.delegate.speed = 1f;
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "none")));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(550));
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 250, 500);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertTrue("Cancelling a withheld DOWN must also consume its eventual native UP",
                    f.nativeDispatchOrder.isEmpty());
        }
    }

    @Test public void unconfiguredRegionStillReachesChildAndAncestorDetectors() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "2", "auto_speed", false), true)) {
            f.select();
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 50, 100);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 50, 100);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);
            assertEquals(1.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void moduleOnlyModeStartsAtNormalAndKeepsItsPresetAcrossVideos() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                "long_press_bottom_right", "speed", "playback_speed", "3", "auto_speed", false))) {
            f.select();
            assertEquals("Module-only control must start at its own 1x instead of the host's 1.25x", 1f,
                    f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());

            f.holder.aid = "video-2";
            f.player.delegate.speed = 1.25f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(3f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals("Turning the preset off must select module 1x", 1f, f.player.getSpeed(), 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());

            Holder next = new Holder();
            next.aid = "video-3";
            next.w = f.root;
            next.A2 = f.holder.A2;
            PlayerManager nextPlayer = new PlayerManager(new FakeFeedWrapper(1.25f));
            f.panel.currentPlayer = nextPlayer;
            f.panel.current = next;
            f.harness.invoke(Holder.class.getMethod("selected"), next);
            ShadowLooper.idleMainLooper();
            assertEquals("Normal playback must remain module 1x after another player is selected", 1f,
                    nextPlayer.getSpeed(), 0f);
        }
    }

    @Test public void moduleOnlyModeRegistersNormalBeforeAnySelectionOrManualGesture() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                "long_press_bottom_right", "speed"))) {
            assertEquals("The module's normal choice must exist before the first holder callback", 1f,
                    GestureSpeedOverride.speedFor(f.player), 0f);
            f.select();
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals("The default enabled preset must be 2x", 2f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void moduleOnlyModeDoesNotUseHostReadbackAsItsSelectedTarget() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                "long_press_bottom_right", "speed", "playback_speed", "3"))) {
            FixedEngineReadbackPlayerManager player = new FixedEngineReadbackPlayerManager(1.7f);
            f.panel.currentPlayer = player;
            f.select();
            assertEquals(1f, player.lastRequested, 0f);
            assertEquals(1f, GestureSpeedOverride.speedFor(player), 0f);
            f.longPress(2, 2);
            assertEquals(3f, player.lastRequested, 0f);
            assertEquals(3f, GestureSpeedOverride.speedFor(player), 0f);
            f.longPress(2, 2);
            assertEquals(1f, player.lastRequested, 0f);
            assertEquals(1f, GestureSpeedOverride.speedFor(player), 0f);
            assertEquals("The independent observation is neither the target nor the submitted value", 1.7f,
                    player.engineReported, 0f);
        }
    }

    @Test public void moduleOnlyModeHandlesHotEnableAndPresetChanges() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            f.select();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                    "long_press_bottom_right", "speed", "playback_speed", "3")));
            ShadowLooper.idleMainLooper();
            assertEquals("Hot enabling must start from module normal playback", 1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                    "long_press_bottom_right", "speed", "playback_speed", "4")));
            ShadowLooper.idleMainLooper();
            assertEquals(4f, f.player.getSpeed(), 0f);
            f.holder.aid = "four-times-video";
            f.player.delegate.speed = 1.25f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals("A selected 4x preset must be inherited after the host resets a new video", 4f,
                    f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                    "long_press_bottom_right", "speed", "playback_speed", "0.75")));
            ShadowLooper.idleMainLooper();
            assertEquals("Changing a preset must not enable it while normal playback is selected", 1f,
                    f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(0.75f, f.player.getSpeed(), 0f);
            f.holder.aid = "next-video";
            f.player.delegate.speed = 1.25f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(0.75f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void hotEnablingModuleOnlyModeUpdatesTheExistingLegacyScopeToken() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            Field ownerField = FeedGestureToolsHook.class.getDeclaredField("speedOverride");
            ownerField.setAccessible(true);
            GestureSpeedOverride owner = (GestureSpeedOverride) ownerField.get(f.hook);
            assertNotNull(owner);
            GestureSpeedOverride.ScopeToken[] queued = {null};
            owner.apply(3f, () -> queued[0] = GestureSpeedOverride.captureScope());
            assertNotNull(queued[0]);
            assertEquals(Float.valueOf(3f), queued[0].desired());

            f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                    "long_press_bottom_right", "speed", "playback_speed", "3")));
            ShadowLooper.idleMainLooper();
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals(Float.valueOf(1f), GestureSpeedOverride.speedFor(f.player));
            assertEquals("An already queued SDK request must observe the new module normal choice",
                    Float.valueOf(1f), queued[0].desired());
            assertSame(owner, ownerField.get(f.hook));

            f.longPress(2, 2);
            assertEquals("Hot enabling must reset the old ON state before the next toggle", 3f,
                    f.player.getSpeed(), 0f);
            assertEquals(Float.valueOf(3f), GestureSpeedOverride.speedFor(f.player));
            assertEquals(Float.valueOf(3f), queued[0].desired());
        }
    }

    @Test public void disablingModuleOnlyModeReleasesItsExclusiveChoiceAndNativeHold() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false,
                "long_press_bottom_right", "speed", "playback_speed", "3"), true)) {
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, false,
                    LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false,
                    "long_press_bottom_right", "speed", "playback_speed", "3")));
            ShadowLooper.idleMainLooper();
            assertNull("Disabling the mode must release the owner created by that mode",
                    GestureSpeedOverride.speedFor(f.player));
            f.player.setSpeed(3.25f);
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals("An external nonpreset speed must remain independent after mode exit", 3.25f,
                    f.player.getSpeed(), 0f);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
            f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 300);
            assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                    "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);
        }
    }

    @Test public void moduleOnlyModeForcesNativeHoldInterceptionEvenWhenTheStoredSwitchIsOff() throws Throwable {
        Map<String, Object> values = Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false);
        assertTrue(new LocalOptions(values).interceptNativeLongPress());
        try (Fixture f = new Fixture(values, true)) {
            f.select();
            Handler nativeHandler = new Handler(Looper.getMainLooper());
            f.touch.nativeDownAction = () -> nativeHandler.postDelayed(
                    f.touch.longPressRunnable, f.touch.mTimeInterval);
            try {
                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(650));
                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 300);
                assertTrue(f.nativeDispatchOrder.isEmpty());
                assertEquals(0, f.press.nativeCalls);
                assertEquals(1f, f.player.getSpeed(), 0f);

                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_DOWN, 150, 300);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(100));
                f.dispatchNativeAncestorTouch(MotionEvent.ACTION_UP, 150, 300);
                assertEquals(Arrays.asList("child:0", "legacy:0", "video:0",
                        "child:1", "legacy:1", "video:1"), f.nativeDispatchOrder);
            } finally { nativeHandler.removeCallbacksAndMessages(null); }
        }
    }

    @Test public void moduleOnlyModeAcceptsEveryPresetAndAlwaysReturnsToItsOwnNormalChoice() throws Throwable {
        try (Fixture f = new Fixture(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                "long_press_bottom_right", "speed"))) {
            f.select();
            for (String preset : new String[]{"0.75", "1", "1.25", "1.5", "2", "3", "4"}) {
                f.hook.setOptions(new LocalOptions(Map.of(LocalOptions.MODULE_ONLY_SPEED, true,
                        "long_press_bottom_right", "speed", "playback_speed", preset)));
                ShadowLooper.idleMainLooper();
                assertEquals(1f, f.player.getSpeed(), 0f);
                f.longPress(2, 2);
                assertEquals(Float.parseFloat(preset), f.player.getSpeed(), 0f);
                assertEquals("倍速锁定目标：" + preset + "x", ShadowToast.getTextOfLatestToast());
                f.longPress(2, 2);
                assertEquals(1f, f.player.getSpeed(), 0f);
                assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void manualToggleStaysEnabledAcrossHolderSelectionUntilTheNextLongPress() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            f.harness.invoke(Holder.class.getMethod("pause"), f.holder);
            ShadowLooper.idleMainLooper();
            Holder next = new Holder();
            next.aid = "video-2";
            next.w = f.root;
            next.A2 = f.holder.A2;
            f.panel.current = next;
            f.harness.invoke(Holder.class.getMethod("selected"), next);
            ShadowLooper.idleMainLooper();
            assertEquals("Changing videos must not turn off the manually enabled speed",
                    3f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals("The next long press must turn off the same persistent toggle",
                    1f, f.player.getSpeed(), 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());

            Holder third = new Holder();
            third.aid = "video-3";
            third.w = f.root;
            third.A2 = f.holder.A2;
            PlayerManager thirdPlayer = new PlayerManager(new FakeFeedWrapper(3f));
            f.panel.currentPlayer = thirdPlayer;
            f.panel.current = third;
            f.harness.invoke(Holder.class.getMethod("selected"), third);
            ShadowLooper.idleMainLooper();
            assertEquals("The explicit 1x choice must also persist into a new player/video",
                    1f, thirdPlayer.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(3f, thirdPlayer.getSpeed(), 0f);
        }
    }

    @Test public void manualTogglePersistsWhenTheSameHolderReceivesAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", true))) {
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, f.player.getSpeed(), 0f);
            f.holder.aid = "video-2";
            f.player.delegate.speed = 1f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals("Reusing a holder for a new aid must reapply the enabled speed",
                    3f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void manualSpeedIsReappliedToAReplacementManagerWhenItsHolderResumes() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            f.select();
            f.longPress(2, 2);
            PlayerManager replacement = new PlayerManager(new FakeFeedWrapper(1.5f));
            f.panel.currentPlayer = replacement;
            f.holder.aid = "video-2";
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals("A resumed holder must apply the existing manual choice to its new engine",
                    3f, replacement.delegate.speed, 0f);

            // Model the host starting a new playback cycle before its holder-resume callback.
            // This fixture does not claim to hook nested manager/engine Java calls.
            replacement.delegate.speed = 1f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(3f, replacement.delegate.speed, 0f);
            f.longPress(2, 2);
            assertEquals(1f, replacement.delegate.speed, 0f);
        }
    }

    @Test public void unavailableReadbackCannotBlockTheIndependentToggle() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            UnreportedPlayerManager player = new UnreportedPlayerManager();
            f.panel.currentPlayer = player;
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, player.actualSpeed, 0f);
            assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
            f.longPress(2, 2);
            assertEquals(1f, player.actualSpeed, 0f);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void implausibleThirtyFpsReadbackMustNotBecomeTheRestorationSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            FrameRateReadbackPlayerManager player = new FrameRateReadbackPlayerManager();
            f.panel.currentPlayer = player;
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, player.actualSpeed, 0f);
            f.touchEvent(MotionEvent.ACTION_UP, 2, 2);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1100));
            assertEquals("Consuming the configured gesture release must preserve its toggle",
                    3f, player.actualSpeed, 0f);
            assertEquals("An invalid readback must not replace feedback for the chosen target",
                    "倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());

            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals("Pausing must keep the manual choice until the module is closed",
                    3f, player.actualSpeed, 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals("A 30.0 readback matching the log cannot be restored as playback speed",
                    1f, player.actualSpeed, 0f);
            assertFalse("A value from an incompatible getter must never be sent to the speed setter",
                    player.writes.contains(30f));
        }
    }

    @Test public void doubleTapUsesTheConfiguredSpeedWithoutASelector() throws Throwable {
        try (Fixture f = new Fixture(Map.of("double_tap_bottom_right", "speed", "playback_speed", "3"))) {
            f.select();
            f.doubleTap(2, 2);
            assertEquals(0, f.panel.nativeDoubleTaps);
            assertEquals(3f, f.player.getSpeed(), 0f);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    @Test public void directWrapperAlsoUsesTheSamePlayerInterfaces() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.panel.currentPlayer = f.player.delegate;
            f.select();
            f.longPress(2, 2);
            assertEquals(2f, f.player.delegate.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(2f, f.player.delegate.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.delegate.getSpeed(), 0f);
        }
    }

    @Test public void requestingAgainAfterAnExternalChangeRestoresThatNewBaseline() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.player.setSpeed(3.25f);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "1.5")));
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(3.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void leavingTheForegroundKeepsManualSpeedUntilHooksClose() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(2f, f.player.getSpeed(), 0f);
            f.hook.onActivityResumed(f.activity);
            f.select();
            assertEquals(2f, f.player.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void pausingTheSelectedHolderKeepsManualSpeedUntilTheNextLongPress() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.harness.invoke(Holder.class.getMethod("pause"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(2f, f.player.getSpeed(), 0f);
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(2f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void closingHooksRestoresOwnedSpeedAndUnregistersCallbacks() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test public void restoringGestureSpeedPreservesAnExternalSpeedChange() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.player.setSpeed(3.25f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(3.25f, f.player.getSpeed(), 0f);
            assertEquals(2, f.player.delegate.speedChanges);
        }
    }

    @Test public void aReplacedCurrentHolderCannotApplyTheSpeedToAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.panel.current = new Holder();
            f.longPress(2, 2);
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.player.delegate.speedChanges);
        }
    }

    @Test public void speedUsesTheCurrentPlayerAfterManagerReplacement() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            PlayerManager replacement = new PlayerManager(new FakeFeedWrapper(1.5f));
            f.panel.currentPlayer = replacement;
            f.longPress(2, 2);
            assertEquals(1.25f, f.player.getSpeed(), 0f);
            assertEquals(0, f.player.delegate.speedChanges);
            assertEquals(2f, replacement.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(2f, replacement.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, replacement.getSpeed(), 0f);
        }
    }

    @Test public void liveSpeedOptionChangesApplyToThePersistentToggleAndItsNextVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed"))) {
            f.select();
            f.longPress(2, 2);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "1.5")));
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.holder.aid = "video-2";
            f.player.delegate.speed = 1f;
            f.harness.invoke(Holder.class.getMethod("resume"), f.holder);
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1f, f.player.getSpeed(), 0f);
            f.longPress(2, 2);
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1.5f, f.player.getSpeed(), 0f);
            f.hook.close();
            ShadowLooper.idleMainLooper();
            assertEquals(1.25f, f.player.getSpeed(), 0f);
        }
    }

    @Test public void delayedSpeedReadbackDoesNotShowAFalseRejectionOrLoseRestoreOwnership() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(false);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            assertEquals(3f, delayed.actualSpeed, 0f);
            assertEquals(1.25f, delayed.getSpeed(), 0f);
            assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
            assertNoSpeedRejection();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(399));
            assertEquals("Target feedback must remain independent of delayed playback confirmation",
                    "倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
            assertEquals(1.25f, delayed.getSpeed(), 0f);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(101));
            assertEquals(3f, delayed.getSpeed(), 0f);
            assertEquals("倍速锁定目标：3x", ShadowToast.getTextOfLatestToast());
            assertNoSpeedRejection();
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(3f, delayed.actualSpeed, 0f);
            f.hook.close();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
        }
    }

    @Test public void unavailableReadbackKeepsTheChosenTargetFeedbackUntilTheNextToggle() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                "playback_speed", "3", "auto_speed", false))) {
            UnreportedPlayerManager player = new UnreportedPlayerManager();
            f.panel.currentPlayer = player;
            f.select();
            f.longPress(2, 2);
            String chosenTargetFeedback = ShadowToast.getTextOfLatestToast();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
            String feedbackAfterReadback = ShadowToast.getTextOfLatestToast();
            assertEquals("Readback failure must not independently toggle the accepted speed",
                    3f, player.actualSpeed, 0f);
            f.longPress(2, 2);
            assertEquals(1f, player.actualSpeed, 0f);
            assertEquals("Readback uncertainty must not replace feedback for the chosen target",
                    chosenTargetFeedback, feedbackAfterReadback);
            assertEquals("倍速锁定目标：3x", chosenTargetFeedback);
            assertFalse("Retrying the same long press would turn the existing choice off",
                    feedbackAfterReadback.contains("重试"));
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            assertEquals(1f, player.actualSpeed, 0f);
        }
    }

    @Test public void targetFeedbackPreservesMismatchUnavailableAndExactReadbackDiagnostics() throws Throwable {
        IPlayerManager[] players = {new FixedEngineReadbackPlayerManager(1.7f),
                new UnreportedPlayerManager(), new FixedEngineReadbackPlayerManager(2f)};
        String[] reasons = {"readback_mismatch", "readback_unavailable", "speed_getter_readback"};
        String[] outcomes = {"unconfirmed", "unconfirmed", "success"};
        String[] evidence = {"requested=2.0;reported=1.7", "setter_returned=true;attempts=3",
                "requested=2.0;reported=2.0"};
        for (int i = 0; i < players.length; i++) {
            try (SpeedReadbackLogs logs = new SpeedReadbackLogs();
                 Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed",
                         "playback_speed", "2", "auto_speed", false))) {
                IPlayerManager player = players[i];
                f.panel.currentPlayer = player;
                f.select();
                int previousToastCount = ShadowToast.shownToastCount();
                f.longPress(2, 2);
                assertEquals("倍速锁定目标：2x", ShadowToast.getTextOfLatestToast());
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(1000));
                assertEquals("倍速锁定目标：2x", ShadowToast.getTextOfLatestToast());
                assertEquals("Readback must not display a second status toast", previousToastCount + 1,
                        ShadowToast.shownToastCount());
                assertEquals("The label describes the selected target, independently of engine feedback", 2f,
                        GestureSpeedOverride.speedFor(player), 0f);
                if (player instanceof FixedEngineReadbackPlayerManager) {
                    FixedEngineReadbackPlayerManager fixed = (FixedEngineReadbackPlayerManager) player;
                    assertEquals(2f, fixed.lastRequested, 0f);
                    assertEquals("The test must not replace the engine observation with the sent value",
                            fixed.engineReported, fixed.getSpeed(), 0f);
                }
                assertTrue("The original structured readback verdict must remain available: " + reasons[i],
                        logs.hasOutcome(reasons[i], outcomes[i], evidence[i]));
                if (!"success".equals(outcomes[i])) {
                    assertFalse("A selected 2x target must not turn an uncertain engine readback into success",
                            logs.hasOutcome("speed_getter_readback", "success", null));
                }
            }
        }
    }

    @Test public void repeatedRequestsBeforeDelayedReadbackKeepTheInitialRestoreValue() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(false);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            f.hook.setOptions(new LocalOptions(Map.of("long_press_bottom_right", "speed",
                    "playback_speed", "4")));
            f.longPress(2, 2);
            assertEquals("已选择正常播放：1x", ShadowToast.getTextOfLatestToast());
            f.longPress(2, 2);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(4f, delayed.actualSpeed, 0f);
            assertNoSpeedRejection();
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(4f, delayed.actualSpeed, 0f);
            f.hook.close();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
        }
    }

    @Test public void pausingBeforeAnAsyncSetterFinishesKeepsThePendingManualChoice() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(true);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.idleMainLooper();
            assertEquals(1, delayed.speedChanges);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(3f, delayed.actualSpeed, 0f);
            f.hook.close();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            assertNoSpeedRejection();
        }
    }

    @Test public void closingBeforeAnAsyncSetterFinishesCancelsItsPendingSpeed() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_bottom_right", "speed", "playback_speed", "3"))) {
            DelayedPlayerManager delayed = new DelayedPlayerManager(true);
            f.panel.currentPlayer = delayed;
            f.select();
            f.longPress(2, 2);
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            f.hook.close();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1.25f, delayed.actualSpeed, 0f);
            assertNoSpeedRejection();
        }
    }

    private static void assertNoSpeedRejection() {
        assertFalse("A successful setter with delayed readback must not be reported as rejected",
                "当前播放器没有接受该倍速".equals(ShadowToast.getTextOfLatestToast()));
    }

    private static final class SpeedReadbackLogs implements AutoCloseable {
        final List<String> records = new CopyOnWriteArrayList<>();
        final boolean previousDebug;
        final DouAiLog.Sink previousSink;

        SpeedReadbackLogs() throws ReflectiveOperationException {
            assertTrue(DouAiLog.awaitIdle(3000));
            Field framework = DouAiLog.class.getDeclaredField("framework");
            framework.setAccessible(true);
            previousSink = (DouAiLog.Sink) framework.get(null);
            previousDebug = DebugLog.isEnabled();
            DebugLog.setEnabled(true);
            DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        }

        boolean hasOutcome(String reason, String outcome, String evidence) throws org.json.JSONException {
            assertTrue(DouAiLog.awaitIdle(3000));
            for (String line : records) {
                org.json.JSONObject record = new org.json.JSONObject(line);
                if (reason.equals(record.optString("reason_code")) && outcome.equals(record.optString("outcome"))
                        && (evidence == null || record.optString("evidence").contains(evidence))) return true;
            }
            return false;
        }

        @Override public void close() {
            try { assertTrue(DouAiLog.awaitIdle(3000)); }
            finally {
                DouAiLog.setFrameworkSink(previousSink);
                DebugLog.setEnabled(previousDebug);
            }
        }
    }

    @Test public void defaultsPreserveNativeGesturesAndHideToolbar() {
        LocalOptions options = new LocalOptions(Collections.emptyMap());
        assertFalse(options.moduleOnlySpeedControl());
        assertFalse(options.enabled("toolbar_enabled"));
        assertEquals("none", select(options, "long_press", 150, 300));
        assertEquals("none", select(options, "double_tap", 150, 300));
    }

    @Test public void nineRegionsUseBothCoordinatesRelativeToTheVideoBounds() {
        Map<String, Object> values = new java.util.HashMap<>();
        for (int index = 0; index < 9; index++) {
            values.put("long_press_" + FeedGestureToolsHook.REGION_IDS[index],
                    FeedGestureToolsHook.ACTION_IDS[index % FeedGestureToolsHook.ACTION_IDS.length]);
        }
        LocalOptions options = new LocalOptions(values);
        for (int index = 0; index < 9; index++) {
            float x = 40 + 50 + index % 3 * 100;
            float y = 80 + 100 + index / 3 * 200;
            assertEquals(FeedGestureToolsHook.REGION_IDS[index],
                    FeedGestureToolsHook.ACTION_IDS[index % FeedGestureToolsHook.ACTION_IDS.length],
                    FeedGestureToolsHook.selectedAction(options, "long_press", x, y, 40, 80, 300, 600));
        }
    }

    @Test public void legacyColumnsApplyToEachRowUntilThatRegionHasAnExplicitAction() {
        LocalOptions options = new LocalOptions(Map.of("long_press_left", "copy_text",
                "long_press_center", "open_comments", "long_press_right", "save_media",
                "long_press_middle_left", "none", "long_press_bottom_right", "speed"));
        for (int row = 0; row < 3; row++) {
            int y = row * 200 + 100;
            assertEquals(row == 1 ? "none" : "copy_text", select(options, "long_press", 50, y));
            assertEquals("open_comments", select(options, "long_press", 150, y));
            assertEquals(row == 2 ? "speed" : "save_media", select(options, "long_press", 250, y));
        }
    }

    @Test public void regionOverrideCanPreserveNativeActionWithoutChangingNeighborRegions() {
        LocalOptions options = new LocalOptions(Map.of("double_tap_action", "menu",
                "double_tap_top_left", "none", "double_tap_middle_right", "speed"));
        assertEquals("none", select(options, "double_tap", 10, 10));
        assertEquals("menu", select(options, "double_tap", 10, 300));
        assertEquals("menu", select(options, "double_tap", 250, 10));
        assertEquals("speed", select(options, "double_tap", 250, 300));
    }

    @Test public void cellBoundariesRouteToTheFollowingColumnAndRow() {
        LocalOptions options = new LocalOptions(Map.of("double_tap_top_left", "menu",
                "double_tap_top_center", "copy_text", "double_tap_middle_center", "save_media",
                "double_tap_middle_right", "speed", "double_tap_bottom_right", "open_comments"));
        assertEquals("menu", select(options, "double_tap", 0, 0));
        assertEquals("copy_text", select(options, "double_tap", 100, 199));
        assertEquals("save_media", select(options, "double_tap", 100, 200));
        assertEquals("speed", select(options, "double_tap", 200, 200));
        assertEquals("open_comments", select(options, "double_tap", 299, 599));
    }

    @Test public void unknownActionAndInvalidCoordinateCannotReplaceNativeGesture() {
        LocalOptions options = new LocalOptions(Map.of("long_press_action", "unsupported"));
        assertEquals("none", select(options, "long_press", 10, 10));
        options = new LocalOptions(Map.of("long_press_action", "copy_text"));
        assertEquals("none", select(options, "long_press", Float.NaN, 10));
        assertEquals("none", select(options, "long_press", 10, Float.POSITIVE_INFINITY));
        assertEquals("none", select(options, "long_press", -1, 10));
        assertEquals("none", select(options, "long_press", 10, -1));
        assertEquals("none", select(options, "long_press", 300, 10));
        assertEquals("none", select(options, "long_press", 10, 600));
        assertEquals("none", FeedGestureToolsHook.selectedAction(options, "long_press", 0, 0, 0, 0, 0, 600));
        assertEquals("none", FeedGestureToolsHook.selectedAction(options, "long_press", 0, 0, 0, 0, 300, 0));
    }

    @Test public void viewFinderIsAvailableInLongPressAndDoubleTapActionChoices() {
        assertEquals("view_finder", FeedGestureToolsHook.ACTION_IDS[
                FeedGestureToolsHook.ACTION_IDS.length - 1]);
        assertEquals("视图查找", FeedGestureToolsHook.ACTION_LABELS[
                FeedGestureToolsHook.ACTION_LABELS.length - 1]);
        LocalOptions options = new LocalOptions(Map.of(
                "long_press_bottom_right", "view_finder",
                "double_tap_top_left", "view_finder"));
        assertEquals("view_finder", select(options, "long_press", 299, 599));
        assertEquals("view_finder", select(options, "double_tap", 0, 0));
    }

    @Test public void topRightViewFinderWinsOverBottomRightSpeedWithMultipleTouchLayers() throws Throwable {
        Map<String, Object> values = new HashMap<>();
        values.put("long_press_top_right", "view_finder");
        values.put("long_press_bottom_right", "speed");
        values.put("playback_speed", "3");
        try (Fixture f = new Fixture(values)) {
            f.root.addView(new TouchLayout(f.activity), new FrameLayout.LayoutParams(300, 600));
            f.select();
            f.longPress(2, 0);

            assertEquals("Top-right finder must consume the host long-press runnable", 0,
                    f.press.nativeCalls);
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertEquals("Top-right must not execute bottom-right speed", 0,
                    f.player.delegate.speedChanges);
            assertTrue("Finder must discard the pending UP event so paused video is not tapped",
                    f.touch.discardCurrentEvent);
            MotionEvent up = MotionEvent.obtain(0, 200, MotionEvent.ACTION_UP, 250, 50, 0);
            try {
                assertEquals(Boolean.FALSE, f.harness.invoke(
                        TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), f.touch, up));
            } finally { up.recycle(); }
            assertEquals("A view-finder release must not reach the host touch handler", 0,
                    f.touch.nativeUpCalls);
            assertFalse(f.touch.discardCurrentEvent);

            f.longPress(2, 2);
            assertTrue("Bottom-right must retain its configured speed action",
                    f.player.delegate.speedChanges > 0);
        }
    }

    @Test public void finderSuppressesResumeAndPlayWhenTheCurrentVideoWasPaused() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.longPress(2, 0);
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertNull(f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player));
            assertNull(f.harness.invoke(PlayerManager.class.getMethod("play"), f.player));
            assertEquals(0, f.player.delegate.resumeCalls);
            assertEquals(0, f.player.delegate.playCalls);
            assertFalse(f.player.delegate.playing);

            ViewFinderOverlay overlay = (ViewFinderOverlay) f.activity.getWindow().getDecorView()
                    .findViewWithTag("douai.view_finder");
            overlay.close();
            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertTrue(f.player.delegate.playing);
        }
    }

    @Test public void finderPreservesPausedPlayerWhenTheEventStateHasNotCaughtUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.hook.setPausedStateProvider(ignored -> false);
            f.select();
            f.longPress(2, 0);

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertFalse("A stale playing event must not override a paused current player",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderGestureBlocksHostResumeBeforeLongPressCallback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.hook.setPausedStateProvider(ignored -> !f.player.delegate.playing);
            f.select();
            f.touch.nativeDownAction = () -> {
                try {
                    f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player);
                } catch (Throwable failure) { throw new AssertionError(failure); }
            };
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }
            f.longPress(2, 0);

            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertFalse("The DOWN listener must not resume a paused finder gesture",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderRegionShortTapStillResumesVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.nativeDownAction = () -> {
                try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            assertFalse(f.player.delegate.playing);
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);

            assertTrue("An ordinary tap keeps the host playback action", f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderSnapshotsPauseBeforeDispatchTouchListener() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                    catch (Throwable failure) { throw new AssertionError(failure); }
                }
                return false;
            });
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }
            f.longPress(2, 0);
            assertFalse(f.player.delegate.playing);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pausedFinderDispatchPreservesPlayControlBeforeAnyHostTouchListener() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            View playControl = new View(f.activity);
            f.root.addView(playControl, new FrameLayout.LayoutParams(50, 50));
            playControl.setVisibility(View.VISIBLE);
            f.touch.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) playControl.setVisibility(View.GONE);
                return false;
            });
            int[] location = new int[2];
            f.touch.getLocationOnScreen(location);
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN,
                    location[0] + 250, location[1] + 100, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class),
                        f.touch, down);
            } finally { down.recycle(); }

            assertEquals("A finder press must preserve the original paused UI", View.VISIBLE,
                    playControl.getVisibility());
            f.longPress(2, 0);
            assertEquals(View.VISIBLE, playControl.getVisibility());
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void restoredPauseWaitsForPlayControlLayoutBeforeFinderSnapshot() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            View playControl = new View(f.activity);
            f.root.addView(playControl, new FrameLayout.LayoutParams(50, 50));
            playControl.setVisibility(View.GONE);
            f.player.delegate.pauseUiAction = () -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
                playControl.setVisibility(View.VISIBLE);
                playControl.layout(100, 200, 150, 250);
            }, 16);
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);

            assertNull("The picker must wait for pause controls to render",
                    f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            assertFalse(ViewFinderOverlay.screenBounds(playControl).isEmpty());
        }
    }

    @Test public void heldFinderGestureOpensFromTimerWithoutDeliveringHostDownOrUp() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNotNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            assertEquals(0, f.press.nativeCalls);
            assertEquals(0, f.player.delegate.resumeCalls);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void heldFinderShortTapReplaysTheOriginalTouchStream() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.resumeOnDown();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            assertFalse(f.player.delegate.playing);
            f.dispatchTouch(MotionEvent.ACTION_UP, 250, 100);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), nativeEvents);
            assertTrue(f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void heldFinderMoveHandsTheTouchStreamBackAndCancelDiscardsIt() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            List<Integer> nativeEvents = new ArrayList<>();
            f.touch.setOnTouchListener((view, event) -> {
                nativeEvents.add(event.getActionMasked());
                return false;
            });
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            f.dispatchTouch(MotionEvent.ACTION_MOVE, 250, 200);
            assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), nativeEvents);
            f.dispatchTouch(MotionEvent.ACTION_CANCEL, 250, 200);
            nativeEvents.clear();
            f.dispatchTouch(MotionEvent.ACTION_DOWN, 250, 100);
            f.dispatchTouch(MotionEvent.ACTION_CANCEL, 250, 100);
            assertTrue(nativeEvents.isEmpty());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(510));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderPreservesNativePlayControlHideAndCancelsStaleDeferredOpening() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.panel.playControl = new View(f.activity);
            f.root.addView(f.panel.playControl, new FrameLayout.LayoutParams(50, 50));
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.harness.invoke(Panel.class.getMethod("hidePlayControl"), f.panel);
            assertEquals(0, f.panel.hidePlayControlCalls);
            assertEquals(View.VISIBLE, f.panel.playControl.getVisibility());
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);
            assertEquals(1, f.panel.showPlayControlCalls);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
            f.harness.invoke(Panel.class.getMethod("hidePlayControl"), f.panel);
            assertEquals(1, f.panel.hidePlayControlCalls);
        }
    }

    @Test public void pauseLayoutWaitDoesNotOpenOnAReplacementPlayer() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.harness.invoke(LongPress.class.getMethod("run"), f.press);
            f.panel.currentPlayer = new PlayerManager(new FakeFeedWrapper(1f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));
        }
    }

    @Test public void finderGuardReleasesWhenHolderIsReusedForAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.longPress(2, 0);
            f.holder.aid = "another-video";

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertTrue(f.player.delegate.playing);
            assertEquals(1, f.player.delegate.resumeCalls);
        }
    }

    @Test public void pendingFinderBlocksNativePanelPlaybackThatBypassesPlayerManager() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(300));
            f.harness.invokeOrProceed(Panel.class.getMethod("togglePlayback", Aweme.class,
                    boolean.class, boolean.class), f.panel, f.holder.getAweme(), true, true);
            f.longPress(2, 0);
            assertFalse("The native panel path must not resume before the finder callback",
                    f.player.delegate.playing);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void finderRestoresPauseIfAnEarlierNativePlaybackPathWasNotIntercepted() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.longPress(2, 0);
            assertFalse("The DOWN pause snapshot must restore the original state", f.player.delegate.playing);
            assertEquals(1, f.player.delegate.pauseCalls);
        }
    }

    @Test public void pendingFinderProtectionExpiresWithoutTouchCompletion() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.resumeOnDown();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3100));
            assertEquals(0, f.player.delegate.resumeCalls);

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
            assertTrue(f.player.delegate.playing);
        }
    }

    @Test public void failedPauseRestorationDoesNotLeavePlaybackGuardActive() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.player.delegate.playing = true;
            f.player.delegate.failPause = true;
            f.longPress(2, 0);
            assertNull(f.activity.getWindow().getDecorView().findViewWithTag("douai.view_finder"));

            f.harness.invoke(PlayerManager.class.getMethod("resumePlay"), f.player);
            assertEquals(1, f.player.delegate.resumeCalls);
        }
    }

    @Test public void multiPointerCancelDoesNotReplayDeferredPlayback() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.resumeOnDown();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = 250;
                coordinates[i].y = 100;
            }
            MotionEvent cancel = MotionEvent.obtain(0, 100, MotionEvent.ACTION_CANCEL, 2,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0);
            try {
                f.harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class),
                        f.touch, cancel);
            } finally { cancel.recycle(); }
            assertEquals(0, f.player.delegate.resumeCalls);
            assertFalse(f.player.delegate.playing);
        }
    }

    @Test public void nativePanelShortTapStillResumesAndPlayingFinderDoesNotPause() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.harness.invoke(Panel.class.getMethod("togglePlayback", Aweme.class,
                    boolean.class, boolean.class), f.panel, f.holder.getAweme(), true, true);
            assertFalse(f.player.delegate.playing);
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);
            assertTrue(f.player.delegate.playing);

            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.longPress(2, 0);
            assertTrue("An originally playing video keeps playing during inspection", f.player.delegate.playing);
            assertEquals(0, f.player.delegate.pauseCalls);
        }
    }

    @Test public void cancelledFinderGestureDoesNotReplayPlaybackOnAnotherVideo() throws Throwable {
        try (Fixture f = new Fixture(Map.of("long_press_top_right", "view_finder"))) {
            f.player.delegate.playing = false;
            f.select();
            f.touch.nativeDownAction = () -> {
                try { f.harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), f.player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.holder.aid = "next-video";
            f.touchEvent(MotionEvent.ACTION_UP, 2, 0);
            assertEquals(0, f.player.delegate.resumeCalls);
            f.touchEvent(MotionEvent.ACTION_DOWN, 2, 0);
            f.touchEvent(MotionEvent.ACTION_CANCEL, 2, 0);
            assertEquals(0, f.player.delegate.resumeCalls);
        }
    }

    private static String select(LocalOptions options, String prefix, float x, float y) {
        return FeedGestureToolsHook.selectedAction(options, prefix, x, y, 0, 0, 300, 600);
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FeedRoot root = new FeedRoot(activity);
        final TouchLayout touch = new TouchLayout(activity);
        final LongPress press = new LongPress(touch);
        final Panel panel = new Panel();
        final PlayerManager player = (PlayerManager) panel.currentPlayer;
        final Holder holder = new Holder();
        final Harness harness = new Harness();
        final FeedGestureToolsHook hook;
        final List<String> nativeDispatchOrder = new ArrayList<>();
        final NativeDetector nativeDetector;
        final NativeDetector legacyNativeDetector;
        final NativeContainer nativeContainer;
        final LegacyNativeContainer legacyNativeContainer;
        long nativeDownTime;
        int componentToggles;

        Fixture(Map<String, ?> values) throws Exception {
            this(values, false);
        }

        Fixture(Map<String, ?> values, boolean withNativeAncestors) throws Exception {
            touch.longPressRunnable = () -> {
                try { harness.invoke(LongPress.class.getMethod("run"), press); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            if (withNativeAncestors) {
                nativeDetector = new NativeDetector("video", nativeDispatchOrder,
                        () -> player.delegate.setSpeed(1f));
                legacyNativeDetector = new NativeDetector("legacy", nativeDispatchOrder,
                        () -> player.delegate.setSpeed(1f));
                nativeContainer = new NativeContainer(activity, nativeDetector);
                legacyNativeContainer = new LegacyNativeContainer(activity, legacyNativeDetector);
                root.addView(nativeContainer, new FrameLayout.LayoutParams(300, 600));
                FrameLayout.LayoutParams legacyParams = new FrameLayout.LayoutParams(300, 600);
                legacyParams.leftMargin = 11;
                legacyParams.topMargin = 17;
                nativeContainer.addView(legacyNativeContainer, legacyParams);
                FrameLayout.LayoutParams touchParams = new FrameLayout.LayoutParams(300, 600);
                touchParams.leftMargin = 13;
                touchParams.topMargin = 23;
                legacyNativeContainer.addView(touch, touchParams);
                touch.setOnTouchListener((view, event) -> {
                    nativeDispatchOrder.add("child:" + event.getActionMasked());
                    return false;
                });
            } else {
                nativeDetector = null;
                legacyNativeDetector = null;
                nativeContainer = null;
                legacyNativeContainer = null;
                root.addView(touch, new FrameLayout.LayoutParams(300, 600));
            }
            activity.setContentView(root);
            root.layout(0, 0, 300, 600);
            if (withNativeAncestors) {
                nativeContainer.layout(0, 0, 300, 600);
                legacyNativeContainer.layout(11, 17, 311, 617);
                touch.layout(13, 23, 313, 623);
            } else touch.layout(0, 0, 300, 600);
            holder.w = root;
            holder.A2 = new ItemParams();
            holder.A2.mFeedContext = panel;
            panel.current = holder;
            hook = FeedGestureToolsHook.install(harness.framework, report(), getClass().getClassLoader(),
                    activity, new LocalOptions(values), ignored -> { });
            hook.setComponentVisibility(shown -> componentToggles++, () -> false);
            assertTrue(root.isAttachedToWindow());
            assertTrue(root.isShown());
        }

        void select() throws Throwable {
            harness.invoke(Holder.class.getMethod("selected"), holder);
            ShadowLooper.idleMainLooper();
        }

        void longPress(int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            touch.lastRawX = location[0] + column * 100 + 50;
            touch.lastRawY = location[1] + row * 200 + 100;
            harness.invoke(LongPress.class.getMethod("run"), press);
            ShadowLooper.idleMainLooper();
            if (player.delegate.pauseCalls > 0) {
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(192));
            }
        }

        void touchEvent(int action, int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, action,
                    location[0] + column * 100 + 50, location[1] + row * 200 + 100, 0);
            try {
                harness.invoke(TouchLayout.class.getMethod("onTouchEvent", MotionEvent.class), touch, event);
            } finally { event.recycle(); }
        }

        void resumeOnDown() {
            touch.nativeDownAction = () -> {
                try { harness.invokeOrProceed(PlayerManager.class.getMethod("resumePlay"), player); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
        }

        void dispatchTouch(int action, int x, int y) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, action, location[0] + x, location[1] + y, 0);
            try {
                harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class), touch, event);
            } finally { event.recycle(); }
        }

        void dispatchNativeAncestorTouch(int action, int x, int y) throws Throwable {
            long eventTime = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) nativeDownTime = eventTime;
            int[] touchLocation = new int[2];
            touch.getLocationOnScreen(touchLocation);
            MotionEvent screenEvent = MotionEvent.obtain(nativeDownTime, eventTime, action,
                    touchLocation[0] + x, touchLocation[1] + y, 0);
            try {
                // Model both ancestors' proven super(child) -> detector order explicitly.
                // Plain Java calls do not run hooks in this harness, so dispatch every seam.
                MotionEvent childEvent = relativeEvent(screenEvent, touch);
                try {
                    harness.invoke(TouchLayout.class.getMethod("dispatchTouchEvent", MotionEvent.class),
                            touch, childEvent);
                } finally { childEvent.recycle(); }
                dispatchDetector(legacyNativeDetector, legacyNativeContainer, screenEvent);
                dispatchDetector(nativeDetector, nativeContainer, screenEvent);
            } finally { screenEvent.recycle(); }
        }

        void dispatchDetector(NativeDetector detector, View owner, MotionEvent screenEvent) throws Throwable {
            MotionEvent event = relativeEvent(screenEvent, owner);
            try {
                harness.invokeOrProceed(NativeDetector.class.getMethod("input", MotionEvent.class), detector, event);
            } finally { event.recycle(); }
        }

        MotionEvent relativeEvent(MotionEvent screenEvent, View owner) {
            int[] location = new int[2];
            owner.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(screenEvent);
            event.offsetLocation(-location[0], -location[1]);
            return event;
        }

        void doubleTap(int column, int row) throws Throwable {
            int[] location = new int[2];
            touch.getLocationOnScreen(location);
            MotionEvent event = MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP,
                    location[0] + column * 100 + 50, location[1] + row * 200 + 100, 0);
            try { harness.invoke(Panel.class.getMethod("doubleTap", MotionEvent.class), panel, event); }
            finally { event.recycle(); }
            ShadowLooper.idleMainLooper();
        }

        @Override public void close() {
            if (nativeDetector != null) nativeDetector.clear();
            if (legacyNativeDetector != null) legacyNativeDetector.clear();
            hook.close();
            ShadowLooper.idleMainLooper();
            activity.finish();
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> results = new ArrayList<>();
        add(results, "feed_holder_page_selected", Holder.class.getMethod("selected"));
        add(results, "feed_holder_resume", Holder.class.getMethod("resume"));
        add(results, "feed_holder_pause", Holder.class.getMethod("pause"));
        add(results, "feed_aweme_accessor", Holder.class.getMethod("getAweme"));
        addType(results, "feed_holder_main_root", Holder.class);
        addType(results, "adv_gesture_item_params", ItemParams.class);
        addType(results, "adv_gesture_touch_layout", TouchLayout.class);
        add(results, "adv_gesture_long_press", LongPress.class.getMethod("run"));
        add(results, "adv_gesture_native_touch_detector", NativeDetector.class.getMethod("input", MotionEvent.class));
        add(results, "adv_gesture_video_container_detector", NativeContainer.class.getMethod("getDetector"));
        add(results, "adv_gesture_legacy_container_detector", LegacyNativeContainer.class.getMethod("getDetector"));
        add(results, "adv_ui_double_tap", Panel.class.getMethod("doubleTap", MotionEvent.class));
        add(results, "auto_slide_current_holder", Panel.class.getMethod("currentHolder"));
        add(results, "adv_gesture_panel_player", Panel.class.getMethod("player"));
        add(results, "adv_player_set_speed", FakeFeedWrapper.class.getMethod("setSpeed", float.class));
        add(results, "adv_player_get_speed", FakeFeedWrapper.class.getMethod("getSpeed"));
        add(results, "adv_gesture_player_set_speed", IPlayerManager.class.getMethod("setSpeed", float.class));
        add(results, "adv_gesture_player_get_speed", IPlayerInfo.class.getMethod("getSpeed"));
        add(results, "adv_gesture_player_is_playing", IPlayerInfo.class.getMethod("isPlaying"));
        add(results, "adv_gesture_player_resume", IPlayerManager.class.getMethod("resumePlay"));
        add(results, "adv_gesture_player_play", IPlayerManager.class.getMethod("play"));
        add(results, "adv_gesture_player_pause", IPlayerManager.class.getMethod("pausePlay"));
        add(results, "adv_gesture_panel_toggle_play", Panel.class.getMethod("togglePlayback", Aweme.class,
                boolean.class, boolean.class));
        add(results, "adv_gesture_panel_hide_play_control", Panel.class.getMethod("hidePlayControl"));
        add(results, "adv_gesture_panel_show_play_control", Panel.class.getMethod("showPlayControl"));
        return new HostScanReport("fixture", results, false);
    }

    private static void add(List<RuleScanResult> results, String id, Method target) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(target.getDeclaringClass()),
                target.getName(), HostTargets.descriptor(target)))));
    }

    private static void addType(List<RuleScanResult> results, String id, Class<?> type) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(type), "", ""))));
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, operation, values) -> {
                    if (!operation.getName().equals("hook")) return null;
                    Method target = (Method) values[0];
                    if (Modifier.isAbstract(target.getModifiers())) {
                        throw new IllegalArgumentException("Cannot hook abstract methods: " + target);
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, action, arguments) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) arguments[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, call, args) -> {
                                            if (call.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invokeOrProceed(Method target, Object owner, Object... arguments) throws Throwable {
            if (interceptors.containsKey(target)) return invoke(target, owner, arguments);
            try { return target.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Hooker hook = interceptors.get(target);
            assertNotNull("Expected installed hook for " + target, hook);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArg": return arguments[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "proceed":
                                try { return target.invoke(owner, values == null ? arguments : (Object[]) values[0]); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            default: return null;
                        }
                    });
            return hook.intercept(chain);
        }
    }
}
