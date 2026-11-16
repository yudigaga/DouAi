package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Original-APK contracts for the new controls; simulated Hook calls are separate tests. */
public class PlaybackExpansionHostContractTest {
    @Test public void progressPauseIdentityAndCommentMetadataArePresentInPinned41()throws Exception{
        String path=System.getenv("DOUAI_TEST_APK");assumeTrue(path!=null&&new File(path).isFile());
        assumeTrue(HostApkTestProfile.is410(new File(path)));
        String p="Lcom/ss/android/ugc/aweme/",str="Ljava/lang/String;",list="Ljava/util/List;";
        String work=p+"feed/model/Aweme;",manager=p+"video/IPlayerManager;",music=p+"music/model/Music;";
        String comment=p+"comment/model/Comment;",events=p+"feed/controller/refactor/impl_pm/PMPlayListenerDispatcherV2;";
        Set<String> wanted=new HashSet<>(List.of(work,manager,music,comment,events));Map<String,ClassDef> types=new HashMap<>();
        try(ZipFile zip=new ZipFile(path)){
            for(var entry:HostApkTestProfile.dexEntries(zip)){
                try(var in=new BufferedInputStream(zip.getInputStream(entry))){
                    for(ClassDef type:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in).getClasses())
                        if(wanted.remove(type.getType()))types.put(type.getType(),com.android.tools.smali.dexlib2.immutable.ImmutableClassDef.of(type));
                }
                if(wanted.isEmpty())break;
            }
        }
        assertTrue(wanted.toString(),wanted.isEmpty());
        PlaybackReadingHostContractTest.method(types,events,"onPlayProgressChange","V",str,"J","J");
        PlaybackReadingHostContractTest.method(types,events,"onPlayCompleted","V",str);
        PlaybackReadingHostContractTest.method(types,manager,"LJJIZ","V");
        PlaybackReadingHostContractTest.method(types,manager,"setSpeed","V","F");
        PlaybackReadingHostContractTest.method(types,work,"getAuthorUid",str);
        PlaybackReadingHostContractTest.field(types,work,"music",music);
        PlaybackReadingHostContractTest.encoded(PlaybackReadingHostContractTest.field(types,music,"mid",str),"id_str");
        PlaybackReadingHostContractTest.field(types,comment,"diggCount","I");
        PlaybackReadingHostContractTest.field(types,comment,"createTime","J");
        PlaybackReadingHostContractTest.field(types,comment,"isAuthorDigged","Z");
        for(String name:List.of("imageList","videoList"))PlaybackReadingHostContractTest.field(types,comment,name,list);
        for(String name:List.of("rootCommentId","replyToUserName","replyToReplyCommentText"))
            PlaybackReadingHostContractTest.field(types,comment,name,str);
    }
}
