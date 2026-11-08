package com.douai.module.ui;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;
import com.douai.module.scan.BinaryHostManifestFixture;
import com.douai.module.scan.HostApkProfile;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

/** Test fixtures select the same original installed profile as the production bridge. */
public final class HostProfileFixture {
    private HostProfileFixture() { }
    public static HostAdaptation.Profile profile(Context context) {
        HostAdaptation.Profile profile = HostAdaptation.currentProfile();
        if (profile != null) return profile;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
            profile = HostApkProfile.read(new java.io.File(info.sourceDir));
            if (profile == null) throw new AssertionError("Fixture requires a supported original host profile");
            return profile;
        } catch (android.content.pm.PackageManager.NameNotFoundException | java.io.IOException failure) { throw new AssertionError(failure); }
    }
    public static void attachOriginalApk(PackageInfo info) {
        try {
            ApplicationInfo application = new ApplicationInfo(info.applicationInfo);
            application.sourceDir = BinaryHostManifestFixture.apk(info.packageName, info.versionName,
                    (int) info.getLongVersionCode(), "40.6.0".equals(info.versionName) ? 40609900
                            : "41.0.0".equals(info.versionName) ? 41009900 : 40809900).getAbsolutePath();
            application.publicSourceDir = application.sourceDir;
            info.applicationInfo = application;
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    public static int id(Context context, int canonical) { return HostResourceProfile.id(profile(context), canonical); }
}
