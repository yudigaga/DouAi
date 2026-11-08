package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Independent original DEX facts for exact send ownership; no production rule mapping is an oracle. */
public final class HashLongPressVoiceHostContractTest {
    private static final String PREFIX = "Lcom/ss/android/ugc/aweme/";
    private static final String UI = PREFIX + "im/business/chat/feature/input/drawer/new/InputDrawerUI2;";
    private static final String BASE = PREFIX + "comment/keyboard/refactor/BaseCmtInputModule;";
    private static final String PUBLISH = PREFIX + "comment/keyboard/refactor/inputbar/publishbtn/PublishBtnModule;";
    private static final String EDIT = PREFIX + "comment/keyboard/refactor/inputbar/edit/AbsInputBarEditModule;";
    private static final String COMMENT = PREFIX + "comment/model/Comment;";
    private static final String VM = PREFIX + "arch/logic/StateViewModel;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static boolean old;
    private static boolean latest;
    private static String style, factory, state, outer, pipeline, click;

    @BeforeClass public static void loadOriginalFacts() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(path);
        File apk = new File(path);
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        old = profile == HostAdaptation.PROFILE_406;
        latest = profile == HostAdaptation.PROFILE_410;
        style = old ? "LX/0yPh;" : latest ? "LX/126m;" : "LX/13jo;";
        factory = old ? "LX/0yPi;" : latest ? "LX/126i;" : "LX/13jl;";
        state = old ? "LX/0yK6;" : latest ? "LX/1209;" : "LX/13e1;";
        outer = old ? "LX/0yFT;" : latest ? "LX/11sf;" : "LX/13ZF;";
        pipeline = old ? "LX/0yFX;" : latest ? "LX/11sr;" : "LX/13ZJ;";
        click = old ? "LY/ACListenerS916S0100000_31;"
                : latest ? "LY/ACListenerS875S0100000_31;" : "LY/ACListenerS824S0100000_18;";
        Set<String> wanted = new HashSet<>(Arrays.asList(UI, BASE, PUBLISH, EDIT, style, factory, state, outer, pipeline, click));
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && !wanted.isEmpty()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (wanted.remove(type.getType())) classes.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
            }
        }
        assertTrue("Missing original send classes: " + wanted, wanted.isEmpty());
    }

    @AfterClass public static void release() { classes.clear(); }

    @Test public void messageSendListenerIsBoundToSWithItsExactDispatcherBranch() {
        assertEquals(PREFIX + "im/sdk/widget/AutoRTLImageView;", field(UI, "s").getType());
        assertEquals(PREFIX + "im/widget/SearchableEditText;", field(UI, "m").getType());
        assertEquals(PREFIX + "im/sdk/chat/SessionInfo;", field(UI, "e").getType());
        List<Instruction> code = code(method(UI, "onViewCreated", "V", "Landroid/view/View;"));
        if (latest) {
            Method sendClick = method(click, "onClick", "V", "Landroid/view/View;");
            assertTrue("The listener's 41.0.0 dispatcher branch must emit the send-after-association action",
                    dispatchCaseCallsString(sendClick, 0x2b, "send_after_association"));
            return;
        }
        boolean proved = false;
        for (int index = 0; index < code.size(); index++) {
            Instruction instruction = code.get(index);
            Object reference = reference(instruction);
            if (instruction.getOpcode() != Opcode.IGET_OBJECT || !(reference instanceof FieldReference)
                    || !((FieldReference) reference).getDefiningClass().equals(UI)
                    || !((FieldReference) reference).getName().equals("s")) continue;
            int viewRegister = ((OneRegisterInstruction) instruction).getRegisterA();
            int listenerRegister = -1;
            boolean branch = false, constructor = false;
            for (int offset = index + 1; offset < Math.min(index + 10, code.size()); offset++) {
                Instruction next = code.get(offset);
                Object ref = reference(next);
                if (next.getOpcode() == Opcode.NEW_INSTANCE && ref instanceof TypeReference
                        && ((TypeReference) ref).getType().equals(click)) {
                    listenerRegister = ((OneRegisterInstruction) next).getRegisterA();
                }
                if (next instanceof NarrowLiteralInstruction
                        && ((NarrowLiteralInstruction) next).getNarrowLiteral()
                        == (old ? 0x23 : latest ? 0x2b : 0x72)) branch = true;
                if (ref instanceof MethodReference && ((MethodReference) ref).getDefiningClass().equals(click)
                        && ((MethodReference) ref).getName().equals("<init>")) constructor = true;
                if (!(ref instanceof MethodReference) || !(next instanceof FiveRegisterInstruction)) continue;
                MethodReference call = (MethodReference) ref;
                if (!call.getParameterTypes().equals(Arrays.asList("Landroid/view/View$OnClickListener;", "Landroid/view/View;"))) continue;
                FiveRegisterInstruction registers = (FiveRegisterInstruction) next;
                if (branch && constructor && listenerRegister == registers.getRegisterC()
                        && viewRegister == registers.getRegisterD()) proved = true;
            }
        }
        assertTrue("Exact native send view/dispatcher pairing changed", proved);
    }

    @Test public void commentStylesAndEditorShareTheSameNativeInputState() {
        assertEquals(BASE, classes.get(PUBLISH).getSuperclass());
        assertEquals(BASE, classes.get(EDIT).getSuperclass());
        assertEquals(state, field(BASE, "e").getType());
        assertEquals(factory, field(PUBLISH, "n").getType());
        assertEquals("Ljava/util/Map;", field(factory, "LIZ").getType());
        assertEquals(state, field(style, "LIZ").getType());
        assertEquals("Landroid/widget/TextView;", field(style, "LIZIZ").getType());
        assertEquals("Landroid/view/ViewGroup;", field(style, "LIZJ").getType());
        assertEquals(VM, field(style, "LIZLLL").getType());
        assertEquals(PREFIX + "comment/widget/CommentMentionEditText;", field(EDIT, "n").getType());
        String bind = latest ? "LJJIIZI" : "LJJIIZ";
        assertNotNull(method(EDIT, bind, "V", "Landroid/view/View;"));
        assertNotNull(method(PUBLISH, bind, "V", "Landroid/view/View;"));
        assertNotNull(method(PUBLISH, old ? "LJJ" : latest ? "LJI" : "LJFF", "V",
                old ? "LX/0zh1;" : latest ? "LX/13RB;" : "LX/14xJ;"));
    }

    @Test public void replyIdentityComesFromTheActivePublishPresenterRatherThanAnUnrelatedComment() {
        assertTrue(classes.get(outer).getInterfaces().contains(field(state, "E").getType()));
        assertEquals(pipeline, field(outer, "LIZIZ").getType());
        List<Instruction> getter = code(method(pipeline, "LJIIJJI", COMMENT));
        boolean nativeReply = false;
        for (Instruction instruction : getter) {
            Object ref = reference(instruction);
            if (ref instanceof MethodReference) {
                MethodReference call = (MethodReference) ref;
                if (call.getDefiningClass().equals(PREFIX + "comment/commentinputmanager/presenter/PublishPresenter;")
                        && call.getName().equals("LJIJI") && call.getReturnType().equals(COMMENT)
                        && call.getParameterTypes().isEmpty()) nativeReply = true;
            }
        }
        assertTrue("Current reply identity getter changed", nativeReply);
    }

    private static Field field(String owner, String name) {
        for (Field field : classes.get(owner).getFields()) if (field.getName().equals(name)) return field;
        throw new AssertionError(owner + "." + name);
    }
    private static Method method(String owner, String name, String returns, String... parameters) {
        for (Method method : classes.get(owner).getMethods()) {
            if (method.getName().equals(name) && method.getReturnType().equals(returns)
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        throw new AssertionError(owner + "." + name);
    }
    private static List<Instruction> code(Method method) {
        assertNotNull(method.getImplementation());
        List<Instruction> code = new ArrayList<>();
        method.getImplementation().getInstructions().forEach(code::add);
        return code;
    }

    private static boolean dispatchCaseCallsString(Method method, int key, String value) {
        List<Instruction> instructions = code(method);
        int[] offsets = new int[instructions.size()];
        Map<Integer, Integer> indexByOffset = new HashMap<>();
        int offset = 0;
        for (int i = 0; i < instructions.size(); i++) {
            offsets[i] = offset;
            indexByOffset.put(offset, i);
            offset += instructions.get(i).getCodeUnits();
        }
        for (int i = 0; i < instructions.size(); i++) {
            Instruction dispatch = instructions.get(i);
            if (dispatch.getOpcode() != Opcode.PACKED_SWITCH || !(dispatch instanceof OffsetInstruction)) continue;
            int payloadOffset = offsets[i] + ((OffsetInstruction) dispatch).getCodeOffset();
            Integer payloadIndex = indexByOffset.get(payloadOffset);
            if (payloadIndex == null || !(instructions.get(payloadIndex) instanceof SwitchPayload)) continue;
            for (SwitchElement element : ((SwitchPayload) instructions.get(payloadIndex)).getSwitchElements()) {
                if (element.getKey() != key) continue;
                Integer start = indexByOffset.get(offsets[i] + element.getOffset());
                if (start == null) return false;
                ArrayDeque<Integer> pending = new ArrayDeque<>();
                Set<Integer> visited = new HashSet<>();
                pending.add(start);
                while (!pending.isEmpty()) {
                    int current = pending.remove();
                    if (!visited.add(current)) continue;
                    Instruction instruction = instructions.get(current);
                    Object reference = reference(instruction);
                    if (reference instanceof StringReference
                            && value.equals(((StringReference) reference).getString())) return true;
                    Opcode opcode = instruction.getOpcode();
                    if (opcode.name().startsWith("RETURN") || opcode == Opcode.THROW) continue;
                    if (opcode.name().startsWith("GOTO") || opcode.name().startsWith("IF_")) {
                        if (instruction instanceof OffsetInstruction) {
                            Integer target = indexByOffset.get(offsets[current]
                                    + ((OffsetInstruction) instruction).getCodeOffset());
                            if (target != null) pending.add(target);
                        }
                        if (opcode.name().startsWith("GOTO")) continue;
                    }
                    if (current + 1 < instructions.size()) pending.add(current + 1);
                }
            }
        }
        return false;
    }
    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }
}
