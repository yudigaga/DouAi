package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original 41.0 APK facts keep profile numbers separate from captions and layout state. */
public final class ProfileSpoofHostContractTest {
    private static final String USER = "Lcom/ss/android/ugc/aweme/profile/model/User;";
    private static final String AREA = "Lcom/ss/android/ugc/aweme/profile/panda/PandaHeaderValueArea;";
    private static final String FOLLOWER_HELPER = "LX/1EJP;";
    private static final String TEXT = "Landroid/widget/TextView;";
    private static final Map<String, ClassDef> TYPES = new HashMap<>();

    @BeforeClass public static void readModernOriginalFacts() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(configured);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        Assume.assumeTrue("This contract covers the 41.0 profile layout",
                HostApkTestProfile.profile(apk) == HostAdaptation.PROFILE_410);
        try (ZipFile archive = new ZipFile(apk)) {
            for (var entry : HostApkTestProfile.dexEntries(archive)) {
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (AREA.equals(type.getType()) || FOLLOWER_HELPER.equals(type.getType())) {
                            TYPES.put(type.getType(), ImmutableClassDef.of(type));
                        }
                    }
                }
                if (TYPES.size() == 2) break;
            }
        }
        assertNotNull("Original profile header", TYPES.get(AREA));
        assertNotNull("Original follower helper", TYPES.get(FOLLOWER_HELPER));
    }

    @AfterClass public static void clearFacts() { TYPES.clear(); }

    @Test public void profileNumbersAreTextViewsWhileTheOldMutualFieldIsState() {
        for (String name : new String[]{"x", "v", "w", "E", "y"}) {
            assertEquals(name, TEXT, field(name).getType());
        }
        assertEquals("F is layout state, never the mutual count TextView", "LX/1EMy;", field("F").getType());
    }

    @Test public void countFieldsFollowTheHostLikesFollowingFollowersAndMutualSources() {
        Method likes = method(AREA, "LJJJLZIJ", "V", "J");
        assertTrue(reads(likes, USER, "totalFavoritedStr"));
        assertDisplays(likes, "x");

        Method render = method(AREA, "LJJIJIIJI", "V", USER);
        assertTrue(calls(render, USER, "getFollowingCount"));
        assertDisplays(render, "v");

        Method followers = method(AREA, "LJJJLIIL", "V", USER);
        assertTrue(calls(followers, FOLLOWER_HELPER, "LJIIJJI"));
        assertDisplays(followers, "w");
        assertTrue(calls(method(FOLLOWER_HELPER, "LJIIJJI", "I", USER), USER, "getFollowerCount"));

        Method mutual = method(AREA, "LJLI", "V", "I", "Ljava/lang/String;", "Z");
        assertDisplays(mutual, "E");
    }

    private static void assertDisplays(Method method, String field) {
        assertTrue(method.getName() + " reads count field " + field, reads(method, AREA, field));
        assertTrue(method.getName() + " writes the displayed count", calls(method, TEXT, "setText"));
    }

    private static Field field(String name) {
        for (Field field : TYPES.get(AREA).getFields()) if (name.equals(field.getName())) return field;
        throw new AssertionError("Missing profile field: " + name);
    }

    private static Method method(String owner, String name, String result, String... parameters) {
        for (Method method : TYPES.get(owner).getMethods()) {
            if (name.equals(method.getName()) && result.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))
                    && method.getImplementation() != null) return method;
        }
        throw new AssertionError("Missing profile method: " + owner + name);
    }

    private static boolean reads(Method method, String owner, String name) {
        for (var instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof FieldReference) {
                FieldReference field = (FieldReference) reference;
                if (owner.equals(field.getDefiningClass()) && name.equals(field.getName())) return true;
            }
        }
        return false;
    }

    private static boolean calls(Method method, String owner, String name) {
        for (var instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof MethodReference) {
                MethodReference called = (MethodReference) reference;
                if (owner.equals(called.getDefiningClass()) && name.equals(called.getName())) return true;
            }
        }
        return false;
    }
}
