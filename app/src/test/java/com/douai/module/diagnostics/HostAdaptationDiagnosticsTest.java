package com.douai.module.diagnostics;

import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostFeatureCatalog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HostAdaptationDiagnosticsTest {
    @Test public void original410BuildIsRequiredBeforeDiagnosticsSelectsTheThirdProfile() throws Exception {
        for (long build : new long[]{41009900L, 41009985L, 40809900L, -1L}) {
            java.io.File apk = com.douai.module.scan.BinaryHostManifestFixture.apk(
                    HostAdaptation.TARGET_PACKAGE, "41.0.0", 410001, build);
            android.content.pm.ApplicationInfo application = new android.content.pm.ApplicationInfo();
            application.sourceDir = apk.getAbsolutePath();
            android.content.Context context = new android.content.ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
                @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return application; }
            };
            Bundle version = HostAdaptationDiagnostics.readInstalledVersion(context);
            assertEquals(build, version.getLong("host_update_version_code"));
            assertEquals(build == 41009900L, version.getBoolean("host_version_profile_matched"));
        }
    }

    @Test public void thirdProfileRejectsEitherOlderRulesFingerprint() {
        HostAdaptation.Profile profile = HostAdaptation.forVersion("41.0.0", 410001L);
        assertNotNull(profile);
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("41.0.0", 410001L), profile.fingerprint, report(""));
        assertTrue(snapshot.getBoolean("host_adaptation_static_verified"));
        assertEquals(profile.rulesAsset, snapshot.getString("host_adaptation_rules_asset"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("匹配 41.0.0"));
        for (HostAdaptation.Profile older : new HostAdaptation.Profile[]{
                HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408}) {
            HostAdaptationDiagnostics.append(snapshot, version("41.0.0", 410001L), older.fingerprint, report(""));
            assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
            HostAdaptationDiagnostics.append(snapshot, version(older.versionName, older.versionCode), profile.fingerprint, report(""));
            assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        }
    }

    @Test public void originalManifestBuildDistinguishes9900FromRetired9985InDiagnostics() throws Exception {
        for (long build : new long[]{40809900L, 40809985L}) {
            java.io.File apk = com.douai.module.scan.BinaryHostManifestFixture.apk(
                    HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, build);
            android.content.pm.ApplicationInfo application = new android.content.pm.ApplicationInfo();
            application.sourceDir = apk.getAbsolutePath();
            android.content.Context context = new android.content.ContextWrapper(org.robolectric.RuntimeEnvironment.getApplication()) {
                @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return application; }
            };
            Bundle version = HostAdaptationDiagnostics.readInstalledVersion(context);
            assertEquals(build, version.getLong("host_update_version_code"));
            assertEquals(build == 40809900L, version.getBoolean("host_version_profile_matched"));
            assertTrue(HostAdaptationDiagnostics.describe(version).contains("构建 " + build));
        }
    }
    @Test public void hiddenSkipSeparatesTheRequestedSwitchFromCompleteInstallationAndRuntimeCounts() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("hidden_skip_enabled", true);
        snapshot.putBoolean("hidden_skip_installed", false);
        String unavailable = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(unavailable.contains("开关开启，Hook 未完整安装"));
        snapshot.putBoolean("hidden_skip_installed", true);
        snapshot.putLong("hidden_skip_current_hits", 3);
        snapshot.putLong("hidden_skip_current_blocked", 2);
        snapshot.putLong("hidden_skip_background_hits", 4);
        snapshot.putLong("hidden_skip_background_blocked", 1);
        String installed = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(installed.contains("开关开启，四处 Hook 完整安装"));
        assertTrue(installed.contains("当前安全召回：命中 3，拦截 2"));
        assertTrue(installed.contains("后台自动移除：命中 4，拦截 1"));
        assertTrue(installed.contains("实际行为仍需真机确认"));
    }

    @Test public void hiddenSkipCountersAreBoundedAndLegacySnapshotsStayUnchanged() {
        Bundle snapshot = new Bundle();
        assertFalse(HostAdaptationDiagnostics.describe(snapshot).contains("视频跳过保护"));
        snapshot.putBoolean("hidden_skip_enabled", false);
        snapshot.putBoolean("hidden_skip_installed", true);
        snapshot.putLong("hidden_skip_current_hits", -4);
        snapshot.putLong("hidden_skip_background_blocked", Long.MAX_VALUE);
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("开关关闭，四处 Hook 完整安装"));
        assertTrue(detail.contains("当前安全召回：命中 0"));
        assertTrue(detail.contains("后台自动移除：命中 0，拦截 1000000000"));
    }

    @Test public void unknownVersionRemainsUnverifiedEvenWithUniqueRulesAndInstalledHooks() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("auto_follow_hook_installed", true);
        HostAdaptationDiagnostics.append(snapshot, version("40.7.0", 400701L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report(""));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertTrue(snapshot.getBoolean("capability.auto_follow.rules_unique"));
        assertTrue(snapshot.getBoolean("capability.auto_follow.installed"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("该宿主版本未验证"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("实际行为仍需真机确认"));
    }

    @Test public void versionMatchDoesNotVerifyAChangedRuleset() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L), "changed", report(""));
        assertTrue(snapshot.getBoolean("host_version_profile_matched"));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("规则配置未绑定已验证证据"));
    }

    @Test public void legacyVersionUsesItsOwnRulesAndRejectsTheNewVersionFingerprint() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("40.6.0", 400601L),
                HostAdaptation.PROFILE_406.fingerprint, report(""));
        assertTrue(snapshot.getBoolean("host_adaptation_static_verified"));
        assertEquals(HostAdaptation.PROFILE_406.rulesAsset,
                snapshot.getString("host_adaptation_rules_asset"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("匹配 40.6.0"));
        HostAdaptationDiagnostics.append(snapshot, version("40.6.0", 400601L),
                HostAdaptation.PROFILE_408.fingerprint, report(""));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.PROFILE_406.fingerprint, report(""));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
    }

    @Test public void diagnosesAmbiguousRuleAndSeparatesInstallationFromRuleMatch() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report("feed_holder_quick_root"));
        assertTrue(snapshot.getBoolean("host_adaptation_static_verified"));
        assertFalse(snapshot.getBoolean("capability.auto_follow.rules_unique"));
        assertEquals("feed_holder_quick_root=AMBIGUOUS",
                snapshot.getString("capability.auto_follow.rule_gaps"));
        assertTrue(snapshot.getBoolean("capability.auto_like.rules_unique"));
        assertFalse(snapshot.getBoolean("capability.auto_like.installed"));
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("自动关注规则缺口：feed_holder_quick_root=AMBIGUOUS"));
        assertTrue(detail.contains("自动点赞：规则唯一命中，Hook 未安装"));
    }

    @Test public void missingVersionAndReportHaveExplicitUnverifiedState() {
        Bundle snapshot = new Bundle();
        HostAdaptationDiagnostics.append(snapshot, HostAdaptationDiagnostics.versionEvidence(null), null, null);
        assertFalse(snapshot.getBoolean("host_version_available"));
        assertFalse(snapshot.getBoolean("host_adaptation_static_verified"));
        assertEquals("feed_holder_selected=MISSING, feed_holder_quick_root=MISSING",
                snapshot.getString("capability.auto_follow.rule_gaps"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("读取失败，未验证"));
    }

    @Test public void performanceEvidenceDistinguishesCachedWorkAndWholeHostHeap() {
        Bundle snapshot = new Bundle();
        assertFalse(HostAdaptationDiagnostics.describe(snapshot).contains("耗时："));
        snapshot.putBoolean("cache_hit", true);
        snapshot.putLong("host_scan_millis", 12);
        snapshot.putLong("host_java_heap_used_bytes", 32 * 1024 * 1024L);
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("缓存读取耗时：12 ms"));
        assertTrue(detail.contains("32 MiB"));
        assertTrue(detail.contains("不能单独归因于模块"));
        assertFalse(detail.contains("性能已验证"));
    }

    @Test public void newCapabilitiesExposeIndependentRuleAndInstallationEvidence() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("feature.local_analytics.installed", true);
        snapshot.putBoolean("feature.media_model.installed", true);
        snapshot.putString("feature.native_auto_reply.failure", "NoSuchMethodException: fixture.Sender.send()");
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report("adv_im_global_received"));
        assertFalse(snapshot.getBoolean("capability.native_auto_reply.rules_unique"));
        assertTrue(snapshot.getString("capability.native_auto_reply.rule_gaps").contains("adv_im_global_received=AMBIGUOUS"));
        assertEquals("NoSuchMethodException: fixture.Sender.send()",
                snapshot.getString("capability.native_auto_reply.installation_failure"));
        assertTrue(snapshot.getBoolean("capability.local_analytics.installed"));
        assertTrue(snapshot.getBoolean("capability.media_model.rules_unique"));
        assertTrue(HostAdaptationDiagnostics.describe(snapshot).contains("实际行为仍需真机确认"));
    }

    @Test public void speechRuleGapIdentifiesAffectedCapabilityWithoutHidingOtherCapabilities() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("feature.comment_tts.installed", true);
        snapshot.putBoolean("auto_like_hook_installed", true);
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report("dm_speech_record_state"));

        assertFalse(snapshot.getBoolean("capability.dm_speech.rules_unique"));
        assertEquals("dm_speech_record_state=AMBIGUOUS",
                snapshot.getString("capability.dm_speech.rule_gaps"));
        assertTrue(snapshot.getBoolean("capability.comment_tts.rules_unique"));
        assertTrue(snapshot.getBoolean("capability.comment_tts.installed"));
        assertTrue(snapshot.getBoolean("capability.auto_like.installed"));
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("私聊文本转语音规则缺口：dm_speech_record_state=AMBIGUOUS"));
        assertTrue(detail.contains("实际行为仍需真机确认"));
    }

    @Test public void speechRuntimeContractFailureIsVisibleDespiteUniqueDexRules() {
        Bundle snapshot = new Bundle();
        String fieldFailure = "NoSuchFieldException: fixture.RecordModel.LIZJ";
        String constructorFailure = "NoSuchMethodException: fixture.Audio(java.io.File,java.util.List,java.lang.Long)";
        snapshot.putString("feature.dm_speech.failure", fieldFailure);
        snapshot.putString("feature.comment_tts.failure", constructorFailure);
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report(""));

        assertTrue(snapshot.getBoolean("capability.dm_speech.rules_unique"));
        assertFalse(snapshot.getBoolean("capability.dm_speech.installed"));
        assertEquals(fieldFailure, snapshot.getString("capability.dm_speech.installation_failure"));
        assertEquals(constructorFailure, snapshot.getString("capability.comment_tts.installation_failure"));
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertTrue(detail.contains("私聊文本转语音：Hook 安装失败：" + fieldFailure));
        assertTrue(detail.contains("评论文本转语音：Hook 安装失败：" + constructorFailure));
    }

    @Test public void installedSpeechClearsStaleFailureWithoutClaimingDelivery() {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("feature.dm_speech.installed", true);
        snapshot.putBoolean("feature.comment_tts.installed", true);
        snapshot.putString("feature.dm_speech.failure", "obsolete field failure");
        HostAdaptationDiagnostics.append(snapshot, version("40.8.0", 400801L),
                HostAdaptation.VERIFIED_RULES_FINGERPRINT, report(""));

        assertTrue(snapshot.getBoolean("capability.dm_speech.installed"));
        assertTrue(snapshot.getBoolean("capability.comment_tts.installed"));
        assertEquals("", snapshot.getString("capability.dm_speech.installation_failure"));
        String detail = HostAdaptationDiagnostics.describe(snapshot);
        assertFalse(detail.contains("obsolete field failure"));
        assertFalse(detail.contains("发送成功"));
        assertTrue(detail.contains("实际行为仍需真机确认"));
    }

    @Test public void installationFailureRetainsOnlyReflectionContractsAndKnownRuleIds() {
        assertEquals("NoSuchFieldException: fixture.RecordModel.LIZJ",
                HostAdaptationDiagnostics.installationFailure(new NoSuchFieldException("fixture.RecordModel.LIZJ")));
        assertEquals("NoSuchMethodException: fixture.Audio.<init>(java.io.File,java.util.List,java.lang.Long)",
                HostAdaptationDiagnostics.installationFailure(new NoSuchMethodException(
                        "fixture.Audio.<init>(java.io.File,java.util.List,java.lang.Long)")));
        assertEquals("ClassNotFoundException: fixture.RecordState",
                HostAdaptationDiagnostics.installationFailure(new RuntimeException("untrusted wrapper",
                        new ClassNotFoundException("fixture.RecordState"))));
        assertEquals("IllegalStateException: Host rule is not unique: dm_speech_record_model",
                HostAdaptationDiagnostics.installationFailure(new IllegalStateException(
                        "Host rule is not unique: dm_speech_record_model")));
        for (Throwable failure : new Throwable[]{
                new IllegalStateException("https://example.test/audio?key=private-token user draft"),
                new NoSuchFieldException("正文 https://example.test/?key=private-token"),
                new NoSuchMethodException("fixture.Audio(https://example.test/?key=private-token)"),
                new ClassNotFoundException("Bearer private-token"),
                new IllegalStateException("Host rule is not unique: secret_api_key")}) {
            assertEquals(failure.getClass().getSimpleName(), HostAdaptationDiagnostics.installationFailure(failure));
        }
    }

    @Test public void installationFailurePreservesAndroidConstructorAndMethodContracts() {
        assertEquals("NoSuchMethodException: fixture.Audio.<init>(java.io.File,java.util.List,java.lang.Long)",
                HostAdaptationDiagnostics.installationFailure(new NoSuchMethodException(
                        "fixture.Audio.<init> [class java.io.File, interface java.util.List, class java.lang.Long]")));
        assertEquals("NoSuchMethodException: fixture.RecordModel.<init>()",
                HostAdaptationDiagnostics.installationFailure(new NoSuchMethodException("fixture.RecordModel.<init> []")));
        assertEquals("NoSuchMethodException: fixture.Audio.send(java.lang.String,int,java.lang.String[],int[][])",
                HostAdaptationDiagnostics.installationFailure(new NoSuchMethodException(
                        "fixture.Audio.send [class java.lang.String, int, class [Ljava.lang.String;, class [[I]")));
    }

    @Test public void installationFailureKeepsAndroidMissingClassButDropsDexPathsAndSensitivePayloads() {
        String path = "DexPathList[[zip file \"/data/user/0/host/private-token/base.apk\"],"
                + "nativeLibraryDirectories=[/data/user/0/host/user-draft/" + "private-data/".repeat(30) + "]]";
        String message = "Didn't find class \"fixture.RecordState\" on path: " + path;
        String summary = HostAdaptationDiagnostics.installationFailure(new ClassNotFoundException(message));
        assertEquals("ClassNotFoundException: fixture.RecordState", summary);
        assertFalse(summary.contains("private-token"));
        assertFalse(summary.contains("user-draft"));
        assertFalse(summary.contains("DexPathList"));
        assertEquals("ClassNotFoundException: fixture.RecordState",
                HostAdaptationDiagnostics.installationFailure(new RuntimeException("Bearer private-token",
                        new ClassNotFoundException(message))));

        for (Throwable failure : new Throwable[]{
                new ClassNotFoundException("Didn't find class \"https://example.test/?key=private-token\" on path: " + path),
                new ClassNotFoundException("Didn't find class \"fixture.RecordState\" Bearer private-token"),
                new NoSuchMethodException("fixture.Audio.send [class java.lang.String, Bearer private-token]"),
                new NoSuchMethodException("fixture.Audio.send [class java.lang.String] private-token"),
                new NoSuchMethodException("fixture.Audio.send [class https://example.test/?key=private-token]")}) {
            assertEquals(failure.getClass().getSimpleName(), HostAdaptationDiagnostics.installationFailure(failure));
        }
    }

    private static Bundle version(String name, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = HostAdaptation.TARGET_PACKAGE;
        info.versionName = name;
        info.setLongVersionCode(code);
        return HostAdaptationDiagnostics.versionEvidence(info);
    }

    private static HostScanReport report(String ambiguousRule) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures()) ids.addAll(feature.rules);
        List<RuleScanResult> results = new ArrayList<>();
        for (String id : ids) {
            HostCandidate first = new HostCandidate("base.apk", "classes.dex", "Lfixture/Owner;", id, "()V");
            List<HostCandidate> candidates = id.equals(ambiguousRule)
                    ? Arrays.asList(first, new HostCandidate("base.apk", "classes2.dex", "Lfixture/Owner;", id, "()V"))
                    : Collections.singletonList(first);
            results.add(new RuleScanResult(id, "fixture", candidates));
        }
        return new HostScanReport("fixture", results, false);
    }
}
