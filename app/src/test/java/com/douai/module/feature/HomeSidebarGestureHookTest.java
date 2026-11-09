package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SidebarSettings;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HomeSidebarGestureHookTest {
    private final LocalOptions enabled = new LocalOptions(Map.of(SidebarSettings.ENABLED, true));

    @Test public void onlyTheMainRecommendedFeedWithAnExistingSidebarIsEligible() {
        assertTrue(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, true, true, false, 0));
        for (String bottom : new String[]{"homepage_notification", "homepage_profile", "homepage_familiar", "homepage_mall", ""}) {
            assertFalse(HomeSidebarGestureHook.eligible(enabled, bottom, "homepage_hot", true, true, true, false, 0));
        }
        for (String top : new String[]{"homepage_mall", "homepage_follow", "homepage_search_home", ""}) {
            assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", top, true, true, true, false, 0));
        }
        assertFalse(HomeSidebarGestureHook.eligible(new LocalOptions(Map.of()), "homepage_home", "homepage_hot", true, true, true, false, 0));
        assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", false, true, true, false, 0));
        assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, false, true, false, 0));
        assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, true, false, false, 0));
    }

    @Test public void liveAndEarlierPhotoNavigationKeepTheirOriginalHorizontalHandling() {
        assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, true, true, true, 0));
        assertFalse(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, true, true, false, 1));
        assertTrue(HomeSidebarGestureHook.eligible(enabled, "homepage_home", "homepage_hot", true, true, true, false, 0));
    }

    @Test public void onlyTheRootRightwardChildScrollQueryCanYieldToTheNativeSidebar() {
        Object pager = new Object();
        assertTrue(HomeSidebarGestureHook.claimRootRightward(pager, pager, 1));
        assertFalse(HomeSidebarGestureHook.claimRootRightward(pager, pager, 0));
        assertFalse(HomeSidebarGestureHook.claimRootRightward(pager, pager, -1));
        assertFalse(HomeSidebarGestureHook.claimRootRightward(pager, new Object(), 1));
        assertFalse(HomeSidebarGestureHook.claimRootRightward(null, null, 1));
    }
}
