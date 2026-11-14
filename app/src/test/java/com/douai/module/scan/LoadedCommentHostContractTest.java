package com.douai.module.scan;

import static org.junit.Assert.*;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.*;

/** Checks both renderers in the original 41 APK and the wire name/type of existing ASR text. */
public class LoadedCommentHostContractTest {
    @Test public void pinnedReadingPathsAreAvailableInTheOriginalDex() throws Exception {
        String apk=System.getenv("DOUAI_TEST_APK");Assume.assumeNotNull(apk);
        Assume.assumeTrue(HostApkTestProfile.is410(new File(apk)));
        String c="Lcom/ss/android/ugc/aweme/comment/", str="Ljava/lang/String;",list="Ljava/util/List;";
        String nativePanel=c+"ui/CommentListFragment;", kmpPanel=c+"commentlist/kmp/KmpCommentListFragment;",
                render=c+"commentlist/listcontainer/commentlist/refactor/CommentListRecyclerUiModule;",
                comment=c+"model/Comment;", audio=c+"model/CommentAudioStruct;",
                adapter=c+"adapter/CommentAdapter;", lvm="Lcom/douyin/kmp/comment/comment/commentpanellist/list/KCmtListViewModel;",
                pvm="Lcom/douyin/kmp/comment/comment/commentpanellist/list/KCmtListPanelViewModel;",
                lazy="Lkotlin/Lazy;", state="Landroidx/compose/foundation/lazy/LazyListState;",rv="Landroidx/recyclerview/widget/RecyclerView;";
        Map<String,List<String>> methods=new HashMap<>(), fields=new HashMap<>();
        for(String f:new String[]{nativePanel,kmpPanel}) {
            methods.put(f,new ArrayList<>(Arrays.asList("onShow()V","onResume()V","onPause()V","isShowing()Z","pk(LX/11ue;)V","onDismiss("+str+")V","onDestroyView()V")));
        }
        methods.get(nativePanel).add("getAwemeId()"+str);methods.get(nativePanel).add("LLLILZLLLI()"+rv);
        methods.get(kmpPanel).add("zM()"+str);
        methods.put(render,Arrays.asList("LJI(LX/13RB;)V","LJJJJZI("+list+"Z)V","LJJJJLI("+list+"Z)V"));
        methods.put(adapter,Arrays.asList("getData()"+list,"LJZ()Z"));
        String fetch=c+"commentlist/listcontainer/commentlist/CommentListDataFetchHelper;",page=c+"model/CommentItemList;";
        methods.put(fetch,List.of("loadMore()V","getData()"+page,"LJIIIZ()"+str,"LJIIJJI()LX/11oW;"));
        fields.put(fetch,List.of("b:Landroidx/fragment/app/Fragment;"));fields.put(page,List.of("hasMore:Z"));
        fields.put("LX/11oW;",List.of("LIZ:"+c+"data/list/CommentListPageNetRequest;"));
        methods.put("LX/11gi;",List.of("LIZ(Ljava/lang/Class;Landroidx/lifecycle/LifecycleOwner;)Lcom/ss/android/ugc/aweme/arch/ability/ICmtAbility;"));
        methods.put("Lcom/ss/android/ugc/aweme/arch/request/Request;",List.of("LJIIIZ()Z"));
        methods.put("LX/0MZo;",List.of("Wl()V","hasMore()Z"));fields.put("LX/0MZn;",List.of("b:LX/0MZo;"));
        fields.put("Lcom/bytedance/kmp/klist/KListViewModel;",List.of("f:Lkotlinx/coroutines/flow/MutableStateFlow;"));
        fields.put("LX/0MLM;",List.of("LJ:LX/0MfB;"));
        methods.put("LX/13Tl;",Arrays.asList("getComment()"+comment));
        methods.put("LX/0Mey;",Arrays.asList("LJIIJJI("+list+")V"));
        methods.put("LX/0Mh4;",Arrays.asList("LIZ(LX/0Lzw;)V"));
        methods.put("LX/0Mff;",Arrays.asList("LIZLLL()"+list));
        methods.put(lvm,Arrays.asList("YD()"+state));
        methods.put(state,Arrays.asList("LIZLLL(II)V"));
        methods.put(rv,Arrays.asList("getAdapter()Landroidx/recyclerview/widget/RecyclerView$Adapter;","scrollToPosition(I)V"));
        methods.put(lazy,Arrays.asList("isInitialized()Z","getValue()Ljava/lang/Object;"));
        fields.put(render,Arrays.asList("g:Landroidx/fragment/app/Fragment;"));
        fields.put(kmpPanel,Arrays.asList("U:LX/0MbR;"));
        fields.put("LX/0MbR;",Arrays.asList("LIZJ:LX/0Mbd;"));fields.put("LX/0MbU;",Arrays.asList("LIZIZ:"+lazy));
        fields.put(pvm,Arrays.asList("l:"+lvm));fields.put(lvm,Arrays.asList("M:LX/0Mff;"));
        fields.put("LX/0Mff;",Arrays.asList("LIZJ:LX/0MAN;"));fields.put("LX/0Mh4;",Arrays.asList("LIZIZ:LX/0Mbe;"));
        fields.put("LX/0MXK;",Arrays.asList("u:Z","z:Z"));
        fields.put(comment,Arrays.asList("cid:"+str,"awemeId:"+str,"text:"+str,"rootCommentId:"+str,"replyId:"+str));
        Set<String> wanted=new HashSet<>(methods.keySet());wanted.addAll(fields.keySet());wanted.add(audio);wanted.add("LX/0MhE;");
        try(ZipFile zip=new ZipFile(apk)){
            for(var e:HostApkTestProfile.dexEntries(zip)){
                try(var in=new BufferedInputStream(zip.getInputStream(e))){
                    for(ClassDef d:DexBackedDexFile.fromInputStream(Opcodes.getDefault(),in).getClasses()){
                        String name=d.getType();if(!wanted.remove(name))continue;
                        Set<String> signatures=new HashSet<>();
                        for(Method m:d.getMethods()) signatures.add(m.getName()+"("+String.join("",m.getParameterTypes())+")"+m.getReturnType());
                        for(String s:methods.getOrDefault(name,Collections.emptyList())) assertTrue(name+s,signatures.contains(s));
                        Set<String> fs=new HashSet<>();
                        boolean mapped=false;
                        for(Field f:d.getFields()){
                            fs.add(f.getName()+":"+f.getType());
                            if((name.equals(comment)&&f.getType().equals(audio)) || (name.equals(audio)&&f.getType().equals(str))){
                                for(var a:f.getAnnotations())if(a.getType().equals("Lcom/google/gson/annotations/SerializedName;"))
                                    for(var v:a.getElements())if(v.getName().equals("value") && v.getValue() instanceof StringEncodedValue)
                                        mapped |= (name.equals(comment)?"comment_audio":"asr_text").equals(((StringEncodedValue)v.getValue()).getValue());
                            }
                        }
                        for(String s:fields.getOrDefault(name,Collections.emptyList())) assertTrue(name+s,fs.contains(s));
                        if(name.equals(comment)||name.equals(audio))assertTrue(name+" missing mapped speech field",mapped);
                    }
                }
                if(wanted.isEmpty())break;
            }
        }
        assertTrue("Missing "+wanted,wanted.isEmpty());
    }
}
