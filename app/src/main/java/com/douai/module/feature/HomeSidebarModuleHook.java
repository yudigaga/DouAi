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
import com.douai.module.config.LiquidGlassSettings;
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
                        reference.invalidateScan();
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
        boolean active = enabled(options) && SidebarSettings.itemsEnabled(options) && home && !binding.notificationCard;
        if (!active) binding.restorePosition();
        binding.update(active, SidebarSettings.selected(options), options);
    }

    private void refresh(Owner reference, String source) throws ReflectiveOperationException {
        Object owner = reference.value.get();
        if (owner == null) return;
        ViewGroup list = (ViewGroup) reference.access.list.get(owner);
        Object registry = reference.access.registry.get(owner);
        ScanSnapshot previous = reference.scan;
        boolean wasCurrent = list != null && registry != null && previous != null
                && previous.matches(list, registry, reference.generation);
        for (WeakReference<Binding> weak : bindings) {
            Binding binding = weak.get();
            if (binding != null && binding.owner == reference) apply(binding);
        }
        if (!reference.access.showsHome(owner)) {
            reference.scan=wasCurrent?previous.withChildren(list,reference.generation):null;
            event("module_scope", "source=" + source + ";home=false;native_restored=true");
            return;
        }
        if (list == null || registry == null) {
            reference.scan = null;
            event("module_pending", "source=" + source + ";reason=owner_not_ready");
            return;
        }
        if (!"settings".equals(source) && wasCurrent && reference.cache.matchesComponents(registry, previous)
                && refreshCached(reference, list, previous, source)) return;
        reference.scan = null;
        refreshFull(reference, list, registry, source);
    }

    private boolean refreshCached(Owner reference,ViewGroup list,ScanSnapshot snapshot,String source)
            throws ReflectiveOperationException {
        List<Object> candidates=snapshot.candidates();
        List<Object> notificationInputs=snapshot.notificationInputs();
        if(candidates==null || candidates.isEmpty() || notificationInputs==null)return false;
        List<Binding> attached=attached(list);
        if(attached.isEmpty())return false;
        Set<ViewGroup> notifications=reference.cache.notificationsFromInputs(notificationInputs,list);
        Set<ViewGroup> roots=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Object component:candidates)
            if (!addCandidateRoots(reference.cache, component, list, roots)) return false;
        roots.removeAll(notifications);
        if(roots.isEmpty())return false;
        for(ViewGroup root:roots) {
            boolean bound=false;
            for(Binding binding:attached)if(binding.view()==root && binding.owner==reference){bound=true;break;}
            if(!bound)return false;
        }
        int visible=reconcile(attached,list,notifications,enabled(options)&&SidebarSettings.itemsEnabled(options),
                SidebarSettings.selected(options),options);
        reference.scan=snapshot.withChildren(list,reference.generation);
        event("module_unique","cards="+attached.size()+";visible="+visible+";notifications="+notifications.size()+";source="+source);
        event("module_scan","source="+source+";path=cache;components="+snapshot.components.size()+";candidates="+candidates.size()
                +";matched="+roots.size()+";enabled="+enabled(options)+";selected="+SidebarSettings.selected(options).size());
        return true;
    }

    private void refreshFull(Owner reference,ViewGroup list,Object registry,String source) throws ReflectiveOperationException {
        Set<ViewGroup> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        CacheAccess cache = reference.cache;
        List<Object> components = cache.components(registry);
        List<Object> candidates=new ArrayList<>(),notificationInputs=cache.notificationInputs(components);
        for (Object component : components)
            if (addCandidateRoots(cache, component, list, roots)) candidates.add(component);
        Set<ViewGroup> notifications=cache.notificationsFromInputs(notificationInputs,list);
        int candidateCount=candidates.size();
        roots.removeAll(notifications);
        for (ViewGroup root : roots) bind(root, reference, source);
        List<Binding> attached=attached(list);
        int visible = reconcile(attached,list,notifications,enabled(options)&&SidebarSettings.itemsEnabled(options),
                SidebarSettings.selected(options),options);
        reference.scan=ScanSnapshot.capture(list,registry,reference.generation,components)
                .withScanData(candidates,notificationInputs);
        event("module_unique", "cards=" + attached.size() + ";visible=" + visible
                + ";notifications=" + notifications.size() + ";source=" + source);
        event("module_scan", "source=" + source + ";components=" + components.size()
                + ";candidates=" + candidateCount + ";matched=" + roots.size()
                + ";enabled=" + enabled(options) + ";selected=" + SidebarSettings.selected(options).size());
    }

    private static boolean addCandidateRoots(CacheAccess cache,Object component,ViewGroup list,Set<ViewGroup> roots)
            throws ReflectiveOperationException {
        if (!cache.componentType.isInstance(component)) return false;
        Object dto=cache.module.get(component);
        String family = cache.family(dto);
        boolean combined = "combined_common".equals(family);
        if (!"legacy_common".equals(family) && !combined) return false;
        if (combined && !cache.combinedComponentType.isInstance(component)) return false;
        List<Object> views=new ArrayList<>();views.add(cache.view.get(component));
        Object tabs=cache.views.get(component);if(tabs instanceof Map<?,?>)views.addAll(((Map<?,?>)tabs).values());
        for(Object view:views) {
            if(!(view instanceof View))continue;
            ViewGroup root=combined?cachedCombinedSectionRoot((View)view,list):cachedSectionRoot((View)view,list);
            if(root!=null && java.util.Objects.equals(root.getTag(),cache.moduleKind.invoke(dto)))roots.add(root);
        }
        return true;
    }

    private List<Binding> attached(ViewGroup list) {
        List<Binding> attached=new ArrayList<>();
        for (WeakReference<Binding> weak : bindings) {
            Binding binding = weak.get();
            if (binding != null && !binding.restored && binding.view().getParent() == list) attached.add(binding);
        }
        return attached;
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
        return reconcile(candidates,list,notifications,enabled,entries,null);
    }
    private static int reconcile(List<Binding> candidates, ViewGroup list, Set<? extends View> notifications,
                         boolean enabled, List<SidebarSettings.Entry> entries,LocalOptions options) {
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
            binding.update(active, entries,options);
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

    private static final class Owner {
        final WeakReference<Object> value;final OwnerAccess access;final CacheAccess cache;
        long generation;ScanSnapshot scan;
        Owner(WeakReference<Object> value,OwnerAccess access,CacheAccess cache){this.value=value;this.access=access;this.cache=cache;}
        void invalidateScan(){generation++;scan=null;}
    }

    static final class ScanSnapshot {
        private final WeakReference<ViewGroup> list;
        private final WeakReference<Object> registry;
        private final long generation;
        private final List<WeakReference<View>> children;
        private final List<WeakReference<Object>> components;
        private final List<WeakReference<Object>> candidates,notificationInputs;
        private ScanSnapshot(ViewGroup list,Object registry,long generation,List<WeakReference<View>> children,
                             List<WeakReference<Object>> components,List<WeakReference<Object>> candidates,
                             List<WeakReference<Object>> notificationInputs) {
            this.list=new WeakReference<>(list);this.registry=new WeakReference<>(registry);this.generation=generation;
            this.children=children;this.components=components;this.candidates=candidates;this.notificationInputs=notificationInputs;
        }
        static ScanSnapshot capture(ViewGroup list,Object registry,long generation,List<?> components) {
            List<WeakReference<View>> children=new ArrayList<>(list.getChildCount());
            for(int i=0;i<list.getChildCount();i++)children.add(new WeakReference<>(list.getChildAt(i)));
            return new ScanSnapshot(list,registry,generation,children,weak(components),null,null);
        }
        boolean matches(ViewGroup list,Object registry,long generation) {
            return this.list.get() == list && this.registry.get() == registry && this.generation == generation
                    && matchesChildren(list);
        }
        private boolean matchesChildren(ViewGroup list) {
            if (list.getChildCount() != children.size()) return false;
            for (int i = 0; i < children.size(); i++)
                if (children.get(i).get() != list.getChildAt(i)) return false;
            return true;
        }
        boolean matchesComponents(android.util.ArrayMap<?, ?> entries) {
            if (entries.size() != components.size()) return false;
            for (int i = 0; i < entries.size(); i++) {
                Object expected = components.get(i).get();
                if (expected == null || expected != entries.valueAt(i)) return false;
            }
            return true;
        }
        List<Object> candidates(){return strong(candidates);}
        List<Object> notificationInputs(){return strong(notificationInputs);}
        ScanSnapshot withScanData(List<?> candidates,List<?> notificationInputs) {
            return new ScanSnapshot(list.get(),registry.get(),generation,children,components,weak(candidates),weak(notificationInputs));
        }
        ScanSnapshot withChildren(ViewGroup list,long generation) {
            if (this.list.get() != list || this.generation != generation) return null;
            if (matchesChildren(list)) return this;
            List<WeakReference<View>> current=new ArrayList<>(list.getChildCount());
            for(int i=0;i<list.getChildCount();i++)current.add(new WeakReference<>(list.getChildAt(i)));
            return new ScanSnapshot(list,registry.get(),generation,current,components,candidates,notificationInputs);
        }
        private static List<WeakReference<Object>> weak(List<?> values) {
            List<WeakReference<Object>> result=new ArrayList<>(values.size());
            for(Object value:values)result.add(new WeakReference<>(value));
            return result;
        }
        private static List<Object> strong(List<WeakReference<Object>> references) {
            if(references==null)return null;
            List<Object> result=new ArrayList<>(references.size());
            for(WeakReference<Object> reference:references){Object value=reference.get();if(value==null)return null;result.add(value);}
            return result;
        }
    }

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
        boolean matchesComponents(Object registry, ScanSnapshot snapshot) throws IllegalAccessException {
            Lock lock = (Lock) readLock.get(registry);
            lock.lock();
            try { return snapshot.matchesComponents((android.util.ArrayMap<?, ?>) entries.get(registry)); }
            finally { lock.unlock(); }
        }
        List<Object> notificationInputs(List<Object> mainComponents) {
            List<Object> result=new ArrayList<>();
            for(Object component:mainComponents)
                if(notificationType.isInstance(component) || combinedComponentType.isInstance(component))result.add(component);
            return result;
        }
        Set<ViewGroup> notificationsFromInputs(List<Object> notificationInputs,ViewGroup list) throws IllegalAccessException {
            List<Object> all=new ArrayList<>(notificationInputs);
            for(Object component:notificationInputs)if(combinedComponentType.isInstance(component)) {
                Object nested=combinedRegistry.get(component);
                if(nested!=null)all.addAll(components(nested));
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
        if (closed || !enabled(current) || !SidebarSettings.itemsEnabled(current)
                || SidebarSettings.selected(current).stream().noneMatch(entry -> entry.id.equals(id))) return;
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
        private boolean keepCommon,gravity,motionActive,nativeHidden;
        private long motionSignature;
        private String commonSelection="*";
        private final SidebarNativeCommon common=new SidebarNativeCommon();
        private final LiquidGlassMotion motion;

        Binding(ViewGroup nativeView, boolean dark, Consumer<String> selected) {
            this.nativeView = nativeView; this.dark = dark; this.selected = selected;
            originalVisibility = nativeView.getVisibility();
            nativeVisibility = originalVisibility;
            originalParams = nativeView.getLayoutParams();
            Context context = nativeView.getContext();
            motion=new LiquidGlassMotion(context);
            wrapper = new Section(context, this);
            wrapper.setTag(nativeView.getTag());
            if (nativeView.getLayoutParams() != null) wrapper.setLayoutParams(nativeView.getLayoutParams());
            module = new LinearLayout(context);
            module.setOrientation(LinearLayout.VERTICAL);
            module.setClipToPadding(false);
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
            update(enabled,entries,null);
        }
        void update(boolean enabled,List<SidebarSettings.Entry> entries,LocalOptions options) {
            if (restored) return;
            keepCommon=enabled && SidebarSettings.keepCommon(options);
            commonSelection=options==null?"*":options.text(SidebarSettings.COMMON_ITEMS,"*");
            LiquidGlassSettings theme=LiquidGlassSettings.read(options);
            gravity=enabled && theme.enabled && theme.gravity;
            int space=gravity?dp(wrapper.getContext(),10):0;
            if(wrapper.getPaddingLeft()!=space || wrapper.getPaddingTop()!=space)
                wrapper.setPadding(space,space,space,space);
            if (enabled) {
                if(keepCommon)restoreNative();else hideNative();
            } else if (Boolean.TRUE.equals(appliedEnabled)) {
                restoreNative();
            }
            module.setVisibility(enabled && primary ? View.VISIBLE : View.GONE);
            wrapper.setVisibility(enabled && !primary && !keepCommon ? View.GONE : View.VISIBLE);
            common.update(nativeView,keepCommon,commonSelection);
            if (Boolean.valueOf(enabled).equals(appliedEnabled) && entries.equals(appliedEntries)){refreshMotion(true);return;}
            appliedEnabled = enabled;
            appliedEntries = List.copyOf(entries);
            clearListeners(module);
            module.removeAllViews();
            if (!enabled){refreshMotion(true);return;}
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
                refreshMotion(true);
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
            refreshMotion(true);
        }

        void restore() {
            if (restored) return;
            motion.close();common.restore();wrapper.setPadding(0,0,0,0);
            restorePosition();
            restored = true;
            clearListeners(module);
            module.removeAllViews();
            module.setVisibility(View.GONE);
            wrapper.setVisibility(View.VISIBLE);
            if (Boolean.TRUE.equals(appliedEnabled)) restoreNative();
        }
        void rollback() {
            restore();
            wrapper.removeView(nativeView);
            nativeView.setLayoutParams(originalParams);
            nativeView.setVisibility(originalVisibility);
        }

        private void hideNative() {
            int visibility = nativeView.getVisibility();
            if(!nativeHidden || visibility!=View.GONE)nativeVisibility=visibility;
            nativeHidden=true;
            if (visibility != View.GONE) {
                nativeView.setVisibility(View.GONE);
            }
        }
        private void restoreNative() {
            if(nativeHidden && nativeView.getVisibility()==View.GONE)nativeView.setVisibility(nativeVisibility);
            else nativeVisibility=nativeView.getVisibility();
            nativeHidden=false;
        }
        void refreshMotion(boolean targetsChanged) {
            if(restored)return;
            android.graphics.Rect visible=new android.graphics.Rect();
            boolean active=gravity && wrapper.isAttachedToWindow() && wrapper.hasWindowFocus()
                    && visiblyShown(wrapper) && wrapper.getGlobalVisibleRect(visible) && !visible.isEmpty();
            if(active) {
                List<View> targets=new ArrayList<>();
                if(keepCommon && nativeView.getVisibility()==View.VISIBLE)targets.add(nativeView);
                if(module.getVisibility()==View.VISIBLE)for(int i=0;i<module.getChildCount();i++)targets.add(module.getChildAt(i));
                View root=wrapper.getRootView();long signature=31L*root.getWidth()+root.getHeight();
                for(java.util.Iterator<View> iterator=targets.iterator();iterator.hasNext();) {
                    View target=iterator.next();android.graphics.RectF box=motion.restingBounds(target,root);
                    if(!visiblyShown(target) || !target.getGlobalVisibleRect(visible) || visible.isEmpty() || box==null){iterator.remove();continue;}
                    signature=31*signature+System.identityHashCode(target);signature=31*signature+Math.round(box.left);
                    signature=31*signature+Math.round(box.top);signature=31*signature+Math.round(box.right);signature=31*signature+Math.round(box.bottom);
                }
                if(!motionActive || targetsChanged || signature!=motionSignature){motion.setTargets(targets,root,null);motionSignature=signature;}
            } else if(motionActive){motion.setTargets(Collections.emptyList(),wrapper.getRootView(),null);motionSignature=0;}
            motion.setActive(active);motionActive=active;
        }
        private static boolean visiblyShown(View view) {
            for(int depth=0;view!=null && depth<64;depth++) {
                if(view.getVisibility()!=View.VISIBLE || !Float.isFinite(view.getAlpha()) || view.getAlpha()<=0)return false;
                view=view.getParent() instanceof View?(View)view.getParent():null;
            }
            return true;
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
    private static final class Section extends LinearLayout implements android.view.ViewTreeObserver.OnPreDrawListener {
        final Binding binding;
        Section(Context context, Binding binding) {
            super(context);this.binding=binding;setOrientation(VERTICAL);setClipToPadding(false);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnPreDrawListener(this);binding.refreshMotion(true);}
        @Override protected void onDetachedFromWindow(){getViewTreeObserver().removeOnPreDrawListener(this);binding.motion.setActive(false);binding.motionActive=false;super.onDetachedFromWindow();}
        @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);binding.refreshMotion(false);}
        @Override public boolean onPreDraw(){binding.refreshMotion(false);return true;}
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            int action=event.getActionMasked();
            if(!binding.restored && action==android.view.MotionEvent.ACTION_DOWN)binding.motion.setInteracting(true);
            boolean handled=super.dispatchTouchEvent(event);
            if(!binding.restored && (action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL))binding.motion.setInteracting(false);
            return handled;
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Combined first-page rendering can asynchronously reveal this same native root.
            boolean active = !binding.restored && Boolean.TRUE.equals(binding.appliedEnabled);
            if (active && !binding.keepCommon) binding.hideNative();
            if(active && binding.keepCommon)binding.common.update(binding.nativeView,true,binding.commonSelection);
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
