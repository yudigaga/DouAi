package com.douai.module.feature;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.view.View;

import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

/** Resource entry names and IDs are bound to exactly one supported host table. */
final class HostResourceCatalog {
    static final String VERIFIED_PACKAGE = HostAdaptation.TARGET_PACKAGE;
    static final String VERIFIED_VERSION_NAME = HostAdaptation.VERIFIED_VERSION_NAME;
    private static final int[] PROFILE_SENTINELS = {0x7f0a463c, 0x7f0a1f69, 0x7f0a2596};
    private static final int CHAPTER_MOUNT = 0x7f0a3fd6;

    private HostResourceCatalog() { }

    static HostAdaptation.Profile profile(Resources resources) {
        if (resources == null) return null;
        HostAdaptation.Profile matched = null;
        for (HostAdaptation.Profile candidate : new HostAdaptation.Profile[]{
                HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408, HostAdaptation.PROFILE_410}) {
            if (!identifies(resources, candidate)) continue;
            if (matched != null) return null;
            matched = candidate;
        }
        return matched;
    }

    private static boolean identifies(Resources resources, HostAdaptation.Profile profile) {
        for (int canonical : PROFILE_SENTINELS) {
            int actual = HostResourceProfile.id(profile, canonical);
            try {
                if (!VERIFIED_PACKAGE.equals(resources.getResourcePackageName(actual))
                        || !expectedName(profile, canonical).equals(resources.getResourceEntryName(actual))) return false;
            } catch (Resources.NotFoundException ignored) { return false; }
        }
        return true;
    }

    static int runtimeId(int canonicalId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        return profile == null || expectedName(profile, canonicalId) == null
                ? View.NO_ID : HostResourceProfile.id(profile, canonicalId);
    }

    static int canonicalId(int actualId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        if (profile == null) return View.NO_ID;
        int canonical = HostResourceProfile.canonicalId(profile, actualId);
        if (HostResourceProfile.id(profile, canonical) != actualId) return View.NO_ID;
        return matches(canonical, resources, profile) ? canonical : View.NO_ID;
    }

    static boolean matches(int canonicalId, Resources resources) {
        HostAdaptation.Profile profile = profile(resources);
        return profile != null && matches(canonicalId, resources, profile);
    }

    private static boolean matches(int canonicalId, Resources resources, HostAdaptation.Profile profile) {
        String expected = expectedName(profile, canonicalId);
        if (expected == null) return false;
        int actual = HostResourceProfile.id(profile, canonicalId);
        try {
            return VERIFIED_PACKAGE.equals(resources.getResourcePackageName(actual))
                    && expected.equals(resources.getResourceEntryName(actual));
        } catch (Resources.NotFoundException ignored) { return false; }
    }

    static boolean matches(int id, String resourceName) {
        return resourceName != null && resourceName.equals(expectedName(id));
    }

    static boolean matches(int id, String packageName, String resourceName) {
        return VERIFIED_PACKAGE.equals(packageName) && matches(id, resourceName);
    }

    static String expectedName(int canonicalId) { return expectedName(HostAdaptation.PROFILE_408, canonicalId); }

    private static String expectedName(HostAdaptation.Profile profile, int canonicalId) {
        if (canonicalId == CHAPTER_MOUNT) {
            if (HostResourceProfile.legacy(profile)) return "i=5";
            return profile == HostAdaptation.PROFILE_410 ? "i=e" : "i_f";
        }
        if (!HostAdaptation.PROFILE_408.getFeedResources().containsKey(canonicalId)) return null;
        return profile.getFeedResources().get(HostResourceProfile.id(profile, canonicalId));
    }

    @SuppressLint("ResourceType")
    static View findVerifiedView(View root, int canonicalId) {
        if (root == null) return null;
        int actual = runtimeId(canonicalId, root.getResources());
        if (actual == View.NO_ID) return null;
        View view = root.findViewById(actual);
        return view != null && canonicalId(view.getId(), view.getResources()) == canonicalId ? view : null;
    }
}
