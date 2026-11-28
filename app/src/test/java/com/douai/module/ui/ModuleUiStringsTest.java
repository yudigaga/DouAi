package com.douai.module.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.douai.module.BuildConfig;
import com.douai.module.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ModuleUiStringsTest {
    @Test public void hostResourceCollisionDoesNotReplaceModuleCommandText() {
        Context module = RuntimeEnvironment.getApplication();
        Resources host = new Resources(module.getAssets(), module.getResources().getDisplayMetrics(),
                module.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) { return "com.ss.android.ugc.aweme"; }
            @Override public String getString(int id, Object... args) {
                throw new AssertionError("A module resource ID must not be read from the host table");
            }
        };
        Context injected = new ContextWrapper(module) {
            @Override public Resources getResources() { return host; }
            @Override public Context createPackageContext(String packageName, int flags) {
                assertEquals(BuildConfig.APPLICATION_ID, packageName);
                return module;
            }
        };
        assertEquals("/技能 ", ModuleUiStrings.get(injected, R.string.ai_chat_slash_command_input, "fallback", "/技能"));
    }

    @Test public void missingModuleResourcesKeepTheFormattedFallback() {
        Context injected = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Resources getResources() { return Resources.getSystem(); }
            @Override public Context createPackageContext(String packageName, int flags)
                    throws PackageManager.NameNotFoundException {
                throw new PackageManager.NameNotFoundException(packageName);
            }
        };
        assertEquals("已发现 2 个工具",
                ModuleUiStrings.get(injected, R.string.mcp_tools_discovered, "已发现 %1$d 个工具", 2));
    }
}
