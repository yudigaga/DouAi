package com.douai.module.scan;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public final class HostAdaptationTest {
    @Test public void version410KeepsItsOwnProfileAndRejectsOtherVersionCodes() {
        HostAdaptation.Profile profile = HostAdaptation.forVersion(
                HostAdaptation.TARGET_PACKAGE, "41.0.0", 410001L);
        assertNotNull("41.0.0 must have a separate verified profile", profile);
        assertEquals("41.0.0", profile.versionName);
        assertEquals(410001L, profile.versionCode);
        assertNotEquals(HostAdaptation.PROFILE_406.rulesAsset, profile.rulesAsset);
        assertNotEquals(HostAdaptation.PROFILE_408.rulesAsset, profile.rulesAsset);
        assertFalse(profile.matchesRules(HostAdaptation.PROFILE_406.fingerprint));
        assertFalse(profile.matchesRules(HostAdaptation.PROFILE_408.fingerprint));
        assertNull(HostAdaptation.forVersion(HostAdaptation.TARGET_PACKAGE, "41.0.0", 400801L));
        assertNull(HostAdaptation.forVersion(HostAdaptation.TARGET_PACKAGE, "40.8.0", 410001L));
        assertNull(HostAdaptation.forVersion("other.application", "41.0.0", 410001L));
    }

    @Test public void versionEvidenceRequiresPackageNameAndActualVersionCode() {
        assertTrue(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801L));
        assertTrue(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.6.0", 400601L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.6.0", 400801L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400601L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.8.0", 40809985L));
        assertFalse(HostAdaptation.matchesVersion(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801L));
        assertFalse(HostAdaptation.matchesVersion("com.douai.module", "40.8.0", 400801L));
        assertFalse(HostAdaptation.matchesVersion(null, null, 0L));
    }

    @Test public void allProfilesKeepIndependentRulesAndImmutableResourceEvidence() throws Exception {
        Set<String> assets = new HashSet<>(), fingerprints = new HashSet<>();
        for (HostAdaptation.Profile profile : HostApkTestProfile.profiles()) {
            assertSame(profile, HostAdaptation.forVersion(profile.versionName, profile.versionCode));
            try (FileInputStream input = new FileInputStream(HostApkTestProfile.assetFile(profile.rulesAsset))) {
                assertEquals(profile.fingerprint, HostRuleSet.parse(input).getFingerprint());
            }
            assertEquals(51, profile.getFeedResources().size());
            assertThrows(UnsupportedOperationException.class, () -> profile.getFeedResources().clear());
            assertTrue("Each version needs an independent asset", assets.add(profile.rulesAsset));
            assertTrue("Each version needs an independent fingerprint", fingerprints.add(profile.fingerprint));
        }
        assertNotEquals(HostAdaptation.PROFILE_406.rulesAsset, HostAdaptation.PROFILE_408.rulesAsset);
        assertFalse(HostAdaptation.PROFILE_406.matchesRules(HostAdaptation.PROFILE_408.fingerprint));
        assertFalse(HostAdaptation.PROFILE_408.matchesRules(HostAdaptation.PROFILE_406.fingerprint));
        assertEquals("d5s", HostAdaptation.PROFILE_406.getFeedResources().get(2131369826));
        assertEquals("d5w", HostAdaptation.PROFILE_408.getFeedResources().get(2131369833));
    }

    @Test public void staticEvidenceBindsThePackagedRulesAndFeatureReferences() throws Exception {
        File file = new File("src/main/assets/" + HostAdaptation.RULES_ASSET);
        if (!file.isFile()) file = new File("app/src/main/assets/" + HostAdaptation.RULES_ASSET);
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(file)) { rules = HostRuleSet.parse(input); }
        assertEquals(HostAdaptation.VERIFIED_RULES_FINGERPRINT, rules.getFingerprint());
        assertFalse(HostAdaptation.matchesRules("changed"));
        assertFalse(HostAdaptation.matchesRules(null));
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules.getRules()) ids.add(rule.getId());
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            for (String rule : feature.rules) assertTrue(feature.id + " references " + rule, ids.contains(rule));
        }
        assertEquals(51, HostAdaptation.getFeedResources().size());
        assertEquals("d5w", HostAdaptation.getFeedResources().get(2131369833));
        assertEquals("jcb", HostAdaptation.getFeedResources().get(2131378332));
        assertEquals("jdf", HostAdaptation.getFeedResources().get(2131378377));
        assertEquals("jdh", HostAdaptation.getFeedResources().get(2131378379));
        assertEquals("comment_layout", HostAdaptation.getFeedResources().get(2131371414));
        assertEquals("j_x", HostAdaptation.getFeedResources().get(2131379772));
    }

    @Test public void speechCapabilitiesCoverTheirScanAndRuntimeContractTargets() {
        HostFeatureCatalog.Feature comment = feature("comment_tts");
        assertEquals("feature.comment_tts.installed", comment.installedKey);
        assertTrue(comment.rules.containsAll(java.util.Arrays.asList("comment_publish_model",
                "comment_attachment_model", "comment_audio_model", "comment_publish_model_copy",
                "comment_attachment_copy", "comment_publish_submit", "comment_publish_submit_checked")));

        HostFeatureCatalog.Feature message = feature("dm_speech");
        assertEquals("feature.dm_speech.installed", message.installedKey);
        assertTrue(message.rules.containsAll(java.util.Arrays.asList("dm_speech_send", "dm_speech_input_api",
                "dm_speech_content", "dm_speech_edit_text", "dm_speech_send_click", "dm_speech_send_confirm",
                "dm_speech_conversation", "dm_speech_audio_create", "dm_speech_audio_destroy",
                "dm_speech_record_event", "dm_speech_record_model", "dm_speech_record_state",
                "dm_speech_upload_enqueue", "dm_speech_message_local_ext", "dm_speech_message_conversation")));
    }

    private static HostFeatureCatalog.Feature feature(String id) {
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) {
            if (id.equals(feature.id)) return feature;
        }
        throw new AssertionError("Missing capability diagnostic: " + id);
    }
}
