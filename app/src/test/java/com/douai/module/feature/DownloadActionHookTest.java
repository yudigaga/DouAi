package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import com.douai.module.scan.HostAdaptation;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public final class DownloadActionHookTest {
    @Before public void installedLegacyProfile() { HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_406); }
    @After public void resetHostProfile() { HostAdaptation.setCurrentProfile(null); }
    public static final class DownloadModuleFixture {
        Context context;
        View nativeRow;
        final AwemeFixture aweme = new AwemeFixture();
        int nativeFactoryCalls;
        int nativeClickCalls;
        View lastClickedView;

        public String moduleName() { return "LppDownloadModule"; }

        public View LJJIFFI(Context context, ViewGroup parent) {
            nativeFactoryCalls++;
            TextView row = new TextView(context);
            row.setOnClickListener(view -> nativeClickCalls++);
            if (parent != null) parent.addView(row);
            nativeRow = row;
            return row;
        }

        public View LJJI(Context context, ViewGroup parent) { return LJJIFFI(context, parent); }

        public void LJJIJIL(View view) {
            nativeClickCalls++;
            lastClickedView = view;
        }

        public void LJJIJIIJIL(View view) { LJJIJIL(view); }

        public AwemeFixture LJIIJJI() { return aweme; }
    }

    public static final class DownloadModule410Fixture {
        final AwemeFixture aweme = new AwemeFixture();
        int nativeClicks;
        public String moduleName() { return "LppDownloadModule"; }
        public View LJJI(Context context, ViewGroup parent) {
            TextView row = new TextView(context);
            if (parent != null) parent.addView(row);
            return row;
        }
        public void LJJIJIIJIL(View view) { nativeClicks++; }
        public AwemeFixture LJIIJ() { return aweme; }
        public String LJIIJJI() { return "decoy-is-not-an-aweme"; }
    }

    public static final class AwemeFixture {
        public VideoFixture video;
        public List<ImageFixture> images;
        public int getAwemeType() { return 68; }
    }

    public static final class ImageFixture {
        public List<String> url_list;
        public List<String> watermark_free_download_url_list;
        public List<String> download_url_list;
    }

    public static final class VideoFixture {
        public UrlFixture play_addr;
        public UrlFixture play_addr_h264;
        public UrlFixture play_addr_265;
        public UrlFixture download_addr;
        public boolean has_watermark = true;
    }

    public static final class UrlFixture {
        public List<String> url_list;
        UrlFixture(String... values) { url_list = Arrays.asList(values); }
    }

    @Test
    public void playbackSourceDoesNotDependOnWatermarkedDownloadAddress() {
        AwemeFixture aweme = new AwemeFixture();
        aweme.video = new VideoFixture();
        aweme.video.download_addr = new UrlFixture("https://media.example/watermarked.mp4");
        aweme.video.play_addr = new UrlFixture("file:///private/cache", "https://media.example/play.mp4");
        assertEquals("https://media.example/play.mp4", DownloadActionHook.findPlaybackVideoUrl(aweme));
        aweme.video.play_addr = null;
        aweme.video.play_addr_h264 = new UrlFixture("https://media.example/h264.mp4");
        assertEquals("https://media.example/h264.mp4", DownloadActionHook.findPlaybackVideoUrl(aweme));
        aweme.video.play_addr_h264 = null;
        aweme.video.play_addr_265 = new UrlFixture("https://media.example/h265.mp4");
        assertEquals("https://media.example/h265.mp4", DownloadActionHook.findPlaybackVideoUrl(aweme));
        aweme.video.play_addr_265 = null;
        org.junit.Assert.assertNull(DownloadActionHook.findPlaybackVideoUrl(aweme));
    }

    @Test public void galleryUsesCleanListThenDisplaySourceInOriginalOrder() {
        AwemeFixture aweme = new AwemeFixture();
        ImageFixture first = new ImageFixture();
        first.watermark_free_download_url_list = Arrays.asList("https://media.example/one.png");
        first.download_url_list = Arrays.asList("https://media.example/watermarked.jpg");
        ImageFixture second = new ImageFixture();
        second.url_list = Arrays.asList("file:///no", "https://media.example/two.webp");
        aweme.images = Arrays.asList(first, second);
        List<DownloadActionHook.MediaAsset> assets = DownloadActionHook.mediaAssets(aweme, true,
                CommentEnhancementsHookTest.options("download_custom_name", true, "download_name_template", "#author#_#aid#"));
        assertEquals(2, assets.size());
        assertEquals("https://media.example/one.png", assets.get(0).url);
        assertEquals("https://media.example/two.webp", assets.get(1).url);
        assertEquals("01.png", assets.get(0).name);
        assertEquals("02.webp", assets.get(1).name);
        second.url_list = Arrays.asList("file:///no");
        assertTrue(DownloadActionHook.mediaAssets(aweme, true,
                new com.douai.module.config.LocalOptions(java.util.Collections.emptyMap())).isEmpty());
    }

    @Test
    public void disabledMenuLeavesNativeTapAndLongPressUnclaimed() throws Throwable {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        DownloadModuleFixture module = new DownloadModuleFixture();
        DownloadActionHook hook = install(installed, false);

        View row = (View) installed.get().intercept(createChain(module, activity.get()));
        assertEquals(1, module.nativeFactoryCalls);
        assertTrue(row.performClick());
        assertEquals(1, module.nativeClickCalls);
        assertFalse(row.performLongClick());
        hook.close();
        activity.pause().stop().destroy();
    }

    @Test
    public void videoAndGalleryChoicesInvokeTheNativePermissionAwareClick() throws Throwable {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        DownloadModuleFixture module = new DownloadModuleFixture();
        DownloadActionHook hook = install(installed, true);
        View row = (View) installed.get().intercept(createChain(module, activity.get()));

        assertTrue(row.performLongClick());
        clickDialogItem(0);
        assertEquals(1, module.nativeClickCalls);
        assertSame(row, module.lastClickedView);

        assertTrue(row.performLongClick());
        clickDialogItem(0);
        assertEquals(2, module.nativeClickCalls);
        assertSame(row, module.lastClickedView);
        hook.close();
        activity.pause().stop().destroy();
    }

    @Test
    public void sourceDataActionDoesNotInvokeNativeDownloadAndDisablingClosesMenu() throws Throwable {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        DownloadModuleFixture module = new DownloadModuleFixture();
        DownloadActionHook hook = install(installed, true);
        View row = (View) installed.get().intercept(createChain(module, activity.get()));

        assertTrue(row.performLongClick());
        clickDialogItem(1);
        assertEquals(0, module.nativeClickCalls);

        hook.setEnabled(false);
        assertFalse(row.performLongClick());
        assertEquals(0, module.nativeClickCalls);
        hook.close();
        activity.pause().stop().destroy();
    }

    @Test
    public void rejectsUnexpectedFactorySignatureBeforeRegistering() throws Exception {
        AtomicBoolean registered = new AtomicBoolean();
        try {
            DownloadActionHook.install(createFramework(new AtomicReference<>(), registered),
                    method("moduleName"), method("LJJIJIL", View.class), method("LJJIJIL", View.class),
                    true, ignored -> { });
            fail("expected signature validation failure");
        } catch (IllegalArgumentException expected) {
            assertFalse(registered.get());
        }
    }

    @Test public void new9900FactoryUsesTheVerifiedProfileAndRejectsLegacyName() throws Exception {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_408);
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        try (DownloadActionHook ignored = DownloadActionHook.install(createFramework(installed, new AtomicBoolean()),
                method("moduleName"), method("LJJI", Context.class, ViewGroup.class), method("LJJIJIL", View.class), true, value -> { })) {
            assertNotNull(installed.get());
        }
        AtomicBoolean registered = new AtomicBoolean();
        try {
            DownloadActionHook.install(createFramework(new AtomicReference<>(), registered), method("moduleName"),
                    method("LJJIFFI", Context.class, ViewGroup.class), method("LJJIJIL", View.class), true, ignored -> { });
            fail("The 406 factory must not be accepted for the 9900 profile");
        } catch (IllegalArgumentException expected) { assertFalse(registered.get()); }
    }

    @Test public void version410InstallsItsNativeClickAndRejectsTheOlderClickName() throws Exception {
        HostAdaptation.setCurrentProfile(HostAdaptation.forVersion("41.0.0", 410001L));
        assertNotNull(HostAdaptation.currentProfile());
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        try (DownloadActionHook ignored = DownloadActionHook.install(createFramework(installed, new AtomicBoolean()),
                method("moduleName"), method("LJJI", Context.class, ViewGroup.class),
                method("LJJIJIIJIL", View.class), true, value -> { })) {
            assertNotNull(installed.get());
        }
        AtomicBoolean registered = new AtomicBoolean();
        try {
            DownloadActionHook.install(createFramework(new AtomicReference<>(), registered), method("moduleName"),
                    method("LJJI", Context.class, ViewGroup.class), method("LJJIJIL", View.class), true, ignored -> { });
            fail("The older native click must not be accepted for 41.0.0");
        } catch (IllegalArgumentException expected) { assertFalse(registered.get()); }
    }

    @Test public void version410MenuReadsAwemeFromTheProfiledGetterWhenTheOldNameReturnsAString() throws Throwable {
        HostAdaptation.setCurrentProfile(HostAdaptation.forVersion("41.0.0", 410001L));
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        DownloadModule410Fixture module = new DownloadModule410Fixture();
        ImageFixture image = new ImageFixture();
        image.url_list = List.of("https://media.example/410.jpg");
        module.aweme.images = List.of(image);
        try (DownloadActionHook hook = DownloadActionHook.install(createFramework(installed, new AtomicBoolean()),
                method410("moduleName"), method410("LJJI", Context.class, ViewGroup.class),
                method410("LJJIJIIJIL", View.class), true, ignored -> { })) {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, args) -> {
                        if ("getThisObject".equals(method.getName())) return module;
                        if ("getArg".equals(method.getName())) return args[0].equals(0) ? new View(activity.get()) : null;
                        if ("proceed".equals(method.getName())) return module.LJJI(activity.get(), new FrameLayout(activity.get()));
                        throw new UnsupportedOperationException(method.getName());
                    });
            View row = (View) installed.get().intercept(chain);
            assertNotNull(row);
            assertTrue(row.performLongClick());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            boolean hasGallery = false;
            for (int index = 0; index < dialog.getListView().getAdapter().getCount(); index++) {
                if ("保存图集".contentEquals(dialog.getListView().getAdapter().getItem(index).toString())) {
                    hasGallery = true;
                }
            }
            assertTrue("The 410 getter must expose the gallery actions", hasGallery);
        } finally { activity.pause().stop().destroy(); }
    }

    @Test public void unverifiedProfileCannotInstallNativeDownloadFactory() throws Exception {
        HostAdaptation.setCurrentProfile(null); AtomicBoolean registered = new AtomicBoolean();
        try {
            DownloadActionHook.install(createFramework(new AtomicReference<>(), registered), method("moduleName"),
                    method("LJJIFFI", Context.class, ViewGroup.class), method("LJJIJIL", View.class), true, ignored -> { });
            fail("Original host profile is required");
        } catch (IllegalArgumentException expected) { assertFalse(registered.get()); }
    }

    @Test public void nativeMenuMethodsMatchTheSelectedOriginalHostDex() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        org.junit.Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        java.io.File apk = new java.io.File(configured);
        assertTrue(apk.isFile());
        boolean legacy = com.douai.module.scan.HostApkTestProfile.is406(apk);
        boolean latest = com.douai.module.scan.HostApkTestProfile.is410(apk);
        String awemeOwner = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
        java.util.Set<String> required = new java.util.HashSet<>(Arrays.asList("moduleName()Ljava/lang/String;",
                (legacy ? "LJJIFFI" : "LJJI") + "(Landroid/content/Context;Landroid/view/ViewGroup;)Landroid/view/View;",
                (latest ? "LJJIJIIJIL" : "LJJIJIL") + "(Landroid/view/View;)V"));
        String awemeGetter = (latest ? "LJIIJ" : "LJIIJJI") + "()" + awemeOwner;
        java.util.Set<String> parentRequired = new java.util.HashSet<>(Arrays.asList(awemeGetter));
        if (latest) parentRequired.add("LJIIJJI()Ljava/lang/String;");
        String owner = "Lcom/ss/android/ugc/aweme/feed/long_press_panel/modules/business/homepage/LppDownloadModule;";
        boolean found = false;
        boolean parentFound = false;
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && (!found || !parentFound)) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (java.io.BufferedInputStream input = new java.io.BufferedInputStream(archive.getInputStream(entry))) {
                    com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile dex = com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(com.android.tools.smali.dexlib2.Opcodes.getDefault(), input);
                    for (com.android.tools.smali.dexlib2.iface.ClassDef type : dex.getClasses()) {
                        if (owner.equals(type.getType())) found = true;
                        else if ("Lcom/ss/android/ugc/aweme/feed/long_press_panel/modules/LongPressPanelAbsModule;"
                                .equals(type.getType())) parentFound = true;
                        else continue;
                        java.util.Set<String> expected = owner.equals(type.getType()) ? required : parentRequired;
                        for (com.android.tools.smali.dexlib2.iface.Method method : type.getMethods()) {
                            StringBuilder signature = new StringBuilder(method.getName()).append('(');
                            for (CharSequence parameter : method.getParameterTypes()) signature.append(parameter);
                            signature.append(')').append(method.getReturnType());
                            if (!expected.contains(signature.toString())) continue;
                            assertTrue(com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
                            assertFalse(com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(method.getAccessFlags()));
                            expected.remove(signature.toString());
                        }
                    }
                }
            }
        }
        assertTrue("Missing original download module", found);
        assertTrue("Missing original download methods: " + required, required.isEmpty());
        assertTrue("Missing inherited original Aweme accessors: " + parentRequired, parentFound && parentRequired.isEmpty());
    }

    @Test public void thirdPartyModeExposesOnlyItsDownloadActionAndDoesNotFallbackToOfficial() throws Throwable {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        AtomicReference<XposedInterface.Hooker> installed = new AtomicReference<>();
        DownloadModuleFixture module = new DownloadModuleFixture();
        try (DownloadActionHook hook = install(installed, true)) {
            hook.setOptions(CommentEnhancementsHookTest.options("download_method", "third_party"));
            View row = (View) installed.get().intercept(createChain(module, activity.get()));
            assertTrue(row.performLongClick());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals(2, dialog.getListView().getAdapter().getCount());
            assertEquals("第三方下载", dialog.getListView().getAdapter().getItem(0));
            clickDialogItem(0);
            assertEquals(0, module.nativeClickCalls);
        } finally { activity.pause().stop().destroy(); }
    }

    private static DownloadActionHook install(AtomicReference<XposedInterface.Hooker> installed,
                                              boolean enabled) throws Exception {
        return DownloadActionHook.install(createFramework(installed, new AtomicBoolean()),
                method("moduleName"), method("LJJIFFI", Context.class, ViewGroup.class),
                method("LJJIJIL", View.class), enabled, ignored -> { });
    }

    private static void clickDialogItem(int index) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getListView().performItemClick(null, index, index);
    }

    private static Method method(String name, Class<?>... parameterTypes) {
        try {
            return DownloadModuleFixture.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Method method410(String name, Class<?>... parameterTypes) {
        try {
            return DownloadModule410Fixture.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException failure) { throw new AssertionError(failure); }
    }

    private static XposedInterface createFramework(AtomicReference<XposedInterface.Hooker> installed,
                                                   AtomicBoolean registered) {
        AtomicReference<Method> target = new AtomicReference<>();
        XposedInterface.HookHandle handle = (XposedInterface.HookHandle) Proxy.newProxyInstance(
                XposedInterface.HookHandle.class.getClassLoader(),
                new Class<?>[]{XposedInterface.HookHandle.class},
                (proxy, method, args) -> null);
        AtomicReference<XposedInterface.HookBuilder> builder = new AtomicReference<>();
        builder.set((XposedInterface.HookBuilder) Proxy.newProxyInstance(
                XposedInterface.HookBuilder.class.getClassLoader(),
                new Class<?>[]{XposedInterface.HookBuilder.class},
                (proxy, method, args) -> {
                    if ("intercept".equals(method.getName())) {
                        registered.set(true);
                        if ("LJJIFFI".equals(target.get().getName()) || "LJJI".equals(target.get().getName())) installed.set((XposedInterface.Hooker) args[0]);
                        return handle;
                    }
                    return builder.get();
                }));
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if ("hook".equals(method.getName())) {
                        target.set((Method) args[0]);
                        return builder.get();
                    }
                    return null;
                });
    }

    private static XposedInterface.Chain createChain(DownloadModuleFixture module, Context context) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("getThisObject".equals(method.getName())) return module;
                    if ("proceed".equals(method.getName())) {
                        return module.LJJIFFI(context, new FrameLayout(context));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
