package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Static contracts from the original host APK, independent of scanner fingerprints. */
public final class NativeChatHostContractTest {
    private static final String ROOT_FACTORY = "LX/0YVw;";
    private static final String DRAWER_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/"
            + "input/drawer/new/InputDrawerUI2;";
    private static final String TITLE_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/"
            + "title/component/ChatTitleUI;";
    private static final String TEXT_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/"
            + "common/content/root/text/TextOnlyCellUI;";
    private static final String TEXT_WIDGET = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/"
            + "vh/textd/IMMsgTextView;";
    private static final String AVATAR_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/"
            + "common/basic/AvatarCellUI;";
    private static final String BUBBLE_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/"
            + "common/bg/NewBgContentCellUI;";
    private static final String PROFILE_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/"
            + "vh/strangerbigcard/StrangerTopAvatarCellUI;";
    private static final String LEAVE_FACTORY = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/"
            + "title/component/LeaveChatUI;";
    private static final String AUTO_RTL = "Lcom/ss/android/ugc/aweme/im/sdk/widget/AutoRTLImageView;";
    private static final String ACCOUNT_PROXY = "Lcom/ss/android/ugc/aweme/account/AccountProxyService;";
    private static final String ACCOUNT_USER_SERVICE = "Lcom/ss/android/ugc/aweme/account/service/IAccountUserService;";
    private static final String USER = "Lcom/ss/android/ugc/aweme/profile/model/User;";
    private static final String FRESCO_HELPER = "Lcom/ss/android/ugc/aweme/im/sdk/common/ImFrescoHelper;";
    private static final String ALBUM_ACTIVITY = "Lcom/ss/android/ugc/aweme/im/business/mediaselectpage/choose/MediaChooseActivity;";
    private static final String ALBUM_PARAMETERS = "LX/1UOy;";
    private static final String ALBUM_RESULT = "Lcom/ss/android/ugc/aweme/im/business/mediaselectpage/choose/model/MediaChooseResult;";
    private static final String ALBUM_MODEL = "Lcom/ss/android/ugc/aweme/im/service/model/MediaModel;";
    private static File apk;
    private static HostAdaptation.Profile profile;
    private static final Map<String, ClassDef> types = new HashMap<>();

    @BeforeClass public static void readOriginalHostDex() throws Exception {
        types.clear();
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", path != null);
        apk = new File(path);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        profile = HostApkTestProfile.profile(apk);
        Set<String> required = new HashSet<>(Arrays.asList(ROOT_FACTORY, DRAWER_FACTORY, TITLE_FACTORY,
                TEXT_FACTORY, TEXT_WIDGET, AVATAR_FACTORY, BUBBLE_FACTORY, PROFILE_FACTORY, LEAVE_FACTORY,
                AUTO_RTL, ACCOUNT_PROXY, ACCOUNT_USER_SERVICE, USER, FRESCO_HELPER, "LX/0uok;", "LX/0XZk;",
                ALBUM_ACTIVITY, ALBUM_PARAMETERS, ALBUM_RESULT, ALBUM_MODEL, "LX/1U1r;", "LX/1U1O;",
                "LX/1U1z;", "LX/0vUA;",
                "LX/0vK4;", "LX/0Yjd;",
                "LX/0YVa;", "LX/0vMA;", "LX/0vM9;", "LX/0vJL;", "LX/0uuG;"));
        Set<String> actualRequired = new HashSet<>();
        for (String descriptor : required) actualRequired.add(hostDescriptor(descriptor));
        required = actualRequired;
        try (ZipFile archive = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && !required.isEmpty()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (required.remove(type.getType())) {
                            // DexBackedClassDef retains its entire DEX byte buffer. Copy
                            // only the selected class so scanning later DEX files and the
                            // rest of the full suite do not retain many host archives.
                            types.put(type.getType(), ImmutableClassDef.of(type));
                        }
                    }
                }
            }
        }
        assertTrue("Missing native chat host classes: " + required, required.isEmpty());
    }

    @AfterClass public static void releaseOriginalHostFacts() {
        types.clear();
        apk = null;
        profile = null;
    }

    @Test public void rootAndInputFactoriesHaveCallableStaticSignatures() {
        Method root = method(ROOT_FACTORY, "LIZ", "I");
        assertTrue(AccessFlags.PUBLIC.isSet(root.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(root.getAccessFlags()));
        assertTrue("Root factory must retain all three host experiment layouts", literals(root).containsAll(
                Arrays.asList(rid(0x7f0d194a), rid(0x7f0d194b), rid(0x7f0d194c))));

        Method input = method(DRAWER_FACTORY, HostResourceProfile.chatMethod(profile, "LJLJJLL"),
                "Landroidx/constraintlayout/widget/ConstraintLayout;", "Landroid/content/Context;");
        assertTrue(AccessFlags.PUBLIC.isSet(input.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(input.getAccessFlags()));
        assertTrue("Official input factory must still create the expected root, action slots and editor",
                literals(input).containsAll(Arrays.asList(rid(0x7f0a577c), rid(0x7f0a6a1e), rid(0x7f0a9dcc), rid(0x7f0a7d27))));
    }

    @Test public void titleTextAndBackgroundFactoriesRetainTheirReflectionContract() {
        assertCallable(TITLE_FACTORY, HostResourceProfile.chatMethod(profile, "LJLIIL"), "Lcom/bytedance/ies/dmt/ui/widget/DmtTextView;",
                true, "I", "Landroid/content/Context;");
        assertCallable(TEXT_FACTORY, "<init>", "V", false);
        Method textFactory = assertCallable(TEXT_FACTORY, HostResourceProfile.chatMethod(profile, "LJJJJL"), "Landroid/view/View;", false,
                "Landroid/content/Context;", "Landroid/view/ViewGroup;");
        assertTrue("Text cell must retain its IMMsgTextView ID", literals(textFactory).contains(rid(0x7f0a1dbf)));
        assertCallable(TEXT_WIDGET, "setText", "V", false, "Ljava/lang/CharSequence;");
        assertCallable(TEXT_WIDGET, "setTextColor", "V", false, "I");
        assertCallable(TEXT_WIDGET, "setMaxWidthPx", "V", false, "I");
        assertCallable(TEXT_WIDGET, "setTextSizePx", "V", false, "F");
        assertCallable("LX/0YVa;", "LIZ", "Landroid/graphics/drawable/Drawable;", true,
                "Landroid/content/Context;");
        assertCallable("LX/0vJL;", "LIZIZ", "I", true);
        assertCallable("LX/0vK4;", "LIZ", "I", true, "Landroid/content/Context;", "I", "Z");
        assertCallable("LX/0Yjd;", "LJIIJ", "Z", true);
        assertCallable("LX/0uuG;", "LJII", "V", true, "Landroid/graphics/drawable/GradientDrawable;");
        assertCallable("LX/0vMA;", "LIZ", "LX/0vM9;", true, "Z");
        ClassDef background = type("LX/0vM9;");
        boolean resourceField = false;
        for (Field field : background.getFields()) {
            if ("LIZ".equals(field.getName()) && "I".equals(field.getType())) {
                assertTrue("Bubble resource field must be public", AccessFlags.PUBLIC.isSet(field.getAccessFlags()));
                assertTrue("Bubble resource field must be per config", !AccessFlags.STATIC.isSet(field.getAccessFlags()));
                resourceField = true;
            }
        }
        assertTrue("Missing X.0vM9.LIZ:I native bubble resource field", resourceField);
    }

    @Test public void everyRootLayoutRetainsTypedHeaderMessageAndInputSlots() throws Exception {
        try (ZipFile archive = new ZipFile(apk)) {
            for (String path : HostResourceProfile.legacy(profile)
                    ? new String[]{"r/zw5.xml", "r/zw6.xml", "r/zw7.xml"}
                    : profile.versionCode == 410001L ? new String[]{"r/z3u.xml", "r/z3v.xml", "r/z3w.xml"}
                    : new String[]{"r/zy5.xml", "r/zy6.xml", "r/zy7.xml"}) {
                ZipEntry entry = archive.getEntry(path);
                assertNotNull("Missing original host root layout " + path, entry);
                byte[] xml;
                try (InputStream input = archive.getInputStream(entry)) { xml = input.readAllBytes(); }
                Map<Integer, String> elements = elementsById(xml);
                assertEquals(path + " root", "FrameLayout", elements.get(rid(0x7f0a2042)));
                assertEquals(path + " main column", "LinearLayout", elements.get(rid(0x7f0a2063)));
                assertEquals(path + " title slot", "FrameLayout", elements.get(rid(0x7f0a4258)));
                assertEquals(path + " message slot", "FrameLayout", elements.get(rid(0x7f0a202d)));
                assertEquals(path + " input panel slot", "FrameLayout", elements.get(rid(0x7f0a6444)));
            }
        }
    }

    @Test public void avatarProfileShellAndNavigationFactoriesHaveIndependentViewContracts() {
        assertCallable(LEAVE_FACTORY, HostResourceProfile.chatMethod(profile, "LJLIIL"), "V", true,
                "Landroid/content/Context;", "Landroid/widget/ImageView;");
        assertCallable(AUTO_RTL, "<init>", "V", false,
                "Landroid/content/Context;", "Landroid/util/AttributeSet;");
        assertCallable(AVATAR_FACTORY, "<init>", "V", false);
        Method avatar = assertCallable(AVATAR_FACTORY, HostResourceProfile.viewFactory(profile), "Landroid/view/View;", false,
                "Landroid/content/Context;", "Landroid/view/ViewGroup;");
        assertNotNull(avatar.getImplementation());
        assertCallable(AVATAR_FACTORY, "getLayoutParams", "Landroid/view/ViewGroup$LayoutParams;", false);
        assertPublicInstanceField(AVATAR_FACTORY, "q", "Lcom/ss/android/ugc/exview/ImAvatarImageView;");
        assertCallable(BUBBLE_FACTORY, "<init>", "V", false);
        assertCallable(BUBBLE_FACTORY, HostResourceProfile.chatMethod(profile, "LJJJIL"), "Landroid/graphics/Rect;", false);
        assertCallable(PROFILE_FACTORY, "<init>", "V", false);
        Method profileView = assertCallable(PROFILE_FACTORY, HostResourceProfile.viewFactory(profile), "Landroid/view/View;", false,
                "Landroid/content/Context;", "Landroid/view/ViewGroup;");
        assertTrue("Profile view factory must retain its native layout", literals(profileView).contains(rid(0x7f0d1b57)));
        assertCallable("LX/0uok;", "LIZJ", "Landroid/widget/FrameLayout;", true,
                "Landroid/content/Context;");
        assertCallable("LX/0XZk;", "LIZIZ", "Landroidx/constraintlayout/widget/ConstraintLayout;", true,
                "Landroid/view/ViewGroup;");
    }

    @Test public void currentAccountAvatarUsesThePublicCachedUserFieldAndHostImageLoader() {
        assertCallable(ACCOUNT_PROXY, "userService", ACCOUNT_USER_SERVICE, true);
        assertCallable(ACCOUNT_USER_SERVICE, "getCurUser", USER, false);
        assertPublicInstanceField(USER, "avatarThumb", "Lcom/ss/android/ugc/aweme/base/model/UrlModel;");
        assertCallable(FRESCO_HELPER, HostResourceProfile.avatarLoader(profile), "V", true,
                "Lcom/ss/android/ugc/aweme/base/ui/AvatarImageView;",
                "Lcom/ss/android/ugc/aweme/base/model/UrlModel;", "Z", "I", "Ljava/lang/String;", "I");
    }

    @Test public void nativeProfileLayoutRetainsTheOfficialLargeAvatarAndNameWidgets() throws Exception {
        try (ZipFile archive = new ZipFile(apk)) {
            ZipEntry entry = archive.getEntry(HostResourceProfile.legacy(profile) ? "r/z9=.xml"
                    : profile.versionCode == 410001L ? "r/0c3.xml" : "r/z_=.xml");
            assertNotNull("Missing original host profile layout", entry);
            byte[] xml;
            try (InputStream input = archive.getInputStream(entry)) { xml = input.readAllBytes(); }
            Map<Integer, String> elements = elementsById(xml);
            assertEquals("com.ss.android.ugc.aweme.base.ui.AvatarImageView", elements.get(rid(0x7f0a10e6)));
            assertEquals("com.bytedance.ies.dmt.ui.widget.DmtTextView", elements.get(rid(0x7f0a8189)));
        }
    }

    @Test public void nativeAlbumHasFreshSerializableLocalReturnParameters() {
        assertCallable(ALBUM_PARAMETERS, "<init>", "V", false, "I");
        assertTrue(type(ALBUM_PARAMETERS).getInterfaces().contains("Ljava/io/Serializable;"));
        assertPublicInstanceField(ALBUM_PARAMETERS, "a", "I");
        for (String field : new String[]{"b", "h", "m"}) assertPublicInstanceField(ALBUM_PARAMETERS, field, "Z");
        for (String field : new String[]{"e", "f"}) assertPublicInstanceField(ALBUM_PARAMETERS, field, "I");
        assertPublicInstanceField(ALBUM_PARAMETERS, "g", "Ljava/lang/String;");
        assertPublicInstanceField(ALBUM_PARAMETERS, "l", "LX/0vUA;");
        assertPublicInstanceField(ALBUM_PARAMETERS, "n", "J");
        assertPublicInstanceField("LX/0vUA;", profile.versionCode == 410001L ? "h" : "g", "Z");
        Method constructor = method(ALBUM_PARAMETERS, "<init>", "V", "I");
        assertFalse("A fresh album parameter object must not manufacture a native direct-send config",
                referencesField(constructor, ALBUM_PARAMETERS, "l"));
        assertTrue("The host media mask must remain a bitmask",
                method(ALBUM_PARAMETERS, "LIZIZ", "Z", "I").getImplementation() != null);
        Method mediaKind = assertCallable("LX/1U1O;", "LJI", "LX/1U2g;", true, ALBUM_PARAMETERS);
        assertTrue("Native photos/video masks must retain 0x1/0x10", literals(mediaKind).containsAll(Arrays.asList(1, 16)));
        assertTrue(referencesField(mediaKind, "LX/1U2g;", "OnlyVideos"));
        assertTrue(referencesField(mediaKind, "LX/1U2g;", "OnlyPhotos"));
    }

    @Test public void nativeAlbumLauncherAndActivityConsumeTheSameIntentParameters() {
        assertEquals("Lcom/ss/android/ugc/aweme/im/activity/BaseImAmeActivity;",
                type(ALBUM_ACTIVITY).getSuperclass());
        assertTrue(AccessFlags.PUBLIC.isSet(type(ALBUM_ACTIVITY).getAccessFlags()));
        Method launcher = assertCallable("LX/1U1r;", "LIZ", "V", true,
                "Landroid/app/Activity;", ALBUM_PARAMETERS, "I");
        assertTrue(strings(launcher).containsAll(Arrays.asList("extra_choose_parameters", "picure_choose_origin")));
        assertTrue("The native legacy request code is 2001; our registry deliberately allocates its own code",
                literals(launcher).contains(2001));
        boolean explicitActivity = false;
        for (Instruction instruction : instructions(launcher)) {
            Object reference = reference(instruction);
            if (reference instanceof TypeReference && ALBUM_ACTIVITY.equals(((TypeReference) reference).getType())) {
                explicitActivity = true;
            }
        }
        assertTrue("The official launcher must still explicitly target MediaChooseActivity", explicitActivity);
        Method read = assertCallable("LX/1U1z;", "LJIIIZ", "V", false);
        assertTrue(strings(read).contains("extra_choose_parameters"));
        assertTrue(referencesField(read,
                "Lcom/ss/android/ugc/aweme/im/business/mediaselectpage/choose/viewmodel/MediaChooseViewModel2;", "g"));
    }

    @Test public void nativeAlbumActivityIsRegisteredInTheOriginalManifest() throws Exception {
        try (ZipFile archive = new ZipFile(apk)) {
            byte[] xml;
            try (InputStream input = archive.getInputStream(archive.getEntry("AndroidManifest.xml"))) {
                xml = input.readAllBytes();
            }
            assertTrue("The explicit native album component must be an installed host activity",
                    activityNames(xml).contains("com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.MediaChooseActivity"));
        }
    }

    @Test public void nativeAlbumUses2012ConfirmationAnd2011CancellationWithMetadataResults() {
        Method complete = assertCallable(ALBUM_ACTIVITY, completionMethod(), "V", false,
                "Z", "Z", ALBUM_RESULT, "Ljava/lang/String;", "Z");
        assertTrue(literals(complete).containsAll(Arrays.asList(2011, 2012)));
        assertTrue(strings(complete).contains("extra_choose_result"));
        assertTrue(calls(complete, "Landroid/app/Activity;", "setResult"));
        Method cancel = assertCallable(ALBUM_ACTIVITY, "onBackPressed", "V", false);
        assertTrue(literals(cancel).contains(2011));
        assertTrue(calls(cancel, "Landroid/app/Activity;", "setResult"));
        assertTrue(type(ALBUM_RESULT).getInterfaces().contains("Ljava/io/Serializable;"));
        assertCallable(ALBUM_RESULT, "getSelectedList", "Ljava/util/ArrayList;", false);
        assertCallable(ALBUM_MODEL, "getFilePath", "Ljava/lang/String;", false);
        assertCallable(ALBUM_MODEL, HostResourceProfile.mediaSourceGetter(profile), "Ljava/lang/String;", false);
        for (String field : new String[]{"type", "width", "height"}) assertPublicInstanceField(ALBUM_MODEL, field, "I");
        for (String field : new String[]{"duration", "fileSize"}) assertPublicInstanceField(ALBUM_MODEL, field, "J");
        assertPublicInstanceField(ALBUM_MODEL, "mimeType", "Ljava/lang/String;");
        assertPublicInstanceField(ALBUM_MODEL, "edited", "Z");
        Method video = assertCallable(ALBUM_MODEL, HostResourceProfile.legacy(profile) ? "q2"
                : profile.versionCode == 410001L ? "r2" : "s2", "Z", false);
        assertTrue("Native video classification must still read MediaModel.type", referencesField(video, ALBUM_MODEL, "type"));
        assertTrue(literals(video).contains(1));
    }

    @Test public void nativeAlbumDirectSendIsGuardedByAnExplicitOptionalConfig() {
        Method complete = method(ALBUM_ACTIVITY, completionMethod(), "V",
                "Z", "Z", ALBUM_RESULT, "Ljava/lang/String;", "Z");
        List<Instruction> code = instructions(complete);
        int send = -1;
        int optionalConfig = -1;
        for (int i = 0; i < code.size(); i++) {
            Object reference = reference(code.get(i));
            if (reference instanceof FieldReference && hostDescriptor(ALBUM_PARAMETERS).equals(((FieldReference) reference).getDefiningClass())
                    && "l".equals(((FieldReference) reference).getName()) && code.get(i).getOpcode() == Opcode.IGET_OBJECT) {
                optionalConfig = i;
            }
            if (reference instanceof MethodReference && hostDescriptor("LX/0vTt;").equals(((MethodReference) reference).getDefiningClass())
                    && "LJFF".equals(((MethodReference) reference).getName())) {
                send = i;
                break;
            }
        }
        assertTrue("The inspected completion method must include the native optional send path", send > 0);
        assertTrue("The native send path must first read the nullable params.l config", optionalConfig >= 0 && optionalConfig < send);
        assertTrue("Native direct sending must also read its explicit enable flag",
                referencesField(complete, "LX/0vUA;", profile.versionCode == 410001L ? "h" : "g"));
        int configRegister = ((TwoRegisterInstruction) code.get(optionalConfig)).getRegisterA();
        int falseFlagRegister = -1;
        for (int i = optionalConfig + 1; i < send; i++) {
            Instruction instruction = code.get(i);
            if (instruction.getOpcode() != Opcode.IF_EQZ
                    || ((OneRegisterInstruction) instruction).getRegisterA() != configRegister) continue;
            int target = branchTarget(code, i);
            if (target <= send || !(code.get(target) instanceof NarrowLiteralInstruction)) continue;
            if (((NarrowLiteralInstruction) code.get(target)).getNarrowLiteral() == 0) {
                falseFlagRegister = ((OneRegisterInstruction) code.get(target)).getRegisterA();
                break;
            }
        }
        assertTrue("A null direct-send config must branch to a false send flag", falseFlagRegister >= 0);
        boolean falseBypassesSend = false;
        for (int i = optionalConfig + 1; i < send; i++) {
            Instruction instruction = code.get(i);
            if (instruction.getOpcode() == Opcode.IF_EQZ
                    && ((OneRegisterInstruction) instruction).getRegisterA() == falseFlagRegister
                    && branchTarget(code, i) > send) falseBypassesSend = true;
        }
        assertTrue("The false direct-send flag must skip the actual native sender invocation", falseBypassesSend);
    }

    @Test public void scopedStorageSourceUrisRemainSeparateFromPotentialCachePaths() {
        Method source = assertCallable(ALBUM_MODEL, HostResourceProfile.mediaSourceGetter(profile), "Ljava/lang/String;", false);
        Method destination = assertCallable(ALBUM_MODEL, "getFilePath", "Ljava/lang/String;", false);
        assertTrue(referencesField(source, ALBUM_MODEL, "relativePath"));
        assertTrue(referencesField(destination, ALBUM_MODEL, "filePath"));
        Method conversion = assertCallable("LX/1U1O;", "LJII", ALBUM_MODEL, true, "LX/1UWt;");
        assertTrue("The native media conversion must retain original-source metadata",
                calls(conversion, ALBUM_MODEL, HostResourceProfile.legacy(profile) ? "LJJJJL"
                        : profile.versionCode == 410001L ? "LJJJJI" : "LJJJJJL"));
        assertTrue("The native media conversion can independently assign a cache destination",
                calls(conversion, ALBUM_MODEL, profile.versionCode == 410001L ? "LJJIJIL" : "LJJIJLIJ"));
        assertTrue("The native scoped-storage guard must still distinguish content URIs", strings(conversion).contains("content"));
    }

    private static List<Instruction> instructions(Method method) {
        assertNotNull(method.getImplementation());
        List<Instruction> result = new ArrayList<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) result.add(instruction);
        return result;
    }

    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static boolean referencesField(Method method, String owner, String name) {
        for (Instruction instruction : instructions(method)) {
            Object reference = reference(instruction);
            if (reference instanceof FieldReference && hostDescriptor(owner).equals(((FieldReference) reference).getDefiningClass())
                    && name.equals(((FieldReference) reference).getName())) return true;
        }
        return false;
    }

    private static boolean calls(Method method, String owner, String name) {
        for (Instruction instruction : instructions(method)) {
            Object reference = reference(instruction);
            if (reference instanceof MethodReference && hostDescriptor(owner).equals(((MethodReference) reference).getDefiningClass())
                    && name.equals(((MethodReference) reference).getName())) return true;
        }
        return false;
    }

    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction instruction : instructions(method)) {
            Object reference = reference(instruction);
            if (reference instanceof StringReference) result.add(((StringReference) reference).getString());
        }
        return result;
    }

    private static int branchTarget(List<Instruction> code, int branch) {
        int address = 0;
        for (int i = 0; i < branch; i++) address += code.get(i).getCodeUnits();
        int targetAddress = address + ((OffsetInstruction) code.get(branch)).getCodeOffset();
        address = 0;
        for (int i = 0; i < code.size(); i++) {
            if (address == targetAddress) return i;
            address += code.get(i).getCodeUnits();
        }
        fail("Native branch has an invalid target address " + targetAddress);
        return -1;
    }

    private static Set<String> activityNames(byte[] data) {
        ByteBuffer xml = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String[] strings = null;
        int[] resources = null;
        Set<String> result = new HashSet<>();
        for (int offset = u16(xml, 2), limit = xml.getInt(4); offset + 8 <= limit; ) {
            int type = u16(xml, offset);
            int header = u16(xml, offset + 2);
            int size = xml.getInt(offset + 4);
            assertTrue(size >= header && header >= 8 && offset + size <= limit);
            if (type == 0x0001) strings = readStrings(xml, offset, header);
            else if (type == 0x0180) {
                resources = new int[(size - header) / 4];
                for (int i = 0; i < resources.length; i++) resources[i] = xml.getInt(offset + header + i * 4);
            } else if (type == 0x0102 && strings != null && resources != null
                    && "activity".equals(strings[xml.getInt(offset + 20)])) {
                int attributes = offset + 16 + u16(xml, offset + 24);
                int attributeSize = u16(xml, offset + 26);
                for (int i = 0, count = u16(xml, offset + 28); i < count; i++) {
                    int attribute = attributes + i * attributeSize;
                    int name = xml.getInt(attribute + 4);
                    if (name < resources.length && resources[name] == 0x01010003
                            && (data[attribute + 15] & 0xff) == 0x03) {
                        String value = strings[xml.getInt(attribute + 16)];
                        result.add(value.startsWith(".") ? "com.ss.android.ugc.aweme" + value : value);
                    }
                }
            }
            offset += size;
        }
        return result;
    }

    private static int rid(int canonicalId) { return HostResourceProfile.id(profile, canonicalId); }

    private static String completionMethod() {
        return HostResourceProfile.legacy(profile) || profile.versionCode == 410001L ? "LLLLZIL" : "LLLLLZIL";
    }

    private static ClassDef type(String descriptor) { return types.get(hostDescriptor(descriptor)); }

    private static String hostDescriptor(String descriptor) {
        if (descriptor.startsWith("LX/") && descriptor.endsWith(";")) {
            return "L" + HostResourceProfile.className(profile,
                    descriptor.substring(1, descriptor.length() - 1).replace('/', '.')).replace('.', '/') + ";";
        }
        return descriptor;
    }

    private static Method method(String descriptor, String name, String returns, String... parameters) {
        ClassDef type = type(descriptor);
        assertNotNull(descriptor, type);
        List<String> actualParameters = new ArrayList<>();
        for (String parameter : parameters) actualParameters.add(hostDescriptor(parameter));
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && hostDescriptor(returns).equals(method.getReturnType())
                    && method.getParameterTypes().equals(actualParameters)) return method;
        }
        fail("Missing " + descriptor + "->" + name + Arrays.toString(parameters) + returns);
        return null;
    }

    private static Method assertCallable(String descriptor, String name, String returns,
                                         boolean isStatic, String... parameters) {
        Method method = method(descriptor, name, returns, parameters);
        assertTrue(descriptor + "->" + name + " must be public", AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
        assertEquals(descriptor + "->" + name + " static flag", isStatic,
                AccessFlags.STATIC.isSet(method.getAccessFlags()));
        return method;
    }

    private static void assertPublicInstanceField(String descriptor, String name, String fieldType) {
        ClassDef type = type(descriptor);
        assertNotNull(descriptor, type);
        for (Field field : type.getFields()) {
            if (name.equals(field.getName()) && hostDescriptor(fieldType).equals(field.getType())) {
                assertTrue(descriptor + "." + name + " must be public", AccessFlags.PUBLIC.isSet(field.getAccessFlags()));
                assertTrue(descriptor + "." + name + " must be an instance field",
                        !AccessFlags.STATIC.isSet(field.getAccessFlags()));
                return;
            }
        }
        fail("Missing public field " + descriptor + "." + name + ":" + fieldType);
    }

    private static Set<Integer> literals(Method method) {
        assertNotNull("Expected a concrete factory " + method.getName(), method.getImplementation());
        Set<Integer> result = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof NarrowLiteralInstruction) {
                result.add(((NarrowLiteralInstruction) instruction).getNarrowLiteral());
            }
        }
        return result;
    }

    /** Reads only the string pool, resource map and start tags from Android binary XML. */
    private static Map<Integer, String> elementsById(byte[] data) {
        ByteBuffer xml = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("Android binary XML header", 0x0003, u16(xml, 0));
        String[] strings = null;
        int[] resources = null;
        Map<Integer, String> result = new LinkedHashMap<>();
        int limit = xml.getInt(4);
        assertTrue(limit <= data.length);
        for (int offset = u16(xml, 2); offset + 8 <= limit; ) {
            int type = u16(xml, offset);
            int header = u16(xml, offset + 2);
            int size = xml.getInt(offset + 4);
            assertTrue("Invalid XML chunk", size >= header && header >= 8 && offset + size <= limit);
            if (type == 0x0001) {
                strings = readStrings(xml, offset, header);
            } else if (type == 0x0180) {
                resources = new int[(size - header) / 4];
                for (int i = 0; i < resources.length; i++) resources[i] = xml.getInt(offset + header + i * 4);
            } else if (type == 0x0102) {
                assertNotNull("XML strings must precede elements", strings);
                assertNotNull("XML resource map must precede attributes", resources);
                String element = strings[xml.getInt(offset + 20)];
                int attributes = offset + 16 + u16(xml, offset + 24);
                int attributeSize = u16(xml, offset + 26);
                int count = u16(xml, offset + 28);
                for (int i = 0; i < count; i++) {
                    int attribute = attributes + i * attributeSize;
                    int name = xml.getInt(attribute + 4);
                    if (name < resources.length && resources[name] == 0x010100d0) {
                        assertEquals("android:id must be a resource reference", 0x01,
                                data[attribute + 15] & 0xff);
                        result.put(xml.getInt(attribute + 16), element);
                    }
                }
            }
            offset += size;
        }
        return result;
    }

    private static String[] readStrings(ByteBuffer xml, int chunk, int header) {
        int count = xml.getInt(chunk + 8);
        int flags = xml.getInt(chunk + 16);
        int start = chunk + xml.getInt(chunk + 20);
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            int offset = start + xml.getInt(chunk + header + i * 4);
            if ((flags & 0x100) != 0) {
                int[] chars = length8(xml, offset);
                int[] bytes = length8(xml, chars[1]);
                byte[] value = new byte[bytes[0]];
                for (int j = 0; j < value.length; j++) value[j] = xml.get(bytes[1] + j);
                result[i] = new String(value, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                int length = u16(xml, offset);
                offset += 2;
                if ((length & 0x8000) != 0) {
                    length = ((length & 0x7fff) << 16) | u16(xml, offset);
                    offset += 2;
                }
                char[] value = new char[length];
                for (int j = 0; j < length; j++) value[j] = (char) u16(xml, offset + j * 2);
                result[i] = new String(value);
            }
        }
        return result;
    }

    private static int[] length8(ByteBuffer buffer, int offset) {
        int value = buffer.get(offset++) & 0xff;
        if ((value & 0x80) != 0) value = ((value & 0x7f) << 8) | (buffer.get(offset++) & 0xff);
        return new int[]{value, offset};
    }

    private static int u16(ByteBuffer buffer, int offset) { return buffer.getShort(offset) & 0xffff; }
}
