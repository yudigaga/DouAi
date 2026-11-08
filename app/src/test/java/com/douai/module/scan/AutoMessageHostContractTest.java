package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
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

/** Original 40.8.0 DEX contracts required after the automatic-message rules have matched. */
public final class AutoMessageHostContractTest {
    private static final String UI = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerUI2;";
    private static final String LOGIC = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerLogic2;";
    private static final String SINGLE = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/SingleInputDrawerUI2;";
    private static final String SESSION = "Lcom/ss/android/ugc/aweme/im/sdk/chat/SessionInfo;";
    private static final String EDITOR = "Lcom/ss/android/ugc/aweme/im/widget/SearchableEditText;";
    private static final String RIPS_UI = "Lcom/ss/android/ugc/aweme/rips/RipsUI;";
    private static final String NESTED_UI = "Lcom/ss/android/ugc/aweme/rips/NestedRipsUI;";
    private static final String RIPS_LOGIC = "Lcom/ss/android/ugc/aweme/rips/BasicRipsLogic;";
    private static final String MODE = "LX/0Yce;", PLAIN = "LX/0Yca;", STATE = "LX/0YRa;";
    private static final String LIVE = "Landroidx/lifecycle/MutableLiveData;";
    private static final String VIEW = "Landroid/view/View;", STRING = "Ljava/lang/String;";
    private static final String BOTTOM = "LX/0isP;";
    private static final String BOTTOM_COMPONENT = "Lcom/ss/android/ugc/aweme/homepage/tab/bottom/MPFBottomTabComponent;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static boolean legacy;
    private static HostContractNameFacts names;

    @BeforeClass public static void readTheOriginalInstallationContracts() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        legacy = profile.versionCode == 400601L;
        names = new HostContractNameFacts(profile);
        classes.clear();
        Set<String> required = new HashSet<>(Arrays.asList(UI, LOGIC, SINGLE, SESSION,
                RIPS_UI, NESTED_UI, MODE, PLAIN, STATE, BOTTOM, BOTTOM_COMPONENT));
        try (ZipFile archive = new ZipFile(apk)) {
            for (ZipEntry entry : HostApkTestProfile.dexEntries(archive)) {
                if (required.isEmpty()) break;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (required.remove(names.type(type.getType()))) classes.put(names.type(type.getType()), names.originalFacts(type));
                    }
                }
            }
        }
        assertTrue("Missing original installation classes: " + required, required.isEmpty());
    }

    @AfterClass public static void releaseDexFacts() { classes.clear(); }

    @Test public void plainTextModeIsAConcreteInputTypeWithTheNativeTextIdentifier() {
        assertEquals(MODE, classes.get(PLAIN).getSuperclass());
        assertFalse(AccessFlags.INTERFACE.isSet(classes.get(PLAIN).getAccessFlags()));
        assertFalse(AccessFlags.ABSTRACT.isSet(classes.get(PLAIN).getAccessFlags()));
        Code constructor = new Code(method(PLAIN, "<init>", "V"));
        assertTrue(constructor.call(MODE, "<init>") >= 0);
        Code identifier = new Code(method(PLAIN, "LIZLLL", STRING));
        int text = identifier.string("text");
        assertEquals(Opcode.RETURN_OBJECT, identifier.at(text + 1).getOpcode());
        assertEquals(register(identifier.at(text)), register(identifier.at(text + 1)));
    }

    @Test public void curInputTypeIsDeclaredOnTheLogicAndFallsBackToPlainTextAfterReadingItsState() {
        Method getter = instanceMethod(LOGIC, "curInputType", MODE);
        assertTrue("getDeclaredMethod must resolve on InputDrawerLogic2 itself",
                AccessFlags.FINAL.isSet(getter.getAccessFlags()));
        Code code = new Code(getter);
        int live = code.call(LOGIC, "LJLI");
        int value = code.call("Landroidx/lifecycle/LiveData;", "getValue");
        int mode = code.field(STATE, "LIZ", Opcode.IGET_OBJECT);
        assertEquals(MODE, field(STATE, "LIZ").getType());
        assertTrue(live < value && value < mode);
        assertEquals(Opcode.IF_NEZ, code.at(mode + 1).getOpcode());
        assertEquals(register(code.at(mode)), register(code.at(mode + 1)));
        assertEquals(Opcode.NEW_INSTANCE, code.at(mode + 2).getOpcode());
        assertEquals(PLAIN, reference(code.at(mode + 2)).toString());
        assertEquals(mode + 3, code.call(PLAIN, "<init>"));
        assertEquals("Only an absent input mode creates a plain-text fallback", mode + 4, code.target(mode + 1));
        assertEquals(Opcode.RETURN_OBJECT, code.at(mode + 4).getOpcode());
        assertEquals(register(code.at(mode)), register(code.at(mode + 4)));
    }

    @Test public void logicConstructionStoresTheNativeDefaultStateAndPreservesTheConversationModePath() {
        Method constructor = method(LOGIC, "<init>", "V", "LX/1WHh;",
                "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/DrawerEntranceProvider;");
        Code construction = new Code(constructor);
        int initialize = construction.call(LOGIC, "LJLIIL");
        assertEquals(Opcode.MOVE_RESULT_OBJECT, construction.at(initialize + 1).getOpcode());
        assertEquals(initialize + 2, construction.field(LOGIC, "h", Opcode.IPUT_OBJECT));
        assertEquals(register(construction.at(initialize + 1)), register(construction.at(initialize + 2)));
        assertEquals(LIVE, field(LOGIC, "h").getType());
        Code liveGetter = new Code(instanceMethod(LOGIC, "LJLI", LIVE));
        assertEquals(0, liveGetter.field(LOGIC, "h", Opcode.IGET_OBJECT));
        assertEquals(Opcode.RETURN_OBJECT, liveGetter.at(1).getOpcode());
        Code defaults = new Code(instanceMethod(LOGIC, "LJLIIL", LIVE));
        int plain = defaults.call(PLAIN, "<init>");
        int state = defaults.call(STATE, "<init>");
        int live = defaults.call(LIVE, "<init>");
        assertTrue(plain < state && state < live);
        FiveRegisterInstruction makePlain = (FiveRegisterInstruction) defaults.at(plain);
        FiveRegisterInstruction makeState = (FiveRegisterInstruction) defaults.at(state);
        FiveRegisterInstruction makeLive = (FiveRegisterInstruction) defaults.at(live);
        assertEquals(makePlain.getRegisterC(), makeState.getRegisterD());
        assertEquals(makeState.getRegisterC(), makeLive.getRegisterD());
        assertTrue("The other native branch resolves the conversation's stored mode",
                defaults.call(SESSION, "getConversationId") > live);
        assertTrue(defaults.call("LX/0YcX;", "LIZ") > live);
        Code stateConstructor = new Code(method(STATE, "<init>", "V", MODE, "I"));
        assertTrue(stateConstructor.field(STATE, "LIZ", Opcode.IPUT_OBJECT) >= 0);
    }

    @Test public void theSingleChatDrawerBindsItsOwnSessionAndReturnsTheActualNativeEditor() {
        assertEquals(UI, classes.get(SINGLE).getSuperclass());
        assertEquals(SESSION, field(UI, "e").getType());
        assertTrue(AccessFlags.FINAL.isSet(field(UI, "e").getAccessFlags()));
        assertEquals(EDITOR, field(UI, "m").getType());
        assertEquals(STRING, field(SESSION, "conversationId").getType());
        Code conversation = new Code(instanceMethod(SESSION, "getConversationId", STRING));
        assertEquals(0, conversation.field(SESSION, "conversationId", Opcode.IGET_OBJECT));
        Code editor = new Code(instanceMethod(UI, "getEditTextView", "Landroid/widget/EditText;"));
        assertEquals(0, editor.field(UI, "m", Opcode.IGET_OBJECT));
        assertEquals(Opcode.RETURN_OBJECT, editor.at(1).getOpcode());
        assertEquals(register(editor.at(0)), register(editor.at(1)));
        Code bind = new Code(instanceMethod(UI, "onViewCreated", "V", VIEW));
        assertTrue("Binding assigns the editor later returned by getEditTextView",
                bind.field(UI, "m", Opcode.IPUT_OBJECT) >= 0);
        instanceMethod(UI, "setText", "V", "Ljava/lang/CharSequence;", STRING, STRING,
                "Ljava/util/List;", "Ljava/util/Map;");
    }

    @Test public void theLogicProviderIsInheritedFromRipsUiAndReturnsItsStoredLogicInstance() {
        assertEquals(NESTED_UI, classes.get(UI).getSuperclass());
        assertEquals(RIPS_UI, classes.get(NESTED_UI).getSuperclass());
        for (String owner : Arrays.asList(UI, NESTED_UI)) {
            assertFalse("Provider lookup must walk the superclass chain", hasMethod(owner, "LJJJJJ"));
        }
        Method provider = instanceMethod(RIPS_UI, "LJJJJJ", RIPS_LOGIC);
        assertTrue(AccessFlags.FINAL.isSet(provider.getAccessFlags()));
        assertEquals(RIPS_LOGIC, field(RIPS_UI, "logic").getType());
        Code code = new Code(provider);
        int read = code.field(RIPS_UI, "logic", Opcode.IGET_OBJECT);
        assertEquals(Opcode.IF_NEZ, code.at(read + 1).getOpcode());
        int returns = code.target(read + 1);
        assertEquals(Opcode.RETURN_OBJECT, code.at(returns).getOpcode());
        assertEquals(register(code.at(read)), register(code.at(returns)));
        assertTrue("An uninitialized native logic remains a host error",
                code.call("Lkotlin/jvm/internal/Intrinsics;", "throwUninitializedPropertyAccessException") < returns);
        assertTrue(new Code(instanceMethod(UI, "onViewCreated", "V", VIEW)).call(RIPS_UI, "LJJJJJ") >= 0);
    }

    @Test public void theScannedBottomViewIsTheInflatedFrameLayoutUsedByTheMainNavigationComponent() {
        assertEquals("Landroid/widget/FrameLayout;", classes.get(BOTTOM).getSuperclass());
        Code constructor = new Code(method(BOTTOM, "<init>", "V", "Landroid/content/Context;"));
        assertTrue(constructor.call("Landroid/widget/FrameLayout;", "<init>") >= 0);
        assertTrue(constructor.string("MainBottomTabViewNew") >= 0);
        int inflate = constructor.call("Landroid/view/LayoutInflater;", "inflate");
        assertEquals("The native layout is inflated into this bottom navigation view",
                method(BOTTOM, "<init>", "V", "Landroid/content/Context;").getImplementation().getRegisterCount() - 2,
                ((FiveRegisterInstruction) constructor.at(inflate)).getRegisterE());
        assertEquals("Lcom/bytedance/dux/image/DuxImageView;", field(BOTTOM, "b").getType());
        assertEquals(BOTTOM, field(BOTTOM_COMPONENT, "k").getType());
        Code layout = new Code(instanceMethod(BOTTOM_COMPONENT, "LJJLIIIJL", VIEW, "Landroid/view/ViewGroup;"));
        int cache = layout.field("LX/0ioG;", "b", Opcode.IGET_OBJECT);
        int store = layout.field(BOTTOM_COMPONENT, "k", Opcode.IPUT_OBJECT);
        assertTrue(cache < store);
        assertEquals(register(layout.at(cache)), register(layout.at(store)));
        int navigation = layout.call("LX/0io8;", "<init>");
        assertTrue(cache < navigation && navigation < store);
        assertEquals(register(layout.at(cache)), ((FiveRegisterInstruction) layout.at(navigation)).getRegisterE());
        Code container = new Code(instanceMethod(BOTTOM_COMPONENT, "getContainerView", VIEW));
        assertEquals(0, container.field(BOTTOM_COMPONENT, "k", Opcode.IGET_OBJECT));
        assertEquals(Opcode.RETURN_OBJECT, container.at(1).getOpcode());
        assertEquals(register(container.at(0)), register(container.at(1)));
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        for (Method method : classes.get(owner).getMethods()) {
            if (name.equals(method.getName()) && returns.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        throw new AssertionError("Missing " + owner + "->" + name + Arrays.toString(parameters) + returns);
    }

    private static Method instanceMethod(String owner, String name, String returns, String... parameters) {
        Method method = method(owner, name, returns, parameters);
        assertTrue(AccessFlags.PUBLIC.isSet(method.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(method.getAccessFlags()));
        return method;
    }

    private static boolean hasMethod(String owner, String name) {
        for (Method method : classes.get(owner).getMethods()) if (name.equals(method.getName())) return true;
        return false;
    }

    private static Field field(String owner, String name) {
        for (Field field : classes.get(owner).getFields()) if (name.equals(field.getName())) return field;
        throw new AssertionError("Missing " + owner + "->" + name);
    }

    private static int register(Instruction instruction) { return ((OneRegisterInstruction) instruction).getRegisterA(); }
    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static final class Code {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        Code(Method method) {
            assertNotNull(method.getImplementation());
            int offset = 0;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                offsets.add(offset); instructions.add(instruction); offset += instruction.getCodeUnits();
            }
        }
        Instruction at(int index) { return instructions.get(index); }
        int target(int index) {
            int target = offsets.indexOf(offsets.get(index) + ((OffsetInstruction) at(index)).getCodeOffset());
            assertTrue("Branch must reach a real instruction", target >= 0);
            return target;
        }
        int call(String owner, String name) {
            for (int index = 0; index < instructions.size(); index++) {
                Object reference = reference(at(index));
                if (reference instanceof MethodReference && owner.equals(((MethodReference) reference).getDefiningClass())
                        && name.equals(((MethodReference) reference).getName())) return index;
            }
            throw new AssertionError("Missing call " + owner + "->" + name);
        }
        int field(String owner, String name, Opcode opcode) {
            for (int index = 0; index < instructions.size(); index++) {
                Object reference = reference(at(index));
                if (at(index).getOpcode() == opcode && reference instanceof FieldReference
                        && owner.equals(((FieldReference) reference).getDefiningClass())
                        && name.equals(((FieldReference) reference).getName())) return index;
            }
            throw new AssertionError("Missing " + opcode + " " + owner + "->" + name);
        }
        int string(String value) {
            for (int index = 0; index < instructions.size(); index++) {
                Object reference = reference(at(index));
                if (reference instanceof StringReference && value.equals(((StringReference) reference).getString())) return index;
            }
            throw new AssertionError("Missing string " + value);
        }
    }
}
