package com.douai.module.config;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class CustomViewLocatorTest {
    @Test
    public void storedAlphaRoundTripsWithoutChangingTheTargetIdentity() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        for (int alpha : new int[]{-2, -1, 0, 128, 255}) {
            JSONObject encoded = new JSONObject(encode(locator));
            encoded.getJSONArray("items").getJSONObject(0).put("alpha", alpha);
            List<CustomViewLocator> parsed = CustomViewLocator.parse(encoded.toString());
            assertEquals(1, parsed.size());
            assertEquals(locator.identityKey(), parsed.get(0).identityKey());
            assertSame(target, parsed.get(0).resolve(root));
            JSONObject saved = new JSONObject(CustomViewLocator.serialize(parsed));
            assertEquals(alpha, saved.getJSONArray("items").getJSONObject(0).optInt("alpha", -999));
        }
        activity.finish();
    }

    @Test
    public void legacyLocatorKeepsItsGoneDefaultWhenSavedAgain() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        JSONObject legacy = new JSONObject(encode(CustomViewLocator.capture(root, target)));
        legacy.getJSONArray("items").getJSONObject(0).remove("alpha");
        JSONObject saved = new JSONObject(CustomViewLocator.serialize(CustomViewLocator.parse(legacy.toString())));
        assertEquals(2, saved.getInt("version"));
        assertEquals(-2, saved.getJSONArray("items").getJSONObject(0).optInt("alpha", -999));
        activity.finish();
    }

    @Test
    public void malformedAlphaFailsClosedInsteadOfSilentlyHidingTheTarget() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        String encoded = encode(CustomViewLocator.capture(root, target));
        for (Object alpha : new Object[]{-3, 256, 0.5, "128", JSONObject.NULL}) {
            JSONObject invalid = new JSONObject(encoded);
            invalid.getJSONArray("items").getJSONObject(0).put("alpha", alpha);
            assertTrue("Invalid alpha " + alpha, CustomViewLocator.parse(invalid.toString()).isEmpty());
        }
        activity.finish();
    }

    @Test
    public void uniqueIdSurvivesHierarchyMovementAndJsonRoundTrip() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        LinearLayout parent = new LinearLayout(activity);
        TextView target = namedText(activity, android.R.id.button1);
        target.setText("dynamic selected text");
        target.setContentDescription("dynamic content description");
        parent.addView(target);
        root.addView(parent);
        activity.setContentView(root);

        View decor = activity.getWindow().getDecorView();
        CustomViewLocator captured = CustomViewLocator.capture(decor, target);
        assertNotNull(captured);
        String serialized = encode(captured);
        List<CustomViewLocator> restored = CustomViewLocator.parse(serialized);
        assertEquals(1, restored.size());
        assertFalse(serialized.contains("dynamic selected text"));
        assertFalse(serialized.contains("dynamic content description"));
        assertEquals("当前窗口", captured.scopeDescription());
        assertEquals("唯一资源 ID", captured.matchingDescription());
        String description = captured.pathDescription();
        assertEquals("DecorView → … → TextView #android:id/button1", description);
        assertEquals(description, restored.get(0).pathDescription());
        assertEquals(serialized, encode(captured));

        parent.removeView(target);
        FrameLayout replacementParent = new FrameLayout(activity);
        replacementParent.addView(new Button(activity));
        replacementParent.addView(target);
        root.addView(replacementParent, 0);
        assertSame(target, restored.get(0).resolve(decor));
        assertEquals(captured.identityKey(), restored.get(0).identityKey());
        assertEquals(captured.identityKey(), CustomViewLocator.capture(decor, target).identityKey());
        assertEquals(description, CustomViewLocator.capture(decor, target).pathDescription());
        activity.finish();
    }

    @Test
    public void uniqueIdRejectsLaterAmbiguityAndDifferentViewClass() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = namedText(activity, android.R.id.button1);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        assertNotNull(locator);
        root.addView(namedText(activity, android.R.id.button1));
        assertNull(locator.resolve(root));

        root.removeAllViews();
        Button wrongClass = new Button(activity);
        wrongClass.setId(android.R.id.button1);
        root.addView(wrongClass);
        assertNull(locator.resolve(root));
        activity.finish();
    }

    @Test
    public void finderOverlayCannotBeCapturedOrBecomeTheResolvedTarget() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = namedText(activity, android.R.id.button1);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        assertNotNull(locator);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag("douai.view_finder");
        TextView pickerChild = namedText(activity, android.R.id.button1);
        overlay.addView(pickerChild);
        root.removeView(target);
        root.addView(overlay);
        assertNull(locator.resolve(root));
        assertNull(CustomViewLocator.capture(root, pickerChild));
        assertNull(CustomViewLocator.capture(root, overlay, pickerChild));
        activity.finish();
    }

    @Test
    public void feedScopeFollowsCurrentCardWhilePreviousCardRemainsInPager() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window = new FrameLayout(activity);
        LinearLayout previousCard = new LinearLayout(activity);
        TextView previousTarget = namedText(activity, android.R.id.button1);
        previousCard.addView(previousTarget);
        window.addView(previousCard);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(window, previousCard, previousTarget));
        assertEquals(CustomViewLocator.Scope.FEED, locator.scope);
        assertEquals("当前信息流卡片", locator.scopeDescription());

        LinearLayout currentCard = new LinearLayout(activity);
        currentCard.addView(new Button(activity));
        TextView currentTarget = namedText(activity, android.R.id.button1);
        currentCard.addView(currentTarget);
        window.addView(currentCard);
        assertSame(currentTarget, locator.resolve(window, currentCard));
        assertNull(locator.resolve(window));

        FrameLayout rebuiltWindow = new FrameLayout(activity);
        LinearLayout rebuiltCard = new LinearLayout(activity);
        FrameLayout movedParent = new FrameLayout(activity);
        TextView rebuiltTarget = namedText(activity, android.R.id.button1);
        movedParent.addView(rebuiltTarget);
        rebuiltCard.addView(movedParent);
        rebuiltWindow.addView(rebuiltCard);
        assertSame(rebuiltTarget, locator.resolve(rebuiltWindow, rebuiltCard));
        assertNull(locator.resolve(window, rebuiltCard));
        activity.finish();
    }

    @Test
    public void windowScopeOutsideFeedSurvivesHostRebuild() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window = new FrameLayout(activity);
        LinearLayout feed = new LinearLayout(activity);
        window.addView(feed);
        TextView navigation = namedText(activity, android.R.id.button2);
        window.addView(navigation);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(window, feed, navigation));
        assertEquals(CustomViewLocator.Scope.WINDOW, locator.scope);

        FrameLayout rebuiltWindow = new FrameLayout(activity);
        LinearLayout rebuiltFeed = new LinearLayout(activity);
        rebuiltWindow.addView(rebuiltFeed);
        FrameLayout navParent = new FrameLayout(activity);
        TextView rebuiltNavigation = namedText(activity, android.R.id.button2);
        navParent.addView(rebuiltNavigation);
        rebuiltWindow.addView(navParent);
        assertSame(rebuiltNavigation, locator.resolve(rebuiltWindow, rebuiltFeed));
        assertSame(rebuiltNavigation, locator.resolve(rebuiltWindow));
        activity.finish();
    }

    @Test
    public void duplicateIdUsesNearestNamedUniqueAncestor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window = new FrameLayout(activity);
        LinearLayout otherBranch = new LinearLayout(activity);
        otherBranch.setId(android.R.id.content);
        otherBranch.addView(namedText(activity, android.R.id.title));
        LinearLayout selectedBranch = new LinearLayout(activity);
        selectedBranch.setId(android.R.id.list);
        TextView target = namedText(activity, android.R.id.title);
        selectedBranch.addView(target);
        window.addView(otherBranch);
        window.addView(selectedBranch);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(window, target));
        assertEquals("命名祖先 + 结构路径", locator.matchingDescription());
        assertEquals("FrameLayout → … → LinearLayout #android:id/list → TextView #android:id/title",
                locator.pathDescription());

        selectedBranch.addView(new Button(activity), 0);
        window.removeView(selectedBranch);
        FrameLayout movedContainer = new FrameLayout(activity);
        movedContainer.addView(selectedBranch);
        window.addView(movedContainer, 0);
        assertSame(target, locator.resolve(window));
        assertEquals(locator.identityKey(), CustomViewLocator.capture(window, target).identityKey());

        LinearLayout conflictingAncestor = new LinearLayout(activity);
        conflictingAncestor.setId(android.R.id.list);
        window.addView(conflictingAncestor);
        assertNull(locator.resolve(window));
        activity.finish();
    }

    @Test
    public void duplicateNamedSiblingsRejectStaleIndexPointingToAnotherSibling() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        TextView first = namedText(activity, android.R.id.title);
        TextView target = namedText(activity, android.R.id.title);
        root.addView(first);
        root.addView(target);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(root, target));
        assertEquals("严格结构路径", locator.matchingDescription());
        assertEquals("LinearLayout → TextView #android:id/title [子节点 2，同类同 ID 2/2]",
                locator.pathDescription());
        assertSame(target, locator.resolve(root));
        root.addView(new Button(activity), 0);
        assertSame(first, root.getChildAt(1));
        assertNull(locator.resolve(root));
        activity.finish();
    }

    @Test
    public void anonymousPathRejectsChangedIndexOrSameSignatureCount() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(root, target));
        assertSame(target, locator.resolve(root));
        root.addView(new Button(activity), 0);
        assertNull(locator.resolve(root));
        root.removeViewAt(0);
        root.addView(new TextView(activity));
        assertSame(target, root.getChildAt(0));
        assertNull(locator.resolve(root));
        activity.finish();
    }

    @Test
    public void anonymousPathSurvivesIdenticalHostRebuildAndDynamicTextChanges() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout firstRoot = new FrameLayout(activity);
        LinearLayout firstParent = new LinearLayout(activity);
        TextView firstTarget = new TextView(activity);
        firstTarget.setText("first dynamic value");
        firstParent.addView(firstTarget);
        firstRoot.addView(firstParent);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(firstRoot, firstTarget));
        assertEquals("严格结构路径", locator.matchingDescription());
        assertEquals("FrameLayout → LinearLayout [子节点 1，同类同 ID 1/1]"
                + " → TextView [子节点 1，同类同 ID 1/1]", locator.pathDescription());
        FrameLayout rebuiltRoot = new FrameLayout(activity);
        LinearLayout rebuiltParent = new LinearLayout(activity);
        TextView rebuiltTarget = new TextView(activity);
        rebuiltTarget.setText("different dynamic value");
        rebuiltParent.addView(rebuiltTarget);
        rebuiltRoot.addView(rebuiltParent);
        assertSame(rebuiltTarget, locator.resolve(rebuiltRoot));
        activity.finish();
    }

    @Test
    public void anonymousPathRejectsScanRootsInsideFinderOverlay() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        TextView target = new TextView(activity);
        feed.addView(target);
        window.addView(feed);
        CustomViewLocator locator = roundTrip(CustomViewLocator.capture(window, feed, target));
        assertSame(target, locator.resolve(window, feed));

        feed.setTag("douai.view_finder");
        assertNull(locator.resolve(window, feed));
        feed.setTag(null);
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag("douai.view_finder.selection_panel");
        window.removeView(feed);
        overlay.addView(feed);
        window.addView(overlay);
        assertNull(locator.resolve(window, feed));

        CustomViewLocator windowLocator = roundTrip(CustomViewLocator.capture(feed, target));
        feed.setTag("douai.view_finder");
        assertNull(windowLocator.resolve(feed));
        activity.finish();
    }

    @Test
    public void malformedMetadataLeafAndVersionFailClosed() throws Exception {
        assertTrue(CustomViewLocator.parse("not-json").isEmpty());
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = namedText(activity, android.R.id.button1);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        String encoded = encode(locator);
        assertTrue(CustomViewLocator.parse(encoded + " trailing-data").isEmpty());

        JSONObject wrongVersion = new JSONObject(encoded);
        wrongVersion.put("version", 1);
        assertTrue(CustomViewLocator.parse(wrongVersion.toString()).isEmpty());
        wrongVersion.put("version", "2");
        assertTrue(CustomViewLocator.parse(wrongVersion.toString()).isEmpty());
        wrongVersion.put("version", 2.5);
        assertTrue(CustomViewLocator.parse(wrongVersion.toString()).isEmpty());

        for (String field : new String[]{"scope", "root", "class", "id", "label", "mode", "anchor", "path"}) {
            JSONObject missing = new JSONObject(encoded);
            missing.getJSONArray("items").getJSONObject(0).remove(field);
            assertTrue("Missing " + field, CustomViewLocator.parse(missing.toString()).isEmpty());
        }
        JSONObject unknownScope = new JSONObject(encoded);
        unknownScope.getJSONArray("items").getJSONObject(0).put("scope", "PAGER");
        assertTrue(CustomViewLocator.parse(unknownScope.toString()).isEmpty());
        JSONObject inconsistentLeaf = new JSONObject(encoded);
        inconsistentLeaf.getJSONArray("items").getJSONObject(0).getJSONArray("path")
                .getJSONObject(0).put("id", "different:id/value");
        assertTrue(CustomViewLocator.parse(inconsistentLeaf.toString()).isEmpty());
        JSONObject inconsistentClass = new JSONObject(encoded);
        inconsistentClass.getJSONArray("items").getJSONObject(0).getJSONArray("path")
                .getJSONObject(0).put("class", Button.class.getName());
        assertTrue(CustomViewLocator.parse(inconsistentClass.toString()).isEmpty());
        JSONObject dynamicLabel = new JSONObject(encoded);
        dynamicLabel.getJSONArray("items").getJSONObject(0).put("label", "a video caption");
        assertTrue(CustomViewLocator.parse(dynamicLabel.toString()).isEmpty());
        activity.finish();
    }

    @Test
    public void invalidPathNumbersAndAnchorsFailClosed() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        String encoded = encode(CustomViewLocator.capture(root, target));
        for (String key : new String[]{"index", "peer", "count"}) {
            JSONObject fractional = new JSONObject(encoded);
            fractional.getJSONArray("items").getJSONObject(0).getJSONArray("path")
                    .getJSONObject(0).put(key, 0.5);
            assertTrue(CustomViewLocator.parse(fractional.toString()).isEmpty());
            JSONObject coerced = new JSONObject(encoded);
            coerced.getJSONArray("items").getJSONObject(0).getJSONArray("path")
                    .getJSONObject(0).put(key, "1");
            assertTrue(CustomViewLocator.parse(coerced.toString()).isEmpty());
        }
        JSONObject invalidPeer = new JSONObject(encoded);
        invalidPeer.getJSONArray("items").getJSONObject(0).getJSONArray("path")
                .getJSONObject(0).put("peer", 1);
        assertTrue(CustomViewLocator.parse(invalidPeer.toString()).isEmpty());
        JSONObject invalidAnchor = new JSONObject(encoded);
        invalidAnchor.getJSONArray("items").getJSONObject(0).put("anchor", 0);
        assertTrue(CustomViewLocator.parse(invalidAnchor.toString()).isEmpty());
        activity.finish();
    }

    @Test
    public void itemAndDepthLimitsRejectInsteadOfTruncating() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        JSONObject encoded = new JSONObject(encode(locator));
        JSONObject item = encoded.getJSONArray("items").getJSONObject(0);
        JSONArray excessiveItems = new JSONArray();
        for (int index = 0; index < 129; index++) excessiveItems.put(item);
        encoded.put("items", excessiveItems);
        assertTrue(CustomViewLocator.parse(encoded.toString()).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> CustomViewLocator.serialize(Collections.nCopies(129, locator)));

        encoded = new JSONObject(encode(locator));
        item = encoded.getJSONArray("items").getJSONObject(0);
        JSONObject node = item.getJSONArray("path").getJSONObject(0);
        JSONArray excessiveDepth = new JSONArray();
        for (int index = 0; index < 65; index++) excessiveDepth.put(node);
        item.put("path", excessiveDepth);
        assertTrue(CustomViewLocator.parse(encoded.toString()).isEmpty());
        activity.finish();
    }

    @Test
    public void unrelatedRootsAndRootTargetsCannotBeCapturedOrResolved() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        root.addView(target);
        CustomViewLocator locator = CustomViewLocator.capture(root, target);
        assertNull(locator.resolve(new LinearLayout(activity)));
        assertNull(CustomViewLocator.capture(root, root));
        assertNull(CustomViewLocator.capture(new FrameLayout(activity), target));
        assertNull(CustomViewLocator.capture(root, root, root));
        activity.finish();
    }

    private static TextView namedText(Activity activity, int id) {
        TextView view = new TextView(activity);
        view.setId(id);
        return view;
    }

    private static String encode(CustomViewLocator locator) {
        assertNotNull(locator);
        return CustomViewLocator.serialize(Collections.singletonList(locator));
    }

    private static CustomViewLocator roundTrip(CustomViewLocator locator) {
        List<CustomViewLocator> restored = CustomViewLocator.parse(encode(locator));
        assertEquals(1, restored.size());
        return restored.get(0);
    }
}
