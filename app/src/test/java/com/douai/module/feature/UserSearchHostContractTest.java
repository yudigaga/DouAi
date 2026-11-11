package com.douai.module.feature;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.douai.module.scan.HostApkTestProfile;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Reads original DEX only; no host classes are instantiated and no requests are sent. */
public final class UserSearchHostContractTest {
    private static final String ROOT = "Lcom/ss/android/ugc/aweme/";
    private static final String API = ROOT + "discover/api/SearchApi$RealApi;";
    private static final String BASE = ROOT + "search/op/standard/AbstractSearchRequest;";
    private static final String REQUEST = ROOT + "search/op/standard/SearchUserListRequest;";
    private static final String WRAPPER = ROOT + "discover/model/SearchUser;";
    private static final String USER = ROOT + "profile/model/User;";
    private static final String PATCH = ROOT + "discover/mixfeed/DynamicPatch;";
    private static final String GSON_PROVIDER = ROOT + "utils/GsonProvider;";
    private static final String GSON_COMPANION = ROOT + "utils/GsonProvider$Companion;";
    private static final String LOG = ROOT + "feed/model/LogPbBean;";
    private static final String USER_MODULE = ROOT + "discover/ui/feedcontainer/modules/page/module/request/UserRequestParamsModule;";
    private static final String COMMON_MODULE = ROOT + "discover/ui/feedcontainer/modules/page/module/request/CommonRequestParamsModule;";
    private static final String STRING = "Ljava/lang/String;", MAP = "Ljava/util/Map;";
    private static final String RX = "Lio/reactivex/Observable;", GSON = "Lcom/google/gson/Gson;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static String response, responseBase, builder, channelGetter;

    @BeforeClass public static void readTheVerifiedOriginalHost() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue(apk.isFile());
        String version = HostApkTestProfile.profile(apk).versionName;
        builder = "40.6.0".equals(version) ? "LX/0fqT;" : "40.8.0".equals(version) ? "LX/0jA7;" : "LX/0exj;";
        channelGetter = "40.6.0".equals(version) ? "e" : "40.8.0".equals(version) ? "LLLLZIL" : "LLLZI";
        load(apk, new HashSet<>(Arrays.asList(API, BASE, REQUEST, WRAPPER, USER, PATCH,
                GSON_PROVIDER, GSON_COMPANION, GSON, LOG, builder, RX, ROOT + "base/api/BaseResponse;",
                ROOT + "framework/services/IRetrofitService;", ROOT + "framework/services/IRetrofit;", ROOT + "app/api/Api;",
                USER_MODULE, COMMON_MODULE)));
        Matcher match = Pattern.compile("Lio/reactivex/Observable<(LX/[^;]+;)>;").matcher(
                annotation(method(API, "LJJIJL", RX, MAP).getAnnotations(), "Ldalvik/annotation/Signature;"));
        assertTrue("Missing native response signature", match.find());
        response = match.group(1);
        load(apk, new HashSet<>(Arrays.asList(response)));
        responseBase = classes.get(response).getSuperclass();
        load(apk, new HashSet<>(Arrays.asList(responseBase)));
    }

    @AfterClass public static void releaseDex() { classes.clear(); }

    @Test public void aUniqueNativeObservableMapMethodOwnsTheReviewedUserEndpoint() {
        int matches = 0;
        for (Method candidate : classes.get(API).getMethods()) {
            if (!RX.equals(candidate.getReturnType()) || !candidate.getParameterTypes().equals(Arrays.asList(MAP))) continue;
            if (!"/aweme/v1/discover/search/".equals(annotation(candidate.getAnnotations(), "Lretrofit2/http/POST;"))) continue;
            matches++;
            assertEquals("LJJIJL", candidate.getName());
            assertEquals("", annotation(candidate.getAnnotations(), "Lretrofit2/http/FormUrlEncoded;"));
            assertEquals("", annotation(candidate.getParameters().get(0).getAnnotations(), "Lretrofit2/http/FieldMap;"));
        }
        assertEquals(1, matches);
    }

    @Test public void typedRequestAndDynamicMapsPreserveKeywordAndOverlayContinuation() {
        assertEquals(BASE, classes.get(REQUEST).getSuperclass());
        Method constructor = method(REQUEST, "<init>", "V", STRING);
        assertTrue(strings(constructor).containsAll(Arrays.asList("cursor", "type", "user")));
        assertTrue(calls(constructor, BASE, "<init>"));
        assertTrue(strings(method(BASE, "<init>", "V", STRING)).containsAll(
                Arrays.asList("keyword", "count", "search_channel", "search_id")));
        method(BASE, "LJIILJJIL", "V", "I");
        method(BASE, "LJJII", "V", STRING);
        method(BASE, "LJ", MAP);
        Method values = method(BASE, "LJJIIZ", MAP);
        assertTrue(reads(values, BASE, "LJJII", MAP));
        assertTrue(calls(values, "Ljava/util/HashMap;", "putAll"));
        field(BASE, "LJJII", MAP, null);
        String module = ROOT + "discover/ui/feedcontainer/modules/page/module/request/UserRequestParamsModule;";
        boolean nativeUserSource = false;
        for (Method candidate : classes.get(module).getMethods()) nativeUserSource |= strings(candidate).contains("user_search");
        assertTrue(nativeUserSource);
        boolean nativeAssembly = false;
        for (Method candidate : classes.get(builder).getMethods()) {
            nativeAssembly |= candidate.getReturnType().equals(MAP)
                    && strings(candidate).containsAll(Arrays.asList("search", "douyin_search", "token", "vlm_config", "1"))
                    && calls(candidate, BASE, "LJJIIZ") && calls(candidate, BASE, "LJ") && calls(candidate, BASE, "LJJIII");
        }
        assertTrue("Missing reviewed native search-scene and token assembly", nativeAssembly);
    }

    @Test public void nativeResponseCarriesUserWrappersAndCursorAndLogSearchId() {
        field(response, "cursor", "I", "cursor");
        field(response, "hasMore", "Z", "has_more");
        Field list = field(response, "userList", "Ljava/util/List;", "user_list");
        assertTrue(annotation(list.getAnnotations(), "Ldalvik/annotation/Signature;").contains(WRAPPER));
        field(responseBase, "logPb", LOG, "log_pb");
        field(LOG, "imprId", STRING, "impr_id");
        assertEquals(ROOT + "base/api/BaseResponse;", classes.get(responseBase).getSuperclass());
        field(ROOT + "base/api/BaseResponse;", "status_code", "I", null);
        field(ROOT + "base/api/BaseResponse;", "error_code", "I", null);
        field(WRAPPER, "a", USER, "user_info");
    }

    @Test public void nativeRequestNiceTypeDoesNotReplaceTheExplicitSearchChannel() {
        Method constructor = method(REQUEST, "<init>", "V", STRING);
        assertTrue(calls(constructor, BASE, "LJIJJLI"));
        assertFalse("The native constructor does not populate search_channel", calls(constructor, BASE, "LJJIFFI"));
        assertTrue(readsNamed(method(BASE, "LJIJJLI", "V", STRING), BASE, "LIZIZ"));
        assertTrue(readsNamed(method(BASE, "LJJIFFI", "V", STRING), BASE, "LJIIL"));
        Method baseConstructor = method(BASE, "<init>", "V", STRING);
        assertTrue(initializesAfterKey(baseConstructor, "nice_search_type", BASE, "LIZIZ"));
        assertTrue(initializesAfterKey(baseConstructor, "search_channel", BASE, "LJIIL"));
        assertEquals(new HashSet<>(Arrays.asList("")), strings(method(USER_MODULE, channelGetter, STRING)));
        boolean nativePageChannel = false;
        for (Method candidate : classes.get(COMMON_MODULE).getMethods()) {
            nativePageChannel |= channelResultReachesSetter(candidate);
        }
        assertTrue("The native page passes the user's empty channel rather than nice_search_type", nativePageChannel);
    }

    @Test public void rawUserIdentityAndRelationshipFlagsHaveTheReviewedNativeTypes() {
        field(USER, "uid", STRING, "uid");
        field(USER, "secUid", STRING, "sec_uid");
        field(USER, "nickname", STRING, "nickname");
        field(USER, "avatarThumb", ROOT + "base/model/UrlModel;", "avatar_thumb");
        field(USER, "uniqueId", STRING, "unique_id");
        field(USER, "shortId", STRING, "short_id");
        field(USER, "followStatus", "I", "follow_status");
        field(USER, "followerStatus", "I", "follower_status");
    }

    @Test public void dynamicPatchCanUseTheStableHostGsonProvider() {
        field(WRAPPER, "n", ROOT + "search/model/SearchDynamicPatch;", "dynamic_patch");
        field(PATCH, "rawData", STRING, "raw_data");
        Field companion = field(GSON_PROVIDER, "Companion", GSON_COMPANION, null);
        assertTrue(AccessFlags.STATIC.isSet(companion.getAccessFlags()));
        method(GSON_COMPANION, "get", GSON_PROVIDER);
        method(GSON_PROVIDER, "getGson", GSON);
        method(GSON, "fromJson", "Ljava/lang/Object;", STRING, "Ljava/lang/Class;");
    }

    @Test public void nativeRetrofitCreationAndBoundedRxWaitKeepTheirReviewedSignatures() {
        String retrofit = ROOT + "framework/services/IRetrofit;";
        method(ROOT + "framework/services/IRetrofitService;", "createNewRetrofit", retrofit, STRING);
        method(retrofit, "create", "Ljava/lang/Object;", "Ljava/lang/Class;");
        Field prefix = field(ROOT + "app/api/Api;", "API_URL_PREFIX_SI", STRING, null);
        assertTrue(AccessFlags.STATIC.isSet(prefix.getAccessFlags()));
        method(RX, "timeout", RX, "J", "Ljava/util/concurrent/TimeUnit;");
        method(RX, "blockingFirst", "Ljava/lang/Object;");
    }

    private static void load(File apk, Set<String> wanted) throws Exception {
        try (ZipFile archive = new ZipFile(apk)) {
            for (ZipEntry entry : HostApkTestProfile.dexEntries(archive)) {
                if (wanted.isEmpty()) break;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (wanted.remove(type.getType())) classes.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
            }
        }
        assertTrue("Missing original DEX classes " + wanted, wanted.isEmpty());
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        for (Method method : classes.get(owner).getMethods()) {
            if (method.getName().equals(name) && method.getReturnType().equals(returns)
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) {
                assertTrue(AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
                return method;
            }
        }
        throw new AssertionError("Missing original method " + owner + "->" + name);
    }

    private static Field field(String owner, String name, String type, String jsonName) {
        for (Field field : classes.get(owner).getFields()) {
            if (field.getName().equals(name) && field.getType().equals(type)) {
                assertTrue(AccessFlags.PUBLIC.isSet(field.getAccessFlags()));
                if (jsonName != null) assertEquals(jsonName, annotation(field.getAnnotations(), "Lcom/google/gson/annotations/SerializedName;"));
                return field;
            }
        }
        throw new AssertionError("Missing original field " + owner + "->" + name);
    }

    private static String annotation(Set<? extends Annotation> values, String type) {
        for (Annotation annotation : values) {
            if (!type.equals(annotation.getType())) continue;
            for (var element : annotation.getElements()) if (element.getName().equals("value")) return value(element.getValue());
            return "";
        }
        return null;
    }

    private static String value(EncodedValue value) {
        if (value instanceof StringEncodedValue) return ((StringEncodedValue) value).getValue();
        if (value instanceof ArrayEncodedValue) {
            StringBuilder text = new StringBuilder();
            for (EncodedValue item : ((ArrayEncodedValue) value).getValue()) text.append(value(item));
            return text.toString();
        }
        throw new AssertionError("Unexpected annotation type");
    }

    private static Set<String> strings(Method method) {
        Set<String> strings = new HashSet<>();
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof StringReference)
                strings.add(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString());
        }
        return strings;
    }

    private static boolean calls(Method method, String owner, String name) {
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
                MethodReference reference = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                if (reference.getDefiningClass().equals(owner) && reference.getName().equals(name)) return true;
            }
        }
        return false;
    }

    private static boolean reads(Method method, String owner, String name, String type) {
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference) {
                FieldReference reference = (FieldReference) ((ReferenceInstruction) instruction).getReference();
                if (reference.getDefiningClass().equals(owner) && reference.getName().equals(name) && reference.getType().equals(type)) return true;
            }
        }
        return false;
    }

    private static boolean readsNamed(Method method, String owner, String name) {
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference) {
                FieldReference reference = (FieldReference) ((ReferenceInstruction) instruction).getReference();
                if (reference.getDefiningClass().equals(owner) && reference.getName().equals(name)) return true;
            }
        }
        return false;
    }

    private static boolean initializesAfterKey(Method method, String key, String owner, String field) {
        String latestKey = null;
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            var reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof StringReference) latestKey = ((StringReference) reference).getString();
            else if (reference instanceof FieldReference) {
                FieldReference target = (FieldReference) reference;
                if (key.equals(latestKey) && target.getDefiningClass().equals(owner) && target.getName().equals(field)
                        && instruction.getOpcode().name().startsWith("IPUT")) return true;
            }
        }
        return false;
    }

    private static boolean channelResultReachesSetter(Method method) {
        boolean getterCalled = false;
        int resultRegister = -1;
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (getterCalled && instruction.getOpcode() == Opcode.MOVE_RESULT_OBJECT) {
                resultRegister = ((OneRegisterInstruction) instruction).getRegisterA();
            } else if (instruction instanceof ReferenceInstruction
                    && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
                MethodReference call = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                if (getterCalled && call.getDefiningClass().equals(BASE) && call.getName().equals("LJJIFFI")
                        && instruction instanceof FiveRegisterInstruction && resultRegister >= 0
                        && ((FiveRegisterInstruction) instruction).getRegisterD() == resultRegister) return true;
                getterCalled = call.getDefiningClass().equals(COMMON_MODULE) && call.getName().equals(channelGetter)
                        && call.getParameterTypes().isEmpty() && call.getReturnType().equals(STRING);
                resultRegister = -1;
            }
        }
        return false;
    }
}
