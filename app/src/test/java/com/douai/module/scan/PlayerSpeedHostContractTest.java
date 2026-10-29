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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original APK facts distinguish playback speed commands from numeric media metrics. */
public final class PlayerSpeedHostContractTest {
    private static final String PREFIX = "Lcom/ss/android/ugc/aweme/";
    private static final String INFO = PREFIX + "video/IPlayerInfoProvider;";
    private static final String WRAPPER = PREFIX + "feed/controller/FeedPlayerWrapper;";
    private static final String MANAGER = PREFIX + "video/PlayerManager;";
    private static final String ABILITY = PREFIX + "o/oplayer/client/player/ability/DPlayAbility;";
    private static final String COMMAND = PREFIX + "player/sdk/api/PlayerCommand;";
    private static final String SPEED_COMMAND = PREFIX + "player/sdk/api/PlayerCommand$Getter$VideoPlaySpeed;";
    private static final String REAL_SPEED_COMMAND = PREFIX + "player/sdk/api/PlayerCommand$Getter$RealVideoPlaySpeed;";
    private static final String VIDEO_ENGINE = "Lcom/ss/ttvideoengine/TTVideoEngine;";
    private static final String CACHED_ENGINE = PREFIX + "player/sdk/impl/MTTVideoEngine;";
    private static final String BASE_ENGINE = PREFIX + "player/sdk/impl/util/MBaseTTVideoEngine;";
    private static final String PLAYBACK_PARAMS = "Lcom/ss/ttm/player/PlaybackParams;";
    private static final String MEDIA_PLAYER = "Lcom/ss/ttm/player/MediaPlayer;";
    private static final String TT_MEDIA_PLAYER = "Lcom/ss/ttm/player/TTMediaPlayer;";
    private static final String MEDIA_CLIENT = "Lcom/ss/ttm/player/MediaPlayerClient;";
    private static final String TT_PLAYER_CLIENT = "Lcom/ss/ttm/player/TTPlayerClient;";
    private static final String TT_PLAYER = "Lcom/ss/ttm/player/TTPlayer;";
    private static final String GESTURE_CONTAINER = PREFIX + "o/oplayer/client/gesture/detector/DVideoGestureContainer;";
    private static final String GESTURE_LISTENER = PREFIX + "o/oplayer/client/gesture/detector/DIVideoGestureListener;";
    private static final String MOTION_EVENT = "Landroid/view/MotionEvent;";
    private static final String QUERY_DESCRIPTOR = "(" + COMMAND + ")Ljava/lang/Object;";
    private static final Map<String, ClassDef> TYPES = new HashMap<>();
    private static File apk;
    private static String infoImplementation, commandDispatcher, previousMetricGetter, nativeDetector, frameContainer,
            mediaParamsCaller;

    @BeforeClass public static void readOriginalPlayerFacts() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(path);
        apk = new File(path);
        assertTrue("DOUAI_TEST_APK must be an original verified host", apk.isFile());
        boolean legacy = HostApkTestProfile.is406(apk);
        infoImplementation = legacy ? "LX/0DZZ;" : "LX/0G5v;";
        commandDispatcher = legacy ? "LX/0DMZ;" : "LX/0Fsa;";
        nativeDetector = legacy ? "LX/1Z4d;" : "LX/1eom;";
        frameContainer = legacy ? "LX/06ns;" : "LX/07rz;";
        mediaParamsCaller = legacy ? "LX/0Cz1;" : "LX/0FfW;";
        previousMetricGetter = legacy ? "LJFF" : "LJIIJ";
        Set<String> wanted = new HashSet<>(Arrays.asList(INFO, WRAPPER, MANAGER, SPEED_COMMAND,
                REAL_SPEED_COMMAND, infoImplementation, commandDispatcher, nativeDetector, GESTURE_CONTAINER, frameContainer,
                VIDEO_ENGINE, CACHED_ENGINE, BASE_ENGINE, PLAYBACK_PARAMS, MEDIA_PLAYER, TT_MEDIA_PLAYER,
                MEDIA_CLIENT, TT_PLAYER_CLIENT, TT_PLAYER, mediaParamsCaller));
        try (ZipFile archive = new ZipFile(apk)) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && !wanted.isEmpty()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (wanted.remove(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
            }
        }
        assertTrue("Missing original speed contract classes: " + wanted, wanted.isEmpty());
    }

    @AfterClass public static void releaseOriginalFacts() { TYPES.clear(); }

    @Test public void allSpeedReadRulesUseTheNamedSpeedCommandInsteadOfTheMediaMetric() throws Exception {
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.rulesFile(apk))) {
            rules = HostRuleSet.parse(input);
        }
        Map<String, String> expected = Map.of("adv_player_get_speed", WRAPPER,
                "adv_player_manager_get_speed", MANAGER, "adv_gesture_player_get_speed", INFO);
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            HostRule rule = rule(rules, entry.getKey());
            assertEquals(entry.getKey(), entry.getValue(), rule.getClassDescriptor());
            assertEquals("Speed reads must query PlayerCommand, not the legacy property-10 metric: " + entry.getKey(),
                    QUERY_DESCRIPTOR, rule.getMethodDescriptor());
            assertEquals(entry.getKey(), "LJI", rule.getMethodName());
            method(entry.getValue(), "LJI", "Ljava/lang/Object;", COMMAND);
        }
        assertCommandRule(rules, "adv_player_speed_command", SPEED_COMMAND);
        assertCommandRule(rules, "adv_player_real_speed_command", REAL_SPEED_COMMAND);
    }

    @Test public void bothVerifiedProfilesSelectTheSameSemanticCommandContract() throws Exception {
        for (String asset : new String[]{"douyin-host-rules.tsv", "douyin-host-rules-40.6.0.tsv"}) {
            HostRuleSet rules;
            try (FileInputStream input = new FileInputStream(HostApkTestProfile.assetFile(asset))) {
                rules = HostRuleSet.parse(input);
            }
            for (String id : new String[]{"adv_player_get_speed", "adv_player_manager_get_speed", "adv_gesture_player_get_speed"}) {
                assertEquals(asset + ":" + id, QUERY_DESCRIPTOR, rule(rules, id).getMethodDescriptor());
                assertEquals(asset + ":" + id, "LJI", rule(rules, id).getMethodName());
            }
            assertCommandRule(rules, "adv_player_speed_command", SPEED_COMMAND);
            assertCommandRule(rules, "adv_player_real_speed_command", REAL_SPEED_COMMAND);
        }
    }

    @Test public void originalFacadeQueriesForwardTheTypedCommandAndItsSingletonIsStaticFinal() {
        assertTrue(calls(method(WRAPPER, "LJI", "Ljava/lang/Object;", COMMAND), ABILITY, "LJI", "Ljava/lang/Object;", COMMAND));
        assertTrue(calls(method(MANAGER, "LJI", "Ljava/lang/Object;", COMMAND), INFO, "LJI", "Ljava/lang/Object;", COMMAND));
        for (String command : new String[]{SPEED_COMMAND, REAL_SPEED_COMMAND}) {
            Field singleton = null;
            for (Field field : TYPES.get(command).getFields()) if ("f".equals(field.getName())) singleton = field;
            assertNotNull("Named speed singleton " + command, singleton);
            assertEquals(command, singleton.getType());
            assertTrue(AccessFlags.PUBLIC.isSet(singleton.getAccessFlags()));
            assertTrue(AccessFlags.STATIC.isSet(singleton.getAccessFlags()));
            assertTrue(AccessFlags.FINAL.isSet(singleton.getAccessFlags()));
        }

        Set<String> unreadCommands = new HashSet<>(Arrays.asList(SPEED_COMMAND, REAL_SPEED_COMMAND));
        for (Method candidate : TYPES.get(infoImplementation).getMethods()) {
            if (!candidate.getReturnType().equals("F") || !candidate.getParameterTypes().isEmpty()
                    || candidate.getImplementation() == null) continue;
            for (var instruction : candidate.getImplementation().getInstructions()) {
                if (!(instruction instanceof ReferenceInstruction)) continue;
                Object reference = ((ReferenceInstruction) instruction).getReference();
                if (reference instanceof FieldReference) {
                    FieldReference field = (FieldReference) reference;
                    if (field.getName().equals("f")) unreadCommands.remove(field.getDefiningClass());
                }
            }
        }
        assertTrue("Host has distinct named float readers for both commands: " + unreadCommands, unreadCommands.isEmpty());
    }

    @Test public void desiredSpeedReadsPlaybackParametersWhileRealSpeedReadsTheEngineReport() {
        Method dispatch = method(commandDispatcher, "LJI", "Ljava/lang/Object;", COMMAND);
        assertTrue(callsInCommandBranch(dispatch, SPEED_COMMAND, VIDEO_ENGINE, "getPlaybackParams", PLAYBACK_PARAMS));
        assertTrue(callsInCommandBranch(dispatch, SPEED_COMMAND, PLAYBACK_PARAMS, "getSpeed", "F"));
        assertFalse(callsInCommandBranch(dispatch, SPEED_COMMAND, VIDEO_ENGINE, "getRealSpeed", "F"));
        assertTrue(callsInCommandBranch(dispatch, REAL_SPEED_COMMAND, VIDEO_ENGINE, "getRealSpeed", "F"));
        assertFalse(callsInCommandBranch(dispatch, REAL_SPEED_COMMAND, VIDEO_ENGINE, "getPlaybackParams", PLAYBACK_PARAMS));
    }

    @Test public void originalSdkBoundariesIncludeTheCachedOverrideAndItsSuperclassDelegation() throws Exception {
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.rulesFile(apk))) {
            rules = HostRuleSet.parse(input);
        }
        String[][] boundaries = {
                {"adv_player_engine_set_params", VIDEO_ENGINE, "setPlaybackParams", "(" + PLAYBACK_PARAMS + ")V"},
                {"adv_player_cached_engine_set_params", CACHED_ENGINE, "setPlaybackParams", "(" + PLAYBACK_PARAMS + ")V"},
                {"adv_player_media_set_params", TT_MEDIA_PLAYER, "setPlaybackParams", "(" + PLAYBACK_PARAMS + ")V"},
                {"adv_player_native_float_option", TT_PLAYER, "setFloatOption", "(IF)I"}
        };
        for (String[] target : boundaries) {
            HostRule boundary = rule(rules, target[0]);
            assertEquals(target[0], target[1], boundary.getClassDescriptor());
            assertEquals(target[0], target[2], boundary.getMethodName());
            assertEquals(target[0], target[3], boundary.getMethodDescriptor());
            Method actual = target[0].equals("adv_player_native_float_option")
                    ? method(TT_PLAYER, "setFloatOption", "I", "I", "F")
                    : method(target[1], "setPlaybackParams", "V", PLAYBACK_PARAMS);
            assertFalse(target[0], AccessFlags.STATIC.isSet(actual.getAccessFlags()));
            assertFalse(target[0], AccessFlags.ABSTRACT.isSet(actual.getAccessFlags()));
            assertFalse(target[0], AccessFlags.NATIVE.isSet(actual.getAccessFlags()));
        }
        assertEquals(BASE_ENGINE, TYPES.get(CACHED_ENGINE).getSuperclass());
        assertEquals(VIDEO_ENGINE, TYPES.get(BASE_ENGINE).getSuperclass());
        assertTrue(TYPES.get(TT_MEDIA_PLAYER).getInterfaces().contains(MEDIA_PLAYER));
        assertEquals(MEDIA_CLIENT, TYPES.get(TT_PLAYER_CLIENT).getSuperclass());
        Method cached = method(CACHED_ENGINE, "setPlaybackParams", "V", PLAYBACK_PARAMS);
        assertTrue(calls(cached, BASE_ENGINE, "setPlaybackParams", "V", PLAYBACK_PARAMS));
        assertTrue(calls(method(BASE_ENGINE, "setPlaybackParams", "V", PLAYBACK_PARAMS),
                VIDEO_ENGINE, "setPlaybackParams", "V", PLAYBACK_PARAMS));
        assertTrue(calls(method(TT_MEDIA_PLAYER, "setPlaybackParams", "V", PLAYBACK_PARAMS),
                MEDIA_CLIENT, "LJJJJLL", "V", PLAYBACK_PARAMS));
        assertTrue("The cached override can skip the base body, so binding cannot assume it always descends",
                conditionallySkipsSuper(cached, BASE_ENGINE));
    }

    @Test public void sdkMapsPlaybackSpeedToNativeOptionSixtyWithoutWritingPitch() {
        Method nativeWrite = method(TT_PLAYER, "setFloatOption", "I", "I", "F");
        assertTrue(calls(nativeWrite, TT_PLAYER, "_setFloatValue", "I", "J", "I", "F"));
        Method forward = method(TT_PLAYER_CLIENT, "LJJJJLL", "V", PLAYBACK_PARAMS);
        int speedRegister = -1, optionRegister = -1, writes = 0;
        boolean speedResultPending = false;
        for (var instruction : forward.getImplementation().getInstructions()) {
            if (speedResultPending && instruction.getOpcode() == Opcode.MOVE_RESULT) {
                speedRegister = ((OneRegisterInstruction) instruction).getRegisterA();
            }
            speedResultPending = false;
            if (instruction instanceof NarrowLiteralInstruction
                    && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == 60) {
                optionRegister = ((OneRegisterInstruction) instruction).getRegisterA();
            }
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference target = (MethodReference) reference;
            if (target.getDefiningClass().equals(PLAYBACK_PARAMS) && target.getName().equals("getSpeed")) {
                speedResultPending = true;
            }
            if (target.getDefiningClass().equals(TT_PLAYER) && target.getName().equals("setFloatOption")) {
                FiveRegisterInstruction invoke = (FiveRegisterInstruction) instruction;
                assertTrue("Native option comes from the literal 60", optionRegister >= 0);
                assertTrue("Native value comes from PlaybackParams.getSpeed", speedRegister >= 0);
                assertEquals(optionRegister, invoke.getRegisterD());
                assertEquals(speedRegister, invoke.getRegisterE());
                writes++;
            }
        }
        assertEquals("PlaybackParams bridge writes exactly the speed option", 1, writes);
        int fieldsWritten = 0;
        for (var instruction : method(PLAYBACK_PARAMS, "setSpeed", PLAYBACK_PARAMS, "F")
                .getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) instruction).getReference() instanceof FieldReference)) continue;
            FieldReference field = (FieldReference) ((ReferenceInstruction) instruction).getReference();
            assertEquals(Opcode.IPUT, instruction.getOpcode());
            assertEquals(PLAYBACK_PARAMS, field.getDefiningClass());
            assertEquals("LIZLLL", field.getName());
            assertEquals("F", field.getType());
            fieldsWritten++;
        }
        assertEquals("setSpeed changes the speed field without touching pitch or other options", 1, fieldsWritten);
    }

    @Test public void sdkDeoptimizationCallersActuallyInvokeTheirScannedSpeedCallees() throws Exception {
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.rulesFile(apk))) {
            rules = HostRuleSet.parse(input);
        }
        String[][] callers = {
                {"adv_player_sdk_base_params_caller", BASE_ENGINE, "setPlaybackParams", VIDEO_ENGINE},
                {"adv_player_sdk_speed_params_caller", TT_PLAYER_CLIENT, "LJJJJLL", TT_PLAYER},
                {"adv_player_sdk_float_option_caller", TT_PLAYER_CLIENT, "LJJIJIIJI", TT_PLAYER},
                {"adv_player_sdk_media_params_caller", mediaParamsCaller, "setPlaybackParams", MEDIA_PLAYER}
        };
        for (String[] target : callers) {
            boolean floatOption = target[0].equals("adv_player_sdk_float_option_caller");
            HostRule caller = rule(rules, target[0]);
            assertEquals(target[1], caller.getClassDescriptor());
            assertEquals(target[2], caller.getMethodName());
            assertEquals(floatOption ? "(IF)I" : "(" + PLAYBACK_PARAMS + ")V", caller.getMethodDescriptor());
            assertTrue(caller.getInvokeOwners().contains(target[3]));
            Method actual = floatOption ? method(target[1], target[2], "I", "I", "F")
                    : method(target[1], target[2], "V", PLAYBACK_PARAMS);
            assertFalse(AccessFlags.STATIC.isSet(actual.getAccessFlags()));
            assertFalse(AccessFlags.ABSTRACT.isSet(actual.getAccessFlags()));
            assertFalse(AccessFlags.NATIVE.isSet(actual.getAccessFlags()));
            if (target[3].equals(VIDEO_ENGINE) || target[3].equals(MEDIA_PLAYER)) {
                assertTrue(calls(actual, target[3], "setPlaybackParams", "V", PLAYBACK_PARAMS));
            } else {
                assertTrue(calls(actual, TT_PLAYER, "setFloatOption", "I", "I", "F"));
            }
        }
    }

    @Test public void ancestorContainersFeedTheirOwnDetectorAfterDispatchingTheEventToChildren() throws Exception {
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(HostApkTestProfile.rulesFile(apk))) {
            rules = HostRuleSet.parse(input);
        }
        HostRule detectorRule = rule(rules, "adv_gesture_native_touch_detector");
        assertEquals(nativeDetector, detectorRule.getClassDescriptor());
        assertEquals("LIZ", detectorRule.getMethodName());
        assertEquals("(" + MOTION_EVENT + ")V", detectorRule.getMethodDescriptor());
        assertTrue(calls(method(nativeDetector, "LIZ", "V", MOTION_EVENT),
                GESTURE_LISTENER, "onTouchEvent", "V", MOTION_EVENT));

        Map<String, String> owners = Map.of("adv_gesture_video_container_detector", GESTURE_CONTAINER,
                "adv_gesture_legacy_container_detector", frameContainer);
        for (Map.Entry<String, String> entry : owners.entrySet()) {
            String owner = entry.getValue();
            HostRule getterRule = rule(rules, entry.getKey());
            assertEquals(owner, getterRule.getClassDescriptor());
            assertEquals("getDetector", getterRule.getMethodName());
            assertEquals("()" + nativeDetector, getterRule.getMethodDescriptor());
            assertTrue(getterRule.getFieldOwners().contains(owner));
            assertTrue(getterRule.getFieldTypes().contains(nativeDetector));
            String fieldName = owner.equals(GESTURE_CONTAINER) ? "a" : "detector";
            assertTrue(getterRule.getFieldNames().contains(fieldName));
            assertReturnsOwnField(method(owner, "getDetector", nativeDetector), owner, fieldName, nativeDetector);

            Method dispatch = method(owner, "dispatchTouchEvent", "Z", MOTION_EVENT);
            int position = 0, superclassDispatch = -1, detectorCalls = 0;
            for (var instruction : dispatch.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction
                        && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
                    MethodReference target = (MethodReference) ((ReferenceInstruction) instruction).getReference();
                    if (instruction.getOpcode() == Opcode.INVOKE_SUPER && target.getName().equals("dispatchTouchEvent")) {
                        superclassDispatch = position;
                    }
                    if (target.getDefiningClass().equals(nativeDetector) && target.getName().equals("LIZ")
                            && target.getParameterTypes().equals(List.of(MOTION_EVENT))) {
                        assertTrue(owner + " must feed its detector only after child dispatch returns",
                                superclassDispatch >= 0 && position > superclassDispatch);
                        detectorCalls++;
                    }
                }
                position++;
            }
            assertEquals(owner + " handles both child-detection modes", 2, detectorCalls);
        }
    }

    @Test public void theFormerZeroArgumentFloatGetterReadsPropertyTenNotTheNamedSpeedCommand() {
        for (String owner : new String[]{WRAPPER}) {
            Method metric = method(owner, previousMetricGetter, "F");
            boolean propertyTen = false, namedCommand = false;
            assertNotNull(metric.getImplementation());
            for (var instruction : metric.getImplementation().getInstructions()) {
                if (instruction instanceof NarrowLiteralInstruction
                        && ((NarrowLiteralInstruction) instruction).getNarrowLiteral() == 10) propertyTen = true;
                if (instruction instanceof ReferenceInstruction) {
                    Object reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof FieldReference
                            && ((FieldReference) reference).getDefiningClass().equals(SPEED_COMMAND)) namedCommand = true;
                }
            }
            assertTrue("The old getter's property-10 metric must not become a restoration baseline", propertyTen);
            assertFalse("A numeric media metric is not VideoPlaySpeed", namedCommand);
        }
    }

    private static HostRule rule(HostRuleSet rules, String id) {
        for (HostRule rule : rules.getRules()) if (rule.getId().equals(id)) return rule;
        fail("Missing playback contract rule " + id); return null;
    }

    private static void assertCommandRule(HostRuleSet rules, String id, String descriptor) {
        HostRule commandRule = rule(rules, id);
        assertEquals(id, HostRule.Kind.CLASS, commandRule.getKind());
        assertEquals(id, descriptor, commandRule.getClassDescriptor());
        assertTrue(id, commandRule.getFieldNames().contains("f"));
        assertTrue(id, commandRule.getFieldTypes().contains(descriptor));
    }

    private static void assertReturnsOwnField(Method getter, String owner, String name, String type) {
        assertNotNull(getter.getImplementation());
        var instructions = getter.getImplementation().getInstructions().iterator();
        var read = instructions.next();
        assertEquals(Opcode.IGET_OBJECT, read.getOpcode());
        FieldReference field = (FieldReference) ((ReferenceInstruction) read).getReference();
        assertEquals(owner, field.getDefiningClass());
        assertEquals(name, field.getName());
        assertEquals(type, field.getType());
        var result = instructions.next();
        assertEquals(Opcode.RETURN_OBJECT, result.getOpcode());
        assertEquals(((TwoRegisterInstruction) read).getRegisterA(), ((OneRegisterInstruction) result).getRegisterA());
        assertFalse("Container getter returns the same field object directly", instructions.hasNext());
    }

    private static Method method(String owner, String name, String result, String... arguments) {
        ClassDef type = TYPES.get(owner);
        assertNotNull(owner, type);
        for (Method method : type.getMethods()) if (method.getName().equals(name)
                && method.getReturnType().equals(result) && method.getParameterTypes().equals(List.of(arguments))) return method;
        fail(owner + "->" + name + Arrays.toString(arguments) + result); return null;
    }

    private static boolean calls(Method source, String owner, String name, String result, String... arguments) {
        assertNotNull(source.getImplementation());
        for (var instruction : source.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference target = (MethodReference) reference;
            if (target.getDefiningClass().equals(owner) && target.getName().equals(name)
                    && target.getReturnType().equals(result) && target.getParameterTypes().equals(List.of(arguments))) return true;
        }
        return false;
    }

    private static boolean conditionallySkipsSuper(Method source, String parent) {
        Map<Integer, Instruction> code = new LinkedHashMap<>();
        int offset = 0, superOffset = -1;
        for (Instruction instruction : source.getImplementation().getInstructions()) {
            code.put(offset, instruction);
            if (instruction.getOpcode() == Opcode.INVOKE_SUPER && instruction instanceof ReferenceInstruction) {
                Object reference = ((ReferenceInstruction) instruction).getReference();
                if (reference instanceof MethodReference
                        && ((MethodReference) reference).getDefiningClass().equals(parent)
                        && ((MethodReference) reference).getName().equals("setPlaybackParams")) superOffset = offset;
            }
            offset += instruction.getCodeUnits();
        }
        if (superOffset < 0) return false;
        for (Map.Entry<Integer, Instruction> entry : code.entrySet()) {
            Instruction instruction = entry.getValue();
            if (entry.getKey() >= superOffset || !instruction.getOpcode().name().startsWith("IF_")
                    || !(instruction instanceof OffsetInstruction)) continue;
            int target = entry.getKey() + ((OffsetInstruction) instruction).getCodeOffset();
            if (target > superOffset && code.containsKey(target)
                    && code.get(target).getOpcode() == Opcode.RETURN_VOID) return true;
        }
        return false;
    }

    private static boolean callsInCommandBranch(Method source, String command,
                                                String owner, String name, String result) {
        assertNotNull(source.getImplementation());
        boolean inBranch = false;
        for (var instruction : source.getImplementation().getInstructions()) {
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (instruction.getOpcode() == Opcode.INSTANCE_OF && reference instanceof TypeReference) {
                if (inBranch) return false;
                inBranch = command.equals(((TypeReference) reference).getType());
            }
            if (inBranch && reference instanceof MethodReference) {
                MethodReference target = (MethodReference) reference;
                if (target.getDefiningClass().equals(owner) && target.getName().equals(name)
                        && target.getReturnType().equals(result) && target.getParameterTypes().isEmpty()) return true;
            }
        }
        return false;
    }
}
