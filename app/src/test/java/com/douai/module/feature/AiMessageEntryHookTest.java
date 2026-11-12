package com.douai.module.feature;

import android.app.Activity;
import android.app.Dialog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SettingsStore;
import com.ss.android.ugc.aweme.im.sdk.module.session.rips.MessagesFragment3;
import com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.MessageListRvLinearLayoutManager;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AiMessageEntryHookTest {
    @Test public void repeatedDiscoveryReusesClassAccessorsButReadsFreshHostState() throws Exception {
        HostReflectionCache cache = null;
        for (java.lang.reflect.Field field : AiMessageEntryHook.class.getDeclaredFields()) {
            if (field.getType() == HostReflectionCache.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                cache = (HostReflectionCache) field.get(null);
            }
        }
        assertNotNull("Discovery should reuse the class-isolated accessor cache", cache);
        java.lang.reflect.Field resolverField = HostReflectionCache.class.getDeclaredField("resolver");
        java.lang.reflect.Field classesField = HostReflectionCache.class.getDeclaredField("classes");
        resolverField.setAccessible(true);
        classesField.setAccessible(true);
        HostReflectionCache.Resolver original = (HostReflectionCache.Resolver) resolverField.get(cache);
        CountingResolver resolver = new CountingResolver();
        java.util.Map<?, ?> classes = (java.util.Map<?, ?>) classesField.get(cache);
        classes.clear();
        resolverField.set(cache, resolver);
        MessageActivity activity = activity();
        try {
            FrameLayout root = root(activity);
            RecyclerView list = list(activity, root);
            MessagesFragment3 fragment = new MessagesFragment3(root);
            activity.fragments.entries.add(fragment);
            assertSame(list, AiMessageEntryHook.uniqueMessageList(activity));
            // Pin weak accessors only for this deterministic resolution workload.
            List<Object> pinned = new ArrayList<>();
            for (Object value : classes.values()) pinned.add(((java.lang.ref.WeakReference<?>) value).get());
            for (int i = 0; i < 300; i++) {
                fragment.hidden = i % 2 == 0;
                if (fragment.hidden) assertNull(AiMessageEntryHook.uniqueMessageList(activity));
                else assertSame(list, AiMessageEntryHook.uniqueMessageList(activity));
            }
            assertEquals("Five getters resolve once, not on each discovery", 5, resolver.methods);
        } finally {
            resolverField.set(cache, original);
            classes.clear();
            activity.finish();
        }
    }

    private static final class CountingResolver implements HostReflectionCache.Resolver {
        int methods;
        public java.lang.reflect.Field field(Class<?> owner, String name) throws NoSuchFieldException {
            return HostReflectionCache.REFLECTION.field(owner, name);
        }
        public java.lang.reflect.Method publicNoArgMethod(Class<?> owner, String name) throws NoSuchMethodException {
            methods++;
            return HostReflectionCache.REFLECTION.publicNoArgMethod(owner, name);
        }
        public java.lang.reflect.Method declaredMethod(Class<?> owner, String name, String descriptor) throws NoSuchMethodException {
            return HostReflectionCache.REFLECTION.declaredMethod(owner, name, descriptor);
        }
    }

    @Test public void stableLayoutBurstsCoalesceAndDoNotSubmitIdenticalDiagnostics() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        list(activity, root);
        MessagesFragment3 fragment = new MessagesFragment3(root);
        activity.fragments.entries.add(fragment);
        MemoryStore store = new MemoryStore();
        new AiModelSettings(Arrays.asList(model("a", true, "助手")), "a").save(store);
        AiMessageEntryHook hook = AiMessageEntryHook.install(activity, store, (owner, value) -> null, ignored -> { });
        try {
            hook.onActivityResumed(activity);
            measure(root, 400, 600);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            long before = suppressedEvents();
            int reads = activity.fragments.reads;
            for (int i = 0; i < 300; i++) root.getViewTreeObserver().dispatchOnGlobalLayout();
            ShadowLooper.idleMainLooper(149, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals(reads, activity.fragments.reads);
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertEquals("300 events coalesce to one fresh visibility/uniqueness check", reads + 1, activity.fragments.reads);
            assertEquals("Stable discovery and readback should not enter logger again", before, suppressedEvents());
            fragment.hidden = true;
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
            fragment.hidden = false;
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertNotNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
            hook.onActivityPaused(activity);
            ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.HOURS);
            hook.onActivityResumed(activity);
            assertNotNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
        } finally { hook.close(); activity.finish(); }
    }

    private static long suppressedEvents() throws Exception {
        java.lang.reflect.Field field = com.douai.module.diagnostics.DouAiLog.class.getDeclaredField("suppressed");
        field.setAccessible(true);
        return field.getLong(null);
    }

    @Test public void discoveryDistinguishesMissingMessagePageUnknownListAndAmbiguousLists() throws Exception {
        MessageActivity activity = activity();
        try {
            FrameLayout root = root(activity);
            AtomicReference<String> diagnostic = new AtomicReference<>();
            assertNull(AiMessageEntryHook.uniqueMessageList(activity, diagnostic::set));
            assertEquals("fragment_manager_getter=true;visible_message_fragments=0;session_lists=0", diagnostic.get());
            MessagesFragment3 fragment = new MessagesFragment3(root);
            activity.fragments.entries.add(fragment);
            RecyclerView list = list(activity, root);
            list.setLayoutManager(new Object());
            assertNull(AiMessageEntryHook.uniqueMessageList(activity, diagnostic::set));
            assertEquals("fragment_manager_getter=true;visible_message_fragments=1;session_lists=0", diagnostic.get());
            list.setLayoutManager(new MessageListRvLinearLayoutManager());
            assertSame(list, AiMessageEntryHook.uniqueMessageList(activity, diagnostic::set));
            assertEquals("fragment_manager_getter=true;visible_message_fragments=1;session_lists=1", diagnostic.get());
            list(activity, root);
            assertNull(AiMessageEntryHook.uniqueMessageList(activity, diagnostic::set));
            assertEquals("fragment_manager_getter=true;visible_message_fragments=1;session_lists=2", diagnostic.get());
            fragment.hidden = true;
            assertNull(AiMessageEntryHook.uniqueMessageList(activity, diagnostic::set));
            assertEquals("fragment_manager_getter=true;visible_message_fragments=0;session_lists=0", diagnostic.get());
        } finally { activity.finish(); }
    }

    @Test public void limitsDiscoveryToUniqueNativeConversationListOnVisibleMessageFragment() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        RecyclerView list = list(activity, root);
        MessagesFragment3 fragment = new MessagesFragment3(root);
        activity.fragments.entries.add(fragment);
        assertSame(list, AiMessageEntryHook.uniqueMessageList(activity));
        fragment.hidden = true;
        assertNull(AiMessageEntryHook.uniqueMessageList(activity));
        fragment.hidden = false;
        RecyclerView second = list(activity, root);
        assertNull(AiMessageEntryHook.uniqueMessageList(activity));
        root.removeView(second);
        list.setLayoutManager(new Object());
        assertNull(AiMessageEntryHook.uniqueMessageList(activity));
        assertFalse(AiMessageEntryHook.isMessageFragment(Object.class));
        list.setLayoutManager(new MessageListRvLinearLayoutManager());
        ParentFragment parent = new ParentFragment();
        parent.children.entries.add(fragment);
        activity.fragments.entries.clear();
        activity.fragments.entries.add(parent);
        assertSame(list, AiMessageEntryHook.uniqueMessageList(activity));
        parent.hidden = true;
        assertNull(AiMessageEntryHook.uniqueMessageList(activity));
        activity.finish();
    }

    @Test public void retainsNativeSiblingsLayoutAndListWhenContactsAreRemoved() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        View before = new View(activity);
        root.addView(before);
        RecyclerView list = list(activity, root);
        FrameLayout.LayoutParams original = (FrameLayout.LayoutParams) list.getLayoutParams();
        original.topMargin = 7;
        View after = new View(activity);
        root.addView(after);
        AiMessageEntryHook.InlineContacts insertion = AiMessageEntryHook.InlineContacts.attach(activity, list);
        assertNotNull(insertion);
        insertion.bind(Arrays.asList(model("a", true, "助手")), ignored -> { });
        assertSame(before, root.getChildAt(0));
        assertSame(list, root.getChildAt(1));
        assertSame(after, root.getChildAt(2));
        assertSame(insertion.wrapper, root.getChildAt(3));
        assertSame(root, list.getParent());
        assertEquals(7, original.topMargin);
        assertTrue(((FrameLayout.LayoutParams) list.getLayoutParams()).topMargin > 7);
        assertNull(AiMessageEntryHook.InlineContacts.attach(activity, list));
        insertion.restore();
        assertSame(list, root.getChildAt(1));
        assertSame(original, list.getLayoutParams());
        assertEquals(7, ((FrameLayout.LayoutParams) list.getLayoutParams()).topMargin);
        assertEquals(3, root.getChildCount());
        activity.finish();
    }

    @Test public void nativeLoadingViewsAndLaterInflatedOverlaysDoNotCoverOrInterceptContacts() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        RecyclerView list = list(activity, root);
        View loading = new View(activity);
        loading.setBackgroundColor(android.graphics.Color.WHITE);
        loading.setElevation(8f);
        AtomicInteger intercepted = new AtomicInteger();
        loading.setOnTouchListener((view, event) -> { intercepted.incrementAndGet(); return true; });
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        activity.fragments.entries.add(new MessagesFragment3(root));
        MemoryStore store = new MemoryStore();
        new AiModelSettings(Arrays.asList(model("a", true, "助手")), "a").save(store);
        AtomicInteger opened = new AtomicInteger();
        AiMessageEntryHook hook = AiMessageEntryHook.install(activity, store,
                (owner, value) -> { opened.incrementAndGet(); return null; }, ignored -> { });
        try {
            hook.onActivityResumed(activity);
            measure(root, 400, 600);
            View header = root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG);
            assertNotNull(header);
            assertTrue(header.isShown());
            assertTrue(header.getWidth() > 0 && header.getHeight() > 0);
            assertTrue(header.getZ() > loading.getZ());
            assertTrue(root.indexOfChild(header) > root.indexOfChild(loading));
            tap(root, header.getX() + header.getWidth() / 2f, header.getY() + header.getHeight() / 2f);
            assertEquals(1, opened.get());
            assertEquals(0, intercepted.get());

            View inflated = new View(activity);
            inflated.setBackgroundColor(android.graphics.Color.WHITE);
            inflated.setElevation(20f);
            inflated.setOnTouchListener((view, event) -> { intercepted.incrementAndGet(); return true; });
            root.addView(inflated, new FrameLayout.LayoutParams(-1, -1));
            hook.onActivityResumed(activity);
            measure(root, 400, 600);
            assertSame(header, root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
            assertTrue(header.getZ() > inflated.getZ());
            assertTrue(root.indexOfChild(header) > root.indexOfChild(inflated));
            tap(root, header.getX() + header.getWidth() / 2f, header.getY() + header.getHeight() / 2f);
            assertEquals(2, opened.get());
            assertEquals(0, intercepted.get());
            assertSame(root, list.getParent());
            assertTrue(list.getTop() >= header.getBottom());
        } finally { hook.close(); activity.finish(); }
    }

    @Test public void nativeConstraintIdsAndSiblingAnchorsRemainValidAndReparentCleansHeader() throws Exception {
        MessageActivity activity = activity();
        ConstraintLayout root = new ConstraintLayout(activity);
        activity.setContentView(root);
        RecyclerView list = new RecyclerView(activity);
        list.setId(123);
        ConstraintLayout.LayoutParams original = new ConstraintLayout.LayoutParams(-1, -1);
        original.topToTop = 0;
        original.bottomToBottom = 0;
        original.topMargin = 9;
        root.addView(list, original);
        View sibling = new View(activity);
        ConstraintLayout.LayoutParams siblingParams = new ConstraintLayout.LayoutParams(-1, 12);
        siblingParams.topToBottom = list.getId();
        root.addView(sibling, siblingParams);
        AiMessageEntryHook.InlineContacts insertion = AiMessageEntryHook.InlineContacts.attach(activity, list);
        assertNotNull(insertion);
        insertion.bind(Arrays.asList(model("a", true, "助手")), ignored -> { });
        assertSame(list, root.findViewById(123));
        assertSame(root, list.getParent());
        assertSame(siblingParams, sibling.getLayoutParams());
        assertEquals(123, siblingParams.topToBottom);
        ConstraintLayout.LayoutParams header = (ConstraintLayout.LayoutParams) insertion.wrapper.getLayoutParams();
        assertEquals(0, header.topToTop);
        assertEquals(-1, header.bottomToBottom);
        assertEquals(0, ((ConstraintLayout.LayoutParams) list.getLayoutParams()).bottomToBottom);
        assertEquals(9, original.topMargin);
        insertion.restore();
        assertSame(original, list.getLayoutParams());
        insertion = AiMessageEntryHook.InlineContacts.attach(activity, list);
        insertion.bind(Arrays.asList(model("a", true, "助手")), ignored -> { });
        root.removeView(list);
        ConstraintLayout replacement = new ConstraintLayout(activity);
        replacement.addView(list);
        assertFalse(insertion.isIntact());
        insertion.restore();
        assertNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
        assertSame(replacement, list.getParent());
        assertSame(original, list.getLayoutParams());
        insertion = AiMessageEntryHook.InlineContacts.attach(activity, list);
        insertion.bind(Arrays.asList(model("a", true, "助手")), ignored -> { });
        replacement.removeView(list);
        FrameLayout movedAgain = new FrameLayout(activity);
        FrameLayout.LayoutParams hostReplacement = new FrameLayout.LayoutParams(-1, -1);
        movedAgain.addView(list, hostReplacement);
        insertion.restore();
        assertNull(replacement.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
        assertSame(hostReplacement, list.getLayoutParams());
        activity.finish();
    }

    @Test public void contactsUseIndependentSwitchNameAndClickLatestEnabledProfile() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        RecyclerView list = list(activity, root);
        activity.fragments.entries.add(new MessagesFragment3(root));
        MemoryStore store = new MemoryStore();
        AiModelConfig enabled = model("a", true, "我的助手");
        AiModelConfig disabled = model("b", false, "隐藏助手");
        new AiModelSettings(Arrays.asList(enabled, disabled), "b").save(store);
        AtomicReference<AiModelConfig> opened = new AtomicReference<>();
        AiMessageEntryHook hook = AiMessageEntryHook.install(activity, store, (owner, value) -> {
            opened.set(value); return null;
        }, ignored -> { });
        try {
            hook.onActivityResumed(activity);
            View contact = root.findViewWithTag("DouAi.AiContact.a");
            assertNotNull(contact);
            assertNull(root.findViewWithTag("DouAi.AiContact.b"));
            assertTrue(containsText(contact, "我的助手"));
            AiModelConfig renamed = model("a", true, "新名字");
            new AiModelSettings(Arrays.asList(renamed, disabled), "b").save(store);
            contact.performClick();
            assertEquals("新名字", opened.get().messageContactDisplayName());
            opened.set(null);
            new AiModelSettings(Arrays.asList(model("a", false, "新名字"), disabled), "b").save(store);
            contact.performClick();
            assertNull(opened.get());
            hook.onActivityResumed(activity);
            assertSame(list, root.getChildAt(0));
            assertNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
        } finally { hook.close(); activity.finish(); }
    }

    @Test public void realConstraintSolverKeepsDistinctWidgetsAndReservesNativeMessagesWhenSmall() throws Exception {
        MessageActivity activity = activity();
        ConstraintLayout root = new ConstraintLayout(activity);
        activity.setContentView(root);
        RecyclerView list = new RecyclerView(activity);
        list.setId(321);
        ConstraintLayout.LayoutParams original = new ConstraintLayout.LayoutParams(-1, -1);
        original.topToTop = 0;
        original.bottomToBottom = 0;
        original.topMargin = 8;
        root.addView(list, original);
        measure(root, 400, 600);
        AiMessageEntryHook.InlineContacts insertion = AiMessageEntryHook.InlineContacts.attach(activity, list);
        insertion.bind(Arrays.asList(model("a", true, "A"), model("b", true, "B"), model("c", true, "C")), ignored -> { });
        ConstraintLayout.LayoutParams header = (ConstraintLayout.LayoutParams) insertion.wrapper.getLayoutParams();
        ConstraintLayout.LayoutParams offset = (ConstraintLayout.LayoutParams) list.getLayoutParams();
        assertNotSame(widget(original), widget(header));
        assertNotSame(widget(original), widget(offset));
        assertNotSame(widget(header), widget(offset));
        measure(root, 400, 600);
        assertEquals(8, insertion.wrapper.getTop());
        assertEquals(insertion.wrapper.getBottom(), list.getTop());
        assertEquals(600, list.getBottom());
        measure(root, 400, 100);
        insertion.resize();
        measure(root, 400, 100);
        assertTrue(insertion.wrapper.getHeight() <= 46);
        assertTrue(list.getHeight() >= 46);
        assertEquals(insertion.wrapper.getBottom(), list.getTop());
        assertEquals(100, list.getBottom());
        insertion.restore();
        activity.finish();
    }

    @Test public void realHostPreferenceEditsRefreshWithoutLayoutOrActivityResume() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        RecyclerView list = list(activity, root);
        activity.fragments.entries.add(new MessagesFragment3(root));
        HostSettingsStore store = new HostSettingsStore(activity);
        new AiModelSettings(Arrays.asList(model("a", true, "初始名字")), "a").save(store);
        AiMessageEntryHook hook = AiMessageEntryHook.install(activity, store, (owner, value) -> null, ignored -> { });
        try {
            hook.onActivityResumed(activity);
            assertTrue(containsText(root.findViewWithTag("DouAi.AiContact.a"), "初始名字"));
            new AiModelSettings(Arrays.asList(model("a", true, "修改名字")), "a").save(store);
            ShadowLooper.idleMainLooper();
            assertTrue(containsText(root.findViewWithTag("DouAi.AiContact.a"), "修改名字"));
            new AiModelSettings(Arrays.asList(model("a", false, "修改名字")), "a").save(store);
            ShadowLooper.idleMainLooper();
            assertNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
            assertSame(root, list.getParent());
        } finally { hook.close(); activity.finish(); }
    }

    @Test public void pickerPausePreservesConversationAndDestroyDismissesIt() throws Exception {
        MessageActivity activity = activity();
        FrameLayout root = root(activity);
        list(activity, root);
        activity.fragments.entries.add(new MessagesFragment3(root));
        MemoryStore store = new MemoryStore();
        new AiModelSettings(Arrays.asList(model("a", true, "助手")), "a").save(store);
        AtomicReference<Dialog> opened = new AtomicReference<>();
        AiMessageEntryHook hook = AiMessageEntryHook.install(activity, store, (owner, value) -> {
            Dialog dialog = new Dialog(owner);
            dialog.show();
            opened.set(dialog);
            return dialog;
        }, ignored -> { });
        try {
            hook.onActivityResumed(activity);
            root.findViewWithTag("DouAi.AiContact.a").performClick();
            assertTrue(opened.get().isShowing());
            hook.onActivityPaused(activity);
            assertTrue(opened.get().isShowing());
            assertNull(root.findViewWithTag(AiMessageEntryHook.CONTACTS_TAG));
            hook.onActivityDestroyed(activity);
            assertFalse(opened.get().isShowing());
        } finally { hook.close(); activity.finish(); }
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private static void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
    private static void tap(View root, float x, float y) {
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(0, 1, MotionEvent.ACTION_UP, x, y, 0);
        try { root.dispatchTouchEvent(down); root.dispatchTouchEvent(up); ShadowLooper.idleMainLooper(); }
        finally { down.recycle(); up.recycle(); }
    }
    private static Object widget(ConstraintLayout.LayoutParams params) throws Exception {
        return params.getClass().getMethod("getConstraintWidget").invoke(params);
    }

    private static MessageActivity activity() { return Robolectric.buildActivity(MessageActivity.class).setup().get(); }
    private static FrameLayout root(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        return root;
    }
    private static RecyclerView list(Activity activity, FrameLayout root) {
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutManager(new MessageListRvLinearLayoutManager());
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));
        return list;
    }
    private static AiModelConfig model(String id, boolean enabled, String display) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id)
                .put("apiEndpoint", "https://example.com/v1/chat/completions").put("modelName", "test")
                .put("messageContactEnabled", enabled).put("messageContactName", display));
    }
    public static final class MessageActivity extends Activity {
        final FragmentManager fragments = new FragmentManager();
        public FragmentManager getSupportFragmentManager() { return fragments; }
    }
    public static final class FragmentManager {
        final List<Object> entries = new ArrayList<>();
        int reads;
        public List<Object> getFragments() { reads++; return entries; }
    }
    public static final class ParentFragment {
        final FragmentManager children = new FragmentManager();
        boolean hidden;
        public boolean isHidden() { return hidden; }
        public FragmentManager getChildFragmentManager() { return children; }
    }
    private static final class MemoryStore implements SettingsStore {
        final Map<String, String> values = new HashMap<>();
        public FeatureSettings load() { return null; }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { return fallback; }
        public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { }
        public void putString(String key, String value) { values.put(key, value); }
    }
}
