package com.douai.module.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import androidx.annotation.StringRes;

import com.douai.module.BuildConfig;

import java.util.Locale;

/** Looks up module strings without applying module resource IDs to Douyin's resource table. */
final class ModuleUiStrings {
    private ModuleUiStrings() { }

    static String get(Context context, @StringRes int id, String fallback, Object... args) {
        try {
            Resources resources = context.getResources();
            if (!isModuleResource(resources, id)) {
                resources = context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                        .getResources();
            }
            return args.length == 0 ? resources.getString(id) : resources.getString(id, args);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException | SecurityException unavailable) {
            return args.length == 0 ? fallback : String.format(Locale.getDefault(), fallback, args);
        }
    }

    private static boolean isModuleResource(Resources resources, @StringRes int id) {
        try {
            String owner = resources.getResourcePackageName(id);
            return BuildConfig.APPLICATION_ID.equals(owner) || "com.douai.module".equals(owner);
        } catch (Resources.NotFoundException unavailable) {
            return false;
        }
    }
}
