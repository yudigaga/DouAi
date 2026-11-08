package com.douai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostApkTestProfile;
import com.douai.module.scan.HostResourceProfile;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostResourceViewTest {
    @Test public void acceptsOnlyTheVerifiedRuntimeResourceAndRejectsLaterReuse() {
        Context base = RuntimeEnvironment.getApplication();
        FixtureResources resources = new FixtureResources(base.getResources());
        Context context = new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
        };
        FrameLayout root = new FrameLayout(context);
        View target = new View(context);
        target.setId(2131379772);
        root.addView(target);
        assertSame(target, HostResourceCatalog.findVerifiedView(root, 2131379772));
        resources.entries.put(2131379772, "reused_for_another_action");
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
        resources.entries.put(2131379772, "j_x");
        resources.packageName = "com.douai.module";
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
    }

    @Test public void unknownAndMissingIdentifiersCannotSelectAView() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(context);
        View unknown = new View(context);
        unknown.setId(0x7f0a9734);
        root.addView(unknown);
        assertNull(HostResourceCatalog.findVerifiedView(root, unknown.getId()));
        assertNull(HostResourceCatalog.findVerifiedView(root, 2131379772));
        assertNull(HostResourceCatalog.findVerifiedView(null, 2131379772));
    }

    @Test public void resolvesLegacyActionsFromTheirOwnTableWithoutAcceptingReusedIdentifiers() {
        Context base = RuntimeEnvironment.getApplication();
        FixtureResources resources = new FixtureResources(base.getResources(), HostAdaptation.PROFILE_406);
        Context context = new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
        };
        FrameLayout root = new FrameLayout(context);
        View follow = new View(context);
        follow.setId(0x7f0a462d);
        root.addView(follow);
        assertSame(HostAdaptation.PROFILE_406, HostResourceCatalog.profile(resources));
        assertSame(follow, HostResourceCatalog.findVerifiedView(root, 0x7f0a463c));
        assertEquals(0x7f0a463c, HostResourceCatalog.canonicalId(follow.getId(), resources));
        // 40.6 esd's actual ID is also 40.8 esa's canonical ID. The table decides its role.
        assertEquals(0x7f0a2398, HostResourceCatalog.canonicalId(0x7f0a2396, resources));
        assertEquals(0x7f0a2394, HostResourceCatalog.runtimeId(0x7f0a2396, resources));
        follow.setId(0x7f0a463c);
        assertNull(HostResourceCatalog.findVerifiedView(root, 0x7f0a463c));
    }

    @Test public void ambiguousOrIncompleteResourceTablesCannotChooseAProfile() {
        Resources base = RuntimeEnvironment.getApplication().getResources();
        FixtureResources mixed = new FixtureResources(base);
        mixed.entries.putAll(HostAdaptation.PROFILE_406.getFeedResources());
        assertNull(HostResourceCatalog.profile(mixed));
        FixtureResources partial = new FixtureResources(base);
        partial.entries.remove(0x7f0a1f69);
        assertNull(HostResourceCatalog.profile(partial));
    }

    @Test public void recognizesThe410SentinelIdsAndRejectsAResourceTableMixedWith408() {
        Resources base = RuntimeEnvironment.getApplication().getResources();
        HostAdaptation.Profile latest = HostApkTestProfile.profiles()[2];
        FixtureResources resources = new FixtureResources(base, latest);
        assertSame(latest, HostResourceCatalog.profile(resources));
        assertEquals("j_e", resources.entries.get(0x7f0a4629));
        assertEquals("d4s", resources.entries.get(0x7f0a1f3c));
        assertEquals("comment_layout", resources.entries.get(0x7f0a2572));
        assertEquals(0x7f0a4629, HostResourceCatalog.runtimeId(0x7f0a463c, resources));
        assertEquals(0x7f0a463c, HostResourceCatalog.canonicalId(0x7f0a4629, resources));
        resources.entries.putAll(HostAdaptation.PROFILE_408.getFeedResources());
        assertNull(HostResourceCatalog.profile(resources));
    }

    private static final class FixtureResources extends Resources {
        String packageName = HostResourceCatalog.VERIFIED_PACKAGE;
        final Map<Integer, String> entries;
        FixtureResources(Resources base) { this(base, HostAdaptation.PROFILE_408); }
        FixtureResources(Resources base, HostAdaptation.Profile profile) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            entries = new HashMap<>(profile.getFeedResources());
        }
        @Override public String getResourcePackageName(int id) {
            if (!entries.containsKey(id)) throw new Resources.NotFoundException();
            return packageName;
        }
        @Override public String getResourceEntryName(int id) {
            String name = entries.get(id);
            if (name == null) throw new Resources.NotFoundException();
            return name;
        }
    }
}
