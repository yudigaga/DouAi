package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.AbsListView;
import android.widget.TextView;
import android.widget.Spinner;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import com.douai.module.R;
import com.douai.module.BuildConfig;
import com.douai.module.feature.ProfileListEntry;
import com.douai.module.feature.AvatarImageLoader;
import com.douai.module.feature.NativeUserProfiles;
import com.douai.module.feature.NativeUserSearch;
import com.douai.module.feature.FollowListSnapshot;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MemberListViewTest {
    @Test public void directoryKeepsLiveLoadingAndErrorsWithoutInlineUsageInstructions() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), backend);
        try {
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            TextView status = members.findViewWithTag("settings.followLists.directoryStatus");
            assertEquals("Usage instructions belong in Help when there is no live status", "", status.getText().toString());
            EditText input = members.findViewWithTag("settings.followLists.search");
            input.setText("Alice");
            assertEquals("Typing only filters existing users", Collections.singletonList("101"), visibleUids(members));
            assertEquals("", status.getText().toString());
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertTrue(status.getText().toString().contains("正在搜索"));
            backend.searches.get(0).updated.accept(new NativeUserSearch.Update(null, "搜索服务暂不可用", false));
            assertEquals("搜索服务暂不可用", status.getText().toString());
            assertEquals(View.VISIBLE, status.getVisibility());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void editorSearchSubmitsTheCurrentQueryOnceAndKeepsBusyEmptyAndClosedGuards() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), backend);
        try {
            EditText input = members.findViewWithTag("settings.followLists.search");
            input.setText("Alice");
            assertTrue(backend.searches.isEmpty());
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals(1, backend.searches.size());
            assertEquals("Alice", backend.searches.get(0).query);
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals("An unfinished search must not be submitted twice", 1, backend.searches.size());
            input.setText("");
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals(1, backend.searches.size());
            input.setText("Bob");
            members.close();
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals(1, backend.searches.size());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void physicalEnterDownAndUpSubmitOnlyOneUserSearch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), backend);
        try {
            EditText input = members.findViewWithTag("settings.followLists.search");
            input.setText("Alice"); input.requestFocus();
            input.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            input.onKeyUp(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            assertEquals(1, backend.searches.size());
            assertEquals("Alice", backend.searches.get(0).query);
        } finally { members.close(); activity.finish(); }
    }

    @Test public void directoryDoesNotRenderTheRemovedInlineSearchOrRefreshActions() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), new DirectoryRequests());
        try {
            assertNull(members.findViewWithTag("settings.followLists.searchAll"));
            assertNull(members.findViewWithTag("settings.followLists.refreshProfiles"));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void nicknameThenParenthesizedRemarkRemainSearchableWithoutChangingSelectionKeys() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        java.lang.reflect.Method fromUser = ProfileListEntry.class.getDeclaredMethod("fromUser", String.class,
                Object.class, ProfileListEntry.class);
        fromUser.setAccessible(true);
        ProfileListEntry person = (ProfileListEntry) fromUser.invoke(null, "101", new RemarkedUser(), null);
        MemberListView members = directory(activity, Collections.singletonList(person), new ProfileRequests(), new DirectoryRequests());
        try {
            assertEquals("Alice（小伙伴）", firstName(members));
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("小伙");
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    public static final class RemarkedUser {
        public String nickName = "Alice";
        public String remarkName = "小伙伴";
    }

    @Test public void hostPickerLoadsPlaceholderFromTheInstalledModulePackage() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        assertEquals(BuildConfig.APPLICATION_ID,
                activity.getResources().getResourcePackageName(R.drawable.ic_member_placeholder));
        Resources hostResources = new Resources(activity.getAssets(), activity.getResources().getDisplayMetrics(),
                activity.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) {
                return "com.ss.android.ugc.aweme";
            }
        };
        String[] requestedPackage = {null};
        Context hostContext = new ContextWrapper(activity) {
            @Override public Resources getResources() { return hostResources; }
            @Override public Context createPackageContext(String packageName, int flags) {
                requestedPackage[0] = packageName;
                assertEquals(Context.CONTEXT_IGNORE_SECURITY, flags);
                return activity;
            }
        };
        MemberListView members = new MemberListView(hostContext, false, Collections.emptyList(),
                Collections.emptySet(), false);
        try {
            assertEquals(BuildConfig.APPLICATION_ID, requestedPackage[0]);
        } finally { members.close(); activity.finish(); }
    }

    @Test public void rowsShowAvatarNicknameDouyinIdAndSelectionSurvivesSearchAndRecycling() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "小满 Alice", "", "alice.dy"), new ProfileListEntry("202", "清风 Bob", "", "bob.dy")),
                Collections.singleton("202"), true);
        try {
            activity.setContentView(members);
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("小满 Alice", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            assertEquals("抖音号：alice.dy", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            assertNotNull(((ImageView) ((android.view.ViewGroup) row).getChildAt(0)).getDrawable());
            row.performClick();
            EditText search = members.findViewWithTag("settings.followLists.search");
            search.setText("202");
            row = list.getAdapter().getView(0, row, list);
            assertEquals("清风 Bob", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            row.performClick();
            assertEquals(Collections.singleton("101"), members.selected());
            search.setText("no match");
            assertEquals(0, list.getAdapter().getCount());
            search.setText("");
            assertEquals(2, list.getAdapter().getCount());
            members.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY));
            members.layout(0, 0, 320, 420);
            Bitmap bitmap = Bitmap.createBitmap(320, 420, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE); members.draw(canvas);
            write(bitmap, "members.png");
        } finally { members.close(); activity.finish(); }
    }

    @Test public void adaptiveIconHasVisiblePixelsAndMonochromeVariant() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Drawable icon = activity.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 256, 256);
        icon.draw(new Canvas(bitmap));
        int first = bitmap.getPixel(128, 128);
        boolean varied = false;
        for (int y = 40; y < 216; y += 4) for (int x = 40; x < 216; x += 4) {
            varied |= bitmap.getPixel(x, y) != first;
        }
        assertTrue(varied);
        assertNotNull(activity.getDrawable(R.drawable.ic_douai_monochrome));
        write(bitmap, "douai-icon.png");
        activity.finish();
    }

    @Test public void nicknameOnlySelectionDoesNotInventAUserUid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Collections.singletonList(ProfileListEntry.named("Alice")),
                Collections.singleton("Alice"), true);
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("抖音号：未读取", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            row.performClick();
            assertTrue(members.selected().isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void douyinIdSearchAndRecyclingKeepSelectionBoundToUid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", "", "alice.dy"),
                new ProfileListEntry("202", "Bob", "", "bob.dy")), Collections.singleton("202"), true);
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            EditText search = members.findViewWithTag("settings.followLists.search");
            assertEquals("昵称、备注、抖音号或 UID", search.getHint().toString());
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("抖音号：alice.dy", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            row.performClick();
            search.setText(" BOB.DY ");
            assertEquals(1, list.getAdapter().getCount());
            row = list.getAdapter().getView(0, row, list);
            assertEquals("Bob", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            assertEquals("抖音号：bob.dy", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            row.performClick();
            assertEquals(Collections.singleton("101"), members.selected());
            search.setText("101");
            assertEquals(1, list.getAdapter().getCount());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void groupConversationKeepsItsOwnIdAndUnresolvedUsersRemainIdentifiable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "", ""), ProfileListEntry.conversation("group-thread", "测试群", "")),
                Collections.emptySet(), true);
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            assertEquals("昵称未读取", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            assertEquals("抖音号：未读取", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            TextView fallback = row.findViewWithTag("member.fallbackUid");
            assertEquals(View.VISIBLE, fallback.getVisibility());
            assertEquals("UID：101", fallback.getText().toString());
            row = list.getAdapter().getView(1, row, list);
            assertEquals("会话 ID：group-thread", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            assertEquals(View.GONE, row.findViewWithTag("member.fallbackUid").getVisibility());
            row.performClick();
            assertEquals(Collections.singleton("group-thread"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void sharedAvatarRequestUpdatesOnlyItsBoundRowsAndCacheAvoidsAnotherLoad() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        int[] loads = {0}, dataChanges = {0};
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String url = "https://p3.douyinpic.com/shared.png";
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", url), new ProfileListEntry("202", "Bob", url)),
                Collections.emptySet(), false, images, (source, request) -> { loads[0]++; return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            list.getAdapter().registerDataSetObserver(new DataSetObserver() {
                @Override public void onChanged() { dataChanges[0]++; }
            });
            View first = list.getAdapter().getView(0, null, list);
            View second = list.getAdapter().getView(1, null, list);
            images.runAll();
            ShadowLooper.idleMainLooper();
            assertEquals(1, loads[0]);
            assertSame(avatar, ((BitmapDrawable) avatar(first).getDrawable()).getBitmap());
            assertSame(avatar, ((BitmapDrawable) avatar(second).getDrawable()).getBitmap());
            assertEquals(0, dataChanges[0]);
            View cached = list.getAdapter().getView(0, null, list);
            images.runAll();
            assertEquals(1, loads[0]);
            assertSame(avatar, ((BitmapDrawable) avatar(cached).getDrawable()).getBitmap());
        } finally { members.close(); avatar.recycle(); activity.finish(); }
    }

    @Test public void recycledRowCancelsItsUnusedQueuedRequestAndReceivesOnlyTheNewAvatar() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        List<String> loaded = new ArrayList<>();
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String firstUrl = "https://p3.douyinpic.com/alice.png", secondUrl = "https://p3.douyinpic.com/bob.png";
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "Alice", firstUrl), new ProfileListEntry("202", "Bob", secondUrl)),
                Collections.emptySet(), false, images, (source, request) -> { loaded.add(source); return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            row = list.getAdapter().getView(1, row, list);
            images.runAll();
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList(secondUrl), loaded);
            assertEquals(secondUrl, avatar(row).getTag());
            assertSame(avatar, ((BitmapDrawable) avatar(row).getDrawable()).getBitmap());
        } finally { members.close(); avatar.recycle(); activity.finish(); }
    }

    @Test public void closingCancelsRequestsAndRecyclesResultsAlreadyWaitingForMainThread() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        QueuedExecutor images = new QueuedExecutor();
        AvatarImageLoader.Request[] request = {null};
        Bitmap avatar = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        String url = "https://p3.douyinpic.com/alice.png";
        MemberListView members = new MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("101", "Alice", url)),
                Collections.emptySet(), false, images, (source, pending) -> { request[0] = pending; return avatar; });
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            Drawable placeholder = avatar(row).getDrawable();
            images.runAll();
            members.close();
            assertTrue(images.isShutdown());
            try { request[0].load(url); fail("Closed member picker must cancel its request"); }
            catch (CancellationException expected) { }
            ShadowLooper.idleMainLooper();
            assertSame(placeholder, avatar(row).getDrawable());
            assertTrue(avatar.isRecycled());
        } finally { members.close(); if (!avatar.isRecycled()) avatar.recycle(); activity.finish(); }
    }

    @Test public void nativeProfileUpdatesKeepSearchSelectionAndRecycledRowsBoundToTheOriginalUid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                new ProfileListEntry("101", "", ""), new ProfileListEntry("202", "Bob", "")),
                Collections.singleton("101"), true, new QueuedExecutor(), (url, request) -> null, profiles);
        try {
            assertEquals(1, profiles.requests.size());
            assertFalse(profiles.requests.get(0).force);
            EditText search = members.findViewWithTag("settings.followLists.search");
            ListView list = members.findViewWithTag("settings.followLists.members");
            TextView status = members.findViewWithTag("settings.followLists.profileStatus");
            assertNotNull(status);
            assertFalse(status.getText().toString().isEmpty());
            search.setText("101");
            View row = list.getAdapter().getView(0, null, list);
            profiles.requests.get(0).send(new ProfileListEntry("101", "真实 Alice", "", "alice.dy"), false, "", false);
            row = list.getAdapter().getView(0, row, list);
            assertEquals("101", search.getText().toString());
            assertEquals("真实 Alice", ((TextView) row.findViewWithTag("member.name")).getText().toString());
            assertEquals("抖音号：alice.dy", ((TextView) row.findViewWithTag("member.uid")).getText().toString());
            assertEquals("101", row.getTag());
            assertEquals(Collections.singleton("101"), members.selected());
            search.setText("ALICE.DY");
            assertEquals(1, list.getAdapter().getCount());
            row = list.getAdapter().getView(0, row, list);
            row.performClick();
            assertTrue(members.selected().isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void refreshAndCloseDetachSubscriptionsAndRejectLateProfileCallbacks() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = new MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("101", "old", "")), Collections.emptySet(), false,
                new QueuedExecutor(), (url, request) -> null, profiles);
        try {
            Button refresh = refreshButton(members);
            assertNotNull(refresh);
            assertEquals("刷新", refresh.getText().toString());
            refresh.performClick();
            assertEquals(2, profiles.requests.size());
            assertTrue(profiles.requests.get(0).closed);
            assertTrue(profiles.requests.get(1).force);
            profiles.requests.get(0).send(new ProfileListEntry("101", "late old", ""), false, "", false);
            assertEquals("old", firstName(members));
            profiles.requests.get(1).send(new ProfileListEntry("101", "fresh", ""), false, "", false);
            assertEquals("fresh", firstName(members));
            members.close();
            assertTrue(profiles.requests.get(1).closed);
            profiles.requests.get(1).send(new ProfileListEntry("101", "after close", ""), false, "", false);
            assertEquals("fresh", firstName(members));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void errorsRemainRetryableAndAccountChangesHideRowsWithoutChangingStoredSelections() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = new MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("101", "old", "")), Collections.singleton("101"), true,
                new QueuedExecutor(), (url, request) -> null, profiles);
        try {
            TextView status = members.findViewWithTag("settings.followLists.profileStatus");
            Button refresh = refreshButton(members);
            assertNotNull(status);
            assertNotNull(refresh);
            assertEquals(1, profiles.requests.size());
            profiles.requests.get(0).send(null, false, "资料读取超时，点击刷新重试", false);
            assertTrue(status.getText().toString().contains("超时"));
            assertTrue(refresh.isEnabled());
            profiles.requests.get(0).send(new ProfileListEntry("101", "stale", ""), false, "登录账号已变化，请重新打开列表", true);
            ListView list = members.findViewWithTag("settings.followLists.members");
            assertEquals(0, list.getAdapter().getCount());
            assertEquals(Collections.singleton("101"), members.selected());
            assertFalse(refresh.isEnabled());
            assertTrue(profiles.requests.get(0).closed);
            assertTrue(status.getText().toString().contains("账号"));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void refreshLetsAnAvatarUrlThatPreviouslyFailedLoadAgain() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileRequests profiles = new ProfileRequests();
        QueuedExecutor images = new QueuedExecutor();
        int[] attempts = {0};
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        MemberListView members = new MemberListView(activity, false, Collections.singletonList(
                new ProfileListEntry("101", "Alice", "https://p3.douyinpic.com/retry.jpg")), Collections.emptySet(), false,
                images, (url, request) -> ++attempts[0] == 1 ? null : bitmap, profiles);
        try {
            ListView list = members.findViewWithTag("settings.followLists.members");
            View row = list.getAdapter().getView(0, null, list);
            images.runAll(); ShadowLooper.idleMainLooper();
            row = list.getAdapter().getView(0, row, list);
            images.runAll();
            assertEquals(1, attempts[0]);
            Button refresh = refreshButton(members);
            assertNotNull(refresh);
            refresh.performClick();
            row = list.getAdapter().getView(0, row, list);
            images.runAll(); ShadowLooper.idleMainLooper();
            assertEquals(2, attempts[0]);
            assertSame(bitmap, ((BitmapDrawable) avatar(row).getDrawable()).getBitmap());
        } finally { members.close(); bitmap.recycle(); activity.finish(); }
    }

    @Test public void groupsAndNicknameRulesDoNotStartProfileRequests() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = new MemberListView(activity, false, Arrays.asList(
                ProfileListEntry.conversation("101", "群", ""), ProfileListEntry.named("Alice")),
                Collections.emptySet(), false, new QueuedExecutor(), (url, request) -> null, profiles);
        try {
            assertTrue(profiles.requests.isEmpty());
            View refresh = members.findViewWithTag("settings.followLists.refreshProfiles");
            View status = members.findViewWithTag("settings.followLists.profileStatus");
            assertTrue(refresh == null || refresh.getVisibility() == View.GONE);
            assertTrue(status == null || status.getVisibility() == View.GONE);
        } finally { members.close(); activity.finish(); }
    }

    @Test public void explicitDirectorySearchFindsAnUnfollowedUidAndKeepsFuzzyNativeResultsVisible() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.emptyList(), profiles, directory);
        try {
            EditText query = members.findViewWithTag("settings.followLists.search");
            EditText search = members.findViewWithTag("settings.followLists.search");
            assertNotNull(search);
            search.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertTrue(directory.searches.isEmpty());
            query.setText("别名");
            assertTrue(directory.searches.isEmpty());
            assertTrue(search.isEnabled());
            search.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals("别名", directory.searches.get(0).query);
            directory.searches.get(0).send(Arrays.asList(
                    new ProfileListEntry("909", "Native fuzzy result", "", "brand.dy")), 7, true, "native-session");
            assertEquals(Collections.singletonList("909"), visibleUids(members));
            assertEquals("Native fuzzy result", firstName(members));
            ListView list = members.findViewWithTag("settings.followLists.members");
            list.getAdapter().getView(0, null, list).performClick();
            assertEquals(new LinkedHashSet<>(Arrays.asList("101", "909")), members.selected());
            Button refresh = refreshButton(members);
            assertEquals(View.VISIBLE, refresh.getVisibility());
            assertEquals(1, profiles.requests.size());
            profiles.requests.get(0).send(new ProfileListEntry("909", "Resolved fuzzy", "", "resolved.dy"), false, "", false);
            assertEquals(Collections.singletonList("909"), visibleUids(members));
            writeView(members, "user-directory-search.png");
            query.setText("absent local text");
            assertTrue(visibleUids(members).isEmpty());
            assertEquals(new LinkedHashSet<>(Arrays.asList("101", "909")), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void allRelationshipsAreMergedOnceAndScopesOnlyFilterTheSameUidCollection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        directory.members.put(FollowListSnapshot.Kind.FOLLOWING, Arrays.asList(
                new ProfileListEntry("201", "Following only", ""), new ProfileListEntry("202", "Mutual", "")));
        directory.members.put(FollowListSnapshot.Kind.FOLLOWERS, Arrays.asList(
                new ProfileListEntry("202", "Mutual", ""), new ProfileListEntry("303", "Follower only", "")));
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")),
                new ProfileRequests(), directory);
        try {
            Spinner scope = members.findViewWithTag("settings.followLists.scope");
            assertNotNull(scope);
            assertEquals(4, scope.getCount());
            assertEquals("未关注", scope.getItemAtPosition(1));
            assertEquals("我关注", scope.getItemAtPosition(2));
            assertEquals("关注我", scope.getItemAtPosition(3));
            assertEquals(2, directory.relations.size());
            directory.relations.get(0).send(true);
            directory.relations.get(1).send(true);
            assertEquals(Arrays.asList("101", "201", "202", "303"), visibleUids(members));
            ListView list = members.findViewWithTag("settings.followLists.members");
            scope.setSelection(2); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("201", "202"), visibleUids(members));
            list.getAdapter().getView(0, null, list).performClick();
            EditText query = members.findViewWithTag("settings.followLists.search");
            query.setText("following only");
            assertEquals(Collections.singletonList("201"), visibleUids(members));
            assertTrue(directory.searches.isEmpty());
            query.setText("");
            scope.setSelection(3); ShadowLooper.idleMainLooper();
            assertSame(list, members.findViewWithTag("settings.followLists.members"));
            assertFalse(directory.relations.get(0).closed);
            assertFalse(directory.relations.get(1).closed);
            assertEquals(Arrays.asList("202", "303"), visibleUids(members));
            assertEquals(new LinkedHashSet<>(Arrays.asList("101", "201")), members.selected());
            writeView(members, "user-directory-followers.png");
            scope.setSelection(1); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("101", "303"), visibleUids(members));
            scope.setSelection(0); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("101", "201", "202", "303"), visibleUids(members));
            assertEquals(2, directory.relations.size());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void emptyOnlineSearchKeepsMatchingUsersInTheOneAggregateList() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), directory);
        try {
            EditText query = members.findViewWithTag("settings.followLists.search");
            query.setText("alice");
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            submitSearch(members);
            directory.searches.get(0).send(Collections.emptyList(), 0, false, "empty");
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus"))
                    .getText().toString().contains("在线"));
            assertEquals(Collections.singleton("101"), members.selected());
            query.setText("");
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void oneBottomSwipeLoadsNextPageWithoutAButtonOrProgrammaticChaining() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), backend);
        try {
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("person");
            submitSearch(members);
            backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Person One", "")), 7, true, "page-one");
            assertNull(members.findViewWithTag("settings.followLists.loadMore"));
            layoutDirectory(members);
            notifyScrollLayout(members);
            assertEquals("Layout is not a request for another page", 1, backend.searches.size());
            swipeTowardEnd(members);
            assertEquals(2, backend.searches.size());
            assertEquals(7, backend.searches.get(1).cursor);
            assertEquals("page-one", backend.searches.get(1).searchId);
            swipeTowardEnd(members);
            assertEquals("A busy page must not be submitted twice", 2, backend.searches.size());
            backend.searches.get(1).send(Collections.singletonList(new ProfileListEntry("808", "Person Two", "")), 14, true, "page-two");
            layoutDirectory(members); notifyScrollLayout(members); notifyScrollLayout(members);
            assertEquals("The page callback must not chain through the directory", 2, backend.searches.size());
            swipeTowardEnd(members);
            assertEquals(14, backend.searches.get(2).cursor);
            assertEquals("page-two", backend.searches.get(2).searchId);
            backend.searches.get(2).send(Collections.emptyList(), 21, false, "last");
            swipeTowardEnd(members);
            assertEquals("A terminal page stays terminal", 3, backend.searches.size());
            assertEquals(Arrays.asList("909", "808"), visibleUids(members));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void emptyFilteredPagesContinueAndFailuresNeedAnotherUpwardSwipeToRetry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), backend);
        try {
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            ((Spinner) members.findViewWithTag("settings.followLists.scope")).setSelection(2);
            ShadowLooper.idleMainLooper();
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("person");
            submitSearch(members);
            backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Person One", "")), 7, true, "page-one");
            assertTrue(visibleUids(members).isEmpty());
            swipeTowardEnd(members);
            assertEquals("An empty filtered viewport must still accept the user's upward gesture", 2, backend.searches.size());
            backend.searches.get(1).updated.accept(new NativeUserSearch.Update(null, "下一页读取失败", false));
            notifyScrollLayout(members); notifyScrollLayout(members);
            assertEquals("A failure must not retry without a new gesture", 2, backend.searches.size());
            ((Spinner) members.findViewWithTag("settings.followLists.scope")).setSelection(0);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("909"), visibleUids(members));
            swipeTowardStart(members);
            assertEquals("Browsing toward the top must not request the next page", 2, backend.searches.size());
            swipeTowardEnd(members);
            assertEquals(3, backend.searches.size());
            assertEquals(7, backend.searches.get(2).cursor);
            assertEquals("page-one", backend.searches.get(2).searchId);
            backend.searches.get(2).send(Collections.singletonList(new ProfileListEntry("808", "Person Two", "")), 14, false, "last");
            assertEquals(Arrays.asList("909", "808"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void aGestureStartedBeforeQueryReplacementCannotLoadItsNewCursor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), backend);
        try {
            EditText search = members.findViewWithTag("settings.followLists.search");
            search.setText("old"); submitSearch(members);
            backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Old", "")), 7, true, "old-id");
            layoutDirectory(members);
            ListView list = members.findViewWithTag("settings.followLists.members");
            long down = SystemClock.uptimeMillis();
            touchList(list, down, down, MotionEvent.ACTION_DOWN, list.getHeight() * .8f);
            search.setText("new"); submitSearch(members);
            backend.searches.get(1).send(Collections.singletonList(new ProfileListEntry("808", "New", "")), 20, true, "new-id");
            touchList(list, down, down + 120, MotionEvent.ACTION_MOVE, list.getHeight() * .2f);
            touchList(list, down, down + 240, MotionEvent.ACTION_UP, list.getHeight() * .2f);
            assertEquals("An old pointer gesture cannot advance a replacement query", 2, backend.searches.size());
            swipeTowardEnd(members);
            assertEquals(3, backend.searches.size());
            assertEquals("new", backend.searches.get(2).query);
            assertEquals(20, backend.searches.get(2).cursor);
            assertEquals("new-id", backend.searches.get(2).searchId);
        } finally { members.close(); activity.finish(); }
    }

    @Test public void bottomSwipesAfterCloseOrAccountChangeCannotStartAnotherPage() {
        for (boolean accountChange : new boolean[]{false, true}) {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            DirectoryRequests backend = new DirectoryRequests();
            backend.accountState = FollowListSnapshot.activateAccount("11");
            MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), backend);
            try {
                ((EditText) members.findViewWithTag("settings.followLists.search")).setText("person");
                submitSearch(members);
                backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Person", "")), 7, true, "id");
                if (accountChange) backend.accountState = FollowListSnapshot.activateAccount("22");
                else members.close();
                swipeTowardEnd(members);
                assertEquals(1, backend.searches.size());
                if (accountChange) assertTrue(visibleUids(members).isEmpty());
            } finally { members.close(); activity.finish(); }
        }
    }

    @Test public void searchPaginationMergesByUidAndStopsWhenTheNativeCursorDoesNotAdvance() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), directory);
        try {
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("person");
            submitSearch(members);
            directory.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "First", "")), 7, true, "sid");
            assertNull(members.findViewWithTag("settings.followLists.loadMore"));
            swipeTowardEnd(members);
            SearchRequest next = directory.searches.get(1);
            assertEquals(7, next.cursor);
            assertEquals("sid", next.searchId);
            swipeTowardEnd(members);
            assertEquals("A pending page cannot be requested twice", 2, directory.searches.size());
            next.send(Arrays.asList(new ProfileListEntry("909", "Updated", ""), new ProfileListEntry("808", "Second", "")),
                    14, true, "sid-2");
            assertEquals(Arrays.asList("909", "808"), visibleUids(members));
            assertEquals("Updated", firstName(members));
            swipeTowardEnd(members);
            directory.searches.get(2).send(Collections.singletonList(new ProfileListEntry("707", "Stuck", "")), 14, true, "sid-2");
            swipeTowardEnd(members);
            assertEquals(3, directory.searches.size());
            assertFalse(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void changingQueryCancelsSearchButChangingFilterKeepsSearchAndBothRelationshipLoads() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), directory);
        try {
            EditText query = members.findViewWithTag("settings.followLists.search");
            query.setText("old");
            submitSearch(members);
            query.setText("new");
            assertTrue(directory.searches.get(0).closed);
            submitSearch(members);
            directory.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "old", "")), 7, false, "old");
            assertTrue(visibleUids(members).isEmpty());
            directory.searches.get(1).send(Collections.singletonList(new ProfileListEntry("808", "new", "")), 7, true, "new");
            assertEquals(Collections.singletonList("808"), visibleUids(members));
            swipeTowardEnd(members);
            Spinner scope = members.findViewWithTag("settings.followLists.scope");
            scope.setSelection(2); ShadowLooper.idleMainLooper();
            assertFalse(directory.searches.get(2).closed);
            assertEquals(2, directory.relations.size());
            directory.members.put(FollowListSnapshot.Kind.FOLLOWING,
                    Collections.singletonList(new ProfileListEntry("201", "new following", "")));
            directory.relations.get(0).send(true);
            assertEquals(Collections.singletonList("201"), visibleUids(members));
            directory.searches.get(2).send(Collections.singletonList(new ProfileListEntry("707", "new late", "")), 14, false, "new");
            assertEquals(Collections.singletonList("201"), visibleUids(members));
            scope.setSelection(0); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("808", "201", "707"), visibleUids(members));
            members.close();
            assertTrue(directory.relations.get(0).closed);
            assertTrue(directory.relations.get(1).closed);
            directory.relations.get(0).send(true);
            assertEquals(Arrays.asList("808", "201", "707"), visibleUids(members));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void nativeSearchAndMoreWorkInsideEveryFilterAndKeepTheirFuzzyMatchesInTheAggregate() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.members.put(FollowListSnapshot.Kind.FOLLOWING, Collections.singletonList(new ProfileListEntry("201", "Following", "")));
        backend.members.put(FollowListSnapshot.Kind.FOLLOWERS, Collections.singletonList(new ProfileListEntry("303", "Follower", "")));
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")), new ProfileRequests(), backend);
        try {
            assertEquals(2, backend.relations.size());
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            Spinner filter = members.findViewWithTag("settings.followLists.scope");
            filter.setSelection(1); ShadowLooper.idleMainLooper();
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("alias");
            EditText search = members.findViewWithTag("settings.followLists.search");
            assertEquals(View.VISIBLE, search.getVisibility()); assertTrue(search.isEnabled());
            search.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Fuzzy stranger", "")), 7, true, "sid");
            assertEquals(Collections.singletonList("909"), visibleUids(members));
            filter.setSelection(2); ShadowLooper.idleMainLooper();
            assertNull(members.findViewWithTag("settings.followLists.loadMore"));
            swipeTowardEnd(members);
            assertEquals(7, backend.searches.get(1).cursor);
            backend.searches.get(1).send(Arrays.asList(new ProfileListEntry("201", "Fuzzy followed", ""),
                    new ProfileListEntry("303", "Fuzzy follower", "")), 14, false, "sid2");
            assertEquals(Collections.singletonList("201"), visibleUids(members));
            filter.setSelection(3); ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("303"), visibleUids(members));
            filter.setSelection(0); ShadowLooper.idleMainLooper();
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("");
            assertEquals(Arrays.asList("101", "201", "303", "909"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void unfollowedWaitsForACompleteFollowingSnapshotAndCanRetryWithoutLosingThePool() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.members.put(FollowListSnapshot.Kind.FOLLOWERS, Collections.singletonList(new ProfileListEntry("303", "Follower", "")));
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")), new ProfileRequests(), backend);
        try {
            assertEquals(2, backend.relations.size());
            backend.relations.get(1).send(true);
            Spinner filter = members.findViewWithTag("settings.followLists.scope");
            filter.setSelection(1); ShadowLooper.idleMainLooper();
            assertTrue("Unknown following membership must not mean unfollowed", visibleUids(members).isEmpty());
            backend.relations.get(0).send(false);
            assertTrue(visibleUids(members).isEmpty());
            assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().contains("关注"));
            members.refreshUsers();
            assertEquals(4, backend.relations.size());
            backend.members.put(FollowListSnapshot.Kind.FOLLOWING, Collections.singletonList(new ProfileListEntry("101", "Local", "")));
            backend.relations.get(2).send(true);
            assertEquals(Collections.singletonList("303"), visibleUids(members));
            filter.setSelection(0); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("101", "303"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void aFailedFollowingRefreshCannotReuseOldAbsenceToClassifyUsersAsUnfollowed() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.members.put(FollowListSnapshot.Kind.FOLLOWING,
                Collections.singletonList(new ProfileListEntry("201", "Following", "")));
        backend.members.put(FollowListSnapshot.Kind.FOLLOWERS,
                Collections.singletonList(new ProfileListEntry("303", "Follower", "")));
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")),
                new ProfileRequests(), backend);
        try {
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            Spinner filter = members.findViewWithTag("settings.followLists.scope");
            filter.setSelection(1); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("101", "303"), visibleUids(members));
            members.refreshUsers();
            assertEquals(4, backend.relations.size());
            backend.relations.get(2).send(false);
            assertTrue("An incomplete refreshed snapshot cannot prove that a user is unfollowed", visibleUids(members).isEmpty());
            assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().contains("关注"));
            filter.setSelection(0); ShadowLooper.idleMainLooper();
            assertEquals(Arrays.asList("101", "201", "303"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
            filter.setSelection(2); ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("201"), visibleUids(members));
        } finally { members.close(); activity.finish(); }
    }

    @Test public void accountChangeClearsDirectoryAndRejectsOutstandingProfilesAndSearches() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")), profiles, directory);
        try {
            EditText query = members.findViewWithTag("settings.followLists.search");
            query.setText("Local");
            submitSearch(members);
            directory.searches.get(0).updated.accept(new NativeUserSearch.Update(null, "登录账号已变化，请重新打开列表", true));
            assertTrue(visibleUids(members).isEmpty());
            assertEquals(Collections.singleton("101"), members.selected());
            assertFalse(query.isEnabled());
            assertFalse(members.findViewWithTag("settings.followLists.scope").isEnabled());
            assertFalse(members.findViewWithTag("settings.followLists.search").isEnabled());
            assertTrue(directory.searches.get(0).closed);
            assertTrue(profiles.requests.get(0).closed);
            profiles.requests.get(0).send(new ProfileListEntry("101", "Late profile", ""), false, "", false);
            directory.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Late search", "")), 7, false, "late");
            assertTrue(visibleUids(members).isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void failedDirectorySearchRemainsRetryableAndDefaultGroupPickerHasNoDirectoryControls() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        MemberListView members = directory(activity, Collections.emptyList(), new ProfileRequests(), directory);
        MemberListView groups = new MemberListView(activity, false, Collections.singletonList(
                ProfileListEntry.conversation("group", "Group", "")), Collections.emptySet(), true);
        try {
            assertNull(groups.findViewWithTag("settings.followLists.scope"));
            assertNull(groups.findViewWithTag("settings.followLists.searchAll"));
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("person");
            EditText search = members.findViewWithTag("settings.followLists.search");
            search.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            directory.searches.get(0).updated.accept(new NativeUserSearch.Update(null, "搜索失败，请重试", false));
            assertTrue(search.isEnabled());
            assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().contains("重试"));
            search.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            assertEquals(2, directory.searches.size());
            members.close();
            assertTrue(directory.searches.get(1).closed);
        } finally { members.close(); groups.close(); activity.finish(); }
    }

    @Test public void profileAccountChangeAlsoCancelsPendingDirectorySearchAndRelationPages() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Local", "")), profiles, directory);
        try {
            ((Spinner) members.findViewWithTag("settings.followLists.scope")).setSelection(1);
            ShadowLooper.idleMainLooper();
            profiles.requests.get(0).send(null, false, "账号已变化", true);
            assertTrue(directory.relations.get(0).closed);
            assertFalse(members.findViewWithTag("settings.followLists.search").isEnabled());
            assertFalse(members.findViewWithTag("settings.followLists.scope").isEnabled());
            directory.members.put(FollowListSnapshot.Kind.FOLLOWING,
                    Collections.singletonList(new ProfileListEntry("909", "Late member", "")));
            directory.relations.get(0).send(true);
            assertTrue(visibleUids(members).isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void directoryCannotRebindProfilesToAnotherAccountWhileMergingSearchResults() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        directory.accountState = FollowListSnapshot.activateAccount("11");
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Account A user", "")), profiles, directory);
        try {
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("remote");
            submitSearch(members);
            profiles.onLoad = () -> directory.accountState = FollowListSnapshot.activateAccount("22");
            directory.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Account A remote", "")), 7, false, "A");
            assertInvalidDirectory(members);
            assertEquals(Collections.singleton("101"), members.selected());
            assertTrue(directory.searches.get(0).closed);
            assertEquals(2, profiles.requests.size());
            for (ProfileRequest request : profiles.requests) assertTrue(request.closed);
            profiles.requests.get(0).send(null, false, "账号已变化", true);
            profiles.requests.get(1).send(new ProfileListEntry("909", "Account B profile", ""), false, "", false);
            assertTrue(visibleUids(members).isEmpty());
        } finally { members.close(); activity.finish(); FollowListSnapshot.reset(); }
    }

    @Test public void relationshipCallbackRejectsTheSameUidAfterAnAccountSwitchAndReturn() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        directory.accountState = FollowListSnapshot.activateAccount("11");
        directory.members.put(FollowListSnapshot.Kind.FOLLOWING,
                Collections.singletonList(new ProfileListEntry("909", "Old relationship", "")));
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Account A user", "")), profiles, directory);
        try {
            ((Spinner) members.findViewWithTag("settings.followLists.scope")).setSelection(1);
            ShadowLooper.idleMainLooper();
            FollowListSnapshot.activateAccount("22");
            directory.accountState = FollowListSnapshot.activateAccount("11");
            directory.relations.get(0).send(true);
            assertInvalidDirectory(members);
            assertTrue(directory.relations.get(0).closed);
            assertTrue(profiles.requests.get(0).closed);
        } finally { members.close(); activity.finish(); FollowListSnapshot.reset(); }
    }

    @Test public void emptyDirectoryPinsItsFirstValidAccountBeforeStartingTheSearch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.emptyList(), profiles, directory);
        try {
            directory.accountState = FollowListSnapshot.activateAccount("11");
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("remote");
            submitSearch(members);
            directory.accountState = FollowListSnapshot.activateAccount("22");
            directory.searches.get(0).send(Collections.singletonList(new ProfileListEntry("909", "Old account user", "")), 7, false, "A");
            assertInvalidDirectory(members);
            assertTrue(directory.searches.get(0).closed);
            assertTrue(profiles.requests.isEmpty());
        } finally { members.close(); activity.finish(); FollowListSnapshot.reset(); }
    }

    @Test public void profileCallbackChecksThePinnedAccountEvenWithoutAnAccountChangedFlag() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests directory = new DirectoryRequests();
        directory.accountState = FollowListSnapshot.activateAccount("11");
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Account A user", "")), profiles, directory);
        try {
            directory.accountState = FollowListSnapshot.activateAccount("22");
            profiles.requests.get(0).send(new ProfileListEntry("101", "Old account profile", ""), false, "", false);
            assertInvalidDirectory(members);
            assertTrue(profiles.requests.get(0).closed);
        } finally { members.close(); activity.finish(); FollowListSnapshot.reset(); }
    }

    @Test public void nativeIdentityRowsAndCountsFollowDayNightDayWithoutLosingSelection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemberListView members = new MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("101", "Alice", "", "alice.dy")), Collections.singleton("101"), true,
                new QueuedExecutor(), (url, request) -> null, new ProfileRequests());
        try {
            activity.setContentView(members);
            members.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
            members.layout(0, 0, 320, 640);
            ShadowLooper.idleMainLooper();
            View row = members.findViewWithTag("101");
            assertNotNull("Mounted identity row must remain in the same view tree", row);
            TextView name = row.findViewWithTag("member.name");
            TextView identity = row.findViewWithTag("member.uid");
            TextView count = members.findViewWithTag("settings.followLists.resultCount");
            for (boolean dark : new boolean[]{true, false}) {
                changeNightMode(activity, dark);
                ShadowLooper.idleMainLooper();
                assertEquals(dark ? 0xFFF2F2F2 : 0xFF1F272D, name.getCurrentTextColor());
                assertEquals(dark ? 0xFFB0B6BB : 0xFF69737A, identity.getCurrentTextColor());
                assertEquals(dark ? 0xFFB0B6BB : 0xFF69737A, count.getCurrentTextColor());
                assertEquals(Collections.singleton("101"), members.selected());
            }
        } finally { changeNightMode(activity, false); members.close(); activity.finish(); }
    }

    @SuppressWarnings("deprecation")
    private static void changeNightMode(Activity activity, boolean dark) {
        Resources system = Resources.getSystem();
        Configuration configuration = new Configuration(system.getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        system.updateConfiguration(configuration, system.getDisplayMetrics());
        activity.getApplication().onConfigurationChanged(configuration);
    }

    private static void assertInvalidDirectory(MemberListView members) {
        assertTrue(visibleUids(members).isEmpty());
        for (String tag : Arrays.asList("search", "scope")) {
            assertFalse("Account change must disable " + tag, members.findViewWithTag("settings.followLists." + tag).isEnabled());
        }
        assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().contains("账号"));
    }

    private static MemberListView directory(Activity activity, List<ProfileListEntry> initial,
                                             ProfileRequests profiles, DirectoryRequests directory) {
        MemberListView members = new MemberListView(activity, false, initial, Collections.singleton("101"), true, true,
                new QueuedExecutor(), (url, request) -> null, profiles, directory);
        for (String tag : Arrays.asList("search", "scope", "directoryStatus", "members")) {
            assertNotNull("Directory control missing: " + tag, members.findViewWithTag("settings.followLists." + tag));
        }
        activity.setContentView(members);
        ShadowLooper.idleMainLooper();
        return members;
    }

    private static List<String> visibleUids(MemberListView members) {
        ListView list = members.findViewWithTag("settings.followLists.members");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) result.add(((ProfileListEntry) list.getAdapter().getItem(i)).uid);
        return result;
    }

    @Test public void oneRefreshForcesRelationsAndProfilesWithoutSubmittingTheCurrentSelection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.holdRefresh = true;
        backend.members.put(FollowListSnapshot.Kind.FOLLOWING,
                Collections.singletonList(new ProfileListEntry("101", "Alice", "")));
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")), profiles, backend);
        try {
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            Spinner filter = members.findViewWithTag("settings.followLists.scope");
            filter.setSelection(2); ShadowLooper.idleMainLooper();
            EditText query = members.findViewWithTag("settings.followLists.search");
            query.setText("Alice");
            ListView list = members.findViewWithTag("settings.followLists.members");
            list.getAdapter().getView(0, null, list).performClick();
            assertTrue(members.selected().isEmpty());
            Button refresh = refreshButton(members);
            refresh.performClick();
            assertEquals("Refresh must request fresh relationships, not only reuse cached lists", 1, backend.refreshes.size());
            assertEquals("Wait for the force refresh before reading its snapshots", 2, backend.relations.size());
            assertTrue(profiles.requests.get(profiles.requests.size() - 1).force);
            assertEquals("刷新", refresh.getText().toString());
            assertEquals("Alice", query.getText().toString());
            assertEquals(2, filter.getSelectedItemPosition());
            assertTrue(members.selected().isEmpty());
            backend.members.put(FollowListSnapshot.Kind.FOLLOWING,
                    Collections.singletonList(new ProfileListEntry("202", "Bob", "")));
            backend.refreshes.get(0).send(true);
            assertEquals(4, backend.relations.size());
            backend.relations.get(2).send(true); backend.relations.get(3).send(true);
            assertTrue(visibleUids(members).isEmpty());
            query.setText("");
            assertEquals(Collections.singletonList("202"), visibleUids(members));
            assertTrue(members.selected().isEmpty());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void forcedRefreshRejectsReplacedClosedAndOtherAccountCallbacks() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.holdRefresh = true;
        backend.accountState = FollowListSnapshot.activateAccount("11");
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), backend);
        try {
            members.refreshUsers();
            assertEquals(1, backend.refreshes.size());
            members.refreshUsers();
            assertEquals(2, backend.refreshes.size());
            assertTrue(backend.refreshes.get(0).closed);
            backend.refreshes.get(0).send(true);
            assertEquals(2, backend.relations.size());
            backend.accountState = FollowListSnapshot.activateAccount("22");
            backend.refreshes.get(1).send(true);
            assertInvalidDirectory(members);
            assertTrue(backend.refreshes.get(1).closed);
            members.close();
            backend.refreshes.get(1).send(true);
            assertTrue(visibleUids(members).isEmpty());
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); FollowListSnapshot.reset(); }
    }

    @Test public void aFailedForcedRefreshDoesNotReuseCachedAbsenceAndRemainsRetryable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.holdRefresh = true;
        MemberListView members = directory(activity, Collections.singletonList(new ProfileListEntry("101", "Alice", "")),
                new ProfileRequests(), backend);
        try {
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            Spinner filter = members.findViewWithTag("settings.followLists.scope");
            filter.setSelection(1); ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            members.refreshUsers();
            assertEquals(1, backend.refreshes.size());
            backend.refreshes.get(0).send(false);
            assertEquals(2, backend.relations.size());
            assertTrue(visibleUids(members).isEmpty());
            assertTrue(refreshButton(members).isEnabled());
            assertTrue(((TextView) members.findViewWithTag("settings.followLists.directoryStatus")).getText().toString().contains("重试"));
            members.refreshUsers();
            backend.refreshes.get(1).send(true);
            backend.relations.get(2).send(true); backend.relations.get(3).send(true);
            assertEquals(Collections.singletonList("101"), visibleUids(members));
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void relationshipMergeKeepsAnUnfinishedProfileForceRefreshUntilItsTerminalUpdate() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.holdRefresh = true;
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity,
                Collections.singletonList(new ProfileListEntry("101", "Cached Alice", "")), profiles, backend);
        try {
            profiles.requests.get(0).send(new ProfileListEntry("101", "Cached Alice", ""), false, "", false);
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            members.refreshUsers();
            ProfileRequest unfinished = profiles.requests.get(1);
            assertTrue(unfinished.force);
            backend.members.put(FollowListSnapshot.Kind.FOLLOWING,
                    Collections.singletonList(new ProfileListEntry("202", "New following", "")));
            backend.refreshes.get(0).send(true);
            backend.relations.get(2).send(true);

            ProfileRequest resumed = profiles.requests.get(profiles.requests.size() - 1);
            resumed.send(new ProfileListEntry("101", resumed.force ? "Fresh Alice" : "Cached Alice", ""), false, "", false);
            assertEquals("Adding a following user must not turn an unfinished force refresh into a cached read",
                    "Fresh Alice", firstName(members));
            assertTrue(unfinished.closed);
            backend.members.put(FollowListSnapshot.Kind.FOLLOWERS,
                    Collections.singletonList(new ProfileListEntry("303", "New follower", "")));
            backend.relations.get(3).send(true);
            assertFalse("After the terminal update, adding another user uses an ordinary profile read",
                    profiles.requests.get(profiles.requests.size() - 1).force);
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    @Test public void searchMergeKeepsAnUnfinishedProfileForceRefreshUntilItsTerminalUpdate() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        DirectoryRequests backend = new DirectoryRequests();
        backend.holdRefresh = true;
        ProfileRequests profiles = new ProfileRequests();
        MemberListView members = directory(activity,
                Collections.singletonList(new ProfileListEntry("101", "Cached Alice", "")), profiles, backend);
        try {
            profiles.requests.get(0).send(new ProfileListEntry("101", "Cached Alice", ""), false, "", false);
            backend.relations.get(0).send(true); backend.relations.get(1).send(true);
            members.refreshUsers();
            assertTrue(profiles.requests.get(1).force);
            ((EditText) members.findViewWithTag("settings.followLists.search")).setText("101");
            submitSearch(members);
            backend.searches.get(0).send(Collections.singletonList(new ProfileListEntry("202", "Online user", "")), 7, false, "first");

            ProfileRequest resumed = profiles.requests.get(profiles.requests.size() - 1);
            resumed.send(new ProfileListEntry("101", resumed.force ? "Fresh Alice" : "Cached Alice", ""), false, "", false);
            assertEquals("An online result must not replace a requested fresh identity with its old cached value",
                    "Fresh Alice", firstName(members));
            submitSearch(members);
            backend.searches.get(1).send(Collections.singletonList(new ProfileListEntry("303", "Another online user", "")), 8, false, "second");
            assertFalse("A later search after completion must not keep forcing every cached identity",
                    profiles.requests.get(profiles.requests.size() - 1).force);
            assertEquals("101", ((EditText) members.findViewWithTag("settings.followLists.search")).getText().toString());
            assertEquals(Collections.singleton("101"), members.selected());
        } finally { members.close(); activity.finish(); }
    }

    private static final class DirectoryRequests implements MemberListView.DirectoryBackend {
        FollowListSnapshot.State accountState;
        boolean holdRefresh;
        final List<RefreshRequest> refreshes = new ArrayList<>();
        final List<SearchRequest> searches = new ArrayList<>();
        final List<RelationRequest> relations = new ArrayList<>();
        final Map<FollowListSnapshot.Kind, List<ProfileListEntry>> members = new EnumMap<>(FollowListSnapshot.Kind.class);
        @Override public FollowListSnapshot.State account() { return accountState; }
        @Override public AutoCloseable search(String query, long cursor, String searchId, Consumer<NativeUserSearch.Update> updated) {
            SearchRequest request = new SearchRequest(query, cursor, searchId, updated);
            searches.add(request); return request;
        }
        @Override public AutoCloseable loadRelation(FollowListSnapshot.Kind kind, Consumer<Boolean> loaded) {
            RelationRequest request = new RelationRequest(kind, loaded);
            relations.add(request); return request;
        }
        @Override public AutoCloseable refreshRelations(Consumer<java.util.Set<FollowListSnapshot.Kind>> completed) {
            RefreshRequest request = new RefreshRequest(completed);
            refreshes.add(request);
            if (!holdRefresh) request.send(true);
            return request;
        }
        @Override public List<ProfileListEntry> members(FollowListSnapshot.Kind kind) {
            return members.getOrDefault(kind, Collections.emptyList());
        }
    }

    private static final class RefreshRequest implements AutoCloseable {
        final Consumer<java.util.Set<FollowListSnapshot.Kind>> completed;
        boolean closed;
        RefreshRequest(Consumer<java.util.Set<FollowListSnapshot.Kind>> completed) { this.completed = completed; }
        void send(boolean success) { completed.accept(success
                ? java.util.EnumSet.allOf(FollowListSnapshot.Kind.class) : Collections.emptySet()); }
        @Override public void close() { closed = true; }
    }

    private static final class SearchRequest implements AutoCloseable {
        final String query, searchId;
        final long cursor;
        final Consumer<NativeUserSearch.Update> updated;
        boolean closed;
        SearchRequest(String query, long cursor, String searchId, Consumer<NativeUserSearch.Update> updated) {
            this.query = query; this.cursor = cursor; this.searchId = searchId; this.updated = updated;
        }
        void send(List<ProfileListEntry> entries, long cursor, boolean hasMore, String searchId) {
            updated.accept(new NativeUserSearch.Update(new NativeUserSearch.Page(entries, cursor, hasMore, searchId), "", false));
        }
        @Override public void close() { closed = true; }
    }

    private static final class RelationRequest implements AutoCloseable {
        final FollowListSnapshot.Kind kind;
        final Consumer<Boolean> loaded;
        boolean closed;
        RelationRequest(FollowListSnapshot.Kind kind, Consumer<Boolean> loaded) { this.kind = kind; this.loaded = loaded; }
        void send(boolean success) { loaded.accept(success); }
        @Override public void close() { closed = true; }
    }

    private static void submitSearch(MemberListView members) {
        ((EditText) members.findViewWithTag("settings.followLists.search")).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
    }

    private static void layoutDirectory(MemberListView members) {
        members.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        members.layout(0, 0, 360, 640);
    }

    private static void notifyScrollLayout(MemberListView members) {
        ListView list = members.findViewWithTag("settings.followLists.members");
        org.robolectric.shadows.ShadowListView shadow = org.robolectric.shadow.api.Shadow.extract(list);
        AbsListView.OnScrollListener listener = shadow.getOnScrollListener();
        if (listener != null) listener.onScroll(list, list.getFirstVisiblePosition(), list.getChildCount(), list.getCount());
    }

    private static void swipeTowardEnd(MemberListView members) { swipeList(members, true); }
    private static void swipeTowardStart(MemberListView members) { swipeList(members, false); }

    private static void swipeList(MemberListView members, boolean towardEnd) {
        layoutDirectory(members);
        ListView list = members.findViewWithTag("settings.followLists.members");
        assertTrue("The actual user viewport must have height before swiping", list.getHeight() > 0);
        long now = SystemClock.uptimeMillis();
        float start = list.getHeight() * (towardEnd ? .8f : .2f);
        float end = list.getHeight() * (towardEnd ? .2f : .8f);
        touchList(list, now, now, MotionEvent.ACTION_DOWN, start);
        touchList(list, now, now + 120, MotionEvent.ACTION_MOVE, end);
        touchList(list, now, now + 240, MotionEvent.ACTION_UP, end);
        ShadowLooper.idleMainLooper();
    }

    private static void touchList(ListView list, long down, long time, int action, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, Math.max(1, list.getWidth() / 2f), y, 0);
        try { list.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static Button refreshButton(MemberListView members) {
        Button button = new Button(members.getContext());
        button.setText("刷新");
        members.bindRefreshButton(button);
        return button;
    }

    private static String firstName(MemberListView members) {
        ListView list = members.findViewWithTag("settings.followLists.members");
        return ((TextView) list.getAdapter().getView(0, null, list).findViewWithTag("member.name")).getText().toString();
    }

    private static final class ProfileRequests implements MemberListView.ProfileLoader {
        final List<ProfileRequest> requests = new ArrayList<>();
        Runnable onLoad;
        @Override public AutoCloseable load(List<ProfileListEntry> entries, boolean force, Consumer<NativeUserProfiles.Update> updated) {
            ProfileRequest request = new ProfileRequest(force, updated);
            requests.add(request);
            if (onLoad != null) onLoad.run();
            return request;
        }
    }

    private static final class ProfileRequest implements AutoCloseable {
        final boolean force;
        final Consumer<NativeUserProfiles.Update> updated;
        boolean closed;
        ProfileRequest(boolean force, Consumer<NativeUserProfiles.Update> updated) { this.force = force; this.updated = updated; }
        void send(ProfileListEntry profile, boolean loading, String error, boolean accountChanged) {
            updated.accept(new NativeUserProfiles.Update(profile == null ? Collections.emptyMap()
                    : Collections.singletonMap(profile.uid, profile), loading, error, accountChanged));
        }
        @Override public void close() { closed = true; }
    }

    private static ImageView avatar(View row) { return (ImageView) ((android.view.ViewGroup) row).getChildAt(0); }

    private static final class QueuedExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> commands = new ArrayDeque<>();
        boolean stopped;
        void runAll() { while (!commands.isEmpty()) commands.remove().run(); }
        @Override public void execute(Runnable command) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            commands.add(command);
        }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() {
            stopped = true; List<Runnable> remaining = new ArrayList<>(commands); commands.clear(); return remaining;
        }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped && commands.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static void write(Bitmap bitmap, String name) {
        String target = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (target == null) return;
        File directory = new File(target); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void writeView(View view, String name) {
        if (System.getenv("DOUAI_CHAT_SCREENSHOTS") == null) return;
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 320, 640);
        Bitmap bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        view.draw(new Canvas(bitmap));
        write(bitmap, name);
        bitmap.recycle();
    }
}
