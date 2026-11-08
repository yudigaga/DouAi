package com.douai.module.scan;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.MethodReferenceRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Independently audited original-APK names, not scanner rules or a behavioral emulator.
 * Both verified original builds normalize names to stable test labels only to share semantic assertions. Type aliases
 * and full owner/signature method aliases are reversible and injective. Opcodes, arguments,
 * registers, field names, string/literal values, branches and return instructions are untouched.
 * Real structural differences, such as callback argument order and safety discriminators,
 * are checked separately by each suite's actual Manifest-selected profile.
 */
final class HostContractNameFacts {
    private final HostAdaptation.Profile profile;
    private final boolean legacy;
    private final Map<String, String> types = new HashMap<>();
    private final Map<String, String> methods = new HashMap<>();
    private final Set<String> typeTargets = new HashSet<>();
    private final Set<String> methodTargets = new HashSet<>();
    private final DexRewriter rewriter;

    HostContractNameFacts(boolean legacy) {
        this(HostAdaptation.forVersion(HostAdaptation.TARGET_PACKAGE,
                legacy ? "40.6.0" : "40.8.0", legacy ? 400601L : 400801L));
    }

    HostContractNameFacts(HostAdaptation.Profile profile) {
        if (profile == null) throw new IllegalArgumentException("A verified original APK profile is required");
        this.profile = profile;
        this.legacy = profile.versionCode == 400601L;
        for (String[] pair : new String[][]{
                {"LX/0dFd;", "LX/14LK;"},
                {"LX/0dHa;", "LX/14Jz;"},
                {"LX/0dKM;", "LX/14Ec;"},
                {"LX/0dKN;", "LX/14Ea;"},
                {"LX/0dPP;", "LX/14PD;"},
                {"LX/0dPQ;", "LX/14PE;"},
                {"LX/0dU1;", "LX/14jX;"},
                {"LX/0y6L;", "LX/13a7;"},
                {"LX/0y6l;", "LX/13ao;"},
                {"LX/0y6o;", "LX/13ar;"},
                {"LX/0yAP;", "LX/13eY;"},
                {"LX/0yAS;", "LX/13ea;"},
                {"LX/0yFs;", "LX/13hi;"},
                {"LX/0yTH;", "LX/13wP;"},
                {"LX/1Qjr;", "LX/1Z8C;"},
                {"LX/00Wd;", "LX/00s8;"},
                {"LX/0RS2;", "LX/0V8X;"},
                {"LX/0d9z;", "LX/14Gs;"},
                {"LX/0dCx;", "LX/14J4;"},
                {"LX/0dED;", "LX/14Mk;"},
                {"LX/0dEE;", "LX/14Ml;"},
                {"LX/0dHB;", "LX/14MH;"},
                {"LX/0dH3;", "LX/14MA;"},
                {"LX/0dH8;", "LX/14Kj;"},
                {"LX/0dHH;", "LX/14MD;"},
                {"LX/0dHN;", "LX/14HZ;"},
                {"LX/0dI8;", "LX/14Ep;"},
                {"LX/0dLF;", "LX/14Es;"},
                {"LX/0dMx;", "LX/159L;"},
                {"LX/0dN2;", "LX/14F9;"},
                {"LX/0dN5;", "LX/14FE;"},
                {"LX/0eOw;", "LX/14u0;"},
                {"LY/AObjectS331S0000000_23;", "LY/AObjectS353S0000000_36;"},
                {"LX/0rwU;", "LX/0vbu;"},
                {"LX/0y6z;", "LX/156M;"},
                {"LX/0xyq;", "LX/155h;"},
                {"LX/0yH8;", "LX/13hD;"},
                {"LX/0yFq;", "LX/13hg;"},
                {"LX/0yH0;", "LX/13iG;"},
                {"LX/0yGX;", "LX/13gy;"},
                {"LX/0yXF;", "LX/12j8;"},
                {"LX/15MJ;", "LX/1SVO;"},
                {"LX/1QkP;", "LX/1Z99;"},
                {"LX/1QjB;", "LX/1Z9A;"},
                {"LX/1QW8;", "LX/16Pt;"},
                {"LX/1Qd0;", "LX/1Z5F;"},
                {"LX/1Qd1;", "LX/1Z6z;"},
                {"LX/1QdT;", "LX/1Z5A;"},
                {"LX/1Qh6;", "LX/1Z6p;"},
                {"LX/1QuJ;", "LX/16Tc;"},
                {"LY/ATCallbackS222S1200000_49;", "LY/ATCallbackS220S1200000_37;"},
                {"LY/ATRunnableS33S1110000_49;", "LY/ATRunnableS26S1110000_37;"},
                {"LX/0s5x;", "LX/0Yca;"},
                {"LX/0s61;", "LX/0Yce;"},
                {"LX/0s65;", "LX/0YRa;"},
                {"LX/0s5u;", "LX/0YcX;"},
                {"LX/1Mas;", "LX/1WHh;"},
                {"LX/1Br3;", "LX/0isP;"},
                {"LX/1Bq2;", "LX/0ioG;"},
                {"LX/1Bpx;", "LX/0io8;"},
        }) {
            if (types.put(pair[0], pair[1]) != null || !typeTargets.add(pair[1]))
                throw new AssertionError("Non-injective original type alias");
        }
        String comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;";
        method(comment, "LJLLLLLL()" + comment, "LJZ");
        method(comment, "LLIIIILZ()Ljava/lang/String;", "LLIIIJ");
        method(comment, "LLLLIILLL()Ljava/lang/String;", "LLLLIILL");
        method(comment, "LLLLL()Ljava/lang/String;", "LLLLJI");
        method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLIIIILZ()I", "LLIIIJ");
        method("LX/0RS2;", "LLLLJ(Ljava/util/HashMap;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "LLLLJI");
        method("LX/1QdT;", "L2(LX/1Qd0;)V", "M2");
        method("LX/1Qh6;", "N3(Ljava/lang/String;Lcom/bytedance/im/core/model/Conversation;Ljava/util/List;Ljava/lang/Integer;LX/1Qd1;Ljava/util/ArrayList;Z)V", "R3");
        String conversations = "Lcom/bytedance/im/core/model/ConversationListModel;";
        method(conversations, "LJJZZI(Ljava/lang/String;ZLcom/bytedance/im/core/client/callback/IRequestListener;)V", "LJLIIL");
        method(conversations, "b3(Ljava/lang/String;[Lcom/bytedance/im/core/model/Conversation;)V", "f3");
        String logic = "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerLogic2;";
        method(logic, "LJJZ()Landroidx/lifecycle/MutableLiveData;", "LJLI");
        method(logic, "LJJZZIII()Landroidx/lifecycle/MutableLiveData;", "LJLIIL");
        method("Lcom/ss/android/ugc/aweme/homepage/tab/bottom/MPFBottomTabComponent;",
                "LJJL(Landroid/view/ViewGroup;)Landroid/view/View;", "LJJLIIIJL");
        if (profile.versionCode == 400801L) {
            // Original APK build 40809900 is normalized to the semantic test labels.
            types.clear(); typeTargets.clear(); methods.clear(); methodTargets.clear();
            for (String[] pair : new String[][]{
                    {"LX/1NtM;", "LX/1SVO;"},
                    {"LX/00zj;", "LX/00s8;"},
                    {"LX/0TA5;", "LX/0V8X;"},
                    {"LX/0TsJ;", "LX/0YRa;"},
                    {"LX/0TxD;", "LX/0YcX;"},
                    {"LX/0TxG;", "LX/0Yca;"},
                    {"LX/0Txd;", "LX/0Yce;"},
                    {"LX/1KUl;", "LX/0io8;"},
                    {"LX/1KV4;", "LX/0ioG;"},
                    {"LX/1KUi;", "LX/0isP;"},
                    {"LX/0p77;", "LX/0vbu;"},
                    {"LX/13m0;", "LX/12j8;"},
                    {"LX/13Ju;", "LX/13a7;"},
                    {"LX/13L6;", "LX/13ao;"},
                    {"LX/13L7;", "LX/13ar;"},
                    {"LX/13UT;", "LX/13eY;"},
                    {"LX/13Px;", "LX/13ea;"},
                    {"LX/13Zx;", "LX/13gy;"},
                    {"LX/13aM;", "LX/13hD;"},
                    {"LX/13Zg;", "LX/13hg;"},
                    {"LX/13Zi;", "LX/13hi;"},
                    {"LX/13aF;", "LX/13iG;"},
                    {"LX/13ns;", "LX/13wP;"},
                    {"LX/14xj;", "LX/14Ea;"},
                    {"LX/14v8;", "LX/14Ec;"},
                    {"LX/14qs;", "LX/14Ep;"},
                    {"LX/13vg;", "LX/14Es;"},
                    {"LX/13vs;", "LX/14F9;"},
                    {"LX/13w5;", "LX/14FE;"},
                    {"LX/0pEc;", "LX/14Gs;"},
                    {"LX/0pIU;", "LX/14HZ;"},
                    {"LX/0pFh;", "LX/14J4;"},
                    {"LX/0p2G;", "LX/14Jz;"},
                    {"LX/0pIH;", "LX/14Kj;"},
                    {"LX/0oul;", "LX/14LK;"},
                    {"LX/0pIC;", "LX/14MA;"},
                    {"LX/0pIL;", "LX/14MD;"},
                    {"LX/0pIQ;", "LX/14MH;"},
                    {"LX/0ouQ;", "LX/14Mk;"},
                    {"LX/0ouT;", "LX/14Ml;"},
                    {"LX/0pK3;", "LX/14PD;"},
                    {"LX/0pK2;", "LX/14PE;"},
                    {"LX/0p2F;", "LX/14jX;"},
                    {"LX/0pII;", "LX/14u0;"},
                    {"LX/14nw;", "LX/155h;"},
                    {"LX/14o1;", "LX/156M;"},
                    {"LX/14qw;", "LX/159L;"},
                    {"LX/1V2o;", "LX/16Pt;"},
                    {"LX/1Tk2;", "LX/16Tc;"},
                    {"LX/1O3p;", "LX/1WHh;"},
                    {"LX/1TVN;", "LX/1Z5A;"},
                    {"LX/1TUn;", "LX/1Z5F;"},
                    {"LX/1TZK;", "LX/1Z6p;"},
                    {"LX/1TU5;", "LX/1Z6z;"},
                    {"LX/1TX2;", "LX/1Z8C;"},
                    {"LX/1Ta8;", "LX/1Z99;"},
                    {"LX/1TZf;", "LX/1Z9A;"},
                    {"LY/AObjectS332S0000000_28;", "LY/AObjectS353S0000000_36;"},
                    {"LY/ATCallbackS204S1200000_49;", "LY/ATCallbackS220S1200000_37;"},
                    {"LY/ATRunnableS25S1110000_49;", "LY/ATRunnableS26S1110000_37;"},
            }) {
                if (types.put(pair[0], pair[1]) != null || !typeTargets.add(pair[1]))
                    throw new AssertionError("Non-injective current original type alias");
            }
            method("Lcom/bytedance/im/core/model/ConversationListModel;", "LJLI(Ljava/lang/String;ZLcom/bytedance/im/core/client/callback/IRequestListener;)V", "LJLIIL");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLIIIL()Ljava/lang/String;", "LLIIIJ");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLJILJILJ()Ljava/lang/Long;", "LLJILLL");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLLLII()Ljava/lang/String;", "LLLLIILL");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLLLJ()Ljava/lang/String;", "LLLLJI");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLLLLJIL()I", "LLLLLJLJLL");
            method("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "LLLLZI()Lcom/ss/android/ugc/aweme/profile/model/User;", "LLLLZLL");
            method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLIIIL()I", "LLIIIJ");
            method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLJILJILJ(Ljava/lang/String;)V", "LLJILLL");
            method("Lcom/ss/android/ugc/aweme/homepage/tab/bottom/MPFBottomTabComponent;", "LJJLIIIJ(Landroid/view/ViewGroup;)Landroid/view/View;", "LJJLIIIJL");
        } else if (profile.versionCode == 410001L) {
            types.clear(); typeTargets.clear(); methods.clear(); methodTargets.clear();
            for (String[] pair : new String[][]{
                    {"LX/00Qz;", "LX/00s8;"},
                    {"LX/0Qqg;", "LX/0V8X;"},
                    {"LX/19UT;", "LX/0YRa;"},
                    {"LX/18Yq;", "LX/0YcX;"},
                    {"LX/18Yt;", "LX/0Yce;"},
                    {"LX/18Uq;", "LX/0Yca;"},
                    {"LX/12a5;", "LX/14Mk;"},
                    {"LX/12a6;", "LX/14Ml;"},
                    {"LX/12YM;", "LX/14LK;"},
                    {"LX/1Tp1;", "LX/14jX;"},
                    {"LX/12WZ;", "LX/14Jz;"},
                    {"LX/1TGa;", "LX/0vbu;"},
                    {"LX/12U5;", "LX/14Gs;"},
                    {"LX/12VZ;", "LX/14J4;"},
                    {"LX/12Ym;", "LX/14MH;"},
                    {"LX/12Yc;", "LX/14MA;"},
                    {"LX/12Yb;", "LX/14Kj;"},
                    {"LX/13Bm;", "LX/14u0;"},
                    {"LX/12Yf;", "LX/14MD;"},
                    {"LX/12TL;", "LX/14HZ;"},
                    {"LX/12bj;", "LX/14PE;"},
                    {"LX/12bi;", "LX/14PD;"},
                    {"LY/AObjectS351S0000000_35;", "LY/AObjectS353S0000000_36;"},
                    {"LX/11ol;", "LX/156M;"},
                    {"LX/11nv;", "LX/13a7;"},
                    {"LX/11oY;", "LX/13ao;"},
                    {"LX/11ob;", "LX/13ar;"},
                    {"LX/11us;", "LX/13ea;"},
                    {"LX/11w6;", "LX/13eY;"},
                    {"LX/11t9;", "LX/13hD;"},
                    {"LX/11tV;", "LX/13hg;"},
                    {"LX/11tb;", "LX/13iG;"},
                    {"LX/11tW;", "LX/13hi;"},
                    {"LX/11sn;", "LX/13gy;"},
                    {"LX/12FZ;", "LX/12j8;"},
                    {"LX/1290;", "LX/13wP;"},
                    {"LX/12Qi;", "LX/14Es;"},
                    {"LX/12Rm;", "LX/14F9;"},
                    {"LX/12Rq;", "LX/14FE;"},
                    {"LX/13N9;", "LX/155h;"},
                    {"LX/12Qf;", "LX/14Ep;"},
                    {"LX/12Rr;", "LX/159L;"},
                    {"LX/12QW;", "LX/14Ec;"},
                    {"LX/12QV;", "LX/14Ea;"},
                    {"LX/1JgG;", "LX/0isP;"},
                    {"LX/1JgC;", "LX/0io8;"},
                    {"LX/1JgM;", "LX/0ioG;"},
                    {"LX/16tl;", "LX/1SVO;"},
                    {"LX/1YMi;", "LX/1WHh;"},
                    {"LX/1Svj;", "LX/1Z99;"},
                    {"LX/1SvD;", "LX/1Z9A;"},
                    {"LX/1Som;", "LX/1Z6z;"},
                    {"LX/1SpR;", "LX/1Z5F;"},
                    {"LX/1SqW;", "LX/1Z5A;"},
                    {"LX/1Stx;", "LX/1Z8C;"},
                    {"LX/1Sv6;", "LX/1Z6p;"},
                    {"LX/1T8I;", "LX/16Tc;"},
                    {"LX/1Sg0;", "LX/16Pt;"},
                    {"LY/ATCallbackS209S1200000_48;", "LY/ATCallbackS220S1200000_37;"},
                    {"LY/ATRunnableS30S1110000_48;", "LY/ATRunnableS26S1110000_37;"},
            }) {
                if (types.put(pair[0], pair[1]) != null || !typeTargets.add(pair[1]))
                    throw new AssertionError("Non-injective 41.0.0 original type alias");
            }
            method(comment, "LJLLL()" + comment, "LJZ");
            method(comment, "LLIFFJFJJ()Ljava/lang/String;", "LLIIIJ");
            method(comment, "LLJ()Ljava/lang/Long;", "LLJILLL");
            method(comment, "LLLILZLLLI()Ljava/lang/String;", "LLLLIILL");
            method(comment, "LLLLIIIILLL()Ljava/lang/String;", "LLLLJI");
            method(comment, "LLLLILI()I", "LLLLLJLJLL");
            method(comment, "LLLLLLZZ()Lcom/ss/android/ugc/aweme/profile/model/User;", "LLLLZLL");
            method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLIFFJFJJ()I", "LLIIIJ");
            method("Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", "LLJ(Ljava/lang/String;)V", "LLJILLL");
            method("Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerLogic2;",
                    "LJLIIL()Landroidx/lifecycle/MutableLiveData;", "LJLI");
            method("Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerLogic2;",
                    "LJLILLLLZI()Landroidx/lifecycle/MutableLiveData;", "LJLIIL");
            method("Lcom/ss/android/ugc/aweme/rips/RipsUI;",
                    "LJJJI()Lcom/ss/android/ugc/aweme/rips/BasicRipsLogic;", "LJJJJJ");
            method("Lcom/bytedance/im/core/model/ConversationListModel;",
                    "LJLJI(Ljava/lang/String;ZLcom/bytedance/im/core/client/callback/IRequestListener;)V", "LJLIIL");
            method("Lcom/bytedance/im/core/model/ConversationListModel;",
                    "W2(Ljava/lang/String;[Lcom/bytedance/im/core/model/Conversation;)V", "f3");
            method("LX/1Sg0;", "A2(Lcom/bytedance/im/core/model/Conversation;Z)V", "J2");
            method("LX/1SqW;", "C2(LX/1SpR;)V", "M2");
            method("LX/1Sv6;",
                    "I3(Ljava/lang/String;Lcom/bytedance/im/core/model/Conversation;Ljava/util/List;Ljava/lang/Integer;LX/1Som;Ljava/util/ArrayList;Z)V",
                    "R3");
            method("LX/1TGa;", "LJFF()V", "LJJII");
            method("Lcom/ss/android/ugc/aweme/homepage/tab/bottom/MPFBottomTabComponent;",
                    "LJJJ(Landroid/view/ViewGroup;)Landroid/view/View;", "LJJLIIIJL");
        }
        rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return HostContractNameFacts.this::renamedType;
            }
            @Override public Rewriter<MethodReference> getMethodReferenceRewriter(Rewriters rewriters) {
                return new MethodReferenceRewriter(rewriters) {
                    @Override public MethodReference rewrite(MethodReference original) {
                        MethodReference renamedTypes = super.rewrite(original);
                        String name = methods.get(signature(original));
                        if (name == null) {
                            name = original.getName();
                            if (methodTargets.contains(signature(renamedTypes)))
                                name = (legacy ? "__douai_original406_" : profile.versionCode == 410001L
                                        ? "__douai_original4109900_" : "__douai_original4089900_") + name;
                        }
                        return new ImmutableMethodReference(renamedTypes.getDefiningClass(), name,
                                renamedTypes.getParameterTypes(), renamedTypes.getReturnType());
                    }
                };
            }
        });
    }

    String type(String original) { return renamedType(original); }
    private String renamedType(String original) {
        if (original.startsWith("[")) return "[" + renamedType(original.substring(1));
        String renamed = types.get(original);
        if (renamed != null) return renamed;
        // A 40.6 APK may also contain an unrelated class already using a 40.8 alias.
        // Keep that original class in a reversible separate namespace, never merge it.
        return typeTargets.contains(original) ? "Ldouai/test/"
                + (legacy ? "original406/" : profile.versionCode == 410001L
                ? "original4109900/" : "original4089900/") + original.substring(1) : original;
    }
    ClassDef originalFacts(ClassDef original) {
        ClassDef result = ImmutableClassDef.of(rewriter.getClassDefRewriter().rewrite(original));
        Set<String> signatures = new HashSet<>();
        int before = 0, after = 0;
        for (var method : original.getMethods()) before++;
        for (var method : result.getMethods()) {
            after++;
            if (!signatures.add(signature(method))) throw new AssertionError("Original method names were merged");
        }
        if (before != after) throw new AssertionError("Original methods were lost during name normalization");
        return result;
    }
    private void method(String owner, String signature, String renamed) {
        String key = owner + "->" + signature;
        String target = types.getOrDefault(owner, owner) + "->" + renamed + signature.substring(signature.indexOf('('));
        for (Map.Entry<String, String> type : types.entrySet()) target = target.replace(type.getKey(), type.getValue());
        if (methods.put(key, renamed) != null || !methodTargets.add(target))
            throw new AssertionError("Non-injective original method alias");
    }
    private static String signature(MethodReference reference) {
        StringBuilder result = new StringBuilder(reference.getDefiningClass()).append("->")
                .append(reference.getName()).append('(');
        for (CharSequence parameter : reference.getParameterTypes()) result.append(parameter);
        return result.append(')').append(reference.getReturnType()).toString();
    }
}
