package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.compose.ui.platform.AbstractComposeView;
import com.douai.module.feature.AvatarImageLoader;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.feature.NativeUserProfiles;
import com.douai.module.feature.NativeUserSearch;
import com.douai.module.feature.FollowListSnapshot;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.BuildConfig;
import com.douai.module.R;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Searchable, recycled identity rows shared by every member and conversation picker. */
public final class MemberListView extends LinearLayout implements AutoCloseable {
    interface AvatarLoader { Bitmap load(String url, AvatarImageLoader.Request request) throws Exception; }
    interface ProfileLoader {
        AutoCloseable load(List<ProfileListEntry> entries, boolean force, Consumer<NativeUserProfiles.Update> updated);
    }
    interface DirectoryBackend {
        default FollowListSnapshot.State account() { return null; }
        default AutoCloseable refreshRelations(Consumer<Set<FollowListSnapshot.Kind>> completed) {
            completed.accept(java.util.EnumSet.allOf(FollowListSnapshot.Kind.class));
            return () -> { };
        }
        AutoCloseable search(String query, long cursor, String searchId, Consumer<NativeUserSearch.Update> updated);
        AutoCloseable loadRelation(FollowListSnapshot.Kind kind, Consumer<Boolean> loaded);
        List<ProfileListEntry> members(FollowListSnapshot.Kind kind);
    }
    private static final DirectoryBackend NATIVE_DIRECTORY = new DirectoryBackend() {
        @Override public FollowListSnapshot.State account() {
            NativeFollowLists.currentAccountUid();
            return FollowListSnapshot.current();
        }
        @Override public AutoCloseable search(String query, long cursor, String searchId, Consumer<NativeUserSearch.Update> updated) {
            return NativeUserSearch.search(query, cursor, searchId, updated);
        }
        @Override public AutoCloseable loadRelation(FollowListSnapshot.Kind kind, Consumer<Boolean> loaded) {
            return NativeFollowLists.load(kind, loaded);
        }
        @Override public AutoCloseable refreshRelations(Consumer<Set<FollowListSnapshot.Kind>> completed) {
            java.util.concurrent.atomic.AtomicReference<Consumer<Set<FollowListSnapshot.Kind>>> listener =
                    new java.util.concurrent.atomic.AtomicReference<>(completed);
            boolean started;
            try {
                started = NativeFollowLists.refreshRelationships(refreshed -> {
                    Consumer<Set<FollowListSnapshot.Kind>> callback = listener.getAndSet(null);
                    if (callback != null) callback.accept(refreshed);
                });
            } catch (RuntimeException failure) { listener.set(null); throw failure; }
            if (!started) {
                Consumer<Set<FollowListSnapshot.Kind>> callback = listener.getAndSet(null);
                if (callback != null) callback.accept(Collections.emptySet());
            }
            return () -> listener.set(null);
        }
        @Override public List<ProfileListEntry> members(FollowListSnapshot.Kind kind) {
            return new ArrayList<>(NativeFollowLists.memberDetails(kind).values());
        }
    };
    private final List<ProfileListEntry> all;
    private final List<ProfileListEntry> visible = new ArrayList<>();
    private final Set<String> selected;
    private final boolean selectable;
    private final boolean miuix;
    private final ExecutorService images;
    private final AvatarLoader avatarLoader;
    private final ProfileLoader profileLoader;
    private final DirectoryBackend directoryBackend;
    private final EditText search;
    private final Spinner scope;
    private final Button searchAll;
    private final Button loadMore;
    private final TextView directoryStatus;
    private final String directoryIntro;
    private final Set<String> remoteUids = new HashSet<>();
    private final Map<FollowListSnapshot.Kind, RelationState> relations = new EnumMap<>(FollowListSnapshot.Kind.class);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Map<String, AvatarTask> pending = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Rows rows = new Rows();
    private final TextView count;
    private final TextView profileStatus;
    private final Button refreshProfiles;
    private final android.graphics.drawable.Drawable.ConstantState placeholder;
    private AutoCloseable profileSubscription;
    private boolean forceProfileRefreshPending;
    private AutoCloseable relationshipRefresh;
    private AutoCloseable directorySubscription;
    private int profileGeneration;
    private int directoryGeneration;
    private int relationGeneration;
    private int directoryScope;
    private boolean directoryLoading;
    private boolean hasMore;
    private long cursor;
    private String searchId = "";
    private String rawQuery = "";
    private boolean accountInvalid;
    private String directoryAccountUid;
    private long directoryAccountEpoch;
    private String query = "";
    private String searchStatus = "";
    private volatile boolean closed;

    private static final class RelationState {
        final Set<String> uids = new HashSet<>();
        AutoCloseable subscription;
        boolean complete, loading, failed;
    }

    public MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                          boolean selectable) {
        this(baseContext, miuix, entries, selected, selectable, false);
    }

    public MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                          boolean selectable, boolean directoryEnabled) {
        this(baseContext, miuix, entries, selected, selectable, directoryEnabled, Executors.newFixedThreadPool(2),
                (url, request) -> request.load(url), NativeUserProfiles::load, NATIVE_DIRECTORY);
    }

    MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                   boolean selectable, ExecutorService images, AvatarLoader avatarLoader) {
        this(baseContext, miuix, entries, selected, selectable, images, avatarLoader, NativeUserProfiles::load);
    }

    MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                   boolean selectable, ExecutorService images, AvatarLoader avatarLoader, ProfileLoader profileLoader) {
        this(baseContext, miuix, entries, selected, selectable, false, images, avatarLoader, profileLoader, NATIVE_DIRECTORY);
    }

    MemberListView(Context baseContext, boolean miuix, List<ProfileListEntry> entries, Set<String> selected,
                   boolean selectable, boolean directoryEnabled, ExecutorService images, AvatarLoader avatarLoader,
                   ProfileLoader profileLoader, DirectoryBackend directoryBackend) {
        super(ModuleTheme.themedContext(baseContext));
        this.images = images;
        this.avatarLoader = avatarLoader;
        this.profileLoader = profileLoader;
        this.miuix = miuix;
        this.directoryBackend = directoryEnabled ? directoryBackend : null;
        for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) relations.put(kind, new RelationState());
        Context context = getContext();
        android.graphics.drawable.Drawable icon;
        String intro = "输入昵称或抖音号，点击搜索抖音用户";
        try {
            android.content.res.Resources resources = context.getResources();
            if (!BuildConfig.APPLICATION_ID.equals(resources.getResourcePackageName(R.drawable.ic_member_placeholder))) {
                resources = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).getResources();
            }
            icon = resources.getDrawable(R.drawable.ic_member_placeholder, null);
            intro = resources.getString(R.string.member_directory_intro);
        } catch (Exception unavailable) { icon = context.getDrawable(android.R.drawable.ic_menu_myplaces); }
        directoryIntro = intro;
        placeholder = icon.getConstantState();
        all = new ArrayList<>(entries);
        this.selected = new LinkedHashSet<>(selected);
        this.selectable = selectable;
        setOrientation(VERTICAL);
        // Fill the bounded Compose host to reserve its footer and avoid probing unattached Compose rows.
        if (directoryEnabled) setLayoutParams(new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (directoryEnabled) {
            scope = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
            scope.setTag("settings.followLists.scope");
            scope.setContentDescription("用户范围");
            ArrayAdapter<String> scopes = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                    new String[]{"全部", "未关注", "我关注", "关注我"});
            scopes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            scope.setAdapter(scopes);
            addView(MiuixFormControls.viewFor(scope), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else scope = null;
        String searchHint = !all.isEmpty() && all.stream().allMatch(entry -> entry.conversation)
                ? "搜索群名称或会话 ID" : "搜索昵称、抖音号或 UID";
        search = miuix ? MiuixFormControls.input(context, searchHint, "", false) : new EditText(context);
        search.setHint(searchHint);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTag("settings.followLists.search");
        addView(MiuixFormControls.viewFor(search), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        if (directoryEnabled) {
            searchAll = miuix ? MiuixFormControls.button(context) : new Button(context);
            searchAll.setText("搜索抖音用户");
            searchAll.setTextSize(14);
            searchAll.setTag("settings.followLists.searchAll");
            searchAll.setOnClickListener(ignored -> searchDirectory(false));
            addView(MiuixFormControls.viewFor(searchAll), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            directoryStatus = new TextView(context);
            directoryStatus.setTextColor(Color.rgb(105, 115, 122));
            directoryStatus.setTextSize(12);
            directoryStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
            directoryStatus.setTag("settings.followLists.directoryStatus");
            directoryStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            directoryStatus.setText(directoryIntro);
            addView(directoryStatus, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        } else { searchAll = null; directoryStatus = null; }
        boolean hasUsers = all.stream().anyMatch(MemberListView::userProfile);
        refreshProfiles = miuix ? MiuixFormControls.button(context) : new Button(context);
        refreshProfiles.setText("刷新");
        refreshProfiles.setTextSize(14);
        refreshProfiles.setTag("settings.followLists.refreshProfiles");
        refreshProfiles.setVisibility(directoryEnabled || hasUsers ? View.VISIBLE : View.GONE);
        refreshProfiles.setOnClickListener(ignored -> refreshUsers());
        addView(MiuixFormControls.viewFor(refreshProfiles), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        profileStatus = new TextView(context);
        profileStatus.setTextColor(Color.rgb(105, 115, 122));
        profileStatus.setTextSize(12);
        profileStatus.setPadding(dp(8), dp(4), dp(8), dp(4));
        profileStatus.setTag("settings.followLists.profileStatus");
        profileStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        profileStatus.setVisibility(hasUsers ? View.VISIBLE : View.GONE);
        addView(profileStatus, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        count = new TextView(context);
        count.setTextColor(Color.rgb(105, 115, 122));
        count.setTextSize(12);
        count.setPadding(dp(8), dp(8), dp(8), dp(8));
        count.setTag("settings.followLists.resultCount");
        addView(count);
        ListView list = new ListView(context);
        list.setTag("settings.followLists.members");
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(1));
        list.setAdapter(rows);
        if (directoryEnabled) addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        else {
            // Keep the existing fixed viewport for group conversations and legacy local lists.
            int height = getResources().getConfiguration().screenHeightDp;
            addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, dp(Math.max(104, Math.min(300,
                    height - (hasUsers ? 320 : 230))))));
        }
        if (directoryEnabled) {
            loadMore = miuix ? MiuixFormControls.button(context) : new Button(context);
            loadMore.setText("加载更多用户");
            loadMore.setTextSize(14);
            loadMore.setTag("settings.followLists.loadMore");
            loadMore.setOnClickListener(ignored -> searchDirectory(true));
            addView(MiuixFormControls.viewFor(loadMore), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            scope.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { changeScope(position); }
                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
        } else loadMore = null;
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int n, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int n) {
                String value = s.toString().trim();
                if (!rawQuery.equals(value)) {
                    rawQuery = value;
                    cancelDirectory();
                    remoteUids.clear();
                    searchStatus = value.isEmpty() ? "" : "点击搜索抖音用户，结果将合并到当前列表";
                    updateDirectoryStatus();
                }
                filter(value);
                updateDirectoryControls();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        filter("");
        ModuleTheme.bind(this);
        updateDirectoryControls();
        checkDirectoryAccount();
        if (hasUsers) loadProfiles(false);
        loadRelations();
    }

    public Set<String> selected() { return Collections.unmodifiableSet(new LinkedHashSet<>(selected)); }

    /** Reconcile the same UID pool after a refresh without resetting its query or selection. */
    public void refreshUsers() {
        if (closed || accountInvalid || !checkDirectoryAccount()) return;
        loadProfiles(true);
        loadRelations(true);
    }

    private boolean checkDirectoryAccount() {
        if (directoryBackend == null) return true;
        if (accountInvalid) return false;
        FollowListSnapshot.State state = directoryBackend.account();
        if (state == null) return true;
        boolean valid = state.active && !state.accountUid.isEmpty();
        if (directoryAccountUid == null) {
            if (valid) {
                directoryAccountUid = state.accountUid;
                directoryAccountEpoch = state.accountEpoch;
            }
            return true;
        }
        if (valid && directoryAccountUid.equals(state.accountUid) && directoryAccountEpoch == state.accountEpoch) return true;
        invalidateAccount("");
        return false;
    }

    private void updateDirectoryControls() {
        if (searchAll == null) return;
        boolean enabled = !closed && !accountInvalid;
        search.setEnabled(enabled);
        scope.setEnabled(enabled);
        searchAll.setEnabled(enabled && !rawQuery.isEmpty() && !directoryLoading);
        loadMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        loadMore.setEnabled(enabled && !directoryLoading && hasMore);
    }

    private void cancelDirectory() {
        directoryGeneration++;
        closeSubscription(directorySubscription);
        directorySubscription = null;
        directoryLoading = false;
        hasMore = false;
        cursor = 0;
        searchId = "";
    }

    private void detachRelation() {
        relationGeneration++;
        closeSubscription(relationshipRefresh);
        relationshipRefresh = null;
        for (RelationState state : relations.values()) {
            AutoCloseable subscription = state.subscription;
            state.subscription = null;
            state.loading = false;
            closeSubscription(subscription);
        }
    }

    private void changeScope(int position) {
        if (position < 0 || position > 3 || closed || accountInvalid || !checkDirectoryAccount() || directoryScope == position) return;
        directoryScope = position;
        filter(query);
        updateDirectoryStatus();
        updateDirectoryControls();
    }

    private void loadRelations() {
        loadRelations(false);
    }

    private void loadRelations(boolean force) {
        if (directoryBackend == null || closed || accountInvalid || !checkDirectoryAccount()) return;
        detachRelation();
        int generation = relationGeneration;
        for (RelationState state : relations.values()) {
            state.loading = true; state.failed = false; state.complete = false;
        }
        filter(query);
        updateDirectoryStatus();
        if (force) {
            try {
                AutoCloseable subscription = directoryBackend.refreshRelations(completed -> {
                    if (closed || accountInvalid || generation != relationGeneration || !checkDirectoryAccount()) return;
                    loadRelationEntries(generation, completed == null ? Collections.emptySet() : completed);
                });
                if (closed || accountInvalid || generation != relationGeneration || !checkDirectoryAccount()) closeSubscription(subscription);
                else relationshipRefresh = subscription;
            } catch (RuntimeException unavailable) {
                if (!closed && !accountInvalid && generation == relationGeneration) failRelationRefresh();
            }
        } else loadRelationEntries(generation);
    }

    private void failRelationRefresh() {
        for (RelationState state : relations.values()) { state.loading = false; state.failed = true; }
        updateDirectoryStatus();
    }

    private void loadRelationEntries(int generation) {
        loadRelationEntries(generation, java.util.EnumSet.allOf(FollowListSnapshot.Kind.class));
    }

    private void loadRelationEntries(int generation, Set<FollowListSnapshot.Kind> refreshed) {
        for (FollowListSnapshot.Kind kind : FollowListSnapshot.Kind.values()) {
            if (closed || accountInvalid || generation != relationGeneration || !checkDirectoryAccount()) break;
            RelationState state = relations.get(kind);
            if (!refreshed.contains(kind)) {
                state.loading = false;
                state.failed = true;
                updateDirectoryStatus();
                continue;
            }
            try {
                AutoCloseable subscription = directoryBackend.loadRelation(kind, loaded -> {
                    if (closed || accountInvalid || generation != relationGeneration || !checkDirectoryAccount()) return;
                    state.loading = false;
                    state.failed = !Boolean.TRUE.equals(loaded);
                    if (state.failed) { updateDirectoryStatus(); return; }
                    List<ProfileListEntry> entries = directoryBackend.members(kind);
                    if (closed || generation != relationGeneration || !checkDirectoryAccount()) return;
                    boolean added = mergeUsers(entries);
                    state.uids.clear();
                    for (ProfileListEntry entry : entries) if (userProfile(entry)) state.uids.add(entry.uid);
                    state.complete = true;
                    filter(query);
                    updateDirectoryStatus();
                    if (added) loadProfiles(false);
                });
                if (closed || accountInvalid || generation != relationGeneration || !checkDirectoryAccount()) closeSubscription(subscription);
                else state.subscription = subscription;
            } catch (RuntimeException unavailable) {
                if (!closed && !accountInvalid && generation == relationGeneration) {
                    state.loading = false;
                    state.failed = true;
                    updateDirectoryStatus();
                }
            }
        }
    }

    private void updateDirectoryStatus() {
        if (directoryStatus == null || accountInvalid) return;
        RelationState following = relations.get(FollowListSnapshot.Kind.FOLLOWING);
        RelationState followers = relations.get(FollowListSnapshot.Kind.FOLLOWERS);
        String relationStatus = "";
        if ((directoryScope == 1 || directoryScope == 2) && !following.complete) {
            relationStatus = following.loading ? "正在读取完整关注名单…"
                    : "关注名单未读取完整，点击刷新重试";
        } else if (directoryScope == 3 && !followers.complete) {
            relationStatus = followers.loading ? "正在读取完整粉丝名单…"
                    : "粉丝名单未读取完整，点击刷新重试";
        } else if (following.failed || followers.failed) {
            relationStatus = "部分关系名单未读取完整，点击刷新重试";
        } else if (following.loading || followers.loading) {
            relationStatus = "正在合并关注与粉丝名单…";
        }
        directoryStatus.setText(searchStatus.isEmpty() ? (relationStatus.isEmpty() ? directoryIntro : relationStatus)
                : relationStatus.isEmpty() ? searchStatus : searchStatus + "\n" + relationStatus);
    }

    private void searchDirectory(boolean more) {
        if (directoryBackend == null || closed || accountInvalid
                || directoryLoading || rawQuery.isEmpty() || (more && !hasMore) || !checkDirectoryAccount()) return;
        long requestedCursor = more ? cursor : 0;
        String requestedSearchId = more ? searchId : "";
        String requestedQuery = rawQuery;
        int generation = ++directoryGeneration;
        closeSubscription(directorySubscription);
        directorySubscription = null;
        if (!more) { remoteUids.clear(); hasMore = false; cursor = 0; searchId = ""; }
        directoryLoading = true;
        searchStatus = more ? "正在加载更多用户…" : "正在搜索抖音用户…";
        updateDirectoryStatus();
        filter(query);
        updateDirectoryControls();
        try {
            AutoCloseable subscription = directoryBackend.search(requestedQuery, requestedCursor, requestedSearchId, update -> {
                if (closed || accountInvalid || generation != directoryGeneration || !checkDirectoryAccount()) return;
                if (update.accountChanged) {
                    invalidateAccount(update.error);
                    return;
                }
                directoryLoading = false;
                NativeUserSearch.Page page = update.page;
                if (page == null) {
                    searchStatus = update.error == null || update.error.isEmpty() ? "搜索失败，请重试" : update.error;
                    updateDirectoryStatus();
                    updateDirectoryControls();
                    return;
                }
                if (page.hasMore && page.cursor <= requestedCursor) {
                    hasMore = false;
                    searchStatus = "搜索分页未前进，请重新搜索";
                    updateDirectoryStatus();
                    updateDirectoryControls();
                    return;
                }
                boolean added = mergeUsers(page.entries);
                for (ProfileListEntry entry : page.entries) if (userProfile(entry)) remoteUids.add(entry.uid);
                cursor = page.cursor;
                searchId = page.searchId;
                hasMore = page.hasMore;
                filter(query);
                searchStatus = remoteUids.isEmpty() ? "在线未找到用户，已保留列表中的匹配用户"
                        : "在线找到 " + remoteUids.size() + " 位用户，已合并到列表" + (hasMore ? "，可加载更多" : "");
                updateDirectoryStatus();
                updateDirectoryControls();
                if (added) loadProfiles(false);
            });
            if (closed || accountInvalid || generation != directoryGeneration) closeSubscription(subscription);
            else directorySubscription = subscription;
        } catch (RuntimeException unavailable) {
            if (!closed && !accountInvalid && generation == directoryGeneration) {
                directoryLoading = false;
                searchStatus = "搜索失败，请重试";
                updateDirectoryStatus();
                updateDirectoryControls();
            }
        }
    }

    private boolean mergeUsers(List<ProfileListEntry> entries) {
        boolean added = false;
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < all.size(); i++) if (userProfile(all.get(i))) positions.put(all.get(i).uid, i);
        for (ProfileListEntry entry : entries) {
            if (!userProfile(entry)) continue;
            Integer position = positions.get(entry.uid);
            if (position == null) {
                positions.put(entry.uid, all.size());
                all.add(entry);
                added = true;
            } else all.set(position, all.get(position).withDisplayProfile(entry));
        }
        if (all.stream().anyMatch(MemberListView::userProfile)) {
            refreshProfiles.setVisibility(View.VISIBLE);
            profileStatus.setVisibility(View.VISIBLE);
        }
        return added;
    }

    private void filter(String value) {
        query = value.trim().toLowerCase(Locale.ROOT);
        visible.clear();
        int total = 0;
        for (ProfileListEntry entry : all) {
            if (!matchesScope(entry)) continue;
            total++;
            if (query.isEmpty() || remoteUids.contains(entry.uid) || entry.uid.toLowerCase(Locale.ROOT).contains(query)
                    || entry.name.toLowerCase(Locale.ROOT).contains(query)
                    || entry.douyinId.toLowerCase(Locale.ROOT).contains(query)) visible.add(entry);
        }
        count.setText(visible.isEmpty() ? "无匹配结果 · 共 " + total + " 项"
                : visible.size() + " / " + total + " 项");
        rows.notifyDataSetChanged();
    }

    private boolean matchesScope(ProfileListEntry entry) {
        RelationState following = relations.get(FollowListSnapshot.Kind.FOLLOWING);
        if (directoryScope == 1) return userProfile(entry) && following.complete && !following.uids.contains(entry.uid);
        if (directoryScope == 2) return following.uids.contains(entry.uid);
        if (directoryScope == 3) return relations.get(FollowListSnapshot.Kind.FOLLOWERS).uids.contains(entry.uid);
        return true;
    }

    private static boolean userProfile(ProfileListEntry entry) {
        return entry != null && !entry.conversation && entry.uid.matches("[1-9][0-9]{0,19}");
    }

    private void invalidateAccount(String error) {
        accountInvalid = true;
        profileGeneration++;
        forceProfileRefreshPending = false;
        detachProfiles();
        cancelDirectory();
        detachRelation();
        remoteUids.clear();
        for (RelationState state : relations.values()) { state.uids.clear(); state.complete = false; }
        all.clear();
        for (AvatarTask task : pending.values()) task.cancel();
        pending.clear();
        cache.evictAll();
        failed.clear();
        filter(query);
        refreshProfiles.setEnabled(false);
        String message = error == null || error.isEmpty() ? "登录账号已变化，请重新打开列表" : error;
        profileStatus.setText(message);
        if (directoryStatus != null) directoryStatus.setText(message);
        updateDirectoryControls();
    }

    private void loadProfiles(boolean force) {
        if (closed || accountInvalid || !checkDirectoryAccount()) return;
        forceProfileRefreshPending |= force;
        List<ProfileListEntry> users = new ArrayList<>();
        for (ProfileListEntry entry : all) if (userProfile(entry)) users.add(entry);
        if (users.isEmpty()) return;
        boolean requestForce = forceProfileRefreshPending;
        int generation = ++profileGeneration;
        AutoCloseable previous = profileSubscription;
        profileSubscription = null;
        if (force) {
            failed.clear();
            rows.notifyDataSetChanged();
        }
        profileStatus.setText("正在读取用户资料…");
        try {
            AutoCloseable subscription = profileLoader.load(Collections.unmodifiableList(users), requestForce, update -> {
                if (closed || generation != profileGeneration || !checkDirectoryAccount()) return;
                if (update.accountChanged) {
                    invalidateAccount(update.error);
                    return;
                }
                if (!update.loading) forceProfileRefreshPending = false;
                if (!update.profiles.isEmpty()) {
                    for (int i = 0; i < all.size(); i++) {
                        ProfileListEntry entry = all.get(i);
                        if (!userProfile(entry)) continue;
                        ProfileListEntry loaded = update.profiles.get(entry.uid);
                        if (loaded != null && entry.uid.equals(loaded.uid) && !loaded.conversation) {
                            all.set(i, entry.withDisplayProfile(loaded));
                        }
                    }
                    filter(query);
                }
                profileStatus.setText(update.loading ? (update.error.isEmpty() ? "正在读取用户资料…"
                        : "正在读取用户资料… · " + update.error)
                        : update.error.isEmpty() ? "资料已更新" : update.error);
            });
            if (closed || generation != profileGeneration || !checkDirectoryAccount()) closeSubscription(subscription);
            else profileSubscription = subscription;
        } catch (RuntimeException unavailable) {
            if (!closed && generation == profileGeneration) {
                forceProfileRefreshPending = false;
                profileStatus.setText("资料读取失败，点击刷新重试");
            }
        } finally {
            // The replacement first joins native in-flight jobs, then releases the old listener.
            closeSubscription(previous);
        }
    }

    private void detachProfiles() {
        AutoCloseable previous = profileSubscription;
        profileSubscription = null;
        closeSubscription(previous);
    }

    private static void closeSubscription(AutoCloseable subscription) {
        if (subscription == null) return;
        try { subscription.close(); } catch (Exception ignored) { }
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public ProfileListEntry getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            IdentityRow row = recycled instanceof IdentityRow ? (IdentityRow) recycled : new IdentityRow(getContext());
            ProfileListEntry entry = getItem(position);
            row.setTag(entry.uid);
            row.name.setText(entry.name.isEmpty() ? (entry.conversation ? "群名称未读取" : "昵称未读取") : entry.name);
            row.uid.setText(entry.conversation ? "会话 ID：" + entry.uid
                    : "抖音号：" + (entry.douyinId.isEmpty() ? "未读取" : entry.douyinId));
            boolean unresolved = !entry.conversation && entry.name.isEmpty() && entry.douyinId.isEmpty() && !entry.uid.isEmpty();
            row.fallbackUid.setText(unresolved ? "UID：" + entry.uid : "");
            row.fallbackUid.setVisibility(unresolved ? View.VISIBLE : View.GONE);
            row.setContentDescription(row.name.getText() + "，" + row.uid.getText()
                    + (unresolved ? "，" + row.fallbackUid.getText() : ""));
            row.check.setVisibility(selectable ? View.VISIBLE : View.GONE);
            row.check.setTag("member.selection." + entry.uid);
            row.check.setChecked(selected.contains(entry.selectionKey));
            row.setOnClickListener(selectable ? ignored -> {
                if (!selected.remove(entry.selectionKey)) selected.add(entry.selectionKey);
                row.check.setChecked(selected.contains(entry.selectionKey));
            } : null);
            bindAvatar(row.avatar, entry.avatarUrl);
            return row;
        }
    }

    private final class IdentityRow extends LinearLayout {
        final ImageView avatar;
        final TextView name;
        final TextView uid;
        final TextView fallbackUid;
        final CheckBox check;
        final View selectionView;
        IdentityRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), dp(8), dp(8), dp(8));
            setMinimumHeight(dp(64));
            avatar = new ImageView(context);
            avatar.setContentDescription("头像");
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(Color.rgb(233, 238, 241));
            avatar.setBackground(background);
            avatar.setClipToOutline(true);
            addView(avatar, new LayoutParams(dp(40), dp(40)));
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            LayoutParams textParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
            textParams.setMarginStart(dp(12));
            addView(texts, textParams);
            name = new TextView(context);
            name.setTextSize(14);
            name.setTextColor(Color.rgb(31, 39, 45));
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTag("member.name");
            texts.addView(name);
            uid = new TextView(context);
            uid.setTextSize(12);
            uid.setTextColor(Color.rgb(105, 115, 122));
            uid.setMaxLines(2);
            uid.setEllipsize(TextUtils.TruncateAt.END);
            uid.setTag("member.uid");
            texts.addView(uid);
            fallbackUid = new TextView(context);
            fallbackUid.setTextSize(12);
            fallbackUid.setTextColor(Color.rgb(105, 115, 122));
            fallbackUid.setMaxLines(2);
            fallbackUid.setEllipsize(TextUtils.TruncateAt.END);
            fallbackUid.setTag("member.fallbackUid");
            texts.addView(fallbackUid);
            check = miuix && selectable ? MiuixFormControls.selectionCheckbox(context, this::performClick) : new CheckBox(context);
            check.setClickable(false);
            check.setFocusable(false);
            selectionView = MiuixFormControls.viewFor(check);
            addView(selectionView, new LayoutParams(dp(miuix ? 44 : 40), dp(miuix ? 44 : 40)));
            ModuleTheme.bind(this);
        }

        @Override public void dispatchStartTemporaryDetach() {
            super.dispatchStartTemporaryDetach();
            // ListView keeps scrap rows attached to the window; release their Compose owners explicitly.
            if (selectionView instanceof AbstractComposeView) ((AbstractComposeView) selectionView).disposeComposition();
        }

        @Override public void dispatchFinishTemporaryDetach() {
            super.dispatchFinishTemporaryDetach();
            if (selectionView instanceof AbstractComposeView && selectionView.isAttachedToWindow()) {
                ((AbstractComposeView) selectionView).createComposition();
            }
        }
    }

    private void bindAvatar(ImageView view, String url) {
        Object previous = view.getTag();
        view.setTag(url);
        if (previous instanceof String && !url.equals(previous)) {
            AvatarTask old = pending.get(previous);
            if (old != null) {
                old.targets.removeIf(target -> target.get() == null || target.get() == view);
                if (old.targets.isEmpty()) { pending.remove(previous); old.cancel(); }
            }
        }
        Bitmap cached = cache.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        view.setImageDrawable(placeholder.newDrawable());
        if (url.isEmpty() || closed || failed.contains(url)) return;
        AvatarTask waiting = pending.get(url);
        if (waiting != null) { waiting.add(view); return; }
        AvatarTask task = new AvatarTask(url);
        task.add(view);
        pending.put(url, task);
        WeakReference<MemberListView> owner = new WeakReference<>(this);
        AvatarLoader loader = avatarLoader;
        android.os.Handler dispatcher = main;
        try { task.future = images.submit(() -> {
            Bitmap bitmap = null;
            try { bitmap = loader.load(url, task.request); }
            catch (Exception ignored) { }
            Bitmap result = bitmap;
            if (!dispatcher.post(() -> {
                MemberListView members = owner.get();
                if (members != null) members.avatarLoaded(task, result);
                else if (result != null) result.recycle();
            }) && result != null) result.recycle();
        }); } catch (RejectedExecutionException unavailable) {
            pending.remove(url);
            task.cancel();
        }
    }

    private void avatarLoaded(AvatarTask task, Bitmap bitmap) {
        if (closed || pending.get(task.url) != task) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        pending.remove(task.url);
        if (bitmap == null) { failed.add(task.url); return; }
        cache.put(task.url, bitmap);
        for (WeakReference<ImageView> target : task.targets) {
            ImageView current = target.get();
            if (current != null && task.url.equals(current.getTag())) current.setImageBitmap(bitmap);
        }
    }

    private static final class AvatarTask {
        final String url;
        final AvatarImageLoader.Request request = new AvatarImageLoader.Request();
        final List<WeakReference<ImageView>> targets = new ArrayList<>();
        Future<?> future;

        AvatarTask(String url) { this.url = url; }

        void add(ImageView view) {
            targets.removeIf(target -> target.get() == null);
            for (WeakReference<ImageView> target : targets) if (target.get() == view) return;
            targets.add(new WeakReference<>(view));
        }

        void cancel() {
            request.cancel();
            if (future != null) future.cancel(true);
            targets.clear();
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void close() {
        closed = true;
        profileGeneration++;
        forceProfileRefreshPending = false;
        detachProfiles();
        cancelDirectory();
        detachRelation();
        for (AvatarTask task : pending.values()) task.cancel();
        pending.clear();
        images.shutdownNow();
        cache.evictAll();
        failed.clear();
    }
}
