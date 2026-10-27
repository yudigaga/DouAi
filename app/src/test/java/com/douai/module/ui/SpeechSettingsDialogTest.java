package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.ActionsKt;
import androidx.compose.ui.test.AssertionsKt;
import androidx.compose.ui.test.FiltersKt;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.feature.SpeechHttpClient;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.feature.VoiceCatalogLabels;
import com.douai.module.feature.VoiceNameTranslationCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.io.File;
import java.io.FileOutputStream;

import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class SpeechSettingsDialogTest {
    private Activity activity;
    private MemoryStore store;
    private SpeechSettingsDialog page;
    private View root;
    private final List<Dialog> children = new ArrayList<>();

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
    }

    @After public void tearDown() {
        if (page != null) page.close();
        for (Dialog dialog : children) dialog.dismiss();
        if (page != null) page.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        activity.finish();
    }

    @Test public void providerChoiceHidesOtherControlsAndPreservesBothConfigurations() {
        store.putString("tts_engine", "installed.engine");
        store.putString("tts_language", "en-US");
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/speak?text={text}");
        open(false);
        assertNotNull(root.findViewWithTag("settings.option.接口设置"));
        assertNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNull(root.findViewWithTag("settings.option.合成 GET"));
        chooseProvider(1);
        assertNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNotNull(root.findViewWithTag("settings.option.合成 GET"));
        assertNull(root.findViewWithTag("settings.option.音色列表 GET"));
        assertNotNull(root.findViewWithTag("settings.option.音色高级设置"));
        assertCompactThirdPartyPage();
        chooseProvider(0);
        assertNull(root.findViewWithTag("settings.option.音色高级设置"));
        assertEquals("installed.engine", value("语音引擎"));
        assertEquals("en-US", value("语音语言"));
        assertEquals("1.4", value("语速"));
        assertEquals("0.8", value("音调"));
        assertEquals("https://example.com/speak?text={text}", store.getString(SpeechSettings.GET_TEMPLATE, ""));
    }

    @Test public void breezeControlsKeepTheMiniMaxLayoutAndIndependentSettings() {
        store.putString(SpeechSettings.MINIMAX_API_KEY, "minimax-private-key");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "mini-voice");
        store.putString(SpeechSettings.SELECTED_VOICE, "third-voice");
        store.putString("tts_rate", "1.4");
        store.putString(BreezeSpeechSettings.API_KEY, "breeze-private-key");
        store.putString(BreezeSpeechSettings.VOICE_ID, "breeze-voice");
        open(false); chooseProvider(3);
        assertEquals("Breeze Blue", value("语音来源"));
        assertEquals("breeze-voice", value("音色"));
        assertEquals("已设置", value("API Key"));
        assertEquals(BreezeSpeechSettings.DEFAULT_BASE_URL, value("API 地址"));
        assertNotNull(root.findViewWithTag("settings.option.Breeze Blue 高级设置"));
        View reading = option(root, "朗读设置").getWindow().getDecorView();
        assertNotNull(reading.findViewWithTag("settings.option.语速"));
        assertNotNull(reading.findViewWithTag("settings.option.音量"));
        assertNull(reading.findViewWithTag("settings.option.音调"));
        AlertDialog volume = option(reading, "音量");
        input(volume, "音量").setText("10"); save(volume);
        assertTrue(volume.isShowing()); assertNotNull(input(volume, "音量").getError());
        input(volume, "音量").setText("2"); save(volume);
        assertEquals("2", store.getString(BreezeSpeechSettings.VOLUME, ""));
        chooseProvider(2);
        assertEquals("mini-voice", value("音色"));
        assertEquals("minimax-private-key", store.getString(SpeechSettings.MINIMAX_API_KEY, ""));
        chooseProvider(1); assertEquals("third-voice", value("音色"));
        chooseProvider(0); assertEquals("1.4", value("语速"));
        assertEquals("breeze-private-key", store.getString(BreezeSpeechSettings.API_KEY, ""));
    }

    @Test public void breezeOutputControlsComposeProfilesWithoutDroppingTheOtherParameters() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.OUTPUT_FORMAT, "mp3_44100_128");
        open(false);
        View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
        View audio = option(advanced, "音频输出").getWindow().getDecorView();
        assertEquals("MP3", value(audio, "生成音频格式（API）"));
        pickLabel(audio, "采样率", "48000 Hz");
        assertEquals("mp3_48000_128", store.getString(BreezeSpeechSettings.OUTPUT_FORMAT, ""));
        AlertDialog incompatible = option(audio, "生成音频格式（API）");
        clickLabel(incompatible, "WAV");
        assertTrue(incompatible.isShowing());
        assertEquals("mp3_48000_128", store.getString(BreezeSpeechSettings.OUTPUT_FORMAT, ""));
        incompatible.dismiss();
        pickLabel(audio, "MP3 码率", "接口默认");
        pickLabel(audio, "生成音频格式（API）", "WAV");
        assertEquals("wav_48000", store.getString(BreezeSpeechSettings.OUTPUT_FORMAT, ""));
        assertNull(audio.findViewWithTag("settings.option.MP3 码率"));
        assertTrue(((TextView) audio.findViewWithTag("speech.breeze.channel.hint")).getText().toString().contains("单声道"));
        AlertDialog manual = option(audio, "手动填写输出格式");
        input(manual, "手动填写输出格式").setText("pcm"); save(manual);
        assertTrue(manual.isShowing()); assertNotNull(input(manual, "手动填写输出格式").getError());
        input(manual, "手动填写输出格式").setText("opus_48000_64"); save(manual);
        assertEquals("opus_48000_64", store.getString(BreezeSpeechSettings.OUTPUT_FORMAT, ""));
    }

    @Test public void breezeLanguageAndGuidanceValidateTheMergedModelWithoutMiniMaxOnlyFields() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        open(false);
        View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
        assertNull(advanced.findViewWithTag("settings.option.混音与声音效果"));
        View expression = option(advanced, "语言与表达").getWindow().getDecorView();
        AlertDialog models = option(root, "语音模型"); clickLabel(models, "breeze-tts-2");
        AlertDialog language = option(expression, "语言代码");
        assertEquals(3, language.getListView().getCount());
        language.dismiss();
        models = option(root, "语音模型"); clickLabel(models, "breeze-tts-2-multilingual");
        pickLabel(expression, "语言代码", "ja");
        assertEquals("ja", store.getString(BreezeSpeechSettings.LANGUAGE_CODE, ""));
        models = option(root, "语音模型"); clickLabel(models, "breeze-tts-2");
        assertTrue(models.isShowing()); assertEquals("breeze-tts-2-multilingual", store.getString(BreezeSpeechSettings.MODEL, ""));
        models.dismiss();
        AlertDialog guidance = option(expression, "指导强度");
        input(guidance, "指导强度").setText("0.9"); save(guidance);
        assertTrue(guidance.isShowing()); assertNotNull(input(guidance, "指导强度").getError());
        input(guidance, "指导强度").setText("10"); save(guidance);
        assertEquals("10", store.getString(BreezeSpeechSettings.GUIDANCE_SCALE, ""));
        pickLabel(expression, "说话人增强", "开启");
        assertEquals("true", store.getString(BreezeSpeechSettings.SPEAKER_BOOST, ""));
    }

    @Test public void breezeCachedListsAndManualIdsStayLocalUntilExplicitRefresh() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "fixture-key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            store.putString(BreezeSpeechSettings.MODEL_SAMPLE, "[\"breeze-tts-2\"]");
            store.putString(BreezeSpeechSettings.MODEL_SOURCE, "api");
            cacheVoiceSample("[{\"id\":\"cached\",\"name\":\"缓存音色\"}]");
            store.putString(SpeechSettings.MINIMAX_VOICE_ID, "mini-voice");
            open(false);
            AlertDialog models = option("语音模型");
            assertEquals(2, models.getListView().getCount());
            assertEquals("刷新", models.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            models.dismiss();
            AlertDialog voices = option("音色");
            assertEquals("缓存音色 · cached", voices.getListView().getAdapter().getItem(0));
            voices.getButton(Dialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper();
            AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
            input(manual, "音色编号").setText("manual-breeze-voice"); save(manual);
            assertEquals("manual-breeze-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals("mini-voice", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void breezeRefreshUsesItsOwnAuthenticationAndCachesAndRetainsThemOnFailure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("[{\"model_id\":\"breeze-fixture\",\"name\":\"Fixture\",\"description\":\"fixture\"}]"));
            server.enqueue(new MockResponse().setBody("{\"voices\":[{\"voice_id\":\"voice-fixture\",\"name\":\"接口音色\"}],\"has_more\":false}"));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("private-key private-provider-message"));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "private-key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "[\"old-mini-model\"]");
            open(false); refreshBreezeList("语音模型");
            RecordedRequest models = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(models); assertEquals("/v1/models", models.getPath());
            assertEquals("private-key", models.getHeader("xi-api-key")); assertNull(models.getHeader("Authorization"));
            await(() -> "api".equals(store.getString(BreezeSpeechSettings.MODEL_SOURCE, "")));
            String cachedModels = store.getString(BreezeSpeechSettings.MODEL_SAMPLE, "");
            assertTrue(cachedModels.contains("breeze-fixture"));
            ((AlertDialog) ShadowDialog.getLatestDialog()).dismiss();
            refreshBreezeList("音色");
            RecordedRequest voices = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(voices); assertTrue(voices.getPath().startsWith("/v1/voices?"));
            assertEquals("private-key", voices.getHeader("xi-api-key"));
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("voice-fixture"));
            AlertDialog chooseVoice = (AlertDialog) ShadowDialog.getLatestDialog(); chooseVoice.getListView().performItemClick(null, 0, 0);
            assertEquals("voice-fixture", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            refreshBreezeList("语音模型"); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            await(() -> org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("原模型列表已保留"));
            assertEquals(cachedModels, store.getString(BreezeSpeechSettings.MODEL_SAMPLE, ""));
            assertFalse(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("private-provider-message"));
            assertEquals("[\"old-mini-model\"]", store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
        }
    }

    @Test public void breezeShowsTheFirstVoicePageBeforeLoadingTheNextPage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"voices\":[{\"voice_id\":\"first-breeze\",\"name\":\"首屏音色\"}],\"has_more\":true,\"next_page_token\":\"next-token\",\"page\":1}"));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "catalog-key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            open(false); refreshBreezeList("音色");
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("first-breeze"));
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(picker.isShowing()); assertTrue(picker.getListView().isShown());
            assertEquals("首屏音色 · first-breeze", picker.getListView().getAdapter().getItem(0));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void breezeCursorPagesKeepTheirOwnFiltersAndAppendSelectableVoices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(true, 1, "cursor ?&/", breezeItem("a", "第一页")));
            server.enqueue(breezePage(false, 2, "", breezeItem("b", "第二页")));
            prepareBreezeCatalog(server);
            store.putString(BreezeSpeechSettings.VOICE_TYPE, "personal");
            store.putString(BreezeSpeechSettings.VOICE_SEARCH, "温和 &A");
            store.putString(BreezeSpeechSettings.VOICE_LANGUAGE, "zh");
            store.putString(BreezeSpeechSettings.LANGUAGE_CODE, "en");
            store.putString(FishSpeechSettings.REFERENCE_ID, "fish-saved");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("第一页"));
            AlertDialog first = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, server.getRequestCount());
            assertEquals("加载更多", first.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            first.getButton(Dialog.BUTTON_POSITIVE).performClick(); assertTrue(first.isShowing());
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("第二页"));
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, picker.getListView().getCount());
            assertTrue(breezeCatalogStatus(library).contains("已加载 2 项"));
            assertFalse(breezeCatalogStatus(library).contains("搜索窗口"));
            RecordedRequest page1 = server.takeRequest(2, TimeUnit.SECONDS), page2 = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(page1); assertNotNull(page2);
            assertEquals("1", page1.getRequestUrl().queryParameter("page"));
            assertEquals("cursor ?&/", page2.getRequestUrl().queryParameter("next_page_token"));
            assertNull(page2.getRequestUrl().queryParameter("page"));
            assertEquals("personal", page2.getRequestUrl().queryParameter("voice_type"));
            assertEquals("温和 &A", page2.getRequestUrl().queryParameter("search"));
            assertEquals("zh", page2.getRequestUrl().queryParameter("language_code"));
            assertEquals("catalog-key", page2.getHeader("xi-api-key")); assertNull(page2.getHeader("Authorization"));
            picker.getListView().performItemClick(null, 1, 1);
            assertEquals("b", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals("en", store.getString(BreezeSpeechSettings.LANGUAGE_CODE, ""));
            assertEquals("fish-saved", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        }
    }

    @Test public void breezeOffsetPagesAreUsedWhenThereIsNoCursor() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(true, 1, "", breezeItem("offset-a", "首屏")));
            server.enqueue(breezePage(false, 2, "", breezeItem("offset-b", "次屏")));
            prepareBreezeCatalog(server);
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("offset-a"));
            ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("offset-b"));
            RecordedRequest page1 = server.takeRequest(2, TimeUnit.SECONDS), page2 = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(page1); assertNotNull(page2);
            assertEquals("1", page1.getRequestUrl().queryParameter("page"));
            assertEquals("2", page2.getRequestUrl().queryParameter("page"));
            assertNull(page2.getRequestUrl().queryParameter("next_page_token"));
            assertEquals(2, ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getCount());
            assertEquals("刷新", ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(Dialog.BUTTON_POSITIVE).getText().toString());
        }
    }

    @Test public void aFailedBreezeNextPageRetainsItsCacheAndCanBeRetriedExplicitly() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(true, 1, "retry-cursor", breezeItem("kept", "保留音色")));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("private-catalog-key private-provider-message"));
            server.enqueue(breezePage(false, 2, "", breezeItem("retry", "重试音色")));
            prepareBreezeCatalog(server); store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("kept"));
            String cached = store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> breezeCatalogStatus(library).contains("已加载音色和当前选择已保留"));
            assertTrue(picker.isShowing()); assertEquals(cached, store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals(1, picker.getListView().getCount());
            assertFalse(breezeCatalogStatus(library).contains("private-provider-message"));
            assertEquals(2, server.getRequestCount());
            assertEquals("加载更多", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("重试音色"));
            RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS), failed = server.takeRequest(2, TimeUnit.SECONDS), retried = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(failed); assertNotNull(retried);
            assertEquals("retry-cursor", failed.getRequestUrl().queryParameter("next_page_token"));
            assertEquals("retry-cursor", retried.getRequestUrl().queryParameter("next_page_token"));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void repeatedBreezeCursorsStopPagingWithoutDroppingLoadedVoices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(true, 1, "same-cursor", breezeItem("kept", "保留音色")));
            server.enqueue(breezePage(true, 2, "same-cursor", breezeItem("new-but-stalled", "游标重复")));
            prepareBreezeCatalog(server); store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("kept"));
            String cached = store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog(); picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> breezeCatalogStatus(library).contains("游标未前进"));
            assertEquals(cached, store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals(1, picker.getListView().getCount());
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(breezeCatalogStatus(library).contains("分页已停止"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void aNewBreezeFilterDiscardsTheOldPageWithoutClearingFishOrModelCaches() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(false, 1, "", breezeItem("old-all", "旧范围")).setBodyDelay(300, TimeUnit.MILLISECONDS));
            server.enqueue(breezePage(false, 1, "", breezeItem("new-personal", "新范围")));
            prepareBreezeCatalog(server);
            store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            store.putString(BreezeSpeechSettings.MODEL_SAMPLE, "[\"breeze-tts-2\"]");
            store.putString(FishSpeechSettings.VOICE_SAMPLE, "fish-cache");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            pickLabel(library, "音色范围", "个人音色"); startBreezeCatalogRefresh(library);
            RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(next); assertEquals("personal", next.getRequestUrl().queryParameter("voice_type"));
            assertEquals("1", next.getRequestUrl().queryParameter("page"));
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("new-personal"));
            assertFalse(store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("old-all"));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals("[\"breeze-tts-2\"]", store.getString(BreezeSpeechSettings.MODEL_SAMPLE, ""));
            assertEquals("fish-cache", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
        }
    }

    @Test public void leavingBreezeDiscardsItsLatePageAndKeepsTheTargetFishCache() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(false, 1, "", breezeItem("late-breeze", "迟到音色")).setBodyDelay(250, TimeUnit.MILLISECONDS));
            prepareBreezeCatalog(server); store.putString(FishSpeechSettings.VOICE_SAMPLE, "fish-cache");
            store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); chooseProvider(4);
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
            while (System.nanoTime() < until) { ShadowLooper.idleMainLooper(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
            assertEquals("", store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("fish-cache", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals(SpeechSettings.FISH, store.getString(SpeechSettings.PROVIDER, ""));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void closingABreezePickerCancelsThePendingPageWithoutReopeningIt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(true, 1, "pending", breezeItem("kept", "保留音色")));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            prepareBreezeCatalog(server); store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("kept"));
            String cached = store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            picker.getButton(Dialog.BUTTON_NEGATIVE).performClick();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
            while (System.nanoTime() < until) { ShadowLooper.idleMainLooper(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
            assertFalse(picker.isShowing()); assertSame(picker, ShadowDialog.getLatestDialog());
            assertEquals(cached, store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            AlertDialog reopened = option(root, "音色");
            assertTrue(reopened.isShowing()); assertEquals(1, reopened.getListView().getCount());
            assertEquals("加载更多", reopened.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void breezeCatalogStillRequiresAKeyBeforeRequestingItsFirstPage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareBreezeCatalog(server); store.putString(BreezeSpeechSettings.API_KEY, "");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library); ShadowLooper.idleMainLooper();
            assertEquals(0, server.getRequestCount());
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("API Key"));
        }
    }

    @Test public void breezeCatalogBudgetRetains200FilteredVoicesWithoutRequestingAnotherPage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int page = 0; page < 10; page++) {
                org.json.JSONObject[] entries = new org.json.JSONObject[20];
                for (int row = 0; row < entries.length; row++) entries[row] = breezeItem("breeze-" + (page * 20 + row), "音色 " + (page * 20 + row));
                server.enqueue(breezePage(true, page + 1, "", entries));
            }
            prepareBreezeCatalog(server); store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
            View library = breezeCatalogForm(); startBreezeCatalogRefresh(library);
            for (int page = 1; page <= 10; page++) {
                int expected = page * 20;
                await(() -> {
                    try { return new org.json.JSONArray(store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "[]")).length() == expected; }
                    catch (Exception invalid) { return false; }
                });
                if (page < 10) ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(Dialog.BUTTON_POSITIVE).performClick();
            }
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(picker.isShowing()); assertEquals(200, picker.getListView().getCount());
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(breezeCatalogStatus(library).contains("200 项预算"));
            assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals(10, server.getRequestCount());
            picker.getListView().performItemClick(null, 199, 199);
            assertEquals("breeze-199", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
        }
    }

    private void prepareBreezeCatalog(MockWebServer server) {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.API_KEY, "catalog-key");
        store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
        open(false);
    }

    private SpeechSettings speechSettings() { return new SpeechSettings(new LocalOptions(store.values)); }

    private void cacheVoiceSample(String sample) {
        SpeechSettings speech = speechSettings();
        store.putString(speech.fish ? FishSpeechSettings.VOICE_SAMPLE : BreezeSpeechSettings.VOICE_SAMPLE, sample);
        store.putString(VoiceCatalogLabels.queryStorageKey(speech), VoiceCatalogLabels.queryKey(speech));
    }

    @Test public void fishLocalSearchUsesChineseAliasesOriginalNamesIdsAndLabelsWithoutFetching() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer ai = new MockWebServer()) {
            prepareFishCatalog(catalog); configureNameAi(ai);
            String sample = "[{\"id\":\"alice-id\",\"name\":\"Alice\",\"labels\":[\"中文\",\"女声\"]},{\"id\":\"bob-id\",\"name\":\"Bob\",\"labels\":[\"英语\"]}]";
            cacheVoiceSample(sample);
            List<VoiceCatalog.Entry> entries = VoiceCatalog.parse(sample, "", "", "");
            VoiceNameTranslationCache.merge(store, VoiceNameTranslationCache.scope(speechSettings()), entries, Map.of("alice-id", "爱丽丝"));
            AlertDialog picker = fishSingleVoice(); EditText search = catalogSearch(picker);
            assertEquals(3, picker.getListView().getCount());
            assertTrue(picker.getListView().getAdapter().getItem(1).toString().contains("爱丽丝（Alice）"));
            search.setText("女声"); assertSame(picker, ShadowDialog.getLatestDialog()); assertEquals(1, picker.getListView().getCount());
            assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("中文 · 女声"));
            search.setText("bob-id"); assertEquals(1, picker.getListView().getCount());
            search.setText("爱丽丝"); assertEquals(1, picker.getListView().getCount());
            picker.getListView().performItemClick(null, 0, 0);
            assertEquals("alice-id", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("Alice", store.getString(FishSpeechSettings.REFERENCE_NAME, ""));
            assertEquals(sample, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertTrue(value("音色").contains("爱丽丝（Alice）"));
            assertEquals(0, catalog.getRequestCount()); assertEquals(0, ai.getRequestCount());
        }
    }

    @Test public void breezeLocalSearchKeepsFilteredSelectionMappedToTheOriginalVoice() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareBreezeCatalog(server);
            cacheVoiceSample("[{\"id\":\"first\",\"name\":\"Alice\"},{\"id\":\"second\",\"name\":\"Bob\",\"labels\":[\"沉稳\"]}]");
            AlertDialog picker = option("音色"); EditText search = catalogSearch(picker);
            search.setText("missing"); assertEquals(0, picker.getListView().getCount());
            picker.getWindow().getDecorView().findViewWithTag("voice.catalog.search.clear").performClick();
            assertSame(search, catalogSearch(picker)); assertEquals(2, picker.getListView().getCount());
            search.setText("沉稳"); assertEquals(1, picker.getListView().getCount());
            picker.getListView().performItemClick(null, 0, 0);
            assertEquals("second", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals("Bob", store.getString(BreezeSpeechSettings.VOICE_NAME, ""));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void fishLegacyOrMismatchedCatalogIsNotPresentedAsFilteredResults() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareFishCatalog(server);
            store.putString(FishSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"legacy\",\"name\":\"Unknown scope\"}]");
            store.putString(FishSpeechSettings.REFERENCE_ID, "selected-id");
            AlertDialog picker = fishSingleVoice(); assertEquals(1, picker.getListView().getCount()); picker.dismiss();
            cacheVoiceSample("[{\"id\":\"marked\",\"name\":\"English only\"}]");
            store.putString(FishSpeechSettings.VOICE_LANGUAGE, "en");
            picker = fishSingleVoice(); assertEquals(1, picker.getListView().getCount());
            assertEquals("selected-id", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void breezeLegacyCatalogWithoutItsQueryMarkerIsIgnored() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareBreezeCatalog(server);
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"legacy\",\"name\":\"Old whole catalog\"}]");
            store.putString(BreezeSpeechSettings.VOICE_ID, "keep-this-id");
            AlertDialog picker = option("音色"); assertEquals(0, picker.getListView().getCount());
            assertEquals("keep-this-id", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void fishAndBreezeRequireACatalogFilterBeforeStartingAnyRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareFishCatalog(server); store.putString(FishSpeechSettings.VOICE_LANGUAGE, "");
            startFishCatalogRefresh(fishCatalogForm()); ShadowLooper.idleMainLooper();
            assertEquals(0, server.getRequestCount());
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("先选择"));
            chooseProvider(3); store.putString(BreezeSpeechSettings.API_KEY, "key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            store.putString(BreezeSpeechSettings.VOICE_LANGUAGE, "");
            startBreezeCatalogRefresh(breezeCatalogForm()); ShadowLooper.idleMainLooper();
            assertEquals(0, server.getRequestCount());
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("先选择"));
        }
    }

    @Test public void fishPickerFiltersEditTagsAndInvalidateOnlyTheirOwnQueryCache() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "selected");
            cacheVoiceSample("[{\"id\":\"voice\",\"name\":\"Cached\"}]");
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "breeze-cache");
            store.putString(VoiceCatalogLabels.BREEZE_QUERY_KEY, "breeze-marker");
            AlertDialog picker = fishSingleVoice();
            picker.getWindow().getDecorView().findViewWithTag("voice.catalog.filters").performClick();
            assertFalse(picker.isShowing()); View filters = fishCatalogForm();
            assertEquals("中文 · zh", value(filters, "音色语言"));
            AlertDialog tags = option(filters, "自定义标签");
            input(tags, "自定义标签（每行一个）").setText("a\nb\nc\nd\ne\nf"); save(tags);
            assertTrue(tags.isShowing()); assertNotNull(input(tags, "自定义标签（每行一个）").getError());
            assertFalse(store.getString(FishSpeechSettings.VOICE_SAMPLE, "").isEmpty());
            input(tags, "自定义标签（每行一个）").setText("女声\n温柔"); save(tags);
            assertEquals(List.of("女声", "温柔"), speechSettings().fishSettings.voiceTags);
            assertEquals("", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("", store.getString(VoiceCatalogLabels.FISH_QUERY_KEY, ""));
            assertEquals("selected", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("breeze-cache", store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("breeze-marker", store.getString(VoiceCatalogLabels.BREEZE_QUERY_KEY, ""));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void breezeSearchAllPreservesLanguageGenderToneAndTagsAndWritesItsProvenance() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(breezePage(false, 2, "", breezeItem("matched", "Alice")));
            prepareBreezeCatalog(server);
            store.putString(BreezeSpeechSettings.VOICE_GENDER, "female"); store.putString(BreezeSpeechSettings.VOICE_TONE, "warm");
            store.putString(BreezeSpeechSettings.VOICE_TAGS, "[\"gentle\"]");
            cacheVoiceSample("[{\"id\":\"cached\",\"name\":\"Local\"}]");
            AlertDialog picker = option("音色"); catalogSearch(picker).setText("Alice");
            assertEquals(0, server.getRequestCount());
            picker.getWindow().getDecorView().findViewWithTag("voice.catalog.search.all").performClick();
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS); assertNotNull(sent);
            assertEquals("Alice", sent.getRequestUrl().queryParameter("search"));
            assertEquals("zh", sent.getRequestUrl().queryParameter("language_code"));
            assertEquals("female", sent.getRequestUrl().queryParameter("gender"));
            assertEquals("warm", sent.getRequestUrl().queryParameter("tone"));
            assertEquals("gentle", sent.getRequestUrl().queryParameter("tags"));
            assertEquals("20", sent.getRequestUrl().queryParameter("page_size"));
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("matched"));
            assertEquals(VoiceCatalogLabels.queryKey(speechSettings()), store.getString(VoiceCatalogLabels.BREEZE_QUERY_KEY, ""));
            assertEquals(1, ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getCount());
        }
    }

    @Test public void translatingNamesIsExplicitAndOnlyChangesTheDerivedNameCache() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer ai = new MockWebServer()) {
            ai.enqueue(nameTranslationResponse("爱丽丝")); prepareFishCatalog(catalog); configureNameAi(ai);
            String sample = "[{\"id\":\"alice-id\",\"name\":\"Alice\"}]"; cacheVoiceSample(sample);
            AlertDialog picker = fishSingleVoice(); assertEquals(0, ai.getRequestCount());
            picker.getWindow().getDecorView().findViewWithTag("voice.catalog.translate").performClick();
            RecordedRequest sent = ai.takeRequest(2, TimeUnit.SECONDS); assertNotNull(sent);
            assertEquals("Bearer text-ai-key", sent.getHeader("Authorization"));
            String body = sent.getBody().readUtf8(); assertTrue(body.contains("Alice")); assertFalse(body.contains("alice-id"));
            await(() -> picker.getListView().getAdapter().getItem(1).toString().contains("爱丽丝（Alice）"));
            assertSame(picker, ShadowDialog.getLatestDialog());
            assertEquals(sample, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("", store.getString(FishSpeechSettings.REFERENCE_NAME, ""));
            picker.getListView().performItemClick(null, 1, 1);
            assertEquals("alice-id", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("Alice", store.getString(FishSpeechSettings.REFERENCE_NAME, ""));
            assertTrue(value("音色").contains("爱丽丝（Alice）"));
            assertEquals(0, catalog.getRequestCount());
        }
    }

    @Test public void closingTheCatalogCancelsNameTranslationAndRejectsItsLateBatch() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer ai = new MockWebServer()) {
            ai.enqueue(nameTranslationResponse("迟到译名").setBodyDelay(300, TimeUnit.MILLISECONDS));
            prepareFishCatalog(catalog); configureNameAi(ai); cacheVoiceSample("[{\"id\":\"voice\",\"name\":\"Alice\"}]");
            AlertDialog picker = fishSingleVoice(); picker.getWindow().getDecorView().findViewWithTag("voice.catalog.translate").performClick();
            assertNotNull(ai.takeRequest(2, TimeUnit.SECONDS)); picker.getButton(Dialog.BUTTON_NEGATIVE).performClick();
            drainMainFor(500);
            assertFalse(picker.isShowing()); assertSame(picker, ShadowDialog.getLatestDialog());
            assertEquals("", store.getString(VoiceNameTranslationCache.KEY, ""));
            assertEquals(0, catalog.getRequestCount());
        }
    }

    @Test public void changingTheGlobalTextModelRejectsThePreviousNameTranslation() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer ai = new MockWebServer()) {
            ai.enqueue(nameTranslationResponse("旧模型译名").setBodyDelay(300, TimeUnit.MILLISECONDS));
            prepareBreezeCatalog(catalog); configureNameAi(ai); cacheVoiceSample("[{\"id\":\"voice\",\"name\":\"Alice\"}]");
            AlertDialog picker = option("音色"); picker.getWindow().getDecorView().findViewWithTag("voice.catalog.translate").performClick();
            assertNotNull(ai.takeRequest(2, TimeUnit.SECONDS)); store.putString(AiModelSettings.KEY, "");
            drainMainFor(500);
            assertTrue(picker.isShowing()); assertEquals("", store.getString(VoiceNameTranslationCache.KEY, ""));
            assertEquals("Alice · voice", picker.getListView().getAdapter().getItem(0));
            assertTrue(((TextView) picker.getWindow().getDecorView().findViewWithTag("voice.catalog.translation.status")).getText().toString().contains("已停止"));
        }
    }

    @Test public void oneTranslationClickHandlesAtMost100OfTheLoadedNames() throws Exception {
        try (MockWebServer catalog = new MockWebServer(); MockWebServer ai = new MockWebServer()) {
            java.util.concurrent.atomic.AtomicInteger translated = new java.util.concurrent.atomic.AtomicInteger();
            ai.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        org.json.JSONArray messages = new org.json.JSONObject(request.getBody().readUtf8()).getJSONArray("messages");
                        org.json.JSONArray names = new org.json.JSONObject(messages.getJSONObject(messages.length() - 1).getString("content")).getJSONArray("names");
                        assertTrue(names.length() <= 40); translated.addAndGet(names.length());
                        org.json.JSONArray aliases = new org.json.JSONArray();
                        for (int index = 0; index < names.length(); index++) aliases.put(new org.json.JSONObject()
                                .put("index", names.getJSONObject(index).getInt("index")).put("name_zh", "中文音色" + index));
                        String text = new org.json.JSONObject().put("translations", aliases).toString();
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody(new org.json.JSONObject()
                                .put("choices", new org.json.JSONArray().put(new org.json.JSONObject().put("message", new org.json.JSONObject().put("content", text)))).toString());
                    } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
                }
            });
            prepareBreezeCatalog(catalog); configureNameAi(ai);
            org.json.JSONArray sample = new org.json.JSONArray();
            for (int index = 0; index < 200; index++) sample.put(new org.json.JSONObject().put("id", "voice-" + index).put("name", "English " + index));
            cacheVoiceSample(sample.toString()); List<VoiceCatalog.Entry> originals = VoiceCatalog.parse(sample.toString(), "", "", "");
            AlertDialog picker = option("音色"); assertEquals(200, picker.getListView().getCount());
            picker.getWindow().getDecorView().findViewWithTag("voice.catalog.translate").performClick();
            await(() -> VoiceNameTranslationCache.read(store).pending(VoiceNameTranslationCache.scope(speechSettings()), originals, 200).size() == 100);
            drainMainFor(100); assertEquals(100, translated.get()); assertEquals(3, ai.getRequestCount());
            assertEquals(sample.toString(), store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals(0, catalog.getRequestCount());
        }
    }

    @Test public void breezeOfficialFiltersAllowMultipleGendersAgesAndMoreThanThreeTones() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareBreezeCatalog(server); View filters = breezeCatalogForm();
            for (String title : new String[]{"音色范围", "音色搜索", "音色语言", "音色分类", "音色性别", "音色年龄", "音色语气",
                    "音色口音", "口音标记范围", "音色来源类型", "仅收藏音色", "音色排序", "排序方向", "自定义标签"}) {
                assertNotNull(title, filters.findViewWithTag("settings.option." + title));
            }
            AlertDialog genders = option(filters, "音色性别"); assertEquals(3, genders.getListView().getCount());
            genders.getListView().performItemClick(null, 0, 0); genders.getListView().performItemClick(null, 1, 1);
            genders.getButton(Dialog.BUTTON_NEGATIVE).performClick(); assertTrue(speechSettings().breezeSettings.voiceGenders.isEmpty());
            genders = option(filters, "音色性别"); genders.getListView().performItemClick(null, 0, 0); genders.getListView().performItemClick(null, 1, 1); save(genders);
            assertEquals(List.of("male", "female"), speechSettings().breezeSettings.voiceGenders);
            AlertDialog ages = option(filters, "音色年龄"); assertEquals(4, ages.getListView().getCount());
            ages.getListView().performItemClick(null, 0, 0); ages.getListView().performItemClick(null, 3, 3); save(ages);
            assertEquals(List.of("child", "old"), speechSettings().breezeSettings.voiceAges);
            AlertDialog tones = option(filters, "音色语气"); assertEquals(20, tones.getListView().getCount());
            for (int index = 0; index < 4; index++) tones.getListView().performItemClick(null, index, index); save(tones);
            assertEquals(List.of("warm", "calm", "bright", "gentle"), speechSettings().breezeSettings.voiceTones);
            tones = option(filters, "音色语气"); tones.getButton(Dialog.BUTTON_NEUTRAL).performClick();
            assertTrue(speechSettings().breezeSettings.voiceTones.isEmpty()); assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void breezeRejectsAccentAndSortConflictsBeforeWritingAnyDraft() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareBreezeCatalog(server); View filters = breezeCatalogForm();
            pickLabel(filters, "音色口音", "美式 · american");
            cacheVoiceSample("[{\"id\":\"kept\",\"name\":\"Existing\"}]");
            String sample = store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""), marker = store.getString(VoiceCatalogLabels.BREEZE_QUERY_KEY, "");
            AlertDialog mode = option(filters, "口音标记范围"); clickLabel(mode, "仅无口音标记");
            assertTrue(mode.isShowing()); assertEquals("all", speechSettings().breezeSettings.voiceAccentMode);
            assertEquals("american", speechSettings().breezeSettings.voiceAccent); assertEquals(sample, store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals(marker, store.getString(VoiceCatalogLabels.BREEZE_QUERY_KEY, "")); mode.dismiss();
            pickLabel(filters, "音色口音", "不限"); pickLabel(filters, "口音标记范围", "仅无口音标记");
            AlertDialog accent = option(filters, "音色口音"); clickLabel(accent, "美式 · american");
            assertTrue(accent.isShowing()); assertEquals("", speechSettings().breezeSettings.voiceAccent); accent.dismiss();
            pickLabel(filters, "音色范围", "系统音色"); pickLabel(filters, "音色排序", "趋势热度");
            AlertDialog scope = option(filters, "音色范围"); clickLabel(scope, "个人音色");
            assertTrue(scope.isShowing()); assertEquals("default", speechSettings().breezeSettings.voiceType);
            assertEquals("trend", speechSettings().breezeSettings.voiceSort); scope.dismiss();
            pickLabel(filters, "音色排序", "创建时间"); pickLabel(filters, "音色范围", "个人音色");
            assertEquals("personal", speechSettings().breezeSettings.voiceType); assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void fishLanguageAndTitleLanguageSelectionsAreIndependentAndAcceptCustomCodes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareFishCatalog(server); store.putString(FishSpeechSettings.VOICE_LICENSED, "false");
            View filters = fishCatalogForm(); assertEquals("关闭", value(filters, "仅许可标记音色"));
            AlertDialog languages = option(filters, "音色语言");
            languages.getListView().performItemClick(null, 1, 1); save(languages);
            assertEquals(List.of("zh", "en"), speechSettings().fishSettings.voiceLanguages);
            AlertDialog titles = option(filters, "标题语言"); titles.getListView().performItemClick(null, 1, 1); titles.getListView().performItemClick(null, 2, 2); save(titles);
            assertEquals(List.of("en", "ja"), speechSettings().fishSettings.voiceTitleLanguages);
            AlertDialog custom = option(filters, "自定义音色语言"); input(custom, "音色语言（每行一个）").setText("zh\nzh-CN\nes"); save(custom);
            assertFalse(custom.isShowing()); assertEquals(List.of("zh", "zh-cn", "es"), speechSettings().fishSettings.voiceLanguages);
            assertEquals(List.of("en", "ja"), speechSettings().fishSettings.voiceTitleLanguages);
            AlertDialog author = option(filters, "作者 ID"); input(author, "作者 ID").setText("author-1"); save(author);
            pickLabel(filters, "仅许可标记音色", "开启"); pickLabel(filters, "音色排序", "使用任务数");
            assertEquals("author-1", speechSettings().fishSettings.voiceAuthorId); assertEquals("true", speechSettings().fishSettings.voiceLicensed);
            assertEquals("task_count", speechSettings().fishSettings.voiceSort); assertEquals(0, server.getRequestCount());
        }
    }

    private void configureNameAi(MockWebServer server) throws Exception {
        AiModelConfig model = AiModelConfig.fromJson(new org.json.JSONObject().put("id", "name-ai").put("name", "名称翻译测试")
                .put("provider", "test").put("protocol", AiModelConfig.CHAT).put("apiEndpoint", server.url("/v1").toString())
                .put("apiKey", "text-ai-key").put("modelName", "text-model").put("allowInsecureHttp", true));
        new AiModelSettings(java.util.Collections.singletonList(model), model.id).save(store);
    }

    private static EditText catalogSearch(AlertDialog picker) { return picker.getWindow().getDecorView().findViewWithTag("voice.catalog.search"); }

    private static MockResponse nameTranslationResponse(String alias) throws Exception {
        String text = new org.json.JSONObject().put("translations", new org.json.JSONArray().put(new org.json.JSONObject()
                .put("index", 0).put("name_zh", alias))).toString();
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(new org.json.JSONObject()
                .put("choices", new org.json.JSONArray().put(new org.json.JSONObject().put("message", new org.json.JSONObject().put("content", text)))).toString());
    }

    private static void drainMainFor(long millis) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) { ShadowLooper.idleMainLooper(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
        ShadowLooper.idleMainLooper();
    }

    private View breezeCatalogForm() {
        for (Dialog child : children) if (child.isShowing() && child.getWindow().getDecorView()
                .findViewWithTag("speech.section.Breeze Blue 音色列表") != null) return child.getWindow().getDecorView();
        View advanced = null;
        for (Dialog child : children) if (child.isShowing() && child.getWindow().getDecorView()
                .findViewWithTag("speech.section.Breeze Blue 高级设置") != null) advanced = child.getWindow().getDecorView();
        if (advanced == null) advanced = option(root, "Breeze Blue 高级设置").getWindow().getDecorView();
        return option(advanced, "音色列表").getWindow().getDecorView();
    }

    private static void startBreezeCatalogRefresh(View library) {
        View refresh = library.findViewWithTag("settings.option.刷新音色");
        assertNotNull(refresh); assertTrue(refresh.performClick()); ShadowLooper.idleMainLooper();
    }

    private static String breezeCatalogStatus(View library) {
        TextView status = library.findViewWithTag("speech.breeze.catalog.status");
        return status == null ? "" : status.getText().toString();
    }

    private static org.json.JSONObject breezeItem(String id, String title) throws Exception {
        return new org.json.JSONObject().put("voice_id", id).put("name", title);
    }

    private static MockResponse breezePage(boolean more, int page, String cursor, org.json.JSONObject... entries) throws Exception {
        org.json.JSONObject response = new org.json.JSONObject().put("voices", new org.json.JSONArray(java.util.Arrays.asList(entries)))
                .put("has_more", more).put("page", page);
        if (!cursor.isEmpty()) response.put("next_page_token", cursor);
        return new MockResponse().setBody(response.toString());
    }

    @Test public void breezeEndpointChangesCancelCatalogRequestsAndOnlyClearBreezeCaches() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("{\"voices\":[{\"voice_id\":\"current\",\"name\":\"当前音色\"}],\"has_more\":false}"));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "fixture-key");
            store.putString(BreezeSpeechSettings.BASE_URL, old.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "mini-cache");
            open(false); refreshBreezeList("语音模型"); assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog endpoint = option("API 地址"); input(endpoint, "API 地址").setText(current.url("/").toString()); save(endpoint);
            refreshBreezeList("音色"); assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> store.getString(BreezeSpeechSettings.VOICE_SAMPLE, "").contains("current"));
            assertEquals("", store.getString(BreezeSpeechSettings.MODEL_SAMPLE, ""));
            assertEquals("mini-cache", store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""));
        }
    }

    @Test public void aClosedBreezeEditorCannotSaveASecretOrAnOldModelChoice() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.API_KEY, "original-key");
        open(false);
        AlertDialog models = option(root, "语音模型"); models.dismiss(); ShadowLooper.idleMainLooper();
        AlertDialog editor = option("API Key"); input(editor, "API Key").setText("late-key");
        page.close(); save(editor);
        models.getListView().performItemClick(null, 1, 1);
        assertEquals("original-key", store.getString(BreezeSpeechSettings.API_KEY, ""));
        assertEquals("", store.getString(BreezeSpeechSettings.MODEL, ""));
    }

    private void refreshBreezeList(String title) {
        AlertDialog choice = SpeechSettings.FISH.equals(store.getString(SpeechSettings.PROVIDER, "")) && "音色".equals(title)
                ? fishSingleVoice() : option(root, title);
        choice.getButton(Dialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
    }

    private View fishVoiceForm() {
        for (Dialog child : children) if (child.isShowing() && child.getWindow().getDecorView()
                .findViewWithTag("speech.section.Fish Audio 音色设置") != null) return child.getWindow().getDecorView();
        AlertDialog opened = option(root, "音色");
        assertNotNull("Fish voice settings did not open", opened.getWindow().getDecorView()
                .findViewWithTag("speech.section.Fish Audio 音色设置"));
        return opened.getWindow().getDecorView();
    }

    private AlertDialog fishSingleVoice() { return option(fishVoiceForm(), "单音色"); }

    @Test public void fishVoiceNavigationReturnsToItsVisibleParentAndReopensAfterBack() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        open(false);
        View first = fishVoiceForm();
        AlertDialog picker = fishSingleVoice();
        picker.getButton(Dialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper();
        assertFalse(picker.isShowing());
        // Robolectric's latest dialog is now the dismissed picker; the parent remains visible.
        assertSame(picker, ShadowDialog.getLatestDialog());
        assertSame(first, fishVoiceForm());
        assertTrue(first.isShown());
        Dialog parent = null;
        for (Dialog child : children) if (child.getWindow().getDecorView() == first) parent = child;
        assertNotNull(parent);
        ((AlertDialog) parent).getButton(Dialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper();
        assertFalse(parent.isShowing());
        View reopened = fishVoiceForm();
        assertNotSame(first, reopened); assertTrue(reopened.isShown());
        assertNotNull(reopened.findViewWithTag("settings.option.音色来源"));
        AlertDialog currentPicker = fishSingleVoice();
        currentPicker.getListView().performItemClick(null, 0, 0);
        assertFalse(currentPicker.isShowing());
        assertEquals("single", store.getString(FishSpeechSettings.VOICE_MODE, ""));
    }

    private void pickLabel(View parent, String title, String label) {
        AlertDialog choice = option(parent, title); clickLabel(choice, label);
        assertFalse(title, choice.isShowing());
    }

    private static void clickLabel(AlertDialog choice, String label) {
        for (int index = 0; index < choice.getListView().getCount(); index++) {
            if (label.equals(choice.getListView().getAdapter().getItem(index).toString())) {
                choice.getListView().performItemClick(null, index, index); return;
            }
        }
        fail("Missing choice: " + label);
    }

    @Test public void fishIsTheFifthProviderAndKeepsItsDbControlsAndReferenceVoiceIndependent() {
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "mini-voice");
        store.putString(BreezeSpeechSettings.VOICE_ID, "breeze-voice");
        store.putString(BreezeSpeechSettings.VOLUME, "1.5");
        store.putString(FishSpeechSettings.API_KEY, "fish-private-key");
        open(false); chooseProvider(4);
        assertEquals("Fish Audio", value("语音来源"));
        assertEquals("接口默认音色", value("音色"));
        assertEquals("s2.1-pro", value("语音引擎"));
        assertEquals("已设置", value("API Key"));
        View reading = option(root, "朗读设置").getWindow().getDecorView();
        assertEquals("0", value(reading, "音量（dB）"));
        AlertDialog volume = option(reading, "音量（dB）"); input(volume, "音量（dB）").setText("-21"); save(volume);
        assertTrue(volume.isShowing()); assertNotNull(input(volume, "音量（dB）").getError());
        input(volume, "音量（dB）").setText("-6"); save(volume);
        assertEquals("-6", store.getString(FishSpeechSettings.VOLUME_DB, ""));
        pickLabel(reading, "响度归一化", "关闭");
        assertEquals("false", store.getString(FishSpeechSettings.NORMALIZE_LOUDNESS, ""));
        AlertDialog voices = fishSingleVoice();
        assertEquals("接口默认音色", voices.getListView().getAdapter().getItem(0));
        voices.getButton(Dialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        input(manual, "音色编号").setText("fish-reference"); save(manual);
        assertEquals("fish-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        voices = fishSingleVoice(); voices.getListView().performItemClick(null, 0, 0);
        assertEquals("", store.getString(FishSpeechSettings.REFERENCE_ID, "missing"));
        assertEquals("接口默认音色", value("音色"));
        chooseProvider(3); assertEquals("breeze-voice", value("音色"));
        assertEquals("1.5", store.getString(BreezeSpeechSettings.VOLUME, ""));
        chooseProvider(2); assertEquals("mini-voice", value("音色"));
    }

    @Test public void fishDefaultVoiceIsAUiChoiceAndNeverAnEmptyCatalogEntry() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.ENGINE, "s2.1-pro-free");
        store.putString(FishSpeechSettings.REFERENCE_ID, "manual-reference");
        String cached = "[{\"id\":\"catalog-reference\",\"name\":\"目录音色\"}]";
        cacheVoiceSample(cached);
        open(false);
        AlertDialog voices = fishSingleVoice();
        assertEquals(2, voices.getListView().getCount());
        assertEquals(-1, voices.getListView().getCheckedItemPosition());
        assertEquals("接口默认音色", voices.getListView().getAdapter().getItem(0));
        assertEquals("目录音色 · catalog-reference", voices.getListView().getAdapter().getItem(1));
        voices.getListView().performItemClick(null, 0, 0);
        assertEquals("", store.getString(FishSpeechSettings.REFERENCE_ID, "missing"));
        assertEquals("", store.getString(FishSpeechSettings.REFERENCE_NAME, "missing"));
        assertEquals("s2.1-pro-free", store.getString(FishSpeechSettings.ENGINE, ""));
        voices = fishSingleVoice();
        assertEquals(0, voices.getListView().getCheckedItemPosition());
        voices.getListView().performItemClick(null, 1, 1);
        assertEquals("catalog-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        assertEquals("目录音色", store.getString(FishSpeechSettings.REFERENCE_NAME, ""));
        assertEquals(cached, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
        List<VoiceCatalog.Entry> entries = VoiceCatalog.parse(cached, "", "", "");
        assertEquals(1, entries.size()); assertEquals("catalog-reference", entries.get(0).id);
        assertThrows(IllegalArgumentException.class, () -> new VoiceCatalog.Entry("", "默认项不能放进目录"));
    }

    @Test public void fishAudioAndGenerationGroupsRejectIncompatibleFormatsAndKeepDormantBitrates() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.MP3_BITRATE, "192");
        open(false);
        View advanced = option("Fish Audio 高级设置").getWindow().getDecorView();
        View audio = option(advanced, "音频输出").getWindow().getDecorView();
        pickLabel(audio, "采样率", "32000 Hz");
        AlertDialog format = option(audio, "生成音频格式（API）"); clickLabel(format, "Ogg / Opus");
        assertTrue(format.isShowing()); assertEquals("", store.getString(FishSpeechSettings.FORMAT, ""));
        assertEquals("32000", store.getString(FishSpeechSettings.SAMPLE_RATE, ""));
        format.dismiss();
        pickLabel(audio, "采样率", "接口默认"); pickLabel(audio, "生成音频格式（API）", "Ogg / Opus");
        pickLabel(audio, "Opus 码率", "48 kbps");
        assertEquals("48000", store.getString(FishSpeechSettings.OPUS_BITRATE, ""));
        assertEquals("192", store.getString(FishSpeechSettings.MP3_BITRATE, ""));
        View generation = option(advanced, "生成参数").getWindow().getDecorView();
        AlertDialog chunk = option(generation, "分段长度"); input(chunk, "分段长度").setText("99"); save(chunk);
        assertTrue(chunk.isShowing()); input(chunk, "分段长度").setText("200"); save(chunk);
        assertEquals("200", store.getString(FishSpeechSettings.CHUNK_LENGTH, ""));
        pickLabel(generation, "质量保护", "开启");
        assertEquals("true", store.getString(FishSpeechSettings.QUALITY_GUARD, ""));
    }

    @Test public void fishEnginesAreExplicitAndReferenceVoicesUseOnlyTheAuthenticatedVoiceCatalog() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"total\":1,\"items\":[{\"_id\":\"11111111111111111111111111111111\",\"type\":\"tts\",\"state\":\"trained\",\"title\":\"Fish 参考音色\"}],\"has_more\":false,\"total_is_exact\":true}"));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
            store.putString(FishSpeechSettings.API_KEY, "fish-private-key");
            store.putString(FishSpeechSettings.BASE_URL, server.url("/").toString());
            open(false);
            AlertDialog engines = option(root, "语音引擎");
            assertEquals(5, engines.getListView().getCount());
            assertFalse(engines.getButton(Dialog.BUTTON_POSITIVE).isShown());
            assertFalse(engines.getButton(Dialog.BUTTON_NEUTRAL).isShown());
            assertEquals(0, server.getRequestCount()); engines.dismiss();
            refreshBreezeList("音色");
            RecordedRequest references = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(references); assertTrue(references.getPath().startsWith("/model"));
            assertEquals("Bearer fish-private-key", references.getHeader("Authorization"));
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("11111111111111111111111111111111"));
            AlertDialog voices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("接口默认音色", voices.getListView().getAdapter().getItem(0));
            voices.getListView().performItemClick(null, 1, 1);
            assertEquals("11111111111111111111111111111111", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("", store.getString(FishSpeechSettings.ENGINE, ""));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void fishScopeAndSearchClearOnlyTheirVoiceCacheAndNeverChangeTheReference() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
        store.putString(FishSpeechSettings.VOICE_SAMPLE, "fish-cache");
        store.putString(FishSpeechSettings.MODEL_SAMPLE, "[\"s2.1-pro\"]");
        store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "breeze-cache");
        open(false);
        View voices = option(fishVoiceForm(), "音色库").getWindow().getDecorView();
        pickLabel(voices, "音色范围", "公开音色");
        assertEquals("public", store.getString(FishSpeechSettings.VOICE_SCOPE, ""));
        assertEquals("", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
        AlertDialog search = option(voices, "音色搜索"); input(search, "音色搜索").setText("reader"); save(search);
        assertEquals("reader", store.getString(FishSpeechSettings.VOICE_SEARCH, ""));
        assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        assertEquals("breeze-cache", store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
        assertEquals("[\"s2.1-pro\"]", store.getString(FishSpeechSettings.MODEL_SAMPLE, ""));
    }

    @Test public void fishFixedEnginePickerCannotWriteAfterClosing() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        open(false);
        AlertDialog engines = option(root, "语音引擎");
        assertEquals(5, engines.getListView().getCount());
        page.close(); engines.getListView().performItemClick(null, 1, 1);
        assertEquals("", store.getString(FishSpeechSettings.ENGINE, ""));
    }

    @Test public void fishPublicWindowFirstPageIsSelectableWithoutAnApiKey() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(100000, true, true, false, fishItem("public-a", "现成音色")));
            prepareFishCatalog(server);
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("false", sent.getRequestUrl().queryParameter("self"));
            assertNull(sent.getHeader("Authorization")); assertEquals("1", sent.getRequestUrl().queryParameter("page_number"));
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("public-a"));
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(picker.isShowing()); assertTrue(picker.getListView().isShown());
            assertEquals(2, picker.getListView().getCount());
            assertEquals("加载更多", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(fishCatalogStatus(library).contains("已加载 1 项"));
            assertTrue(fishCatalogStatus(library).contains("搜索窗口"));
            assertTrue(fishCatalogStatus(library).contains("总数估计"));
            assertEquals(1, server.getRequestCount());
            picker.getListView().performItemClick(null, 1, 1);
            assertEquals("public-a", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("single", store.getString(FishSpeechSettings.VOICE_MODE, ""));
        }
    }

    @Test public void fishLoadMoreAppendsAndDeduplicatesPagesBeforeSelectingANewVoice() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(3, true, true, false, fishItem("a", "第一音色"), fishItem("b", "第二音色")));
            server.enqueue(fishPage(3, false, true, false, fishItem("b", "重复音色"), fishItem("c", "第三音色")));
            prepareFishCatalog(server);
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("第一音色"));
            AlertDialog first = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, server.getRequestCount());
            first.getButton(Dialog.BUTTON_POSITIVE).performClick(); assertTrue(first.isShowing());
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("第三音色"));
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(4, picker.getListView().getCount());
            assertEquals("第二音色 · b", picker.getListView().getAdapter().getItem(2));
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(fishCatalogStatus(library).contains("已加载 3 项"));
            assertTrue(fishCatalogStatus(library).contains("搜索窗口内结果"));
            picker.getListView().performItemClick(null, 3, 3);
            assertEquals("c", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            RecordedRequest firstPage = server.takeRequest(2, TimeUnit.SECONDS), secondPage = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(firstPage); assertNotNull(secondPage);
            assertEquals("1", firstPage.getRequestUrl().queryParameter("page_number"));
            assertEquals("2", secondPage.getRequestUrl().queryParameter("page_number"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void aFailedFishNextPageRetainsTheFirstPageAndCurrentVoice() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(50000, true, true, false, fishItem("first", "首屏音色")));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("private-provider-body"));
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("first"));
            String cached = store.getString(FishSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> fishCatalogStatus(library).contains("已加载音色和当前选择已保留"));
            assertTrue(picker.isShowing()); assertEquals(2, picker.getListView().getCount());
            assertEquals(cached, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertFalse(fishCatalogStatus(library).contains("private-provider-body"));
            assertEquals("加载更多", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void aRepeatedFishNextPageKeepsTheExistingVoicesAndStopsThePagingLoop() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(10000, true, true, false, fishItem("same", "原音色")));
            server.enqueue(fishPage(10000, true, true, false, fishItem("same", "重复行")));
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("same"));
            String cached = store.getString(FishSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> fishCatalogStatus(library).contains("分页未前进"));
            assertTrue(picker.isShowing()); assertEquals(2, picker.getListView().getCount());
            assertEquals(cached, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void changingFishSearchDiscardsTheOldPageAndOnlyCachesTheNewQuery() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(10000, true, true, false, fishItem("old-query", "旧查询"))
                    .setBodyDelay(300, TimeUnit.MILLISECONDS));
            server.enqueue(fishPage(1, false, false, true, fishItem("new-query", "新查询")));
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog search = option(library, "音色搜索"); input(search, "音色搜索").setText("new & title"); save(search);
            startFishCatalogRefresh(library);
            RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(next); assertEquals("new & title", next.getRequestUrl().queryParameter("title"));
            assertEquals("1", next.getRequestUrl().queryParameter("page_number"));
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("new-query"));
            assertFalse(store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("old-query"));
            assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void aFishPageCannotWriteItsCacheAfterTheProviderChanges() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(10000, true, true, false, fishItem("late-fish", "迟到音色"))
                    .setBodyDelay(250, TimeUnit.MILLISECONDS));
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "breeze-cache");
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            chooseProvider(3);
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
            while (System.nanoTime() < until) { ShadowLooper.idleMainLooper(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
            assertEquals("", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals("breeze-cache", store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals(SpeechSettings.BREEZE, store.getString(SpeechSettings.PROVIDER, ""));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void privateFishVoicesStillRequireAKeyBeforeAnyRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            prepareFishCatalog(server); store.putString(FishSpeechSettings.VOICE_SCOPE, "self");
            View library = fishCatalogForm(); startFishCatalogRefresh(library); ShadowLooper.idleMainLooper();
            assertEquals(0, server.getRequestCount());
            assertEquals("", store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("API Key"));
        }
    }

    @Test public void filteredFishPagesAdvanceByRawItemsAndUnknownMoreStateDoesNotGuess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            org.json.JSONObject svc = fishItem("conversion", "非TTS音色").put("type", "svc");
            server.enqueue(fishPage(10000, true, true, false, svc));
            server.enqueue(fishPage(10000, null, true, false, fishItem("ready", "可用音色")));
            prepareFishCatalog(server);
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "missing").equals("[]"));
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, picker.getListView().getCount());
            assertEquals("加载更多", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("ready"));
            picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, picker.getListView().getCount());
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(fishCatalogStatus(library).contains("更多状态未知"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void anEmptyFishPageCannotKeepAdvancingAnUnfinishedExactTotal() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(fishPage(3, true, false, true, fishItem("first", "首屏音色")));
            server.enqueue(fishPage(3, null, false, true));
            prepareFishCatalog(server);
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            await(() -> store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("first"));
            String cached = store.getString(FishSpeechSettings.VOICE_SAMPLE, "");
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            await(() -> fishCatalogStatus(library).contains("分页未前进"));
            assertEquals(cached, store.getString(FishSpeechSettings.VOICE_SAMPLE, ""));
            assertEquals(2, picker.getListView().getCount());
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void fishCatalogBudgetKeepsTheLoadedWindowSelectable() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int page = 0; page < 10; page++) {
                org.json.JSONObject[] entries = new org.json.JSONObject[20];
                for (int row = 0; row < entries.length; row++) entries[row] = fishItem("voice-" + (page * 20 + row), "音色 " + (page * 20 + row));
                server.enqueue(fishPage(100000, true, true, false, entries));
            }
            prepareFishCatalog(server); store.putString(FishSpeechSettings.REFERENCE_ID, "saved-reference");
            View library = fishCatalogForm(); startFishCatalogRefresh(library);
            for (int page = 1; page <= 10; page++) {
                int expected = page * 20;
                await(() -> {
                    try { return new org.json.JSONArray(store.getString(FishSpeechSettings.VOICE_SAMPLE, "[]")).length() == expected; }
                    catch (Exception invalid) { return false; }
                });
                if (page < 10) ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(Dialog.BUTTON_POSITIVE).performClick();
            }
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(picker.isShowing()); assertEquals(201, picker.getListView().getCount());
            assertEquals("刷新", picker.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
            assertTrue(fishCatalogStatus(library).contains("200 项预算"));
            assertEquals("saved-reference", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
            assertEquals(10, server.getRequestCount());
            picker.getListView().performItemClick(null, 200, 200);
            assertEquals("voice-199", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        }
    }

    private void prepareFishCatalog(MockWebServer server) {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.BASE_URL, server.url("/").toString());
        open(false);
    }

    private View fishCatalogForm() {
        for (Dialog child : children) if (child.isShowing() && child.getWindow().getDecorView()
                .findViewWithTag("speech.section.Fish Audio 音色列表") != null) return child.getWindow().getDecorView();
        return option(fishVoiceForm(), "音色库").getWindow().getDecorView();
    }

    private static void startFishCatalogRefresh(View library) {
        View refresh = library.findViewWithTag("settings.option.刷新音色");
        assertNotNull(refresh); assertTrue(refresh.performClick()); ShadowLooper.idleMainLooper();
    }

    private static String fishCatalogStatus(View library) {
        TextView status = library.findViewWithTag("speech.fish.catalog.status");
        return status == null ? "" : status.getText().toString();
    }

    private static org.json.JSONObject fishItem(String id, String title) throws Exception {
        return new org.json.JSONObject().put("_id", id).put("title", title).put("type", "tts").put("state", "trained");
    }

    private static MockResponse fishPage(long total, Boolean more, boolean limited, boolean exact,
                                         org.json.JSONObject... entries) throws Exception {
        org.json.JSONObject response = new org.json.JSONObject().put("total", total).put("items", new org.json.JSONArray(java.util.Arrays.asList(entries)))
                .put("window_limited", limited).put("total_is_exact", exact);
        if (more != null) response.put("has_more", more);
        return new MockResponse().setBody(response.toString());
    }

    @Test public void fishInactiveControlsHideWithoutDiscardingTheirStoredValues() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.ENGINE, "s1");
        store.putString(FishSpeechSettings.NORMALIZE_LOUDNESS, "false");
        store.putString(FishSpeechSettings.NORMALIZE, "false");
        store.putString(FishSpeechSettings.LANGUAGE, "zh-CN");
        open(false);
        View reading = option("朗读设置").getWindow().getDecorView();
        assertNull(reading.findViewWithTag("settings.option.响度归一化"));
        View advanced = option("Fish Audio 高级设置").getWindow().getDecorView();
        View language = option(advanced, "语言与发音").getWindow().getDecorView();
        assertNull(language.findViewWithTag("settings.option.归一化语言"));
        assertEquals("zh-CN", store.getString(FishSpeechSettings.LANGUAGE, ""));
        AlertDialog engines = option(root, "语音引擎"); engines.getListView().performItemClick(null, 0, 0);
        assertNotNull(reading.findViewWithTag("settings.option.响度归一化"));
        assertEquals("false", store.getString(FishSpeechSettings.NORMALIZE_LOUDNESS, ""));
        pickLabel(language, "文本归一化", "开启");
        assertEquals("zh-CN", value(language, "归一化语言"));
    }

    @Test public void fishSpeakerRowsSaveAnOrderedArrayAndDoNotOverwriteTheSingleVoice() throws Exception {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        store.putString(FishSpeechSettings.REFERENCE_ID, "saved-single");
        open(false);
        AlertDialog modes = option(fishVoiceForm(), "音色来源"); clickLabel(modes, "多说话人音色");
        AlertDialog speakers = (AlertDialog) ShadowDialog.getLatestDialog();
        addSpeaker(speakers, "11111111111111111111111111111111");
        addSpeaker(speakers, "11111111111111111111111111111111");
        assertEquals("", store.getString(FishSpeechSettings.VOICE_MODE, ""));
        save(speakers);
        assertEquals("multi", store.getString(FishSpeechSettings.VOICE_MODE, ""));
        org.json.JSONArray ids = new org.json.JSONArray(store.getString(FishSpeechSettings.REFERENCE_IDS, ""));
        assertEquals(2, ids.length()); assertEquals(ids.getString(0), ids.getString(1));
        assertEquals("saved-single", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
        assertEquals("多说话人 · 2 位", value("音色"));
        AlertDialog engines = option(root, "语音引擎");
        engines.getListView().performItemClick(null, 3, 3);
        assertTrue(engines.isShowing()); assertEquals("", store.getString(FishSpeechSettings.ENGINE, ""));
    }

    @Test public void fishSpeakerDraftCannotPersistAfterCancelOrClosingThePage() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        open(false);
        AlertDialog modes = option(fishVoiceForm(), "音色来源"); clickLabel(modes, "多说话人音色");
        AlertDialog speakers = (AlertDialog) ShadowDialog.getLatestDialog();
        addSpeaker(speakers, "11111111111111111111111111111111");
        speakers.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        assertEquals("", store.getString(FishSpeechSettings.REFERENCE_IDS, ""));
        modes = option(fishVoiceForm(), "音色来源"); clickLabel(modes, "多说话人音色");
        speakers = (AlertDialog) ShadowDialog.getLatestDialog();
        addSpeaker(speakers, "22222222222222222222222222222222");
        page.close(); save(speakers);
        assertEquals("", store.getString(FishSpeechSettings.REFERENCE_IDS, ""));
        assertEquals("", store.getString(FishSpeechSettings.VOICE_MODE, ""));
    }

    private void addSpeaker(AlertDialog speakers, String id) {
        View add = speakers.getWindow().getDecorView().findViewWithTag("speech.fish.speaker.add");
        add.performClick();
        AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
        picker.getButton(Dialog.BUTTON_NEUTRAL).performClick(); ShadowLooper.idleMainLooper();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        input(editor, "参考音色 ID").setText(id); save(editor);
    }

    @Test public void fishReferenceSampleEditsRemainDraftsUntilTheWholeListIsSaved() throws Exception {
        java.io.File directory = new java.io.File(activity.getFilesDir(), "douai-voice-source");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        java.io.File sample = new java.io.File(directory, "reference-" + java.util.UUID.randomUUID() + ".m4a");
        java.nio.file.Files.writeString(sample.toPath(), "fLaCfixture-audio");
        try {
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
            String original = new org.json.JSONArray().put(new org.json.JSONArray().put(new org.json.JSONObject()
                    .put("path", sample.getAbsolutePath()).put("text", "  样本原文。  "))).toString();
            store.putString(FishSpeechSettings.REFERENCE_SAMPLES, original);
            open(false);
            AlertDialog modes = option(fishVoiceForm(), "音色来源"); clickLabel(modes, "参考音频");
            AlertDialog samples = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(((TextView) samples.getWindow().getDecorView().findViewWithTag("speech.fish.references.hint"))
                    .getText().toString().contains("发送给 Fish"));
            AlertDialog edit = option(samples.getWindow().getDecorView(), "样本 1 · 说话人 0");
            input(edit, "样本原文").setText("  新原文。\n完整保留。  "); save(edit);
            assertEquals(original, store.getString(FishSpeechSettings.REFERENCE_SAMPLES, ""));
            samples.getButton(Dialog.BUTTON_NEGATIVE).performClick();
            assertTrue(sample.isFile()); assertEquals(original, store.getString(FishSpeechSettings.REFERENCE_SAMPLES, ""));
            modes = option(fishVoiceForm(), "音色来源"); clickLabel(modes, "参考音频");
            samples = (AlertDialog) ShadowDialog.getLatestDialog();
            edit = option(samples.getWindow().getDecorView(), "样本 1 · 说话人 0");
            input(edit, "样本原文").setText("  新原文。\n完整保留。  "); save(edit); save(samples);
            assertEquals("references", store.getString(FishSpeechSettings.VOICE_MODE, ""));
            FishSpeechSettings settings = new FishSpeechSettings(new com.douai.module.config.LocalOptions(store.values));
            assertEquals("  新原文。\n完整保留。  ", settings.referenceGroups().get(0).get(0).text);
            assertTrue(sample.isFile());
        } finally { java.nio.file.Files.deleteIfExists(sample.toPath()); }
    }

    @Test public void breezeAutoModelsAndDeliveryOptionsKeepIndependentSelections() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.OUTPUT_FORMAT, "flac");
        open(false);
        assertEquals("自动按语言选择", value("语音模型"));
        View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
        View audio = option(advanced, "音频输出").getWindow().getDecorView();
        AlertDialog invalid = option(audio, "生成返回方式"); clickLabel(invalid, "HTTP 流式接收");
        assertTrue(invalid.isShowing()); assertEquals("", store.getString(BreezeSpeechSettings.DELIVERY, "")); invalid.dismiss();
        pickLabel(audio, "生成音频格式（API）", "MP3");
        pickLabel(audio, "生成返回方式", "HTTP 流式接收");
        assertEquals("stream", store.getString(BreezeSpeechSettings.DELIVERY, ""));
        pickLabel(audio, "生成返回方式", "后台生成");
        assertEquals("async", store.getString(BreezeSpeechSettings.DELIVERY, ""));
        assertEquals("", store.getString(FishSpeechSettings.FORMAT, ""));
    }

    @Test public void breezeVoiceFiltersClearOnlyTheirCatalogAndKeepSynthesisLanguage() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.VOICE_ID, "saved-voice");
        store.putString(BreezeSpeechSettings.LANGUAGE_CODE, "en");
        store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "old-cache");
        store.putString(BreezeSpeechSettings.MODEL_SAMPLE, "[\"breeze-tts-2\"]");
        open(false);
        View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
        View voices = option(advanced, "音色列表").getWindow().getDecorView();
        pickLabel(voices, "音色范围", "个人音色");
        assertEquals("", store.getString(BreezeSpeechSettings.VOICE_SAMPLE, ""));
        AlertDialog search = option(voices, "音色搜索"); input(search, "音色搜索").setText("narrator"); save(search);
        pickLabel(voices, "音色语言", "中文 · zh");
        assertEquals("saved-voice", store.getString(BreezeSpeechSettings.VOICE_ID, ""));
        assertEquals("en", store.getString(BreezeSpeechSettings.LANGUAGE_CODE, ""));
        assertEquals("[\"breeze-tts-2\"]", store.getString(BreezeSpeechSettings.MODEL_SAMPLE, ""));
    }

    @Test public void breezeInactiveExpressiveControlsRetainValuesAndShowOnlyEffectiveControls() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        store.putString(BreezeSpeechSettings.SPEAKER_BOOST, "true");
        store.putString(BreezeSpeechSettings.GUIDANCE_SCALE, "4");
        open(false);
        View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
        View expression = option(advanced, "语言与表达").getWindow().getDecorView();
        assertNull(expression.findViewWithTag("settings.option.指导强度"));
        assertNotNull(expression.findViewWithTag("settings.option.说话人增强"));
        AlertDialog instructions = option(expression, "语音指令");
        input(instructions, "语音指令").setText("温和自然地朗读。"); save(instructions);
        assertNotNull(expression.findViewWithTag("settings.option.指导强度"));
        assertNull(expression.findViewWithTag("settings.option.说话人增强"));
        assertEquals("true", store.getString(BreezeSpeechSettings.SPEAKER_BOOST, ""));
        assertEquals("4", store.getString(BreezeSpeechSettings.GUIDANCE_SCALE, ""));
    }

    @Test public void breezeInstructionEnhanceOnlyStartsOnClickAndSavesOnlyAfterConfirmation() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"instruction\":\"温和自然，停顿清晰。\",\"language_code\":\"zh\"}"));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "instruction-key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            store.putString(BreezeSpeechSettings.LANGUAGE_CODE, "zh");
            store.putString(BreezeSpeechSettings.INSTRUCTIONS, "温和。");
            open(false);
            View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
            View expression = option(advanced, "语言与表达").getWindow().getDecorView();
            AlertDialog editor = option(expression, "语音指令");
            assertEquals(0, server.getRequestCount());
            editor.getWindow().getDecorView().findViewWithTag("speech.breeze.enhance").performClick();
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("/v1/text-to-speech/enhance", sent.getPath());
            assertEquals("instruction-key", sent.getHeader("xi-api-key"));
            org.json.JSONObject body = new org.json.JSONObject(sent.getBody().readUtf8());
            assertEquals("温和。", body.getString("instruction")); assertFalse(body.has("text"));
            await(() -> input(editor, "语音指令").getText().toString().equals("温和自然，停顿清晰。"));
            assertEquals("温和。", store.getString(BreezeSpeechSettings.INSTRUCTIONS, "")); save(editor);
            assertEquals("温和自然，停顿清晰。", store.getString(BreezeSpeechSettings.INSTRUCTIONS, ""));
        }
    }

    @Test public void editingAnInstructionDraftDiscardsThePendingEnhancement() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBodyDelay(250, TimeUnit.MILLISECONDS)
                    .setBody("{\"instruction\":\"late result\",\"language_code\":\"en\"}"));
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(BreezeSpeechSettings.API_KEY, "instruction-key");
            store.putString(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
            store.putString(BreezeSpeechSettings.INSTRUCTIONS, "first draft");
            open(false);
            View advanced = option("Breeze Blue 高级设置").getWindow().getDecorView();
            View expression = option(advanced, "语言与表达").getWindow().getDecorView();
            AlertDialog editor = option(expression, "语音指令");
            editor.getWindow().getDecorView().findViewWithTag("speech.breeze.enhance").performClick();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            input(editor, "语音指令").setText("edited draft");
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < end) { ShadowLooper.idleMainLooper(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10)); }
            assertEquals("edited draft", input(editor, "语音指令").getText().toString());
            assertEquals("first draft", store.getString(BreezeSpeechSettings.INSTRUCTIONS, ""));
            page.close(); save(editor);
            assertEquals("first draft", store.getString(BreezeSpeechSettings.INSTRUCTIONS, ""));
        }
    }

    @Test public void miniMaxMixEditorValidatesWeightsAndRetainsTheSavedSingleVoice() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "saved-single");
        open(false);
        View form = miniMaxSection("混音与声音效果");
        AlertDialog editor = option(form, "混合音色（每行 voice_id/weight）");
        EditText text = input(editor, "混合音色（每行 voice_id/weight）");
        text.setText("one/10\none/20"); save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(text.getError());
        text.setText(" one/20 \n two/30 "); save(editor);
        assertFalse(editor.isShowing());
        assertEquals("one/20\ntwo/30", store.getString(MiniMaxSpeechOptions.TIMBRE_WEIGHTS, ""));
        assertEquals("saved-single", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
        TextView hint = form.findViewWithTag("speech.minimax.mix.hint");
        assertTrue(hint.getText().toString().contains("普通音色 ID 将被忽略"));
        form = miniMaxSection("音频输出");
        assertEquals("音频 URL · url", value(form, "返回方式（传输）"));
        pick(form, "返回方式（传输）", 1);
        assertEquals("hex", store.getString(MiniMaxSpeechOptions.OUTPUT_FORMAT, ""));
        assertEquals("mp3", new MiniMaxSpeechOptions(new com.douai.module.config.LocalOptions(store.values)).audioFormat);
    }

    @Test public void miniMaxGroupsKeepTheMainPageCompactAndRefreshOpenSummariesAndCleanup() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        open(false);
        for (String title : new String[]{"API Key", "API 地址", "语速", "音量", "音调", "生成音频格式（API）", "情绪"}) {
            assertNull(title + " belongs in its group", root.findViewWithTag("settings.option." + title));
        }
        AlertDialog advanced = option("MiniMax 高级设置");
        View summary = advanced.getWindow().getDecorView();
        for (String title : new String[]{"音频输出", "语言与发音", "混音与声音效果"}) {
            assertNotNull(summary.findViewWithTag("settings.option." + title));
        }
        assertNull(summary.findViewWithTag("settings.option.情绪"));
        View audio = miniMaxSection("音频输出");
        AlertDialog formats = option(audio, "生成音频格式（API）");
        assertTrue(formats.getListView().getAdapter().getItem(5).toString().contains("实验"));
        formats.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("mp3", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, "mp3"));
        pick(audio, "生成音频格式（API）", 5);
        assertEquals("aac", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
        assertNotNull(audio.findViewWithTag("speech.minimax.aac.hint"));
        assertNull(audio.findViewWithTag("settings.option.MP3 码率"));
        assertTrue(value(summary, "音频输出").contains("AAC（实验）"));
        View effects = miniMaxSection("混音与声音效果");
        assertNull(effects.findViewWithTag("settings.option.音高调整（-100 至 100）"));
        chooseProvider(0);
        assertNotNull(effects.findViewWithTag("speech.minimax.inactive"));
        chooseProvider(2);
        assertNotNull(effects.findViewWithTag("settings.option.混合音色（每行 voice_id/weight）"));
        assertEquals("aac", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
        page.close();
        assertFalse(advanced.isShowing());
        for (Dialog child : children) assertFalse("Grouped dialogs must close with settings", child.isShowing());
    }

    @Test public void muLawShowsItsFixedRateAndRestoresTheSavedRateWhenChangingBack() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(MiniMaxSpeechOptions.AUDIO_FORMAT, "pcmu_wav");
        store.putString(MiniMaxSpeechOptions.SAMPLE_RATE, "44100");
        store.putString(MiniMaxSpeechOptions.CHANNEL, "2");
        open(false);
        View form = miniMaxSection("音频输出");
        View effects = miniMaxSection("混音与声音效果");
        assertNull(form.findViewWithTag("settings.option.采样率"));
        assertNull(effects.findViewWithTag("settings.option.音高调整（-100 至 100）"));
        assertEquals("双声道", value(form, "声道"));
        TextView hint = form.findViewWithTag("speech.minimax.mulaw.hint");
        assertTrue(hint.getText().toString().contains("8000 Hz"));
        assertEquals("44100", store.getString(MiniMaxSpeechOptions.SAMPLE_RATE, ""));
        pick(form, "生成音频格式（API）", 1);
        assertEquals("44100 Hz", value(form, "采样率"));
        assertNotNull(effects.findViewWithTag("settings.option.音高调整（-100 至 100）"));
        assertEquals("2", store.getString(MiniMaxSpeechOptions.CHANNEL, ""));
    }

    @Test public void changingToOpusCannotSilentlyDropVoiceEffectsAndResetRequiresExplicitConfirmation() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(MiniMaxSpeechOptions.MODIFY_PITCH, "20");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "saved-single");
        open(false);
        View form = miniMaxSection("音频输出");
        View effects = miniMaxSection("混音与声音效果");
        AlertDialog formats = option(form, "生成音频格式（API）");
        assertEquals(6, formats.getListView().getAdapter().getCount());
        assertTrue(formats.getListView().getAdapter().getItem(3).toString().contains("重置"));
        formats.getListView().performItemClick(null, 3, 3); ShadowLooper.idleMainLooper();
        assertTrue(formats.isShowing());
        assertEquals("20", store.getString(MiniMaxSpeechOptions.MODIFY_PITCH, ""));
        assertEquals("mp3", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, "mp3"));
        formats.dismiss();
        AlertDialog reset = option(effects, "重置音色调整");
        reset.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("20", store.getString(MiniMaxSpeechOptions.MODIFY_PITCH, ""));
        reset = option(effects, "重置音色调整");
        reset.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
        assertEquals("0", store.getString(MiniMaxSpeechOptions.MODIFY_PITCH, ""));
        assertEquals("saved-single", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
        pick(form, "生成音频格式（API）", 3);
        assertEquals("opus", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
        assertNull(effects.findViewWithTag("settings.option.音高调整（-100 至 100）"));
        assertNotNull(effects.findViewWithTag("speech.minimax.effects.hint"));
    }

    @Test public void miniMaxControlsPreserveSystemAndThirdPartySettingsAndHideTheApiKey() {
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        store.putString(SpeechSettings.SELECTED_VOICE, "third-voice");
        store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-private-key");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "minimax-voice");
        open(false);
        chooseProvider(2);
        assertEquals("MiniMax", value("语音来源"));
        assertEquals("已设置", value("API Key"));
        assertEquals(SpeechSettings.DEFAULT_MINIMAX_BASE_URL, value("API 地址"));
        assertEquals("1", value("语速"));
        assertEquals("1", value("音量"));
        assertEquals("0", value("音调"));
        assertEquals("minimax-voice", value("音色"));
        assertNull(root.findViewWithTag("settings.option.合成 GET"));
        assertNull(root.findViewWithTag("settings.option.语音引擎"));
        assertNull(root.findViewWithTag("settings.option.音色高级设置"));
        AlertDialog editor = option("API Key");
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                input(editor, "API Key").getInputType() & android.text.InputType.TYPE_MASK_VARIATION);
        editor.dismiss();
        editor = option("音量");
        input(editor, "音量").setText("10");
        save(editor);
        assertEquals("10", store.getString(SpeechSettings.MINIMAX_VOLUME, ""));
        chooseProvider(1);
        assertEquals("third-voice", value("音色"));
        chooseProvider(0);
        assertEquals("1.4", value("语速"));
        assertEquals("0.8", value("音调"));
        assertEquals("minimax-voice", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
    }

    @Test public void miniMaxRefreshActionsBelongToTheirCachedListsAndOpeningDoesNotRequestOrSynthesize() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "[\"speech-2.8-hd\"]");
            store.putString(SpeechSettings.MINIMAX_MODEL_SOURCE, "api");
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "[{\"id\":\"saved-voice\",\"name\":\"缓存音色\"}]");
            open(false);
            assertNull("Model refresh must stay inside its list", root.findViewWithTag("settings.option.刷新模型"));
            assertNull("Official refresh must stay inside its model list", root.findViewWithTag("settings.option.刷新官方模型列表"));
            assertNull("Voice refresh must stay inside its list", root.findViewWithTag("settings.option.刷新音色"));
            AlertDialog models = option("模型");
            assertEquals("speech-2.8-hd", models.getListView().getAdapter().getItem(0));
            assertEquals("刷新", models.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("手动填写", models.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            models.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog sources = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, sources.getListView().getAdapter().getCount());
            assertTrue(sources.getListView().getAdapter().getItem(0).toString().contains("当前接口"));
            assertTrue(sources.getListView().getAdapter().getItem(1).toString().contains("官方目录"));
            sources.dismiss();
            AlertDialog voices = option("音色");
            assertEquals("缓存音色 · saved-voice", voices.getListView().getAdapter().getItem(0));
            assertEquals("刷新", voices.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("手动填写", voices.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            assertEquals("Opening cached lists must not request models, voices or paid synthesis", 0, server.getRequestCount());
        }
    }

    @Test public void miniMaxEditsValidateRangesAndManualVoiceDoesNotOverwriteThirdPartySelection() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.SELECTED_VOICE, "third-voice");
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "saved-voice");
        open(false);
        String[][] invalids = {{"语速", "0.49"}, {"音量", "0"}, {"音调", "0.5"}, {"API 地址", "invalid-url"}};
        for (String[] invalid : invalids) {
            AlertDialog editor = option(invalid[0]);
            input(editor, invalid[0]).setText(invalid[1]);
            save(editor);
            assertTrue(editor.isShowing());
            assertNotNull(input(editor, invalid[0]).getError());
            editor.dismiss();
        }
        AlertDialog voice = option("音色");
        voice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("saved-voice", input(manual, "音色编号").getText().toString());
        input(manual, "音色编号").setText("new-minimax-voice");
        save(manual);
        assertEquals("new-minimax-voice", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
        assertEquals("third-voice", store.getString(SpeechSettings.SELECTED_VOICE, ""));
    }

    @Test public void miniMaxModelsOfferTheBuiltInCatalogAndManualIdsWithoutRelabelingThemAsFetched() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.MINIMAX_MODEL, "manual-model");
        open(false);
        AlertDialog choice = option("模型");
        assertEquals(9, choice.getListView().getAdapter().getCount());
        assertTrue(choice.getListView().getAdapter().getItem(0).toString().contains("当前模型，未在列表中"));
        choice.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        input(editor, "模型").setText("");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input(editor, "模型").getError());
        input(editor, "模型").setText("speech-future-hd");
        save(editor);
        assertEquals("speech-future-hd", value("模型"));
        TextView hint = root.findViewWithTag("speech.model.capability");
        assertNotNull(hint);
        assertTrue(hint.getText().toString().contains("能力未验证"));
    }

    @Test public void miniMaxModelRefreshUsesTheApiAndPreservesItsCacheWhenTheApiHasOnlyTextModels() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"MiniMax-M2.5\"},{\"id\":\"speech-future-hd\"}]}"));
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"id\":\"MiniMax-M2.5\"}]}"));
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            open(false);
            refreshMiniMaxList("模型");
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals("/v1/models", request.getPath());
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"));
            await(() -> "api".equals(store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, "")));
            String cached = store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, "");
            assertTrue(cached.contains("speech-future-hd"));
            assertFalse(cached.contains("MiniMax-M2.5"));
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 1, 1);
            assertEquals("speech-future-hd", value("模型"));
            refreshMiniMaxList("模型");
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            await(() -> org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString().contains("未返回语音模型"));
            assertEquals(cached, store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
            assertEquals("api", store.getString(SpeechSettings.MINIMAX_MODEL_SOURCE, ""));
        }
    }

    @Test public void changingMiniMaxEndpointCancelsTheModelFetchAndReleasesTheSharedVoiceRequestSlot() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("{\"system_voice\":[{\"voice_id\":\"current\"}],\"base_resp\":{\"status_code\":0}}"));
            old.start(); current.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, old.url("/").toString());
            open(false);
            refreshMiniMaxList("模型");
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            refreshMiniMaxList("音色");
            assertEquals(1, old.getRequestCount());
            AlertDialog editor = option("API 地址");
            input(editor, "API 地址").setText(current.url("/").toString());
            save(editor);
            refreshMiniMaxList("音色");
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").contains("current"));
            assertEquals("", store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
        }
    }

    @Test public void miniMaxAdvancedSettingsValidateAndPersistWithoutChangingLocalControls() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        store.putString(SpeechSettings.RATE, "1.8");
        store.putString(SpeechSettings.PITCH, "1.4");
        open(false);
        View form = miniMaxSection("语言与发音");
        pick(form, "情绪", 1);
        assertEquals("happy", store.getString(MiniMaxSpeechOptions.EMOTION, ""));
        form = miniMaxSection("音频输出");
        pick(form, "生成音频格式（API）", 1);
        assertEquals("wav", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
        assertNull(form.findViewWithTag("settings.option.MP3 码率"));
        pick(form, "采样率", 5);
        pick(form, "声道", 1);
        assertEquals("44100", store.getString(MiniMaxSpeechOptions.SAMPLE_RATE, ""));
        assertEquals("2", store.getString(MiniMaxSpeechOptions.CHANNEL, ""));
        form = miniMaxSection("混音与声音效果");
        AlertDialog editor = option(form, "音高调整（-100 至 100）");
        input(editor, "音高调整（-100 至 100）").setText("101");
        save(editor);
        assertTrue(editor.isShowing());
        input(editor, "音高调整（-100 至 100）").setText("-30");
        save(editor);
        assertEquals("-30", store.getString(MiniMaxSpeechOptions.MODIFY_PITCH, ""));
        form = miniMaxSection("语言与发音");
        editor = option(form, "发音字典（每行 原文/替换内容）");
        input(editor, "发音字典（每行 原文/替换内容）").setText("invalid");
        save(editor);
        assertTrue(editor.isShowing());
        input(editor, "发音字典（每行 原文/替换内容）").setText("处理/(chu3)(li3)\n世界/world");
        save(editor);
        assertEquals("处理/(chu3)(li3)\n世界/world", store.getString(MiniMaxSpeechOptions.PRONUNCIATION_TONE, ""));
        assertEquals("1.8", store.getString(SpeechSettings.RATE, ""));
        assertEquals("1.4", store.getString(SpeechSettings.PITCH, ""));
    }

    @Test public void miniMaxAdvancedAndModelEditsRejectIncompatibleMergedSettingsBeforeSaving() {
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        open(false);
        View form = miniMaxSection("语言与发音");
        AlertDialog emotion = option(form, "情绪");
        assertTrue(emotion.getListView().getAdapter().getItem(9).toString().contains("不适用"));
        assertTrue(emotion.getListView().getAdapter().getItem(9).toString().contains("speech-2.6"));
        emotion.getListView().performItemClick(null, 9, 9);
        assertTrue(emotion.isShowing());
        assertEquals("", store.getString(MiniMaxSpeechOptions.EMOTION, ""));
        emotion.dismiss();
        pick(form, "语言增强", java.util.Arrays.asList(MiniMaxSpeechOptions.LANGUAGES).indexOf("English"));
        AlertDialog latex = option(form, "LaTeX 朗读");
        assertTrue(latex.getListView().getAdapter().getItem(1).toString().contains("不适用"));
        latex.getListView().performItemClick(null, 1, 1);
        assertTrue(latex.isShowing());
        assertEquals("", store.getString(MiniMaxSpeechOptions.LATEX_READ, ""));
        latex.dismiss();
        pick(form, "语言增强", 1);
        pick(form, "LaTeX 朗读", 1);
        assertEquals("true", store.getString(MiniMaxSpeechOptions.LATEX_READ, ""));
        pick(form, "文本归一化", 2);
        for (Dialog child : children) child.dismiss();
        AlertDialog models = option("模型");
        boolean offeredConflict = false;
        for (int index = 0; index < models.getListView().getAdapter().getCount(); index++) {
            String label = models.getListView().getAdapter().getItem(index).toString();
            if (label.startsWith("speech-01-hd")) offeredConflict = label.contains("不适用");
        }
        assertTrue(offeredConflict);
        models.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        input(manual, "模型").setText("speech-01-hd");
        save(manual);
        assertTrue(manual.isShowing());
        assertNotNull(input(manual, "模型").getError());
        assertEquals(SpeechSettings.DEFAULT_MINIMAX_MODEL, store.getString(SpeechSettings.MINIMAX_MODEL, SpeechSettings.DEFAULT_MINIMAX_MODEL));
    }

    private void pick(View form, String title, int index) {
        AlertDialog choice = option(form, title);
        choice.getListView().performItemClick(null, index, index);
        assertFalse(title, choice.isShowing());
    }

    private void refreshMiniMaxList(String title) {
        AlertDialog choice = option(title);
        choice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        if ("模型".equals(title)) {
            AlertDialog source = (AlertDialog) ShadowDialog.getLatestDialog();
            source.getListView().performItemClick(null, 0, 0);
            ShadowLooper.idleMainLooper();
        }
    }

    @Test public void miniMaxRefreshShowsSafeHttpAndProviderReasonsAndRetainsExistingCatalogs() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-private-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            String models = "[\"speech-2.8-hd\"]", voices = "[{\"id\":\"saved-voice\",\"name\":\"saved\"}]";
            store.putString(SpeechSettings.MINIMAX_MODEL_SAMPLE, models);
            store.putString(SpeechSettings.MINIMAX_VOICE_SAMPLE, voices);
            open(false);
            for (String action : new String[]{"刷新模型", "刷新音色"}) {
                for (int code : new int[]{401, 429, 1008, 2042, -1}) {
                    String expected;
                    MockResponse response = new MockResponse().addHeader("Content-Type", "application/json");
                    if (code == 401 || code == 429) {
                        response.setResponseCode(code).setBody("private-token https://secret.example");
                        expected = "HTTP " + code;
                    } else if (code > 0) {
                        response.setBody("{\"base_resp\":{\"status_code\":" + code
                                + ",\"status_msg\":\"fixture-private-key https://secret.example\"}}");
                        expected = code == 1008 ? "账户余额不足" : "当前账户无所选音色权限";
                    } else {
                        response.setBody("private-token https://secret.example");
                        expected = "不是有效的 JSON";
                    }
                    server.enqueue(response);
                    refreshMiniMaxList("刷新模型".equals(action) ? "模型" : "音色");
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                    await(() -> String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()).contains(expected));
                    String message = String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
                    assertTrue(message.contains("已保留"));
                    assertFalse(message.contains("fixture-private-key"));
                    assertFalse(message.contains("private-token"));
                    assertFalse(message.contains("secret.example"));
                    assertEquals(models, store.getString(SpeechSettings.MINIMAX_MODEL_SAMPLE, ""));
                    assertEquals(voices, store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""));
                }
            }
        }
    }

    @Test public void miniMaxRefreshCachesItsOwnCatalogAndSelectsTheProviderVoiceId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody(
                    "{\"system_voice\":[{\"voice_id\":\"male-qn-qingse\",\"voice_name\":\"青涩青年\"}],"
                    + "\"voice_cloning\":[{\"voice_id\":\"cloned\"}],\"voice_generation\":[],\"base_resp\":{\"status_code\":0}}"));
            server.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString());
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString("tts_voice_sample", "7. Third Voice");
            store.putString(SpeechSettings.SELECTED_VOICE, "7");
            open(false);
            refreshMiniMaxList("音色");
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/get_voice", request.getPath());
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"));
            await(() -> !store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").isEmpty());
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(2, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 1, 1);
            assertEquals("cloned", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""));
            assertEquals("7. Third Voice", store.getString("tts_voice_sample", ""));
            assertEquals("7", store.getString(SpeechSettings.SELECTED_VOICE, ""));
            AlertDialog apiKey = option("API Key");
            input(apiKey, "API Key").setText("new-key");
            save(apiKey);
            assertEquals("", store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, ""));
            assertEquals("7. Third Voice", store.getString("tts_voice_sample", ""));
        }
    }

    @Test public void changingMiniMaxEndpointCancelsTheOldVoiceFetch() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("{\"system_voice\":[{\"voice_id\":\"current\",\"voice_name\":\"当前音色\"}],\"base_resp\":{\"status_code\":0}}"));
            old.start(); current.start();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(SpeechSettings.MINIMAX_API_KEY, "fixture-key");
            store.putString(SpeechSettings.MINIMAX_BASE_URL, old.url("/").toString());
            open(false);
            refreshMiniMaxList("音色");
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog editor = option("API 地址");
            input(editor, "API 地址").setText(current.url("/").toString());
            save(editor);
            refreshMiniMaxList("音色");
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> store.getString(SpeechSettings.MINIMAX_VOICE_SAMPLE, "").contains("current"));
            assertEquals("当前音色 · current", ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getAdapter().getItem(0));
        }
    }

    @Test public void savingGetUpdatesParameterNamesAndRejectsAnInvalidAddress() {
        open(true);
        AlertDialog editor = option("合成 GET");
        EditText input = input(editor, "合成 GET");
        input.setText("invalid-url");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        input.setText("https://example.com/tts?ID=key&body={text}&speaker={voice}");
        save(editor);
        assertFalse(editor.isShowing());
        assertEquals("ID、body、speaker", value("合成参数"));
        AlertDialog parameters = option("合成参数");
        View form = parameters.getWindow().getDecorView();
        assertEquals("ID", ((EditText) form.findViewWithTag("speech.parameter.name.0")).getText().toString());
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.2").getVisibility());
    }

    @Test public void localRateAndPitchRejectInvalidEditsWithoutChangingSavedValues() {
        store.putString("tts_rate", "1.4");
        store.putString("tts_pitch", "0.8");
        open(false);
        String[][] settings = {{"语速", "tts_rate", "1.4"}, {"音调", "tts_pitch", "0.8"}};
        for (String[] setting : settings) {
            AlertDialog editor = option(setting[0]);
            EditText input = input(editor, setting[0]);
            for (String invalid : new String[]{"0.49", "2.01", "0", "-1", "invalid", "NaN", "Infinity", "", "1e100"}) {
                input.setText(invalid);
                save(editor);
                assertTrue(setting[0] + " must reject " + invalid, editor.isShowing());
                assertNotNull(setting[0] + " must explain the valid range", input.getError());
                assertEquals(setting[2], store.getString(setting[1], ""));
            }
            editor.dismiss();
        }
    }

    @Test public void localRateAndPitchAcceptBothRangeEndpoints() {
        open(false);
        String[][] settings = {{"语速", "tts_rate"}, {"音调", "tts_pitch"}};
        for (String[] setting : settings) {
            for (String valid : new String[]{"0.5", "2"}) {
                AlertDialog editor = option(setting[0]);
                input(editor, setting[0]).setText(valid);
                save(editor);
                assertFalse(editor.isShowing());
                assertEquals(valid, store.getString(setting[1], ""));
                assertEquals(valid, value(setting[0]));
            }
        }
    }

    @Test public void legacyLocalValuesDisplayEffectiveValuesWithoutRewritingStoredPreferences() {
        store.putString("tts_rate", "3");
        store.putString("tts_pitch", "0.1");
        open(false);
        assertEquals("2", value("语速"));
        assertEquals("0.5", value("音调"));
        AlertDialog rate = option("语速");
        assertEquals("2", input(rate, "语速").getText().toString());
        rate.dismiss();
        assertEquals("3", store.getString("tts_rate", ""));
        assertEquals("0.1", store.getString("tts_pitch", ""));

        for (String invalid : new String[]{"invalid", "NaN", "Infinity", ""}) {
            chooseProvider(1);
            store.putString("tts_rate", invalid);
            store.putString("tts_pitch", invalid);
            chooseProvider(0);
            assertEquals("1", value("语速"));
            assertEquals("1", value("音调"));
            assertEquals(invalid, store.getString("tts_rate", ""));
            assertEquals(invalid, store.getString("tts_pitch", ""));
        }
    }

    @Test public void parametersHaveStableTagsAndSupportArbitraryNamesAndTextVoiceBindings() {
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/speak?ID=old&body={text}&speaker={voice}");
        open(true);
        AlertDialog editor = option("合成参数");
        View form = editor.getWindow().getDecorView();
        form.findViewWithTag("speech.parameter.remove.0").performClick();
        form.findViewWithTag("speech.parameter.add").performClick();
        assertNull(form.findViewWithTag("speech.parameter.name.0"));
        ((EditText) form.findViewWithTag("speech.parameter.name.3")).setText("token");
        ((EditText) form.findViewWithTag("speech.parameter.value.3")).setText("key + 中文&value");
        Spinner source = form.findViewWithTag("speech.parameter.source.1");
        source.setSelection(0);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        ((EditText) form.findViewWithTag("speech.parameter.value.1")).setText("retained fixed value");
        source.setSelection(1);
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, form.findViewWithTag("speech.parameter.fixed.1").getVisibility());
        save(editor);
        ThirdPartyRequestTemplate template = ThirdPartyRequestTemplate.parse(store.getString(SpeechSettings.GET_TEMPLATE, ""), "{text}", "{voice}");
        assertEquals(3, template.parameters.size());
        assertEquals("{text}", template.parameters.get(0).value);
        assertEquals("{voice}", template.parameters.get(1).value);
        assertEquals("token", template.parameters.get(2).name);
        assertEquals("key + 中文&value", template.parameters.get(2).value);
        String resolved = SpeechHttpClient.resolveTemplate(store.getString(SpeechSettings.GET_TEMPLATE, ""), "你好 & +", "223");
        assertTrue(resolved, resolved.contains("body=%E4%BD%A0%E5%A5%BD%20%26%20%2B"));
        assertTrue(resolved, resolved.contains("speaker=223"));
    }

    @Test public void voiceListGetIsEditedAsOneAddressWithoutAParameterEditor() {
        store.putString(SpeechSettings.VOICE_GET_TEMPLATE, "https://example.com/voices");
        open(true);
        AlertDialog editor = option("音色列表 GET");
        assertEquals("https://example.com/voices", input(editor, "音色列表 GET").getText().toString());
        input(editor, "音色列表 GET").setText("https://example.com/catalog.txt");
        save(editor);
        assertEquals("https://example.com/catalog.txt", value("音色列表 GET"));
        assertCompactThirdPartyPage();
    }

    @Test public void headersValidateStringValuesAndSaveUserDefinedNames() {
        open(true);
        AlertDialog editor = option("合成请求头");
        EditText input = input(editor, "合成请求头");
        input.setText("{\"X-Api-Key\":42}");
        save(editor);
        assertTrue(editor.isShowing());
        assertNotNull(input.getError());
        input.setText("{\"X-Api-Key\":\"user-key\"}");
        save(editor);
        assertEquals("{\"X-Api-Key\":\"user-key\"}", store.getString(SpeechSettings.HEADERS, ""));
        assertEquals("{\"X-Api-Key\":\"user-key\"}", value("合成请求头"));
        assertCompactThirdPartyPage();
    }

    @Test public void advancedPageKeepsSavedFieldsAndSamplesAndRefreshesAfterEdits() {
        store.putString(SpeechSettings.HEADERS, "{\"X-Synthesis\":\"saved\"}");
        store.putString(SpeechSettings.AUDIO_FIELD, "data.audio");
        store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"saved\"}");
        store.putString(SpeechSettings.VOICE_LIST_PATH, "data.voices");
        store.putString(SpeechSettings.VOICE_ID_PATH, "id");
        store.putString(SpeechSettings.VOICE_NAME_PATH, "name");
        String sample = "{\"data\":{\"voices\":[{\"id\":\"7\",\"name\":\"saved voice\"}]}}";
        store.putString("tts_voice_sample", sample);
        open(true);
        assertCompactThirdPartyPage();
        AlertDialog advanced = option("音色高级设置");
        View form = advanced.getWindow().getDecorView();
        assertEquals("{\"X-Synthesis\":\"saved\"}", value("合成请求头"));
        assertEquals("data.audio", value("音频地址字段"));
        assertNull(form.findViewWithTag("settings.option.合成请求头"));
        assertNull(form.findViewWithTag("settings.option.音频地址字段"));
        assertEquals("{\"X-Catalog\":\"saved\"}", value(form, "音色列表请求头"));
        assertEquals("data.voices", value(form, "列表字段"));
        assertEquals("id", value(form, "音色编号字段"));
        assertEquals("name", value(form, "音色名称字段"));
        assertEquals("已保存", value(form, "音色列表样本"));
        AlertDialog editor = option(form, "列表字段");
        input(editor, "列表字段").setText("result.voices");
        save(editor);
        assertTrue(advanced.isShowing());
        assertEquals("result.voices", value(form, "列表字段"));
        assertEquals(sample, store.getString("tts_voice_sample", ""));
        assertEquals("result.voices", store.getString(SpeechSettings.VOICE_LIST_PATH, ""));
        assertEquals("{\"X-Catalog\":\"saved\"}", store.getString(SpeechSettings.VOICE_HEADERS, ""));
        assertCompactThirdPartyPage();
        advanced.dismiss();
        chooseProvider(0);
        chooseProvider(1);
        advanced = option("音色高级设置");
        assertEquals("result.voices", value(advanced.getWindow().getDecorView(), "列表字段"));
        assertEquals("data.audio", value("音频地址字段"));
    }

    @Test public void synthesisSamplePreviewsAndSelectsAnAudioFieldLikeTheDownloadEditor() {
        String sample = "{\"code\":200,\"data\":{\"audio\":\"https://example.com/voice.mp3\","
                + "\"cover\":\"https://example.com/cover.jpg\"}}";
        open(true);
        AlertDialog editor = option("合成响应样本");
        EditText input = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample");
        input.setText(sample);
        editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.parse").performClick();
        TextView preview = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.preview");
        assertEquals("data.audio\ndata.cover", preview.getText().toString());
        save(editor);
        assertEquals("2 个链接字段", value("合成响应样本"));
        AlertDialog fields = option("音频地址字段");
        assertEquals("data.audio", fields.getListView().getAdapter().getItem(1));
        fields.getListView().performItemClick(null, 1, 1);
        assertEquals("data.audio", store.getString(SpeechSettings.AUDIO_FIELD, ""));
        assertEquals("https://example.com/voice.mp3", SpeechHttpClient.audioUrl(sample, settingsAudioField()));
        assertEquals(sample, store.getString("tts_synthesis_sample", ""));
        assertEquals("", store.getString("tts_voice_sample", ""));
    }

    @Test public void synthesisArrayFieldsKeepTheirIndexAndManualFieldsRemainEditable() {
        String sample = "{\"data\":{\"audio\":[\"https://example.com/one.mp3\",\"https://example.com/two.mp3\"]}}";
        store.putString("tts_synthesis_sample", sample);
        open(true);
        AlertDialog fields = option("音频地址字段");
        assertEquals("data.audio[1]", fields.getListView().getAdapter().getItem(2));
        fields.getListView().performItemClick(null, 2, 2);
        assertEquals("https://example.com/two.mp3", SpeechHttpClient.audioUrl(sample, settingsAudioField()));
        fields = option("音频地址字段");
        fields.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
        input(manual, "音频地址字段").setText("custom.output");
        save(manual);
        assertEquals("custom.output", value("音频地址字段"));
    }

    @Test public void synthesisTxtSampleAndInvalidOrCanceledEditsDoNotAffectVoiceSettings() {
        String link = "https://example.com/audio.mp3";
        store.putString("tts_voice_sample", "1. 叶修");
        store.putString(SpeechSettings.SELECTED_VOICE, "1");
        open(true);
        AlertDialog editor = option("合成响应样本");
        EditText input = editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample");
        input.setText("{invalid}"); save(editor);
        assertTrue(editor.isShowing()); assertNotNull(input.getError());
        input.setText(link); save(editor);
        assertEquals("1 个链接字段", value("合成响应样本"));
        AlertDialog fields = option("音频地址字段");
        fields.getListView().performItemClick(null, 0, 0);
        assertEquals("自动识别", value("音频地址字段"));
        assertEquals(link, SpeechHttpClient.audioUrl(link, settingsAudioField()));
        editor = option("合成响应样本");
        ((EditText) editor.getWindow().getDecorView().findViewWithTag("speech.synthesis.sample")).setText("");
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals(link, store.getString("tts_synthesis_sample", ""));
        assertEquals("1. 叶修", store.getString("tts_voice_sample", ""));
        assertEquals("1", store.getString(SpeechSettings.SELECTED_VOICE, ""));
    }

    private String settingsAudioField() { return store.getString(SpeechSettings.AUDIO_FIELD, ""); }

    @Test public void cancelingEditorsLeavesExistingConfigurationAndVoiceUntouched() {
        store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"saved\"}");
        store.putString(SpeechSettings.SELECTED_VOICE, "7");
        store.putString(SpeechSettings.SELECTED_VOICE_NAME, "saved voice");
        store.putString("tts_voice_sample", "7. saved voice\n8. other voice");
        open(true);
        AlertDialog advanced = option("音色高级设置");
        AlertDialog editor = option(advanced.getWindow().getDecorView(), "音色列表请求头");
        input(editor, "音色列表请求头").setText("{}");
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("{\"X-Catalog\":\"saved\"}", store.getString(SpeechSettings.VOICE_HEADERS, ""));
        advanced.dismiss();
        AlertDialog choice = option("音色");
        choice.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("saved voice · 7", value("音色"));
    }

    @Test public void numberedTxtSampleUsesTheExplicitVoiceIdInTheSynthesisRequest() {
        store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/tts?content={text}&ID={voice}");
        open(true);
        AlertDialog advanced = option("音色高级设置");
        AlertDialog sample = option(advanced.getWindow().getDecorView(), "音色列表样本");
        input(sample, "音色列表样本").setText("1. 叶修\n223. 瓦瓦");
        save(sample);
        assertEquals("已保存", value(advanced.getWindow().getDecorView(), "音色列表样本"));
        advanced.dismiss();
        AlertDialog choice = option("音色");
        assertEquals(2, choice.getListView().getAdapter().getCount());
        assertEquals("瓦瓦 · 223", choice.getListView().getAdapter().getItem(1));
        choice.getListView().performItemClick(null, 1, 1);
        assertEquals("223", store.getString(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals("瓦瓦", store.getString(SpeechSettings.SELECTED_VOICE_NAME, ""));
        assertEquals("瓦瓦 · 223", value("音色"));
        assertTrue(SpeechHttpClient.resolveTemplate(store.getString(SpeechSettings.GET_TEMPLATE, ""), "hello", "223").endsWith("&ID=223"));
    }

    @Test public void refreshUsesIndependentGetAndHeadersAndDisplaysAllNumberedVoices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            StringBuilder body = new StringBuilder();
            for (int id = 1; id <= 223; id++) body.append(id).append(". 音色").append(id).append('\n');
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=utf-8").setBody(body.toString()));
            server.start();
            store.putString(SpeechSettings.VOICE_GET_TEMPLATE, server.url("/voices").toString());
            store.putString(SpeechSettings.VOICE_HEADERS, "{\"X-Catalog\":\"catalog-key\"}");
            open(true);
            fieldParent("刷新音色").findViewWithTag("settings.option.刷新音色").performClick();
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("GET", request.getMethod());
            assertEquals("/voices", request.getPath());
            assertNull(request.getRequestUrl().query());
            assertEquals("catalog-key", request.getHeader("X-Catalog"));
            await(() -> body.toString().equals(store.getString("tts_voice_sample", "")));
            AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(223, choice.getListView().getAdapter().getCount());
            choice.getListView().performItemClick(null, 222, 222);
            assertEquals("223", store.getString(SpeechSettings.SELECTED_VOICE, ""));
        }
    }

    @Test public void changingCatalogSettingsCancelsOldFetchAndAllowsTheNewCatalog() throws Exception {
        try (MockWebServer old = new MockWebServer(); MockWebServer current = new MockWebServer()) {
            old.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            current.enqueue(new MockResponse().setBody("7. 当前音色"));
            old.start();
            current.start();
            store.putString(SpeechSettings.VOICE_GET_TEMPLATE, old.url("/old").toString());
            open(true);
            fieldParent("刷新音色").findViewWithTag("settings.option.刷新音色").performClick();
            assertNotNull(old.takeRequest(2, TimeUnit.SECONDS));
            AlertDialog editor = option("音色列表 GET");
            input(editor, "音色列表 GET").setText(current.url("/new").toString());
            save(editor);
            fieldParent("刷新音色").findViewWithTag("settings.option.刷新音色").performClick();
            assertNotNull(current.takeRequest(2, TimeUnit.SECONDS));
            await(() -> "7. 当前音色".equals(store.getString("tts_voice_sample", "")));
            assertEquals("当前音色 · 7", ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().getAdapter().getItem(0));
        }
    }

    @Test public void closingPreviewIgnoresTheCanceledSynthesisCallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            store.putString(SpeechSettings.GET_TEMPLATE, server.url("/tts").toString());
            open(true);
            AlertDialog preview = option("试听");
            View form = preview.getWindow().getDecorView();
            form.findViewWithTag("speech.preview.start").performClick();
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            TextView status = form.findViewWithTag("speech.preview.status");
            assertEquals("正在生成语音", status.getText().toString());
            preview.dismiss();
            ShadowLooper.idleMainLooper();
            assertEquals("正在生成语音", status.getText().toString());
            assertFalse(form.findViewWithTag("speech.preview.start").isEnabled());
        }
    }

    private void open(boolean thirdParty) {
        if (thirdParty) store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY);
        page = new SpeechSettingsDialog(activity, store, false, (dialog, cleanup) -> {
            children.add(dialog);
            if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
            dialog.setOwnerActivity(activity);
            dialog.show();
        });
        page.dialog().setOwnerActivity(activity);
        page.dialog().show();
        root = page.dialog().getWindow().getDecorView();
    }

    private AlertDialog option(String title) {
        return option(fieldParent(title), title);
    }

    private View miniMaxSection(String title) {
        for (Dialog child : children) {
            if (child.isShowing() && child.getWindow().getDecorView().findViewWithTag("speech.section." + title) != null) {
                return child.getWindow().getDecorView();
            }
        }
        View advanced = fieldParent(title);
        return option(advanced, title).getWindow().getDecorView();
    }

    private View fieldParent(String title) {
        if (root.findViewWithTag("settings.option." + title) != null) return root;
        for (int index = children.size() - 1; index >= 0; index--) {
            Dialog child = children.get(index);
            if (child.isShowing() && child.getWindow().getDecorView().findViewWithTag("settings.option." + title) != null) {
                return child.getWindow().getDecorView();
            }
        }
        String section;
        if (java.util.Arrays.asList("API Key", "API 地址", "语音引擎", "语音语言", "合成请求头", "合成响应样本", "音频地址字段").contains(title)) section = "接口设置";
        else if (java.util.Arrays.asList("语速", "音量", "音调").contains(title)) section = "朗读设置";
        else if (java.util.Arrays.asList("音色列表 GET", "刷新音色").contains(title)) section = "音色高级设置";
        else if (java.util.Arrays.asList("音频输出", "语言与发音", "混音与声音效果").contains(title)) section = "MiniMax 高级设置";
        else throw new AssertionError("Missing speech settings row: " + title);
        View opened = option(root, section).getWindow().getDecorView();
        assertNotNull(title, opened.findViewWithTag("settings.option." + title));
        return opened;
    }

    private AlertDialog option(View parent, String title) {
        View option = parent.findViewWithTag("settings.option." + title);
        assertNotNull(title, option);
        assertTrue(option.performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void chooseProvider(int which) {
        AlertDialog choice = option("语音来源");
        choice.getListView().performItemClick(null, which, which);
        ShadowLooper.idleMainLooper();
    }

    private String value(String title) {
        return value(fieldParent(title), title);
    }

    private String value(View parent, String title) {
        TextView value = parent.findViewWithTag("settings.value." + title);
        assertNotNull(title, value);
        return value.getText().toString();
    }

    private void assertCompactThirdPartyPage() {
        assertNotNull(root.findViewWithTag("settings.option.接口设置"));
        assertNull(root.findViewWithTag("settings.option.合成请求头"));
        assertNull(root.findViewWithTag("settings.option.音频地址字段"));
        assertNull(root.findViewWithTag("settings.option.音色列表参数"));
        for (String title : new String[]{"音色列表请求头", "列表字段", "音色编号字段", "音色名称字段", "音色列表样本"}) {
            assertNull(title, root.findViewWithTag("settings.option." + title));
        }
    }

    private static EditText input(AlertDialog dialog, String title) {
        return dialog.getWindow().getDecorView().findViewWithTag("settings.input." + title);
    }

    private static void save(AlertDialog dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }

    private static void await(BooleanSupplier complete) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        ShadowLooper.idleMainLooper();
        assertTrue("Speech settings request did not complete", complete.getAsBoolean());
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }

    @RunWith(RobolectricTestRunner.class)
    @Config(sdk = 28, qualifiers = "w296dp-h560dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public static final class MiuixPreviewTest {
        @Rule public final AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> compose =
                AndroidComposeTestRule_androidKt.createAndroidComposeRule(ComponentActivity.class, EmptyCoroutineContext.INSTANCE);
        private final List<Dialog> children = new ArrayList<>();
        private SpeechSettingsDialog page;

        @After public void close() {
            compose.runOnUiThread(() -> {
                if (page != null) { page.close(); page.dialog().dismiss(); }
                for (Dialog dialog : children) dialog.dismiss();
                return Unit.INSTANCE;
            });
        }

        @Test public void narrowMiuixPageAndParameterEditorKeepTheirControlsVisible() throws Exception { verify(); }

        @Test @Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi")
        public void landscapeMiuixPageAndParameterEditorKeepTheirControlsVisible() throws Exception { verify(); }

        @Test public void breezeSettingsUseTheSameNarrowHostDialogsWithTheirOwnControls() throws Exception {
            MemoryStore store = new MemoryStore();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
            store.putString(BreezeSpeechSettings.API_KEY, "breeze-private-key");
            store.putString(BreezeSpeechSettings.VOICE_ID, "breeze-current-voice");
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog);
                    if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                });
                page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show();
                return Unit.INSTANCE;
            });
            scrollToOption("语音来源");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasText("Breeze Blue", false, false), false));
            compose.waitForIdle();
            assertEquals(SpeechSettings.BREEZE, store.getString(SpeechSettings.PROVIDER, ""));
            scrollToOption("语音模型");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.语音模型"), false));
            savePreview(page.dialog(), "speech-breeze-settings");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音模型"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.0"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("手动填写", false, false), false));
            compose.runOnUiThread(() -> { children.get(children.size() - 1).dismiss(); return Unit.INSTANCE; });
            verifySimpleSection("接口设置", new String[]{"API Key", "API 地址"}, "speech-breeze-interface");
            verifySimpleSection("朗读设置", new String[]{"语速", "音量"}, "speech-breeze-reading");
            scrollToOption("Breeze Blue 高级设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.Breeze Blue 高级设置"), false));
            Dialog advanced = children.get(children.size() - 1);
            scrollToOption(advanced, "音频输出");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音频输出"), false));
            Dialog audio = children.get(children.size() - 1);
            for (String title : new String[]{"生成音频格式（API）", "采样率", "MP3 码率", "手动填写输出格式"}) {
                scrollToOption(audio, title);
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + title), false));
            }
            savePreview(audio, "speech-breeze-audio");
            compose.runOnUiThread(() -> { audio.dismiss(); return Unit.INSTANCE; });
            scrollToOption(advanced, "语言与表达");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语言与表达"), false));
            Dialog expression = children.get(children.size() - 1);
            for (String title : new String[]{"语音指令", "语言代码", "指导强度", "说话人增强"}) {
                scrollToOption(expression, title);
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + title), false));
            }
            compose.runOnIdle(() -> {
                assertEquals("breeze-private-key", store.getString(BreezeSpeechSettings.API_KEY, ""));
                assertEquals("", store.getString(SpeechSettings.MINIMAX_API_KEY, ""));
                return Unit.INSTANCE;
            });
        }

        @Test public void fishSettingsSeparateTheEngineReferenceVoiceAndSupportedGroupsInMiuix() throws Exception {
            MemoryStore store = new MemoryStore();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            store.putString(FishSpeechSettings.API_KEY, "fish-private-key");
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog);
                    if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                });
                page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show();
                return Unit.INSTANCE;
            });
            scrollToOption("语音来源");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasText("Fish Audio", false, false), false));
            compose.waitForIdle();
            assertEquals(SpeechSettings.FISH, store.getString(SpeechSettings.PROVIDER, ""));
            scrollToOption("语音引擎");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音引擎"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.0"), false));
            compose.runOnUiThread(() -> { children.get(children.size() - 1).dismiss(); return Unit.INSTANCE; });
            verifySimpleSection("接口设置", new String[]{"API Key", "API 地址"}, "speech-fish-interface");
            verifySimpleSection("朗读设置", new String[]{"语速", "音量（dB）", "响度归一化"}, "speech-fish-reading");
            scrollToOption("Fish Audio 高级设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.Fish Audio 高级设置"), false));
            Dialog advanced = children.get(children.size() - 1);
            for (String group : new String[]{"音频输出", "生成参数", "语言与发音"}) {
                scrollToOption(advanced, group);
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option." + group), false));
                Dialog grouped = children.get(children.size() - 1);
                String[] controls = "音频输出".equals(group) ? new String[]{"生成音频格式（API）", "采样率", "MP3 码率"}
                        : "生成参数".equals(group) ? new String[]{"温度", "Top P", "延迟模式", "分段长度", "质量保护"}
                        : "语言与发音".equals(group) ? new String[]{"文本归一化", "归一化语言", "发音字典（JSON）"}
                        : new String[]{"音色范围", "音色搜索", "刷新音色"};
                for (String control : controls) {
                    scrollToOption(grouped, control);
                    AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + control), false));
                }
                savePreview(grouped, "speech-fish-" + group);
                compose.runOnUiThread(() -> { grouped.dismiss(); return Unit.INSTANCE; });
            }
            compose.runOnIdle(() -> {
                assertEquals("fish-private-key", store.getString(FishSpeechSettings.API_KEY, ""));
                assertEquals("", store.getString(BreezeSpeechSettings.API_KEY, ""));
                assertEquals("", store.getString(SpeechSettings.MINIMAX_API_KEY, ""));
                return Unit.INSTANCE;
            });
        }

        @Test public void fishReferenceAudioAndSpeakerEditorsRemainReachableInTheNarrowMiuixPage() throws Exception {
            MemoryStore store = new MemoryStore();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
            store.putString(FishSpeechSettings.VOICE_MODE, "references");
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog);
                    if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                });
                page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show();
                return Unit.INSTANCE;
            });
            scrollToOption("音色");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色"), false));
            Dialog voice = children.get(children.size() - 1);
            scrollToOption(voice, "音色来源");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.音色来源"), false));
            scrollToOption(voice, "参考音频样本");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.参考音频样本"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("添加参考音频", false, false), false));
            Dialog references = children.get(children.size() - 1);
            savePreview(references, "speech-fish-reference-editor");
            compose.runOnUiThread(() -> { references.dismiss(); return Unit.INSTANCE; });
            scrollToOption(voice, "音色来源");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色来源"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("添加说话人", false, false), false));
            assertEquals("references", store.getString(FishSpeechSettings.VOICE_MODE, ""));
        }

        @Test public void publicFishWindowKeepsItsChoicesVisibleInMiuix() throws Exception {
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(fishPage(100000, true, true, false, fishItem("miuix-public", "公开现成音色")));
                MemoryStore store = new MemoryStore();
                store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
                store.putString(FishSpeechSettings.BASE_URL, server.url("/").toString());
                compose.runOnUiThread(() -> {
                    page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                        children.add(dialog);
                        if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                        dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                    });
                    page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show();
                    return Unit.INSTANCE;
                });
                scrollToOption("音色");
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色"), false));
                Dialog voice = children.get(children.size() - 1);
                scrollToOption(voice, "音色库");
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色库"), false));
                Dialog library = children.get(children.size() - 1);
                scrollToOption(library, "刷新音色");
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.刷新音色"), false));
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(sent); assertNull(sent.getHeader("Authorization"));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("miuix-public") && System.nanoTime() < until) {
                    compose.waitForIdle(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                compose.waitForIdle();
                assertTrue(store.getString(FishSpeechSettings.VOICE_SAMPLE, "").contains("miuix-public"));
                AlertDialog picker = (AlertDialog) children.get(children.size() - 1);
                assertEquals("catalog rows after one page", 2, picker.getListView().getCount());
                ActionsKt.performScrollToIndex(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choices"), false), 1);
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("加载更多", false, false), false));
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
                assertEquals("miuix-public", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
                assertEquals(1, server.getRequestCount());
            }
        }

        @Test public void filteredCatalogHeaderAndLocalSearchRemainUsableInNarrowMiuix() throws Exception {
            try (MockWebServer server = new MockWebServer()) {
                MemoryStore store = new MemoryStore(); store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH);
                store.putString(FishSpeechSettings.BASE_URL, server.url("/").toString());
                String sample = "[{\"id\":\"alice\",\"name\":\"Alice\",\"labels\":[\"中文\",\"女声\"]},{\"id\":\"bob\",\"name\":\"Bob\"}]";
                SpeechSettings settings = new SpeechSettings(new LocalOptions(store.values));
                store.putString(FishSpeechSettings.VOICE_SAMPLE, sample);
                store.putString(VoiceCatalogLabels.FISH_QUERY_KEY, VoiceCatalogLabels.queryKey(settings));
                VoiceNameTranslationCache.merge(store, VoiceNameTranslationCache.scope(settings), VoiceCatalog.parse(sample, "", "", ""), Map.of("alice", "爱丽丝"));
                compose.runOnUiThread(() -> {
                    page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                        children.add(dialog); if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                        dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                    });
                    page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show(); return Unit.INSTANCE;
                });
                scrollToOption("音色"); ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色"), false));
                Dialog voice = children.get(children.size() - 1); scrollToOption(voice, "单音色");
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.单音色"), false));
                AlertDialog picker = (AlertDialog) children.get(children.size() - 1);
                EditText[] original = new EditText[1]; compose.runOnIdle(() -> { original[0] = catalogSearch(picker); return Unit.INSTANCE; });
                for (String tag : new String[]{"voice.catalog.filters", "voice.catalog.search", "voice.catalog.search.all", "voice.catalog.search.clear", "voice.catalog.translate"}) {
                    AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag(tag), false));
                }
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
                compose.runOnUiThread(() -> { original[0].setText("爱丽丝"); return Unit.INSTANCE; });
                compose.waitForIdle();
                compose.runOnIdle(() -> {
                    assertSame(original[0], catalogSearch(picker)); assertEquals(1, picker.getListView().getCount());
                    assertTrue(picker.getListView().getAdapter().getItem(0).toString().contains("爱丽丝（Alice）"));
                    assertEquals(0, server.getRequestCount()); return Unit.INSTANCE;
                });
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.0"), false));
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("voice.catalog.search.clear"), false));
                compose.runOnIdle(() -> { assertSame(original[0], catalogSearch(picker)); assertEquals(3, picker.getListView().getCount()); return Unit.INSTANCE; });
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
                assertEquals("alice", store.getString(FishSpeechSettings.REFERENCE_ID, ""));
                assertEquals("Alice", store.getString(FishSpeechSettings.REFERENCE_NAME, ""));
            }
        }

        @Test public void allBreezeCatalogFiltersAndMultipleSelectionsAreReachableInMiuix() {
            MemoryStore store = new MemoryStore(); store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog); if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.setOwnerActivity(compose.getActivity()); dialog.show();
                });
                page.dialog().setOwnerActivity(compose.getActivity()); page.dialog().show(); return Unit.INSTANCE;
            });
            scrollToOption("Breeze Blue 高级设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.Breeze Blue 高级设置"), false));
            Dialog advanced = children.get(children.size() - 1); scrollToOption(advanced, "音色列表");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色列表"), false));
            Dialog filters = children.get(children.size() - 1);
            for (String title : new String[]{"音色范围", "音色搜索", "音色语言", "音色分类", "音色性别", "音色年龄", "音色语气",
                    "音色口音", "口音标记范围", "音色来源类型", "仅收藏音色", "音色排序", "排序方向", "自定义标签", "刷新音色"}) {
                scrollToOption(filters, title); AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + title), false));
            }
            scrollToOption(filters, "音色性别"); ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色性别"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.0"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choice.1"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            assertEquals(List.of("male", "female"), new SpeechSettings(new LocalOptions(store.values)).breezeSettings.voiceGenders);
        }

        private void verify() throws Exception {
            MemoryStore store = new MemoryStore();
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY);
            store.putString(SpeechSettings.GET_TEMPLATE, "https://example.com/tts?ID=example-user-key&body={text}&speaker={voice}");
            compose.runOnUiThread(() -> {
                page = new SpeechSettingsDialog(compose.getActivity(), store, true, (dialog, cleanup) -> {
                    children.add(dialog);
                    dialog.setOwnerActivity(compose.getActivity());
                    if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
                    dialog.show();
                });
                page.dialog().show();
                return Unit.INSTANCE;
            });
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.合成 GET"), false));
            savePreview(page.dialog(), "speech-settings");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.合成参数"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasContentDescription("添加参数", false, false), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("speech.parameter.name.0"), false));
            AssertionsKt.assertIsDisplayed(compose.onAllNodes(FiltersKt.hasContentDescription("移除参数", false, false), false).get(0));
            savePreview(children.get(children.size() - 1), "speech-parameters");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("音色高级设置");
            savePreview(page.dialog(), "speech-settings-voices");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.音色高级设置"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色高级设置"), false));
            scrollToOption(children.get(children.size() - 1), "音色列表请求头");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.音色列表请求头"), false));
            savePreview(children.get(children.size() - 1), "speech-voice-advanced");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                store.putString("tts_synthesis_sample", "{\"data\":{\"audio\":\"https://example.com/sound.mp3\"}}");
                return Unit.INSTANCE;
            });
            scrollToOption("接口设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.接口设置"), false));
            Dialog thirdInterface = children.get(children.size() - 1);
            scrollToOption(thirdInterface, "音频地址字段");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音频地址字段"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("data.audio", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-audio-fields");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                thirdInterface.dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("语音来源");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.语音来源"), false));
            compose.waitForIdle();
            ActionsKt.performClick(compose.onNode(FiltersKt.hasText("MiniMax", false, false), false));
            compose.waitForIdle();
            assertEquals(SpeechSettings.MINIMAX, store.getString(SpeechSettings.PROVIDER, ""));
            scrollToOption("模型");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option.模型"), false));
            savePreview(page.dialog(), "speech-minimax-settings");
            compose.runOnIdle(() -> {
                View main = page.dialog().getWindow().getDecorView();
                assertNull(main.findViewWithTag("settings.option.刷新模型"));
                assertNull(main.findViewWithTag("settings.option.刷新官方模型列表"));
                assertNull(main.findViewWithTag("settings.option.刷新音色"));
                return Unit.INSTANCE;
            });
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.模型"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("手动填写", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-model-list");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            compose.waitForIdle();
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("当前接口模型（使用 API Key）", false, false), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("MiniMax 官方目录（无需 Key）", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-model-refresh-sources");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("音色");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.音色"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.button.-1"), false));
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasText("手动填写", false, false), false));
            savePreview(children.get(children.size() - 1), "speech-minimax-voice-list");
            compose.runOnUiThread(() -> {
                children.get(children.size() - 1).dismiss();
                return Unit.INSTANCE;
            });
            scrollToOption("MiniMax 高级设置");
            verifySimpleSection("接口设置", new String[]{"API Key", "API 地址"}, "speech-minimax-interface");
            verifySimpleSection("朗读设置", new String[]{"语速", "音量", "音调"}, "speech-minimax-reading");
            scrollToOption("MiniMax 高级设置");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.MiniMax 高级设置"), false));
            compose.waitForIdle();
            Dialog minimaxAdvanced = children.get(children.size() - 1);
            savePreview(minimaxAdvanced, "speech-minimax-advanced");
            for (String section : new String[]{"音频输出", "语言与发音", "混音与声音效果"}) {
                scrollToOption(minimaxAdvanced, section);
                ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option." + section), false));
                compose.waitForIdle();
                Dialog grouped = children.get(children.size() - 1);
                String[] controls = "音频输出".equals(section)
                        ? new String[]{"生成音频格式（API）", "返回方式（传输）", "采样率", "MP3 码率", "声道", "AIGC 水印"}
                        : "语言与发音".equals(section)
                        ? new String[]{"情绪", "语言增强", "文本归一化", "发音字典（每行 原文/替换内容）", "LaTeX 朗读"}
                        : new String[]{"混合音色（每行 voice_id/weight）", "音高调整（-100 至 100）", "音强调整（-100 至 100）", "音色调整（-100 至 100）", "声音效果", "重置音色调整"};
                for (String control : controls) {
                    scrollToOption(grouped, control);
                    AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + control), false));
                }
                savePreview(grouped, "speech-minimax-" + section);
                if ("音频输出".equals(section)) {
                    scrollToOption(grouped, "生成音频格式（API）");
                    ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.生成音频格式（API）"), false));
                    ActionsKt.performScrollToIndex(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choices"), false), 5);
                    ActionsKt.performClick(compose.onNode(FiltersKt.hasText("AAC（实验，接口可能拒绝）", false, false), false));
                    compose.waitForIdle();
                    assertEquals("aac", store.getString(MiniMaxSpeechOptions.AUDIO_FORMAT, ""));
                    compose.runOnIdle(() -> {
                        View audio = grouped.getWindow().getDecorView();
                        assertNotNull(audio.findViewWithTag("speech.minimax.aac.hint"));
                        assertNull(audio.findViewWithTag("settings.option.MP3 码率"));
                        return Unit.INSTANCE;
                    });
                    scrollToOption(grouped, "生成音频格式（API）");
                    ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option.生成音频格式（API）"), false));
                    ActionsKt.performScrollToIndex(compose.onNode(FiltersKt.hasTestTag("miuix.dialog.choices"), false), 0);
                    ActionsKt.performClick(compose.onNode(FiltersKt.hasText("MP3", false, false), false));
                    compose.waitForIdle();
                }
                compose.runOnUiThread(() -> { grouped.dismiss(); return Unit.INSTANCE; });
            }
        }

        private void verifySimpleSection(String section, String[] controls, String screenshot) throws Exception {
            scrollToOption(section);
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("settings.option." + section), false));
            Dialog grouped = children.get(children.size() - 1);
            for (String control : controls) {
                scrollToOption(grouped, control);
                AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("settings.option." + control), false));
            }
            savePreview(grouped, screenshot);
            compose.runOnUiThread(() -> { grouped.dismiss(); return Unit.INSTANCE; });
        }

        private void scrollToOption(String title) {
            scrollToOption(page.dialog(), title);
        }

        private void scrollToOption(Dialog dialog, String title) {
            // Dynamic provider changes replace the native form inside AndroidView. Let that layout
            // settle before reading its row positions or inspecting a newly opened child dialog.
            compose.waitForIdle();
            compose.runOnUiThread(() -> {
                View row = dialog.getWindow().getDecorView().findViewWithTag("settings.option." + title);
                assertNotNull("Dialog must contain the requested row before scrolling: " + title, row);
                android.view.ViewParent parent = row.getParent();
                while (!(parent instanceof android.widget.ScrollView)) parent = parent.getParent();
                ((android.widget.ScrollView) parent).scrollTo(0, row.getTop());
                return Unit.INSTANCE;
            });
            compose.waitForIdle();
        }

        private void savePreview(Dialog dialog, String name) throws Exception {
            compose.waitForIdle();
            String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
            if (location == null) return;
            compose.runOnUiThread(() -> {
                View decor = dialog.getWindow().getDecorView();
                android.view.WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
                int width = bounds.width > 0 ? bounds.width : decor.getWidth();
                int height = bounds.height > 0 ? bounds.height : decor.getHeight();
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                decor.layout(0, 0, width, height);
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(bitmap));
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                HashSet<Integer> colors = new HashSet<>();
                for (int pixel : pixels) colors.add(pixel);
                assertTrue("Miuix speech settings should render controls", colors.size() > 8);
                File directory = new File(location);
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File output = new File(directory, "miuix-" + name + "-" + width + "x" + height + ".png");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                } catch (Exception failure) { throw new AssertionError(failure); }
                bitmap.recycle();
                return Unit.INSTANCE;
            });
        }
    }
}
