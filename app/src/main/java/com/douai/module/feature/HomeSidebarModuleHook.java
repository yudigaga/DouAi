package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SidebarSettings;
import com.douai.module.diagnostics.DouAiLog;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shows one module list in the home sidebar and retains native sections for restoration. */
public final class HomeSidebarModuleHook implements AutoCloseable {
    private final BehaviorHookGroup hooks;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<WeakReference<Binding>> bindings = new ArrayList<>();
    private final List<Owner> owners = new ArrayList<>();
    private final Set<Owner> pendingRefresh = Collections.newSetFromMap(new IdentityHashMap<>());
    private final BiConsumer<Activity, String> launcher;
    private final Runnable closeSidebar;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private HomeSidebarModuleHook(LocalOptions options, BiConsumer<Activity, String> launcher,
                                  Runnable closeSidebar, Consumer<String> log) {
        this.options = options;
        this.launcher = launcher;
        this.closeSidebar = closeSidebar == null ? () -> { } : closeSidebar;
        hooks = new BehaviorHookGroup("home_sidebar", log == null ? ignored -> { } : log);
    }

    public static HomeSidebarModuleHook install(XposedInterface framework, ClassLoader loader, LocalOptions options,
            BiConsumer<Activity, String> launcher, Runnable closeSidebar, Consumer<String> log) throws Exception {
        if (framework == null || loader == null || launcher == null) throw new IllegalArgumentException("Home sidebar bindings are required");
        Class<?> moduleType = type(loader, "X.1ZZT");
        Class<?> registryType = type(loader, "X.1Zan");
        Class<?> homeRegistry = type(loader, "X.1ZZf");
        if (!registryType.isAssignableFrom(homeRegistry)) throw new IllegalStateException("Home sidebar registry changed");
        Method moduleId = method(moduleType, "LIZLLL", String.class);
        CacheAccess cache = new CacheAccess(loader, registryType, moduleType, moduleId);
        Class<?> legacy = type(loader, "com.ss.android.ugc.aweme.sidebar.panel.home.HomeSideBarFragmentV2");
        Class<?> combined = type(loader, "com.ss.android.ugc.aweme.sidebar.panel.combine.HomeSideBarLogicManager");
        Class<?> viewModel = type(loader, "com.ss.android.ugc.aweme.sidebar.core.viewmodel.HomeSideBarContainerViewModel");
        OwnerAccess[] accesses = {
                new OwnerAccess(legacy, "jM", "onPageSelected", int.class, "D", "w", "y", homeRegistry, viewModel,
                        moduleType, null),
                new OwnerAccess(combined, "LIZ", "LJJI", String.class, "j", "d", "f", homeRegistry, viewModel,
                        moduleType, "U")
        };
        HomeSidebarModuleHook feature = new HomeSidebarModuleHook(options, launcher, closeSidebar, log);
        try {
            for (OwnerAccess access : accesses) {
                feature.hooks.add(framework, access.build, chain -> {
                    Object result = chain.proceed();
                    if (!feature.canUpdate()) return result;
                    try {
                        Object owner = chain.getThisObject();
                        Owner reference = feature.remember(owner, access, cache);
                        Object list = access.list.get(owner);
                        if (!matchesContainer(list, chain.getArg(1))) {
                            event("module_container_preserved", "main=false;ready=" + (list != null));
                            return result;
                        }
                        feature.queueRefresh(reference);
                        String family = cache.family(chain.getArg(0));
                        event("module_candidate", "family=" + family + ";result="
                                + (result == null ? "empty" : result.getClass().getSimpleName()));
                        if (result instanceof ViewGroup && ("legacy_common".equals(family) || "combined_common".equals(family)))
                            return feature.bind((ViewGroup) result, reference, "build");
                    } catch (Exception | LinkageError unavailable) { preserved("build", unavailable); }
                    return result;
                });
                feature.hooks.add(framework, access.show, chain -> {
                    Object result = chain.proceed();
                    if (!feature.canUpdate()) return result;
                    try {
                        Object owner = chain.getThisObject();
                        feature.refresh(feature.remember(owner, access, cache), "show");
                    } catch (Exception | LinkageError unavailable) { preserved("show", unavailable); }
                    return result;
                });
            }
            feature.hooks.add(framework, method(combined, "LIZIZ", void.class, String.class), chain -> {
                Object result = chain.proceed();
                if (feature.canUpdate()) {
                    try { feature.refresh(feature.remember(chain.getThisObject(), accesses[1], cache), "side_changed"); }
                    catch (Exception | LinkageError unavailable) { preserved("side_changed", unavailable); }
                }
                return result;
            });
            return feature;
        } catch (Exception | LinkageError failure) {
            feature.close();
            throw failure;
        }
    }

    static boolean matchesSection(boolean home, String id) { return home && "service_module".equals(id); }

    static boolean matchesContainer(Object list, Object suppliedParent) { return list != null && list == suppliedParent; }

    static boolean matchesSection(boolean home, String id, String kind, boolean hasMoreComponent) {
        return home && ("service_module".equals(id)
                || "more_function_view_module".equals(kind) && hasMoreComponent);
    }

    private boolean canUpdate() { return !closed && Looper.myLooper() == Looper.getMainLooper(); }

    private Owner remember(Object value, OwnerAccess access, CacheAccess cache) {
        owners.removeIf(owner -> owner.value.get() == null);
        for (Owner owner : owners) if (owner.value.get() == value) return owner;
        Owner owner = new Owner(new WeakReference<>(value), access, cache);
        owners.add(owner);
        return owner;
    }

    private void queueRefresh(Owner owner) {
        if (!pendingRefresh.add(owner)) return;
        main.post(() -> {
            pendingRefresh.remove(owner);
            if (!canUpdate()) return;
            try { refresh(owner, "assembled"); }
            catch (Exception | LinkageError unavailable) { preserved("assembled", unavailable); }
        });
    }

    private ViewGroup bind(ViewGroup nativeView, Owner owner, String source) throws ReflectiveOperationException {
        Binding existing = nativeView instanceof Section ? ((Section) nativeView).binding : null;
        if (existing == null && nativeView.getParent() instanceof Section)
            existing = ((Section) nativeView.getParent()).binding;
        if (existing != null) {
            existing.owner = owner;
            apply(existing);
            return existing.view();
        }
        if (!enabled(options) || !owner.access.showsHome(owner.value.get())) return nativeView;
        Binding binding = Binding.attach(nativeView, owner.access.dark(owner.value.get()), this::openEntry);
        binding.owner = owner;
        binding.primary = false;
        try { apply(binding); }
        catch (RuntimeException | LinkageError failure) { binding.rollbackAttached(); throw failure; }
        bindings.removeIf(reference -> reference.get() == null);
        bindings.add(new WeakReference<>(binding));
        event("module_bound", "source=" + source + ";enabled=" + enabled(options)
                + ";selected=" + SidebarSettings.selected(options).size() + ";ui_result=unconfirmed");
        return binding.view();
    }

    private void apply(Binding binding) {
        boolean home = false;
        try {
            Owner owner = binding.owner;
            Object value = owner == null ? null : owner.value.get();
            home = value != null && owner.access.showsHome(value);
        } catch (IllegalAccessException unavailable) { preserved("scope", unavailable); }
        boolean active = enabled(options) && home && !binding.notificationCard;
        if (!active) binding.restorePosition();
        binding.update(active, SidebarSettings.selected(options));
    }

    private void refresh(Owner reference, String source) throws ReflectiveOperationException {
        Object owner = reference.value.get();
        if (owner == null) return;
        for (WeakReference<Binding> weak : bindings) {
            Binding binding = weak.get();
            if (binding != null && binding.owner == reference) apply(binding);
        }
        if (!reference.access.showsHome(owner)) {
            event("module_scope", "source=" + source + ";home=false;native_restored=true");
            return;
        }
        ViewGroup list = (ViewGroup) reference.access.list.get(owner);
        Object registry = reference.access.registry.get(owner);
        if (list == null || registry == null) {
            event("module_pending", "source=" + source + ";reason=owner_not_ready");
            return;
        }
        Set<ViewGroup> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        CacheAccess cache = reference.cache;
        List<Object> components = cache.components(registry);
        int candidates = 0;
        for (Object component : components) {
            if (!cache.componentType.isInstance(component)) continue;
            Object dto = cache.module.get(component);
            String family = cache.family(dto);
            boolean combined = "combined_common".equals(family);
            if (!"legacy_common".equals(family) && !combined) continue;
            if (combined && !cache.combinedComponentType.isInstance(component)) continue;
            candidates++;
            List<Object> views = new ArrayList<>();
            views.add(cache.view.get(component));
            Object tabs = cache.views.get(component);
            if (tabs instanceof Map<?, ?>) views.addAll(((Map<?, ?>) tabs).values());
            for (Object view : views) {
                if (!(view instanceof View)) continue;
                ViewGroup root = combined ? cachedCombinedSectionRoot((View) view, list)
                        : cachedSectionRoot((View) view, list);
                if (root != null && java.util.Objects.equals(root.getTag(), cache.moduleKind.invoke(dto))) roots.add(root);
            }
        }
        Set<ViewGroup> notifications = cache.notifications(components, list);
        roots.removeAll(notifications);
        for (ViewGroup root : roots) bind(root, reference, source);
        List<Binding> attached = new ArrayList<>();
        for (WeakReference<Binding> weak : bindings) {
            Binding binding = weak.get();
            if (binding != null && !binding.restored && binding.view().getParent() == list) attached.add(binding);
        }
        int visible = reconcile(attached, list, notifications, enabled(options), SidebarSettings.selected(options));
        event("module_unique", "cards=" + attached.size() + ";visible=" + visible
                + ";notifications=" + notifications.size() + ";source=" + source);
        event("module_scan", "source=" + source + ";components=" + components.size()
                + ";candidates=" + candidates + ";matched=" + roots.size()
                + ";enabled=" + enabled(options) + ";selected=" + SidebarSettings.selected(options).size());
    }

    /** Only the native grid under this owner's direct module child can establish cached identity. */
    @SuppressLint("ResourceType") // ID belongs to the verified host APK, not module resources.
    static ViewGroup cachedSectionRoot(View cell, ViewGroup list) {
        View current = cell;
        for (int depth = 0; current != null && depth < 12 && current != list; depth++) {
            if (current.getId() == 0x7f0a1a98 && current.getParent() instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) current.getParent();
                if (root.getParent() == list) return root;
                if (root.getParent() instanceof Section) {
                    Section section = (Section) root.getParent();
                    if (section.binding.nativeView == root && section.getParent() == list) return section;
                }
                return null;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    /** Combined cards build their grid dynamically; identity comes from the DTO and component. */
    static ViewGroup cachedCombinedSectionRoot(View cell, ViewGroup list) {
        View current = cell;
        for (int depth = 0; current != null && depth < 12 && current != list; depth++) {
            if (current.getParent() == list) return current instanceof ViewGroup ? (ViewGroup) current : null;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return null;
    }

    /** Applies one display decision to the physical main list, after native children are attached. */
    static int reconcile(List<Binding> candidates, ViewGroup list, Set<? extends View> notifications,
                         boolean enabled, List<SidebarSettings.Entry> entries) {
        Binding primary = null;
        View notification = lastNotification(list, notifications);
        int noticeIndex = notification == null ? -1 : list.indexOfChild(notification);
        int preferredIndex = Integer.MAX_VALUE;
        for (Binding binding : candidates) {
            if (binding.restored || binding.view().getParent() != list || notifications.contains(binding.view())) continue;
            int index = list.indexOfChild(binding.view());
            if (enabled && index > noticeIndex && index < preferredIndex) {
                primary = binding;
                preferredIndex = index;
            }
        }
        if (enabled && primary == null) {
            for (Binding binding : candidates) {
                if (binding.restored || binding.view().getParent() != list || notifications.contains(binding.view())) continue;
                int index = list.indexOfChild(binding.view());
                if (index < preferredIndex) { primary = binding; preferredIndex = index; }
            }
        }
        for (Binding binding : candidates) {
            if (binding != primary && !binding.restored && binding.view().getParent() == list) binding.restorePosition();
        }
        notification = lastNotification(list, notifications);
        if (primary != null && notification != null) primary.moveAfter(notification);
        for (Binding binding : candidates) {
            if (binding.restored || binding.view().getParent() != list) continue;
            binding.notificationCard = notifications.contains(binding.view());
            binding.primary = binding == primary;
            boolean active = enabled && !binding.notificationCard;
            if (!active) binding.restorePosition();
            binding.update(active, entries);
        }
        return primary == null ? 0 : 1;
    }

    private static View lastNotification(ViewGroup list, Set<? extends View> notifications) {
        View result = null;
        int last = -1;
        for (View view : notifications) {
            int index = list.indexOfChild(view);
            if (index > last) { result = view; last = index; }
        }
        return result;
    }

    private record Owner(WeakReference<Object> value, OwnerAccess access, CacheAccess cache) { }

    private static final class OwnerAccess {
        final Method build, show, isDark;
        final Field list, registry, viewModel, side;
        OwnerAccess(Class<?> owner, String buildName, String showName, Class<?> showArg,
                    String listName, String registryName, String viewModelName, Class<?> homeRegistry,
                    Class<?> modelType, Class<?> moduleType, String sideName) throws Exception {
            build = method(owner, buildName, ViewGroup.class, moduleType, ViewGroup.class);
            show = method(owner, showName, void.class, showArg);
            list = field(owner, listName, LinearLayout.class);
            registry = field(owner, registryName, homeRegistry);
            viewModel = field(owner, viewModelName, modelType);
            isDark = method(modelType, "cE", boolean.class);
            side = sideName == null ? null : field(owner, sideName, String.class);
        }
        boolean showsHome(Object owner) throws IllegalAccessException { return side == null || "left_sidebar".equals(side.get(owner)); }
        boolean dark(Object owner) throws ReflectiveOperationException {
            Object model = viewModel.get(owner);
            if (model == null) throw new IllegalStateException("Home sidebar theme is unavailable");
            return Boolean.TRUE.equals(isDark.invoke(model));
        }
    }

    private static final class CacheAccess {
        final Class<?> componentType, combinedComponentType, itemType, notificationType;
        final Field readLock, entries, module, view, views, noticeRoot, combinedRegistry;
        final Method moduleId, moduleKind, moduleItems, businessType;
        CacheAccess(ClassLoader loader, Class<?> registryType, Class<?> moduleType, Method moduleId) throws Exception {
            componentType = type(loader, "com.ss.android.ugc.aweme.sidebar.component.BaseSideBarCellComponent");
            combinedComponentType = type(loader, "com.ss.android.ugc.aweme.sidebar.business.more.CombineCellComponent");
            itemType = type(loader, "X.1ZZe");
            notificationType = type(loader, "com.ss.android.ugc.aweme.sidebar.component.otherbusiness.msg.SideBarNotificationCellComponent");
            noticeRoot = field(notificationType, "s", ViewGroup.class);
            combinedRegistry = field(combinedComponentType, "x", type(loader, "X.1ZZS"));
            readLock = field(registryType, "b", java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock.class);
            entries = field(registryType, "e", android.util.ArrayMap.class);
            module = field(componentType, "q", moduleType);
            view = field(componentType, "n", View.class);
            views = field(componentType, "m", Map.class);
            this.moduleId = moduleId;
            moduleKind = method(moduleType, "LJIIIZ", String.class);
            moduleItems = method(moduleType, "LIZJ", List.class);
            businessType = method(itemType, "LIZLLL", String.class);
        }
        String family(Object dto) throws ReflectiveOperationException {
            if (dto == null) return "missing";
            String id = (String) moduleId.invoke(dto);
            String kind = (String) moduleKind.invoke(dto);
            if (!"more_function_view_module".equals(kind)) {
                if (matchesSection(true, id)) return "legacy_common";
                return "grid_list".equals(kind) ? "other_grid" : "other";
            }
            Object items = moduleItems.invoke(dto);
            boolean hasMoreComponent = false;
            if (items instanceof List<?>) {
                for (Object item : (List<?>) items) {
                    if (itemType.isInstance(item) && "more_function_page_component".equals(businessType.invoke(item))) {
                        hasMoreComponent = true;
                        break;
                    }
                }
            }
            if (matchesSection(true, id, kind, hasMoreComponent))
                return hasMoreComponent ? "combined_common" : "legacy_common";
            return "combined_other";
        }
        List<Object> components(Object registry) throws IllegalAccessException {
            Lock lock = (Lock) readLock.get(registry);
            lock.lock();
            try { return new ArrayList<>(((Map<?, ?>) entries.get(registry)).values()); }
            finally { lock.unlock(); }
        }
        Set<ViewGroup> notifications(List<Object> mainComponents, ViewGroup list) throws IllegalAccessException {
            List<Object> all = new ArrayList<>(mainComponents);
            for (Object component : mainComponents) {
                if (!combinedComponentType.isInstance(component)) continue;
                Object nested = combinedRegistry.get(component);
                if (nested != null) all.addAll(components(nested));
            }
            Set<ViewGroup> result = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object component : all) {
                if (!notificationType.isInstance(component)) continue;
                List<Object> viewsToCheck = new ArrayList<>();
                viewsToCheck.add(noticeRoot.get(component));
                viewsToCheck.add(view.get(component));
                Object tabs = views.get(component);
                if (tabs instanceof Map<?, ?>) viewsToCheck.addAll(((Map<?, ?>) tabs).values());
                for (Object candidate : viewsToCheck) {
                    if (!(candidate instanceof View)) continue;
                    ViewGroup root = cachedCombinedSectionRoot((View) candidate, list);
                    if (root != null) result.add(root);
                }
            }
            return result;
        }
    }

    public void setOptions(LocalOptions next) {
        options = next;
        onMain(() -> {
            if (closed) return;
            bindings.removeIf(reference -> {
                Binding binding = reference.get();
                if (binding == null) return true;
                try { apply(binding); }
                catch (RuntimeException | LinkageError unavailable) {
                    binding.restore();
                    event("module_update_preserved", "error_type=" + unavailable.getClass().getSimpleName());
                }
                return false;
            });
            owners.removeIf(owner -> owner.value.get() == null);
            for (Owner owner : owners) {
                try { refresh(owner, "settings"); }
                catch (Exception | LinkageError unavailable) { preserved("settings", unavailable); }
            }
            event("module_options", "owners=" + owners.size() + ";bindings=" + bindings.size()
                    + ";enabled=" + enabled(options) + ";selected=" + SidebarSettings.selected(options).size());
        });
    }

    public boolean isActive() { return !closed && hooks.isActive(); }

    private void openEntry(String id, Activity activity) {
        LocalOptions current = options;
        if (closed || !enabled(current) || SidebarSettings.selected(current).stream().noneMatch(entry -> entry.id.equals(id))) return;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            event("entry_unavailable", "reason=no_current_activity");
            return;
        }
        if(!SidebarSettings.isFeedAction(id)) FeedGestureToolsHook.keepPauseForSidebarSettings(activity);
        try { closeSidebar.run(); }
        catch (RuntimeException unavailable) { event("close_unavailable", "error_type=" + unavailable.getClass().getSimpleName()); }
        try { launcher.accept(activity, id); }
        catch (RuntimeException | LinkageError unavailable) {
            event("entry_unavailable", "error_type=" + unavailable.getClass().getSimpleName());
            Toast.makeText(activity, "模块入口暂不可用，请从模块设置打开", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hooks.close();
        onMain(() -> {
            for (WeakReference<Binding> reference : bindings) {
                Binding binding = reference.get();
                if (binding != null) binding.restore();
            }
            bindings.clear();
            owners.clear();
            pendingRefresh.clear();
        });
    }

    static final class Binding {
        private final Section wrapper;
        private final ViewGroup nativeView;
        private Owner owner;
        final LinearLayout module;
        private final boolean dark;
        private final int originalVisibility;
        private int nativeVisibility;
        private final ViewGroup.LayoutParams originalParams;
        private final Consumer<String> selected;
        private boolean restored;
        private boolean primary = true;
        private boolean notificationCard;
        private WeakReference<ViewGroup> positionParent;
        private WeakReference<View> positionBefore, positionAfter;
        private int positionIndex;
        private Boolean appliedEnabled;
        private List<SidebarSettings.Entry> appliedEntries;

        Binding(ViewGroup nativeView, boolean dark, Consumer<String> selected) {
            this.nativeView = nativeView; this.dark = dark; this.selected = selected;
            originalVisibility = nativeView.getVisibility();
            nativeVisibility = originalVisibility;
            originalParams = nativeView.getLayoutParams();
            Context context = nativeView.getContext();
            wrapper = new Section(context, this);
            wrapper.setTag(nativeView.getTag());
            if (nativeView.getLayoutParams() != null) wrapper.setLayoutParams(nativeView.getLayoutParams());
            module = new LinearLayout(context);
            module.setOrientation(LinearLayout.VERTICAL);
            module.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
            GradientDrawable background = new GradientDrawable();
            background.setColor(dark ? 0x08FFFFFF : 0xFFFFFFFF);
            background.setCornerRadius(dp(context, 12));
            module.setBackground(background);
            wrapper.addView(nativeView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            wrapper.addView(module, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Binding(ViewGroup nativeView, boolean dark, BiConsumer<String, Activity> selected) {
            this(nativeView, dark, id -> selected.accept(id, activity(nativeView.getContext())));
        }

        static Binding attach(ViewGroup nativeView, boolean dark, BiConsumer<String, Activity> selected) {
            if (nativeView.getParent() != null && !(nativeView.getParent() instanceof ViewGroup))
                throw new IllegalStateException("Unsupported sidebar parent");
            ViewGroup parent = (ViewGroup) nativeView.getParent();
            int index = parent == null ? -1 : parent.indexOfChild(nativeView);
            ViewGroup.LayoutParams params = nativeView.getLayoutParams();
            int visibility = nativeView.getVisibility();
            if (parent != null) parent.removeViewAt(index);
            Binding binding = null;
            try {
                binding = new Binding(nativeView, dark, selected);
                if (parent != null) parent.addView(binding.view(), index);
                return binding;
            } catch (RuntimeException | LinkageError failure) {
                if (binding != null) {
                    if (binding.wrapper.getParent() instanceof ViewGroup)
                        ((ViewGroup) binding.wrapper.getParent()).removeView(binding.wrapper);
                    binding.rollback();
                } else if (nativeView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) nativeView.getParent()).removeView(nativeView);
                }
                nativeView.setLayoutParams(params);
                nativeView.setVisibility(visibility);
                if (parent != null && nativeView.getParent() == null)
                    parent.addView(nativeView, Math.min(index, parent.getChildCount()));
                throw failure;
            }
        }

        ViewGroup view() { return wrapper; }

        void update(boolean enabled, List<SidebarSettings.Entry> entries) {
            if (restored) return;
            if (enabled) {
                if (!Boolean.TRUE.equals(appliedEnabled)) nativeVisibility = nativeView.getVisibility();
                hideNative();
            } else if (Boolean.TRUE.equals(appliedEnabled)) {
                if (nativeView.getVisibility() != View.GONE) nativeVisibility = nativeView.getVisibility();
                nativeView.setVisibility(nativeVisibility);
            }
            module.setVisibility(enabled && primary ? View.VISIBLE : View.GONE);
            wrapper.setVisibility(enabled && !primary ? View.GONE : View.VISIBLE);
            if (Boolean.valueOf(enabled).equals(appliedEnabled) && entries.equals(appliedEntries)) return;
            appliedEnabled = enabled;
            appliedEntries = List.copyOf(entries);
            clearListeners(module);
            module.removeAllViews();
            if (!enabled) return;
            Context context = wrapper.getContext();
            TextView title = new TextView(context);
            title.setText("模块功能");
            title.setTextColor(dark ? 0xE6FFFFFF : 0xFF161823);
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setPadding(0, dp(context, 2), 0, dp(context, 8));
            module.addView(title, new LinearLayout.LayoutParams(-1, -2));
            if (entries.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText("尚未选择显示项，请到模块设置勾选");
                empty.setTextColor(dark ? 0xCCFFFFFF : 0xFF55565D);
                empty.setTextSize(14);
                empty.setMinHeight(dp(context, 48));
                empty.setGravity(Gravity.CENTER_VERTICAL);
                module.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                return;
            }
            for (SidebarSettings.Entry entry : entries) {
                TextView row = new Shortcut(context);
                row.setTag("douai.sidebar." + entry.id);
                row.setText(entry.title);
                row.setContentDescription(entry.title);
                row.setTextColor(dark ? 0xE6FFFFFF : 0xFF161823);
                row.setTextSize(15);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(context, 48));
                row.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10));
                row.setMaxLines(3);
                GradientDrawable mask = new GradientDrawable();
                mask.setColor(0xFFFFFFFF);
                mask.setCornerRadius(dp(context, 6));
                row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x16161823), null, mask));
                row.setFocusable(true);
                row.setOnClickListener(view -> { if (!restored && module.getVisibility() == View.VISIBLE) selected.accept(entry.id); });
                module.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        void restore() {
            if (restored) return;
            restorePosition();
            if (Boolean.TRUE.equals(appliedEnabled) && nativeView.getVisibility() != View.GONE)
                nativeVisibility = nativeView.getVisibility();
            restored = true;
            clearListeners(module);
            module.removeAllViews();
            module.setVisibility(View.GONE);
            wrapper.setVisibility(View.VISIBLE);
            if (Boolean.TRUE.equals(appliedEnabled)) nativeView.setVisibility(nativeVisibility);
        }
        void rollback() {
            restore();
            wrapper.removeView(nativeView);
            nativeView.setLayoutParams(originalParams);
            nativeView.setVisibility(originalVisibility);
        }

        private void hideNative() {
            int visibility = nativeView.getVisibility();
            if (visibility != View.GONE) {
                nativeVisibility = visibility;
                nativeView.setVisibility(View.GONE);
            }
        }
        void rollbackAttached() {
            restorePosition();
            ViewGroup parent = wrapper.getParent() instanceof ViewGroup ? (ViewGroup) wrapper.getParent() : null;
            int index = parent == null ? -1 : parent.indexOfChild(wrapper);
            if (parent != null) parent.removeView(wrapper);
            rollback();
            if (parent != null) parent.addView(nativeView, Math.min(index, parent.getChildCount()));
        }

        private void moveAfter(View anchor) {
            if (!(wrapper.getParent() instanceof ViewGroup) || anchor == wrapper) return;
            ViewGroup parent = (ViewGroup) wrapper.getParent();
            if (anchor.getParent() != parent) return;
            int index = parent.indexOfChild(wrapper), anchorIndex = parent.indexOfChild(anchor);
            int target = anchorIndex + (index < anchorIndex ? 0 : 1);
            if (index == target) return;
            if (positionParent == null || positionParent.get() != parent) {
                positionParent = new WeakReference<>(parent);
                positionIndex = index;
                positionBefore = new WeakReference<>(index > 0 ? parent.getChildAt(index - 1) : null);
                positionAfter = new WeakReference<>(index + 1 < parent.getChildCount() ? parent.getChildAt(index + 1) : null);
            }
            moveTo(parent, target);
        }

        private void restorePosition() {
            if (positionParent == null) return;
            ViewGroup parent = positionParent.get();
            if (parent != null && wrapper.getParent() == parent) {
                int index = parent.indexOfChild(wrapper);
                View after = positionAfter.get(), before = positionBefore.get();
                int target;
                if (after != null && after.getParent() == parent) {
                    int anchor = parent.indexOfChild(after);
                    target = anchor - (index < anchor ? 1 : 0);
                } else if (before != null && before.getParent() == parent) {
                    int anchor = parent.indexOfChild(before);
                    target = anchor + (index < anchor ? 0 : 1);
                } else target = Math.min(positionIndex, parent.getChildCount() - 1);
                if (index != target) moveTo(parent, target);
            }
            positionParent = null;
            positionBefore = null;
            positionAfter = null;
        }

        private void moveTo(ViewGroup parent, int target) {
            int index = parent.indexOfChild(wrapper);
            parent.removeView(wrapper);
            try { parent.addView(wrapper, Math.min(target, parent.getChildCount())); }
            catch (RuntimeException | LinkageError failure) {
                if (wrapper.getParent() == null) parent.addView(wrapper, Math.min(index, parent.getChildCount()));
                throw failure;
            }
        }
        private static void clearListeners(ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                child.setOnClickListener(null);
                child.setClickable(false);
            }
        }
    }

    /** Keeps the binding alive with its view without consuming the host's default tag. */
    private static final class Section extends FrameLayout {
        final Binding binding;
        Section(Context context, Binding binding) { super(context); this.binding = binding; }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Combined first-page rendering can asynchronously reveal this same native root.
            boolean active = !binding.restored && Boolean.TRUE.equals(binding.appliedEnabled);
            if (active) binding.hideNative();
            // Let the outer sidebar scroll all selected rows despite the native card's fixed height.
            super.onMeasure(widthMeasureSpec, active
                    ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) : heightMeasureSpec);
            // The host may assign a fixed height after build, even while the native card is GONE.
            if (binding.module.getVisibility() != View.VISIBLE && binding.nativeView.getVisibility() == View.GONE)
                setMeasuredDimension(getMeasuredWidth(), 0);
        }
    }

    private static final class Shortcut extends TextView {
        Shortcut(Context context) { super(context); }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
    }
    private static boolean enabled(LocalOptions options) { return options != null && options.enabled(SidebarSettings.ENABLED); }
    private void onMain(Runnable work) { if (Looper.myLooper() == Looper.getMainLooper()) work.run(); else main.post(work); }
    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }
    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException("Home sidebar contract changed");
        method.setAccessible(true);
        return method;
    }
    private static Field field(Class<?> owner, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected) throw new NoSuchFieldException("Home sidebar field changed");
        field.setAccessible(true);
        return field;
    }
    private static void preserved(String source, Throwable failure) {
        event("module_preserved", "source=" + source + ";error_type=" + failure.getClass().getSimpleName());
    }
    private static void event(String reason, String evidence) {
        try { DouAiLog.event("home_sidebar", "module_section", reason, evidence); }
        catch (RuntimeException ignored) { }
    }
}
