package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Verifies the exact 41 input APK; legacy versions are intentionally excluded by profile. */
public class PlaybackReadingHostContractTest {
    private static final String PREFIX="Lcom/ss/android/ugc/aweme/", U="Lcom/ss/ugc/aweme/",
            INFO=PREFIX+"video/IPlayerInfoProvider;",MAN=PREFIX+"video/IPlayerManager;",
            PANEL=PREFIX+"feed/panel/BaseListFragmentPanel;", DETAIL=PREFIX+"detail/panel/DetailFragmentPanel;",
            SIM="Lcom/ss/android/ugc/playerkit/simapicommon/model/SimVideoUrlModel;",
            WORK=PREFIX+"feed/model/Aweme;", CH=U+"ChapterInfo;", CL=U+"ClaInfo;", SUB=U+"SubtitleItem;",
            VH=PREFIX+"chapter/SearchChapterAdapter$SearchChapterViewHolder;",LIST="Ljava/util/List;",STR="Ljava/lang/String;";
    @Test public void selectionEligibilityUnitsAndReadingModelsMatchOriginalApk() throws Exception{
        String path=System.getenv("DOUAI_TEST_APK");assumeTrue(path!=null&&!path.isBlank());
        File apk=new File(path); assumeTrue(HostApkTestProfile.is410(apk));
        Set<String> wanted=new HashSet<>(List.of(INFO,MAN,PANEL,DETAIL,SIM,WORK,CH,CL,SUB,VH,"LX/0so0;","LX/0sS3;"));
        Map<String,ClassDef> types=new HashMap<>();
        try(ZipFile zip=new ZipFile(apk)){
            for(var e:HostApkTestProfile.dexEntries(zip)){
                try(var in=new BufferedInputStream(zip.getInputStream(e))){
                    for(ClassDef c:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in).getClasses()){
                        if(wanted.remove(c.getType()))types.put(c.getType(),com.android.tools.smali.dexlib2.immutable.ImmutableClassDef.of(c));
                    }
                }
                if(wanted.isEmpty())break;
            }
        }
        assertTrue(wanted.toString(),wanted.isEmpty());
        assertEquals(PANEL,types.get(DETAIL).getSuperclass());
        method(types,PANEL,"getCurrentViewHolderP",PREFIX+"feed/adapter/IFeedViewHolder;");
        method(types,PANEL,"LLJLL",MAN);method(types,MAN,"seek","V","F");
        method(types,INFO,"getCurrentPosition","J");method(types,INFO,"getDuration","J");
        method(types,INFO,"LJIILLIIL",SIM);field(types,SIM,"aid",STR);field(types,SIM,"sourceId",STR);
        method(types,"LX/0so0;","LIZIZ","Z",WORK);method(types,WORK,"isLive","Z");method(types,WORK,"getAd","Z");
        method(types,WORK,"getAwemeType","I");method(types,WORK,"getMoreChapterList",LIST);
        encoded(field(types,WORK,"claInfo",CL),"cla_info");encoded(field(types,CL,"list",LIST),"list");
        boolean hasItem=false;
        for(var a:field(types,CL,"list",LIST).getAnnotations())for(var el:a.getElements())
            if(el.getValue() instanceof ArrayEncodedValue values)for(var v:values.getValue())
                if(v instanceof StringEncodedValue s && s.getValue().equals(SUB))hasItem=true;
        assertTrue("cla_info.list must hold SubtitleItem",hasItem);
        for(String name:List.of("format","url","language"))encoded(field(types,SUB,name,STR),name);
        encoded(field(types,SUB,"expire","J"),"expire");
        encoded(field(types,CH,"timestamp","I"),"timestamp");encoded(field(types,CH,"desc",STR),"desc");
        encoded(field(types,CH,"keyWord",STR),"key_word");
        // Use the native user action, not just the existence of a method with a float argument.
        List<String> ops=new ArrayList<>();boolean hundred=false;
        for(var ins:method(types,VH,"I3","V").getImplementation().getInstructions()){
            if(ins instanceof NarrowLiteralInstruction n && n.getNarrowLiteral()==Float.floatToIntBits(100f))hundred=true;
            if(ins instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m)
                ops.add(m.getDefiningClass()+"->"+m.getName());
            else ops.add(ins.getOpcode().name());
        }
        assertTrue(hundred); int mul=ops.indexOf("MUL_FLOAT_2ADDR"),div=ops.indexOf("DIV_FLOAT_2ADDR");
        assertTrue(mul>=0 && mul<div && ops.indexOf(INFO+"->getDuration")>mul
                && ops.indexOf(MAN+"->seek")>div);
        boolean ms=false;for(var ins:method(types,"LX/0sS3;","LIZJ","I","I").getImplementation().getInstructions())
            if(ins instanceof NarrowLiteralInstruction n && n.getNarrowLiteral()==1000 && ins.getOpcode().name().startsWith("DIV_INT"))ms=true;
        assertTrue("chapter display converts milliseconds into seconds",ms);
    }
    static Method method(Map<String,ClassDef> types,String owner,String name,String result,String... args){
        for(var m:types.get(owner).getMethods())if(m.getName().equals(name)&&m.getReturnType().equals(result)
                &&m.getParameterTypes().equals(Arrays.asList(args)))return m;
        throw new AssertionError(owner+"->"+name);
    }
    static Field field(Map<String,ClassDef> types,String owner,String name,String type){
        for(var f:types.get(owner).getFields())if(name.equals(f.getName())&&type.equals(f.getType()))return f;
        throw new AssertionError(owner+"->"+name);
    }
    static void encoded(Field f,String wire){
        for(var a:f.getAnnotations())if(a.getType().equals("Lcom/google/gson/annotations/SerializedName;"))
            for(var e:a.getElements())if(e.getName().equals("value")&&e.getValue() instanceof StringEncodedValue s
                    &&s.getValue().equals(wire))return;
        throw new AssertionError("Missing wire name "+wire+" on "+f.getName());
    }
}
