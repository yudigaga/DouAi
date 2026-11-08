package com.douai.module.feature;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.AnnotationElement;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostApkTestProfile;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original 41.0.0 APK contracts; these checks never instantiate the host or send requests. */
public final class HostDouyinSearchContractTest {
    private static final String STRING = "Ljava/lang/String;";
    private static final String MAP = "Ljava/util/Map;";
    private static final String LIST = "Ljava/util/List;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String REQUEST = "Lcom/ss/android/ugc/aweme/search/op/standard/SearchVideoListRequest;";
    private static final String BASE_REQUEST = "Lcom/ss/android/ugc/aweme/search/op/standard/AbstractSearchRequest;";
    private static final String API = "Lcom/ss/android/ugc/aweme/discover/api/SearchApi$RealApi;";
    private static final String RESPONSE = "LX/0lv8;";
    private static final String OBSERVABLE = "Lio/reactivex/Observable;";
    private static final String OBSERVER = "Lio/reactivex/Observer;";
    private static final String DISPOSABLE = "Lio/reactivex/disposables/Disposable;";
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static final String USER = "Lcom/ss/android/ugc/aweme/profile/model/User;";
    private static final String SHARE = "Lcom/ss/android/ugc/aweme/base/share/ShareInfo;";
    private static final String SUMMARY = "Lcom/ss/android/ugc/aweme/feed/model/search/VideoAbstract;";
    private static final String BASE_RESPONSE = "Lcom/ss/android/ugc/aweme/base/api/BaseResponse;";
    private static final String RESPONSE_BASE = "LX/0lrd;";
    private static final String LOG_PB = "Lcom/ss/android/ugc/aweme/feed/model/LogPbBean;";
    private static final String RX_ASYNC = "Lcom/bytedance/retrofit2/rxjava2/adapter/CallEnqueueObservable$CallCallback;";
    private static final String RX_SYNC = "Lcom/bytedance/retrofit2/rxjava2/adapter/CallExecuteObservable$CallDisposable;";
    private static final String UI_MODEL = "Lcom/ss/android/ugc/aweme/discover/presenter/SearchAwemeFeedModel;";
    private static final Map<String, ClassDef> classes = new HashMap<>();

    @BeforeClass public static void readRequiredClassesFromTheVerifiedOriginalApk() throws Exception {
        classes.clear();
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must identify an original host APK", apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        Assume.assumeTrue("This capability has only been reviewed for original 41.0.0",
                profile == HostAdaptation.PROFILE_410);
        Set<String> required = new HashSet<>(Arrays.asList(REQUEST, BASE_REQUEST, API, RESPONSE,
                OBSERVABLE, OBSERVER, DISPOSABLE, AWEME, USER, SHARE, SUMMARY, BASE_RESPONSE, RX_ASYNC, RX_SYNC,
                UI_MODEL, RESPONSE_BASE, LOG_PB, "LX/0fOQ;", "LX/0fPi;", "LX/0exj;", "LX/0Uvf;", "LX/0Uqs;", "LX/0lrQ;"));
        try (ZipFile archive = new ZipFile(apk)) {
            for (ZipEntry entry : HostApkTestProfile.dexEntries(archive)) {
                if (required.isEmpty()) break;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (required.remove(type.getType())) classes.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
            }
        }
        assertTrue("Missing reviewed search classes: " + required, required.isEmpty());
    }

    @AfterClass public static void releaseOriginalDexObjects() { classes.clear(); }

    @Test public void requestConstructorCountAndParameterMapsKeepTheReviewedSignatures() {
        assertEquals(BASE_REQUEST, type(REQUEST).getSuperclass());
        Method constructor = callable(REQUEST, "<init>", "V", false, STRING);
        assertTrue(calls(constructor, BASE_REQUEST, "<init>", "V", STRING));
        assertTrue(strings(constructor).contains("video"));
        callable(BASE_REQUEST, "LJIILJJIL", "V", false, "I");
        callable(BASE_REQUEST, "LJJIIZ", MAP, false);
        callable(BASE_REQUEST, "LJ", MAP, false);
        instanceField(BASE_REQUEST, "LJJII", MAP);
    }

    @Test public void nativeParameterAssemblerCombinesHostDynamicFieldsAndTheSearchToken() {
        Method builder = callable("LX/0exj;", "LIZIZ", MAP, true,
                "LX/0fOQ;", BASE_REQUEST, "LX/0exk;");
        assertTrue(calls(builder, BASE_REQUEST, "LJJIIZ", MAP));
        assertTrue(calls(builder, BASE_REQUEST, "LJ", MAP));
        assertTrue(strings(builder).containsAll(Arrays.asList("token", "search", "douyin_search", "vlm_config", "1")));
        Field token = field("LX/0fPi;", "LIZJ", "LX/0fPi;");
        assertTrue(AccessFlags.PUBLIC.isSet(token.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(token.getAccessFlags()));
        assertTrue(AccessFlags.FINAL.isSet(token.getAccessFlags()));
        assertTrue(strings(method("LX/0fPi;", "<init>", "V")).contains("search"));
    }

    @Test public void continuationUsesTheReviewedCursorBooleanLogIdAndFinalExtrasOverlay() {
        instanceField(RESPONSE, "cursor", "I");
        instanceField(RESPONSE, "hasMore", "Z");
        instanceField(RESPONSE_BASE, "logPb", LOG_PB);
        instanceField(LOG_PB, "imprId", STRING);
        assertTrue(readsField(callable(RESPONSE, "LJZI", "I", false), RESPONSE, "cursor", "I"));
        assertTrue(readsField(callable(RESPONSE, "hasMore", "Z", false), RESPONSE, "hasMore", "Z"));
        Method setSearchId = callable(BASE_REQUEST, "LJJII", "V", false, STRING);
        assertTrue(readsField(setSearchId, BASE_REQUEST, "LJIILL", "LX/0mLS;"));
        assertTrue(strings(method(BASE_REQUEST, "<init>", "V", STRING)).contains("search_id"));
        assertTrue(strings(method(REQUEST, "<init>", "V", STRING)).contains("offset"));
        Method parameters = callable(BASE_REQUEST, "LJJIIZ", MAP, false);
        assertTrue(readsField(parameters, BASE_REQUEST, "LJJII", MAP));
        assertTrue(calls(parameters, "Ljava/util/HashMap;", "putAll", "V", MAP));
    }

    @Test public void reviewedObservableSearchUsesTheNativeApiObjectAndTheVideoEndpoint() {
        Method api = callable(API, "LJJIIZI", OBSERVABLE, false, MAP);
        assertEquals("/aweme/v1/search/item/", annotationString(api.getAnnotations(), "Lretrofit2/http/POST;", "value"));
        assertNotNull(annotation(api.getAnnotations(), "Lretrofit2/http/FormUrlEncoded;"));
        assertNotNull(annotation(api.getParameters().get(0).getAnnotations(), "Lretrofit2/http/FieldMap;"));
        assertTrue(signature(api).contains("Lio/reactivex/Observable<LX/0lv8;>;"));
        Method factory = callable("LX/0Uvf;", "LIZ", API, true);
        assertTrue(readsField(factory, "LX/0Uqs;", "LIZJ", API));
        assertTrue(calls(method("LX/0Uqs;", "<clinit>", "V"),
                "Lcom/bytedance/ies/ugc/aweme/network/IRetrofit;", "create", OBJECT, "Ljava/lang/Class;"));
    }

    @Test public void theSameEndpointHasARealVideoPageProducerAndResponseConsumer() {
        Method producer = method(UI_MODEL, "LJJJJ", "V", "LX/0lt8;", "Z");
        assertTrue(calls(producer, REQUEST, "<init>", "V", STRING));
        assertTrue(calls(producer, "Lcom/ss/android/ugc/aweme/discover/presenter/SearchBaseModel;",
                "LJIILLIIL", "V", "LX/0lrQ;", "Z"));
        assertTrue(calls(method("LX/0lrQ;", "LIZ", OBJECT), "LX/0exj;", "LIZLLL", OBJECT,
                "LX/0fOQ;", BASE_REQUEST));
        assertTrue(calls(method("LX/0exj;", "LIZLLL", OBJECT, "LX/0fOQ;", BASE_REQUEST),
                API, "LJIIL", "Lcom/google/common/util/concurrent/ListenableFuture;", MAP));
        assertTrue(calls(method(UI_MODEL, "LJJJIL", "V", RESPONSE), RESPONSE, "LJLZ", LIST));
    }

    @Test public void normalizationReturnsAwemesAndTheSmallProjectionFieldsHaveReviewedTypes() {
        instanceField(RESPONSE, "awemeList", LIST);
        instanceField(RESPONSE, "awemeFeeds", LIST);
        instanceField(BASE_RESPONSE, "status_code", "I");
        instanceField(BASE_RESPONSE, "error_code", "I");
        Method normalize = callable(RESPONSE, "LJLZ", LIST, false);
        assertTrue(calls(normalize, "Lcom/ss/android/ugc/aweme/newfollow/model/BaseFlowFeed;", "getAweme", AWEME));
        callable(AWEME, "getAid", STRING, false);
        instanceField(AWEME, "aid", STRING);
        instanceField(AWEME, "desc", STRING);
        instanceField(AWEME, "author", USER);
        instanceField(USER, "nickname", STRING);
        instanceField(AWEME, "shareInfo", SHARE);
        instanceField(AWEME, "shareUrl", STRING);
        instanceField(SHARE, "shareUrl", STRING);
        instanceField(AWEME, "videoAbstract", SUMMARY);
        assertTrue(readsField(callable(SUMMARY, "LIZ", STRING, false), SUMMARY, "d", STRING));
    }

    @Test public void hostObserverSubscriptionAndBothDisposableAdaptersCancelTheActualCall() {
        callable(OBSERVABLE, "subscribe", "V", false, OBSERVER);
        callable(OBSERVER, "onSubscribe", "V", false, DISPOSABLE);
        callable(OBSERVER, "onNext", "V", false, OBJECT);
        callable(OBSERVER, "onError", "V", false, "Ljava/lang/Throwable;");
        callable(OBSERVER, "onComplete", "V", false);
        callable(DISPOSABLE, "dispose", "V", false);
        callable(DISPOSABLE, "isDisposed", "Z", false);
        for (String adapter : Arrays.asList(RX_ASYNC, RX_SYNC)) {
            assertTrue(type(adapter).getInterfaces().contains(DISPOSABLE));
            assertTrue(calls(callable(adapter, "dispose", "V", false),
                    "Lcom/bytedance/retrofit2/Call;", "cancel", "V"));
        }
    }

    private static ClassDef type(String name) {
        ClassDef value = classes.get(name); assertNotNull("Missing original class " + name, value); return value;
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        for (Method value : type(owner).getMethods()) {
            if (value.getName().equals(name) && value.getReturnType().equals(returns)
                    && parameterNames(value.getParameterTypes()).equals(Arrays.asList(parameters))) return value;
        }
        throw new AssertionError("Missing original method " + owner + "->" + name + Arrays.toString(parameters) + returns);
    }

    private static Method callable(String owner, String name, String returns, boolean isStatic, String... parameters) {
        Method result = method(owner, name, returns, parameters);
        assertTrue("Method is not public: " + owner + "->" + name, AccessFlags.PUBLIC.isSet(result.getAccessFlags()));
        assertEquals("Method static shape changed: " + owner + "->" + name,
                isStatic, AccessFlags.STATIC.isSet(result.getAccessFlags()));
        return result;
    }

    private static Field field(String owner, String name, String type) {
        for (Field value : type(owner).getFields()) {
            if (value.getName().equals(name) && value.getType().equals(type)) return value;
        }
        throw new AssertionError("Missing original field " + owner + "->" + name + ":" + type);
    }

    private static void instanceField(String owner, String name, String type) {
        Field value = field(owner, name, type);
        assertTrue(AccessFlags.PUBLIC.isSet(value.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(value.getAccessFlags()));
    }

    private static List<String> parameterNames(List<? extends CharSequence> names) {
        List<String> result = new ArrayList<>(); for (CharSequence value : names) result.add(value.toString()); return result;
    }

    private static boolean calls(Method method, String owner, String name, String returns, String... parameters) {
        if (method.getImplementation() == null) return false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference value = (MethodReference) reference;
            if (value.getDefiningClass().equals(owner) && value.getName().equals(name)
                    && value.getReturnType().equals(returns)
                    && parameterNames(value.getParameterTypes()).equals(Arrays.asList(parameters))) return true;
        }
        return false;
    }

    private static boolean readsField(Method method, String owner, String name, String type) {
        if (method.getImplementation() == null) return false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof FieldReference) {
                FieldReference value = (FieldReference) reference;
                if (value.getDefiningClass().equals(owner) && value.getName().equals(name) && value.getType().equals(type)) return true;
            }
        }
        return false;
    }

    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        if (method.getImplementation() != null) for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction) {
                Object value = ((ReferenceInstruction) instruction).getReference();
                if (value instanceof StringReference) result.add(((StringReference) value).getString());
            }
        }
        return result;
    }

    private static Annotation annotation(Set<? extends Annotation> annotations, String name) {
        for (Annotation value : annotations) if (value.getType().equals(name)) return value;
        return null;
    }

    private static String annotationString(Set<? extends Annotation> annotations, String name, String key) {
        Annotation value = annotation(annotations, name);
        if (value != null) for (AnnotationElement element : value.getElements()) {
            if (element.getName().equals(key) && element.getValue() instanceof StringEncodedValue)
                return ((StringEncodedValue) element.getValue()).getValue();
        }
        return null;
    }

    private static String signature(Method method) {
        Annotation value = annotation(method.getAnnotations(), "Ldalvik/annotation/Signature;");
        StringBuilder result = new StringBuilder();
        if (value != null) for (AnnotationElement element : value.getElements()) {
            if (!element.getName().equals("value") || !(element.getValue() instanceof ArrayEncodedValue)) continue;
            for (EncodedValue part : ((ArrayEncodedValue) element.getValue()).getValue())
                if (part instanceof StringEncodedValue) result.append(((StringEncodedValue) part).getValue());
        }
        return result.toString();
    }
}
