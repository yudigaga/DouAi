package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SeenWorkHostContractTest {
    private static final String FEED = "Lcom/ss/android/ugc/aweme/feed/";
    private static final String MODEL = FEED+"presenter/RecommendFeedFetchModelV2;";
    private static final String FRAGMENT = FEED+"ui/FeedRecommendFragment;";
    private static final String PAGE = FEED+"model/FeedItemList;";
    private static final String REQUEST = "LX/1R33;";

    @Test public void recommendationFragmentOwnsPerRequestCallableReturningFeedPage() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue(path != null && new File(path).isFile());
        Assume.assumeTrue(HostApkTestProfile.is410(new File(path)));
        try (ZipFile apk = new ZipFile(path)) {
            DexBackedDexFile dex16 = dex(apk,16), dex43 = dex(apk,43);
            ClassDef fragment = definition(dex43,FRAGMENT), model = definition(dex16,MODEL);
            ClassDef request = definition(dex43,REQUEST);
            assertTrue(request.getInterfaces().contains("Ljava/util/concurrent/Callable;"));
            assertField(fragment,"u",FEED+"panel/FullFeedFragmentPanel;");
            assertField(model,"q","Landroidx/fragment/app/Fragment;");
            assertField(request,"h",MODEL);
            assertTrue(calls(fragment,null,MODEL,"<init>","V"));
            assertTrue(calls(definition(dex16,FEED+"presenter/handler/FeedFetchListHandler;"),
                    "LIZJ",REQUEST,"<init>","V"));
            assertTrue(calls(request,"call",FEED+"cache/IFeedApi;","LIZ",PAGE));
        }
    }
    private static boolean calls(ClassDef c, String only, String owner, String name, String returns) {
        for (Method m : c.getMethods()) if ((only == null || only.equals(m.getName())) && m.getImplementation()!=null)
            for (Instruction i : m.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction) {
                Object ref = ((ReferenceInstruction)i).getReference();
                if (ref instanceof MethodReference) {
                    MethodReference r=(MethodReference)ref;
                    if (owner.equals(r.getDefiningClass()) && name.equals(r.getName()) && returns.equals(r.getReturnType())) return true;
                }
            }
        return false;
    }
    private static void assertField(ClassDef c,String name,String type) {
        for (var f:c.getFields()) if (name.equals(f.getName()) && type.equals(f.getType())) return;
        fail(c.getType()+" missing "+name);
    }
    private static ClassDef definition(DexBackedDexFile d,String type) {
        for (ClassDef c:d.getClasses()) if (c.getType().equals(type)) return c;
        throw new AssertionError(type);
    }
    private static DexBackedDexFile dex(ZipFile zip,int ordinal) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(zip.getInputStream(zip.getEntry("classes"+ordinal+".dex")))) {
            return DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in);
        }
    }
}
