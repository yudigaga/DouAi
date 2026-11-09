package com.douai.module.feature;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import com.douai.module.config.SidebarSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HomeSidebarModuleHookTest {
    @Test public void themedViewContextsResolveTheActivityWithoutAcceptingApplicationContext() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            android.content.Context themed = new android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material);
            assertSame(activity, HomeSidebarGestureHook.activityContext(themed, Activity.class));
            assertNull(HomeSidebarGestureHook.activityContext(activity.getApplicationContext(), Activity.class));
        } finally { activity.finish(); }
    }

    @Test public void onlyTheVerifiedHomeServiceModuleIsEligible() {
        assertTrue(HomeSidebarModuleHook.matchesSection(true, "service_module"));
        assertFalse(HomeSidebarModuleHook.matchesSection(false, "service_module"));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, "life_module"));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, "top_area"));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, ""));
    }

    @Test public void anOnlineModuleRequiresBothTheCombinedKindAndItsMoreFunctionComponent() {
        assertTrue(HomeSidebarModuleHook.matchesSection(true, "online_module_42", "more_function_view_module", true));
        assertTrue(HomeSidebarModuleHook.matchesSection(true, null, "more_function_view_module", true));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, "online_module_42", "grid_list", true));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, "online_module_42", "more_function_view_module", false));
        assertFalse(HomeSidebarModuleHook.matchesSection(true, "online_module_42", null, true));
        assertFalse(HomeSidebarModuleHook.matchesSection(false, "online_module_42", "more_function_view_module", true));
        assertFalse(HomeSidebarModuleHook.matchesSection(false, "service_module", "more_function_view_module", true));
        assertTrue(HomeSidebarModuleHook.matchesSection(true, "service_module", null, false));
    }

    @Test public void theMainContainerMustBeTheSameObjectEvenWhenTheBottomContainerHasTheSameTag() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            LinearLayout main = new LinearLayout(activity);
            FrameLayout bottom = new FrameLayout(activity);
            main.setTag("sidebar_modules");
            bottom.setTag("sidebar_modules");
            assertTrue(HomeSidebarModuleHook.matchesContainer(main, main));
            assertFalse(HomeSidebarModuleHook.matchesContainer(main, bottom));
            assertFalse(HomeSidebarModuleHook.matchesContainer(null, bottom));
            assertFalse(HomeSidebarModuleHook.matchesContainer(null, null));
        } finally { activity.finish(); }
    }

    @Test public void selectedRowsReplaceOnlyTheirNativeSectionAndRestoreItWithoutChangingItsChildren() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout nativeView = new LinearLayout(activity);
        TextView original = new TextView(activity);
        original.setText("观看历史");
        nativeView.addView(original);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(320, -2);
        params.bottomMargin = 12;
        nativeView.setLayoutParams(params);
        List<String> clicked = new ArrayList<>();
        HomeSidebarModuleHook.Binding binding = new HomeSidebarModuleHook.Binding(nativeView, false, id -> clicked.add(id));
        try {
            binding.update(true, SidebarSettings.parse("comment_history,reply_history"));
            assertSame(params, binding.view().getLayoutParams());
            assertEquals(1, nativeView.getChildCount());
            assertSame(original, nativeView.getChildAt(0));
            assertEquals(View.GONE, nativeView.getVisibility());
            TextView comment = binding.view().findViewWithTag("douai.sidebar.comment_history");
            assertNotNull(comment);
            assertTrue(comment.performClick());
            assertEquals(List.of("comment_history"), clicked);
            binding.update(false, SidebarSettings.parse("module"));
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(View.GONE, binding.module.getVisibility());
            assertFalse(comment.performClick());
            binding.update(true, List.of());
            assertTrue(((TextView) binding.module.getChildAt(1)).getText().toString().contains("尚未选择"));
            binding.restore();
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(1, nativeView.getChildCount());
            assertEquals(0, binding.module.getChildCount());
        } finally { binding.restore(); activity.finish(); }
    }

    @Test public void darkRowsHaveOpaqueReadableTextAndDoNotIntroduceAnInnerScrollContainer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HomeSidebarModuleHook.Binding binding = new HomeSidebarModuleHook.Binding(new FrameLayout(activity), true, ignored -> { });
        try {
            binding.update(true, SidebarSettings.parse("module"));
            TextView row = binding.view().findViewWithTag("douai.sidebar.module");
            assertEquals(0xE6FFFFFF, row.getCurrentTextColor());
            assertEquals(Math.round(48 * activity.getResources().getDisplayMetrics().density), row.getMinHeight());
            assertTrue(binding.module instanceof LinearLayout);
            assertEquals("android.widget.Button", row.getAccessibilityClassName().toString());
        } finally { binding.restore(); activity.finish(); }
    }

    @Test public void anAbortedWrapReturnsAnUnparentedNativeViewWithItsOriginalLayout() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout nativeView = new FrameLayout(activity);
        LinearLayout.LayoutParams original = new LinearLayout.LayoutParams(300, -2);
        original.topMargin = 7;
        nativeView.setLayoutParams(original);
        HomeSidebarModuleHook.Binding binding = new HomeSidebarModuleHook.Binding(nativeView, false, ignored -> { });
        try {
            binding.update(true, SidebarSettings.parse("module"));
            binding.rollback();
            assertNull(nativeView.getParent());
            assertSame(original, nativeView.getLayoutParams());
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(0, binding.module.getChildCount());
        } finally { binding.restore(); activity.finish(); }
    }

    @Test public void attachingACachedSectionKeepsItsPositionLayoutAndNativeTag() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        View before = new View(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        TextView nativeChild = new TextView(activity);
        nativeView.addView(nativeChild);
        nativeView.setTag("grid_list");
        LinearLayout.LayoutParams original = new LinearLayout.LayoutParams(320, -2);
        original.leftMargin = 11;
        original.bottomMargin = 17;
        View after = new View(activity);
        list.addView(before);
        list.addView(nativeView, original);
        list.addView(after);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> { });
            binding.update(false, SidebarSettings.parse("module"));

            assertEquals(3, list.getChildCount());
            assertSame(before, list.getChildAt(0));
            assertSame(binding.view(), list.getChildAt(1));
            assertSame(after, list.getChildAt(2));
            assertSame(list, binding.view().getParent());
            assertSame(original, binding.view().getLayoutParams());
            assertEquals(11, ((LinearLayout.LayoutParams) binding.view().getLayoutParams()).leftMargin);
            assertEquals(17, ((LinearLayout.LayoutParams) binding.view().getLayoutParams()).bottomMargin);
            assertEquals("grid_list", binding.view().getTag());
            assertEquals("grid_list", nativeView.getTag());
            assertSame(binding.view(), nativeView.getParent());
            assertSame(nativeChild, nativeView.getChildAt(0));
            assertEquals(View.VISIBLE, nativeView.getVisibility());
        } finally {
            if (binding != null) binding.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void rollingBackAnAttachedSectionRestoresItsNativeSiblingPositionAndListeners() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        View before = new View(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        nativeView.setTag("grid_list");
        nativeView.setVisibility(View.INVISIBLE);
        TextView nativeChild = new TextView(activity);
        List<String> clicked = new ArrayList<>();
        nativeChild.setOnClickListener(ignored -> clicked.add("native"));
        nativeView.addView(nativeChild);
        LinearLayout.LayoutParams original = new LinearLayout.LayoutParams(300, -2);
        original.topMargin = 13;
        View after = new View(activity);
        list.addView(before);
        list.addView(nativeView, original);
        list.addView(after);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> clicked.add(id));
            binding.update(true, SidebarSettings.parse("comment_history"));
            TextView shortcut = binding.view().findViewWithTag("douai.sidebar.comment_history");
            assertNotNull(shortcut);

            binding.rollbackAttached();

            assertEquals(3, list.getChildCount());
            assertSame(before, list.getChildAt(0));
            assertSame(nativeView, list.getChildAt(1));
            assertSame(after, list.getChildAt(2));
            assertSame(list, nativeView.getParent());
            assertNull(binding.view().getParent());
            assertSame(original, nativeView.getLayoutParams());
            assertEquals(13, ((LinearLayout.LayoutParams) nativeView.getLayoutParams()).topMargin);
            assertEquals("grid_list", nativeView.getTag());
            assertEquals(View.INVISIBLE, nativeView.getVisibility());
            assertSame(nativeChild, nativeView.getChildAt(0));
            assertFalse(shortcut.performClick());
            assertTrue(nativeChild.performClick());
            assertEquals(List.of("native"), clicked);
        } finally {
            if (binding != null) binding.restore();
            activity.finish();
        }
    }

    @Test public void cachedCellsResolveTheirOwnNativeOrWrappedGridSection() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        nativeView.setTag("grid_list");
        GridLayout grid = new GridLayout(activity);
        grid.setId(0x7f0a1a98);
        FrameLayout cellRoot = new FrameLayout(activity);
        TextView cell = new TextView(activity);
        cellRoot.addView(cell);
        grid.addView(cellRoot);
        nativeView.addView(grid);
        list.addView(nativeView);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            assertSame(nativeView, HomeSidebarModuleHook.cachedSectionRoot(cell, list));

            binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> { });
            binding.update(true, SidebarSettings.parse("module"));

            assertSame(binding.view(), HomeSidebarModuleHook.cachedSectionRoot(cell, list));
            assertEquals("grid_list", HomeSidebarModuleHook.cachedSectionRoot(cell, list).getTag());
            assertSame(cellRoot, cell.getParent());
            assertSame(grid, cellRoot.getParent());
        } finally {
            if (binding != null) binding.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void cachedSectionLookupRejectsAnotherOwnerAndSectionsWithoutTheNativeGrid() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout owner = new LinearLayout(activity);
        LinearLayout otherOwner = new LinearLayout(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        nativeView.setTag("grid_list");
        GridLayout grid = new GridLayout(activity);
        grid.setId(0x7f0a1a98);
        TextView cell = new TextView(activity);
        grid.addView(cell);
        nativeView.addView(grid);
        owner.addView(nativeView);
        FrameLayout noGrid = new FrameLayout(activity);
        noGrid.setTag("grid_list");
        TextView unrelatedCell = new TextView(activity);
        noGrid.addView(unrelatedCell);
        owner.addView(noGrid);
        activity.setContentView(owner);
        try {
            assertNull(HomeSidebarModuleHook.cachedSectionRoot(cell, otherOwner));
            assertNull(HomeSidebarModuleHook.cachedSectionRoot(unrelatedCell, owner));
            assertSame(nativeView, HomeSidebarModuleHook.cachedSectionRoot(cell, owner));
            assertSame(noGrid, unrelatedCell.getParent());
        } finally { activity.finish(); }
    }

    @Test public void combinedCardsResolveDynamicallyCreatedCellsWithoutAResourceIdOnlyWithinTheirOwner() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        LinearLayout otherOwner = new LinearLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        card.setTag("more_function_view_module");
        GridLayout dynamicGrid = new GridLayout(activity);
        FrameLayout cellRoot = new FrameLayout(activity);
        TextView cell = new TextView(activity);
        cellRoot.addView(cell);
        dynamicGrid.addView(cellRoot);
        card.addView(dynamicGrid);
        list.addView(card);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            assertEquals(View.NO_ID, dynamicGrid.getId());
            assertEquals(View.NO_ID, card.getId());
            assertSame(card, HomeSidebarModuleHook.cachedCombinedSectionRoot(cell, list));
            assertNull(HomeSidebarModuleHook.cachedCombinedSectionRoot(cell, otherOwner));

            binding = HomeSidebarModuleHook.Binding.attach(card, false, (id, owner) -> { });
            binding.update(true, SidebarSettings.parse("module"));

            assertSame(binding.view(), HomeSidebarModuleHook.cachedCombinedSectionRoot(cell, list));
            assertEquals("more_function_view_module", binding.view().getTag());
            assertNull(HomeSidebarModuleHook.cachedCombinedSectionRoot(cell, otherOwner));
            assertSame(dynamicGrid, cellRoot.getParent());
            assertSame(card, dynamicGrid.getParent());
        } finally {
            if (binding != null) binding.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void aSectionCreatedWhileDisabledUsesNewSelectionsWhenEnabledWithoutDiscardingNativeChildren() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        TextView original = new TextView(activity);
        original.setText("观看历史");
        List<String> clicked = new ArrayList<>();
        original.setOnClickListener(ignored -> clicked.add("native"));
        nativeView.addView(original);
        list.addView(nativeView);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> clicked.add(id));
            binding.update(false, SidebarSettings.parse("module"));
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(View.GONE, binding.module.getVisibility());
            assertNull(binding.view().findViewWithTag("douai.sidebar.module"));

            binding.update(true, SidebarSettings.parse("comment_history"));
            TextView previous = binding.view().findViewWithTag("douai.sidebar.comment_history");
            assertNotNull(previous);
            assertEquals(View.GONE, nativeView.getVisibility());
            assertTrue(previous.performClick());

            binding.update(true, SidebarSettings.parse("reply_history,ai_chat"));
            assertNull(binding.view().findViewWithTag("douai.sidebar.comment_history"));
            assertFalse(previous.performClick());
            TextView reply = binding.view().findViewWithTag("douai.sidebar.reply_history");
            assertNotNull(reply);
            assertNotNull(binding.view().findViewWithTag("douai.sidebar.ai_chat"));
            assertTrue(reply.performClick());

            binding.update(false, SidebarSettings.parse("module"));
            assertFalse(reply.performClick());
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(1, nativeView.getChildCount());
            assertSame(original, nativeView.getChildAt(0));
            assertTrue(original.performClick());

            binding.update(true, SidebarSettings.parse("module"));
            assertNotNull(binding.view().findViewWithTag("douai.sidebar.module"));
            assertNull(binding.view().findViewWithTag("douai.sidebar.reply_history"));
            assertEquals(List.of("comment_history", "reply_history", "native"), clicked);
            assertSame(original, nativeView.getChildAt(0));
        } finally {
            if (binding != null) binding.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void measuringAnEnabledSectionHidesAnAsynchronouslyRevealedNativeCardAndRestoresItsLatestVisibility() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        nativeView.setVisibility(View.GONE);
        TextView nativeChild = new TextView(activity);
        nativeView.addView(nativeChild);
        list.addView(nativeView);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = null;
        try {
            binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> { });
            binding.update(true, SidebarSettings.parse("module"));
            assertEquals(View.GONE, nativeView.getVisibility());

            nativeView.setVisibility(View.VISIBLE);
            binding.view().measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));

            assertEquals(View.GONE, nativeView.getVisibility());
            assertEquals(View.VISIBLE, binding.module.getVisibility());
            assertTrue(binding.module.getMeasuredHeight() > 0);
            assertNotNull(binding.view().findViewWithTag("douai.sidebar.module"));
            assertSame(nativeChild, nativeView.getChildAt(0));

            binding.update(true, SidebarSettings.ENTRIES);
            int fixedWidth = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY);
            int fixedHeight = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY);
            binding.view().measure(fixedWidth, fixedHeight);
            assertTrue(binding.view().getMeasuredHeight() > 240);
            assertEquals(SidebarSettings.ENTRIES.size() + 1, binding.module.getChildCount());
            for (SidebarSettings.Entry entry : SidebarSettings.ENTRIES) {
                assertNotNull(binding.view().findViewWithTag("douai.sidebar." + entry.id));
            }

            binding.update(false, SidebarSettings.ENTRIES);
            binding.view().measure(fixedWidth, fixedHeight);

            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(View.GONE, binding.module.getVisibility());
            assertEquals(240, binding.view().getMeasuredHeight());
            assertSame(nativeChild, nativeView.getChildAt(0));
        } finally {
            if (binding != null) binding.restore();
            activity.finish();
        }
    }

    @Test public void measuringADisabledSectionLeavesHostVisibilityChangesUntouched() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout nativeView = new FrameLayout(activity);
        nativeView.setVisibility(View.GONE);
        HomeSidebarModuleHook.Binding binding = new HomeSidebarModuleHook.Binding(nativeView, false, ignored -> { });
        activity.setContentView(binding.view());
        try {
            binding.update(false, SidebarSettings.parse("module"));
            for (int visibility : new int[]{View.VISIBLE, View.INVISIBLE, View.GONE}) {
                nativeView.setVisibility(visibility);
                binding.view().measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST));
                assertEquals(visibility, nativeView.getVisibility());
                assertEquals(View.GONE, binding.module.getVisibility());
            }

            int fixedWidth = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY);
            int fixedHeight = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY);
            nativeView.setVisibility(View.GONE);
            binding.view().measure(fixedWidth, fixedHeight);
            assertEquals(View.GONE, nativeView.getVisibility());
            assertEquals(0, binding.view().getMeasuredHeight());

            nativeView.setVisibility(View.VISIBLE);
            binding.view().measure(fixedWidth, fixedHeight);
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(View.GONE, binding.module.getVisibility());
            assertEquals(240, binding.view().getMeasuredHeight());
        } finally { binding.restore(); activity.finish(); }
    }

    @Test public void duplicateCommonCardsShowOnlyTheCandidateAfterTheUnchangedNotificationCard() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout firstNative = new FrameLayout(activity);
        FrameLayout notification = new FrameLayout(activity);
        TextView noticeContent = new TextView(activity);
        noticeContent.setText("通知消息");
        List<String> clicked = new ArrayList<>();
        noticeContent.setOnClickListener(ignored -> clicked.add("notification"));
        notification.addView(noticeContent);
        FrameLayout afterNative = new FrameLayout(activity);
        Space tail = new Space(activity);
        list.addView(firstNative);
        list.addView(notification);
        list.addView(afterNative);
        list.addView(tail);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding first = HomeSidebarModuleHook.Binding.attach(firstNative, false, (id, owner) -> { });
        HomeSidebarModuleHook.Binding after = HomeSidebarModuleHook.Binding.attach(afterNative, false, (id, owner) -> { });
        try {
            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(after, first), list, Set.of(notification),
                    true, SidebarSettings.parse("module,comment_history")));

            assertEquals(View.GONE, first.view().getVisibility());
            assertEquals(View.GONE, first.module.getVisibility());
            assertEquals(View.GONE, firstNative.getVisibility());
            assertEquals(View.VISIBLE, after.view().getVisibility());
            assertEquals(View.VISIBLE, after.module.getVisibility());
            assertEquals(View.GONE, afterNative.getVisibility());
            assertSame(notification, list.getChildAt(1));
            assertSame(after.view(), list.getChildAt(2));
            assertSame(tail, list.getChildAt(3));
            assertSame(noticeContent, notification.getChildAt(0));
            assertEquals(View.VISIBLE, notification.getVisibility());
            assertTrue(noticeContent.performClick());
            assertEquals(List.of("notification"), clicked);
            assertNotNull(after.view().findViewWithTag("douai.sidebar.comment_history"));

            assertEquals(0, HomeSidebarModuleHook.reconcile(List.of(after, first), list, Set.of(notification),
                    false, SidebarSettings.parse("module,comment_history")));
            assertEquals(View.VISIBLE, first.view().getVisibility());
            assertEquals(View.VISIBLE, firstNative.getVisibility());
            assertEquals(View.VISIBLE, after.view().getVisibility());
            assertEquals(View.VISIBLE, afterNative.getVisibility());
            assertEquals(View.GONE, first.module.getVisibility());
            assertEquals(View.GONE, after.module.getVisibility());
            assertSame(notification, list.getChildAt(1));
            assertSame(tail, list.getChildAt(3));
        } finally {
            first.rollbackAttached();
            after.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void aSingleCardMovesAfterNotificationsOnceAndRestoresItsOriginalNeighborsAndLayoutWhenDisabled() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        View prefix = new View(activity);
        FrameLayout nativeView = new FrameLayout(activity);
        LinearLayout.LayoutParams original = new LinearLayout.LayoutParams(320, -2);
        original.topMargin = 19;
        original.bottomMargin = 7;
        View neighbor = new View(activity);
        FrameLayout notification = new FrameLayout(activity);
        Space tail = new Space(activity);
        list.addView(prefix);
        list.addView(nativeView, original);
        list.addView(neighbor);
        list.addView(notification);
        list.addView(tail);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding binding = HomeSidebarModuleHook.Binding.attach(nativeView, false, (id, owner) -> { });
        List<View> added = new ArrayList<>();
        List<View> removed = new ArrayList<>();
        list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { added.add(child); }
            @Override public void onChildViewRemoved(View parent, View child) { removed.add(child); }
        });
        try {
            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(binding), list, Set.of(notification),
                    true, SidebarSettings.parse("module")));
            assertSame(prefix, list.getChildAt(0));
            assertSame(neighbor, list.getChildAt(1));
            assertSame(notification, list.getChildAt(2));
            assertSame(binding.view(), list.getChildAt(3));
            assertSame(tail, list.getChildAt(4));
            assertEquals(List.of(binding.view()), added);
            assertEquals(List.of(binding.view()), removed);
            assertSame(original, binding.view().getLayoutParams());

            HomeSidebarModuleHook.reconcile(List.of(binding), list, Set.of(notification), true, SidebarSettings.parse("module"));
            assertEquals(List.of(binding.view()), added);
            assertEquals(List.of(binding.view()), removed);

            assertEquals(0, HomeSidebarModuleHook.reconcile(List.of(binding), list, Set.of(notification),
                    false, SidebarSettings.parse("module")));
            assertSame(prefix, list.getChildAt(0));
            assertSame(binding.view(), list.getChildAt(1));
            assertSame(neighbor, list.getChildAt(2));
            assertSame(notification, list.getChildAt(3));
            assertSame(tail, list.getChildAt(4));
            assertSame(original, binding.view().getLayoutParams());
            assertEquals(19, ((LinearLayout.LayoutParams) binding.view().getLayoutParams()).topMargin);
            assertEquals(7, ((LinearLayout.LayoutParams) binding.view().getLayoutParams()).bottomMargin);
            assertEquals(View.VISIBLE, nativeView.getVisibility());
            assertEquals(View.GONE, binding.module.getVisibility());
            assertEquals(List.of(binding.view(), binding.view()), added);
            assertEquals(List.of(binding.view(), binding.view()), removed);
        } finally {
            list.setOnHierarchyChangeListener(null);
            binding.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void allCandidatesIdentifiedAsNotificationCardsRestoreNativeContentAndLeaveOnlyOneOrdinaryModuleList() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout nativeNotification = new FrameLayout(activity);
        TextView noticeContent = new TextView(activity);
        noticeContent.setText("通知消息");
        nativeNotification.addView(noticeContent);
        FrameLayout secondNativeNotification = new FrameLayout(activity);
        TextView secondNoticeContent = new TextView(activity);
        secondNativeNotification.addView(secondNoticeContent);
        FrameLayout nativeCommon = new FrameLayout(activity);
        list.addView(nativeNotification);
        list.addView(secondNativeNotification);
        list.addView(nativeCommon);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding notification = HomeSidebarModuleHook.Binding.attach(nativeNotification, false, (id, owner) -> { });
        HomeSidebarModuleHook.Binding secondNotification = HomeSidebarModuleHook.Binding.attach(secondNativeNotification, false, (id, owner) -> { });
        HomeSidebarModuleHook.Binding common = HomeSidebarModuleHook.Binding.attach(nativeCommon, false, (id, owner) -> { });
        try {
            notification.update(true, SidebarSettings.parse("module"));
            secondNotification.update(true, SidebarSettings.parse("module"));
            assertEquals(View.GONE, nativeNotification.getVisibility());
            assertEquals(View.GONE, secondNativeNotification.getVisibility());

            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(notification, secondNotification, common), list,
                    Set.of(notification.view(), secondNotification.view()),
                    true, SidebarSettings.parse("module")));

            assertSame(notification.view(), list.getChildAt(0));
            assertEquals(View.VISIBLE, notification.view().getVisibility());
            assertEquals(View.VISIBLE, nativeNotification.getVisibility());
            assertEquals(View.GONE, notification.module.getVisibility());
            assertSame(noticeContent, nativeNotification.getChildAt(0));
            assertSame(secondNotification.view(), list.getChildAt(1));
            assertEquals(View.VISIBLE, secondNotification.view().getVisibility());
            assertEquals(View.VISIBLE, secondNativeNotification.getVisibility());
            assertEquals(View.GONE, secondNotification.module.getVisibility());
            assertSame(secondNoticeContent, secondNativeNotification.getChildAt(0));
            assertSame(common.view(), list.getChildAt(2));
            assertEquals(View.VISIBLE, common.module.getVisibility());
            assertEquals(View.GONE, nativeCommon.getVisibility());
        } finally {
            notification.rollbackAttached();
            secondNotification.rollbackAttached();
            common.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void aLateNotificationIdentityRepositionsTheOrdinaryCardAfterTheLastRestoredNotification() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout list = new LinearLayout(activity);
        FrameLayout lateNativeNotification = new FrameLayout(activity);
        TextView lateNoticeContent = new TextView(activity);
        lateNativeNotification.addView(lateNoticeContent);
        FrameLayout nativeCommon = new FrameLayout(activity);
        FrameLayout knownNotification = new FrameLayout(activity);
        Space tail = new Space(activity);
        list.addView(lateNativeNotification);
        list.addView(nativeCommon);
        list.addView(knownNotification);
        list.addView(tail);
        activity.setContentView(list);
        HomeSidebarModuleHook.Binding lateNotification = HomeSidebarModuleHook.Binding.attach(lateNativeNotification, false, (id, owner) -> { });
        HomeSidebarModuleHook.Binding common = HomeSidebarModuleHook.Binding.attach(nativeCommon, false, (id, owner) -> { });
        try {
            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(lateNotification, common), list,
                    Set.of(knownNotification), true, SidebarSettings.parse("module")));
            assertSame(common.view(), list.getChildAt(0));
            assertSame(knownNotification, list.getChildAt(1));
            assertSame(lateNotification.view(), list.getChildAt(2));
            assertEquals(View.VISIBLE, lateNotification.module.getVisibility());
            assertEquals(View.GONE, common.view().getVisibility());

            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(lateNotification, common), list,
                    Set.of(lateNotification.view(), knownNotification), true, SidebarSettings.parse("module")));

            assertSame(lateNotification.view(), list.getChildAt(0));
            assertSame(knownNotification, list.getChildAt(1));
            assertSame(common.view(), list.getChildAt(2));
            assertSame(tail, list.getChildAt(3));
            assertEquals(View.VISIBLE, lateNativeNotification.getVisibility());
            assertEquals(View.GONE, lateNotification.module.getVisibility());
            assertSame(lateNoticeContent, lateNativeNotification.getChildAt(0));
            assertEquals(View.VISIBLE, knownNotification.getVisibility());
            assertEquals(View.VISIBLE, common.view().getVisibility());
            assertEquals(View.VISIBLE, common.module.getVisibility());
            assertEquals(View.GONE, nativeCommon.getVisibility());
        } finally {
            lateNotification.rollbackAttached();
            common.rollbackAttached();
            activity.finish();
        }
    }

    @Test public void reconcilingOneOwnerDoesNotChangeAPreviouslyPositionedForeignBinding() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout main = new LinearLayout(activity);
        FrameLayout mainNotification = new FrameLayout(activity);
        FrameLayout mainNative = new FrameLayout(activity);
        main.addView(mainNotification);
        main.addView(mainNative);
        activity.setContentView(main);
        HomeSidebarModuleHook.Binding mainBinding = HomeSidebarModuleHook.Binding.attach(mainNative, false, (id, owner) -> { });
        LinearLayout foreign = new LinearLayout(activity);
        FrameLayout foreignNative = new FrameLayout(activity);
        LinearLayout.LayoutParams foreignParams = new LinearLayout.LayoutParams(280, -2);
        foreignParams.leftMargin = 9;
        FrameLayout foreignNotification = new FrameLayout(activity);
        foreign.addView(foreignNative, foreignParams);
        foreign.addView(foreignNotification);
        HomeSidebarModuleHook.Binding foreignBinding = HomeSidebarModuleHook.Binding.attach(foreignNative, false, (id, owner) -> { });
        try {
            HomeSidebarModuleHook.reconcile(List.of(foreignBinding), foreign, Set.of(foreignNotification),
                    true, SidebarSettings.parse("ai_chat"));
            View foreignRow = foreignBinding.view().findViewWithTag("douai.sidebar.ai_chat");
            assertNotNull(foreignRow);
            assertSame(foreignNotification, foreign.getChildAt(0));
            assertSame(foreignBinding.view(), foreign.getChildAt(1));

            assertEquals(1, HomeSidebarModuleHook.reconcile(List.of(foreignBinding, mainBinding), main,
                    Set.of(mainNotification), true, SidebarSettings.parse("comment_history")));

            assertSame(foreignNotification, foreign.getChildAt(0));
            assertSame(foreignBinding.view(), foreign.getChildAt(1));
            assertSame(foreignParams, foreignBinding.view().getLayoutParams());
            assertSame(foreignRow, foreignBinding.view().findViewWithTag("douai.sidebar.ai_chat"));
            assertNull(foreignBinding.view().findViewWithTag("douai.sidebar.comment_history"));
            assertEquals(View.VISIBLE, foreignBinding.view().getVisibility());
            assertEquals(View.VISIBLE, foreignBinding.module.getVisibility());
            assertEquals(View.GONE, foreignNative.getVisibility());
            assertNotNull(mainBinding.view().findViewWithTag("douai.sidebar.comment_history"));
        } finally {
            mainBinding.rollbackAttached();
            foreignBinding.rollbackAttached();
            activity.finish();
        }
    }
}
