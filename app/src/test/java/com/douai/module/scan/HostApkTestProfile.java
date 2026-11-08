package com.douai.module.scan;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Selects original-APK contracts from the binary Manifest, never from the file name. */
public final class HostApkTestProfile {
    private HostApkTestProfile() { }

    public static HostAdaptation.Profile profile(File apk) throws IOException {
        HostAdaptation.Profile profile = HostApkProfile.read(apk);
        if (profile == null) {
            HostApkProfile.Identity identity = HostApkProfile.readIdentity(apk);
            throw new AssertionError("No verified profile for original APK " + identity.packageName
                    + " " + identity.versionName + " (" + identity.versionCode + "; build=" + identity.updateVersionCode + ")");
        }
        return profile;
    }

    public static boolean is406(File apk) throws IOException {
        return "40.6.0".equals(profile(apk).versionName);
    }

    public static boolean is410(File apk) throws IOException {
        return "41.0.0".equals(profile(apk).versionName);
    }

    public static HostAdaptation.Profile[] profiles() {
        HostAdaptation.Profile latest = HostAdaptation.forVersion("41.0.0", 410001L);
        if (latest == null) throw new AssertionError("Missing independently verified 41.0.0 profile");
        return new HostAdaptation.Profile[]{HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408, latest};
    }

    public static List<ZipEntry> dexEntries(ZipFile archive) {
        List<ZipEntry> result = new ArrayList<>();
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().matches("classes(?:[0-9]+)?\\.dex")) result.add(entry);
        }
        return result;
    }

    public static File rulesFile(File apk) throws IOException {
        return assetFile(profile(apk).rulesAsset);
    }

    public static File assetFile(String asset) {
        File file = new File("src/main/assets/" + asset);
        if (!file.isFile()) file = new File("app/src/main/assets/" + asset);
        if (!file.isFile()) throw new AssertionError("Missing host rules asset: " + asset);
        return file;
    }
}
