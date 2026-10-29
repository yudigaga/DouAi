package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Optional packaged-artifact checks, run with DOUAI_VERIFY_RELEASE_APK after building Release. */
public final class ApkArtifactContractTest {
    @Test public void releaseMetadataAndHostRulesMatchTheirContracts() throws Exception {
        try (ZipFile release = archive("DOUAI_VERIFY_RELEASE_APK")) {
            assertEquals("com.douai.module.DouAiModule",
                    new String(read(release, "META-INF/xposed/java_init.list"), StandardCharsets.UTF_8).trim());
            assertEquals("com.ss.android.ugc.aweme",
                    new String(read(release, "META-INF/xposed/scope.list"), StandardCharsets.UTF_8).trim());
            Properties metadata = new Properties();
            metadata.load(new ByteArrayInputStream(read(release, "META-INF/xposed/module.prop")));
            assertEquals("102", metadata.getProperty("minApiVersion"));
            assertEquals("102", metadata.getProperty("targetApiVersion"));
            assertEquals("true", metadata.getProperty("staticScope"));
            assertEquals(3, metadata.size());

            for (HostAdaptation.Profile profile : new HostAdaptation.Profile[]{
                    HostAdaptation.PROFILE_406, HostAdaptation.PROFILE_408}) {
                File source = HostApkTestProfile.assetFile(profile.rulesAsset);
                byte[] packagedRules = read(release, "assets/" + profile.rulesAsset);
                assertArrayEquals(profile.versionName + " packaged host rules differ from source",
                        Files.readAllBytes(source.toPath()), packagedRules);
                HostRuleSet rules = HostRuleSet.parse(new ByteArrayInputStream(packagedRules));
                assertEquals(HostRuleSet.CURRENT_FORMAT_VERSION, rules.getFormatVersion());
                assertEquals("com.ss.android.ugc.aweme", rules.getTargetPackage());
                assertFalse(rules.getRules().isEmpty());
                assertEquals(profile.versionName + " rules must retain their own profile fingerprint",
                        profile.fingerprint, rules.getFingerprint());
            }
            assertNotNull(release.getEntry("resources.arsc"));
            assertNotNull(release.getEntry("AndroidManifest.xml"));
        }
    }

    @Test public void releaseModuleEntryAndReflectionContractsSurviveR8() throws Exception {
        DexFacts release = dexFacts("DOUAI_VERIFY_RELEASE_APK");
        String entry = "Lcom/douai/module/DouAiModule;";
        assertNotNull(entry, release.classes.get(entry));
        assertEquals("Lio/github/libxposed/api/XposedModule;", release.classes.get(entry).getSuperclass());
        for (String callback : new String[]{"ModuleLoaded", "PackageLoaded", "PackageReady"}) {
            assertMethod(release, entry, "on" + callback
                    + "(Lio/github/libxposed/api/XposedModuleInterface$" + callback + "Param;)V",
                    AccessFlags.PUBLIC.getValue(), false);
        }
        for (String activity : new String[]{"Lcom/douai/module/MainActivity;",
                "Lcom/douai/module/ui/HostRestartActivity;"}) {
            assertMethod(release, activity, "onCreate(Landroid/os/Bundle;)V",
                    AccessFlags.PROTECTED.getValue() | AccessFlags.PUBLIC.getValue(), false);
        }
        assertExtends(release, "Lcom/douai/module/MainActivity;", "Landroid/app/Activity;");
        assertEquals("Landroid/app/Activity;",
                release.classes.get("Lcom/douai/module/ui/HostRestartActivity;").getSuperclass());
        String context = "Lcom/douai/module/ui/miuix/HostComposeContext;";
        assertMethod(release, context, "getClassLoader()Ljava/lang/ClassLoader;",
                AccessFlags.PUBLIC.getValue(), false);
        assertEquals("Landroid/content/ContextWrapper;", release.classes.get(context).getSuperclass());
        assertMethod(release, context, "getResources()Landroid/content/res/Resources;",
                AccessFlags.PUBLIC.getValue(), false);
        assertMethod(release, context, "getAssets()Landroid/content/res/AssetManager;",
                AccessFlags.PUBLIC.getValue(), false);
        assertMethod(release, context, "getSystemService(Ljava/lang/String;)Ljava/lang/Object;",
                AccessFlags.PUBLIC.getValue(), false);
        assertMethod(release, context, "create(Landroid/app/Activity;)" + context,
                AccessFlags.PUBLIC.getValue(), true);
        for (String owner : release.classes.keySet()) {
            assertFalse("compileOnly API packaged: " + owner, owner.startsWith("Lio/github/libxposed/api/"));
        }
    }

    @Test public void releaseHostReflectionNamesAndScannerOpcodeTextSurviveR8() throws Exception {
        DexFacts release = dexFacts("DOUAI_VERIFY_RELEASE_APK");
        assertMethod(release, "Lcom/douai/module/config/AiModelSettings;",
                "sameAutomationSelection(Lcom/douai/module/config/LocalOptions;Lcom/douai/module/config/LocalOptions;Ljava/lang/String;)Z",
                0, true);
        for (String marker : new String[]{"跟随全局默认", "自动回复（私聊、群聊、评论回复）", "请先选择模型配置"}) {
            assertTrue("Missing per-feature model selection marker: " + marker, release.strings.contains(marker));
        }
        assertFalse("Model management must not include activation controls", release.strings.contains("ai.activate."));
        assertFalse("Saving a model must not activate it", release.strings.contains("保存并启用"));
        for (String marker : new String[]{"module_only_speed", "仅模块控制倍速", "倍速锁定目标：", "已选择正常播放：1x"}) {
            assertTrue("Missing module speed control marker: " + marker, release.strings.contains(marker));
        }
        assertFalse("The old retry toast would contradict a retained speed choice",
                release.strings.contains("倍速尚未确认生效，请重试"));
        assertMethod(release, "Lcom/douai/module/feature/HostPlaybackSpeedAccess;",
                "readEffective(Ljava/lang/Object;)F", 0, false);
        assertMethod(release, "Lcom/douai/module/feature/GestureSpeedOverride;",
                "registerGlobal()V", 0, false);
        assertMethod(release, "Lcom/douai/module/feature/GestureSpeedOverride;",
                "captureScope()Lcom/douai/module/feature/GestureSpeedOverride$ScopeToken;", 0, true);
        assertMethod(release, "Lcom/douai/module/feature/EnginePlaybackSpeedHooks;",
                "close()V", 0, false);
        assertMethod(release, "Lcom/douai/module/feature/EnginePlaybackSpeedHooks;",
                "deoptimizeSdkCallers(Lio/github/libxposed/api/XposedInterface;Lcom/douai/module/scan/HostScanReport;Ljava/lang/ClassLoader;)V",
                0, false);
        assertMethod(release, "Lcom/douai/module/feature/GestureSpeedOverride;",
                "restorationSpeed(Ljava/lang/Object;F)F", 0, false);
        assertMethod(release, "Lcom/douai/module/feature/SpeedGestureNativeGate;",
                "hold(Landroid/view/View;Landroid/view/MotionEvent;)Lcom/douai/module/feature/SpeedGestureNativeGate$Lease;",
                0, false);
        for (String operation : new String[]{"replayDown", "discard"}) {
            assertMethod(release, "Lcom/douai/module/feature/SpeedGestureNativeGate$Lease;",
                    operation + "()V", 0, false);
        }
        assertMethod(release, "Lcom/douai/module/feature/CommentReplyHook;",
                "generationFailureDiagnostic(Ljava/lang/Exception;)Ljava/lang/String;", 0, true);
        assertMethod(release, "Lcom/douai/module/feature/CommentReplyHook$SharedReplyGenerator;",
                "<init>(Ljava/util/function/Consumer;)V", 0, false);
        for (String marker : new String[]{"Comment reply AI request sent: images=", "Comment reply generated: chars=",
                "video_source_unavailable"}) {
            assertTrue("Missing comment reply diagnostic: " + marker, release.strings.contains(marker));
        }
        assertNotNull("Native AI chat bridge was removed by R8",
                release.classes.get("Lcom/douai/module/ui/HostNativeChatViews;"));
        assertNotNull("Native album bridge was removed by R8",
                release.classes.get("Lcom/douai/module/ui/HostMediaPicker;"));
        assertNotNull("Global incoming-message mapper was removed by R8",
                release.classes.get("Lcom/douai/module/feature/IncomingMessageSnapshot;"));
        for (String type : release.classes.keySet()) {
            assertFalse("A host test fixture was packaged: " + type,
                    type.startsWith("Lcom/ss/android/ugc/aweme/") || type.startsWith("LX/"));
        }
        for (String value : new String[]{"androidx.activity.result.ActivityResultCallback",
                "androidx.activity.result.ActivityResultRegistry",
                "androidx.activity.result.ActivityResultLauncher",
                "androidx.activity.result.ActivityResult",
                "androidx.activity.result.contract.ActivityResultContract",
                "androidx.activity.result.contract.ActivityResultContracts$OpenDocument",
                "androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult",
                "com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.MediaChooseActivity",
                "com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.model.MediaChooseResult",
                "com.ss.android.ugc.aweme.im.service.model.MediaModel", "X.1Cd0",
                "extra_choose_parameters", "extra_choose_result", "getSelectedList", "getFilePath", "LJIILJJIL",
                "getActivityResultRegistry", "onActivityResult", "getResultCode", "getData",
                "register", "launch", "unregister", "com.google.gson.Gson",
                "const-string", "invoke-static"}) {
            assertTrue("Missing runtime string: " + value, release.strings.contains(value));
        }
    }

    private static void assertExtends(DexFacts facts, String type, String frameworkBase) {
        Set<String> visited = new HashSet<>();
        String current = type;
        while (!frameworkBase.equals(current)) {
            assertTrue(type + " has a cyclic superclass chain at " + current, visited.add(current));
            ClassDef owner = facts.classes.get(current);
            assertNotNull(type + " has an unavailable superclass: " + current, owner);
            current = owner.getSuperclass();
            assertNotNull(type + " does not extend " + frameworkBase, current);
        }
    }

    @Test public void releaseHiddenSkipUsesTheHostsKotlinUnitNameAndSingleton() throws Exception {
        DexFacts release = dexFacts("DOUAI_VERIFY_RELEASE_APK");
        assertMethodString(release, "Lcom/douai/module/feature/HostBehaviorHooks;", "installHiddenSkip", "kotlin.Unit");
        ClassDef unit = release.classes.get("Lkotlin/Unit;");
        assertNotNull("Keep the class name so R8 cannot adapt host-loader literals", unit);
        boolean instance = false;
        for (com.android.tools.smali.dexlib2.iface.Field field : unit.getFields()) {
            if (field.getName().equals("INSTANCE") && field.getType().equals("Lkotlin/Unit;")) instance = true;
        }
        assertTrue("Reflective Kotlin Unit singleton must retain INSTANCE", instance);
    }

    @Test public void releaseLikeSignatureUsesTheHostsFunction2Name() throws Exception {
        DexFacts release = dexFacts("DOUAI_VERIFY_RELEASE_APK");
        assertMethodString(release, "Lcom/douai/module/feature/AutoLikeHook;", "install", "kotlin.jvm.functions.Function2");
    }

    private static void assertMethodString(DexFacts facts, String owner, String name, String expected) {
        ClassDef type = facts.classes.get(owner);
        assertNotNull(owner, type);
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getImplementation() == null) continue;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction
                        && ((ReferenceInstruction) instruction).getReference() instanceof StringReference
                        && expected.equals(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString())) return;
            }
        }
        fail(owner + "." + name + " lost host-loader string " + expected);
    }

    private static void assertMethod(DexFacts facts, String type, String signature,
                                     int visibility, boolean isStatic) {
        ClassDef owner = facts.classes.get(type);
        assertNotNull(type, owner);
        for (Method method : owner.getMethods()) {
            StringBuilder descriptor = new StringBuilder(method.getName()).append('(');
            for (CharSequence parameter : method.getParameterTypes()) descriptor.append(parameter);
            descriptor.append(')').append(method.getReturnType());
            if (!signature.contentEquals(descriptor)) continue;
            // Zero requests no visibility constraint, e.g. package-private diagnostic helpers.
            if (visibility != 0) assertTrue(type + " visibility: " + signature, (method.getAccessFlags() & visibility) != 0);
            assertEquals(type + " static: " + signature, isStatic,
                    AccessFlags.STATIC.isSet(method.getAccessFlags()));
            return;
        }
        fail(type + " missing " + signature);
    }

    private static DexFacts dexFacts(String variable) throws Exception {
        DexFacts facts = new DexFacts();
        try (ZipFile archive = archive(variable)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(),
                        new ByteArrayInputStream(read(archive, entry.getName())));
                for (ClassDef owner : dex.getClasses()) {
                    facts.classes.put(owner.getType(), owner);
                    for (com.android.tools.smali.dexlib2.iface.Field field : owner.getFields()) {
                        if (field.getInitialValue() instanceof com.android.tools.smali.dexlib2.iface.value.StringEncodedValue) {
                            facts.strings.add(((com.android.tools.smali.dexlib2.iface.value.StringEncodedValue)
                                    field.getInitialValue()).getValue());
                        }
                    }
                    for (Method method : owner.getMethods()) {
                        if (method.getImplementation() == null) continue;
                        for (Instruction instruction : method.getImplementation().getInstructions()) {
                            if (instruction instanceof ReferenceInstruction
                                    && ((ReferenceInstruction) instruction).getReference() instanceof StringReference) {
                                facts.strings.add(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString());
                            }
                        }
                    }
                }
            }
        }
        assertFalse("No DEX classes", facts.classes.isEmpty());
        return facts;
    }

    private static ZipFile archive(String variable) throws Exception {
        String path = System.getenv(variable);
        Assume.assumeTrue(variable + " is not configured", path != null);
        assertFalse(variable + " must not be blank", path.trim().isEmpty());
        File file = new File(path);
        assertTrue(variable + " must point to a built APK: " + path, file.isFile());
        assertTrue(variable + " must be readable: " + path, file.canRead());
        return new ZipFile(file);
    }

    private static byte[] read(ZipFile archive, String name) throws Exception {
        assertNotNull(name, archive.getEntry(name));
        try (java.io.InputStream input = archive.getInputStream(archive.getEntry(name))) { return input.readAllBytes(); }
    }

    private static final class DexFacts {
        final Map<String, ClassDef> classes = new HashMap<>();
        final Set<String> strings = new HashSet<>();
    }
}
