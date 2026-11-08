package com.douai.module.scan;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class HostApkProfileTest {
    @Test public void onlyVerified410BuildSelectsTheNewProfileFromEitherStringPoolEncoding() throws Exception {
        HostAdaptation.Profile profile = HostAdaptation.forVersion(
                HostAdaptation.TARGET_PACKAGE, "41.0.0", 410001L);
        assertNotNull("Missing 41.0.0 profile", profile);
        for (boolean utf8 : new boolean[]{true, false}) {
            byte[] accepted = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                    "41.0.0", 410001, utf8, 41009900L);
            assertEquals(41009900L, HostApkProfile.parseManifest(accepted).updateVersionCode);
            assertSame(profile, HostApkProfile.read(apk(accepted)));
        }
        for (long build : new long[]{41009985L, 41009901L, 40809900L, -1L}) {
            byte[] rejected = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                    "41.0.0", 410001, true, build);
            assertNull("Unsupported 41.0 build must not use 41009900 rules: " + build,
                    HostApkProfile.read(apk(rejected)));
        }
    }

    @Test public void readsBothReleasedProfilesFromUtf8AndUtf16BinaryManifests() throws Exception {
        assertSame(HostAdaptation.PROFILE_406, HostApkProfile.read(apk(manifest(
                HostAdaptation.TARGET_PACKAGE, "40.6.0", 400601, true))));
        assertSame(HostAdaptation.PROFILE_408, HostApkProfile.read(apk(manifest(
                HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, false))));
    }

    @Test public void unknownVersionsMismatchedCodesAndDifferentPackagesNeverSelectTheLatestProfile() throws Exception {
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801, true))));
        assertNull(HostApkProfile.read(apk(manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400601, true))));
        assertNull(HostApkProfile.read(apk(manifest("other.application", "40.8.0", 400801, true))));
    }

    @Test public void only9900BuildCanSelect408EvenWhenVersionNameAndCodeAreIdentical() throws Exception {
        for (long build : new long[]{40809985L, 40809901L, -1L}) {
            byte[] xml = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                    "40.8.0", 400801, true, build);
            assertNull("Unsupported 40.8 build must not use 9900 rules: " + build, HostApkProfile.read(apk(xml)));
        }
        byte[] accepted = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                "40.8.0", 400801, false, 40809900L);
        assertEquals(40809900L, HostApkProfile.parseManifest(accepted).updateVersionCode);
        assertSame(HostAdaptation.PROFILE_408, HostApkProfile.read(apk(accepted)));
    }

    @Test public void updateVersionMustBeCompiledAndroidMetadataInsideTheApplication() throws Exception {
        byte[] xml = BinaryHostManifestFixture.manifest(HostAdaptation.TARGET_PACKAGE,
                "40.8.0", 400801, true, 40809900L);
        int root = 8 + buffer(xml).getInt(12);
        int application = root + 96, meta = application + 36;
        byte[] wrongNamespace = xml.clone();
        buffer(wrongNamespace).putInt(meta + 36, -1);
        assertNull(HostApkProfile.read(apk(wrongNamespace)));
        byte[] wrongOwner = xml.clone();
        buffer(wrongOwner).putInt(application + 20, 14); // activity is not application metadata.
        assertNull(HostApkProfile.read(apk(wrongOwner)));
        byte[] compiledOldBuild = xml.clone();
        buffer(compiledOldBuild).putInt(meta + 36 + 20 + 8, 13); // Raw string says 40809900.
        buffer(compiledOldBuild).putInt(meta + 36 + 20 + 16, 40809985);
        assertNull(HostApkProfile.read(apk(compiledOldBuild)));
    }

    @Test public void invalidChunkAttributeAndStringOffsetsFailAsIoErrors() throws Exception {
        byte[] valid = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        assertInvalid(Arrays.copyOf(valid, valid.length - 1));

        byte[] badChunk = valid.clone();
        buffer(badChunk).putInt(12, Integer.MAX_VALUE);
        assertInvalid(badChunk);

        byte[] badString = valid.clone();
        buffer(badString).putInt(8 + 28, Integer.MAX_VALUE);
        assertInvalid(badString);

        byte[] badAttribute = valid.clone();
        int element = 8 + buffer(valid).getInt(12);
        buffer(badAttribute).putShort(element + 28, (short) 0xffff);
        assertInvalid(badAttribute);

        assertInvalid(new byte[16 * 1024 * 1024 + 1]);
    }

    @Test public void versionAttributesRequireTheAndroidNamespace() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.8.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20, -1);
        assertInvalid(xml);
    }

    @Test public void uncompiledRawTextCannotOverrideTheActualTypedVersion() throws Exception {
        byte[] xml = manifest(HostAdaptation.TARGET_PACKAGE, "40.7.0", 400801, true);
        int element = 8 + buffer(xml).getInt(12);
        buffer(xml).putInt(element + 36 + 20 + 8, 7); // Raw text says 40.8.0; the compiled value remains 40.7.0.
        assertNull(HostApkProfile.read(apk(xml)));
    }

    private static void assertInvalid(byte[] manifest) {
        try {
            HostApkProfile.parseManifest(manifest);
            fail("Malformed Manifest must fail explicitly");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static File apk(byte[] manifest) throws IOException {
        // Its misleading name ensures profile selection is bound to Manifest contents.
        File apk = Files.createTempFile("douyin-40.8.0-", ".apk").toFile();
        apk.deleteOnExit();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            output.write(manifest);
            output.closeEntry();
        }
        return apk;
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    private static byte[] manifest(String packageName, String versionName, int versionCode, boolean utf8) throws IOException {
        return BinaryHostManifestFixture.manifest(packageName, versionName, versionCode, utf8,
                "40.8.0".equals(versionName) && versionCode == 400801
                        ? HostAdaptation.VERIFIED_UPDATE_VERSION_CODE : -1);
    }

    private static void attribute(ByteBuffer data, int namespace, int name, int raw, int type, int value) {
        data.putInt(namespace).putInt(name).putInt(raw).putShort((short) 8);
        data.put((byte) 0).put((byte) type).putInt(value);
    }
}
