package com.douai.module.scan;

import static org.junit.Assert.*;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;

/** Original APK: the list presenter's own view resolves the action column after native binding. */
public class CommentToolsVisibleHostContractTest {
    @Test public void presenterAndPanelExposeVisibleListAndCurrentComment() throws Exception {
        String configured=System.getenv("DOUAI_TEST_APK");
        Assume.assumeNotNull(configured);
        File apk=new File(configured);
        Assume.assumeTrue(HostApkTestProfile.is410(apk));
        String prefix="Lcom/ss/android/ugc/aweme/", menu=prefix+"comment/ui/longpress/",
                presenter=menu+"CommentLongPressVerListPresenter;", root=menu+"CommentRootPanelModel;",
                panel=prefix+"sharer/panelmodel/base/PanelModel;", base=prefix+"sharer/panelmodel/model/ListPresenter;",
                q=prefix+"kiwi/model/QModel;", cp=prefix+"comment/model/Comment;";
        Set<String> pending=new HashSet<>(Arrays.asList(presenter,root,panel,"LX/11lG;"));
        try(ZipFile zip=new ZipFile(apk)){
            for(var e:HostApkTestProfile.dexEntries(zip)){
                try(var in=new BufferedInputStream(zip.getInputStream(e))){
                    for(ClassDef c:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in).getClasses()){
                        if(!pending.remove(c.getType()))continue;
                        if(c.getType().equals(presenter)){
                            assertEquals(base,c.getSuperclass()); boolean listId=false,superBind=false,lookup=false;
                            for(Method m:c.getMethods()){
                                if(!m.getName().equals("onBind") || !m.getReturnType().equals("V")
                                        || !m.getParameterTypes().equals(Arrays.asList(q)))continue;
                                for(var i:m.getImplementation().getInstructions()){
                                    if(i instanceof NarrowLiteralInstruction)
                                        listId|=((NarrowLiteralInstruction)i).getNarrowLiteral()==0x7f0ad256;
                                    if(!(i instanceof ReferenceInstruction))continue;
                                    var ref=((ReferenceInstruction)i).getReference();
                                    if(!(ref instanceof MethodReference))continue;
                                    MethodReference r=(MethodReference)ref;
                                    superBind|=base.equals(r.getDefiningClass()) && r.getName().equals("onBind");
                                    lookup|=r.getName().equals("getView") && r.getReturnType().equals("Landroid/view/View;");
                                }
                            }
                            assertTrue("Original action-list lookup changed",listId && superBind && lookup);
                            continue;
                        }
                        String field=c.getType().equals(root)?"comment":c.getType().equals(panel)?"m":"LJIIIIZZ";
                        String type=c.getType().equals(panel)?"LX/1M56;":cp;
                        if(c.getType().equals("LX/11lG;"))assertEquals("LX/1M56;",c.getSuperclass());
                        boolean found=false;
                        for(var f:c.getFields())found|=f.getName().equals(field) && f.getType().equals(type);
                        assertTrue(c.getType()+"."+field,found);
                    }
                }
                if(pending.isEmpty())break;
            }
        }
        assertTrue("Missing "+pending,pending.isEmpty());
    }
}
