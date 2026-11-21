package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
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

    @Test public void verticalColouringBuildsSeparateBitmapBandsFromNativeVideoLayoutEdges() throws Exception {
        String path=System.getenv("DOUAI_TEST_APK"); assumeTrue(path!=null && new File(path).isFile());
        assumeTrue(HostApkTestProfile.is410(new File(path)));
        String feed="Lcom/ss/android/ugc/aweme/feed/";
        String group="Landroid/view/ViewGroup;", view="Landroid/view/View;", image="Landroid/widget/ImageView;";
        String presenter=feed+"quick/presenter/FeedFreeSpaceColouringPresenter;";
        String colouring=feed+"ui/colouring/VerticalVideoColouring;";
        String local=feed+"ui/colouring/VerticalVideoLocalColouring;";
        try (ZipFile apk=new ZipFile(path)) {
            Method feedLayer=method(type(apk,7,"LX/0PGt;"),"LIZ");
            assertTrue(creates(feedLayer,"Lcom/ss/android/ugc/aweme/view/MeasureOnceCheckDrawChildRelativeLayout;"));
            assertTrue(literal(feedLayer,0x7f0a3ffa));
            assertTrue(literal(feedLayer,0x7f0a3fac));
            Method lazyViews=method(type(apk,7,"LY/AgS530S0000000_14;"),"inflate$6");
            assertTrue(creates(lazyViews,"Landroid/widget/FrameLayout;"));
            assertTrue(literal(lazyViews,0x7f0a3fac));
            ClassDef p=type(apk,25,presenter);
            Method init=method(p,"<init>");
            assertTrue("Presenter resolves the dedicated full-size colouring container",literal(init,0x7f0a3fac));
            assertTrue(fieldAccess(init,presenter,"coverContainer",group,"IPUT_OBJECT"));
            assertTrue(calls(method(p,"LLLL"),local,"<init>"));
            assertEquals(colouring,type(apk,25,local).getSuperclass());
            ClassDef owner=type(apk,56,colouring);
            for (String[] edge:new String[][]{{"LIZLLL","getTop"},{"LIZJ","getBottom"}}) {
                Method paint=method(owner,edge[0]);
                assertTrue("Band height uses the raw renderer edge: "+edge[0],calls(paint,view,edge[1]));
                assertTrue(calls(paint,view,"getWidth"));
                assertTrue("The band is a plain, separate ImageView: "+edge[0],creates(paint,image));
                assertTrue(calls(paint,image,"setImageDrawable"));
                assertTrue(calls(paint,image,"setScaleType"));
                assertTrue(fieldAccess(paint,"Landroid/widget/FrameLayout$LayoutParams;","gravity","I","IPUT"));
                assertTrue(literal(paint,"getTop".equals(edge[1])?0x31:0x51));
                assertTrue(fieldAccess(paint,colouring,"d",group,"IGET_OBJECT"));
                assertTrue(calls(paint,group,"addView"));
            }
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
    private static boolean literal(Method method,int value) {
        for(var instruction:method.getImplementation().getInstructions())
            if(instruction instanceof NarrowLiteralInstruction n && n.getNarrowLiteral()==value) return true;
        return false;
    }
    private static boolean creates(Method method,String type) {
        for(var instruction:method.getImplementation().getInstructions())
            if(instruction.getOpcode().name().equals("NEW_INSTANCE") && instruction instanceof ReferenceInstruction r
                    && r.getReference() instanceof TypeReference reference && reference.getType().equals(type)) return true;
        return false;
    }
    private static boolean fieldAccess(Method method,String owner,String name,String type,String opcode) {
        for(var instruction:method.getImplementation().getInstructions())
            if(instruction.getOpcode().name().equals(opcode) && instruction instanceof ReferenceInstruction r
                    && r.getReference() instanceof FieldReference f && f.getDefiningClass().equals(owner)
                    && f.getName().equals(name) && f.getType().equals(type)) return true;
        return false;
    }
}
