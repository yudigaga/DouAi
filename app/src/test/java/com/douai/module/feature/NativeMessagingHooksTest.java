package com.douai.module.feature;

import android.content.ContentValues;
import android.content.Context;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeMessagingHooksTest {
    public static final class Sdk { public long getUid() { return 100; } }
    public static final class Database {
        public Sdk imSdkContext = new Sdk();
        ContentValues received;
        public int update(String table, ContentValues values, String where, String[] arguments) {
            received = values;
            return 1;
        }
        public Boolean local(Message message) { message.ext.put("s:is_recalled", "true"); return true; }
    }
    public static final class Message {
        public ConcurrentHashMap<String, String> ext = new ConcurrentHashMap<>();
        public String getUuid() { return "fixture-uuid"; }
        public ConcurrentHashMap<String, String> getExt() { return ext; }
        public boolean isRecalled() { return "true".equals(ext.get("s:is_recalled")); }
    }

    static final class Framework {
        final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
        final AtomicInteger revoked = new AtomicInteger();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> {
                                            if ("unhook".equals(action.getName())) revoked.incrementAndGet();
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object receiver, Object... input) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed":
                                Object[] selected = arguments == null || arguments.length == 0 ? input : (Object[]) arguments[0];
                                return target.invoke(receiver, selected);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooks.get(target).intercept(chain);
        }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "adv_im_db_update", Database.class.getMethod("update", String.class, ContentValues.class, String.class, String[].class));
        add(rules, "adv_im_uid", Sdk.class.getMethod("getUid"));
        add(rules, "adv_im_message_recalled", Message.class.getMethod("isRecalled"));
        add(rules, "adv_im_message_ext", Message.class.getMethod("getExt"));
        add(rules, "adv_im_message_uuid", Message.class.getMethod("getUuid"));
        add(rules, "adv_im_local_recall", Database.class.getMethod("local", Message.class));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "message", Collections.singletonList(new HostCandidate("fixture.apk",
                "classes.dex", HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    private static LocalOptions options(boolean self, boolean other) {
        Map<String, Object> values = new HashMap<>();
        values.put("prevent_message_recall", true);
        values.put("recall_self", self);
        values.put("recall_other", other);
        return new LocalOptions(values);
    }

    @Test public void databaseAndLiveModelAreBothProtectedAndPartialUpdateGetsAMarker() throws Throwable {
        Framework framework = new Framework();
        Context context = RuntimeEnvironment.getApplication();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                context, options(true, true), ignored -> { });
        try {
            Database database = new Database();
            ContentValues values = new ContentValues();
            values.put("ext", "{\"s:is_recalled\":\"true\",\"s:recall_uid\":\"200\"}");
            values.put("content", "撤回提示");
            Method update = Database.class.getMethod("update", String.class, ContentValues.class, String.class, String[].class);
            assertEquals(1, framework.invoke(update, database, "msg", values, "msg_uuid=?", new String[]{"fixture-uuid"}));
            assertNotSame(values, database.received);
            assertFalse(database.received.containsKey("content"));
            assertTrue(context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE)
                    .getBoolean("recall.100.fixture-uuid", false));
            Message message = new Message();
            message.ext.put("s:is_recalled", "true");
            message.ext.put("s:recall_uid", "200");
            assertEquals(false, framework.invoke(Message.class.getMethod("isRecalled"), message));
            assertEquals("true", message.ext.get("s:is_recalled"));
            hook.setOptions(new LocalOptions(Collections.emptyMap()));
            assertEquals(true, framework.invoke(Message.class.getMethod("isRecalled"), message));
        } finally { hook.close(); }
        assertEquals(3, framework.revoked.get());
    }

    @Test public void localOwnRecallWithoutActorDoesNotUseTheOtherUsersSwitch() throws Throwable {
        Framework framework = new Framework();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                RuntimeEnvironment.getApplication(), options(false, true), ignored -> { });
        try {
            Database database = new Database();
            Message message = new Message();
            framework.invoke(Database.class.getMethod("local", Message.class), database, message);
            assertEquals(true, framework.invoke(Message.class.getMethod("isRecalled"), message));
            hook.setOptions(options(true, false));
            assertEquals(false, framework.invoke(Message.class.getMethod("isRecalled"), message));
        } finally { hook.close(); }
    }

    @Test public void handledStreakResetDoesNotClearNewRecordsAfterProcessRestart() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences records = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        LocalOptions reset = new LocalOptions(Collections.singletonMap("streak_reset_token", "once"));
        Framework first = new Framework();
        NativeMessagingHooks initial = NativeMessagingHooks.install(first.api, report(), getClass().getClassLoader(),
                context, reset, ignored -> { });
        records.edit().putBoolean("streak.100.2026-10-10.200", true).apply();
        initial.close();
        NativeMessagingHooks restarted = NativeMessagingHooks.install(new Framework().api, report(), getClass().getClassLoader(),
                context, reset, ignored -> { });
        try {
            restarted.setOptions(reset);
            assertTrue(records.getBoolean("streak.100.2026-10-10.200", false));
            assertEquals("once", records.getString("streak.reset_token", ""));
            restarted.setOptions(new LocalOptions(Collections.singletonMap("streak_reset_token", "again")));
            assertFalse(records.contains("streak.100.2026-10-10.200"));
            assertEquals("again", records.getString("streak.reset_token", ""));
        } finally { restarted.close(); }
    }

    @Test public void hookExposesCountOfLegacyPendingStreakOutcomes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences records = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        records.edit().clear().putBoolean("streak.100.2026-10-12.200.pending", true).commit();
        NativeMessagingHooks hook = NativeMessagingHooks.install(new Framework().api, report(), getClass().getClassLoader(),
                context, new LocalOptions(Collections.emptyMap()), ignored -> { });
        try {
            assertEquals(1, hook.unknownStreakCount());
            assertFalse(records.contains("streak.100.2026-10-12.200.pending"));
        } finally { hook.close(); }
    }

    @Test public void changingEitherSharedMediaOptionInvalidatesRepliesButExplicitImageDefaultDoesNot() throws Exception {
        Method changed = NativeMessagingHooks.class.getDeclaredMethod("automationOptionsChanged", LocalOptions.class, LocalOptions.class);
        changed.setAccessible(true);
        LocalOptions defaults = new LocalOptions(Collections.emptyMap());
        assertEquals(false, changed.invoke(null, defaults,
                new LocalOptions(Collections.singletonMap(LocalOptions.REPLY_RECOGNIZE_IMAGES, true))));
        assertEquals(true, changed.invoke(null, defaults,
                new LocalOptions(Collections.singletonMap(LocalOptions.REPLY_RECOGNIZE_IMAGES, false))));
        assertEquals(true, changed.invoke(null, defaults,
                new LocalOptions(Collections.singletonMap(LocalOptions.REPLY_RECOGNIZE_VIDEO, true))));
    }

    @Test public void globalAndOtherFunctionModelChangesDoNotInvalidatePinnedReplies() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer global = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer replies = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer comments = new okhttp3.mockwebserver.MockWebServer()) {
            com.douai.module.config.AiModelSettings profiles = AutomationModelFixtures.profiles(global, replies, comments);
            Map<String, Object> values = new HashMap<>();
            values.put(com.douai.module.config.AiModelSettings.KEY, profiles.serialize());
            values.put(com.douai.module.config.AiModelSettings.REPLY_MODEL_ID, "reply");
            LocalOptions before = new LocalOptions(values);
            Method changed = NativeMessagingHooks.class.getDeclaredMethod("automationOptionsChanged", LocalOptions.class, LocalOptions.class);
            changed.setAccessible(true);
            values.put(com.douai.module.config.AiModelSettings.KEY, profiles.activate("comment").serialize());
            values.put(com.douai.module.config.AiModelSettings.COMMENT_MODEL_ID, "global");
            assertEquals(false, changed.invoke(null, before, new LocalOptions(values)));
            values.put(com.douai.module.config.AiModelSettings.REPLY_MODEL_ID, "global");
            assertEquals(true, changed.invoke(null, before, new LocalOptions(values)));
        }
    }

    @Test public void disabledStreakDoesNotEnumerateConversationsForItsFormerContactSideEffect() throws Exception {
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()));
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        Method tick = NativeMessagingHooks.class.getDeclaredMethod("streakTick");
        tick.setAccessible(true);
        try {
            for (int index = 0; index < 20; index++) tick.invoke(hook);
            assertEquals("Disabled streaks must perform zero full conversation scans", 0, sdk.model.reads);
        } finally { hook.close(); }
    }

    @Test public void streakTimerStopsOnDisableAndInvalidWindowAndRestartsOnEnable() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("streak_targets", "200");
        try {
            hook.setOptions(new LocalOptions(values));
            timer.advance(10 * 60_000);
            assertEquals(0, sdk.model.reads);
            values.put("streak_enabled", true);
            hook.setOptions(new LocalOptions(values));
            timer.advance(0);
            assertEquals(1, sdk.model.reads);
            values.put("streak_enabled", false);
            hook.setOptions(new LocalOptions(values));
            timer.advance(10 * 60_000);
            assertEquals(1, sdk.model.reads);
            values.put("streak_enabled", true);
            values.put("streak_start", "bad");
            values.put("streak_end", "06:00");
            hook.setOptions(new LocalOptions(values));
            timer.advance(10 * 60_000);
            assertEquals(1, sdk.model.reads);
        } finally { hook.close(); }
    }

    @Test public void streakWindowTimerUnderstandsDaytimeOvernightAndInvalidConfiguration() {
        java.time.ZonedDateTime noon = java.time.ZonedDateTime.parse("2026-10-25T12:00:00+08:00[Asia/Shanghai]");
        Map<String, Object> values = new HashMap<>();
        values.put("streak_start", "23:00"); values.put("streak_end", "06:00");
        assertEquals(11 * 60 * 60_000L, NativeMessagingHooks.nextStreakWindowDelay(new LocalOptions(values), noon));
        assertEquals(0, NativeMessagingHooks.nextStreakWindowDelay(new LocalOptions(values), noon.withHour(1)));
        values.put("streak_start", "09:00"); values.put("streak_end", "10:00");
        assertEquals(21 * 60 * 60_000L, NativeMessagingHooks.nextStreakWindowDelay(new LocalOptions(values), noon));
        values.put("streak_start", "bad");
        assertEquals(-1, NativeMessagingHooks.nextStreakWindowDelay(new LocalOptions(values), noon));
    }

    @Test public void contactsLoadOncePerDirtySnapshotAndGroupMembersAreOnlyLoadedOnRequest() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        Conversation group = new Conversation(false, "group", "群聊");
        sdk.model.rows.add(new Conversation(true, "thread", "Alice"));
        sdk.model.rows.add(group);
        List<Boolean> completions = new ArrayList<>();
        try {
            NativeMessagingHooks.loadContacts(false, completions::add);
            NativeMessagingHooks.loadContacts(false, completions::add);
            assertEquals(0, sdk.model.reads);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList(true, true), completions);
            assertEquals(1, sdk.model.reads);
            assertEquals(0, group.memberReads);
            assertEquals("Alice", NativeMessagingHooks.availableContacts().get("200"));
            NativeMessagingHooks.loadContacts(false, completions::add);
            assertEquals(3, completions.size());
            assertEquals(1, sdk.model.reads);

            NativeMessagingHooks.loadContacts(true, completions::add);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(1, group.memberReads);
            assertEquals("群友", NativeMessagingHooks.availableGroupMembers().get("300").name);
            Method dirty = NativeMessagingHooks.class.getDeclaredMethod("markContactsDirty"); dirty.setAccessible(true);
            for (int index = 0; index < 50; index++) dirty.invoke(hook);
            timer.advance(10 * 60_000);
            assertEquals("Marking dirty must not cause full scans for every incoming message", 2, sdk.model.reads);
            NativeMessagingHooks.loadContacts(false, completions::add);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(3, sdk.model.reads);
            assertEquals(1, group.memberReads);
        } finally { hook.close(); }
    }

    @Test public void cancelledContactConsumerDoesNoWorkAndAccountSwitchCannotDeliverOldSelection() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        List<Boolean> completions = new ArrayList<>();
        try {
            NativeMessagingHooks.loadContacts(true, completions::add).close();
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(0, sdk.model.reads);
            assertTrue(completions.isEmpty());
            NativeMessagingHooks.loadContacts(false, completions::add);
            sdk.uid = 101;
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList(false), completions);
            assertTrue(NativeMessagingHooks.availableContacts().isEmpty());
        } finally { hook.close(); }
    }

    @Test public void blockingContactEnumerationCannotDelayTheIndependentStreakWorkerOrDeliverAfterClose() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true); values.put("streak_enabled", true); values.put("streak_targets", "200");
        NativeMessagingHooks hook = enumerationFields(new NativeMessagingHooks(RuntimeEnvironment.getApplication(),
                new LocalOptions(values), ignored -> { }, null, new java.util.concurrent.ScheduledThreadPoolExecutor(1)));
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch streakRan = new java.util.concurrent.CountDownLatch(1);
        List<Boolean> completed = new ArrayList<>();
        sdk.model.beforeAll = () -> {
            if (Thread.currentThread().getName().equals("DouAi-contact-snapshot")) {
                entered.countDown();
                try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else streakRan.countDown();
        };
        try {
            NativeMessagingHooks.loadContacts(true, completed::add);
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            Method tick = NativeMessagingHooks.class.getDeclaredMethod("streakTick"); tick.setAccessible(true);
            java.util.concurrent.ScheduledThreadPoolExecutor streak = (java.util.concurrent.ScheduledThreadPoolExecutor)
                    HostTargets.read(hook, "streakWorker");
            streak.submit(() -> { try { tick.invoke(hook); } catch (Exception failure) { throw new AssertionError(failure); } })
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("Streaks must enumerate even while the UI snapshot lane is blocked", 0, streakRan.getCount());
            hook.close();
            release.countDown();
            java.util.concurrent.ExecutorService contacts = (java.util.concurrent.ExecutorService) HostTargets.read(hook, "contactsWorker");
            assertTrue(contacts.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue("Closing the hook must detach consumers from an in-flight contact snapshot", completed.isEmpty());
        } finally { release.countDown(); hook.close(); }
    }

    @Test public void conversationChangesDuringEnumerationCannotPublishTheStaleSnapshot() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        sdk.model.rows.add(new Conversation(true, "thread", "Alice"));
        List<Boolean> completed = new ArrayList<>();
        Method dirty = NativeMessagingHooks.class.getDeclaredMethod("markContactsDirty"); dirty.setAccessible(true);
        sdk.model.beforeAll = () -> { try { dirty.invoke(hook); } catch (Exception failure) { throw new AssertionError(failure); } };
        try {
            NativeMessagingHooks.loadContacts(false, completed::add);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList(false), completed);
            assertTrue(NativeMessagingHooks.availableContacts().isEmpty());
            sdk.model.beforeAll = () -> { };
            NativeMessagingHooks.loadContacts(false, completed::add);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList(false, true), completed);
            assertEquals("Alice", NativeMessagingHooks.availableContacts().get("200"));
        } finally { hook.close(); }
    }

    @Test public void relationshipAccountAbaInvalidatesQueuedRunningAndMainThreadContactResults() throws Exception {
        for (String stage : Arrays.asList("queued", "running", "main_callback")) {
            FollowListSnapshot.activateAccount("100");
            NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
            NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
            EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
            sdk.model.rows.add(new Conversation(true, "thread", "Alice"));
            List<Boolean> completed = new ArrayList<>();
            try {
                NativeMessagingHooks.loadContacts(false, completed::add);
                if (stage.equals("queued")) switchRelationshipAwayAndBack();
                if (stage.equals("running")) sdk.model.beforeAll = NativeMessagingHooksTest::switchRelationshipAwayAndBack;
                timer.advance(0);
                if (stage.equals("main_callback")) switchRelationshipAwayAndBack();
                org.robolectric.shadows.ShadowLooper.idleMainLooper();
                assertEquals(stage, Collections.singletonList(false), completed);
                assertTrue(stage, NativeMessagingHooks.availableContacts().isEmpty());
            } finally { hook.close(); FollowListSnapshot.reset(); }
        }
    }

    @Test public void relationshipAccountAbaCannotReuseAnOldCachedContactSnapshot() throws Exception {
        FollowListSnapshot.activateAccount("100");
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        sdk.model.rows.add(new Conversation(true, "thread", "Alice"));
        List<Boolean> completed = new ArrayList<>();
        try {
            NativeMessagingHooks.loadContacts(false, completed::add);
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList(true), completed);
            switchRelationshipAwayAndBack();
            assertTrue(NativeMessagingHooks.availableContacts().isEmpty());
            NativeMessagingHooks.loadContacts(false, completed::add);
            assertEquals("The previous A session must not be returned as a cache hit", 1, completed.size());
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList(true, true), completed);
            assertEquals(2, sdk.model.reads);
        } finally { hook.close(); FollowListSnapshot.reset(); }
    }

    private static void switchRelationshipAwayAndBack() {
        FollowListSnapshot.activateAccount("101");
        FollowListSnapshot.activateAccount("100");
    }

    @Test public void timeAndTimezoneBroadcastsReplanTheNextWindowWithoutRepeatingADurableSend() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(hook, "sdk");
        java.util.concurrent.atomic.AtomicReference<java.time.ZonedDateTime> wall = new java.util.concurrent.atomic.AtomicReference<>(
                java.time.ZonedDateTime.parse("2026-10-25T08:00:00+08:00[Asia/Shanghai]"));
        hook.streakTime = wall::get;
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true); values.put("streak_enabled", true); values.put("streak_targets", "200");
        values.put("streak_start", "09:00"); values.put("streak_end", "10:00");
        try {
            hook.setOptions(new LocalOptions(values));
            assertEquals(60 * 60_000L, streakDelay(hook));
            wall.set(wall.get().withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")));
            broadcastTime(android.content.Intent.ACTION_TIMEZONE_CHANGED);
            assertEquals(0, streakDelay(hook));
            timer.advance(0);
            assertEquals("A timezone change that enters the window must immediately re-evaluate streaks", 1, sdk.model.reads);

            StreakSendLedger ledger = (StreakSendLedger) HostTargets.read(hook, "streakLedger");
            String key = "streak.100.2026-10-25.200";
            StreakSendLedger.Attempt attempt = ledger.begin(key, 10);
            assertNotNull(attempt); assertTrue(ledger.complete(key, attempt.token, true, 11));
            wall.set(wall.get().withHour(8).withMinute(30));
            broadcastTime(android.content.Intent.ACTION_TIME_CHANGED);
            assertEquals(30 * 60_000L, streakDelay(hook));
            wall.set(wall.get().withHour(9).withMinute(5));
            broadcastTime(android.content.Intent.ACTION_TIME_CHANGED);
            timer.advance(0);
            assertEquals("Time correction must retain the daily durable submission guard", 1, sdk.model.reads);
            assertEquals(StreakSendLedger.Status.SENT, ledger.read(key).status);
        } finally { hook.close(); }
        broadcastTime(android.content.Intent.ACTION_TIME_CHANGED);
        timer.advance(60_000);
        assertEquals(1, sdk.model.reads);
    }

    @Test @Config(manifest = Config.NONE, sdk = 28)
    public void timeChangeReceiverRegistersAndClosesOnApi28() throws Exception {
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks hook = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        try { broadcastTime(android.content.Intent.ACTION_TIME_CHANGED); timer.advance(0); }
        finally { hook.close(); }
        broadcastTime(android.content.Intent.ACTION_TIMEZONE_CHANGED);
        timer.advance(0);
        assertTrue(NativeMessagingHooks.availableContacts().isEmpty());
    }

    @Test public void lateSdkObservationFromAClosedHookCannotClearItsReplacementContactSnapshot() throws Exception {
        NativeMessagingHooks old = enumerationHook(new LocalOptions(Collections.emptyMap()), new NativeFollowListsTest.ManualTimer());
        old.close();
        NativeFollowListsTest.ManualTimer timer = new NativeFollowListsTest.ManualTimer();
        NativeMessagingHooks current = enumerationHook(new LocalOptions(Collections.emptyMap()), timer);
        EnumerationSdk sdk = (EnumerationSdk) HostTargets.read(current, "sdk");
        sdk.model.rows.add(new Conversation(true, "thread", "New owner"));
        try {
            NativeMessagingHooks.loadContacts(false, ignored -> { });
            timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals("New owner", NativeMessagingHooks.availableContacts().get("200"));
            Method observe = NativeMessagingHooks.class.getDeclaredMethod("observeSdk", Object.class); observe.setAccessible(true);
            observe.invoke(old, new EnumerationSdk());
            Method capture = NativeMessagingHooks.class.getDeclaredMethod("captureSdk", Object.class); capture.setAccessible(true);
            capture.invoke(old, new Database());
            old.close();
            assertNull(HostTargets.read(old, "sdk"));
            assertEquals("New owner", NativeMessagingHooks.availableContacts().get("200"));
            assertSame(current, HostTargets.field(NativeMessagingHooks.class, "active").get(null));
        } finally { current.close(); }
    }

    private static void broadcastTime(String action) {
        RuntimeEnvironment.getApplication().sendBroadcast(new android.content.Intent(action));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
    }

    private static long streakDelay(NativeMessagingHooks hook) {
        java.util.concurrent.ScheduledFuture<?> future = (java.util.concurrent.ScheduledFuture<?>) HostTargets.read(hook, "streakTimer");
        assertNotNull(future);
        return future.getDelay(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    static NativeMessagingHooks enumerationHook(LocalOptions options) throws Exception {
        return enumerationHook(options, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
    }

    static NativeMessagingHooks enumerationHook(LocalOptions options,
                                               java.util.concurrent.ScheduledThreadPoolExecutor timer) throws Exception {
        return enumerationFields(new NativeMessagingHooks(RuntimeEnvironment.getApplication(), options,
                ignored -> { }, null, timer, timer));
    }

    static NativeMessagingHooks enumerationFields(NativeMessagingHooks hook) throws Exception {
        BehaviorHookGroup receive = (BehaviorHookGroup) HostTargets.read(hook, "receive");
        receive.add(new Framework().api, EnumerationSdk.class.getMethod("getUid"), chain -> chain.proceed());
        HostTargets.field(NativeMessagingHooks.class, "sdk").set(hook, new EnumerationSdk());
        HostTargets.field(NativeMessagingHooks.class, "sdkUid").set(hook, EnumerationSdk.class.getMethod("getUid"));
        HostTargets.field(NativeMessagingHooks.class, "sdkConversations").set(hook, EnumerationSdk.class.getMethod("conversations"));
        HostTargets.field(NativeMessagingHooks.class, "allConversations").set(hook, ConversationModel.class.getMethod("all"));
        HostTargets.field(NativeMessagingHooks.class, "conversationId").set(hook, Conversation.class.getMethod("id"));
        HostTargets.field(NativeMessagingHooks.class, "conversationMembers").set(hook, Conversation.class.getMethod("members"));
        HostTargets.field(NativeMessagingHooks.class, "conversationCore").set(hook, Conversation.class.getMethod("core"));
        HostTargets.field(NativeMessagingHooks.class, "conversationName").set(hook, Core.class.getMethod("name"));
        HostTargets.field(NativeMessagingHooks.class, "isSingle").set(hook, Conversation.class.getMethod("single"));
        HostTargets.field(NativeMessagingHooks.class, "active").set(null, hook);
        return hook;
    }

    public static final class EnumerationSdk {
        public long uid = 100;
        public final ConversationModel model = new ConversationModel();
        public long getUid() { return uid; }
        public ConversationModel conversations() { return model; }
    }

    public static final class ConversationModel {
        int reads;
        Runnable beforeAll = () -> { };
        final List<Conversation> rows = new ArrayList<>();
        public List<Conversation> all() { reads++; beforeAll.run(); return rows; }
    }

    public static final class Conversation {
        final boolean single;
        final String id, name;
        int memberReads;
        Conversation(boolean single, String id, String name) { this.single = single; this.id = id; this.name = name; }
        public String id() { return id; }
        public boolean single() { return single; }
        public List<Long> members() { return Arrays.asList(100L, 200L); }
        public List<Member> getMembers() { memberReads++; return Collections.singletonList(new Member()); }
        public Core core() { return new Core(name); }
    }

    public static final class Core {
        final String label;
        Core(String label) { this.label = label; }
        public String name() { return label; }
        public String getIcon() { return ""; }
    }

    public static final class Member {
        public long getUid() { return 300; }
        public String getAlias() { return "群友"; }
    }
}
