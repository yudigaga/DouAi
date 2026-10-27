"""Failure-path tests for the release gate; no Android SDK, network, or Gradle needed."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_verification as gate
import lint_budget


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.run = Path(self.temp.name) / "verification"
        self.run.mkdir()
        self.identity = dict(packageName="io.github.yudigaga.douai", versionName="0.1.99", versionCode=100)
        self.write(self.root / "app/build.gradle.kts", 'applicationId = "io.github.yudigaga.douai"\nversionName = "0.1.99"\nversionCode = 100\n')
        self.write(self.root / "app/src/main/java/com/douai/module/scan/HostAdaptation.java",
                   'TARGET_PACKAGE = "com.ss.android.ugc.aweme"; VERIFIED_VERSION_NAME = "40.6.0"; VERIFIED_VERSION_CODE = 400601L;')
        self.inventory = {"suites": {"example.HostTest": ["hostContract"], "example.NormalTest": ["works"]},
                          "hostContracts": {"example.HostTest": {"hostContract": "host-apk"}}}
        self.host = self.root / "original-host.apk"
        self.apk = self.run / "DouAi-0.1.99-release.apk"
        self.build_apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        for path in (self.host, self.apk, self.build_apk):
            path.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"fixture manifest")
                archive.writestr("classes.dex", b"fixture dex")
        self.build_apk.write_bytes(self.apk.read_bytes())
        self.write(self.run / "apk-badging.txt", "package: name='io.github.yudigaga.douai' versionCode='100' versionName='0.1.99'\n")
        self.write(self.run / "host-badging.txt", "package: name='com.ss.android.ugc.aweme' versionCode='400601' versionName='40.6.0'\n")
        self.write(self.run / "apk-signature.txt", "Verified using v3 scheme (APK Signature Scheme v3): true\nSigner #1 certificate SHA-256 digest: " + gate.CERTIFICATE_SHA256 + "\n")
        gate.write_json(self.build_apk.parent / "output-metadata.json", {
            "applicationId": self.identity["packageName"],
            "elements": [{"outputFile": self.build_apk.name, "versionName": "0.1.99", "versionCode": 100}]})
        self.profile = dict(gradleHeapMiB=768, testHeap="1g", maxParallelForks=1, forkEvery=20)
        self.inputs = {"identity": self.identity, "inventory": self.inventory,
                       "source": {"commit": "test", "dirty": False, "files": gate.source_snapshot(self.root)},
                       "hostApk": {"path": str(self.host), "sha256": gate.sha256(self.host)},
                       "localSmaliSha256": {}, "testProfile": self.profile}
        for relative in gate.SMALI_FILES:
            path = self.root / "douyin-40.6.0/apktool-no-res" / relative
            self.write(path, "fixture smali")
            self.inputs["localSmaliSha256"][relative] = gate.sha256(path)
        gate.write_json(self.run / "inputs.json", self.inputs)
        gate.write_json(self.run / "test-profile.json", self.profile)
        for suite, names in self.inventory["suites"].items():
            self.junit(suite, [(name, None) for name in names])
        self.write(self.run / "reports/lint-results-release.xml", '<issues><issue id="SetTextI18n" severity="Warning" message="Fixture warning" /></issues>')
        for name in gate.MAPPING_FILES:
            self.write(self.run / "reports/mapping" / name, "fixture R8 evidence")
        lint_budget.update(self.run / "reports/lint-results-release.xml", self.root,
                           self.root / "tools/lint-baseline.json", "Reviewed fixture warning")
        self.inputs["source"]["files"] = gate.source_snapshot(self.root)
        gate.write_json(self.run / "inputs.json", self.inputs)

    @staticmethod
    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def junit(self, suite, cases):
        root = ET.Element("testsuite", name=suite, tests=str(len(cases)),
                          failures=str(sum(status == "failure" for _, status in cases)),
                          errors=str(sum(status == "error" for _, status in cases)),
                          skipped=str(sum(status == "skipped" for _, status in cases)))
        for name, status in cases:
            case = ET.SubElement(root, "testcase", name=name, classname=suite)
            if status:
                ET.SubElement(case, status)
        self.write(self.run / "reports/junit" / f"TEST-{suite}.xml", ET.tostring(root, encoding="unicode"))

    def reports(self):
        return gate.junit_reports(self.run / "reports/junit")

    def test_source_version_rejects_uncarried_components(self):
        for version in ("0.1.100", "0.1.101", "0.10.0", "0.9.100"):
            with self.subTest(version=version):
                self.write(self.root / "app/build.gradle.kts",
                           'applicationId = "io.github.yudigaga.douai"\n'
                           f'versionName = "{version}"\nversionCode = 102\n')
                with self.assertRaisesRegex(gate.VerificationError, "version.*carry"):
                    gate.source_identity(self.root)

    def test_source_version_accepts_normalized_carries_without_deriving_version_code(self):
        for version in ("0.1.99", "0.2.0", "0.2.1", "0.9.99", "1.0.0"):
            with self.subTest(version=version):
                self.write(self.root / "app/build.gradle.kts",
                           'applicationId = "io.github.yudigaga.douai"\n'
                           f'versionName = "{version}"\nversionCode = 102\n')
                actual = gate.source_identity(self.root)
                self.assertEqual(version, actual["versionName"])
                self.assertEqual(102, actual["versionCode"])

    def seal(self):
        gate.seal(self.root, self.run, self.apk, self.build_apk)

    def finish(self):
        self.seal()
        return gate.finish(self.root, self.run)

    def test_success_records_local_scope_and_binds_saved_evidence(self):
        manifest = self.finish()
        self.assertEqual(2, manifest["tests"]["tests"])
        self.assertEqual("not-run", manifest["deviceValidation"])
        self.assertFalse(manifest["published"])
        self.assertEqual(gate.sha256(self.apk), manifest["artifact"]["sha256"])
        self.assertIn("reports/junit/TEST-example.HostTest.xml", manifest["evidenceSha256"])
        self.assertEqual(manifest, gate.check_integrity(self.run))

    def test_missing_host_input_fails_before_work(self):
        with self.assertRaisesRegex(gate.VerificationError, "Missing APK"):
            gate.prepare(self.root, self.root / "absent.apk", self.run)

    def test_missing_final_archive_fails(self):
        self.apk.unlink()
        with self.assertRaisesRegex(gate.VerificationError, "Missing APK"):
            self.seal()

    def test_incorrect_archive_is_rejected_before_tests(self):
        self.apk.write_bytes(self.apk.read_bytes() + b"changed")
        with self.assertRaisesRegex(gate.VerificationError, "differs"):
            self.seal()

    def test_stale_signed_apk_left_by_unsigned_build_is_rejected(self):
        metadata = gate.read_json(self.build_apk.parent / "output-metadata.json")
        metadata["elements"][0]["outputFile"] = "app-release-unsigned.apk"
        gate.write_json(self.build_apk.parent / "output-metadata.json", metadata)
        with self.assertRaisesRegex(gate.VerificationError, "current Gradle build"):
            self.seal()

    def test_wrong_package_or_version_is_rejected(self):
        self.write(self.run / "apk-badging.txt", "package: name='other.package' versionCode='100' versionName='0.1.99'")
        with self.assertRaisesRegex(gate.VerificationError, "identity"):
            self.seal()

    def test_wrong_host_profile_is_rejected(self):
        self.write(self.run / "host-badging.txt", "package: name='com.ss.android.ugc.aweme' versionCode='400602' versionName='40.6.1'")
        with self.assertRaisesRegex(gate.VerificationError, "Host APK"):
            self.seal()

    def test_explicit_legacy_and_latest_profiles_are_both_valid_host_identities(self):
        source = ('TARGET_PACKAGE = "com.ss.android.ugc.aweme"; '
                  'VERIFIED_VERSION_NAME = "40.8.0"; VERIFIED_VERSION_CODE = 400801L; '
                  'public static final Profile PROFILE_406 = new Profile("40.6.0", 400601L, "old", "hash", null); '
                  'public static final Profile PROFILE_408 = new Profile("40.8.0", 400801L, "new", "hash", null);')
        identities = gate.verified_host_identities(source)
        self.assertEqual({("40.6.0", 400601), ("40.8.0", 400801)},
                         {(item["versionName"], item["versionCode"]) for item in identities})
        self.assertTrue(all(item["packageName"] == "com.ss.android.ugc.aweme" for item in identities))

    def test_legacy_host_can_seal_only_when_its_exact_profile_is_declared(self):
        source = self.root / "app/src/main/java/com/douai/module/scan/HostAdaptation.java"
        self.write(source, 'TARGET_PACKAGE = "com.ss.android.ugc.aweme"; '
                   'VERIFIED_VERSION_NAME = "40.8.0"; VERIFIED_VERSION_CODE = 400801L; '
                   'public static final Profile PROFILE_406 = new Profile("40.6.0", 400601L, "old", "hash", null);')
        self.seal()
        self.write(self.run / "host-badging.txt",
                   "package: name='com.ss.android.ugc.aweme' versionCode='400801' versionName='40.6.0'")
        with self.assertRaisesRegex(gate.VerificationError, "Host APK"):
            self.seal()

    def test_wrong_certificate_or_missing_v3_is_rejected(self):
        valid = (self.run / "apk-signature.txt").read_text()
        for text in (valid.replace(gate.CERTIFICATE_SHA256, "A" * 64), valid.replace(": true", ": false")):
            with self.subTest(text=text):
                self.write(self.run / "apk-signature.txt", text)
                with self.assertRaises(gate.VerificationError):
                    self.seal()

    def test_any_skip_failure_or_error_blocks_strict_gate(self):
        for status in ("skipped", "failure", "error"):
            with self.subTest(status=status):
                self.junit("example.HostTest", [("hostContract", status)])
                with self.assertRaisesRegex(gate.VerificationError, "zero failures/errors/skips"):
                    gate.validate_full_junit(self.inventory, self.reports())

    def test_missing_suite_or_test_is_rejected(self):
        path = self.run / "reports/junit/TEST-example.NormalTest.xml"
        path.unlink()
        with self.assertRaisesRegex(gate.VerificationError, "suite inventory mismatch"):
            gate.validate_full_junit(self.inventory, self.reports())
        self.junit("example.NormalTest", [("differentTest", None)])
        with self.assertRaisesRegex(gate.VerificationError, "method inventory mismatch"):
            gate.validate_full_junit(self.inventory, self.reports())

    def test_junit_header_cannot_hide_failed_case(self):
        self.junit("example.HostTest", [("hostContract", "failure")])
        path = self.run / "reports/junit/TEST-example.HostTest.xml"
        self.write(path, path.read_text().replace('failures="1"', 'failures="0"'))
        with self.assertRaisesRegex(gate.VerificationError, "counter mismatch"):
            self.reports()

    def test_robolectric_variants_are_counted_and_all_must_pass(self):
        self.junit("example.NormalTest", [("works[28]", None), ("works[35]", None)])
        self.assertEqual(3, gate.validate_full_junit(self.inventory, self.reports())["tests"])
        self.junit("example.NormalTest", [("works[28]", None), ("works[35]", "skipped")])
        with self.assertRaises(gate.VerificationError):
            gate.validate_full_junit(self.inventory, self.reports())

    def test_ci_reports_all_host_class_skips_without_claiming_success(self):
        self.junit("example.HostTest", [("classMethod", "skipped")])
        summary, bad = gate.host_summary(self.inventory, self.reports(), False)
        self.assertFalse(bad)
        self.assertIn("SKIPPED / NOT VERIFIED", summary)
        _, bad = gate.host_summary(self.inventory, self.reports(), True)
        self.assertTrue(bad)

    def test_ci_missing_host_report_is_a_failure(self):
        reports = self.reports()
        del reports["example.HostTest"]
        summary, bad = gate.host_summary(self.inventory, reports, False)
        self.assertTrue(bad)
        self.assertIn("MISSING REPORT", summary)

    def test_ci_cannot_claim_host_pass_without_configured_input(self):
        _, bad = gate.host_summary(self.inventory, self.reports(), False)
        self.assertTrue(bad)

    def test_profile_override_cannot_silently_weaken_release_gate(self):
        self.seal()
        gate.write_json(self.run / "test-profile.json", {**self.profile, "forkEvery": 0})
        with self.assertRaisesRegex(gate.VerificationError, "profile differs"):
            gate.finish(self.root, self.run)

    def test_missing_mapping_or_lint_error_fails(self):
        self.seal()
        self.write(self.run / "reports/lint-results-release.xml", '<issues><issue severity="Error" /></issues>')
        with self.assertRaisesRegex(gate.VerificationError, "Lint has errors"):
            gate.finish(self.root, self.run)
        self.write(self.run / "reports/lint-results-release.xml", '<issues />')
        (self.run / "reports/mapping/mapping.txt").unlink()
        with self.assertRaisesRegex(gate.VerificationError, "Missing R8"):
            gate.finish(self.root, self.run)

    def test_source_host_archive_or_sealed_evidence_mutation_fails(self):
        for mutation in ("source", "host", "archive", "evidence", "smali"):
            with self.subTest(mutation=mutation):
                # Each subcase has an isolated fixture via a fresh instance.
                case = ReleaseGateTest()
                case.setUp()
                try:
                    case.seal()
                    target = {"source": case.root / "app/build.gradle.kts", "host": case.host,
                              "archive": case.apk, "evidence": case.run / "apk-signature.txt",
                              "smali": case.root / "douyin-40.6.0/apktool-no-res" / gate.SMALI_FILES[0]}[mutation]
                    target.write_bytes(target.read_bytes() + b"tampered")
                    with self.assertRaises(gate.VerificationError):
                        gate.finish(case.root, case.run)
                    self.assertFalse((case.run / "manifest.json").exists())
                finally:
                    case.doCleanups()

    def test_integrity_rejects_deleted_or_changed_evidence(self):
        self.finish()
        self.write(self.run / "reports/mapping/mapping.txt", "tampered")
        with self.assertRaisesRegex(gate.VerificationError, "Evidence was changed"):
            gate.check_integrity(self.run)
        (self.run / "reports/mapping/mapping.txt").unlink()
        with self.assertRaisesRegex(gate.VerificationError, "Missing input"):
            gate.check_integrity(self.run)

    def test_missing_completed_manifest_is_not_verified(self):
        with self.assertRaisesRegex(gate.VerificationError, "Missing input"):
            gate.check_integrity(self.run)

    def test_inventory_handles_java_kotlin_annotations_and_ignores_comments(self):
        test_root = self.root / "app/src/test/java/example"
        self.write(test_root / "HostTest.java", '''package example;
            // @Test public void commentedOut() {}
            class HostTest {
              @Test(expected=Exception.class) public void hostContract() { String env = "DOUAI_TEST_APK"; }
              @Test public void ordinaryTest() {}
            }''')
        self.write(test_root / "KotlinTest.kt", '''package example
            class KotlinTest {
              @Test @Config(sdk = [28, 35])
              fun works() { val text = "@Test" }
            }''')
        result = gate.test_inventory(self.root, require_contracts=False)
        self.assertEqual(["hostContract", "ordinaryTest"], result["suites"]["example.HostTest"])
        self.assertEqual(["works"], result["suites"]["example.KotlinTest"])
        self.assertEqual({"hostContract": "host-apk"}, result["hostContracts"]["example.HostTest"])
        with self.assertRaisesRegex(gate.VerificationError, "Required host-contract suite"):
            gate.test_inventory(self.root)

    def test_nested_java_and_kotlin_suites_match_complete_gradle_report_fixture(self):
        fixture = Path(__file__).parent / "fixtures/nested"
        inventory = gate.test_inventory(fixture, require_contracts=False)
        reports = gate.junit_reports(fixture / "junit")
        self.assertEqual(6, len(inventory["suites"]))
        self.assertEqual(["sameName", "afterNestedClass"], inventory["suites"]["example.JavaOuterTest"])
        self.assertEqual(["sameName"], inventory["suites"]["example.JavaOuterTest$NestedTest"])
        self.assertEqual(["deep"], inventory["suites"]["example.JavaOuterTest$NestedTest$DeepTest"])
        self.assertEqual(["nested works"], inventory["suites"]["example.KotlinOuterTest$NestedTest"])
        self.assertEqual({"example.JavaOuterTest"}, set(inventory["hostContracts"]))
        totals = gate.validate_full_junit(inventory, reports)
        self.assertEqual(6, totals["suites"])
        self.assertEqual(8, totals["tests"])

    def test_missing_nested_suite_or_misfiled_nested_method_fails(self):
        fixture = Path(__file__).parent / "fixtures/nested"
        inventory = gate.test_inventory(fixture, require_contracts=False)
        reports = gate.junit_reports(fixture / "junit")
        nested = reports.pop("example.JavaOuterTest$NestedTest")
        with self.assertRaisesRegex(gate.VerificationError, "suite inventory mismatch"):
            gate.validate_full_junit(inventory, reports)
        reports["example.JavaOuterTest$NestedTest"] = nested
        nested["cases"][0].set("name", "afterNestedClass")
        with self.assertRaisesRegex(gate.VerificationError, "method inventory mismatch"):
            gate.validate_full_junit(inventory, reports)

    def test_actual_speech_preview_tests_have_their_own_required_suite(self):
        project = Path(__file__).resolve().parents[2]
        inventory = gate.test_inventory(project)
        nested = "com.douai.module.ui.SpeechSettingsDialogTest$MiuixPreviewTest"
        self.assertEqual({"narrowMiuixPageAndParameterEditorKeepTheirControlsVisible",
                          "landscapeMiuixPageAndParameterEditorKeepTheirControlsVisible",
                          "breezeSettingsUseTheSameNarrowHostDialogsWithTheirOwnControls",
                          "fishSettingsSeparateTheEngineReferenceVoiceAndSupportedGroupsInMiuix",
                          "fishReferenceAudioAndSpeakerEditorsRemainReachableInTheNarrowMiuixPage",
                          "publicFishWindowKeepsItsChoicesVisibleInMiuix",
                          "filteredCatalogHeaderAndLocalSearchRemainUsableInNarrowMiuix",
                          "allBreezeCatalogFiltersAndMultipleSelectionsAreReachableInMiuix"},
                         set(inventory["suites"][nested]))
        self.assertFalse(set(inventory["suites"][nested]) &
                         set(inventory["suites"]["com.douai.module.ui.SpeechSettingsDialogTest"]))


if __name__ == "__main__":
    unittest.main()
