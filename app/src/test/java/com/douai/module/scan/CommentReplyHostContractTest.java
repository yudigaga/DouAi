package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.AnnotationElement;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Original DEX contracts that distinguish the comment UI category from its native inbox group. */
public final class CommentReplyHostContractTest {
    private static final String RESPONSE = "Lcom/ss/android/ugc/aweme/socialnotice/bean/NoticeResponse;";
    private static final String CONTEXT = "Lcom/ss/android/ugc/aweme/socialnotice/quick/context/NoticeContext;";
    private static final String FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;";
    private static final String INTEGER = "Ljava/lang/Integer;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String FACTORY = "LY/AObjectS353S0000000_36;";
    private static final String NOTICE = "Lcom/ss/android/ugc/aweme/socialnotice/bean/BaseNotice;";
    private static final String PAYLOAD = "Lcom/ss/android/ugc/aweme/socialnotice/bean/CommentNotice;";
    private static final String COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;";
    private static final String USER = "Lcom/ss/android/ugc/aweme/profile/model/User;";
    private static final String WORK = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static final String GENERAL = "LX/14Es;";
    private static final String ADDITIONAL = "LX/14Ep;";
    private static final String DETAIL = "LX/14Ea;";
    private static final String ACCESSORS = "LX/14Ec;";
    private static final String MINE = "Lcom/ss/android/ugc/aweme/socialnotice/bean/MineCommentResponse;";
    private static final String REPLY_LIST = "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;";
    private static final String REPLY_API = "Lcom/ss/android/ugc/aweme/comment/api/CommentRealApi;";
    private static final String REPLY_BUILDER = "LX/13ar;";
    private static final String REPLY_ARGS = "LX/13ao;";
    private static final String WORK_CONTEXT = "LX/13ea;";
    private static final String TASK = "Lbolts/Task;";
    private static final String PUBLISH_MODEL = "LX/13hi;";
    private static final String PUBLISH_BASE = "LX/13iG;";
    private static final String PUBLISH_BUILDER = "LX/13hg;";
    private static final String PUBLISH_BUILDER_BASE = "LX/13hD;";
    private static final String PUBLISH_PRESENTER =
            "Lcom/ss/android/ugc/aweme/comment/commentinputmanager/presenter/PublishPresenter;";
    private static final String PUBLISH_REQUEST =
            "Lcom/ss/android/ugc/aweme/comment/data/publish/CommentPublishRequest;";
    private static final Map<String, ClassDef> classes = new HashMap<>();
    private static boolean legacy;
    private static boolean latest;
    private static HostContractNameFacts names;

    @BeforeClass public static void readOnlyTheRequiredOriginalDexClasses() throws Exception {
        classes.clear();
        String configured = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is not configured", configured != null);
        File apk = new File(configured);
        assertTrue("DOUAI_TEST_APK must point to the original host APK", apk.isFile());
        HostAdaptation.Profile profile = HostApkTestProfile.profile(apk);
        legacy = profile.versionCode == 400601L;
        latest = profile.versionCode == 410001L;
        names = new HostContractNameFacts(profile);
        Set<String> required = new HashSet<>(Arrays.asList("LX/14LK;", "LX/14Mk;", FACTORY,
                "LX/14Gs;", "LX/14MH;", "LX/14MA;", "LX/14MD;", "LX/14Jz;", "LX/14PE;", "LX/14jX;",
                NOTICE, PAYLOAD, GENERAL, ADDITIONAL, DETAIL, ACCESSORS, "LX/14F9;", "LX/14FE;",
                "LX/14PD;", "LX/14Kj;", "LX/14HZ;", MINE));
        Set<String> commentClasses = new HashSet<>(Arrays.asList(COMMENT, "LX/12j8;", "LX/13a7;",
                REPLY_BUILDER, REPLY_ARGS, "LX/13wP;", "LX/13eY;", REPLY_API, REPLY_LIST, "LX/156M;",
                PUBLISH_MODEL, PUBLISH_BASE, PUBLISH_BUILDER, PUBLISH_BUILDER_BASE,
                PUBLISH_PRESENTER, PUBLISH_REQUEST));
        Set<String> taskClasses = new HashSet<>(Arrays.asList(TASK));
        try (ZipFile archive = new ZipFile(apk)) {
            required.addAll(commentClasses);
            required.addAll(taskClasses);
            for (ZipEntry entry : HostApkTestProfile.dexEntries(archive)) {
                if (required.isEmpty()) break;
                readSelectedClasses(archive, entry.getName(), required);
            }
            commentClasses.removeAll(classes.keySet());
            taskClasses.removeAll(classes.keySet());
        }
        assertTrue("Missing original comment inbox classes: " + required, required.isEmpty());
        assertTrue("Missing original comment recipient/publish classes: " + commentClasses, commentClasses.isEmpty());
        assertTrue("Missing original reply-list task class: " + taskClasses, taskClasses.isEmpty());
    }

    @AfterClass public static void releaseOriginalDexFacts() { classes.clear(); }

    @Test public void receivedCommentGroupIsResolvedFromTheNativeStringKeyAndReturnedAsAnInt() {
        Method resolver = method("LX/14LK;", "LJIIIIZZ", "I");
        assertTrue(AccessFlags.PUBLIC.isSet(resolver.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(resolver.getAccessFlags()));
        Code code = new Code(resolver);
        int key = stringIndex(code, "2");
        int keyRegister = ((OneRegisterInstruction) code.at(key)).getRegisterA();
        assertCall(code.at(key + 1), "LX/14Mk;", "LIZ", "I", STRING);
        assertEquals(keyRegister, ((FiveRegisterInstruction) code.at(key + 1)).getRegisterC());
        assertEquals(Opcode.MOVE_RESULT, code.at(key + 2).getOpcode());
        assertEquals(Opcode.RETURN, code.at(key + 3).getOpcode());
        assertEquals(((OneRegisterInstruction) code.at(key + 2)).getRegisterA(),
                ((OneRegisterInstruction) code.at(key + 3)).getRegisterA());
        assertTrue("The resolver must consult the host's current mapping",
                referencesField(method("LX/14Mk;", "LIZ", "I", STRING), "LX/14Mk;", "LIZLLL"));
    }

    @Test public void theNativeDefaultMapStoresFortyFourForReceivedCommentsRatherThanUiCategoryThree() {
        // Both verified originals (40609900 and 40809900) keep this dispatcher
        // directly in invoke; the retired 40809985 split bridge is not a target.
        Method initializer = method(FACTORY, "invoke", "Ljava/lang/Object;");
        Code code = new Code(initializer);
        int key = stringIndexFollowedByCall(code, "2", "LX/14Ml;", "LIZJ");
        int keyRegister = ((OneRegisterInstruction) code.at(key)).getRegisterA();
        assertEquals(Opcode.IF_EQ, code.at(key + 4).getOpcode());
        int fallback = code.target(key + 4);
        assertEquals(44, ((NarrowLiteralInstruction) code.at(fallback)).getNarrowLiteral());
        int valueRegister = ((OneRegisterInstruction) code.at(fallback)).getRegisterA();
        assertTrue(code.at(fallback + 1).getOpcode().name().startsWith("GOTO"));
        int box = code.target(fallback + 1);
        assertCall(code.at(box), INTEGER, "valueOf", INTEGER, "I");
        assertEquals(valueRegister, ((FiveRegisterInstruction) code.at(box)).getRegisterC());
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code.at(box + 1).getOpcode());
        int boxedRegister = ((OneRegisterInstruction) code.at(box + 1)).getRegisterA();
        assertCall(code.at(box + 2), "LX/0V8X;", latest ? "LLZLLIL" : "LLLLJI", "Ljava/lang/Object;",
                "Ljava/util/HashMap;", "Ljava/lang/Object;", "Ljava/lang/Object;");
        FiveRegisterInstruction put = (FiveRegisterInstruction) code.at(box + 2);
        assertEquals("The default map must retain key 2", keyRegister, put.getRegisterD());
        assertEquals("The stored value must be the boxed fallback 44", boxedRegister, put.getRegisterE());
        assertTrue("This factory is the default-map initializer used by the resolver",
                calls(method("LX/14Mk;", "<clinit>", "V"), FACTORY, "<init>"));
    }

    @Test public void theInteractivePagePassesItsResolvedSubtypeToFetchNoticeInsteadOfItsPrimaryUiCategory() {
        assertTrue(referencesField(method("LX/14Gs;", "LIZ", "I", CONTEXT), "LX/14J4;", "LIZ"));
        assertTrue(referencesField(method("LX/14Gs;", "LJ", "I", CONTEXT), "LX/14J4;", "LIZJ"));
        Method model = method("LX/14MA;", "LJII", "V", "LX/14u0;");
        assertTrue(referencesField(model, "LX/14MH;", "LIZIZ"));
        assertFalse("The network path must not read the primary UI category", referencesField(model, "LX/14MH;", "LIZ"));
        Code modelCode = new Code(model);
        int readSubtype = fieldIndex(modelCode, "LX/14MH;", "LIZIZ");
        int subtypeRegister = ((OneRegisterInstruction) modelCode.at(readSubtype)).getRegisterA();
        int construct = callIndex(modelCode, "LX/14MD;", "<init>");
        int constructorStart = ((RegisterRangeInstruction) modelCode.at(construct)).getStartRegister();
        assertEquals("After receiver and model, the request constructor receives the subtype register",
                constructorStart + 2, subtypeRegister);

        Method request = method("LX/14MD;", "subscribe", "V", "Lio/reactivex/SingleEmitter;");
        Code requestCode = new Code(request);
        int captured = fieldIndex(requestCode, "LX/14MD;", "LIZIZ");
        int capturedRegister = ((OneRegisterInstruction) requestCode.at(captured)).getRegisterA();
        int wrapper = callIndex(requestCode, "LX/14Jz;", "LIZ");
        int wrapperStart = ((RegisterRangeInstruction) requestCode.at(wrapper)).getStartRegister();
        assertTrue("The wrapper's group argument must be copied from the captured subtype",
                movesRegisterBefore(requestCode, wrapper, capturedRegister, wrapperStart + 5));

        Method bridge = method("LX/14Jz;", "LIZ", RESPONSE,
                "J", "J", "I", "I", INTEGER, "I", "I", "Z", "I", STRING, "I", "I", STRING, "I", STRING, "I", "I", "I");
        Code bridgeCode = new Code(bridge);
        int fetch = callIndex(bridgeCode, "LX/14PD;", "fetchNotice");
        int fetchStart = ((RegisterRangeInstruction) bridgeCode.at(fetch)).getStartRegister();
        int originalGroup = bridge.getImplementation().getRegisterCount() - 20 + 5;
        assertTrue("The native API group slot must preserve the wrapper's group argument",
                movesRegisterBefore(bridgeCode, fetch, originalGroup, fetchStart + 6));
    }

    @Test public void theReadOnlyApiHasTheExpectedTwentyThreeQuerySlotsAndNativeProvider() {
        Method fetch = method("LX/14PE;", "fetchNotice", FUTURE,
                "J", "J", "I", "I", INTEGER, "I", INTEGER, INTEGER, "I", STRING, STRING, STRING,
                "I", STRING, "I", STRING, "I", "I", STRING, "I", "I", INTEGER, "I");
        List<String> queries = new ArrayList<>();
        for (var parameter : fetch.getParameters()) queries.add(query(parameter.getAnnotations()));
        assertEquals(Arrays.asList("max_time", "min_time", "count", "notice_group", "top_group",
                "is_mark_read", "address_book_access", "gps_access", "is_new_notice", "user_avatar_shrink",
                "video_cover_shrink", "pin_notice_id", "sub_category", "scene", "interactive_disable_style",
                "top_notice_ids", "notice_unread_count", "current_unread_count", "del_notice_id", "preload",
                "enable_official_disable", "notice_type", "enable_type_filter"), queries);
        assertTrue(AccessFlags.ABSTRACT.isSet(fetch.getAccessFlags()));
        Field provider = field("LX/14jX;", "b", "LX/14PE;");
        assertTrue(AccessFlags.STATIC.isSet(provider.getAccessFlags()));
        assertTrue(AccessFlags.FINAL.isSet(provider.getAccessFlags()));
    }

    @Test public void bothInteractiveNoticeEnvelopesExposeTheirSerializedCommentModelsDirectly() {
        assertSerializedField(NOTICE, "commentNotice", PAYLOAD, "comment");
        assertSerializedField(NOTICE, "interactiveGeneralNoticeStruct", GENERAL, "interactive_notice");
        assertSerializedField(GENERAL, "F", PAYLOAD, "comment");
        assertSerializedField(GENERAL, "Q", ADDITIONAL, "additional_info");
        assertSerializedField(ADDITIONAL, "d", DETAIL, "comment");
        assertSerializedField(DETAIL, "a", COMMENT, "current_comment");
        assertDirectGetter(NOTICE, "LJ", PAYLOAD, "commentNotice", Opcode.IGET_OBJECT);
        assertDirectGetter(GENERAL, "LJIILL", PAYLOAD, "F", Opcode.IGET_OBJECT);
        assertDirectGetter(GENERAL, "LJII", ADDITIONAL, "Q", Opcode.IGET_OBJECT);
        assertDirectGetter(ADDITIONAL, "LIZIZ", DETAIL, "d", Opcode.IGET_OBJECT);
        assertDirectGetter(DETAIL, "LIZ", COMMENT, "a", Opcode.IGET_OBJECT);

        Method detail = method(ACCESSORS, "LIZ", DETAIL, NOTICE);
        assertTrue(AccessFlags.PUBLIC.isSet(detail.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(detail.getAccessFlags()));
        assertTrue(referencesField(detail, NOTICE, "interactiveGeneralNoticeStruct"));
        assertTrue(calls(detail, GENERAL, "LJII"));
        assertTrue(calls(detail, ADDITIONAL, "LIZIZ"));
    }

    @Test public void nativePayloadAndCurrentCommentAccessorsPreserveBothFallbackOrders() {
        Method payload = method(ACCESSORS, "LIZLLL", PAYLOAD, NOTICE);
        assertTrue(AccessFlags.PUBLIC.isSet(payload.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(payload.getAccessFlags()));
        Code payloadCode = new Code(payload);
        int nested = callIndex(payloadCode, GENERAL, "LJIILL");
        int legacy = callIndex(payloadCode, NOTICE, "LJ");
        assertTrue("Nested interactive_notice.comment must be tried before the legacy field", nested < legacy);
        assertEquals(Opcode.MOVE_RESULT_OBJECT, payloadCode.at(nested + 1).getOpcode());
        assertEquals(Opcode.IF_NEZ, payloadCode.at(nested + 2).getOpcode());
        assertEquals("A non-null nested payload returns without consulting the legacy payload",
                Opcode.RETURN_OBJECT, payloadCode.at(payloadCode.target(nested + 2)).getOpcode());
        assertEquals("A null nested payload must flow directly to the legacy getter", legacy, nested + 3);

        Method target = method(ACCESSORS, "LIZJ", COMMENT, NOTICE);
        assertTrue(AccessFlags.PUBLIC.isSet(target.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(target.getAccessFlags()));
        Code targetCode = new Code(target);
        int detail = callIndex(targetCode, ACCESSORS, "LIZ");
        int current = callIndex(targetCode, DETAIL, "LIZ");
        int fallback = callIndex(targetCode, ACCESSORS, "LIZLLL");
        int payloadComment = callIndex(targetCode, PAYLOAD, "LJFF");
        assertTrue(detail < current && current < fallback && fallback < payloadComment);
        assertEquals(Opcode.IF_NEZ, targetCode.at(current + 2).getOpcode());
        assertEquals("A non-null additional_info current_comment wins over either payload envelope",
                Opcode.RETURN_OBJECT, targetCode.at(targetCode.target(current + 2)).getOpcode());
        assertEquals("A null current_comment falls back to the payload accessor", fallback, current + 3);
    }

    @Test public void nativeAdditionalDetailSeparatesCurrentCommentVisibilityReplyFlagLevelOneAndParentMetadata() {
        assertSerializedField(DETAIL, "b", "I", "comment_unvisible");
        assertSerializedField(DETAIL, "d", "Z", "is_reply");
        assertSerializedField(DETAIL, "e", COMMENT, "level1_comment");
        assertSerializedField(DETAIL, "f", STRING, "parent_id");
        assertDirectGetter(DETAIL, "LIZIZ", "I", "b", Opcode.IGET);
        assertDirectGetter(DETAIL, "LJ", "Z", "d", Opcode.IGET_BOOLEAN);
        assertDirectGetter(DETAIL, "LIZJ", COMMENT, "e", Opcode.IGET_OBJECT);
        assertDirectGetter(DETAIL, "LIZLLL", STRING, "f", Opcode.IGET_OBJECT);

        Code levelOne = new Code(method(ACCESSORS, "LJII", COMMENT, NOTICE));
        int payload = callIndex(levelOne, ACCESSORS, "LIZLLL");
        int payloadLevelOne = callIndex(levelOne, PAYLOAD, latest ? "LJIILIIL" : "LJIILJJIL");
        int detail = callIndex(levelOne, ACCESSORS, "LIZ");
        int detailLevelOne = callIndex(levelOne, DETAIL, "LIZJ");
        assertTrue(payload < payloadLevelOne && payloadLevelOne < detail && detail < detailLevelOne);
        assertEquals("When a payload exists, its level-one result is returned directly",
                Opcode.RETURN_OBJECT, levelOne.at(payloadLevelOne + 2).getOpcode());
        assertTrue(calls(method(ACCESSORS, "LJIIJJI", "Z", NOTICE), DETAIL, "LJ"));
        // parent_id is a separate host metadata field. These contracts deliberately do
        // not equate it with the target Comment's replyId/replyToReplyId or prove an author.
    }

    @Test public void nativeReplyUsesTheUnifiedTargetAndGeneralWorkEvenWhenThePayloadWorkHelperReturnsNull() {
        Method reply = method("LX/14FE;", "LIZLLL", "Z", NOTICE, "LX/159L;");
        assertEquals("LX/14F9;", classes.get("LX/14FE;").getSuperclass());
        assertTrue(calls(reply, "LX/14F9;", "LIZLLL"));
        Code replyCode = new Code(reply);
        int target = callIndex(replyCode, ACCESSORS, "LIZJ");
        assertEquals(Opcode.MOVE_RESULT_OBJECT, replyCode.at(target + 1).getOpcode());
        assertEquals(Opcode.IPUT_OBJECT, replyCode.at(target + 2).getOpcode());
        assertFieldReference(replyCode.at(target + 2), "LX/159L;", "a", COMMENT);
        assertEquals(((OneRegisterInstruction) replyCode.at(target + 1)).getRegisterA(),
                ((OneRegisterInstruction) replyCode.at(target + 2)).getRegisterA());

        Code replyBase = new Code(method("LX/14F9;", "LIZLLL", "Z", NOTICE, "LX/159L;"));
        int work = callIndex(replyBase, GENERAL, "getAweme");
        assertEquals(Opcode.IPUT_OBJECT, replyBase.at(work + 2).getOpcode());
        assertFieldReference(replyBase.at(work + 2), "LX/159L;", "f", WORK);
        assertSerializedField(GENERAL, "p", WORK, "aweme");
        assertDirectGetter(GENERAL, "getAweme", WORK, "p", Opcode.IGET_OBJECT);

        Code helper = new Code(method(ACCESSORS, "LIZIZ", WORK, NOTICE));
        int payloadWork = callIndex(helper, PAYLOAD, "getAweme");
        int generalWork = callIndex(helper, GENERAL, "getAweme");
        assertTrue(payloadWork < generalWork);
        assertEquals("The convenience helper returns a present payload's null aweme directly; it does not retry GeneralNotice.aweme",
                Opcode.RETURN_OBJECT, helper.at(payloadWork + 2).getOpcode());
    }

    @Test public void nativeReplyRecipientUsesTheSerializedUidAndComparesItWithTheCurrentAccount() {
        assertSerializedField(COMMENT, "replyToUserId", STRING, "reply_to_userid");
        assertDirectGetter(COMMENT, "LLLLJI", STRING, "replyToUserId", Opcode.IGET_OBJECT);
        Code recipient = new Code(method("LX/12j8;", "LIZ", STRING, COMMENT));
        int account = callIndex(recipient,
                "Lcom/ss/android/ugc/aweme/account/service/IAccountUserService;", "getCurUserId");
        assertEquals(Opcode.MOVE_RESULT_OBJECT, recipient.at(account + 1).getOpcode());
        int ownUid = ((OneRegisterInstruction) recipient.at(account + 1)).getRegisterA();
        int repliedUser = callIndex(recipient, COMMENT, "LLLLJI");
        int recipientUid = ((OneRegisterInstruction) recipient.at(repliedUser + 1)).getRegisterA();
        int compare = callIndex(recipient, "Lkotlin/jvm/internal/Intrinsics;", "areEqual");
        assertTrue(account < repliedUser && repliedUser < compare);
        FiveRegisterInstruction comparison = (FiveRegisterInstruction) recipient.at(compare);
        assertEquals(ownUid, comparison.getRegisterC());
        assertEquals(recipientUid, comparison.getRegisterD());
        assertEquals(Opcode.IF_NEZ, recipient.at(compare + 2).getOpcode());
        assertEquals("The native path returns when the replied-to UID differs from the current account",
                Opcode.RETURN_OBJECT, recipient.at(compare + 3).getOpcode());
    }

    @Test public void nativeCommentCloneKeepsTheCapturedIdentityRecipientAndImagesWithoutWritingTheOriginal() {
        assertTrue(classes.get(COMMENT).getInterfaces().contains("Ljava/lang/Cloneable;"));
        Method bridge = method(COMMENT, "clone", "Ljava/lang/Object;");
        assertTrue(AccessFlags.PUBLIC.isSet(bridge.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(bridge.getAccessFlags()));
        assertTrue(bridge.getParameterTypes().isEmpty());
        Code bridgeCode = new Code(bridge);
        int copyCall = callIndex(bridgeCode, COMMENT, "LJZ");
        assertCall(bridgeCode.at(copyCall), COMMENT, "LJZ", COMMENT);
        assertEquals(Opcode.RETURN_OBJECT, bridgeCode.at(copyCall + 2).getOpcode());

        Method copier = method(COMMENT, "LJZ", COMMENT);
        Code copy = new Code(copier);
        assertEquals(Opcode.NEW_INSTANCE, copy.at(0).getOpcode());
        int cloneRegister = ((OneRegisterInstruction) copy.at(0)).getRegisterA();
        int originalRegister = copier.getImplementation().getRegisterCount() - 1;
        for (String fieldName : new String[]{"cid", "text", "awemeId", "replyToUserId", "imageList"}) {
            int read = fieldIndex(copy, COMMENT, fieldName);
            assertEquals(Opcode.IGET_OBJECT, copy.at(read).getOpcode());
            assertEquals(originalRegister, ((TwoRegisterInstruction) copy.at(read)).getRegisterB());
            assertEquals(Opcode.IPUT_OBJECT, copy.at(read + 1).getOpcode());
            FieldReference source = (FieldReference) reference(copy.at(read));
            assertFieldReference(copy.at(read + 1), COMMENT, fieldName, source.getType());
            TwoRegisterInstruction write = (TwoRegisterInstruction) copy.at(read + 1);
            assertEquals(((OneRegisterInstruction) copy.at(read)).getRegisterA(), write.getRegisterA());
            assertEquals(cloneRegister, write.getRegisterB());
        }
        int user = callIndex(copy, COMMENT, "LLLLZLL");
        assertEquals(originalRegister, ((FiveRegisterInstruction) copy.at(user)).getRegisterC());
        assertEquals(Opcode.IPUT_OBJECT, copy.at(user + 2).getOpcode());
        assertFieldReference(copy.at(user + 2), COMMENT, "user", USER);
        assertEquals(cloneRegister, ((TwoRegisterInstruction) copy.at(user + 2)).getRegisterB());

        Method userGetter = method(COMMENT, "LLLLZLL", USER);
        Code userCode = new Code(userGetter);
        int preferredUser = fieldIndex(userCode, COMMENT, "commentUser");
        int fallbackUser = fieldIndex(userCode, COMMENT, "user");
        assertEquals(Opcode.IGET_OBJECT, userCode.at(preferredUser).getOpcode());
        TwoRegisterInstruction preferredRead = (TwoRegisterInstruction) userCode.at(preferredUser);
        assertEquals(userGetter.getImplementation().getRegisterCount() - 1, preferredRead.getRegisterB());
        assertEquals(Opcode.IF_NEZ, userCode.at(preferredUser + 1).getOpcode());
        assertEquals(preferredRead.getRegisterA(),
                ((OneRegisterInstruction) userCode.at(preferredUser + 1)).getRegisterA());
        assertEquals("A null commentUser falls through to the raw user field", preferredUser + 2, fallbackUser);
        assertEquals(Opcode.IGET_OBJECT, userCode.at(fallbackUser).getOpcode());
        TwoRegisterInstruction fallbackRead = (TwoRegisterInstruction) userCode.at(fallbackUser);
        assertEquals(preferredRead.getRegisterA(), fallbackRead.getRegisterA());
        assertEquals(preferredRead.getRegisterB(), fallbackRead.getRegisterB());
        int returnUser = userCode.target(preferredUser + 1);
        assertEquals("A non-null commentUser skips the raw user fallback", fallbackUser + 1, returnUser);
        assertEquals(Opcode.RETURN_OBJECT, userCode.at(returnUser).getOpcode());
        assertEquals(preferredRead.getRegisterA(),
                ((OneRegisterInstruction) userCode.at(returnUser)).getRegisterA());
        for (Instruction instruction : copy.instructions) {
            if (instruction.getOpcode().name().startsWith("IPUT")) {
                assertNotEquals("The host clone must not assign fields on the original comment",
                        originalRegister, ((TwoRegisterInstruction) instruction).getRegisterB());
            }
        }
        assertFalse("Capture local original-comment context before cloning; the host does not copy it",
                referencesField(copier, COMMENT, "replyToReplyCommentText"));
        assertFalse(referencesField(copier, COMMENT, "replyUser"));
    }

    @Test public void nativePublishHelpersHaveProvidedRootAndIncomingCidTargetBranches() {
        Method root = method("LX/13a7;", "LJ", STRING, COMMENT, "LX/13hi;");
        Method directTarget = method("LX/13a7;", "LJFF", STRING, COMMENT, "LX/13hi;");
        assertDirectGetter(COMMENT, "LLLLIILL", STRING, "replyId", Opcode.IGET_OBJECT);
        assertDirectGetter(COMMENT, "LLIIIJ", STRING, "cid", Opcode.IGET_OBJECT);
        assertTrue("The native root branch can return the target's explicit replyId",
                returnsFirstArgumentGetter(root, "LLLLIILL"));
        assertTrue("The direct reply branch returns the incoming target's own CID",
                returnsFirstArgumentGetter(directTarget, "LLIIIJ"));
        assertTrue("The direct reply helper also consults the target's root relationship",
                calls(directTarget, COMMENT, "LLLLIILL"));
        // Assigning a verified level1_comment CID to a missing root is module
        // compatibility logic, not an operation performed by these native helpers.
    }

    @Test public void nativePublishBuilderCopiesLegacyReplyIdsSeparatelyFromTheTargetComment() {
        assertEquals(PUBLISH_BASE, classes.get(PUBLISH_MODEL).getSuperclass());
        assertEquals(PUBLISH_BUILDER_BASE, classes.get(PUBLISH_BUILDER).getSuperclass());
        Code builder = new Code(method(PUBLISH_BUILDER, "LIZ", PUBLISH_MODEL));
        for (String[] mapping : new String[][]{
                {PUBLISH_BUILDER_BASE, "LIZJ", PUBLISH_BASE, "b", STRING},
                {PUBLISH_BUILDER_BASE, "LJ", PUBLISH_BASE, "e", STRING},
                {PUBLISH_BUILDER, "LJIILLIIL", PUBLISH_MODEL, "p", COMMENT}}) {
            assertFalse(AccessFlags.STATIC.isSet(field(mapping[0], mapping[1], mapping[4]).getAccessFlags()));
            assertFalse(AccessFlags.STATIC.isSet(field(mapping[2], mapping[3], mapping[4]).getAccessFlags()));
            int read = fieldIndex(builder, mapping[0], mapping[1]);
            assertEquals(Opcode.IGET_OBJECT, builder.at(read).getOpcode());
            assertEquals(Opcode.IPUT_OBJECT, builder.at(read + 1).getOpcode());
            assertFieldReference(builder.at(read + 1), mapping[2], mapping[3], mapping[4]);
            assertEquals(((OneRegisterInstruction) builder.at(read)).getRegisterA(),
                    ((OneRegisterInstruction) builder.at(read + 1)).getRegisterA());
        }
        assertFalse("The base constructor leaves the legacy root unset",
                referencesField(method(PUBLISH_BASE, "<init>", "V"), PUBLISH_BASE, "b"));
        assertFalse("The base constructor leaves the legacy direct target unset",
                referencesField(method(PUBLISH_BASE, "<init>", "V"), PUBLISH_BASE, "e"));
        assertFalse(referencesField(method(PUBLISH_MODEL, "<init>", "V"), PUBLISH_BASE, "b"));
        assertFalse(referencesField(method(PUBLISH_MODEL, "<init>", "V"), PUBLISH_BASE, "e"));
    }

    @Test public void nativeUiBuildsNestedRepliesWithTheTargetsRootAndItsOwnCid() {
        assertDirectGetter(COMMENT, "LLLLIILL", STRING, "replyId", Opcode.IGET_OBJECT);
        assertDirectGetter(COMMENT, "LLIIIJ", STRING, "cid", Opcode.IGET_OBJECT);
        Code ui = new Code(method(PUBLISH_PRESENTER, "LJIIZILJ", PUBLISH_BUILDER, "LX/13gy;"));
        int pairedNestedBranches = 0;
        for (int index = 0; index + 5 < ui.instructions.size(); index++) {
            Object candidate = reference(ui.at(index));
            if (!(candidate instanceof MethodReference)) continue;
            MethodReference getter = (MethodReference) candidate;
            if (!COMMENT.equals(getter.getDefiningClass()) || !"LLLLIILL".equals(getter.getName())
                    || ui.at(index + 2).getOpcode() != Opcode.IPUT_OBJECT
                    || !(reference(ui.at(index + 2)) instanceof FieldReference)
                    || !"LIZJ".equals(((FieldReference) reference(ui.at(index + 2))).getName())) continue;
            // Only the adjacent root/cid pair is evidence of nested reply targeting.
            Object directCandidate = reference(ui.at(index + 3));
            if (!(directCandidate instanceof MethodReference)
                    || !"LLIIIJ".equals(((MethodReference) directCandidate).getName())) continue;
            assertCall(ui.at(index), COMMENT, "LLLLIILL", STRING);
            assertCall(ui.at(index + 3), COMMENT, "LLIIIJ", STRING);
            assertEquals(Opcode.MOVE_RESULT_OBJECT, ui.at(index + 1).getOpcode());
            assertEquals(Opcode.MOVE_RESULT_OBJECT, ui.at(index + 4).getOpcode());
            assertEquals(Opcode.IPUT_OBJECT, ui.at(index + 5).getOpcode());
            assertFieldReference(ui.at(index + 2), PUBLISH_BUILDER_BASE, "LIZJ", STRING);
            assertFieldReference(ui.at(index + 5), PUBLISH_BUILDER_BASE, "LJ", STRING);
            assertEquals("Both IDs must be read from the same actual target Comment",
                    ((FiveRegisterInstruction) ui.at(index)).getRegisterC(),
                    ((FiveRegisterInstruction) ui.at(index + 3)).getRegisterC());
            assertEquals(((OneRegisterInstruction) ui.at(index + 1)).getRegisterA(),
                    ((OneRegisterInstruction) ui.at(index + 2)).getRegisterA());
            assertEquals(((OneRegisterInstruction) ui.at(index + 4)).getRegisterA(),
                    ((OneRegisterInstruction) ui.at(index + 5)).getRegisterA());
            assertEquals("The root and direct target must populate the same native builder",
                    ((TwoRegisterInstruction) ui.at(index + 2)).getRegisterB(),
                    ((TwoRegisterInstruction) ui.at(index + 5)).getRegisterB());
            pairedNestedBranches++;
        }
        assertTrue("The native UI must retain actual nested-reply root/cid branches", pairedNestedBranches >= 6);
    }

    @Test public void ordinaryNativePublishHelpersReturnLegacyModelIdsWhenReplyTreeRoutingIsDisabled() {
        for (String[] helper : new String[][]{{"LJ", "b"}, {"LJFF", "e"}}) {
            Method target = method("LX/13a7;", helper[0], STRING, COMMENT, PUBLISH_MODEL);
            assertTrue(AccessFlags.PUBLIC.isSet(target.getAccessFlags()));
            assertTrue(AccessFlags.STATIC.isSet(target.getAccessFlags()));
            Code code = new Code(target);
            String routingFlag = latest ? "LX/00uL;" : "LX/00s8;";
            int flag = callIndex(code, routingFlag, "LIZ");
            assertCall(code.at(flag), routingFlag, "LIZ", "Z");
            assertEquals(Opcode.MOVE_RESULT, code.at(flag + 1).getOpcode());
            assertEquals(Opcode.IF_NEZ, code.at(flag + 2).getOpcode());
            assertEquals(((OneRegisterInstruction) code.at(flag + 1)).getRegisterA(),
                    ((OneRegisterInstruction) code.at(flag + 2)).getRegisterA());
            assertCall(code.at(flag + 3), "LX/155h;", "LIZIZ", "Z", COMMENT);
            assertEquals("Target routing must consult the actual first Comment argument",
                    target.getImplementation().getRegisterCount() - 2,
                    ((FiveRegisterInstruction) code.at(flag + 3)).getRegisterC());
            assertEquals(Opcode.MOVE_RESULT, code.at(flag + 4).getOpcode());
            assertEquals(Opcode.IF_NEZ, code.at(flag + 5).getOpcode());
            assertEquals(((OneRegisterInstruction) code.at(flag + 4)).getRegisterA(),
                    ((OneRegisterInstruction) code.at(flag + 5)).getRegisterA());
            assertEquals("Either enabled routing condition skips the legacy return",
                    code.target(flag + 2), code.target(flag + 5));
            assertEquals(Opcode.IGET_OBJECT, code.at(flag + 6).getOpcode());
            assertFieldReference(code.at(flag + 6), PUBLISH_BASE, helper[1], STRING);
            assertEquals("The disabled branch must read from the actual second model argument",
                    target.getImplementation().getRegisterCount() - 1,
                    ((TwoRegisterInstruction) code.at(flag + 6)).getRegisterB());
            assertEquals(Opcode.RETURN_OBJECT, code.at(flag + 7).getOpcode());
            assertEquals(((OneRegisterInstruction) code.at(flag + 6)).getRegisterA(),
                    ((OneRegisterInstruction) code.at(flag + 7)).getRegisterA());
        }
    }

    @Test public void nativePublishEndpointsReceiveTheEffectiveRootAndDirectHelperResults() {
        for (String[] path : new String[][]{
                {"LJII", "Ljava/lang/Object;", "LJII", "/aweme/v1/comment/publish/", "5"},
                {"LJIILL", PUBLISH_MODEL, "LJIIL", "/aweme/v1/comment/publish_evaluate/", "6"}}) {
            Method caller = method(PUBLISH_REQUEST, path[0], "LJII".equals(path[0]) ? "Lio/reactivex/Observable;" : "V", path[1]);
            Method endpoint = singleMethod(REPLY_API, path[2]);
            assertEquals(path[3], annotationValue(endpoint.getAnnotations(), "Lretrofit2/http/POST;"));
            Code code = new Code(caller);
            int request = callIndex(code, REPLY_API, path[2]);
            assertEquals(Opcode.INVOKE_INTERFACE_RANGE, code.at(request).getOpcode());
            int requestStart = ((RegisterRangeInstruction) code.at(request)).getStartRegister();
            for (String[] helper : new String[][]{{"LJ", "2", "reply_id"}, {"LJFF", path[4], "reply_to_reply_id"}}) {
                int parameter = Integer.parseInt(helper[1]);
                assertEquals(STRING, endpoint.getParameters().get(parameter).getType());
                assertEquals(helper[2], annotationValue(endpoint.getParameters().get(parameter).getAnnotations(),
                        "Lretrofit2/http/Field;"));
                int invoke = callIndex(code, "LX/13a7;", helper[0]);
                assertCall(code.at(invoke), "LX/13a7;", helper[0], STRING, COMMENT, PUBLISH_MODEL);
                assertEquals(Opcode.IGET_OBJECT, code.at(invoke - 1).getOpcode());
                assertFieldReference(code.at(invoke - 1), PUBLISH_MODEL, "p", COMMENT);
                TwoRegisterInstruction targetRead = (TwoRegisterInstruction) code.at(invoke - 1);
                FiveRegisterInstruction helperCall = (FiveRegisterInstruction) code.at(invoke);
                assertEquals(targetRead.getRegisterA(), helperCall.getRegisterC());
                assertEquals("Target and model must be taken from the same prepared native model",
                        targetRead.getRegisterB(), helperCall.getRegisterD());
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code.at(invoke + 1).getOpcode());
                int resultRegister = ((OneRegisterInstruction) code.at(invoke + 1)).getRegisterA();
                assertEquals("The helper result must occupy its exact native wire parameter register",
                        parameterRegister(endpoint, requestStart, parameter), resultRegister);
                assertTrue(invoke + 1 < request);
                for (int index = invoke + 2; index < request; index++) {
                    Instruction instruction = code.at(index);
                    if (instruction.getOpcode().setsRegister() && instruction instanceof OneRegisterInstruction) {
                        assertNotEquals("The wire ID must not be overwritten before the service call",
                                resultRegister, ((OneRegisterInstruction) instruction).getRegisterA());
                    }
                }
            }
        }
    }

    @Test public void ownCommentsUseTheNativeSelfListEndpointAndRawCommentNoticeItems() {
        Method selfList = method("LX/14PD;", "getMineComment", "Lio/reactivex/Observable;", "J", "I");
        assertEquals("/aweme/v1/comment/self/list/",
                annotationValue(selfList.getAnnotations(), "Lretrofit2/http/GET;"));
        assertEquals("start_time", query(selfList.getParameters().get(0).getAnnotations()));
        assertEquals("count", query(selfList.getParameters().get(1).getAnnotations()));
        assertTrue(annotationValue(selfList.getAnnotations(), "Ldalvik/annotation/Signature;")
                .contains("Lio/reactivex/Observable<" + MINE + ">;"));
        Field provider = field("LX/14Jz;", "b", "LX/14PD;");
        assertTrue(AccessFlags.STATIC.isSet(provider.getAccessFlags()));
        assertTrue(AccessFlags.FINAL.isSet(provider.getAccessFlags()));

        assertEquals("Lcom/ss/android/ugc/aweme/base/api/BaseResponse;", classes.get(MINE).getSuperclass());
        assertSerializedField(MINE, "items", "Ljava/util/List;", "comment_list");
        assertEquals("Ljava/util/List<" + PAYLOAD + ">;",
                annotationValue(field(MINE, "items", "Ljava/util/List;").getAnnotations(),
                        "Ldalvik/annotation/Signature;"));
        assertSerializedField(MINE, "hasMore", "Z", "has_more");
        assertSerializedField(MINE, "minTime", "J", "min_time");
        assertSerializedField(MINE, "maxTime", "J", "max_time");
        assertSerializedField(MINE, "lastReadTime", "J", "last_read_time");
        assertTrue("The UI conversion marks entries mine locally; that flag alone cannot prove authorship",
                calls(method(MINE, "getItems", "Ljava/util/List;"), PAYLOAD, latest ? "LJJ" : "LJJIFFI"));

        Code nativeRequest = new Code(method("LX/14Kj;", "LJI", "V"));
        int invoke = callIndex(nativeRequest, "LX/14PD;", "getMineComment");
        FiveRegisterInstruction request = (FiveRegisterInstruction) nativeRequest.at(invoke);
        int timeRead = fieldIndex(nativeRequest, "LX/14Kj;", "LJIIIZ");
        assertEquals(Opcode.IGET_WIDE, nativeRequest.at(timeRead).getOpcode());
        assertEquals(((OneRegisterInstruction) nativeRequest.at(timeRead)).getRegisterA(), request.getRegisterD());
        assertEquals(20, ((NarrowLiteralInstruction) nativeRequest.at(invoke - 1)).getNarrowLiteral());
        assertEquals(((OneRegisterInstruction) nativeRequest.at(invoke - 1)).getRegisterA(), request.getRegisterF());
    }

    @Test public void replyListBuilderPreservesRootPaginationOwnContextAndNativeWorkResolution() {
        Code constructor = new Code(method(REPLY_ARGS, "<init>", "V", REPLY_BUILDER));
        for (String[] mapping : new String[][]{
                {"LIZ", "LIZ", WORK_CONTEXT}, {"LJII", "LIZIZ", STRING},
                {"LJIIIZ", "LIZLLL", "Ljava/lang/Long;"}, {"LJIIJJI", "LJFF", INTEGER},
                {"LJIIJ", "LJ", STRING}, {"LJJII", "LJIJJLI", COMMENT}, {"LJJIII", "LJJIII", STRING}}) {
            Field source = field(REPLY_BUILDER, mapping[0], mapping[2]);
            assertFalse(AccessFlags.STATIC.isSet(source.getAccessFlags()));
            assertTrue(AccessFlags.FINAL.isSet(field(REPLY_ARGS, mapping[1], mapping[2]).getAccessFlags()));
            int read = fieldIndex(constructor, REPLY_BUILDER, mapping[0]);
            assertEquals(Opcode.IGET_OBJECT, constructor.at(read).getOpcode());
            assertEquals(Opcode.IPUT_OBJECT, constructor.at(read + 1).getOpcode());
            assertFieldReference(constructor.at(read + 1), REPLY_ARGS, mapping[1], mapping[2]);
            assertEquals(((OneRegisterInstruction) constructor.at(read)).getRegisterA(),
                    ((OneRegisterInstruction) constructor.at(read + 1)).getRegisterA());
        }
        Code item = new Code(method(REPLY_ARGS, "LIZ", STRING));
        int context = fieldIndex(item, REPLY_ARGS, "LIZ");
        String workResolver = latest ? "LJI" : "LJFF";
        int resolve = callIndex(item, "LX/13eY;", workResolver);
        assertCall(item.at(resolve), "LX/13eY;", workResolver, STRING, WORK_CONTEXT, STRING);
        FiveRegisterInstruction resolveCall = (FiveRegisterInstruction) item.at(resolve);
        assertEquals(((OneRegisterInstruction) item.at(context)).getRegisterA(), resolveCall.getRegisterC());
        assertEquals(0, ((NarrowLiteralInstruction) item.at(resolve - 1)).getNarrowLiteral());
        assertEquals(((OneRegisterInstruction) item.at(resolve - 1)).getRegisterA(), resolveCall.getRegisterD());
        Method nativeItem = method("LX/13eY;", workResolver, STRING, WORK_CONTEXT, STRING);
        assertTrue(calls(nativeItem, "LX/13eY;", "LIZJ"));
        assertTrue("The native resolver includes the captured Aweme aid, not a copied web signature",
                referencesField(nativeItem, WORK, "aid"));
    }

    @Test public void ownCommentRefreshAndLoadMoreAdvanceUsingTheReturnedMinTime() {
        Method callback = method("LX/14HZ;", "LIZ", "Ljava/lang/Object;", "I", STRING, "Ljava/lang/Object;");
        Code code = new Code(callback);
        int copies = 0;
        for (int index = 0; index + 1 < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (!(reference instanceof FieldReference)) continue;
            FieldReference read = (FieldReference) reference;
            if (!MINE.equals(read.getDefiningClass()) || !"minTime".equals(read.getName())) continue;
            assertEquals(Opcode.IGET_WIDE, code.at(index).getOpcode());
            assertEquals(Opcode.IPUT_WIDE, code.at(index + 1).getOpcode());
            assertFieldReference(code.at(index + 1), "LX/14Kj;", "LJIIIZ", "J");
            assertEquals(((OneRegisterInstruction) code.at(index)).getRegisterA(),
                    ((OneRegisterInstruction) code.at(index + 1)).getRegisterA());
            copies++;
        }
        assertEquals("Both refresh and load-more paths use the server's min_time cursor", 2, copies);
        assertFalse(referencesField(callback, MINE, "maxTime"));
    }

    @Test public void nativeReplyListWrapperBindsTheExactRootItemAndPaginationWireSlots() {
        Method endpoint = singleMethod(REPLY_API, "LJIJJLI");
        assertEquals("Lbolts/Task;", endpoint.getReturnType());
        assertEquals(46, endpoint.getParameters().size());
        assertEquals("/aweme/v1/comment/list/reply/",
                annotationValue(endpoint.getAnnotations(), "Lretrofit2/http/POST;"));
        int[] positions = {0, 1, 2, 4, 18, 44};
        String[] names = {"comment_id", "cursor", "count", "item_id", "comment_uid", "merge_cursor"};
        String[] types = {STRING, "J", "I", STRING, STRING, STRING};
        for (int index = 0; index < positions.length; index++) {
            assertEquals(names[index], query(endpoint.getParameters().get(positions[index]).getAnnotations()));
            assertEquals(types[index], endpoint.getParameters().get(positions[index]).getType());
        }
        assertTrue(annotationValue(endpoint.getAnnotations(), "Ldalvik/annotation/Signature;")
                .contains("Lbolts/Task<" + REPLY_LIST + ">;"));

        Method wrapper = method("LX/13wP;", "LJIJJ", "Lbolts/Task;", REPLY_ARGS);
        assertTrue(AccessFlags.PUBLIC.isSet(wrapper.getAccessFlags()));
        assertTrue(AccessFlags.STATIC.isSet(wrapper.getAccessFlags()));
        Code code = new Code(wrapper);
        int invoke = callIndex(code, REPLY_API, "LJIJJLI");
        MethodReference actual = (MethodReference) reference(code.at(invoke));
        assertEquals(endpoint.getParameters().stream().map(parameter -> parameter.getType())
                        .collect(java.util.stream.Collectors.toList()),
                actual.getParameterTypes().stream().map(CharSequence::toString)
                        .collect(java.util.stream.Collectors.toList()));
        int start = ((RegisterRangeInstruction) code.at(invoke)).getStartRegister();
        int root = fieldIndex(code, REPLY_ARGS, "LIZIZ");
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code.at(root + 1).getOpcode());
        TwoRegisterInstruction saveRoot = (TwoRegisterInstruction) code.at(root + 1);
        assertEquals(((OneRegisterInstruction) code.at(root)).getRegisterA(), saveRoot.getRegisterB());
        assertTrue(movesRegisterBefore(code, invoke, saveRoot.getRegisterA(), parameterRegister(endpoint, start, 0)));
        int cursor = fieldIndex(code, REPLY_ARGS, "LIZLLL");
        assertCall(code.at(cursor + 1), "Lkotlin/jvm/internal/Intrinsics;", "checkNotNull", "V", "Ljava/lang/Object;");
        assertCall(code.at(cursor + 2), "Ljava/lang/Long;", "longValue", "J");
        assertEquals(Opcode.MOVE_RESULT_WIDE, code.at(cursor + 3).getOpcode());
        assertEquals(parameterRegister(endpoint, start, 1), ((OneRegisterInstruction) code.at(cursor + 3)).getRegisterA());
        int count = fieldIndex(code, REPLY_ARGS, "LJFF");
        assertCall(code.at(count + 1), "Lkotlin/jvm/internal/Intrinsics;", "checkNotNull", "V", "Ljava/lang/Object;");
        assertCall(code.at(count + 2), INTEGER, "intValue", "I");
        assertEquals(parameterRegister(endpoint, start, 2), ((OneRegisterInstruction) code.at(count + 3)).getRegisterA());
        int item = callIndex(code, REPLY_ARGS, "LIZ");
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code.at(item + 1).getOpcode());
        assertEquals(parameterRegister(endpoint, start, 4), ((OneRegisterInstruction) code.at(item + 1)).getRegisterA());
        for (String[] wire : new String[][]{{"LJJIII", "18"}, {"LJ", "44"}}) {
            int read = fieldIndex(code, REPLY_ARGS, wire[0]);
            int source = ((OneRegisterInstruction) code.at(read)).getRegisterA();
            assertTrue(movesRegisterBefore(code, invoke, source,
                    parameterRegister(endpoint, start, Integer.parseInt(wire[1]))));
        }
    }

    @Test public void nativeReplyListResponsePreservesCommentsAndBothPaginationCursors() {
        assertEquals("LX/156M;", classes.get(REPLY_LIST).getSuperclass());
        assertEquals("Lcom/ss/android/ugc/aweme/base/api/BaseResponse;", classes.get("LX/156M;").getSuperclass());
        assertSerializedField(REPLY_LIST, "items", "Ljava/util/List;", "comments");
        assertEquals("Ljava/util/List<+" + COMMENT + ">;",
                annotationValue(field(REPLY_LIST, "items", "Ljava/util/List;").getAnnotations(),
                        "Ldalvik/annotation/Signature;"));
        assertSerializedField(REPLY_LIST, "cursor", "J", "cursor");
        assertSerializedField(REPLY_LIST, "hasMore", "Z", "has_more");
        assertSerializedField(REPLY_LIST, "mergeCursor", STRING, "merge_cursor");
    }

    @Test public void replyTaskTimedWaitUsesTheHostMethodAndReturnsCompletionUnderItsLock() {
        Method timedWait = method(TASK, "LJIJJ", "Z", "J", "Ljava/util/concurrent/TimeUnit;");
        assertTrue(AccessFlags.PUBLIC.isSet(timedWait.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(timedWait.getAccessFlags()));
        for (Method candidate : classes.get(TASK).getMethods()) {
            assertFalse("The supported host does not expose the standard timed-wait name",
                    "waitForCompletion".equals(candidate.getName())
                            && candidate.getParameterTypes().equals(Arrays.asList("J", "Ljava/util/concurrent/TimeUnit;")));
        }
        method(TASK, "waitForCompletion", "V"); // Only the unbounded English overload survives.
        Code code = new Code(timedWait);
        int lockRead = fieldIndex(code, TASK, "lock");
        assertEquals(Opcode.IGET_OBJECT, code.at(lockRead).getOpcode());
        int lock = ((OneRegisterInstruction) code.at(lockRead)).getRegisterA();
        assertEquals(Opcode.MONITOR_ENTER, code.at(lockRead + 1).getOpcode());
        assertEquals(lock, ((OneRegisterInstruction) code.at(lockRead + 1)).getRegisterA());
        int before = callIndex(code, TASK, "isCompleted");
        assertCall(code.at(before), TASK, "isCompleted", "Z");
        assertEquals(Opcode.IF_NEZ, code.at(before + 2).getOpcode());
        int after = code.target(before + 2);
        assertCall(code.at(after), TASK, "isCompleted", "Z");
        int conversion = callIndex(code, "Ljava/util/concurrent/TimeUnit;", "toMillis");
        assertCall(code.at(conversion), "Ljava/util/concurrent/TimeUnit;", "toMillis", "J", "J");
        assertEquals(Opcode.MOVE_RESULT_WIDE, code.at(conversion + 1).getOpcode());
        int wait = callIndex(code, "Ljava/lang/Object;", "wait");
        assertTrue(before < conversion && conversion < wait && wait < after);
        assertCall(code.at(wait), "Ljava/lang/Object;", "wait", "V", "J");
        assertEquals(((OneRegisterInstruction) code.at(conversion + 1)).getRegisterA(),
                ((FiveRegisterInstruction) code.at(wait)).getRegisterD());
        assertEquals(Opcode.IGET_OBJECT, code.at(conversion - 1).getOpcode());
        assertFieldReference(code.at(conversion - 1), TASK, "lock", "Ljava/lang/Object;");
        assertEquals(((OneRegisterInstruction) code.at(conversion - 1)).getRegisterA(),
                ((FiveRegisterInstruction) code.at(wait)).getRegisterC());
        assertEquals(Opcode.MOVE_RESULT, code.at(after + 1).getOpcode());
        assertEquals(Opcode.MONITOR_EXIT, code.at(after + 2).getOpcode());
        assertEquals(lock, ((OneRegisterInstruction) code.at(after + 2)).getRegisterA());
        assertEquals(Opcode.RETURN, code.at(after + 3).getOpcode());
        assertEquals(((OneRegisterInstruction) code.at(after + 1)).getRegisterA(),
                ((OneRegisterInstruction) code.at(after + 3)).getRegisterA());
        assertEquals("The native timed wait performs one wait, so false must not be treated as a result",
                1, code.instructions.stream().filter(instruction -> {
                    Object reference = reference(instruction);
                    return reference instanceof MethodReference
                            && "Ljava/lang/Object;".equals(((MethodReference) reference).getDefiningClass())
                            && "wait".equals(((MethodReference) reference).getName());
                }).count());
        assertTrue(timedWait.getImplementation().getTryBlocks().stream()
                .anyMatch(block -> block.getExceptionHandlers().stream()
                        .anyMatch(handler -> handler.getExceptionType() == null)));
    }

    @Test public void replyTaskErrorGetterObservesFailureAndStateReadersDoNotReplaceWaiting() {
        Method error = method(TASK, "LJIIL", "Ljava/lang/Exception;");
        assertTrue(AccessFlags.PUBLIC.isSet(error.getAccessFlags()));
        assertFalse(AccessFlags.STATIC.isSet(error.getAccessFlags()));
        for (Method candidate : classes.get(TASK).getMethods()) {
            assertNotEquals("The host's error getter is obfuscated", "getError", candidate.getName());
        }
        Code errorCode = new Code(error);
        int lockRead = fieldIndex(errorCode, TASK, "lock");
        assertEquals(Opcode.MONITOR_ENTER, errorCode.at(lockRead + 1).getOpcode());
        int lock = ((OneRegisterInstruction) errorCode.at(lockRead)).getRegisterA();
        assertEquals(lock, ((OneRegisterInstruction) errorCode.at(lockRead + 1)).getRegisterA());
        int readError = fieldIndex(errorCode, TASK, "error");
        assertEquals(Opcode.IGET_OBJECT, errorCode.at(readError).getOpcode());
        assertEquals(Opcode.IF_EQZ, errorCode.at(readError + 1).getOpcode());
        int observed = fieldIndex(errorCode, TASK, "errorHasBeenObserved");
        assertTrue(readError < observed);
        assertEquals(Opcode.IPUT_BOOLEAN, errorCode.at(observed).getOpcode());
        assertEquals(1, ((NarrowLiteralInstruction) errorCode.at(observed - 1)).getNarrowLiteral());
        assertTrue(calls(error, "LX/1SVO;", "LIZ"));
        int finalRead = errorCode.target(readError + 1);
        assertFieldReference(errorCode.at(finalRead), TASK, "error", "Ljava/lang/Exception;");
        assertEquals(Opcode.MONITOR_EXIT, errorCode.at(finalRead + 1).getOpcode());
        assertEquals(lock, ((OneRegisterInstruction) errorCode.at(finalRead + 1)).getRegisterA());
        assertEquals(Opcode.RETURN_OBJECT, errorCode.at(finalRead + 2).getOpcode());
        assertEquals(((OneRegisterInstruction) errorCode.at(finalRead)).getRegisterA(),
                ((OneRegisterInstruction) errorCode.at(finalRead + 2)).getRegisterA());

        Method faulted = method(TASK, "isFaulted", "Z");
        assertTrue(calls(faulted, TASK, "LJIIL"));
        assertFalse("Reading a result does not wait for completion", calls(method(TASK, "getResult", "Ljava/lang/Object;"),
                TASK, "LJIJJ"));
        assertLockedTaskFieldGetter("getResult", "Ljava/lang/Object;", "result", Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT);
        assertLockedTaskFieldGetter("isCancelled", "Z", "cancelled", Opcode.IGET_BOOLEAN, Opcode.RETURN);
        assertLockedTaskFieldGetter("isCompleted", "Z", "complete", Opcode.IGET_BOOLEAN, Opcode.RETURN);
    }

    private static void assertLockedTaskFieldGetter(String name, String returns, String fieldName,
                                                   Opcode readOpcode, Opcode returnOpcode) {
        Code code = new Code(method(TASK, name, returns));
        int lock = fieldIndex(code, TASK, "lock");
        assertEquals(Opcode.MONITOR_ENTER, code.at(lock + 1).getOpcode());
        int read = fieldIndex(code, TASK, fieldName);
        assertEquals(readOpcode, code.at(read).getOpcode());
        assertEquals(Opcode.MONITOR_EXIT, code.at(read + 1).getOpcode());
        assertEquals(returnOpcode, code.at(read + 2).getOpcode());
        assertEquals(((OneRegisterInstruction) code.at(read)).getRegisterA(),
                ((OneRegisterInstruction) code.at(read + 2)).getRegisterA());
        assertTrue(code.instructions.stream().noneMatch(instruction -> reference(instruction) instanceof MethodReference));
    }

    private static void readSelectedClasses(ZipFile archive, String dexName, Set<String> required) throws Exception {
        ZipEntry entry = archive.getEntry(dexName);
        assertNotNull("The supported host must contain " + dexName, entry);
        try (BufferedInputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
            for (ClassDef type : dex.getClasses()) {
                if (required.remove(names.type(type.getType()))) classes.put(names.type(type.getType()), names.originalFacts(type));
            }
        }
    }

    private static boolean returnsFirstArgumentGetter(Method method, String getter) {
        Code code = new Code(method);
        int firstArgument = method.getImplementation().getRegisterCount() - 2;
        for (int index = 0; index + 2 < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (!(reference instanceof MethodReference)) continue;
            MethodReference call = (MethodReference) reference;
            if (!COMMENT.equals(call.getDefiningClass()) || !getter.equals(call.getName())
                    || !(code.at(index) instanceof FiveRegisterInstruction)
                    || ((FiveRegisterInstruction) code.at(index)).getRegisterC() != firstArgument
                    || code.at(index + 1).getOpcode() != Opcode.MOVE_RESULT_OBJECT
                    || code.at(index + 2).getOpcode() != Opcode.RETURN_OBJECT) continue;
            if (((OneRegisterInstruction) code.at(index + 1)).getRegisterA()
                    == ((OneRegisterInstruction) code.at(index + 2)).getRegisterA()) return true;
        }
        return false;
    }

    private static Method method(String owner, String name, String returns, String... parameters) {
        ClassDef type = classes.get(owner);
        assertNotNull(owner, type);
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName()) && returns.equals(method.getReturnType())
                    && method.getParameterTypes().equals(Arrays.asList(parameters))) return method;
        }
        throw new AssertionError("Missing " + owner + "->" + name + Arrays.toString(parameters) + returns);
    }

    private static Method singleMethod(String owner, String name) {
        Method found = null;
        assertNotNull(owner, classes.get(owner));
        for (Method candidate : classes.get(owner).getMethods()) {
            if (!name.equals(candidate.getName())) continue;
            assertNull("Ambiguous native method " + owner + "->" + name, found);
            found = candidate;
        }
        assertNotNull("Missing native method " + owner + "->" + name, found);
        return found;
    }

    private static int parameterRegister(Method method, int invocationStart, int parameter) {
        int register = invocationStart + 1;
        for (int index = 0; index < parameter; index++) {
            String type = method.getParameterTypes().get(index).toString();
            register += "J".equals(type) || "D".equals(type) ? 2 : 1;
        }
        return register;
    }

    private static String annotationValue(Set<? extends Annotation> annotations, String type) {
        for (Annotation annotation : annotations) {
            if (!type.equals(annotation.getType())) continue;
            for (AnnotationElement element : annotation.getElements()) {
                if (!"value".equals(element.getName())) continue;
                if (element.getValue() instanceof StringEncodedValue) {
                    return ((StringEncodedValue) element.getValue()).getValue();
                }
                if (element.getValue() instanceof ArrayEncodedValue) {
                    StringBuilder value = new StringBuilder();
                    for (var part : ((ArrayEncodedValue) element.getValue()).getValue()) {
                        assertTrue("Native signature components must be strings", part instanceof StringEncodedValue);
                        value.append(((StringEncodedValue) part).getValue());
                    }
                    return value.toString();
                }
            }
        }
        throw new AssertionError("Missing native annotation value " + type);
    }

    private static Field field(String owner, String name, String type) {
        for (Field field : classes.get(owner).getFields()) {
            if (name.equals(field.getName()) && type.equals(field.getType())) return field;
        }
        throw new AssertionError("Missing " + owner + "->" + name + ":" + type);
    }

    private static void assertSerializedField(String owner, String name, String type, String serializedName) {
        Field field = field(owner, name, type);
        for (Annotation annotation : field.getAnnotations()) {
            if (!"Lcom/google/gson/annotations/SerializedName;".equals(annotation.getType())) continue;
            for (AnnotationElement element : annotation.getElements()) {
                if ("value".equals(element.getName()) && element.getValue() instanceof StringEncodedValue) {
                    assertEquals(owner + "->" + name + " serialized name", serializedName,
                            ((StringEncodedValue) element.getValue()).getValue());
                    return;
                }
            }
        }
        throw new AssertionError("Missing serialized name for " + owner + "->" + name);
    }

    private static void assertDirectGetter(String owner, String name, String returns, String fieldName, Opcode readOpcode) {
        Code code = new Code(method(owner, name, returns));
        assertEquals("Native getters must not require a separate JSON parse", 2, code.instructions.size());
        assertEquals(readOpcode, code.at(0).getOpcode());
        assertFieldReference(code.at(0), owner, fieldName, returns);
        assertEquals(readOpcode == Opcode.IGET_OBJECT ? Opcode.RETURN_OBJECT : Opcode.RETURN, code.at(1).getOpcode());
        assertEquals(((OneRegisterInstruction) code.at(0)).getRegisterA(),
                ((OneRegisterInstruction) code.at(1)).getRegisterA());
    }

    private static void assertFieldReference(Instruction instruction, String owner, String name, String type) {
        Object reference = reference(instruction);
        assertTrue("Expected a field reference", reference instanceof FieldReference);
        FieldReference field = (FieldReference) reference;
        assertEquals(owner, field.getDefiningClass());
        assertEquals(name, field.getName());
        assertEquals(type, field.getType());
    }

    private static String query(Set<? extends Annotation> annotations) {
        for (Annotation annotation : annotations) {
            if (!"Lretrofit2/http/Query;".equals(annotation.getType())) continue;
            for (AnnotationElement element : annotation.getElements()) {
                if ("value".equals(element.getName()) && element.getValue() instanceof StringEncodedValue) {
                    return ((StringEncodedValue) element.getValue()).getValue();
                }
            }
        }
        throw new AssertionError("Missing native @Query value");
    }

    private static void assertCall(Instruction instruction, String owner, String name, String returns, String... parameters) {
        Object reference = reference(instruction);
        assertTrue("Expected a method reference", reference instanceof MethodReference);
        MethodReference method = (MethodReference) reference;
        assertEquals(owner, method.getDefiningClass());
        assertEquals(name, method.getName());
        assertEquals(returns, method.getReturnType());
        assertEquals(Arrays.asList(parameters), method.getParameterTypes());
    }

    private static boolean calls(Method method, String owner, String name) {
        return callIndexOrMissing(new Code(method), owner, name) >= 0;
    }

    private static int callIndex(Code code, String owner, String name) {
        int result = callIndexOrMissing(code, owner, name);
        assertTrue("Missing call " + owner + "->" + name, result >= 0);
        return result;
    }

    private static int callIndexOrMissing(Code code, String owner, String name) {
        for (int index = 0; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof MethodReference && owner.equals(((MethodReference) reference).getDefiningClass())
                    && name.equals(((MethodReference) reference).getName())) return index;
        }
        return -1;
    }

    private static boolean referencesField(Method method, String owner, String name) {
        return fieldIndexOrMissing(new Code(method), owner, name) >= 0;
    }

    private static int fieldIndex(Code code, String owner, String name) {
        int result = fieldIndexOrMissing(code, owner, name);
        assertTrue("Missing field reference " + owner + "->" + name, result >= 0);
        return result;
    }

    private static int fieldIndexOrMissing(Code code, String owner, String name) {
        for (int index = 0; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof FieldReference && owner.equals(((FieldReference) reference).getDefiningClass())
                    && name.equals(((FieldReference) reference).getName())) return index;
        }
        return -1;
    }

    private static int stringIndex(Code code, String value) {
        for (int index = 0; index < code.instructions.size(); index++) {
            Object reference = reference(code.at(index));
            if (reference instanceof StringReference && value.equals(((StringReference) reference).getString())) return index;
        }
        throw new AssertionError("Missing string " + value);
    }

    private static int stringIndexFollowedByCall(Code code, String value, String owner, String name) {
        for (int index = 0; index + 1 < code.instructions.size(); index++) {
            Object text = reference(code.at(index));
            Object call = reference(code.at(index + 1));
            if (text instanceof StringReference && value.equals(((StringReference) text).getString())
                    && call instanceof MethodReference && owner.equals(((MethodReference) call).getDefiningClass())
                    && name.equals(((MethodReference) call).getName())) return index;
        }
        throw new AssertionError("Missing mapping lookup for " + value);
    }

    private static boolean movesRegisterBefore(Code code, int before, int source, int target) {
        for (int index = before - 1; index >= 0; index--) {
            Instruction instruction = code.at(index);
            if (instruction instanceof TwoRegisterInstruction && instruction.getOpcode().name().startsWith("MOVE")) {
                TwoRegisterInstruction move = (TwoRegisterInstruction) instruction;
                if (move.getRegisterA() == target) return move.getRegisterB() == source;
            }
        }
        return false;
    }

    private static Object reference(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    private static final class Code {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final Map<Integer, Integer> indexes = new HashMap<>();
        Code(Method method) {
            assertNotNull("Missing method body " + method.getDefiningClass() + "->" + method.getName(), method.getImplementation());
            int offset = 0;
            for (Instruction instruction : method.getImplementation().getInstructions()) {
                indexes.put(offset, instructions.size());
                offsets.add(offset);
                instructions.add(instruction);
                offset += instruction.getCodeUnits();
            }
        }
        Instruction at(int index) { return instructions.get(index); }
        int target(int index) {
            assertTrue(at(index) instanceof OffsetInstruction);
            Integer target = indexes.get(offsets.get(index) + ((OffsetInstruction) at(index)).getCodeOffset());
            assertNotNull("Branch must target an instruction", target);
            return target;
        }
    }
}
