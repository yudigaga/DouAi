package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

public final class FeedVideoFramingHostContractTest {
    @Test public void ordinaryFeedAdaptsTheSurfaceReturnedByThePlayerAndReadsEncodedVideoDimensions() throws Exception {
        String path=System.getenv("DOUAI_TEST_APK"); assumeTrue(path!=null && new File(path).isFile());
        assumeTrue(HostApkTestProfile.is410(new File(path)));
        String root="Lcom/ss/android/ugc/aweme/feed/", player=root+"adapter/IFeedPlayerView;", video=root+"model/Video;";
        try (ZipFile apk=new ZipFile(path)) {
            ClassDef holder=type(apk,25,root+"adapter/VideoViewHolder;");
            assertEquals(player,method(holder,"getFeedPlayerView").getReturnType());
            assertTrue(calls(method(holder,"LJJZ"),player,"getVideoView"));
            Method surface=method(type(apk,2,root+"ui/FeedVideoPlayerView;"),"getVideoView");
            assertEquals("Landroid/view/View;",surface.getReturnType());
            assertTrue(calls(surface,"LX/0HBK;","getView"));
            Method nativeSize=method(type(apk,25,"LX/0tTc;"),"LJIIZILJ");
            boolean readsWidth=false,readsHeight=false;
            for(var i:nativeSize.getImplementation().getInstructions()) if(i instanceof ReferenceInstruction r
                    && r.getReference() instanceof FieldReference f && video.equals(f.getDefiningClass()) && "I".equals(f.getType())) {
                readsWidth|=f.getName().equals("t");readsHeight|=f.getName().equals("s");
            }
            assertTrue(readsWidth&&readsHeight);
            ClassDef state=type(apk,52,"LX/1h9V;");boolean setter=false;
            for(var m:state.getMethods()) if(m.getName().equals("LJJIJIIJIL")
                    && m.getParameterTypes().equals(java.util.List.of("Ljava/lang/String;","Z")) && "V".equals(m.getReturnType())) setter=true;
            assertTrue(setter);
        }
    }
    private static ClassDef type(ZipFile zip,int n,String name)throws Exception {
        try(var in=new BufferedInputStream(zip.getInputStream(zip.getEntry("classes"+n+".dex")))){
            for(ClassDef c:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in).getClasses())if(name.equals(c.getType()))return c;
        }
        throw new AssertionError(name);
    }
    private static Method method(ClassDef c,String name){for(Method m:c.getMethods())if(name.equals(m.getName()))return m;throw new AssertionError(name);}
    private static boolean calls(Method m,String owner,String name){
        for(var i:m.getImplementation().getInstructions())if(i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference f
                && owner.equals(f.getDefiningClass()) && name.equals(f.getName()))return true;
        return false;
    }
}
