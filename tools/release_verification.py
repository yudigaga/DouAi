"""Local release evidence checks and CI host-contract summaries (Python stdlib only)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
import zipfile
import lint_budget

CERTIFICATE_SHA256 = "FAE67C134F4C4FA73B8E34487C47A8C7F3E7C34B840BC245AFAC51585B7DFD47"
SMALI_FILES = (
    "smali_classes9/com/ss/android/ugc/aweme/services/IExternalService.smali",
    "smali_classes43/com/ss/android/ugc/aweme/services/IExternalService$Companion.smali",
    "smali_classes9/X/0PoP.smali", "smali_classes33/X/15De.1.smali", "smali_classes53/X/0BDp.1.smali",
)
MAPPING_FILES = ("mapping.txt", "configuration.txt", "seeds.txt", "usage.txt")
REQUIRED_HOST_SUITES = {
    "com.douai.module.scan.DouyinHostAnchorTest",
    "com.douai.module.scan.NativeChatHostContractTest",
    "com.douai.module.scan.CommentReplyHostContractTest",
    "com.douai.module.scan.BackgroundMessageHostContractTest",
    "com.douai.module.scan.HiddenVideoHostContractTest",
    "com.douai.module.scan.HostResourceTableContractTest",
    "com.douai.module.scan.AutoMessageHostContractTest",
    "com.douai.module.feature.OfficialLivePhotoDownloadTest",
    "com.douai.module.feature.LivePhotoRecognitionRegressionTest",
}
APK_SUITE = "com.douai.module.scan.ApkArtifactContractTest"


class VerificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def schema_version(record, label):
    version = record.get("schemaVersion")
    require(type(version) is int and version in (1, 2), f"Unsupported {label} schema version")
    return version


def sha256(path):
    require(path.is_file(), f"Missing input: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def read_json(path):
    require(path.is_file(), f"Missing input: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def source_identity(root):
    text = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    def field(name, pattern):
        match = re.search(r"\b" + name + r"\s*=\s*" + pattern, text)
        require(match, f"Missing Gradle field {name}")
        return match.group(1)
    version_name = field("versionName", r'"([^"]+)"')
    require(re.fullmatch(r"[0-9]+\.[0-9]\.(?:0|[1-9][0-9]?)", version_name),
            "Release version must carry at patch 100 and minor 10")
    return {"packageName": field("applicationId", r'"([^"]+)"'),
            "versionName": version_name,
            "versionCode": int(field("versionCode", r"(\d+)"))}


def source_snapshot(root):
    paths = set()
    for folder in ("app/src", "gradle", "tools", ".github"):
        paths.update(p for p in (root / folder).rglob("*") if p.is_file()
                     and "__pycache__" not in p.parts and p.suffix != ".pyc")
    for name in ("app/build.gradle.kts", "app/proguard-release.pro", "build.gradle.kts",
                 "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat"):
        path = root / name
        if path.is_file():
            paths.add(path)
    return {p.relative_to(root).as_posix(): sha256(p) for p in sorted(paths)}


def lexical_source(text):
    """Mask comments and literals without moving source offsets (including Kotlin raw strings)."""
    masked = list(text)
    index = 0
    while index < len(text):
        end = index
        if text.startswith("//", index):
            end = text.find("\n", index)
            if end < 0:
                end = len(text)
        elif text.startswith("/*", index):
            end, depth = index + 2, 1
            while end < len(text) and depth:
                if text.startswith("/*", end):
                    depth += 1
                    end += 2
                elif text.startswith("*/", end):
                    depth -= 1
                    end += 2
                else:
                    end += 1
            require(depth == 0, "Unclosed source block comment")
        elif text.startswith('"""', index):
            closing = text.find('"""', index + 3)
            require(closing >= 0, "Unclosed source text block")
            end = closing + 3
        elif text[index] in ('"', "'"):
            quote = text[index]
            end = index + 1
            while end < len(text):
                if text[end] == "\\":
                    end += 2
                elif text[end] == quote:
                    end += 1
                    break
                else:
                    end += 1
            require(end <= len(text) and text[end - 1] == quote, "Unclosed source string")
        if end > index:
            for offset in range(index, end):
                if text[offset] not in "\r\n":
                    masked[offset] = " "
            index = end
        else:
            index += 1
    return "".join(masked)


def lexical_classes_and_annotations(clean):
    """Resolve member class scopes and balanced delimiters, rather than assuming one suite/file."""
    token_pattern = re.compile(
        r"(?P<type>(?<![\w.:])(?:class|interface|object)\s+(?P<name>`[^`]+`|[A-Za-z_]\w*))"
        r"|(?P<annotation>@(?:org\.junit\.)?(?:Test|BeforeClass|Before)\b)"
        r"|(?P<word>\b(?:fun|void|val|var)\b)|(?P<symbol>[{}()\[\];@])"
        r"|(?P<identifier>`[^`]*`)"
    )
    frames, scopes, annotations, pairs, delimiters = [], [], [], {}, []
    pending = None
    parens = brackets = 0
    def current_scope():
        return next((frame for frame in reversed(frames) if frame is not None), None)
    for token in token_pattern.finditer(clean):
        kind = token.lastgroup
        if token.group("type"):
            pending = {"name": token.group("name").strip("`"), "parent": current_scope(),
                       "declaration": token.start(), "parens": parens, "brackets": brackets,
                       "depth": len(frames)}
        elif kind == "annotation":
            if pending and (parens, brackets, len(frames)) == (pending["parens"], pending["brackets"], pending["depth"]):
                pending = None  # A Kotlin class without a body ends before the next member annotation.
            scope = current_scope()
            require(scope is not None, "JUnit method annotation outside a named class")
            annotations.append({"kind": token.group().rsplit(".", 1)[-1].lstrip("@"),
                                "start": token.start(), "end": token.end(), "scope": scope})
        elif kind == "word":
            if pending and (parens, brackets, len(frames)) == (pending["parens"], pending["brackets"], pending["depth"]):
                pending = None
        elif kind == "symbol":
            value = token.group()
            if value in "({[":
                delimiters.append((value, token.start()))
            elif value in ")}]":
                require(delimiters and delimiters[-1][0] == {")": "(", "}": "{", "]": "["}[value],
                        "Unbalanced source delimiters")
                _, opening = delimiters.pop()
                pairs[opening] = token.start()
            if value == "(":
                parens += 1
            elif value == ")":
                parens -= 1
            elif value == "[":
                brackets += 1
            elif value == "]":
                brackets -= 1
            elif value == "{":
                if pending and (parens, brackets, len(frames)) == (pending["parens"], pending["brackets"], pending["depth"]):
                    scope = {**pending, "body": token.start(), "end": None}
                    scopes.append(scope)
                    frames.append(scope)
                    pending = None
                else:
                    frames.append(None)
            elif value == "}":
                scope = frames.pop()
                if scope is not None:
                    scope["end"] = token.start()
                if pending and len(frames) <= pending["depth"]:
                    pending = None
            elif value in ";@" and pending and (parens, brackets) == (pending["parens"], pending["brackets"]):
                pending = None
    require(not delimiters and not frames, "Unclosed source delimiters")
    return annotations, pairs


def annotated_methods(text, clean, annotations, pairs, package):
    methods = []
    for annotation in annotations:
        scope = annotation["scope"]
        match = re.search(r"\b(?:fun|void)\s+(`[^`]+`|[A-Za-z_]\w*)\s*\(", clean[annotation["end"]:scope["end"]])
        require(match, "Cannot inventory JUnit annotated method")
        parameter_start = annotation["end"] + match.end() - 1
        parameter_end = pairs[parameter_start]
        body = clean.find("{", parameter_end + 1, scope["end"])
        require(body >= 0, "JUnit annotated methods must have a block body")
        method_end = pairs[body] + 1
        ancestors = []
        current = scope
        while current:
            ancestors.append(current["name"])
            current = current["parent"]
        methods.append({**annotation, "suite": package + "." + "$".join(reversed(ancestors)),
                        "name": match.group(1).strip("`"), "raw": text[annotation["start"]:method_end]})
    return methods


def test_inventory(root, require_contracts=True):
    """Inventory actual Java/Kotlin JUnit class scopes, including independently run nested suites."""
    suites = {}
    hosts = {}
    for path in sorted((root / "app/src/test").rglob("*")):
        if path.suffix not in (".java", ".kt"):
            continue
        text = path.read_text(encoding="utf-8")
        clean = lexical_source(text)
        if not re.search(r"@(?:org\.junit\.)?Test\b", clean):
            continue
        package = re.search(r"\bpackage\s+([\w.]+)", clean)
        require(package, f"Missing test package: {path}")
        annotations, pairs = lexical_classes_and_annotations(clean)
        methods = annotated_methods(text, clean, annotations, pairs, package.group(1))
        host_setups = {m["suite"] for m in methods if m["kind"] in ("BeforeClass", "Before")
                       and "DOUAI_TEST_APK" in m["raw"]}
        file_suites = set()
        for method in methods:
            if method["kind"] != "Test":
                continue
            suite, name = method["suite"], method["name"]
            if suite not in file_suites:
                require(suite not in suites, f"Duplicate source suite {suite}")
                suites[suite] = []
                file_suites.add(suite)
            require(name not in suites[suite], f"Duplicate source test {suite}.{name}")
            suites[suite].append(name)
            raw = method["raw"]
            dependency = ("host-apk" if suite in host_setups or "DOUAI_TEST_APK" in raw else
                          "local-smali" if "local host APK evidence" in raw else None)
            if dependency:
                hosts.setdefault(suite, {})[name] = dependency
    require(suites, "No source test suites found")
    require(hosts, "No host-contract suites found")
    if require_contracts:
        require(REQUIRED_HOST_SUITES <= set(hosts), "Required host-contract suite missing from source: " +
                str(sorted(REQUIRED_HOST_SUITES - set(hosts))))
        require(APK_SUITE in suites and len(suites[APK_SUITE]) >= 3, "Required final APK contracts missing from source")
        require("verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet" in
                suites["com.douai.module.scan.DouyinHostAnchorTest"], "Required host rule uniqueness contract missing")
    return {"suites": suites, "hostContracts": hosts}


def junit_reports(directory, allow_empty=False):
    reports = {}
    for path in sorted(directory.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        require(root.tag == "testsuite", f"Expected Gradle testsuite: {path}")
        name = root.get("name")
        require(name and name not in reports, f"Missing or duplicate JUnit suite: {path}")
        cases = root.findall("testcase")
        counts = {"tests": len(cases),
                  "failures": sum(case.find("failure") is not None for case in cases),
                  "errors": sum(case.find("error") is not None for case in cases),
                  "skipped": sum(case.find("skipped") is not None for case in cases)}
        for key, actual in counts.items():
            require(int(root.get(key, "0")) == actual, f"JUnit {key} counter mismatch: {name}")
        require(counts["tests"] > 0, f"Empty JUnit suite: {name}")
        reports[name] = {"counts": counts, "cases": cases}
    require(reports or allow_empty, f"No JUnit reports in {directory}")
    return reports


def canonical_name(name):
    # Robolectric adds SDK suffixes, e.g. method[28]. Every actual variant is still checked.
    return name.split("[", 1)[0]


def validate_full_junit(inventory, reports):
    expected = inventory["suites"]
    require(set(expected) == set(reports), "JUnit suite inventory mismatch; missing=" +
            str(sorted(set(expected) - set(reports))) + "; unexpected=" +
            str(sorted(set(reports) - set(expected))))
    totals = dict(tests=0, failures=0, errors=0, skipped=0, suites=len(reports))
    for suite, methods in expected.items():
        report = reports[suite]
        counts = report["counts"]
        require(not any(counts[k] for k in ("failures", "errors", "skipped")),
                f"Release requires zero failures/errors/skips: {suite}: {counts}")
        actual = {canonical_name(case.get("name", "")) for case in report["cases"]}
        require(set(methods) == actual, f"JUnit method inventory mismatch: {suite}; missing=" +
                str(sorted(set(methods) - actual)) + "; unexpected=" + str(sorted(actual - set(methods))))
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += counts[key]
    return totals


def host_summary(inventory, reports, host_configured):
    rows = []
    bad = False
    for suite, methods in inventory["hostContracts"].items():
        report = reports.get(suite)
        if report is None:
            rows.append((suite, "MISSING REPORT", 0, 0, len(methods)))
            bad = True
            continue
        passed = skipped = missing = failed = 0
        for name, dependency in methods.items():
            cases = [case for case in report["cases"] if canonical_name(case.get("name", "")) == name]
            # JUnit emits a single class-level skip for @BeforeClass assumptions.
            if not cases and report["counts"]["tests"] == report["counts"]["skipped"]:
                skipped += 1
            elif not cases:
                missing += 1
            elif any(case.find("failure") is not None or case.find("error") is not None for case in cases):
                failed += 1
            elif any(case.find("skipped") is not None for case in cases):
                skipped += 1
            else:
                passed += 1
                if dependency == "host-apk" and not host_configured:
                    bad = True
            if dependency == "host-apk" and host_configured and (not cases or any(
                    case.find("skipped") is not None for case in cases)):
                bad = True
        state = ("FAILED" if failed else "MISSING TESTS" if missing else
                 "SKIPPED / NOT VERIFIED" if skipped else "PASSED")
        bad |= bool(failed or missing)
        rows.append((suite, state, passed, skipped, missing))
    lines = ["## Host contracts", "", "A green ordinary CI run may have unverified host contracts.", "",
             "| Suite | Status | Passed | Skipped | Missing |", "| --- | --- | ---: | ---: | ---: |"]
    lines.extend(f"| `{suite.rsplit('.', 1)[-1]}` | {state} | {passed} | {skipped} | {missing} |"
                 for suite, state, passed, skipped, missing in rows)
    lines.extend(["", "`host-apk` requires DOUAI_TEST_APK; the LivePhoto smali comparison also requires local extracted evidence.",
                  "These checks establish static contracts, not real-device sending or performance.", ""])
    return "\n".join(lines), bad


def validate_apk(path):
    require(path.is_file(), f"Missing APK: {path}")
    with zipfile.ZipFile(path) as archive:
        require({"AndroidManifest.xml", "classes.dex"} <= set(archive.namelist()), f"Invalid APK structure: {path}")


def parse_badging(text):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
    require(match, "Missing aapt package/version evidence")
    return {"packageName": match.group(1), "versionCode": int(match.group(2)), "versionName": match.group(3)}


def parse_signature(text):
    require(re.search(r"Verified using v3 scheme .*: true", text), "APK v3 signature was not verified")
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", text)
    require(len(digests) == 1 and digests[0].upper() == CERTIFICATE_SHA256, "Unexpected release signing certificate")
    return {"certificateSha256": digests[0].upper(), "signedV3": True}


def validate_alignment(run_dir, apk_sha256):
    evidence = read_json(run_dir / "apk-alignment.json")
    require(evidence.get("command") == ["zipalign", "-c", "-P", "16", "-v", "4"],
            "Wrong APK alignment check command")
    require(evidence.get("apkSha256") == apk_sha256, "APK alignment evidence targets a different archive")
    require(evidence.get("exitCode") == 0, "APK alignment check failed")
    output = run_dir / "apk-alignment.txt"
    require(output.is_file(), "Missing APK alignment output")
    raw = output.read_bytes()
    try:
        text = raw.decode("utf-16") if raw.startswith((b"\xff\xfe", b"\xfe\xff")) else raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise VerificationError("Invalid APK alignment output encoding") from error
    require(re.search(r"^Verification successful\s*$", text, re.M),
            "APK alignment output does not confirm success")


def prepare(root, host_apk, run_dir):
    validate_apk(host_apk)
    lint_budget.baseline_entries(root / "tools/lint-baseline.json")
    for relative in SMALI_FILES:
        require((root / "douyin-40.6.0/apktool-no-res" / relative).is_file(),
                f"Missing local-smali evidence: {relative}")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root))
    inputs = {"schemaVersion": 2, "startedAtUtc": utc_now(), "root": str(root),
              "source": {"commit": commit, "dirty": dirty, "files": source_snapshot(root)},
              "identity": source_identity(root), "inventory": test_inventory(root),
              "hostApk": {"path": str(host_apk), "sha256": sha256(host_apk)},
              "localSmaliSha256": {relative: sha256(root / "douyin-40.6.0/apktool-no-res" / relative)
                                   for relative in SMALI_FILES},
              "testProfile": {"gradleHeapMiB": 768, "testHeap": "1g", "maxParallelForks": 1, "forkEvery": 20}}
    write_json(run_dir / "inputs.json", inputs)


def verified_host_identities(host_source):
    package = re.search(r'TARGET_PACKAGE = "([^"]+)"', host_source)
    name = re.search(r'VERIFIED_VERSION_NAME = "([^"]+)"', host_source)
    code = re.search(r"VERIFIED_VERSION_CODE = (\d+)L", host_source)
    require(package and name and code, "Missing verified host identity in source")
    versions = {(name.group(1), int(code.group(1)))}
    versions.update((version, int(number)) for version, number in re.findall(
        r'public static final Profile [A-Z0-9_]+ = new Profile\("([^"]+)",\s*(\d+)L', host_source))
    return [{"packageName": package.group(1), "versionName": version, "versionCode": number}
            for version, number in sorted(versions)]


def seal(root, run_dir, apk, build_apk):
    inputs = read_json(run_dir / "inputs.json")
    version = schema_version(inputs, "inputs")
    validate_apk(apk)
    require(sha256(apk) == sha256(build_apk), "Archived APK differs from final build output")
    if version == 2:
        validate_alignment(run_dir, sha256(apk))
    metadata = read_json(build_apk.parent / "output-metadata.json")
    elements = metadata.get("elements", [])
    require(len(elements) == 1 and elements[0].get("outputFile") == build_apk.name,
            "Signed APK is not the output selected by the current Gradle build")
    identity = parse_badging((run_dir / "apk-badging.txt").read_text(encoding="utf-8-sig"))
    require(identity == inputs["identity"], "Built APK identity does not match source")
    require(metadata.get("applicationId") == identity["packageName"] and
            elements[0].get("versionCode") == identity["versionCode"] and
            elements[0].get("versionName") == identity["versionName"], "Gradle APK metadata identity mismatch")
    host_identity = parse_badging((run_dir / "host-badging.txt").read_text(encoding="utf-8-sig"))
    host_source = (root / "app/src/main/java/com/douai/module/scan/HostAdaptation.java").read_text(encoding="utf-8")
    require(host_identity in verified_host_identities(host_source),
            "Host APK is not a verified source profile")
    signature = parse_signature((run_dir / "apk-signature.txt").read_text(encoding="utf-8-sig"))
    write_json(run_dir / "output-metadata.json", metadata)
    evidence_names = ("apk-badging.txt", "apk-signature.txt", "host-badging.txt", "output-metadata.json")
    if version == 2:
        evidence_names += ("apk-alignment.txt", "apk-alignment.json")
    write_json(run_dir / "artifact-seal.json", {**identity, **signature,
               **({"schemaVersion": 2} if version == 2 else {}), "fileName": apk.name,
               "sha256": sha256(apk), "bytes": apk.stat().st_size, "hostIdentity": host_identity,
               "sealedEvidenceSha256": {name: sha256(run_dir / name) for name in evidence_names}})


def finish(root, run_dir):
    inputs = read_json(run_dir / "inputs.json")
    version = schema_version(inputs, "inputs")
    artifact = read_json(run_dir / "artifact-seal.json")
    require(artifact.get("schemaVersion", 1) == version, "Artifact seal schema differs from inputs")
    apk = run_dir / artifact["fileName"]
    require(sha256(apk) == artifact["sha256"], "Final archive was changed after sealing")
    if version == 2:
        require({"apk-alignment.txt", "apk-alignment.json"} <= set(artifact["sealedEvidenceSha256"]),
                "Missing sealed APK alignment evidence")
        validate_alignment(run_dir, artifact["sha256"])
    require(sha256(Path(inputs["hostApk"]["path"])) == inputs["hostApk"]["sha256"], "Host APK changed during verification")
    for relative, expected in inputs["localSmaliSha256"].items():
        require(sha256(root / "douyin-40.6.0/apktool-no-res" / relative) == expected,
                f"Local smali evidence changed during verification: {relative}")
    require(source_snapshot(root) == inputs["source"]["files"], "Source changed during verification; rerun against stable source")
    for name, expected in artifact["sealedEvidenceSha256"].items():
        require(sha256(run_dir / name) == expected, f"Sealed APK evidence changed: {name}")
    reports = junit_reports(run_dir / "reports/junit")
    totals = validate_full_junit(inputs["inventory"], reports)
    lint_path = run_dir / "reports/lint-results-release.xml"
    require(lint_path.is_file(), "Missing Release Lint report")
    lint = ET.parse(lint_path).getroot()
    require(lint.tag == "issues", "Invalid Release Lint XML")
    issues = lint.findall("issue")
    require(not any(i.get("severity") in ("Error", "Fatal") for i in issues), "Release Lint has errors")
    baseline = root / "tools/lint-baseline.json"
    budget = lint_budget.evaluate(lint_path, root, baseline)
    write_json(run_dir / "reports/lint-budget.json", budget)
    (run_dir / "reports/lint-budget.md").write_text(lint_budget.summary(budget), encoding="utf-8")
    require(budget["passed"], "Release Lint has unreviewed warning additions; review reports/lint-budget.json")
    write_json(run_dir / "reports/lint-baseline.json", read_json(baseline))
    for name in MAPPING_FILES:
        require((run_dir / "reports/mapping" / name).is_file(), f"Missing R8 evidence: {name}")
    for name in ("mapping.txt", "configuration.txt"):
        require((run_dir / "reports/mapping" / name).stat().st_size > 0, f"Empty R8 evidence: {name}")
    for name in ("apk-badging.txt", "apk-signature.txt", "host-badging.txt", "test-profile.json"):
        require((run_dir / name).is_file(), f"Missing evidence: {name}")
    require(read_json(run_dir / "test-profile.json") == inputs["testProfile"], "Actual Gradle test profile differs from release profile")
    # Bind a future integrity check to the original external-tool evidence as well as the APK.
    parse_signature((run_dir / "apk-signature.txt").read_text(encoding="utf-8-sig"))
    require(parse_badging((run_dir / "apk-badging.txt").read_text(encoding="utf-8-sig")) == inputs["identity"],
            "Archived package evidence changed")
    summary, bad = host_summary(inputs["inventory"], reports, True)
    require(not bad, "Host-contract summary failed")
    (run_dir / "host-contracts.md").write_text(summary, encoding="utf-8")
    manifest = {"schemaVersion": version, "status": "verified-local", "published": False,
                "finishedAtUtc": utc_now(), "source": inputs["source"], "artifact": artifact,
                "hostApk": inputs["hostApk"], "tests": totals, "testProfile": inputs["testProfile"],
                "localSmaliSha256": inputs["localSmaliSha256"],
                "lint": {"errors": 0, "warnings": sum(i.get("severity") == "Warning" for i in issues),
                         "budget": budget},
                "deviceValidation": "not-run", "realServiceValidation": "not-run"}
    (run_dir / "SHA256SUMS.txt").write_text(f"{artifact['sha256']}  {artifact['fileName']}\n", encoding="ascii")
    evidence = [p for p in run_dir.rglob("*") if p.is_file() and p.name not in ("manifest.json", "failure.json")]
    manifest["evidenceSha256"] = {p.relative_to(run_dir).as_posix(): sha256(p) for p in sorted(evidence)}
    write_json(run_dir / "manifest.json", manifest)
    return manifest


def check_integrity(run_dir):
    manifest = read_json(run_dir / "manifest.json")
    version = schema_version(manifest, "manifest")
    require(manifest.get("status") == "verified-local", "Manifest is not a completed local verification")
    require(manifest.get("evidenceSha256"), "Missing evidence hash inventory")
    if version == 2:
        require({"apk-alignment.txt", "apk-alignment.json"} <= set(manifest["evidenceSha256"]),
                "Missing APK alignment evidence from manifest")
    for relative, expected in manifest["evidenceSha256"].items():
        path = (run_dir / relative).resolve()
        require(run_dir.resolve() in path.parents, "Evidence path escapes verification directory")
        require(sha256(path) == expected, f"Evidence was changed: {relative}")
    artifact = manifest["artifact"]
    require(artifact.get("schemaVersion", 1) == version, "Artifact schema differs from manifest")
    require(sha256(run_dir / artifact["fileName"]) == artifact["sha256"], "APK digest differs from manifest")
    if version == 2:
        validate_alignment(run_dir, artifact["sha256"])
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "seal", "finish", "integrity", "host-summary", "inventory"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--host-apk", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--build-apk", type=Path)
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        if args.command in ("prepare", "seal", "finish", "integrity"):
            require(args.run_dir is not None, "--run-dir is required")
            args.run_dir = args.run_dir.resolve()
        if args.command == "prepare":
            require(args.host_apk is not None, "--host-apk is required")
            prepare(root, args.host_apk.resolve(), args.run_dir)
        elif args.command == "seal":
            require(args.apk is not None and args.build_apk is not None, "--apk and --build-apk are required")
            seal(root, args.run_dir, args.apk.resolve(), args.build_apk.resolve())
        elif args.command == "finish":
            result = finish(root, args.run_dir)
            print(f"Verified {result['tests']['tests']} tests; manifest: {args.run_dir / 'manifest.json'}")
        elif args.command == "integrity":
            check_integrity(args.run_dir)
            print("Archived evidence hashes match the completed manifest.")
        elif args.command == "inventory":
            print(json.dumps(test_inventory(root), ensure_ascii=False, indent=2))
        elif args.command == "host-summary":
            reports = junit_reports(args.junit_dir or root / "app/build/test-results/testDebugUnitTest", allow_empty=True)
            host_path = os.environ.get("DOUAI_TEST_APK")
            require(not host_path or Path(host_path).is_file(), "Configured DOUAI_TEST_APK is not a readable local APK")
            configured = bool(host_path)
            summary, bad = host_summary(test_inventory(root), reports, configured)
            print(summary)
            if args.summary:
                args.summary.parent.mkdir(parents=True, exist_ok=True)
                args.summary.write_text(summary, encoding="utf-8")
            github_summary = os.environ.get("GITHUB_STEP_SUMMARY")
            if github_summary:
                with open(github_summary, "a", encoding="utf-8") as output:
                    output.write(summary)
            require(not bad, "Host-contract coverage has missing or failed checks")
    except (VerificationError, OSError, ET.ParseError, zipfile.BadZipFile, ValueError) as error:
        print(f"Verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
