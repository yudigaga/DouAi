package com.douai.module.feature;

import android.os.Bundle;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;
import kotlin.Unit;

import static org.junit.Assert.*;

/** Replays both supported safety callback branches independently pinned by HiddenVideoHostContractTest. */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(sdk = 34)
public final class HostBehaviorHiddenVideoTest {
    @ParameterizedRobolectricTestRunner.Parameters(name = "host406={0}")
    public static List<Object[]> profiles() { return Arrays.asList(new Object[]{true}, new Object[]{false}); }
    private final boolean legacy;
    public HostBehaviorHiddenVideoTest(boolean legacy) { this.legacy = legacy; }
    private static HostBehaviorHooks.HiddenSafetyTaskContract contract(boolean legacy) {
        return legacy ? HostBehaviorHooks.HiddenSafetyTaskContract.HOST_406 : HostBehaviorHooks.HiddenSafetyTaskContract.HOST_408;
    }

    @Test public void productionAcceptsTheVerified410TaskPairAndRejectsCrossVersionPairs() {
        HostBehaviorHooks.HiddenSafetyTaskContract latest = HostBehaviorHooks.HiddenSafetyTaskContract.resolve(
                "Lkotlin/jvm/internal/ALambdaS43S1500000_47;", "LX/1R3E;");
        assertNotNull(latest);
        assertEquals(2, latest.currentBranch);
            assertEquals(Integer.valueOf(3), latest.secondaryCurrentBranch);
        assertNull(latest.backgroundBranch);
        try {
            java.lang.reflect.Method classifier = HostBehaviorHooks.HiddenSafetyTaskContract.class
                    .getDeclaredMethod("blocksCurrentBranch", int.class);
            classifier.setAccessible(true);
            assertTrue((Boolean) classifier.invoke(latest, 2));
            assertTrue("410 key 3 is also a safety_check_recall delete path", (Boolean) classifier.invoke(latest, 3));
            assertFalse((Boolean) classifier.invoke(latest, 0));
            assertFalse((Boolean) classifier.invoke(latest, 1));
        } catch (ReflectiveOperationException missingContract) {
            fail("The 410 current task must classify both safety branches: " + missingContract);
        }
        for (String[] pair : Arrays.asList(
                new String[]{"Lkotlin/jvm/internal/ALambdaS43S1500000_47;", "LX/06ht;"},
                new String[]{"Lkotlin/jvm/internal/ALambdaS43S1500000_47;", "LX/12bW;"},
                new String[]{"Lkotlin/jvm/internal/ALambdaS32S1500000_4;", "LX/1R3E;"},
                new String[]{"Lkotlin/jvm/internal/ALambdaS38S1500000_35;", "LX/1R3E;"})) {
            assertThrows(IllegalStateException.class,
                    () -> HostBehaviorHooks.HiddenSafetyTaskContract.resolve(pair[0], pair[1]));
        }
    }

    @Test public void productionContractsRequireOneOfTheTwoExactTaskPairs() {
        assertSame(HostBehaviorHooks.HiddenSafetyTaskContract.HOST_406,
                HostBehaviorHooks.HiddenSafetyTaskContract.resolve("Lkotlin/jvm/internal/ALambdaS32S1500000_4;", "LX/06ht;"));
        assertSame(HostBehaviorHooks.HiddenSafetyTaskContract.HOST_408,
                HostBehaviorHooks.HiddenSafetyTaskContract.resolve("Lkotlin/jvm/internal/ALambdaS38S1500000_35;",
                        "LX/12bW;"));
        for (String[] pair : Arrays.asList(
                new String[]{"Lkotlin/jvm/internal/ALambdaS32S1500000_4;", "LX/12bW;"},
                new String[]{"Lkotlin/jvm/internal/ALambdaS38S1500000_35;", "LX/06ht;"},
                new String[]{"Lkotlin/jvm/internal/ALambdaS31S1500000_28;", "Lkotlin/jvm/internal/ALambdaS17S1600000_28;"},
                new String[]{"Lunknown/Current;", "Lunknown/Background;"})) {
            assertThrows(IllegalStateException.class, () -> HostBehaviorHooks.HiddenSafetyTaskContract.resolve(pair[0], pair[1]));
        }
    }

    @Test public void productionEntryRejectsUnknownTaskPairsBeforeAnyHiddenHookIsRegistered() throws Exception {
        Harness harness = new Harness();
        HostScanReport targets = report(BasePanel.class.getMethod("deleteAweme", int.class, Object.class),
                FullPanel.class.getMethod("deleteAweme", int.class, Object.class), legacy);
        try (HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, targets,
                getClass().getClassLoader(), null, new LocalOptions(Map.of("prevent_skip_hidden", true)), ignored -> { })) {
            assertFalse(hooks.supports("prevent_skip_hidden"));
            assertTrue(harness.interceptors.isEmpty());
            Bundle diagnostic = new Bundle(); hooks.appendHiddenSkipDiagnostics(diagnostic);
            assertFalse(diagnostic.getBoolean("hidden_skip_installed"));
            assertFalse(diagnostic.getString("hidden_skip_failure", "").isEmpty());
        }
    }

    public static class BasePanel {
        int baseDeletes;

        public void deleteAweme(int position, Object aweme) {
            baseDeletes++;
        }
    }

    public static final class FullPanel extends BasePanel {
        int fullDeletes;

        @Override public void deleteAweme(int position, Object aweme) {
            fullDeletes++;
            super.deleteAweme(position, aweme);
        }
    }

    @Test public void preventSkipHiddenBlocksFullFeedOverrideBeforeItDeletesData() throws Throwable {
        Harness harness = new Harness();
        Method base = BasePanel.class.getMethod("deleteAweme", int.class, Object.class);
        Method full = FullPanel.class.getMethod("deleteAweme", int.class, Object.class);
        HostBehaviorHooks hooks = HostBehaviorHooks.installWithHiddenTaskContract(harness.framework, report(base, full, legacy),
                getClass().getClassLoader(), null,
                new LocalOptions(Map.of("prevent_skip_hidden", true)), ignored -> { }, contract(legacy));
        FullPanel panel = new FullPanel();

        harness.invoke(full, panel, 3, new Object());

        assertEquals(0, panel.fullDeletes);
        assertEquals(0, panel.baseDeletes);
        assertEquals(0, harness.proceeded);
        hooks.close();
    }

    @Test public void currentSafetyRecallKeepsTheItemItsPositionAndBackNavigation() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("B");
            assertEquals("The host caches the response before scheduling destructive work", 1, flow.cacheCalls);
            assertEquals(Arrays.asList("A", "B", "C"), flow.aids());
            flow.drainMain();

            assertEquals("The hidden item must stay in the feed rather than disappear", Arrays.asList("A", "B", "C"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertEquals(1, flow.currentIndex);
            flow.next();
            assertEquals("C", flow.currentAid());
            flow.previous();
            assertEquals("Going back one slot must find B, not skip directly to A", "B", flow.currentAid());
            assertEquals(0, flow.modelDeletes);
            assertEquals(0, flow.legacyPanel.fullDeletes);
            assertEquals(0, flow.legacyPanel.baseDeletes);
            assertEquals(List.of(Unit.INSTANCE), flow.results);
        }
    }

    @Test public void currentSafetyRecallCannotLeaveAMarkerThatDeletesTheItemOnTheNextSetData() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("B");
            flow.drainMain();
            assertFalse("Blocking only the final delete leaves this global recall marker behind", flow.recallSet.contains("B"));
            flow.setData(flow.original);
            assertEquals(Arrays.asList("A", "B", "C"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertEquals(1, flow.currentIndex);
            assertEquals(0, flow.recallEvents);
        }
    }

    @Test public void backgroundSafetyDeletionKeepsTheListAndCurrentPosition() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("C");
            assertEquals(1, flow.cacheCalls);
            flow.drainMain();
            assertEquals(Arrays.asList("A", "B", "C"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertEquals(1, flow.currentIndex);
            assertEquals(0, flow.modelDeletes);
            assertEquals(List.of(Unit.INSTANCE), flow.results);
        }
    }

    @Test public void disabledOptionStillRunsRecallEventModelRemovalAndNextSetDataFiltering() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, false)) {
            flow.onNeedRecallAid("B");
            flow.drainMain();
            assertEquals(Arrays.asList("A", "C"), flow.aids());
            assertEquals("C", flow.currentAid());
            assertEquals(1, flow.currentIndex);
            assertEquals(Set.of("B"), flow.recallSet);
            assertEquals(1, flow.recallEvents);
            assertEquals(1, flow.modelDeletes);
            assertEquals(List.of(1), flow.adapterDeletedIndexes);
            flow.setData(flow.original);
            assertEquals(Arrays.asList("A", "C"), flow.aids());
            flow.previous();
            assertEquals("A", flow.currentAid());
            assertEquals(1, flow.cacheCalls);
        }
    }

    @Test public void disabledOptionStillRunsTheBackgroundSafetyCheckDelete() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, false)) {
            flow.onNeedRecallAid("C");
            flow.drainMain();
            assertEquals(Arrays.asList("A", "B"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertEquals(List.of("safety_check"), flow.directDeleteSources);
            assertEquals(1, flow.modelDeletes);
            assertTrue(flow.recallSet.isEmpty());
        }
    }

    @Test public void turningOnBeforeQueuedTasksExecuteProtectsBothSafetyTaskKinds() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, false)) {
            flow.onNeedRecallAid("B", "C");
            flow.setEnabled(true);
            flow.drainMain();
            assertEquals(Arrays.asList("A", "B", "C"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertTrue(flow.recallSet.isEmpty());
            assertEquals(0, flow.modelDeletes);
            assertEquals(1, flow.cacheCalls);
            assertEquals(Arrays.asList(Unit.INSTANCE, Unit.INSTANCE), flow.results);
        }
    }

    @Test public void turningOffBeforeQueuedTasksExecuteRestoresBothSafetyTaskKinds() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("B", "C");
            flow.setEnabled(false);
            flow.drainMain();
            assertEquals(List.of("A"), flow.aids());
            assertEquals(2, flow.modelDeletes);
            assertEquals(Set.of("B"), flow.recallSet);
            assertEquals(List.of("safety_check"), flow.directDeleteSources);
        }
    }

    @Test public void hotOptionsAffectSubsequentResponsesAndDoNotUndoHostBookkeeping() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, false)) {
            flow.onNeedRecallAid("C");
            flow.drainMain();
            assertEquals(Arrays.asList("A", "B"), flow.aids());
            flow.setEnabled(true);
            flow.onNeedRecallAid("B");
            flow.drainMain();
            assertEquals(Arrays.asList("A", "B"), flow.aids());
            assertEquals("B", flow.currentAid());
            assertEquals(2, flow.cacheCalls);
            assertEquals(Arrays.asList(List.of("C"), List.of("B")), flow.cachedRejectedAids);
        }
    }

    @Test public void discriminatorOneRetainsTheSeparateDirectDeletionBranchWhenProtectionIsEnabled() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            // Both supported APKs use $t=1 for the separate generic deletion path.
            Object result = flow.harness.invoke(CurrentSafetyTask.class.getMethod("invoke"), new CurrentSafetyTask(flow, "B", 1));
            assertSame(Unit.INSTANCE, result);
            assertEquals(Arrays.asList("A", "C"), flow.aids());
            assertEquals(List.of("none"), flow.directDeleteSources);
            assertTrue(flow.recallSet.isEmpty());
            assertEquals(1, flow.modelDeletes);
        }
    }

    @Test public void dedicatedBackgroundTasksHaveNoDispatcherAndUnknownCurrentCasesStillProceed() throws Throwable {
        assertThrows(NoSuchFieldException.class, () -> LegacyBackgroundSafetyTask.class.getDeclaredField("$t"));
        assertThrows(NoSuchFieldException.class, () -> BackgroundSafetyTask.class.getDeclaredField("$t"));
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            assertNull(flow.harness.invoke(CurrentSafetyTask.class.getMethod("invoke"),
                    new CurrentSafetyTask(flow, "B", 2)));
            assertEquals(Arrays.asList("A", "B", "C"), flow.aids());
            assertEquals(1, flow.harness.proceeded);
            Bundle diagnostic = new Bundle(); flow.hooks.appendHiddenSkipDiagnostics(diagnostic);
            assertEquals(0, diagnostic.getLong("hidden_skip_current_hits"));
            assertEquals(0, diagnostic.getLong("hidden_skip_background_hits"));
        }
    }

    @Test public void keepingSafetyTasksDoesNotRewritePlaybackStatusOrResponseCache() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("B", "C");
            flow.drainMain();
            assertEquals(1, flow.cacheCalls);
            assertEquals(List.of(Arrays.asList("B", "C")), flow.cachedRejectedAids);
            for (Aweme item : flow.original) {
                assertEquals(7, item.status);
                assertFalse(item.playable);
                assertEquals(3, item.potentialRiskLevel);
            }
        }
    }

    @Test public void closingTheFeatureLetsAlreadyQueuedSafetyWorkRunNormally() throws Throwable {
        try (SafetyFlow flow = new SafetyFlow(legacy, true)) {
            flow.onNeedRecallAid("B", "C");
            flow.hooks.close();
            flow.drainMain();
            assertEquals(List.of("A"), flow.aids());
            assertEquals(2, flow.modelDeletes);
        }
    }

    @Test public void missingRulesOrChangedTaskShapesLeaveTheWholeFeatureUnavailableAndHostDeletionOpen() throws Throwable {
        for (String fault : Arrays.asList("missing_current", "missing_background", "wrong_discriminator", "wrong_background_discriminator", "shared_background_dispatcher", "wrong_return")) {
            Map<String, Method> methods = hiddenMethods(BasePanel.class.getMethod("deleteAweme", int.class, Object.class),
                    FullPanel.class.getMethod("deleteAweme", int.class, Object.class), legacy);
            if ("missing_current".equals(fault)) methods.remove("adv_ui_hidden_recall_task");
            if ("missing_background".equals(fault)) methods.remove("adv_ui_hidden_background_task");
            if ("wrong_discriminator".equals(fault)) methods.put("adv_ui_hidden_recall_task", WrongDiscriminatorTask.class.getMethod("invoke"));
            if ("wrong_background_discriminator".equals(fault)) methods.put("adv_ui_hidden_background_task", WrongDiscriminatorTask.class.getMethod("invoke"));
            if ("shared_background_dispatcher".equals(fault)) methods.put("adv_ui_hidden_background_task", SharedBackgroundTask.class.getMethod("invoke"));
            if ("wrong_return".equals(fault)) methods.put("adv_ui_hidden_background_task", WrongReturnTask.class.getMethod("invoke"));
            Harness harness = new Harness();
            try (HostBehaviorHooks hooks = HostBehaviorHooks.installWithHiddenTaskContract(harness.framework, report(methods, legacy), getClass().getClassLoader(), null,
                    new LocalOptions(Map.of("prevent_skip_hidden", true)), ignored -> { }, contract(legacy))) {
                assertFalse(fault, hooks.supports("prevent_skip_hidden"));
                assertTrue(fault, harness.interceptors.isEmpty());
                FullPanel panel = new FullPanel();
                harness.invoke(methods.get("adv_ui_delete_aweme_full_feed"), panel, 1, new Object());
                assertEquals(fault, 1, panel.fullDeletes);
                assertEquals(fault, 1, panel.baseDeletes);
                Bundle diagnostic = new Bundle(); hooks.appendHiddenSkipDiagnostics(diagnostic);
                assertTrue("Requested setting remains visible even when adaptation fails: " + fault, diagnostic.getBoolean("hidden_skip_enabled"));
                assertFalse(fault, diagnostic.getBoolean("hidden_skip_installed"));
                assertFalse(fault, diagnostic.getString("hidden_skip_failure", "").isEmpty());
            }
        }
    }

    @Test public void registrationFailureOnEitherNewTaskRevokesEveryEarlierHookInTheFeature() throws Throwable {
        for (String rejectedRule : Arrays.asList("adv_ui_hidden_recall_task", "adv_ui_hidden_background_task")) {
            Map<String, Method> methods = hiddenMethods(BasePanel.class.getMethod("deleteAweme", int.class, Object.class),
                    FullPanel.class.getMethod("deleteAweme", int.class, Object.class), legacy);
            Harness harness = new Harness();
            harness.rejected = methods.get(rejectedRule);
            try (HostBehaviorHooks hooks = HostBehaviorHooks.installWithHiddenTaskContract(harness.framework, report(methods, legacy), getClass().getClassLoader(), null,
                    new LocalOptions(Map.of("prevent_skip_hidden", true)), ignored -> { }, contract(legacy))) {
                assertFalse(rejectedRule, hooks.supports("prevent_skip_hidden"));
                assertTrue(rejectedRule, harness.interceptors.isEmpty());
                assertEquals(rejectedRule, "adv_ui_hidden_recall_task".equals(rejectedRule) ? 2 : 3, harness.revoked);
                FullPanel panel = new FullPanel();
                harness.invoke(methods.get("adv_ui_delete_aweme_full_feed"), panel, 1, new Object());
                assertEquals(rejectedRule, 1, panel.fullDeletes);
                assertEquals(rejectedRule, 1, panel.baseDeletes);
                Bundle diagnostic = new Bundle(); hooks.appendHiddenSkipDiagnostics(diagnostic);
                assertFalse(rejectedRule, diagnostic.getBoolean("hidden_skip_installed"));
                assertFalse(rejectedRule, diagnostic.getString("hidden_skip_failure", "").isEmpty());
            }
        }
    }

    @Test public void diagnosticsSeparateRequestedSettingInstallationAndPerRouteHitsWithoutRepeatingBlockLogs() throws Throwable {
        List<String> logs = new ArrayList<>();
        try (SafetyFlow flow = new SafetyFlow(legacy, true, logs::add)) {
            for (int repeat = 0; repeat < 2; repeat++) { flow.onNeedRecallAid("B", "C"); flow.drainMain(); }
            flow.harness.invoke(CurrentSafetyTask.class.getMethod("invoke"), new CurrentSafetyTask(flow, "absent", 1));
            Bundle active = new Bundle(); flow.hooks.appendHiddenSkipDiagnostics(active);
            assertTrue(active.getBoolean("hidden_skip_enabled"));
            assertTrue(active.getBoolean("hidden_skip_installed"));
            assertEquals("The separate generic branch must not count as a safety recall", 2, active.getLong("hidden_skip_current_hits"));
            assertEquals(2, active.getLong("hidden_skip_current_blocked"));
            assertEquals(2, active.getLong("hidden_skip_background_hits"));
            assertEquals(2, active.getLong("hidden_skip_background_blocked"));
            assertEquals("", active.getString("hidden_skip_failure"));

            flow.setEnabled(false);
            flow.onNeedRecallAid("B", "C"); flow.drainMain();
            Bundle disabled = new Bundle(); flow.hooks.appendHiddenSkipDiagnostics(disabled);
            assertFalse(disabled.getBoolean("hidden_skip_enabled"));
            assertTrue("Turning the option off does not uninstall the registered feature", disabled.getBoolean("hidden_skip_installed"));
            assertEquals(3, disabled.getLong("hidden_skip_current_hits"));
            assertEquals(3, disabled.getLong("hidden_skip_background_hits"));
            assertEquals(2, disabled.getLong("hidden_skip_current_blocked"));
            assertEquals(2, disabled.getLong("hidden_skip_background_blocked"));
            assertEquals(1L, logs.stream().filter("Hidden video auto-skip blocked: route=current"::equals).count());
            assertEquals(1L, logs.stream().filter("Hidden video auto-skip blocked: route=background"::equals).count());

            flow.hooks.close();
            flow.harness.invoke(CurrentSafetyTask.class.getMethod("invoke"), new CurrentSafetyTask(flow, "A", 0));
            Bundle closed = new Bundle(); flow.hooks.appendHiddenSkipDiagnostics(closed);
            assertFalse(closed.getBoolean("hidden_skip_enabled"));
            assertFalse(closed.getBoolean("hidden_skip_installed"));
            assertEquals(3, closed.getLong("hidden_skip_current_hits"));
            assertEquals(3, closed.getLong("hidden_skip_background_hits"));
        }
    }

    public static final class WrongDiscriminatorTask {
        public final String $t = "0";
        public Object invoke() { return Unit.INSTANCE; }
    }

    public static final class SharedBackgroundTask {
        public final int $t = 0;
        public Object invoke() { return Unit.INSTANCE; }
    }

    public static final class WrongReturnTask {
        public int invoke() { return 0; }
    }

    /** Minimal payloads retain the state fields the production task must never rewrite. */
    private static final class Aweme {
        final String aid;
        int status = 7;
        boolean playable;
        int potentialRiskLevel = 3;
        Aweme(String aid) { this.aid = aid; }
    }

    private static final class DeleteEvent {
        final Aweme aweme;
        final String eventType, source;
        DeleteEvent(Aweme aweme, String eventType, String source) {
            this.aweme = aweme; this.eventType = eventType; this.source = source;
        }
    }

    /** Original 40.6 and 40809900 tasks map zero to recall and one to generic deletion. */
    public static final class CurrentSafetyTask {
        public final int $t;
        private final SafetyFlow flow;
        private final String aid;
        CurrentSafetyTask(SafetyFlow flow, String aid, int discriminator) { this.flow = flow; this.aid = aid; $t = discriminator; }
        public Object invoke() {
            if ($t == 0) flow.recall(aid);
            else if ($t == 1) flow.directDelete(aid, "none");
            else return null;
            return Unit.INSTANCE;
        }
    }

    public static final class LegacyBackgroundSafetyTask {
        private final SafetyFlow flow;
        private final String aid;
        LegacyBackgroundSafetyTask(SafetyFlow flow, String aid) { this.flow = flow; this.aid = aid; }
        public Object invoke() { flow.directDelete(aid, "safety_check"); return Unit.INSTANCE; }
    }

    /** Original 40809900 LX/12bW is dedicated to background safety deletion. */
    public static final class BackgroundSafetyTask {
        private final SafetyFlow flow;
        private final String aid;
        BackgroundSafetyTask(SafetyFlow flow, String aid) { this.flow = flow; this.aid = aid; }
        public Object invoke() { flow.directDelete(aid, "safety_check"); return Unit.INSTANCE; }
    }

    /**
     * Behavioral replay: the original safety callback caches then queues one of two tasks;
     * current recall mutates the global set, notifies the panel, emits an event and reaches model.remove;
     * background removal reaches the model through feedDataContext. Neither route enters legacy LJLJL.
     * HiddenVideoHostContractTest independently pins these edges against the original APK DEX.
     */
    private static final class SafetyFlow implements AutoCloseable {
        final boolean legacy;
        final Harness harness = new Harness();
        final FullPanel legacyPanel = new FullPanel();
        final HostBehaviorHooks hooks;
        final List<Aweme> original = Arrays.asList(new Aweme("A"), new Aweme("B"), new Aweme("C"));
        final List<Aweme> items = new ArrayList<>(original);
        final Set<String> recallSet = new HashSet<>();
        final Queue<Object> mainTasks = new ArrayDeque<>();
        final List<Object> results = new ArrayList<>();
        final List<List<String>> cachedRejectedAids = new ArrayList<>();
        final List<String> directDeleteSources = new ArrayList<>();
        final List<Integer> adapterDeletedIndexes = new ArrayList<>();
        int currentIndex = 1, cacheCalls, recallEvents, modelDeletes;

        SafetyFlow(boolean legacy, boolean enabled) throws Exception {
            this(legacy, enabled, ignored -> { });
        }
        SafetyFlow(boolean legacy, boolean enabled, Consumer<String> log) throws Exception {
            this.legacy = legacy;
            hooks = HostBehaviorHooks.installWithHiddenTaskContract(harness.framework,
                    report(BasePanel.class.getMethod("deleteAweme", int.class, Object.class),
                            FullPanel.class.getMethod("deleteAweme", int.class, Object.class), legacy),
                    getClass().getClassLoader(), null, new LocalOptions(Map.of("prevent_skip_hidden", enabled)), log, contract(legacy));
        }
        void setEnabled(boolean enabled) { hooks.setOptions(new LocalOptions(Map.of("prevent_skip_hidden", enabled))); }
        void onNeedRecallAid(String... rejectedAids) {
            cacheCalls++;
            cachedRejectedAids.add(Arrays.asList(rejectedAids.clone()));
            // 06hv / 12bV record the rejection list before scheduling current/background work.
            for (String aid : rejectedAids) {
                if (find(aid) == null) continue;
                mainTasks.add(aid.equals(currentAid()) ? new CurrentSafetyTask(this, aid, 0)
                        : legacy ? new LegacyBackgroundSafetyTask(this, aid) : new BackgroundSafetyTask(this, aid));
            }
        }
        void drainMain() throws Throwable {
            while (!mainTasks.isEmpty()) {
                Object task = mainTasks.remove();
                results.add(harness.invoke(task.getClass().getMethod("invoke"), task));
            }
        }
        void recall(String aid) {
            if (!recallSet.add(aid)) return;
            // 06R8 -> recall listener -> BasePanel.j -> onDeleteAwemeEvent -> model.deleteItem.
            for (Aweme item : new ArrayList<>(items)) if (recallSet.contains(item.aid)) onDeleteAwemeEvent(new DeleteEvent(item, "homepage_hot", "recall"));
        }
        void onDeleteAwemeEvent(DeleteEvent event) {
            if (!"homepage_hot".equals(event.eventType)) return;
            recallEvents++;
            deleteItem(event.aweme);
        }
        void directDelete(String aid, String source) { directDeleteSources.add(source); deleteItem(find(aid)); }
        void deleteItem(Aweme item) {
            int index = items.indexOf(item);
            if (index < 0) return;
            items.remove(item);
            modelDeletes++;
            adapterDeletedIndexes.add(index);
            currentIndex = Math.min(currentIndex, Math.max(0, items.size() - 1));
        }
        void setData(List<Aweme> next) {
            items.clear();
            for (Aweme item : next) if (!recallSet.contains(item.aid)) items.add(item);
            currentIndex = Math.min(currentIndex, Math.max(0, items.size() - 1));
        }
        Aweme find(String aid) { for (Aweme item : items) if (item.aid.equals(aid)) return item; return null; }
        List<String> aids() { List<String> result = new ArrayList<>(); for (Aweme item : items) result.add(item.aid); return result; }
        String currentAid() { return items.isEmpty() ? "" : items.get(currentIndex).aid; }
        void next() { currentIndex = Math.min(currentIndex + 1, items.size() - 1); }
        void previous() { currentIndex = Math.max(0, currentIndex - 1); }
        @Override public void close() { hooks.close(); }
    }

    private static HostScanReport report(Method base, Method full, boolean legacy) {
        return report(hiddenMethods(base, full, legacy), legacy);
    }

    private static Map<String, Method> hiddenMethods(Method base, Method full, boolean legacy) {
        Map<String, Method> methods = new LinkedHashMap<>();
        methods.put("adv_ui_delete_aweme", base);
        methods.put("adv_ui_delete_aweme_full_feed", full);
        try {
            methods.put("adv_ui_hidden_recall_task", CurrentSafetyTask.class.getMethod("invoke"));
            methods.put("adv_ui_hidden_background_task", (legacy ? LegacyBackgroundSafetyTask.class : BackgroundSafetyTask.class).getMethod("invoke"));
        } catch (NoSuchMethodException failure) { throw new AssertionError(failure); }
        return methods;
    }

    private static HostScanReport report(Map<String, Method> methods, boolean legacy) {
        ArrayList<RuleScanResult> results = new ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return new HostScanReport("fixture", results, false);
    }

    private static final class Harness {
        int proceeded, revoked;
        Method rejected;
        final Map<Method, XposedInterface.Hooker> interceptors = new LinkedHashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { XposedInterface.class },
                (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class },
                            (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                if (target.equals(rejected)) throw new IllegalStateException("fixture rejected registration");
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class },
                                        (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) { interceptors.remove(target); revoked++; }
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] { XposedInterface.Chain.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return owner;
                        if (method.getName().equals("getExecutable")) return target;
                        if (method.getName().equals("getArgs")) return java.util.Arrays.asList(arguments);
                        if (method.getName().equals("getArg")) return arguments[(Integer) args[0]];
                        if (method.getName().equals("proceed")) {
                            proceeded++;
                            return target.invoke(owner, args == null ? arguments : (Object[]) args[0]);
                        }
                        return null;
                    });
            XposedInterface.Hooker hooker = interceptors.get(target);
            return hooker == null ? target.invoke(owner, arguments) : hooker.intercept(chain);
        }
    }
}
