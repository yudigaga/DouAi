package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
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

/** The same named repository contract is checked against each configured original 40.6/40.8/41.0 APK. */
public final class UserProfileHostContractTest {
    private static final String ROOT = "Lcom/ss/android/ugc/aweme/im/sdk/core/";
    private static final String REPOSITORY = ROOT + "IMUserRepository;";
    private static final String PARAM = ROOT + "UserRequestParam;";
    private static final String BUILDER = ROOT + "UserRequestParam$Builder;";
    private static final String SCENE = ROOT + "UserRequestParam$Scene;";
    private static final String USER = "Lcom/ss/android/ugc/aweme/im/service/model/IMUser;";
    private static final String FUNCTION = "Lkotlin/jvm/functions/Function1;";
    private static final String UNIT = "Lkotlin/Unit;";
    private static final String CACHE = "Lcom/bytedance/im/core/model/cache/IMMemoryCache;";
    private static final String STRING = "Ljava/lang/String;";
    private static final Map<String, ClassDef> TYPES = new HashMap<>();
    private static HostAdaptation.Profile profile;

    @BeforeClass public static void readOriginalHostContract() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(configured);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to an original APK", apk.isFile());
        profile = HostApkTestProfile.profile(apk);
        var wanted = Arrays.asList(REPOSITORY, PARAM, BUILDER, SCENE, USER, FUNCTION, UNIT, CACHE);
        try (ZipFile archive = new ZipFile(apk)) {
            for (var entry : HostApkTestProfile.dexEntries(archive)) {
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (wanted.contains(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
                if (TYPES.size() == wanted.size()) break;
            }
        }
        for (String type : wanted) assertNotNull("Original profile type: " + type, TYPES.get(type));
    }

    @AfterClass public static void clearFacts() { TYPES.clear(); }

    @Test public void builderAndCallbackHaveTheExactNamedContractUsedByTheAdapter() {
        method(BUILDER, "<init>", "V");
        method(BUILDER, "setUid", BUILDER, STRING);
        method(BUILDER, "setSource", BUILDER, STRING);
        method(BUILDER, "setScene", BUILDER, SCENE);
        method(BUILDER, "build", PARAM);
        assertTrue(AccessFlags.STATIC.isSet(field(SCENE, "NET").getAccessFlags()));
        assertEquals(SCENE, field(SCENE, "NET").getType());
        assertEquals(UNIT, field(UNIT, "INSTANCE").getType());
        method(FUNCTION, "invoke", "Ljava/lang/Object;", "Ljava/lang/Object;");
        method(USER, "getUid", STRING);
    }

    @Test public void netRequestStartsAnAsyncJobAndExposesOnlyAnImUserReturn() {
        Method getUser = method(REPOSITORY, "getUser", USER, PARAM, FUNCTION);
        assertTrue(AccessFlags.STATIC.isSet(getUser.getAccessFlags()));
        assertTrue("NET branch must remain present", reads(getUser, SCENE, "NET"));
        assertTrue("Native repository owns the coroutine", calls(getUser,
                "Lkotlinx/coroutines/BuildersKt;", "launch$default"));
        assertFalse("Adapter must use the asynchronous entry", calls(getUser,
                "Lbolts/Task;", "waitForCompletion"));
    }

    @Test public void cacheAndRawIdentityFieldsExistInTheOriginalModel() {
        method(REPOSITORY, "40.6.0".equals(profile.versionName) ? "LJIILL" : "LJIILLIIL", CACHE);
        method(CACHE, "get", "Ljava/lang/Object;", "Ljava/lang/Object;");
        for (String json : new String[]{"nickname", "remark_name", "avatar_thumb", "unique_id", "short_id"}) {
            assertTrue("IMUser raw JSON field " + json, hasJsonField(json));
        }
    }

    private static boolean hasJsonField(String name) {
        for (Field field : TYPES.get(USER).getFields()) {
            for (var annotation : field.getAnnotations()) {
                if (!"Lcom/google/gson/annotations/SerializedName;".equals(annotation.getType())) continue;
                for (var element : annotation.getElements()) {
                    if ("value".equals(element.getName()) && element.getValue() instanceof StringEncodedValue
                            && name.equals(((StringEncodedValue) element.getValue()).getValue())) return true;
                }
            }
        }
        return false;
    }

    private static Field field(String owner, String name) {
        for (Field field : TYPES.get(owner).getFields()) if (name.equals(field.getName())) return field;
        throw new AssertionError("Missing profile field: " + owner + name);
    }

    private static Method method(String owner, String name, String result, String... parameters) {
        for (Method method : TYPES.get(owner).getMethods()) {
            if (name.equals(method.getName()) && result.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        throw new AssertionError("Missing profile method: " + owner + name + Arrays.toString(parameters));
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
