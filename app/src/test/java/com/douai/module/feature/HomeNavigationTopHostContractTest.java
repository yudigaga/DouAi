package com.douai.module.feature;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.douai.module.scan.HostApkTestProfile;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** The original APK, independently of fixtures, defines TOP's runtime ID and content contracts. */
public class HomeNavigationTopHostContractTest {
    @Test public void topFactoryStoresTheActualChannelListItemAndThePublicGetterReadsThatSameId() throws Exception {
        String configured=System.getenv("DOUAI_TEST_APK");assumeTrue(configured!=null && !configured.isBlank());
        File apk=new File(configured);assumeTrue(HostApkTestProfile.is410(apk));
        String top="Lcom/ss/android/ugc/aweme/homepage/tab/top/ui/tabstrip/item/HomeTopTabItemViewNew;";
        String factory="Lcom/ss/android/ugc/aweme/homepage/tab/top/ui/tabstrip/item/inflate/HomeTopTabInflateFactory;",delegate="LX/1Jha;";
        Set<String> wanted=new HashSet<>(List.of(top,factory,delegate));Map<String,ClassDef> types=new HashMap<>();
        try(ZipFile zip=new ZipFile(apk)) {
            for(var entry:HostApkTestProfile.dexEntries(zip)) {
                try(var input=new BufferedInputStream(zip.getInputStream(entry))) {
                    for(ClassDef type:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),input).getClasses())
                        if(wanted.remove(type.getType()))types.put(type.getType(),com.android.tools.smali.dexlib2.immutable.ImmutableClassDef.of(type));
                }
                if(wanted.isEmpty())break;
            }
        }
        assertTrue(wanted.toString(),wanted.isEmpty());
        Method getter=method(types.get(top),"getCurrentTabId","Ljava/lang/String;");
        int delegateRegister=-1,idRegister=-1;boolean returnedId=false;
        for(var instruction:getter.getImplementation().getInstructions()) {
            if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof FieldReference field && instruction.getOpcode()==Opcode.IGET_OBJECT) {
                var registers=(TwoRegisterInstruction)instruction;
                if(field.getDefiningClass().equals(top) && field.getName().equals("r"))delegateRegister=registers.getRegisterA();
                if(field.getDefiningClass().equals(delegate) && field.getName().equals("s")) {
                    assertTrue(delegateRegister>=0);assertEquals(delegateRegister,registers.getRegisterB());idRegister=registers.getRegisterA();
                }
            }
            if(instruction.getOpcode()==Opcode.RETURN_OBJECT){assertTrue(idRegister>=0);assertEquals(idRegister,((OneRegisterInstruction)instruction).getRegisterA());returnedId=true;}
        }
        assertTrue(returnedId);

        boolean typedString=false;int awaitingResult=0,listRegister=-1,iteratorRegister=-1,itemRegister=-1,stored=0;
        for(var instruction:method(types.get(factory),"inflate","Landroid/view/View;").getImplementation().getInstructions()) {
            if(awaitingResult!=0) {
                assertEquals(Opcode.MOVE_RESULT_OBJECT,instruction.getOpcode());int resultRegister=((OneRegisterInstruction)instruction).getRegisterA();
                if(awaitingResult==1)listRegister=resultRegister;
                else if(awaitingResult==2)iteratorRegister=resultRegister;
                else {itemRegister=resultRegister;typedString=false;}
                awaitingResult=0;
            }
            if(instruction instanceof ReferenceInstruction ref) {
                if(ref.getReference() instanceof MethodReference target) {
                    if(target.getDefiningClass().equals("LX/1Jat;") && target.getName().equals("LLILZIL"))awaitingResult=1;
                    if(target.getDefiningClass().equals("Ljava/util/List;") && target.getName().equals("iterator") && listRegister>=0) {
                        assertEquals(listRegister,((FiveRegisterInstruction)instruction).getRegisterC());awaitingResult=2;
                    }
                    if(target.getDefiningClass().equals("Ljava/util/Iterator;") && target.getName().equals("next") && iteratorRegister>=0) {
                        assertEquals(iteratorRegister,((FiveRegisterInstruction)instruction).getRegisterC());awaitingResult=3;
                    }
                    assertFalse("TOP must not inherit the bottom raw-type conversion assumption",target.getDefiningClass().equals("LX/1Jlu;") && target.getName().equals("LIZLLL"));
                } else if(ref.getReference() instanceof TypeReference type && type.getType().equals("Ljava/lang/String;") && instruction.getOpcode()==Opcode.CHECK_CAST)
                    typedString=((OneRegisterInstruction)instruction).getRegisterA()==itemRegister;
                else if(ref.getReference() instanceof FieldReference field && field.getDefiningClass().equals(delegate) && field.getName().equals("s") && instruction.getOpcode()==Opcode.IPUT_OBJECT) {
                    assertTrue(listRegister>=0 && iteratorRegister>=0 && typedString);assertEquals(itemRegister,((TwoRegisterInstruction)instruction).getRegisterA());stored++;
                }
            }
        }
        assertTrue("A real top factory item must reach the delegate ID",stored>0);
        Method content=method(types.get(delegate),"getContentView","Landroid/view/View;");
        boolean text=false,image=false;
        for(var instruction:content.getImplementation().getInstructions())if(instruction instanceof ReferenceInstruction ref && ref.getReference() instanceof FieldReference field) {
            if(field.getDefiningClass().equals(delegate) && field.getName().equals("j") && field.getType().equals("Lcom/bytedance/dux/text/DuxTextView;"))text=true;
            if(field.getDefiningClass().equals(delegate) && field.getName().equals("l") && field.getType().equals("Lcom/ss/android/ugc/aweme/base/ui/AutoPlayLottieView;"))image=true;
        }
        assertTrue("The metadata consumer must handle native text and native image content",text && image);
    }
    private static Method method(ClassDef type,String name,String result) {
        for(Method method:type.getMethods())if(method.getName().equals(name) && method.getReturnType().equals(result)) {
            assertNotNull(method.getImplementation());return method;
        }
        throw new AssertionError(type.getType()+"->"+name);
    }
}
