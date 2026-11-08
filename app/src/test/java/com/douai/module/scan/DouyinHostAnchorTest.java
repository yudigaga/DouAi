package com.douai.module.scan;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DouyinHostAnchorTest {
    @Test public void checkedVoiceSubmitStartsHostUploadBeforeReturningFalse() throws Exception {
        String apkPath = System.getenv("DOUAI_TEST_APK");
        File apk = apkPath == null ? null : new File(apkPath);
        Assume.assumeTrue(apk != null && apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        boolean legacy = profile.versionCode == 400601L;
        boolean latest = profile.versionCode == 410001L;
        String callbackType = legacy ? "LY/AObjectS313S0200000_35;"
                : latest ? "LY/AObjectS314S0200000_35;" : "LY/AObjectS307S0200000_36;";
        String modelType = legacy ? "LX/0yFs;" : latest ? "LX/11tW;" : "LX/13Zi;";
        Method checked = null;
        Method callbackConstructor = null;
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && (checked == null || callbackConstructor == null)) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        if (callbackType.equals(type.getType())) {
                            for (Method method : type.getMethods()) {
                                if ("<init>".equals(method.getName()) && method.getParameterTypes().equals(
                                        Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;", "I"))) {
                                    callbackConstructor = method;
                                }
                            }
                        }
                        if (!"Lcom/ss/android/ugc/aweme/comment/data/publish/CommentPublishRequest;"
                                .equals(type.getType())) continue;
                        for (Method method : type.getMethods()) {
                            if ("LJIILLIIL".equals(method.getName()) && "Z".equals(method.getReturnType())
                                    && method.getParameterTypes().equals(Collections.singletonList(modelType))) {
                                checked = method;
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Missing checked host comment publisher", checked != null && checked.getImplementation() != null);
        assertTrue("Missing host audio callback constructor",
                callbackConstructor != null && callbackConstructor.getImplementation() != null);
        int firstCallbackArgument = callbackConstructor.getImplementation().getRegisterCount() - 3;
        int secondCallbackArgument = callbackConstructor.getImplementation().getRegisterCount() - 2;
        Map<String, Integer> callbackFields = new HashMap<>();
        for (Instruction instruction : callbackConstructor.getImplementation().getInstructions()) {
            if (instruction.getOpcode() != Opcode.IPUT_OBJECT) continue;
            FieldReference field = (FieldReference) ((ReferenceInstruction) instruction).getReference();
            callbackFields.put(field.getName(), ((TwoRegisterInstruction) instruction).getRegisterA());
        }
        assertEquals("Host callback l0 stores the model argument",
                Integer.valueOf(latest ? firstCallbackArgument : secondCallbackArgument), callbackFields.get("l0"));
        assertEquals("Host callback l1 stores the request argument",
                Integer.valueOf(latest ? secondCallbackArgument : firstCallbackArgument), callbackFields.get("l1"));
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction instruction : checked.getImplementation().getInstructions()) instructions.add(instruction);
        Map<Integer, String> objectRegisters = new HashMap<>();
        int checkedParameterBase = checked.getImplementation().getRegisterCount() - 2;
        objectRegisters.put(checkedParameterBase, "request");
        objectRegisters.put(checkedParameterBase + 1, "model");
        int callbackRegister = -1;
        boolean uploadReturnsFalse = false;
        for (int index = 0; index + 1 < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            if (instruction.getOpcode() == Opcode.MOVE_OBJECT
                    || instruction.getOpcode() == Opcode.MOVE_OBJECT_FROM16
                    || instruction.getOpcode() == Opcode.MOVE_OBJECT_16) {
                TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
                String source = objectRegisters.get(move.getRegisterB());
                if (source == null) objectRegisters.remove(move.getRegisterA());
                else objectRegisters.put(move.getRegisterA(), source);
            }
            if (!(instruction instanceof ReferenceInstruction)) continue;
            Object reference = ((ReferenceInstruction) instruction).getReference();
            if (!(reference instanceof MethodReference)) continue;
            MethodReference call = (MethodReference) reference;
            if (callbackType.equals(call.getDefiningClass())
                    && "<init>".equals(call.getName()) && call.getParameterTypes().equals(
                    Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;", "I"))) {
                assertTrue("Host audio callback constructor uses explicit argument registers",
                        instruction instanceof FiveRegisterInstruction);
                FiveRegisterInstruction construct = (FiveRegisterInstruction) instruction;
                assertEquals("First callback argument retains the profiled role",
                        latest ? "model" : "request", objectRegisters.get(construct.getRegisterD()));
                assertEquals("Second callback argument retains the profiled role",
                        latest ? "request" : "model", objectRegisters.get(construct.getRegisterE()));
                callbackRegister = construct.getRegisterC();
            }
            if (!"Lcom/ss/android/ugc/aweme/comment/helper/AudioUploader;".equals(call.getDefiningClass())
                    || !(latest ? "LJIIIIZZ" : "LJFF").equals(call.getName())) continue;
            assertTrue("Host constructs the owned callback before starting its audio upload", callbackRegister >= 0);
            FiveRegisterInstruction upload = (FiveRegisterInstruction) instruction;
            if (latest) {
                assertEquals("410 uses the asynchronous uploader overload with both callbacks",
                        Arrays.asList("LX/11uI;", "Ljava/lang/String;", callbackType, "LY/AObjectS825S0100000_35;"),
                        call.getParameterTypes());
                assertEquals("The checked publisher passes the owned callback as uploader arg 2",
                        callbackRegister, upload.getRegisterF());
                continue;
            }
            assertEquals("The upload receives the callback whose l0 is model and l1 is request",
                    callbackRegister, upload.getRegisterF());
            Instruction following = instructions.get(index + 1);
            assertEquals("Upload branch must retain its checked-submit return contract",
                    com.android.tools.smali.dexlib2.Opcode.RETURN, following.getOpcode());
            int returnedRegister = ((OneRegisterInstruction) following).getRegisterA();
            // The checked publisher initializes the voice-upload result to false.
            for (int previous = 0; previous < index; previous++) {
                Instruction value = instructions.get(previous);
                if (value instanceof OneRegisterInstruction
                        && ((OneRegisterInstruction) value).getRegisterA() == returnedRegister) {
                    if (value.getOpcode() == com.android.tools.smali.dexlib2.Opcode.CONST_4
                            && ((NarrowLiteralInstruction) value).getNarrowLiteral() == 0) {
                        uploadReturnsFalse = true;
                    }
                    break;
                }
            }
        }
        if (!latest) assertTrue("Host upload start must not be interpreted as checked-submit rejection", uploadReturnsFalse);
    }

    @Test public void downloadDirectoryResultContractExistsInTheTargetHost() throws Exception {
        String apkPath = System.getenv("DOUAI_TEST_APK");
        File apk = apkPath == null ? null : new File(apkPath);
        Assume.assumeTrue(apk != null && apk.isFile());
        Set<String> required = new HashSet<>(Arrays.asList(
                "Landroidx/activity/result/contract/ActivityResultContracts$StartActivityForResult;",
                "Landroidx/activity/result/ActivityResult;"));
        Set<String> resultMethods = new HashSet<>();
        try (ZipFile archive = new ZipFile(apk)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && !required.isEmpty()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) {
                        required.remove(type.getType());
                        if ("Landroidx/activity/result/ActivityResult;".equals(type.getType())) {
                            for (Method method : type.getMethods()) {
                                if (method.getParameterTypes().isEmpty()) {
                                    resultMethods.add(method.getName() + ":" + method.getReturnType());
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Missing host download picker contracts: " + required, required.isEmpty());
        assertTrue(resultMethods.contains("getResultCode:I"));
        assertTrue(resultMethods.contains("getData:Landroid/content/Intent;"));
    }

    @Test
    public void verifiedFeedAnchorsAreUniqueAcrossTheLocalHostDexSet() throws Exception {
        String apkPath = System.getenv("DOUAI_TEST_APK");
        File apk = apkPath == null ? null : new File(apkPath);
        Assume.assumeTrue(apk != null && apk.isFile());

        File rulesFile = HostApkTestProfile.rulesFile(apk);
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream(rulesFile)) {
            rules = HostRuleSet.parse(input);
        }
        assertEquals(HostApkTestProfile.profile(apk).fingerprint, rules.getFingerprint());
        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk), rules);

        assertEquals(rules.getRules().size(), report.getRuleResults().size());
        for (Map.Entry<String, RuleScanResult> result : report.getResults().entrySet()) {
            assertEquals(result.getKey() + " should resolve uniquely", RuleScanResult.Status.UNIQUE,
                    result.getValue().getStatus());
        }
    }
}
