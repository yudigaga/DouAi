package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original APK evidence for SDK-wide receive notifications and cold conversation lookup. */
public final class BackgroundMessageHostContractTest {
    private static final String OBSERVER = "LX/1Z8C;";
    private static final String SDK_BASE = "LX/1Z99;";
    private static final String SDK = "Lcom/bytedance/im/core/mi/IMSdkContext;";
    private static final String MESSAGE = "Lcom/bytedance/im/core/model/Message;";
    private static final String EXTRA = "Lcom/bytedance/im/core/model/ReceiveMsgExtra;";
    private static final String CONVERSATIONS = "Lcom/bytedance/im/core/model/ConversationListModel;";
    private static final String CONVERSATION = "Lcom/bytedance/im/core/model/Conversation;";
    private static final String LISTENER = "Lcom/bytedance/im/core/client/callback/IRequestListener;";
    private static final String BIZ_OBSERVER = "Lcom/ss/android/ugc/aweme/im/sdk/chat/GlobalMsgObserver;";
    private static final String CORE_OBSERVER = "Lcom/bytedance/ies/im/core/core/GlobalMsgObserver;";
    private static final String CLIENT_OBSERVER = "Lcom/bytedance/ies/im/core/api/client/calback/IMessageObserver;";
    private static final String OBSERVER_API = "Lcom/bytedance/im/core/internal/utils/IObserverUtils;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static boolean legacy;
    private static boolean latest;
    private static HostContractNameFacts names;

    @BeforeClass public static void readActualHostContracts() throws Exception {
        classes.clear();
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue(apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        legacy = profile.versionCode == 400601L;
        latest = profile.versionCode == 410001L;
        names = new HostContractNameFacts(profile);
        Set<String> wanted = new HashSet<>(Arrays.asList(OBSERVER, SDK_BASE, SDK, MESSAGE, EXTRA,
                CONVERSATIONS, BIZ_OBSERVER, CORE_OBSERVER, "LX/1Z9A;", "LX/0vbu;", "LX/16Tc;",
                "LX/1Z5A;", "LX/1Z6p;", "LX/16Pt;", "LY/ATRunnableS26S1110000_37;",
                "LY/ATCallbackS220S1200000_37;"));
        try (ZipFile archive = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && !wanted.isEmpty()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (wanted.remove(names.type(type.getType()))) classes.put(names.type(type.getType()), names.originalFacts(type));
                    }
                }
            }
        }
        assertTrue("Missing original background-message contracts: " + wanted, wanted.isEmpty());
    }

    @AfterClass public static void releaseDexFacts() { classes.clear(); }

    @Test public void globalReceiveEntryCarriesItsSdkWithoutAChatPage() {
        Method receive = callable(OBSERVER, "onGetMessage", "V", false, "Ljava/util/List;", "I", EXTRA);
        assertTrue(AccessFlags.FINAL.isSet(receive.getAccessFlags()));
        assertEquals("LX/1Z9A;", classes.get(OBSERVER).getSuperclass());
        assertEquals(SDK_BASE, classes.get("LX/1Z9A;").getSuperclass());
        Field context = field(SDK_BASE, "imSdkContext", SDK);
        assertTrue(AccessFlags.PUBLIC.isSet(context.getAccessFlags()));
        assertTrue(AccessFlags.FINAL.isSet(context.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(context.getAccessFlags()));
        callable(SDK, "getUid", "J", false);
        callable(SDK, "getConversationListModel", CONVERSATIONS, false);
        assertTrue("This is the SDK observer utility, not a chat fragment callback",
                classes.get(OBSERVER).getInterfaces().contains(OBSERVER_API));
    }

    @Test public void bothOnlineNotificationsAndPulledMessagesReachTheSameGlobalEntry() {
        Method online = callable("LX/1Z5A;", "M2", "V", false, "LX/1Z5F;");
        Method pulled = callable("LX/1Z6p;", "R3", "V", false,
                "Ljava/lang/String;", CONVERSATION, "Ljava/util/List;", "Ljava/lang/Integer;",
                "LX/1Z6z;", "Ljava/util/ArrayList;", "Z");
        assertTrue(calls(online, OBSERVER, "onGetMessage"));
        assertTrue(calls(pulled, OBSERVER, "onGetMessage"));
        assertTrue(calls(online, SDK_BASE, "getConversationListModel"));
        assertTrue(calls(pulled, "LX/1Z9A;", "getMessageGetHookObserver"));
        assertTrue(calls(pulled, "Lcom/bytedance/im/core/dependency/IIMGetMessageHookObserver;", "onNotifyUpdateStart"));
    }

    @Test public void applicationInitializationRegistersTheBusinessObserverGlobally() {
        Method initialize = callable("LX/0vbu;", "LJJII", "V", true);
        assertTrue(calls(initialize, BIZ_OBSERVER, "LIZ"));
        if (latest) {
            assertTrue(calls(initialize, "Lcom/bytedance/ies/im/core/api/IMCore;", "getPlatform"));
            assertTrue(calls(initialize, "Lcom/bytedance/ies/im/core/api/service/IPlatformService;", "LJJIL"));
            return;
        }
        assertTrue(calls(initialize, "LX/16Tc;", "LIZ"));
        Method facade = callable("LX/16Tc;", "LIZ", "V", false, CLIENT_OBSERVER);
        assertTrue(calls(facade, CORE_OBSERVER, "LIZ"));
        Method register = callable(CORE_OBSERVER, "LIZ", "V", false, CLIENT_OBSERVER);
        assertTrue(calls(register, OBSERVER_API, "registerGlobal"));
        Method nativeRegister = callable(OBSERVER, "registerGlobal", "V", false,
                "Lcom/bytedance/im/core/model/IMessageObserver;");
        assertTrue(referencesField(nativeRegister, OBSERVER, "e"));
        callable(BIZ_OBSERVER, "onGetMessage", "V", false, "Ljava/util/List;", "I", EXTRA);
    }

    @Test public void messageGettersExposeReceiveIdentityAndContentWithoutLoadingChatUi() {
        for (String getter : new String[]{"getSender", "getMsgId", "getCreatedAt"}) callable(MESSAGE, getter, "J", false);
        for (String getter : new String[]{"getContent", "getConversationId", "getUuid"}) {
            callable(MESSAGE, getter, "Ljava/lang/String;", false);
        }
        for (String getter : new String[]{"getMsgType", "getConversationType", "getSvrStatus", "getDeleted"}) {
            callable(MESSAGE, getter, "I", false);
        }
        callable(MESSAGE, "getExt", "Ljava/util/concurrent/ConcurrentHashMap;", false);
        assertTrue("getMsgId is the stored message/server identifier used by this host",
                referencesField(method(MESSAGE, "getMsgId", "J"), MESSAGE, "msgId"));
        assertTrue(referencesField(method(MESSAGE, "getCreatedAt", "J"), MESSAGE, "createdAt"));
        assertTrue(referencesField(method(MESSAGE, "getConversationType", "I"), MESSAGE, "conversationType"));
    }

    @Test public void synchronousConversationLookupReadsMemoryAndDoesNotQueryTheDatabase() {
        Method lookup = callable(CONVERSATIONS, "getConversation", CONVERSATION, false, "Ljava/lang/String;");
        assertTrue(calls(lookup, "LX/16Pt;", "getConversation"));
        Method cache = callable("LX/16Pt;", "getConversation", CONVERSATION, false, "Ljava/lang/String;");
        assertTrue(calls(cache, "Lcom/bytedance/im/core/model/cache/IMMemoryCache;", "get"));
        assertFalse("A cold cache miss is not proof that the conversation is missing from local storage",
                calls(lookup, "Lcom/bytedance/im/core/db/delegate/IIMConversationDaoDelegate;", "getConversation"));
        assertFalse(calls(lookup, "LX/1Z9A;", "executeDB"));
    }

    @Test public void asynchronousConversationLookupSchedulesALocalReadAndReturnsTheResolvedObject() {
        Method async = callable(CONVERSATIONS, "getConversationAsync", "V", false, "Ljava/lang/String;", LISTENER);
        assertTrue(calls(async, CONVERSATIONS, "LJLIIL"));
        Method cold = callable(CONVERSATIONS, "LJLIIL", "V", false, "Ljava/lang/String;", "Z", LISTENER);
        assertTrue(calls(cold, CONVERSATIONS, "getConversation"));
        assertTrue(calls(cold, "LX/1Z9A;", "executeDB"));
        assertTrue(referencesField(cold, "Lcom/bytedance/im/core/db/thread/DbType;", "CONV"));
        assertTrue(referencesField(cold, "Lcom/bytedance/im/core/db/thread/DbAction;", "READ"));
        assertTrue(strings(cold).contains("ConversationListModel_getConversation"));
        Method read = callable("LY/ATRunnableS26S1110000_37;", "onRun", "Ljava/lang/Object;", false);
        assertTrue(calls(read, "Lcom/bytedance/im/core/db/delegate/IIMConversationDaoDelegate;", "getConversation"));
        Method callback = callable("LY/ATCallbackS220S1200000_37;", "onCallback", "V", false, "Ljava/lang/Object;");
        assertTrue(calls(callback, LISTENER, "onSuccess"));
        assertTrue(calls(callback, CONVERSATIONS, "f3"));
        Method warmCache = callable(CONVERSATIONS, "f3", "V", false, "Ljava/lang/String;", "[" + CONVERSATION);
        assertTrue(calls(warmCache, "LX/16Pt;", "J2"));
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        ClassDef type = classes.get(owner);
        assertNotNull(owner, type);
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && returns.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        fail("Missing " + owner + "->" + name + Arrays.toString(parameters) + returns);
        return null;
    }

    private static Method callable(String owner, String name, String returns, boolean isStatic, String... parameters) {
        Method method = method(owner, name, returns, parameters);
        assertTrue(owner + "->" + name + " must be public", AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
        assertEquals(owner + "->" + name + " static flag", isStatic, AccessFlags.STATIC.isSet(method.getAccessFlags()));
        return method;
    }

    private static Field field(String owner, String name, String type) {
        for (Field field : classes.get(owner).getFields()) {
            if (name.equals(field.getName()) && type.equals(field.getType())) return field;
        }
        fail("Missing " + owner + "." + name + ":" + type);
        return null;
    }

    private static boolean calls(Method method, String owner, String name) {
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            Object reference = reference(instruction);
            if (reference instanceof MethodReference && owner.equals(((MethodReference) reference).getDefiningClass())
                    && name.equals(((MethodReference) reference).getName())) return true;
        }
        return false;
    }

    private static boolean referencesField(Method method, String owner, String name) {
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            Object reference = reference(instruction);
            if (reference instanceof FieldReference && owner.equals(((FieldReference) reference).getDefiningClass())
                    && name.equals(((FieldReference) reference).getName())) return true;
        }
        return false;
    }

    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            Object reference = reference(instruction);
            if (reference instanceof StringReference) result.add(((StringReference) reference).getString());
        }
        return result;
    }

    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }
}
