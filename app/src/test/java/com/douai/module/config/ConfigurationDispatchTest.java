package com.douai.module.config;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.douai.module.DouAiModule;
import com.douai.module.feature.AutoCommentHook;
import com.douai.module.feature.AutoCommentHookTest;
import com.douai.module.feature.AutomationDispatcher;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.FollowListPager;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.PlaybackBehaviorHooks;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigurationDispatchTest {
    @Test public void changingDownloadMethodAndParserOptionsUpdatesBothSaveEntrypoints() throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        Constructor<DownloadActionHook> constructor = DownloadActionHook.class.getDeclaredConstructor(
                Method.class, FeatureSettings.class, Consumer.class);
        constructor.setAccessible(true);
        try (DownloadActionHook download = constructor.newInstance(null, FeatureSettings.defaults(),
                (Consumer<String>) ignored -> { });
             FeedGestureToolsHook gesture = gesture(new LocalOptions(values))) {
            setField(module, "downloadActionHook", download);
            setField(module, "feedGestureToolsHook", gesture);
            update(module, values);
            for (String key : new String[]{ThirdPartyDownloadSettings.METHOD,
                    ThirdPartyDownloadSettings.GET_TEMPLATE, ThirdPartyDownloadSettings.APP_SECRET,
                    ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_HEADER,
                    ThirdPartyDownloadSettings.DOWNLOAD_FIELDS, ThirdPartyDownloadSettings.NAMING_FIELDS,
                    LocalOptions.DOWNLOAD_SAVE_TREE}) {
                LocalOptions previous = options(download);
                values.put(key, "changed");
                update(module, values);
                LocalOptions current = (LocalOptions) field(module, "localOptions");
                assertNotSame(previous, current);
                assertSame(key, current, options(download));
                assertSame(key, current, options(gesture));
            }
        }
    }

    @Test public void changingOnlyPlaybackSpeedUpdatesPlaybackAndGestureSnapshots() throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("playback_speed", "2");
        LocalOptions initial = new LocalOptions(values);
        try (FeedGestureToolsHook gesture = gesture(initial);
             PlaybackBehaviorHooks playback = playback(initial)) {
            setField(module, "feedGestureToolsHook", gesture);
            setField(module, "playbackBehaviorHooks", playback);
            update(module, values);
            LocalOptions previous = options(gesture);
            assertSame(previous, options(playback));

            values.put("playback_speed", "3");
            update(module, values);
            LocalOptions updated = (LocalOptions) field(module, "localOptions");
            assertNotSame(previous, updated);
            assertSame(updated, options(gesture));
            assertSame(updated, options(playback));
            assertEquals(3f, options(gesture).decimal("playback_speed", 2f), 0f);
            assertEquals(3f, options(playback).decimal("playback_speed", 2f), 0f);

            values.put("reply_tip", true);
            update(module, values);
            assertSame(updated, options(gesture));
            assertSame(updated, options(playback));
        } finally {
            ShadowLooper.idleMainLooper();
        }
    }

    @Test public void changingOnlyReplyScopeModeUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_scope_mode", "all", "whitelist");
    }

    @Test public void changingOnlyCommentScopeModeUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("comment_scope_mode", "all", "blacklist");
    }

    @Test public void changingOnlyPrivateChatParticipationUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_private_chats", false, true);
    }

    @Test public void changingOnlyLegacyReplyGlobalUpdatesRelationshipSnapshot() throws Exception {
        assertRelationshipDispatch("reply_global", true, false);
    }

    @Test public void changingOnlyReplyDailyLimitUpdatesReplyAndDispatcherSnapshots() throws Exception {
        assertAutomationLimitDispatch("reply_daily_limit");
    }

    @Test public void changingOnlyReplyCooldownUpdatesReplyAndDispatcherSnapshots() throws Exception {
        assertAutomationLimitDispatch("reply_user_cooldown_minutes");
    }

    @Test public void changingOnlyCommentDailyLimitUpdatesCommentAndDispatcherSnapshots() throws Exception {
        assertAutomationLimitDispatch("comment_daily_limit");
    }

    @Test public void changingOnlyCommentCooldownUpdatesCommentAndDispatcherSnapshots() throws Exception {
        assertAutomationLimitDispatch("comment_user_cooldown_minutes");
    }

    @Test public void changingOnlyReplyModelUpdatesReplyAndDispatcherButKeepsCommentSnapshot() throws Exception {
        assertAutomationSettingDispatch(AiModelSettings.REPLY_MODEL_ID, "selected-reply-profile");
    }

    @Test public void changingOnlyCommentModelUpdatesCommentAndDispatcherButKeepsReplySnapshot() throws Exception {
        assertAutomationSettingDispatch(AiModelSettings.COMMENT_MODEL_ID, "selected-comment-profile");
    }

    @Test public void voiceProviderConfigurationUpdatesBothAutomationSnapshots() throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        LocalOptions initial = new LocalOptions(values);
        try (AutomationDispatcher dispatcher = new AutomationDispatcher(RuntimeEnvironment.getApplication(), ignored -> { });
             NativeMessagingHooks reply = messaging(initial, dispatcher);
             AutoCommentHook comment = comments(initial, dispatcher)) {
            setField(module, "automationDispatcher", dispatcher);
            setField(module, "nativeMessagingHooks", reply);
            setField(module, "autoCommentDraftHook", comment);
            update(module, values);
            for (String key : new String[]{SpeechSettings.PROVIDER, SpeechSettings.GET_TEMPLATE,
                    SpeechSettings.SELECTED_VOICE, SpeechSettings.HEADERS, SpeechSettings.AUDIO_FIELD}) {
                values.put(key, "changed");
                update(module, values);
                LocalOptions current = (LocalOptions) field(module, "localOptions");
                assertSame(key, current, options(reply));
                assertSame(key, current, options(comment));
                assertSame(key, current, options(dispatcher));
            }
            for (String key : new String[]{LocalOptions.REPLY_VOICE, LocalOptions.COMMENT_VOICE}) {
                Object selected = LocalOptions.REPLY_VOICE.equals(key) ? reply : comment;
                values.put(key, true);
                update(module, values);
                LocalOptions current = (LocalOptions) field(module, "localOptions");
                assertSame(key, current, options(selected));
                assertTrue(options(selected).enabled(key));
            }
        } finally { ShadowLooper.idleMainLooper(); }
    }

    private static void assertAutomationLimitDispatch(String key) throws Exception {
        assertAutomationSettingDispatch(key, "12");
    }

    private static void assertAutomationSettingDispatch(String key, String value) throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("reply_daily_limit", "0");
        values.put("reply_user_cooldown_minutes", "0");
        values.put("comment_daily_limit", "0");
        values.put("comment_user_cooldown_minutes", "0");
        values.put("auto_message_text", "reply");
        values.put("auto_comment_text", "comment");
        LocalOptions initial = new LocalOptions(values);
        try (AutomationDispatcher dispatcher = new AutomationDispatcher(RuntimeEnvironment.getApplication(), ignored -> { });
             NativeMessagingHooks reply = messaging(initial, dispatcher);
             AutoCommentHook comment = comments(initial, dispatcher)) {
            setField(module, "automationDispatcher", dispatcher);
            setField(module, "nativeMessagingHooks", reply);
            setField(module, "autoCommentDraftHook", comment);
            update(module, values);
            LocalOptions previous = options(dispatcher);
            assertSame(field(module, "localOptions"), previous);
            assertSame(previous, options(reply));
            assertSame(previous, options(comment));

            values.put(key, value);
            update(module, values);
            LocalOptions updated = (LocalOptions) field(module, "localOptions");
            Object selected = key.startsWith("reply_") ? reply : comment;
            Object other = key.startsWith("reply_") ? comment : reply;
            assertNotSame(previous, updated);
            assertSame(key, updated, options(selected));
            assertSame(key, updated, options(dispatcher));
            assertSame(key, previous, options(other));
            assertEquals(value, options(selected).text(key, ""));
            assertEquals(value, options(dispatcher).text(key, ""));
            assertEquals("reply", options(selected).text("auto_message_text", ""));
            assertEquals("comment", options(selected).text("auto_comment_text", ""));

            values.put("playback_speed", "3");
            update(module, values);
            assertSame(updated, options(selected));
            assertSame(updated, options(dispatcher));
            assertSame(previous, options(other));
        } finally {
            ShadowLooper.idleMainLooper();
        }
    }

    private static void assertRelationshipDispatch(String key, Object before, Object after) throws Exception {
        DouAiModule module = module();
        Map<String, Object> values = new HashMap<>();
        values.put("reply_scope_relations", "following");
        values.put("comment_scope_relations", "followers");
        values.put(key, before);
        try (NativeFollowLists relationships = relationships()) {
            setField(module, "nativeFollowLists", relationships);
            update(module, values);
            LocalOptions previous = options(relationships);
            assertSame(field(module, "localOptions"), previous);

            values.put(key, after);
            update(module, values);
            LocalOptions updated = (LocalOptions) field(module, "localOptions");
            assertNotSame(previous, updated);
            assertSame(key, updated, options(relationships));
            if (after instanceof Boolean) assertEquals(after, options(relationships).enabled(key));
            else assertEquals(after, options(relationships).text(key, ""));
            assertEquals("following", options(relationships).text("reply_scope_relations", ""));
            assertEquals("followers", options(relationships).text("comment_scope_relations", ""));

            values.put("playback_speed", "3");
            update(module, values);
            assertSame(updated, options(relationships));
        }
    }

    private static FeedGestureToolsHook gesture(LocalOptions options) throws Exception {
        Constructor<FeedGestureToolsHook> constructor = FeedGestureToolsHook.class.getDeclaredConstructor(
                Application.class, LocalOptions.class, Consumer.class, Method.class,
                Field.class, Field.class, Field.class, Class.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), options,
                (Consumer<String>) ignored -> { }, null, null, null, null, null);
    }

    private static PlaybackBehaviorHooks playback(LocalOptions options) throws Exception {
        Constructor<PlaybackBehaviorHooks> constructor = PlaybackBehaviorHooks.class.getDeclaredConstructor(
                LocalOptions.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(options, (Consumer<String>) ignored -> { });
    }

    private static NativeMessagingHooks messaging(LocalOptions options, AutomationDispatcher dispatcher) throws Exception {
        Constructor<NativeMessagingHooks> constructor = NativeMessagingHooks.class.getDeclaredConstructor(
                Context.class, LocalOptions.class, Consumer.class, AutomationDispatcher.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication(), options,
                (Consumer<String>) ignored -> { }, dispatcher);
    }

    private static AutoCommentHook comments(LocalOptions options, AutomationDispatcher dispatcher) throws Exception {
        Method report = AutoCommentHookTest.class.getDeclaredMethod("report");
        report.setAccessible(true);
        Class<?> frameworkType = Class.forName(AutoCommentHookTest.class.getName() + "$Framework");
        Constructor<?> constructor = frameworkType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object framework = constructor.newInstance();
        return AutoCommentHook.install((XposedInterface) field(framework, "api"),
                (HostScanReport) report.invoke(null), AutoCommentHookTest.class.getClassLoader(),
                RuntimeEnvironment.getApplication(), options, dispatcher, ignored -> { });
    }

    private static NativeFollowLists relationships() throws Exception {
        Class<?> accountSource = Class.forName(NativeFollowLists.class.getName() + "$AccountSource");
        Object accounts = Proxy.newProxyInstance(accountSource.getClassLoader(), new Class<?>[]{accountSource},
                (proxy, method, arguments) -> { throw new AssertionError("Unexpected account lookup"); });
        FollowListPager.Fetcher fetcher = (kind, query) -> { throw new AssertionError("Unexpected host fetch"); };
        Constructor<NativeFollowLists> constructor = NativeFollowLists.class.getDeclaredConstructor(
                accountSource, FollowListPager.Fetcher.class, Consumer.class);
        constructor.setAccessible(true);
        NativeFollowLists relationships = constructor.newInstance(accounts, fetcher,
                (Consumer<String>) ignored -> { });
        ScheduledThreadPoolExecutor original = (ScheduledThreadPoolExecutor) field(relationships, "worker");
        original.shutdownNow();
        // Exercise real setOptions dispatch without starting host account or network work.
        setField(relationships, "worker", new ScheduledThreadPoolExecutor(1) {
            @Override public void execute(Runnable command) { }
        });
        return relationships;
    }

    private static DouAiModule module() {
        DouAiModule module = new DouAiModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, arguments) -> null);
        module.attachFramework(framework, () -> { });
        return module;
    }

    private static void update(DouAiModule module, Map<String, Object> values) throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) return new HashMap<>(values);
                    throw new AssertionError("Live getter used after capture: " + method.getName());
                });
        Method update = DouAiModule.class.getDeclaredMethod("updateConfiguration", SharedPreferences.class);
        update.setAccessible(true);
        update.invoke(module, preferences);
        // Configuration now acknowledges real main continuations, rather than the initial setter enqueue.
        for (int pass = 0; pass < 3; pass++) {
            ((com.douai.module.scan.HostWorkQueue) field(module, "hostWorkQueue")).submit(() -> { })
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private static LocalOptions options(Object target) throws Exception {
        return (LocalOptions) field(target, "options");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
