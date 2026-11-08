package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original APK evidence, independent from the module's synthetic protocol fixture. */
public final class CommentAiHostContractTest {
    private static final String MODEL = "Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/model/";
    private static final String CORE = "Lcom/ss/ugc/android/cachalot/core/model/";
    private static final String SERVICE = "Lcom/ss/android/ugc/aweme/c2recommend/commentai/service/";
    private static final String WRAPPER = "Lcom/ss/android/ugc/aweme/comment/aianalysis/AIAnalysisWrapperFragmentV2;";
    private static final String ARCH = "Lcom/ss/android/ugc/aweme/c2page/common/newarchitecture/";
    private static final String COMMAND = "Lcom/ss/android/ugc/aweme/search/ai/specificbiz/bizmodule/chat/data/AiStreamCommand;";
    private static final Set<String> NAMES = Set.of("LX/1B3z;", "LX/1B3X;", "LX/1BUJ;", "LX/1BqP;", "LX/1Brw;",
            "LX/1Ape;", "LX/0fEq;", "LX/0fK1;", COMMAND, MODEL + "Message;", MODEL + "Message$Display;", MODEL + "AiTextModel;",
            CORE + "FeedModel;", CORE + "FeedStructModel;", CORE + "BusinessDataItem;", CORE + "RenderInfoItem;",
            "Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/AiStreamTextCardFactory;",
            "Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/SearchAiTextDataType;",
            "LX/0mOT;", "LX/1BaN;", "LX/1B4J;", "LX/1B46;", "LX/1AyY;", "LX/1B8r;", "LX/00lO;", "LX/0enb;", "LX/0end;",
            SERVICE + "C2FeedCommentAiAnalysisService;", SERVICE + "C2FeedCommentAiAnalysisSSEPrefetcherImpl;", WRAPPER,
            "Lcom/ss/android/ugc/aweme/utils/GsonProvider;", "Lcom/ss/android/ugc/aweme/utils/GsonHolder;",
            "Lcom/google/gson/Gson;", "Lcom/google/gson/GsonBuilder;",
            "Lcom/ss/ugc/android/cachalot/common/CachalotBusinessDataItemTypeAdapterFactory;",
            "Lcom/ss/android/ugc/aweme/c2page/impl/newarchitecture/C2ComposerImpl;");
    private static final Map<String, ClassDef> TYPES = new HashMap<>();

    @BeforeClass public static void original41Contract() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(configured);
        File apk = new File(configured);
        assertTrue(apk.isFile());
        Assume.assumeTrue(HostApkTestProfile.profile(apk) == HostAdaptation.PROFILE_410);
        try (ZipFile archive = new ZipFile(apk)) {
            for (var entry : HostApkTestProfile.dexEntries(archive)) {
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (NAMES.contains(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
                if (TYPES.size() == NAMES.size()) break;
            }
        }
        assertEquals(NAMES, TYPES.keySet());
    }

    @Test public void managerAndEventsRetainExact41Signatures() {
        method("LX/1B3z;", "LIZLLL", "V", "LX/1B3X;");
        method("LX/1B3z;", "LJIIJ", "V", "LX/1B3X;");
        method("LX/1B3z;", "LJIILIIL", "V", "LX/1B4M;");
        method("LX/1BUJ;", "<init>", "V", "Ljava/lang/String;", "I", "LX/1B3X;");
        method("LX/1BqP;", "<init>", "V", "Ljava/lang/String;", "I", "LX/1Ape;", "Ljava/lang/String;", "J", "J",
                "Lcom/ss/android/ugc/aweme/c2page/common/newarchitecture/ai/BaseSSEResponse;");
        method("LX/1Brw;", "<init>", "V", "Ljava/lang/String;", "I",
                "Lcom/ss/android/ugc/aweme/c2page/common/newarchitecture/ai/BaseSSEResponse;", "Ljava/lang/String;");
    }

    @Test public void nativeCardAndSpanSchemasExistInOriginalDex() {
        assertTrue(strings(method("Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/AiStreamTextCardFactory;",
                "getCardType", "Ljava/lang/String;")).contains("737614"));
        assertTrue(strings(method("Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/SearchAiTextDataType;",
                "getType", "Ljava/lang/String;")).contains("999670"));
        assertTrue(types(method("Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/SearchAiTextDataType;",
                "getDataType", "Ljava/lang/Class;")).contains(MODEL + "AiTextModel;"));
        assertTrue(types(method("LX/0mOT;", "LIZ", "[Ljava/lang/Class;"))
                .contains("Lcom/ss/android/ugc/aweme/search/ai/specificbiz/container/card/quick/text/AiStreamTextCardFactory;"));
        field(MODEL + "AiTextModel;", "_streamData", "Ljava/util/List;");
        field(COMMAND, "display", "Lcom/google/gson/JsonObject;");
        field(MODEL + "Message;", "LIZ", MODEL + "Message$Display;");
        field(MODEL + "Message$Display;", "LIZIZ", "Ljava/util/List;");
        field("LX/0fEq;", "LJIIL", "LX/0fK1;");
        field("LX/0fK1;", "LIZ", "Ljava/lang/String;");
        field(CORE + "FeedModel;", "businessData", "Ljava/util/concurrent/CopyOnWriteArrayList;");
        field(CORE + "FeedModel;", "renderInfo", "Ljava/util/concurrent/CopyOnWriteArrayList;");
        field(CORE + "FeedModel;", "struct", CORE + "FeedStructModel;");
        field(CORE + "BusinessDataItem;", "data", "Ljava/lang/Object;");
        field(CORE + "FeedStructModel;", "dataId", "Ljava/lang/String;");
        field(CORE + "FeedStructModel;", "renderId", "Ljava/lang/String;");
        field(CORE + "RenderInfoItem;", "LIZIZ", "Ljava/lang/String;");
    }

    @Test public void everyNativeEntryCancellationAndParserBindingMatchesTheOriginalHost() {
        method("LX/1B3z;", "LIZIZ", "V");
        method("LX/1B3z;", "LIZJ", "V");
        method("LX/1B3z;", "LJIIL", "V");
        method("LX/1BaN;", "LIZ", "Ljava/lang/String;", ARCH + "context/IC2ArchContext;");
        method(SERVICE + "C2FeedCommentAiAnalysisService;", "LIZ", "LX/12L3;",
                "Landroidx/fragment/app/Fragment;", "Ljava/lang/String;");
        method("LX/1B46;", "<init>", "V", "Landroidx/fragment/app/Fragment;", "Ljava/lang/String;", "Z");
        method("LX/1AyY;", "LIZ", ARCH + "assemble/IC2Composer;", "LX/1B3O;", "Lkotlin/jvm/functions/Function3;");
        method("LX/1B8r;", "<init>", "V", ARCH + "assemble/IC2Composer;", "Ljava/lang/String;");
        method(SERVICE + "C2FeedCommentAiAnalysisSSEPrefetcherImpl;", "LIZ", "V",
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Lcom/ss/android/ugc/aweme/ability/CommentAiAnalysisSSEPrefetchParams;");
        method(SERVICE + "C2FeedCommentAiAnalysisSSEPrefetcherImpl;", "cancel", "V");
        method(SERVICE + "C2FeedCommentAiAnalysisSSEPrefetcherImpl;", "LIZIZ", SERVICE + "C2FeedCommentAiAnalysisSSEPrefetcherImpl;");
        method(WRAPPER, "b0", "V", "LX/11ue;", "Z");
        method(WRAPPER, "setUserVisibleHint", "V", "Z");
        method(WRAPPER, "X1", "V", "Landroidx/fragment/app/FragmentActivity;", "Ljava/lang/String;");
        method(WRAPPER, "onDestroyView", "V");
        method("LX/0enb;", "<init>", "V", "Ljava/lang/String;", "I");
        method("LX/0end;", "<init>", "V", "Ljava/lang/String;", "I", "Ljava/lang/Throwable;", "LX/1Brg;", "Ljava/lang/String;",
                ARCH + "ai/BaseSSEResponse;");
        method("Lcom/ss/android/ugc/aweme/utils/GsonHolder;", "LIZIZ", "Lcom/ss/android/ugc/aweme/utils/GsonProvider;");
        method("Lcom/ss/android/ugc/aweme/utils/GsonProvider;", "getGson", "Lcom/google/gson/Gson;");
        method("Lcom/ss/android/ugc/aweme/c2page/impl/newarchitecture/C2ComposerImpl;", "LIZJ", "Ljava/lang/Object;", "Ljava/lang/Class;");
        field("LX/1B3z;", "LIZIZ", ARCH + "context/IC2ArchContext;");
        field("LX/1B3z;", "LIZ", "LX/1B4J;");
        field("LX/1B4J;", "LIZIZ", "LX/1B3a;");
        field("LX/00lO;", "LIZIZ", "Lkotlin/Lazy;");
    }

    @Test public void nativeSchemaCanRegisterTheBusinessAdapterOnAnIndependentHostGson() {
        method("Lcom/google/gson/Gson;", "newBuilder", "Lcom/google/gson/GsonBuilder;");
        method("Lcom/google/gson/GsonBuilder;", "registerTypeAdapterFactory", "Lcom/google/gson/GsonBuilder;",
                "Lcom/google/gson/TypeAdapterFactory;");
        method("Lcom/google/gson/GsonBuilder;", "create", "Lcom/google/gson/Gson;");
        method("Lcom/ss/ugc/android/cachalot/common/CachalotBusinessDataItemTypeAdapterFactory;", "<init>", "V");
    }

    private static void field(String owner, String name, String descriptor) {
        for (var field : TYPES.get(owner).getFields()) if (name.equals(field.getName())) {
            assertEquals(owner + name, descriptor, field.getType()); return;
        }
        fail("Missing host field " + owner + name);
    }
    private static Method method(String owner, String name, String result, String... args) {
        for (Method method : TYPES.get(owner).getMethods()) if (name.equals(method.getName())
                && result.equals(method.getReturnType()) && method.getParameterTypes().equals(Arrays.asList(args))) return method;
        throw new AssertionError("Missing host method " + owner + name + Arrays.toString(args));
    }
    private static Set<String> strings(Method method) {
        Set<String> found = new java.util.HashSet<>();
        for (var instruction : method.getImplementation().getInstructions()) if (instruction instanceof ReferenceInstruction) {
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof StringReference) found.add(((StringReference) reference).getString());
        }
        return found;
    }
    private static Set<String> types(Method method) {
        Set<String> found = new java.util.HashSet<>();
        for (var instruction : method.getImplementation().getInstructions()) if (instruction instanceof ReferenceInstruction) {
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (reference instanceof TypeReference) found.add(((TypeReference) reference).getType());
        }
        return found;
    }
}
