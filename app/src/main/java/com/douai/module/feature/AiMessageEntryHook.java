package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SettingsRepository;
import com.douai.module.ui.AiContactAvatar;
import com.douai.module.ui.ModuleTheme;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Adds local AI contacts above the native conversation list on the messages page. */
public final class AiMessageEntryHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    static final String LIST_MANAGER = "com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.MessageListRvLinearLayoutManager";
    static final String CONTACTS_TAG = "DouAi.MessageContacts";
    private static final String FRAGMENT_PACKAGE = "com.ss.android.ugc.aweme.im.sdk.module.session.rips.";
    private final Application application;
    private final SettingsStore store;
    private final ContactOpener opener;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Monitor> monitors = new WeakHashMap<>();
    private final Map<Activity, WeakReference<Dialog>> conversations = new WeakHashMap<>();
    private final SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private volatile boolean closed;

    public interface ContactOpener { Dialog open(Activity activity, AiModelConfig model); }

    private AiMessageEntryHook(Application application, SettingsStore store,
                               ContactOpener opener, Consumer<String> log) {
        this.application = application;
        this.store = store;
        this.opener = opener;
        this.log = log == null ? ignored -> { } : log;
        preferences = application.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferenceListener = (changed, key) -> {
            if (!closed && (key == null || AiModelSettings.KEY.equals(key))) runOnMain(() -> {
                if (!closed) for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.refresh();
            });
        };
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        application.registerActivityLifecycleCallbacks(this);
    }

    public static AiMessageEntryHook install(Context context, SettingsStore store,
                                              ContactOpener opener, Consumer<String> log) {
        if (context == null || !(context.getApplicationContext() instanceof Application)
                || store == null || opener == null) {
            throw new IllegalArgumentException("Host application, settings and conversation opener are required");
        }
        return new AiMessageEntryHook((Application) context.getApplicationContext(), store, opener, log);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (closed || activity == null) return;
        runOnMain(() -> {
            if (closed || activity.isDestroyed() || activity.getWindow() == null) return;
            Monitor monitor = monitors.get(activity);
            if (monitor == null) {
                monitor = new Monitor(activity);
                monitors.put(activity, monitor);
            }
            monitor.attach();
            monitor.refresh();
        });
    }

    @Override public void onActivityPaused(Activity activity) { removeMonitor(activity); }

    @Override public void onActivityDestroyed(Activity activity) {
        removeMonitor(activity);
        runOnMain(() -> {
            WeakReference<Dialog> reference = conversations.remove(activity);
            dismiss(reference == null ? null : reference.get());
        });
    }

    private void removeMonitor(Activity activity) {
        runOnMain(() -> {
            Monitor monitor = monitors.remove(activity);
            if (monitor != null) monitor.close();
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        application.unregisterActivityLifecycleCallbacks(this);
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        runOnMain(() -> {
            for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.close();
            monitors.clear();
            for (WeakReference<Dialog> reference : conversations.values()) dismiss(reference.get());
            conversations.clear();
        });
    }

    private void openConversation(Activity activity, String modelId) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) { DouAiLog.event("ai_contacts", "eligibility", "activity_unavailable", ""); return; }
        DouAiLog.Operation trace = DouAiLog.begin("ai_contacts", "contact_click");
        try {
            // Read again at click time so removed/disabled contacts never use a stale API profile.
            for (AiModelConfig model : AiModelSettings.read(store).configs) {
                if (!model.id.equals(modelId) || !model.messageContactEnabled) continue;
                WeakReference<Dialog> reference = conversations.get(activity);
                Dialog previous = reference == null ? null : reference.get();
                if (previous != null && previous.isShowing()) { trace.finish(DouAiLog.Outcome.SKIPPED, "already_showing", "dialog_showing=true"); return; }
                Dialog dialog = opener.open(activity, model);
                if (dialog != null) conversations.put(activity, new WeakReference<>(dialog));
                trace.finish(dialog != null && dialog.isShowing() ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED,
                        "dialog_readback", "dialog_present=" + (dialog != null) + ";showing=" + (dialog != null && dialog.isShowing()));
                return;
            }
            trace.finish(DouAiLog.Outcome.SKIPPED, "model_removed_or_disabled", "");
        } catch (RuntimeException failure) {
            trace.error("open_dialog", failure); trace.finish(DouAiLog.Outcome.FAILED, "dialog_open_failed", "");
            safeLog("Could not open AI contact: " + failure.getClass().getSimpleName());
        }
    }

    private final class Monitor implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> owner;
        private ViewTreeObserver observer;
        private InlineContacts insertion;
        private String appliedSettings;
        private String failedSettings;
        private String cachedSettings;
        private List<AiModelConfig> cachedContacts = Collections.emptyList();
        private boolean pending;
        private boolean active = true;
        private final Runnable refreshTask = () -> { pending = false; if (active) refresh(); };

        Monitor(Activity activity) { owner = new WeakReference<>(activity); }

        void attach() {
            Activity activity = owner.get();
            if (activity == null || activity.getWindow() == null) return;
            ViewTreeObserver next = activity.getWindow().getDecorView().getViewTreeObserver();
            if (next == observer || !next.isAlive()) return;
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = next;
            observer.addOnGlobalLayoutListener(this);
        }

        @Override public void onGlobalLayout() {
            // One delayed refresh coalesces host list relayouts and model-setting changes.
            if (!pending && active && !closed) {
                pending = true;
                main.postDelayed(refreshTask, 150);
            }
        }

        void refresh() {
            Activity activity = owner.get();
            if (!active || closed || activity == null || activity.isDestroyed() || activity.isFinishing()) return;
            try {
                String serialized = store.getString(AiModelSettings.KEY, "");
                if (!serialized.equals(cachedSettings)) {
                    cachedContacts = enabledContacts(AiModelSettings.parse(serialized));
                    cachedSettings = serialized;
                }
                List<AiModelConfig> contacts = cachedContacts;
                if (contacts.isEmpty()) { DouAiLog.event("ai_contacts", "eligibility", "no_enabled_contacts", "configured_count=0"); restore(); return; }
                View list = uniqueMessageList(activity, detail -> DouAiLog.event("ai_contacts", "discovery",
                        "message_list_inspected", detail));
                if (insertion != null && (insertion.list != list || !insertion.isIntact())) restore();
                if (list == null) { DouAiLog.event("ai_contacts", "eligibility", "message_list_not_unique_or_missing", ""); return; }
                if (insertion == null) {
                    insertion = InlineContacts.attach(activity, list);
                    appliedSettings = null;
                }
                if (insertion == null) {
                    DouAiLog.event("ai_contacts", "eligibility", "message_list_container_unsupported",
                            "parent_class=" + (list.getParent() == null ? "none" : list.getParent().getClass().getName())
                                    + ";layout_class=" + (list.getLayoutParams() == null ? "none" : list.getLayoutParams().getClass().getName())
                                    + ";layout_height=" + (list.getLayoutParams() == null ? 0 : list.getLayoutParams().height));
                    return;
                }
                if (!serialized.equals(appliedSettings)) {
                    insertion.bind(contacts, id -> openConversation(activity, id));
                    appliedSettings = serialized;
                }
                insertion.resize();
                DouAiLog.event("ai_contacts", "view_applied", "insertion_readback", "intact=" + insertion.isIntact()
                        + ";contact_count=" + contacts.size() + ";attached=" + insertion.wrapper.isAttachedToWindow()
                        + ";shown=" + insertion.wrapper.isShown() + ";width=" + insertion.wrapper.getWidth()
                        + ";height=" + insertion.wrapper.getHeight() + ";sibling_z=" + insertion.highestSiblingZ
                        + ";contact_z=" + insertion.wrapper.getZ());
                failedSettings = null;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                restore();
                String serialized = store.getString(AiModelSettings.KEY, "");
                if (!serialized.equals(failedSettings)) {
                    failedSettings = serialized;
                    DouAiLog.failure("ai_contacts", "message_contact_refresh", failure);
                    safeLog("AI message contacts unavailable: " + failure.getClass().getSimpleName());
                }
            }
        }

        void restore() {
            if (insertion != null) insertion.restore();
            insertion = null;
            appliedSettings = null;
        }

        void close() {
            active = false;
            pending = false;
            main.removeCallbacks(refreshTask);
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
            restore();
        }
    }

    static List<AiModelConfig> enabledContacts(AiModelSettings settings) {
        List<AiModelConfig> result = new ArrayList<>();
        for (AiModelConfig model : settings.configs) if (model.messageContactEnabled) result.add(model);
        return result;
    }

    /** Exact host fragment and layout-manager contracts avoid chat, commerce and generic lists. */
    static View uniqueMessageList(Activity activity) throws ReflectiveOperationException {
        return uniqueMessageList(activity, ignored -> { });
    }

    static View uniqueMessageList(Activity activity, Consumer<String> diagnostic) throws ReflectiveOperationException {
        Method getter;
        try { getter = activity.getClass().getMethod("getSupportFragmentManager"); }
        catch (NoSuchMethodException absent) { diagnostic.accept("fragment_manager_getter=false"); return null; }
        Object manager = getter.invoke(activity);
        List<View> matches = new ArrayList<>();
        int[] messageFragments = {0};
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectMessageLists(manager, matches, visited, messageFragments, 0);
        diagnostic.accept("fragment_manager_getter=true;visible_message_fragments=" + messageFragments[0]
                + ";session_lists=" + matches.size());
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void collectMessageLists(Object manager, List<View> matches, Set<Object> visited,
                                             int[] messageFragments, int depth) throws ReflectiveOperationException {
        if (manager == null || depth > 12 || !visited.add(manager)) return;
        Object fragments = manager.getClass().getMethod("getFragments").invoke(manager);
        if (!(fragments instanceof List<?>)) return;
        for (Object fragment : (List<?>) fragments) {
            if (fragment == null) continue;
            boolean hidden = Boolean.TRUE.equals(fragment.getClass().getMethod("isHidden").invoke(fragment));
            if (hidden) continue;
            if (isMessageFragment(fragment.getClass())) {
                Object view = fragment.getClass().getMethod("getView").invoke(fragment);
                if (view instanceof View && ((View) view).isShown()) {
                    messageFragments[0]++;
                    collectSessionLists((View) view, matches);
                }
                // Children of an identified message fragment are already covered by its view tree.
            } else {
                Object children = fragment.getClass().getMethod("getChildFragmentManager").invoke(fragment);
                collectMessageLists(children, matches, visited, messageFragments, depth + 1);
            }
        }
    }

    static boolean isMessageFragment(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if ((FRAGMENT_PACKAGE + "MessagesFragment3").equals(name)
                    || (FRAGMENT_PACKAGE + "MessagesFragment5").equals(name)) return true;
        }
        return false;
    }

    private static void collectSessionLists(View view, List<View> matches) {
        if (!view.isShown()) return;
        if (isSessionList(view)) { matches.add(view); return; }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSessionLists(group.getChildAt(i), matches);
        }
    }

    private static boolean isSessionList(View view) {
        boolean recycler = false;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            recycler |= "androidx.recyclerview.widget.RecyclerView".equals(type.getName());
        }
        if (!recycler) return false;
        try {
            Object manager = view.getClass().getMethod("getLayoutManager").invoke(view);
            return manager != null && LIST_MANAGER.equals(manager.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException absent) { return false; }
    }

    static final class InlineContacts {
        final View list;
        final ViewGroup parent;
        final ViewGroup.LayoutParams originalParams;
        private ViewGroup.LayoutParams appliedParams;
        private int modelCount;
        private int appliedHeight = -1;
        private float highestSiblingZ;
        final LinearLayout wrapper;
        final ScrollView scroll;
        final LinearLayout rows;

        private InlineContacts(Activity activity, View list, ViewGroup parent) {
            this.list = list;
            this.parent = parent;
            originalParams = list.getLayoutParams();
            wrapper = new LinearLayout(activity);
            wrapper.setId(View.generateViewId());
            wrapper.setTag(CONTACTS_TAG);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            scroll = new ScrollView(activity);
            scroll.setFillViewport(false);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.setScrollbarFadingEnabled(false);
            rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
            wrapper.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            // The native recycler keeps its ID, direct parent and place among native siblings.
            // This preserves ConstraintLayout references and native findViewById casts.
            parent.addView(wrapper, copyParams(originalParams));
        }

        static InlineContacts attach(Activity activity, View list) {
            if (!(list.getParent() instanceof ViewGroup)) return null;
            ViewGroup parent = (ViewGroup) list.getParent();
            if (!safeContainer(parent, list)) return null;
            return new InlineContacts(activity, list, parent);
        }

        /** Verified native layouts use ConstraintLayout; FrameLayout is the equivalent plain host. */
        private static boolean safeContainer(ViewGroup parent, View list) {
            ViewGroup.LayoutParams params = list.getLayoutParams();
            if (!(params instanceof ViewGroup.MarginLayoutParams)
                    || params.height == ViewGroup.LayoutParams.WRAP_CONTENT) return false;
            boolean frame = parent instanceof android.widget.FrameLayout;
            boolean constraint = false;
            for (Class<?> type = parent.getClass(); type != null; type = type.getSuperclass()) {
                constraint |= "androidx.constraintlayout.widget.ConstraintLayout".equals(type.getName());
            }
            if (!frame && !constraint) return false;
            // A second monitor must not insert another header for the same native list.
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (CONTACTS_TAG.equals(parent.getChildAt(i).getTag())) return false;
            }
            return true;
        }

        private static ViewGroup.LayoutParams copyParams(ViewGroup.LayoutParams source) {
            try {
                // ConstraintLayout's copy constructor shares the original solver widget. A new
                // (width,height) instance retains a separate widget; only public layout values copy.
                Constructor<?> create = source.getClass().getConstructor(int.class, int.class);
                ViewGroup.LayoutParams result = (ViewGroup.LayoutParams) create.newInstance(source.width, source.height);
                for (Field field : source.getClass().getFields()) {
                    int flags = field.getModifiers();
                    if (Modifier.isStatic(flags) || Modifier.isFinal(flags)) continue;
                    if (field.getType().isPrimitive() || field.getType() == String.class) {
                        field.set(result, field.get(source));
                    }
                }
                if (source instanceof ViewGroup.MarginLayoutParams && result instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) source;
                    if (margins.isMarginRelative()) {
                        ((ViewGroup.MarginLayoutParams) result).setMarginStart(margins.getMarginStart());
                        ((ViewGroup.MarginLayoutParams) result).setMarginEnd(margins.getMarginEnd());
                    }
                }
                return result;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native conversation layout parameters cannot be copied", failure);
            }
        }

        private static void clearBottomAnchor(ViewGroup.LayoutParams params, String field) {
            try { params.getClass().getField(field).setInt(params, -1); }
            catch (NoSuchFieldException plainLayout) { /* FrameLayout has no constraints. */ }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        void bind(List<AiModelConfig> models, Consumer<String> open) {
            modelCount = models.size();
            appliedHeight = -1;
            rows.removeAllViews();
            Activity activity = (Activity) wrapper.getContext();
            for (AiModelConfig model : models) {
                LinearLayout row = new LinearLayout(activity);
                row.setTag("DouAi.AiContact." + model.id);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
                row.setBackgroundColor(Color.WHITE);
                Bitmap avatar = AiContactAvatar.load(model.messageContactAvatar);
                if (avatar != null) {
                    ImageView image = new ImageView(activity);
                    image.setImageBitmap(avatar);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable shape = new GradientDrawable();
                    shape.setColor(0xffe1f3ed);
                    shape.setCornerRadius(dp(activity, 24));
                    image.setBackground(shape);
                    image.setClipToOutline(true);
                    row.addView(image, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
                } else {
                    TextView placeholder = new TextView(activity);
                    placeholder.setText("AI");
                    placeholder.setTextSize(16);
                    placeholder.setTextColor(0xff00776c);
                    placeholder.setGravity(Gravity.CENTER);
                    GradientDrawable shape = new GradientDrawable();
                    shape.setColor(0xffe1f3ed);
                    shape.setCornerRadius(dp(activity, 24));
                    placeholder.setBackground(shape);
                    row.addView(placeholder, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
                }
                LinearLayout labels = new LinearLayout(activity);
                labels.setOrientation(LinearLayout.VERTICAL);
                labels.setPadding(dp(activity, 12), 0, 0, 0);
                TextView title = new TextView(activity);
                title.setText(model.messageContactDisplayName());
                title.setTextColor(0xff20282c);
                title.setTextSize(16);
                title.setSingleLine(true);
                labels.addView(title);
                TextView detail = new TextView(activity);
                detail.setText("AI · " + model.modelName);
                detail.setTextColor(0xff636e74);
                detail.setTextSize(12);
                detail.setSingleLine(true);
                labels.addView(detail);
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                row.setContentDescription(model.messageContactDisplayName() + "，AI 联系人，点按开始对话");
                row.setFocusable(true);
                row.setOnClickListener(ignored -> open.accept(model.id));
                rows.addView(row, new LinearLayout.LayoutParams(-1, dp(activity, 64)));
            }
            resize();
            ModuleTheme.bind(wrapper);
        }

        void resize() {
            // Native loading/empty views and inflated stubs share this container with the list.
            if (wrapper.getParent() == parent && parent.indexOfChild(wrapper) != parent.getChildCount() - 1) {
                wrapper.bringToFront();
            }
            highestSiblingZ = 0;
            for (int index = 0; index < parent.getChildCount(); index++) {
                View sibling = parent.getChildAt(index);
                if (sibling != wrapper) {
                    float siblingZ = sibling.getZ();
                    if (Float.isFinite(siblingZ) && siblingZ > highestSiblingZ) highestSiblingZ = siblingZ;
                }
            }
            float contactElevation = highestSiblingZ + 1f;
            wrapper.setElevation(Float.isFinite(contactElevation) ? contactElevation : highestSiblingZ);
            Activity activity = (Activity) wrapper.getContext();
            int headerHeight = dp(activity, 64 * modelCount);
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) originalParams;
            int available = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom()
                    - margins.topMargin - margins.bottomMargin;
            if (originalParams.height > 0) available = Math.min(available, originalParams.height);
            // Expand all contacts when they fit, leaving room for at least one native message row.
            if (available > 0) headerHeight = Math.min(headerHeight,
                    Math.max(1, available - Math.min(dp(activity, 64), (available + 1) / 2)));
            if (headerHeight == appliedHeight) return;
            scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, headerHeight));
            ViewGroup.LayoutParams headerParams = copyParams(originalParams);
            headerParams.height = headerHeight;
            clearBottomAnchor(headerParams, "bottomToTop");
            clearBottomAnchor(headerParams, "bottomToBottom");
            clearBottomAnchor(headerParams, "baselineToBaseline");
            clearBottomAnchor(headerParams, "baselineToTop");
            clearBottomAnchor(headerParams, "baselineToBottom");
            wrapper.setLayoutParams(headerParams);
            appliedParams = copyParams(originalParams);
            ((ViewGroup.MarginLayoutParams) appliedParams).topMargin += headerHeight;
            if (appliedParams.height > 0) appliedParams.height = Math.max(1, appliedParams.height - headerHeight);
            list.setLayoutParams(appliedParams);
            appliedHeight = headerHeight;
        }

        boolean isIntact() {
            return wrapper.getParent() == parent && list.getParent() == parent
                    && (appliedParams == null || list.getLayoutParams() == appliedParams);
        }

        void restore() {
            rows.removeAllViews();
            if (wrapper.getParent() instanceof ViewGroup) {
                ((ViewGroup) wrapper.getParent()).removeView(wrapper);
            }
            // A host refresh may move the recycler or replace its params before the next layout.
            if (list.getLayoutParams() == appliedParams) {
                list.setLayoutParams(originalParams);
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }

    private void safeLog(String message) { try { log.accept(message); } catch (Throwable ignored) { } }
    private static void dismiss(Dialog dialog) {
        if (dialog != null && dialog.isShowing()) try { dialog.dismiss(); } catch (RuntimeException ignored) { }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
