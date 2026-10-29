package com.douai.module.scan;

import org.junit.Test;

import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** The new host profile must preserve the previously shipped host rules as a separate contract. */
public final class HostRuleAssetProfilesTest {
    @Test public void supportedProfilesHaveSeparateAssetsWithTheCompleteFeatureRuleCoverage() throws Exception {
        assertNotEquals(HostAdaptation.PROFILE_406.rulesAsset, HostAdaptation.PROFILE_408.rulesAsset);
        HostRuleSet older = rules(HostAdaptation.PROFILE_406);
        HostRuleSet newer = rules(HostAdaptation.PROFILE_408);
        assertEquals("ecda2db82b867d00f65adf5ac588a45217bc3e9313dbc3713b8ae38a52fcb9f0",
                older.getFingerprint());
        assertEquals(HostAdaptation.PROFILE_406.fingerprint, older.getFingerprint());
        assertEquals(HostAdaptation.PROFILE_408.fingerprint, newer.getFingerprint());
        assertNotEquals(older.getFingerprint(), newer.getFingerprint());
        assertEquals(242, older.getRules().size());
        assertEquals(242, newer.getRules().size());
        Set<String> olderIds = ids(older), newerIds = ids(newer);
        assertEquals(olderIds, newerIds);
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            for (String id : feature.rules) {
                assertTrue("40.6.0 " + feature.id + " references missing rule " + id, olderIds.contains(id));
                assertTrue("40.8.0 " + feature.id + " references missing rule " + id, newerIds.contains(id));
            }
        }
    }

    @Test public void bothProfilesReadPlaybackSpeedThroughTheNamedCommand() throws Exception {
        String command = "Lcom/ss/android/ugc/aweme/player/sdk/api/PlayerCommand;";
        String speedCommand = "Lcom/ss/android/ugc/aweme/player/sdk/api/PlayerCommand$Getter$VideoPlaySpeed;";
        String realSpeedCommand = "Lcom/ss/android/ugc/aweme/player/sdk/api/PlayerCommand$Getter$RealVideoPlaySpeed;";
        for (HostAdaptation.Profile profile : new HostAdaptation.Profile[]{
                HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408}) {
            HostRuleSet rules = rules(profile);
            HostRule singleton = rule(rules, "adv_player_speed_command");
            assertEquals(profile.versionName, HostRule.Kind.CLASS, singleton.getKind());
            assertEquals(profile.versionName, speedCommand, singleton.getClassDescriptor());
            assertTrue(profile.versionName, singleton.getFieldTypes().contains(speedCommand));
            assertTrue(profile.versionName, singleton.getFieldNames().contains("f"));
            HostRule realSingleton = rule(rules, "adv_player_real_speed_command");
            assertEquals(profile.versionName, HostRule.Kind.CLASS, realSingleton.getKind());
            assertEquals(profile.versionName, realSpeedCommand, realSingleton.getClassDescriptor());
            assertTrue(profile.versionName, realSingleton.getFieldTypes().contains(realSpeedCommand));
            assertTrue(profile.versionName, realSingleton.getFieldNames().contains("f"));
            for (String id : new String[]{"adv_player_get_speed", "adv_player_manager_get_speed",
                    "adv_gesture_player_get_speed"}) {
                HostRule query = rule(rules, id);
                assertEquals(profile.versionName + ":" + id, "LJI", query.getMethodName());
                assertEquals(profile.versionName + ":" + id,
                        "(" + command + ")Ljava/lang/Object;", query.getMethodDescriptor());
            }
        }
    }

    @Test public void bothProfilesBindNativeGestureInputsToTheirActualContainerDetectors() throws Exception {
        String container = "Lcom/ss/android/ugc/aweme/o/oplayer/client/gesture/detector/DVideoGestureContainer;";
        for (HostAdaptation.Profile profile : new HostAdaptation.Profile[]{
                HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408}) {
            String detector = profile == HostAdaptation.PROFILE_406 ? "LX/1Z4d;" : "LX/1eom;";
            String frameContainer = profile == HostAdaptation.PROFILE_406 ? "LX/06ns;" : "LX/07rz;";
            HostRuleSet rules = rules(profile);
            HostRule input = rule(rules, "adv_gesture_native_touch_detector");
            assertEquals(profile.versionName, detector, input.getClassDescriptor());
            assertEquals("LIZ", input.getMethodName());
            assertEquals("(Landroid/view/MotionEvent;)V", input.getMethodDescriptor());
            for (String id : new String[]{"adv_gesture_video_container_detector", "adv_gesture_legacy_container_detector"}) {
                HostRule getter = rule(rules, id);
                String owner = id.equals("adv_gesture_video_container_detector") ? container : frameContainer;
                assertEquals(profile.versionName + ":" + id, owner, getter.getClassDescriptor());
                assertEquals("getDetector", getter.getMethodName());
                assertEquals("()" + detector, getter.getMethodDescriptor());
                assertTrue(getter.getFieldOwners().contains(owner));
                assertTrue(getter.getFieldTypes().contains(detector));
                assertTrue(getter.getFieldNames().contains(owner.equals(container) ? "a" : "detector"));
            }
        }
    }

    @Test public void bothProfilesDeclareTheSameSdkSpeedBoundariesIncludingTheCachedOverride() throws Exception {
        String params = "Lcom/ss/ttm/player/PlaybackParams;";
        String[][] targets = {
                {"adv_player_engine_set_params", "Lcom/ss/ttvideoengine/TTVideoEngine;", "setPlaybackParams", "(" + params + ")V"},
                {"adv_player_cached_engine_set_params", "Lcom/ss/android/ugc/aweme/player/sdk/impl/MTTVideoEngine;", "setPlaybackParams", "(" + params + ")V"},
                {"adv_player_media_set_params", "Lcom/ss/ttm/player/TTMediaPlayer;", "setPlaybackParams", "(" + params + ")V"},
                {"adv_player_native_float_option", "Lcom/ss/ttm/player/TTPlayer;", "setFloatOption", "(IF)I"},
                {"adv_player_sdk_base_params_caller", "Lcom/ss/android/ugc/aweme/player/sdk/impl/util/MBaseTTVideoEngine;", "setPlaybackParams", "(" + params + ")V"},
                {"adv_player_sdk_speed_params_caller", "Lcom/ss/ttm/player/TTPlayerClient;", "LJJJJLL", "(" + params + ")V"},
                {"adv_player_sdk_float_option_caller", "Lcom/ss/ttm/player/TTPlayerClient;", "LJJIJIIJI", "(IF)I"}
        };
        for (HostAdaptation.Profile profile : new HostAdaptation.Profile[]{
                HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408}) {
            HostRuleSet rules = rules(profile);
            for (String[] target : targets) {
                HostRule boundary = rule(rules, target[0]);
                assertEquals(profile.versionName + ":" + target[0], HostRule.Kind.METHOD, boundary.getKind());
                assertEquals(target[1], boundary.getClassDescriptor());
                assertEquals(target[2], boundary.getMethodName());
                assertEquals(target[3], boundary.getMethodDescriptor());
            }
            HostRule mediaCaller = rule(rules, "adv_player_sdk_media_params_caller");
            String owner = profile == HostAdaptation.PROFILE_406 ? "LX/0Cz1;" : "LX/0FfW;";
            assertEquals(owner, mediaCaller.getClassDescriptor());
            assertEquals("setPlaybackParams", mediaCaller.getMethodName());
            assertEquals("(" + params + ")V", mediaCaller.getMethodDescriptor());
            assertTrue(mediaCaller.getInvokeOwners().contains("Lcom/ss/ttm/player/MediaPlayer;"));
            assertTrue(mediaCaller.getFieldOwners().contains(owner));
            assertTrue(mediaCaller.getFieldTypes().contains("Lcom/ss/ttm/player/MediaPlayer;"));
            assertTrue(mediaCaller.getFieldNames().contains("f"));
        }
    }

    private static HostRuleSet rules(HostAdaptation.Profile profile) throws Exception {
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.assetFile(profile.rulesAsset))) {
            return HostRuleSet.parse(input);
        }
    }

    private static Set<String> ids(HostRuleSet rules) {
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules.getRules()) ids.add(rule.getId());
        return ids;
    }

    private static HostRule rule(HostRuleSet rules, String id) {
        for (HostRule rule : rules.getRules()) if (id.equals(rule.getId())) return rule;
        fail("Missing playback rule " + id);
        return null;
    }
}
