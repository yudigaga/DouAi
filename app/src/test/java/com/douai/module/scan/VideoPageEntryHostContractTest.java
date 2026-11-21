package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Characterizes the original 41.0.0 APK boundaries used by the entry-cover hook. */
public final class VideoPageEntryHostContractTest {
    private static final String FEED = "Lcom/ss/android/ugc/aweme/feed/";
    private static final String PRESENTER = FEED + "quick/presenter/FeedCoverPresenter;";
    private static final String SYNC = FEED + "quick/presenter/FeedSyncPresenter;";
    private static final String PARAMS = FEED + "model/VideoItemParams;";
    private static final String AWEME = FEED + "model/Aweme;";
    private static final String COVER = FEED + "widget/CoverImageView;";
    private static final String PANEL = FEED + "panel/BaseListFragmentPanel;";
    private static final String DISPATCHER = FEED + "controller/refactor/impl_pm/PMPlayListenerDispatcherV2;";
    private static final String VIEW = "Landroid/view/View;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String WEAK = "Ljava/lang/ref/WeakReference;";

    @Test public void bindingAssociatesTheCoverWithTheAwemeInInheritedVideoItemParams() throws Exception {
        try (ZipFile apk = host()) {
            ClassDef presenter = type(apk, 2, PRESENTER);
            String extension = FEED + "extend/IExtendBusinessFeedSyncPresenter;";
            assertEquals(extension, presenter.getSuperclass());
            assertEquals(SYNC, type(apk, 25, extension).getSuperclass());
            field(type(apk, 25, SYNC), "videoItemParams", PARAMS);
            field(presenter, "mAweme", AWEME);
            field(presenter, "mCoverView", COVER);
            Method bind = method(presenter, "onBindSafe", "V", "Lcom/ss/android/ugc/aweme/kiwi/model/QModel;");
            assertTrue(calls(bind, SYNC, "onBindSafe", "V", "Lcom/ss/android/ugc/aweme/kiwi/model/QModel;"));
            assertTrue(accesses(bind, SYNC, "videoItemParams", PARAMS, "IGET"));
            assertTrue(calls(bind, PARAMS, "getAweme", AWEME));
            assertTrue(accesses(bind, PRESENTER, "mAweme", AWEME, "IPUT"));
            assertTrue(accesses(bind, PRESENTER, "mCoverView", COVER, "IPUT"));
            method(presenter, "mustUnbind", "V");
            assertTrue(calls(method(presenter, "unBindSafe", "V"), PRESENTER, "LLLLILI", "V"));
        }
    }

    @Test public void hideObserverOwnsItsPresenterAndRetainsBothHideDispatchAndPanelNotification() throws Exception {
        try (ZipFile apk = host()) {
            String observerName = FEED + "quick/presenter/FeedCoverPresenter$observeVM$5;";
            ClassDef observer = type(apk, 2, observerName);
            field(observer, "a", PRESENTER);
            method(observer, "<init>", "V", PRESENTER);
            Method hide = method(observer, "onChanged", "V", "Ljava/lang/Object;");
            assertTrue(accesses(hide, observerName, "a", PRESENTER, "IGET"));
            assertTrue(accesses(hide, PRESENTER, "mCoverView", COVER, "IGET"));
            assertTrue(setsGone(hide));
            String runnable = "LY/AObjectS13S3100000_9;";
            assertTrue(calls(hide, runnable, "<init>", "V", "Ljava/lang/Object;", STRING, STRING, STRING, "I"));
            assertTrue(calls(hide, FEED + "play/MainThreadHelper;", "LIZJ", "V", "Lkotlin/jvm/functions/Function0;"));
            assertTrue(setsGone(method(type(apk, 2, runnable), "invoke", "Ljava/lang/Object;")));
            assertTrue(calls(hide, PANEL, "LLLLIL", "V"));
        }
    }

    @Test public void completedCoverListenerKeepsWeakOwnersAndMarksTheCurrentParamsLoaded() throws Exception {
        try (ZipFile apk = host()) {
            // Runtime descriptor is case-sensitive; apktool exports it as 0HLd.1.smali on Windows.
            ClassDef listener = type(apk, 2, "LX/0HLd;");
            field(listener, "a", WEAK);
            field(listener, "d", WEAK);
            Method constructor = method(listener, "<init>", "V", PRESENTER, WEAK, "LX/0HLW;");
            assertTrue(accesses(constructor, "LX/0HLd;", "a", WEAK, "IPUT"));
            assertTrue(accesses(constructor, "LX/0HLd;", "d", WEAK, "IPUT"));
            assertTrue(calls(constructor, WEAK, "<init>", "V", "Ljava/lang/Object;"));
            Method complete = method(listener, "onComplete", "V", "Landroid/net/Uri;", VIEW,
                    "Lcom/bytedance/lighten/core/ImageInfo;", "Landroid/graphics/drawable/Animatable;");
            assertTrue("onComplete reads its presenter weak reference", accesses(complete, "LX/0HLd;", "d", WEAK, "IGET"));
            assertTrue("WeakReference.get is inherited from Reference in the DEX callsite",
                    calls(complete, "Ljava/lang/ref/Reference;", "get", "Ljava/lang/Object;"));
            assertTrue("onComplete marks the cover loaded", calls(complete, PRESENTER, "LLLLLLJ", "V"));
            Method loaded = method(type(apk, 2, PRESENTER), "LLLLLLJ", "V");
            assertTrue("loaded reads params declared by FeedSyncPresenter", accesses(loaded, SYNC, "videoItemParams", PARAMS, "IGET"));
            assertTrue(accesses(loaded, PARAMS, "isVideoCoverLoaded", "Z", "IPUT"));
            assertTrue(writesLoadedTrue(loaded));
        }
    }

    @Test public void ordinaryDetailInheritsTheFeedBindingAndEntryLifecycleHooks() throws Exception {
        try (ZipFile apk = host()) {
            String holderName = FEED + "adapter/VideoViewHolder;";
            ClassDef detail = type(apk, 25, "Lcom/ss/android/ugc/aweme/detail/ui/DetailFeedVideoViewHolder;");
            assertEquals(holderName, detail.getSuperclass());
            assertTrue(calls(method(detail, "onViewHolderSelected", "V", "I"), holderName, "onViewHolderSelected", "V", "I"));
            for (Method m : detail.getMethods()) {
                assertFalse("Detail overrides entry hook: " + m.getName(),
                        List.of("bind", "onPageSelected", "onResume").contains(m.getName()));
            }
            ClassDef holder = type(apk, 25, holderName);
            method(holder, "bind", "V", AWEME);
            method(holder, "onPageSelected", "V");
            method(holder, "onResume", "V");
        }
    }

    @Test public void panelOwnsTheDispatcherWhoseAidBearingFirstFrameReachesItsListeners() throws Exception {
        try (ZipFile apk = host()) {
            field(type(apk, 43, PANEL), "V", "LX/0HIn;");
            field(type(apk, 2, "LX/0HIn;"), "m", DISPATCHER);
            ClassDef dispatcher = type(apk, 43, DISPATCHER);
            Method firstFrame = method(dispatcher, "onRenderFirstFrame", "V", STRING, "LX/0Gf4;");
            assertTrue(calls(firstFrame, "LX/1RXQ;", "LIZ", "V", STRING, "LX/0Gf4;"));
            Method bridge = method(dispatcher, "onRenderFirstFrame", "V", "LX/0Gf4;");
            assertFalse(calls(bridge, "LX/1RXQ;", "LIZ", "V", STRING, "LX/0Gf4;"));
        }
    }

    @Test public void playerCallbacksPublishTheThreeWhitelistedReasonsToHideCoverLiveData() throws Exception {
        try (ZipFile apk = host()) {
            Method callback = method(type(apk, 2, "LY/AaS2276S0100000_9;"), "accept", "V", "Ljava/lang/Object;");
            Set<String> reasons = hideCoverReasons(callback);
            for (String reason : List.of("HideCover when Playing", "HideCover when PreRender", "HideCover when Progress"))
                assertTrue("Missing sF().setValue(reason) callsite: " + reason, reasons.contains(reason));
        }
    }

    @Test public void recommendationVideoCardsUseTheSamePresenterAndExposePaintedCoverBounds() throws Exception {
        try (ZipFile apk=host()) {
            String coverModule=FEED + "quick/uimodule/FeedCoverModule;";
            String story=FEED + "quick/uimodule/StoryRootModule;";
            ClassDef holder=type(apk,25,FEED + "adapter/VideoViewHolder;");
            assertTrue(calls(method(holder,"LJJIIJZLJL","V"),story,"<init>","V",
                    VIEW,VIEW,"LX/0tOJ;","Z","Z"));
            assertTrue(calls(method(type(apk,25,story),"subModules","Ljava/util/List;"),
                    coverModule,"<init>","V",VIEW,"I"));
            ClassDef module=type(apk,25,coverModule);
            assertTrue(calls(method(module,"<init>","V",VIEW,"I"),PRESENTER,"<init>","V",VIEW));
            assertTrue(accesses(method(module,"presenter","Lcom/ss/android/ugc/aweme/kiwi/presenter/QIPresenter;"),
                    coverModule,"a",PRESENTER,"IGET"));
            ClassDef hierarchy=type(apk,38,"Lcom/facebook/drawee/generic/GenericDraweeHierarchy;");
            Method painted=method(hierarchy,"getActualImageBounds","V","Landroid/graphics/RectF;");
            assertTrue(calls(painted,"Landroid/graphics/Matrix;","mapRect","Z","Landroid/graphics/RectF;"));
        }
    }

    private static ZipFile host() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        assumeTrue(path != null && new File(path).isFile());
        assumeTrue(HostApkTestProfile.is410(new File(path)));
        return new ZipFile(path);
    }

    private static ClassDef type(ZipFile zip, int n, String name) throws Exception {
        try (var in = new BufferedInputStream(zip.getInputStream(zip.getEntry("classes" + n + ".dex")))) {
            for (ClassDef c : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), in).getClasses())
                if (name.equals(c.getType())) return c;
        }
        throw new AssertionError(name);
    }

    private static void field(ClassDef owner, String name, String type) {
        for (var f : owner.getFields()) if (name.equals(f.getName()) && type.equals(f.getType())) return;
        fail(owner.getType() + "->" + name + ":" + type);
    }

    private static Method method(ClassDef owner, String name, String result, String... params) {
        for (Method m : owner.getMethods()) if (name.equals(m.getName()) && result.equals(m.getReturnType())
                && m.getParameterTypes().equals(List.of(params))) return m;
        throw new AssertionError(owner.getType() + "->" + name + List.of(params) + result);
    }

    private static boolean calls(Method method, String owner, String name, String result, String... params) {
        for (var i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction r
                && r.getReference() instanceof MethodReference m && owner.equals(m.getDefiningClass())
                && name.equals(m.getName()) && result.equals(m.getReturnType()) && m.getParameterTypes().equals(List.of(params))) return true;
        return false;
    }

    private static boolean accesses(Method method, String owner, String name, String type, String opcodePrefix) {
        for (var i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction r
                && i.getOpcode().name().startsWith(opcodePrefix) && r.getReference() instanceof FieldReference f
                && owner.equals(f.getDefiningClass()) && name.equals(f.getName()) && type.equals(f.getType())) return true;
        return false;
    }

    private static boolean setsGone(Method method) {
        Instruction previous = null;
        for (var i : method.getImplementation().getInstructions()) {
            if (i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference m
                    && "setVisibility".equals(m.getName()) && m.getParameterTypes().equals(List.of("I"))
                    && i instanceof FiveRegisterInstruction registers && previous instanceof NarrowLiteralInstruction literal
                    && literal.getNarrowLiteral() == 8 && previous instanceof OneRegisterInstruction value
                    && registers.getRegisterD() == value.getRegisterA()) return true;
            previous = i;
        }
        return false;
    }

    private static boolean writesLoadedTrue(Method method) {
        int register = -1;
        for (var i : method.getImplementation().getInstructions()) {
            if (i instanceof NarrowLiteralInstruction literal && i instanceof OneRegisterInstruction value)
                register = literal.getNarrowLiteral() == 1 ? value.getRegisterA() : -1;
            if (i instanceof ReferenceInstruction r && r.getReference() instanceof FieldReference f
                    && i.getOpcode().name().startsWith("IPUT") && i instanceof TwoRegisterInstruction value
                    && PARAMS.equals(f.getDefiningClass()) && "isVideoCoverLoaded".equals(f.getName())
                    && value.getRegisterA() == register) return true;
        }
        return false;
    }

    /** Follow the local sF result and literal argument registers, rather than the DEX string pool. */
    private static Set<String> hideCoverReasons(Method method) {
        Set<String> reasons = new HashSet<>();
        var literals = new HashMap<Integer, String>();
        boolean awaitingCoverResult = false;
        int coverRegister = -1;
        for (var i : method.getImplementation().getInstructions()) {
            if (awaitingCoverResult) {
                coverRegister = i.getOpcode().name().equals("MOVE_RESULT_OBJECT") && i instanceof OneRegisterInstruction value
                        ? value.getRegisterA() : -1;
                awaitingCoverResult = false;
            }
            if (i instanceof ReferenceInstruction r && r.getReference() instanceof StringReference text
                    && i instanceof OneRegisterInstruction value) {
                literals.put(value.getRegisterA(), text.getString());
                if (coverRegister == value.getRegisterA()) coverRegister = -1;
            }
            if (i instanceof ReferenceInstruction r && r.getReference() instanceof MethodReference call) {
                if ((FEED + "quick/viewmodel/FeedItemFragmentVM;").equals(call.getDefiningClass())
                        && "sF".equals(call.getName()) && call.getParameterTypes().isEmpty()
                        && "Lcom/ss/android/ugc/aweme/kiwi/viewmodel/QLiveData;".equals(call.getReturnType()))
                    awaitingCoverResult = true;
                if ("Lcom/ss/android/ugc/aweme/kiwi/viewmodel/QLiveData;".equals(call.getDefiningClass())
                        && "setValue".equals(call.getName()) && call.getParameterTypes().equals(List.of("Ljava/lang/Object;"))
                        && i instanceof FiveRegisterInstruction registers && registers.getRegisterC() == coverRegister) {
                    String reason = literals.get(registers.getRegisterD());
                    if (reason != null) reasons.add(reason);
                    coverRegister = -1;
                }
            }
        }
        return reasons;
    }
}
