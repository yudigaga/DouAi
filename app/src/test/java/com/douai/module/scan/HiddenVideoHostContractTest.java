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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
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
import java.util.ArrayDeque;
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

/** Pins each supported original DEX safety-task edge independently of module hooks. */
public final class HiddenVideoHostContractTest {
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String STRING = "Ljava/lang/String;";
    private static String RESPONSE_CALLBACK = "LX/12bV;";
    private static String CURRENT_TASK = "Lkotlin/jvm/internal/ALambdaS38S1500000_35;";
    private static String BACKGROUND_TASK = "LX/12bW;";
    private static String DELETE_TASK_SOURCE = "LX/1R0v;";
    private static String RECALL = "LX/07Zg;";
    private static final String FEED_DATA = "Lcom/ss/android/ugc/aweme/feed/plato/core/IFeedDataContext;";
    private static final String DEFAULTS = "Lcom/ss/android/ugc/aweme/feed/plato/core/IFeedDataContext$DefaultImpls;";
    private static final String DELETE_RESULT = "Lcom/ss/android/ugc/aweme/feed/listener/DeleteResult;";
    private static final String MAIN_THREAD = "Lcom/ss/android/ugc/aweme/feed/play/MainThreadHelper;";
    private static final String UNIT = "Lkotlin/Unit;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static boolean legacy;
    private static boolean latest;
    private static int recallBranch, genericBranch;
    private static String deleteMethod;

    @BeforeClass public static void readOnlyTheOriginalSafetyTaskDexClasses() throws Exception {
        classes.clear();
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        legacy = profile == HostAdaptation.PROFILE_406;
        latest = profile == HostAdaptation.PROFILE_410;
        RESPONSE_CALLBACK = legacy ? "LX/06hv;" : latest ? "LX/1R0w;" : "LX/12bV;";
        CURRENT_TASK = legacy ? "Lkotlin/jvm/internal/ALambdaS32S1500000_4;"
                : latest ? "Lkotlin/jvm/internal/ALambdaS43S1500000_47;"
                : "Lkotlin/jvm/internal/ALambdaS38S1500000_35;";
        BACKGROUND_TASK = legacy ? "LX/06ht;" : latest ? "LX/1R3E;" : "LX/12bW;";
        DELETE_TASK_SOURCE = "LX/1R0v;";
        RECALL = legacy ? "LX/06R8;" : latest ? "LX/07jF;" : "LX/07Zg;";
        recallBranch = latest ? 2 : 0;
        genericBranch = latest ? 3 : 1;
        deleteMethod = latest || legacy ? "LJIIIIZZ" : "LJIIIZ";
        Set<String> required = new HashSet<>(Arrays.asList(RESPONSE_CALLBACK, CURRENT_TASK, BACKGROUND_TASK, RECALL, DEFAULTS));
        if (latest) required.add(DELETE_TASK_SOURCE);
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
        assertTrue("Missing original safety callback/task classes: " + required, required.isEmpty());
    }

    @AfterClass public static void releaseOriginalDexFacts() { classes.clear(); }

    @Test public void bothDestructiveTasksAreInstanceObjectInvocationsAndReturnHostUnit() {
        for (String task : Arrays.asList(CURRENT_TASK)) {
            Field discriminator = field(task, "$t", "I");
            assertFalse(AccessFlags.STATIC.isSet(discriminator.getAccessFlags()));
            assertTrue(AccessFlags.FINAL.isSet(discriminator.getAccessFlags()));
        }
        for (Field field : classes.get(BACKGROUND_TASK).getFields()) assertNotEquals("$t", field.getName());
        Method current = method(CURRENT_TASK, "invoke", OBJECT);
        Method background = method(BACKGROUND_TASK, "invoke", OBJECT);
        for (Method task : Arrays.asList(current, background)) {
            assertTrue(AccessFlags.PUBLIC.isSet(task.getAccessFlags()));
            assertTrue(AccessFlags.FINAL.isSet(task.getAccessFlags()));
            assertFalse(AccessFlags.STATIC.isSet(task.getAccessFlags()));
        }
        Code currentCode = new Code(current);
        assertUnitReturn(currentCode, reachable(currentCode, currentCode.switchBranch(recallBranch)));
        assertUnitReturn(currentCode, reachable(currentCode, currentCode.switchBranch(genericBranch)));
        Code backgroundCode = new Code(background);
        assertUnitReturn(backgroundCode, reachable(backgroundCode, 0));
    }

    @Test public void actualPackedSwitchPreservesOnlyNonSafetyBranches() {
        Code code = new Code(method(CURRENT_TASK, "invoke", OBJECT));
        int read = fieldIndex(code, CURRENT_TASK, "$t");
        assertEquals(Opcode.IGET, code.at(read).getOpcode());
        int dispatch = code.switchIndex();
        assertEquals(((OneRegisterInstruction) code.at(read)).getRegisterA(),
                ((OneRegisterInstruction) code.at(dispatch)).getRegisterA());
        Set<Integer> one = reachable(code, code.switchBranch(recallBranch));
        Set<Integer> two = reachable(code, code.switchBranch(genericBranch));
        assertTrue("The profile's recall discriminator must reach the recall manager", calls(code, one, RECALL, "LJI"));
        assertFalse(calls(code, one, DEFAULTS, "LIZIZ"));
        if (latest) {
            assertTrue("The other safety-recall discriminator deletes through FeedDataContext defaults",
                    calls(code, two, DEFAULTS, "LIZIZ"));
            assertTrue("The separate delete branch still reports the safety-recall event",
                    containsString(code, two, "safety_check_recall"));
            assertUnitReturn(code, two);
            Set<Integer> drawAd = reachable(code, code.switchBranch(0));
            assertTrue(containsString(code, drawAd, "draw_ad"));
            assertFalse(calls(code, drawAd, DEFAULTS, "LIZIZ"));
            assertFalse(calls(code, drawAd, RECALL, "LJI"));
            Set<Integer> delayed = reachable(code, code.switchBranch(1));
            assertFalse(calls(code, delayed, DEFAULTS, "LIZIZ"));
            assertFalse(calls(code, delayed, RECALL, "LJI"));
        } else {
            assertTrue("The legacy generic branch retains its direct delete", calls(code, two, DEFAULTS, "LIZIZ"));
            assertFalse(calls(code, two, RECALL, "LJI"));
        }
        for (String panel : Arrays.asList("Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;",
                "Lcom/ss/android/ugc/aweme/feed/panel/FullFeedFragmentPanel;")) {
            assertFalse("The safety recall task bypasses the old panel delete hooks", calls(code, one, panel, "LJLJL"));
        }
    }

    @Test public void targetListItemSafetyDeleteSchedulesTheFourthRecallBranch() {
        if (!latest) return;
        Method source = method(DELETE_TASK_SOURCE, "LIZ", "V", "Ljava/util/ArrayList;", "LX/07jw;");
        Code code = new Code(source);
        int constructor = callIndex(code, CURRENT_TASK, "<init>", 0);
        RegisterRangeInstruction task = (RegisterRangeInstruction) code.at(constructor);
        assertEquals(8, task.getRegisterCount());
        int discriminator = task.getStartRegister() + task.getRegisterCount() - 1;
        assertEquals("Non-current safety-recall items use the second suppressed discriminator", 3,
                precedingLiteral(code, constructor, discriminator));
        assertScheduledObject(code, constructor);
    }

    @Test public void backgroundTaskPassesSafetyCheckAsTheDirectDeleteReasonRatherThanUsingLegacyPanelDeletion() {
        Code code = new Code(method(BACKGROUND_TASK, "invoke", OBJECT));
        int reason = stringIndex(code, "safety_check");
        int delete = callIndex(code, FEED_DATA, deleteMethod, 0);
        assertEquals(reason + 1, delete);
        MethodReference target = (MethodReference) reference(code.at(delete));
        assertEquals(DELETE_RESULT, target.getReturnType());
        assertEquals(Arrays.asList(STRING, STRING), target.getParameterTypes());
        assertEquals("The reason argument must come from the original safety_check constant",
                ((OneRegisterInstruction) code.at(reason)).getRegisterA(),
                ((FiveRegisterInstruction) code.at(delete)).getRegisterE());
        Set<Integer> body = reachable(code, 0);
        assertTrue(body.contains(delete));
        assertFalse(calls(code, body, RECALL, "LJI"));
        assertFalse(calls(code, body, "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;", "LJLJL"));
        assertFalse(calls(code, body, "Lcom/ss/android/ugc/aweme/feed/panel/FullFeedFragmentPanel;", "LJLJL"));
    }

    @Test public void responseCacheBookkeepingPrecedesBothAsynchronousTasksWithTheirActualDiscriminators() {
        Code code = new Code(method(RESPONSE_CALLBACK, "LIZ", "V", "Ljava/util/ArrayList;",
                legacy ? "LX/06fP;" : latest ? "LX/07jw;" : "LX/07hG;"));
        int cache = latest ? -1 : callIndex(code, legacy ? "LX/04Ov;" : "LX/0Bj9;", "LIZJ", 0);
        int currentConstructor = callIndex(code, CURRENT_TASK, "<init>", 0);
        int backgroundConstructor = callIndex(code, BACKGROUND_TASK, "<init>", 0);
        if (!latest) {
            assertTrue("Cache bookkeeping must remain before the current-item destructive task", cache < currentConstructor);
            assertTrue("Cache bookkeeping must remain before the background destructive task", cache < backgroundConstructor);
        }
        RegisterRangeInstruction current = (RegisterRangeInstruction) code.at(currentConstructor);
        assertEquals(8, current.getRegisterCount());
        int discriminatorRegister = current.getStartRegister() + current.getRegisterCount() - 1;
        assertEquals("The callback schedules the profile recall case", recallBranch,
                precedingLiteral(code, currentConstructor, discriminatorRegister));
        RegisterRangeInstruction background = (RegisterRangeInstruction) code.at(backgroundConstructor);
        assertEquals(8, background.getRegisterCount());
        assertScheduledObject(code, currentConstructor);
        assertScheduledObject(code, backgroundConstructor);
        assertEquals(-1, callIndexOrMissing(code, CURRENT_TASK, "<init>", currentConstructor + 1));
        assertEquals(-1, callIndexOrMissing(code, BACKGROUND_TASK, "<init>", backgroundConstructor + 1));
        assertEquals("These callbacks schedule the mutations rather than directly deleting from the cache stage", -1,
                callIndexOrMissing(code, RECALL, "LJI", 0));
        assertEquals(-1, callIndexOrMissing(code, FEED_DATA, deleteMethod, 0));
    }

    @Test public void recallAddsTheGlobalMarkerBeforeNotifyingPanelsAndTheGenericDeleteDefaultHasNoSafetyReason() {
        Code recall = new Code(method(RECALL, "LJI", "V", STRING));
        int add = callIndex(recall, "Ljava/util/Set;", "add", 0);
        int notify = callIndex(recall, "Lcom/ss/android/ugc/aweme/feed/adapter/FeedRecallHelper$FeedRecallCallBack;",
                legacy ? "LLILLL" : latest ? "LLJILLL" : "LLIZ", 0);
        assertTrue("Blocking only the later event would leave the recall set mutated", add < notify);
        Code defaults = new Code(method(DEFAULTS, "LIZIZ", DELETE_RESULT, FEED_DATA, STRING));
        int none = stringIndex(defaults, "none");
        int delete = callIndex(defaults, FEED_DATA, deleteMethod, 0);
        assertEquals(none + 1, delete);
        assertEquals(((OneRegisterInstruction) defaults.at(none)).getRegisterA(),
                ((FiveRegisterInstruction) defaults.at(delete)).getRegisterE());
    }

    @Test public void laterSetDataFilteringRemovesOrdinaryItemsWhoseAidsRemainInTheSameRecallSet() {
        Method filter = method(RECALL, "LIZIZ", "V", "Ljava/util/List;", "Z");
        assertTrue(AccessFlags.STATIC.isSet(filter.getAccessFlags()));
        Code code = new Code(filter);
        int gate = -1;
        int flagRegister = filter.getImplementation().getRegisterCount() - 1;
        for (int index = 0; index < code.instructions.size(); index++) {
            if (code.at(index).getOpcode() == Opcode.IF_EQZ
                    && ((OneRegisterInstruction) code.at(index)).getRegisterA() == flagRegister) { gate = index; break; }
        }
        assertTrue("The set-data recall filter must be gated by its boolean argument", gate >= 0);
        int contains = callIndex(code, "Ljava/util/Set;", "contains", 0);
        int remove = callIndex(code, "Ljava/util/Iterator;", "remove", contains + 1);
        assertTrue(gate < contains && contains < remove);
        assertTrue(fieldIndex(code, RECALL, "LIZIZ") < contains);
        assertTrue(stringIndex(code, "deleteFilterRecallAwemeForSetData: deleteAweme, aid = ") < remove);
        assertTrue("The true branch must reach the real iterator removal", reachable(code, gate + 1).contains(remove));
    }

    private static void assertScheduledObject(Code code, int constructor) {
        int schedule = callIndex(code, MAIN_THREAD, "LIZJ", constructor + 1);
        assertEquals("MainThreadHelper must receive this constructed task, not an unrelated callback",
                ((RegisterRangeInstruction) code.at(constructor)).getStartRegister(),
                ((FiveRegisterInstruction) code.at(schedule)).getRegisterC());
    }

    private static int precedingLiteral(Code code, int before, int register) {
        for (int index = before - 1; index >= 0; index--) {
            Instruction instruction = code.at(index);
            if (instruction instanceof NarrowLiteralInstruction && instruction instanceof OneRegisterInstruction
                    && ((OneRegisterInstruction) instruction).getRegisterA() == register) {
                return ((NarrowLiteralInstruction) instruction).getNarrowLiteral();
            }
        }
        throw new AssertionError("Missing constructor discriminator literal for register " + register);
    }

    private static void assertUnitReturn(Code code, Set<Integer> body) {
        boolean found = false;
        for (int index : body) {
            Object reference = reference(code.at(index));
            if (!(reference instanceof FieldReference)) continue;
            FieldReference field = (FieldReference) reference;
            if (!UNIT.equals(field.getDefiningClass()) || !"INSTANCE".equals(field.getName())) continue;
            assertEquals(UNIT, field.getType());
            assertEquals(Opcode.SGET_OBJECT, code.at(index).getOpcode());
            assertEquals(Opcode.RETURN_OBJECT, code.at(index + 1).getOpcode());
            assertEquals(((OneRegisterInstruction) code.at(index)).getRegisterA(),
                    ((OneRegisterInstruction) code.at(index + 1)).getRegisterA());
            found = true;
        }
        assertTrue("The branch must return the host Kotlin Unit singleton", found);
    }

    private static Set<Integer> reachable(Code code, int start) {
        Set<Integer> visited = new HashSet<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            int index = pending.remove();
            if (!visited.add(index)) continue;
            Instruction instruction = code.at(index);
            String opcode = instruction.getOpcode().name();
            if (opcode.startsWith("RETURN") || instruction.getOpcode() == Opcode.THROW) continue;
            assertFalse("Execution must not fall through into a switch payload", instruction instanceof SwitchPayload);
            if (opcode.startsWith("GOTO")) { pending.add(code.target(index)); continue; }
            if (opcode.startsWith("IF_")) pending.add(code.target(index));
            if (instruction.getOpcode() == Opcode.PACKED_SWITCH || instruction.getOpcode() == Opcode.SPARSE_SWITCH) {
                SwitchPayload payload = (SwitchPayload) code.at(code.target(index));
                for (SwitchElement element : payload.getSwitchElements()) pending.add(code.indexAt(code.offsets.get(index) + element.getOffset()));
            }
            assertTrue("A non-terminal instruction must have a successor", index + 1 < code.instructions.size());
            pending.add(index + 1);
        }
        return visited;
    }

    private static boolean calls(Code code, Set<Integer> body, String owner, String name) {
        for (int index : body) {
            Object reference = reference(code.at(index));
            if (reference instanceof MethodReference && owner.equals(((MethodReference) reference).getDefiningClass())
                    && name.equals(((MethodReference) reference).getName())) return true;
        }
        return false;
    }

    private static boolean containsString(Code code, Set<Integer> body, String value) {
        for (int index : body) {
            Object reference = reference(code.at(index));
            if (reference instanceof StringReference && value.equals(((StringReference) reference).getString())) return true;
        }
        return false;
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        assertNotNull(owner, classes.get(owner));
        for (Method method : classes.get(owner).getMethods()) {
            if (name.equals(method.getName()) && returns.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        throw new AssertionError("Missing " + owner + "->" + name + Arrays.toString(parameters) + returns);
    }

    private static Field field(String owner, String name, String type) {
        for (Field field : classes.get(owner).getFields()) {
            if (name.equals(field.getName()) && type.equals(field.getType())) return field;
        }
        throw new AssertionError("Missing " + owner + "->" + name + ':' + type);
    }

    private static int fieldIndex(Code code, String owner, String name) {
        for (int index = 0; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof FieldReference && owner.equals(((FieldReference) reference).getDefiningClass())
                    && name.equals(((FieldReference) reference).getName())) return index;
        }
        throw new AssertionError("Missing field reference " + owner + "->" + name);
    }

    private static int callIndex(Code code, String owner, String name, int from) {
        int index = callIndexOrMissing(code, owner, name, from);
        assertTrue("Missing call " + owner + "->" + name, index >= 0);
        return index;
    }

    private static int callIndexOrMissing(Code code, String owner, String name, int from) {
        for (int index = from; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof MethodReference && owner.equals(((MethodReference) reference).getDefiningClass())
                    && name.equals(((MethodReference) reference).getName())) return index;
        }
        return -1;
    }

    private static int stringIndex(Code code, String value) {
        for (int index = 0; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof StringReference && value.equals(((StringReference) reference).getString())) return index;
        }
        throw new AssertionError("Missing string " + value);
    }

    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static final class Code {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final Map<Integer, Integer> indexes = new HashMap<>();
        Code(Method method) {
            assertNotNull("Missing method body " + method.getDefiningClass() + "->" + method.getName(), method.getImplementation());
            int offset = 0;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                indexes.put(offset, instructions.size());
                offsets.add(offset);
                instructions.add(instruction);
                offset += instruction.getCodeUnits();
            }
        }
        Instruction at(int index) { return instructions.get(index); }
        int indexAt(int offset) {
            Integer index = indexes.get(offset);
            assertNotNull("Branch must target an instruction at " + offset, index);
            return index;
        }
        int target(int index) { return indexAt(offsets.get(index) + ((OffsetInstruction) at(index)).getCodeOffset()); }
        int switchIndex() {
            for (int index = 0; index < instructions.size(); index++) if (at(index).getOpcode() == Opcode.PACKED_SWITCH) return index;
            throw new AssertionError("Missing packed switch");
        }
        int switchBranch(int key) {
            int dispatch = switchIndex();
            SwitchPayload payload = (SwitchPayload) at(target(dispatch));
            for (SwitchElement element : payload.getSwitchElements()) {
                if (element.getKey() == key) return indexAt(offsets.get(dispatch) + element.getOffset());
            }
            throw new AssertionError("Missing packed-switch key " + key);
        }
    }
}
