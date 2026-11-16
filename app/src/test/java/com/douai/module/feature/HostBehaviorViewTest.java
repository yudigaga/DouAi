package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class HostBehaviorViewTest {
    public static final class Panel {
        public View i = new View(RuntimeEnvironment.getApplication());
        public View j = new View(RuntimeEnvironment.getApplication());
        public void spaces() { }
    }

    public static final class Profile {
        public final TextView y;
        public final TextView w;
        public final TextView x;
        public final TextView F;
        public final LinearLayout root;
        public boolean own;
        public boolean failOwn;
        public Profile(Context context) {
            root = new LinearLayout(context);
            y = new TextView(context); w = new TextView(context);
            x = new TextView(context); F = new TextView(context);
            root.addView(y); root.addView(w); root.addView(x); root.addView(F);
            y.setText("12"); w.setText("34"); x.setText("56"); F.setText("78");
        }
        public void bind(Object user) { }
        public boolean own() {
            if (failOwn) throw new IllegalStateException("Profile fixture unavailable");
            return own;
        }
        public View root() { return root; }
    }

    public static final class ModernProfile {
        public final TextView x, v, w, E;
        public final TextView y;
        public final Object F = new Object();
        public final LinearLayout root;
        public boolean own;
        public ModernProfile(Context context) {
            root = new LinearLayout(context);
            x = new TextView(context); v = new TextView(context);
            w = new TextView(context); E = new TextView(context); y = new TextView(context);
            for (TextView view : new TextView[]{x, v, w, E, y}) root.addView(view);
            x.setText("12"); v.setText("34"); w.setText("56"); E.setText("78");
            y.setText("获赞");
        }
        public void bind(Object user) { }
        public boolean own() { return own; }
        public View root() { return root; }
    }

    public static final class RecallCell {
        public String uid = "100";
        public boolean failMessage;
        public void bind(RecallHolder holder, int index) { }
        public RecallMessage message(int index) {
            if (failMessage) throw new IllegalStateException("Recall fixture unavailable");
            return new RecallMessage();
        }
        public static RecallCell account() { return new RecallCell(); }
        public RecallCell user() { return this; }
    }

    public static final class RecallMessage {
        public String uuid() { return "message"; }
    }

    public static final class RecallHolder {
        public final View itemView;
        public RecallHolder(Context context) { itemView = new View(context); }
    }

    @Test public void preparedBlackSpacesWaitForEveryExistingFrameBatch() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        Map<View, Boolean> views = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) views.put(new View(RuntimeEnvironment.getApplication()), true);
        Map<String, Map<View, Boolean>> byKey = ConfigurationReconciliationActionTest.field(hooks, "observedByKey");
        byKey.put("hide_video_black_edges", views);
        java.util.concurrent.CompletableFuture<Void> applied = hooks.prepareOptions(
                new LocalOptions(Map.of("hide_video_black_edges", true))).apply(() -> true).toCompletableFuture();
        assertFalse(applied.isDone());
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertFalse(applied.isDone());
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(32));
        assertTrue(applied.isDone());
        assertFalse(applied.isCompletedExceptionally());
        for (View view : views.keySet()) assertEquals(View.GONE, view.getVisibility());
        hooks.close();
    }

    @Test public void preparedStartupOptionsAreAcceptedOnMainBeforeApplicationProceeds() throws Exception {
        java.lang.reflect.Constructor<HostBehaviorHooks> constructor = HostBehaviorHooks.class.getDeclaredConstructor(
                LocalOptions.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        try (HostBehaviorHooks hooks = constructor.newInstance(new LocalOptions(Collections.emptyMap()),
                (java.util.function.Consumer<String>) ignored -> { })) {
            LocalOptions next = new LocalOptions(Map.of("prevent_skip_hidden", true));
            java.util.concurrent.CompletableFuture<Void> result = hooks.prepareOptions(next).apply(() -> true).toCompletableFuture();
            assertSame(next, ConfigurationReconciliationActionTest.field(hooks, "options"));
            assertTrue("No observed view tail exists, so actual local completion is already known", result.isDone());
            assertFalse(result.isCompletedExceptionally());
        }
    }

    @Test public void preparedBlackSpacesRetryResumesTheFailedViewWithoutRepeatingSuccessfulViews() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        CountingVisibility first = new CountingVisibility(false), failed = new CountingVisibility(true);
        Map<View, Boolean> views = new LinkedHashMap<>(); views.put(first, true); views.put(failed, true);
        Map<String, Map<View, Boolean>> byKey = ConfigurationReconciliationActionTest.field(hooks, "observedByKey");
        byKey.put("hide_video_black_edges", views);
        com.douai.module.config.ConfigurationApplyAction action = hooks.prepareOptions(
                new LocalOptions(Map.of("hide_video_black_edges", true)));
        java.util.concurrent.CompletableFuture<Void> attempt = action.apply(() -> true).toCompletableFuture();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(attempt.isCompletedExceptionally());
        java.util.concurrent.CompletableFuture<Void> retry = action.apply(() -> true).toCompletableFuture();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(retry.isDone()); assertFalse(retry.isCompletedExceptionally());
        assertEquals(1, first.hides); assertEquals(2, failed.hides);
        assertEquals(View.GONE, failed.getVisibility());
        hooks.close();
    }

    @Test public void preparedBlackSpacesCancellationBetweenFramesStopsTheRemainingViews() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        Map<View, Boolean> views = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) views.put(new View(RuntimeEnvironment.getApplication()), true);
        Map<String, Map<View, Boolean>> byKey = ConfigurationReconciliationActionTest.field(hooks, "observedByKey");
        byKey.put("hide_video_black_edges", views);
        java.util.concurrent.CompletableFuture<Void> attempt = hooks.prepareOptions(
                new LocalOptions(Map.of("hide_video_black_edges", true))).apply(() -> true).toCompletableFuture();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        long hidden = views.keySet().stream().filter(view -> view.getVisibility() == View.GONE).count();
        assertEquals(64, hidden); assertFalse(attempt.isDone());
        attempt.cancel(false);
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(32));
        assertEquals(hidden, views.keySet().stream().filter(view -> view.getVisibility() == View.GONE).count());
        hooks.close();
    }

    @Test public void preparedSuccessorFinishesAnInterruptedObservedKeyUsingLatestOptions() throws Throwable {
        for (boolean hiddenAtLatestRevision : new boolean[]{true, false}) {
            Harness harness = new Harness();
            HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                    "adv_ui_black_spaces", Panel.class.getMethod("spaces"))), getClass().getClassLoader(), null,
                    new LocalOptions(Collections.emptyMap()), ignored -> { });
            Map<View, Boolean> views = new LinkedHashMap<>();
            for (int i = 0; i < 70; i++) views.put(new View(RuntimeEnvironment.getApplication()), true);
            Map<String, Map<View, Boolean>> byKey = ConfigurationReconciliationActionTest.field(hooks, "observedByKey");
            byKey.put("hide_video_black_edges", views);
            java.util.concurrent.atomic.AtomicBoolean oldCurrent = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.CompletableFuture<Void> old = hooks.prepareOptions(new LocalOptions(
                    Map.of("hide_video_black_edges", true))).apply(oldCurrent::get).toCompletableFuture();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(64, views.keySet().stream().filter(view -> view.getVisibility() == View.GONE).count());
            assertFalse(old.isDone());
            oldCurrent.set(false);
            java.util.concurrent.CompletableFuture<Void> latest = hooks.prepareOptions(new LocalOptions(Map.of(
                    "hide_video_black_edges", hiddenAtLatestRevision, "remove_splash_ads", true)))
                    .apply(() -> true).toCompletableFuture();
            assertTrue(old.isCancelled());
            assertFalse("The accepted old options must not erase its unfinished view tail", latest.isDone());
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(48));
            assertTrue(latest.isDone());
            assertFalse(latest.isCompletedExceptionally());
            for (View view : views.keySet()) assertEquals(hiddenAtLatestRevision ? View.GONE : View.VISIBLE, view.getVisibility());
            hooks.close();
        }
    }

    private static final class CountingVisibility extends View {
        boolean fail;
        int hides;
        CountingVisibility(boolean fail) { super(RuntimeEnvironment.getApplication()); this.fail = fail; }
        @Override public void setVisibility(int value) {
            if (value == View.GONE) {
                hides++;
                if (fail) { fail = false; throw new IllegalStateException("hide failed"); }
            }
            super.setVisibility(value);
        }
    }

    @Test public void blackSpacesApplyLiveAndRestoreOwnedVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Collections.emptyMap()), ignored -> { });
        Panel panel = new Panel();
        panel.j.setVisibility(View.INVISIBLE);
        harness.invoke(spaces, panel);
        assertEquals(1, harness.proceeded);
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        hooks.setOptions(new LocalOptions(Map.of("hide_video_black_edges", true)));
        assertEquals(View.GONE, panel.i.getVisibility());
        assertEquals(View.GONE, panel.j.getVisibility());
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(View.VISIBLE, panel.i.getVisibility());
        assertEquals(View.INVISIBLE, panel.j.getVisibility());
        hooks.close();
    }

    @Test public void restorationPreservesANewHostVisibility() throws Throwable {
        Harness harness = new Harness();
        Method spaces = Panel.class.getMethod("spaces");
        HostBehaviorHooks hooks = HostBehaviorHooks.install(harness.framework, report(Map.of(
                "adv_ui_black_spaces", spaces)), getClass().getClassLoader(), null,
                new LocalOptions(Map.of("hide_video_black_edges", true)), ignored -> { });
        Panel panel = new Panel();
        harness.invoke(spaces, panel);
        panel.i.setVisibility(View.INVISIBLE);
        hooks.close();
        assertEquals(View.INVISIBLE, panel.i.getVisibility());
        assertEquals(View.VISIBLE, panel.j.getVisibility());
    }

    @Test public void profileSpoofOnlyChangesOwnProfileAndRestoresText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Profile profile = new Profile(activity);
        activity.setContentView(profile.root);
        Harness harness = new Harness();
        Method bind = Profile.class.getMethod("bind", Object.class);
        ProfileSpoofHook hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                "adv_profile_render", bind, "adv_profile_own", Profile.class.getMethod("own"),
                "adv_profile_root", Profile.class.getMethod("root"))), getClass().getClassLoader(),
                activity, new LocalOptions(Map.of("spoof_profile", true, "profile_like_count", "9001",
                        "profile_following_count", "9002", "profile_follower_count", "9003",
                        "profile_mutual_count", "9004")), ignored -> { });
        harness.invoke(bind, profile);
        assertEquals("12", profile.y.getText().toString());
        profile.own = true;
        harness.invoke(bind, profile);
        assertEquals("9001", profile.y.getText().toString());
        assertEquals("9002", profile.w.getText().toString());
        assertEquals("9003", profile.x.getText().toString());
        assertEquals("9004", profile.F.getText().toString());
        hook.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals("12", profile.y.getText().toString());
        assertEquals("34", profile.w.getText().toString());
        assertEquals("56", profile.x.getText().toString());
        assertEquals("78", profile.F.getText().toString());
        hook.close();
        activity.finish();
    }

    @Test public void profileReflectionFailureRevokesRegistrationAndRestoresDisplayedCounts() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Profile profile = new Profile(activity);
        profile.own = true;
        activity.setContentView(profile.root);
        Harness harness = new Harness();
        Method bind = Profile.class.getMethod("bind", Object.class);
        ProfileSpoofHook hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                "adv_profile_render", bind, "adv_profile_own", Profile.class.getMethod("own"),
                "adv_profile_root", Profile.class.getMethod("root"))), getClass().getClassLoader(),
                activity, new LocalOptions(Map.of("spoof_profile", true, "profile_like_count", "9001")),
                ignored -> { });
        try {
            assertTrue(hook.isActive());
            harness.invoke(bind, profile);
            assertEquals("9001", profile.y.getText().toString());
            profile.failOwn = true;
            harness.invoke(bind, profile);
            assertFalse(hook.isActive());
            assertFalse(harness.interceptors.containsKey(bind));
            assertEquals("12", profile.y.getText().toString());
            assertEquals("34", profile.w.getText().toString());
            assertEquals("56", profile.x.getText().toString());
            assertEquals("78", profile.F.getText().toString());
        } finally { hook.close(); activity.finish(); }
    }

    @Test public void modernProfileSpoofKeepsLabelsAndRestoresAllSemanticCounts() throws Throwable {
        HostAdaptation.Profile previous = HostAdaptation.currentProfile();
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileSpoofHook hook = null;
        try {
            ModernProfile profile = new ModernProfile(activity);
            activity.setContentView(profile.root);
            Harness harness = new Harness();
            Method bind = ModernProfile.class.getMethod("bind", Object.class);
            LocalOptions enabled = new LocalOptions(Map.of("spoof_profile", true,
                    "profile_like_count", "9001", "profile_following_count", "9002",
                    "profile_follower_count", "9003", "profile_mutual_count", "9004"));
            hook = ProfileSpoofHook.install(harness.framework, report(Map.of(
                    "adv_profile_render", bind, "adv_profile_own", ModernProfile.class.getMethod("own"),
                    "adv_profile_root", ModernProfile.class.getMethod("root"))), getClass().getClassLoader(),
                    activity, enabled, ignored -> { });
            harness.invoke(bind, profile);
            assertModernCounts(profile, "12", "34", "56", "78");

            profile.own = true;
            harness.invoke(bind, profile);
            assertModernCounts(profile, "9001", "9002", "9003", "9004");
            profile.own = false;
            harness.invoke(bind, profile);
            assertModernCounts(profile, "12", "34", "56", "78");

            profile.own = true;
            harness.invoke(bind, profile);
            hook.setOptions(new LocalOptions(Collections.emptyMap()));
            assertModernCounts(profile, "12", "34", "56", "78");
            hook.setOptions(enabled);
            assertModernCounts(profile, "9001", "9002", "9003", "9004");
            hook.close();
            assertModernCounts(profile, "12", "34", "56", "78");
        } finally {
            if (hook != null) hook.close();
            activity.finish();
            HostAdaptation.setCurrentProfile(previous);
        }
    }

    private static void assertModernCounts(ModernProfile profile, String likes, String following,
                                           String followers, String mutual) {
        assertEquals(likes, profile.x.getText().toString());
        assertEquals(following, profile.v.getText().toString());
        assertEquals(followers, profile.w.getText().toString());
        assertEquals(mutual, profile.E.getText().toString());
        assertEquals("获赞", profile.y.getText().toString());
    }

    @Test public void recallReflectionFailureRevokesRegistrationAndRestoresHighlightedBackground() throws Throwable {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("recall.100.message", true).commit();
        Harness harness = new Harness();
        Method bind = RecallCell.class.getMethod("bind", RecallHolder.class, int.class);
        RecallHighlightHook hook = RecallHighlightHook.install(harness.framework, report(Map.of(
                "adv_im_cell_bind", bind, "adv_im_cell_message", RecallCell.class.getMethod("message", int.class),
                "adv_im_message_uuid", RecallMessage.class.getMethod("uuid"),
                "media_account_service", RecallCell.class.getMethod("account"),
                "media_current_user", RecallCell.class.getMethod("user"))), getClass().getClassLoader(), context,
                new LocalOptions(Map.of("prevent_message_recall", true, "recall_background", "#2051BA55")),
                ignored -> { });
        RecallCell cell = new RecallCell();
        RecallHolder holder = new RecallHolder(context);
        GradientDrawable original = new GradientDrawable();
        original.setColor(Color.BLUE);
        holder.itemView.setBackground(original);
        RecallHolder failingHolder = new RecallHolder(context);
        GradientDrawable failingOriginal = new GradientDrawable();
        failingOriginal.setColor(Color.RED);
        failingHolder.itemView.setBackground(failingOriginal);
        try {
            assertTrue(hook.isActive());
            harness.invoke(bind, cell, holder, 0);
            assertEquals(Color.parseColor("#2051BA55"), ((ColorDrawable) holder.itemView.getBackground()).getColor());
            cell.failMessage = true;
            harness.invoke(bind, cell, failingHolder, 0);
            assertFalse(hook.isActive());
            assertFalse(harness.interceptors.containsKey(bind));
            assertSame(original, holder.itemView.getBackground());
            assertSame(failingOriginal, failingHolder.itemView.getBackground());
        } finally { hook.close(); }
    }

    private static HostScanReport report(Map<String, Method> methods) {
        java.util.ArrayList<RuleScanResult> results = new java.util.ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return new HostScanReport("fixture", results, false);
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new LinkedHashMap<>();
        int proceeded;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        void invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getThisObject")) return owner;
                        if (method.getName().equals("getExecutable")) return target;
                        if (method.getName().equals("getArgs")) return Arrays.asList(arguments);
                        if (method.getName().equals("getArg")) return arguments[(Integer) args[0]];
                        if (method.getName().startsWith("proceed")) { proceeded++; return null; }
                        return null;
                    });
            interceptors.get(target).intercept(chain);
        }
    }
}
