package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProfileListEntryTest {
    @Test public void serializedNativeAvatarAndImDisplayAvatarBothResolve() {
        assertEquals("https://p3.douyinpic.com/avatar.jpg", ProfileListEntry.avatar(new Profile()));
        assertEquals("https://p3.douyinpic.com/avatar.jpg", ProfileListEntry.avatar(new ImProfile()));
    }
    @Test public void missingAndUnsupportedAvatarUrlsRetainIdentityAndUsePlaceholder() {
        ProfileListEntry profile = new ProfileListEntry("100", "Alice", "https://example.invalid/p.png");
        assertEquals("100", profile.uid);
        assertEquals("Alice", profile.name);
        assertEquals("", profile.avatarUrl);
        assertEquals("", ProfileListEntry.avatar(null));
    }
    @Test public void anHttpAvatarCandidateCannotHideALaterApprovedHttpsCandidate() {
        ProfileListEntry profile = ProfileListEntry.fromUser("100", new AvatarCandidatesProfile(
                "http://p3.douyinpic.com/old.jpg", "https://p3.douyinpic.com/usable.jpg"), null);
        assertEquals("https://p3.douyinpic.com/usable.jpg", profile.avatarUrl);
    }
    @Test public void anUnapprovedAvatarCandidateCannotHideALaterApprovedCandidateOrLowerTheAllowlist() {
        ProfileListEntry profile = ProfileListEntry.fromUser("100", new AvatarCandidatesProfile(
                "https://unapproved.invalid/private.jpg", "https://p3.douyinpic.com/usable.jpg"), null);
        assertEquals("https://p3.douyinpic.com/usable.jpg", profile.avatarUrl);
        assertEquals("", ProfileListEntry.fromUser("100", new AvatarCandidatesProfile(
                "http://p3.douyinpic.com/old.jpg", "https://unapproved.invalid/private.jpg"), null).avatarUrl);
    }
    @Test public void anEmptyRawUrlListStillUsesItsExistingUrlGetterFallback() {
        assertEquals("https://p3.douyinpic.com/getter-fallback.jpg",
                ProfileListEntry.fromUser("100", new AvatarGetterFallbackProfile(), null).avatarUrl);
    }
    @Test public void displayIdsAndConversationTitlesDoNotChangeSelectionKeys() {
        ProfileListEntry user = new ProfileListEntry("100", "Alice", "", " alice.id ");
        assertEquals("alice.id", user.douyinId);
        assertEquals("100", user.selectionKey);
        assertFalse(user.conversation);
        ProfileListEntry group = ProfileListEntry.conversation("group-100", "Group", "");
        assertEquals("group-100", group.selectionKey);
        assertEquals("", group.douyinId);
        assertTrue(group.conversation);
        assertEquals("Alice", ProfileListEntry.named("Alice").selectionKey);
    }
    @Test public void sameUserFallbackFillsOnlyMissingIdentityFields() {
        ProfileListEntry partial = new ProfileListEntry("100", "Alice", "", "");
        ProfileListEntry fallback = new ProfileListEntry("100", "旧昵称", "https://p3.douyinpic.com/avatar.jpg", "alice.id");
        ProfileListEntry merged = partial.withFallback(fallback);
        assertEquals("Alice", merged.name);
        assertEquals("alice.id", merged.douyinId);
        assertEquals("https://p3.douyinpic.com/avatar.jpg", merged.avatarUrl);
        assertEquals("100", merged.selectionKey);
        assertSame(partial, partial.withFallback(new ProfileListEntry("999", "Other", "", "other.id")));
    }
    @Test public void zeroShortIdIsMissingRatherThanAPublicDouyinId() {
        assertEquals("", ProfileListEntry.fromUser("100", new IdProfile("", "0"), null).douyinId);
    }
    @Test public void zeroUniqueIdFallsBackToTheValidShortId() {
        assertEquals("12345", ProfileListEntry.fromUser("100", new IdProfile("0", "12345"), null).douyinId);
    }
    @Test public void emptyRawAvatarUsesTheExistingImDisplayAvatarGetter() {
        assertEquals("https://p3.douyinpic.com/avatar.jpg", ProfileListEntry.avatar(new EmptyAvatarImProfile()));
    }
    @Test public void missingRawNicknameNeverUsesTheOverriddenDisplayName() {
        for (String raw : new String[]{null, "", "  "}) {
            EmptyNicknameProfile user = new EmptyNicknameProfile(raw);
            assertEquals("", ProfileListEntry.fromUser("100", user, null).name);
            assertEquals("关系中的真实昵称", ProfileListEntry.fromUser("100", user,
                    new ProfileListEntry("100", "关系中的真实昵称", "")).name);
        }
    }
    @Test public void displayUpdatesKeepTheOriginalSelectionAndReplyRuleName() {
        ProfileListEntry original = new ProfileListEntry("100", "旧昵称", "", "").withSenderName("回复规则名");
        ProfileListEntry updated = original.withDisplayProfile(new ProfileListEntry("100", "真实新昵称",
                "https://p3.douyinpic.com/new.jpg", "new.id").withSenderName("不能覆盖规则名"));
        assertEquals("真实新昵称", updated.name);
        assertEquals("new.id", updated.douyinId);
        assertEquals("100", updated.selectionKey);
        assertEquals("回复规则名", updated.senderName);
        assertSame(original, original.withDisplayProfile(new ProfileListEntry("999", "其他用户", "", "")));
    }
    @Test public void rawRemarkIsKeptSeparatelyFromTheRealNicknameAndReplyRuleName() {
        ProfileListEntry profile = ProfileListEntry.fromUser("100", new RemarkProfile("真实昵称", "  我的备注  "), null)
                .withSenderName("原回复规则名");
        assertEquals("真实昵称", profile.name);
        assertEquals("我的备注", remark(profile));
        assertEquals("原回复规则名", profile.senderName);
        assertEquals("100", profile.selectionKey);
    }
    @Test public void emptyRawRemarkDoesNotBecomeAComputedDisplayName() {
        for (String raw : new String[]{null, "", "  "}) {
            ProfileListEntry profile = ProfileListEntry.fromUser("100", new RemarkProfile("真实昵称", raw), null);
            assertEquals("", remark(profile));
            assertEquals("真实昵称", profile.name);
        }
    }
    @Test public void plainAndGetterOnlyRemarkSourcesDecodeWithoutChangingTheNickname() {
        ProfileListEntry field = ProfileListEntry.fromUser("100", new PlainRemarkProfile(), null);
        assertEquals("字段昵称", field.name);
        assertEquals("字段备注", remark(field));
        ProfileListEntry getter = ProfileListEntry.fromUser("100", new GetterRemarkProfile(), null);
        assertEquals("getter昵称", getter.name);
        assertEquals("getter备注", remark(getter));
    }
    @Test public void sameUserFallbackAndHydrationRetainKnownRemarksAcrossPartialProfiles() {
        ProfileListEntry known = ProfileListEntry.fromUser("100", new RemarkProfile("旧昵称", "已知备注"), null)
                .withSenderName("既有规则名");
        ProfileListEntry partial = new ProfileListEntry("100", "新昵称", "", "new.id");
        assertEquals("已知备注", remark(partial.withFallback(known)));
        ProfileListEntry updated = known.withDisplayProfile(partial);
        assertEquals("新昵称", updated.name);
        assertEquals("已知备注", remark(updated));
        assertEquals("既有规则名", updated.senderName);
        ProfileListEntry fresh = ProfileListEntry.fromUser("100", new RemarkProfile("最新昵称", "新备注"), null);
        updated = updated.withDisplayProfile(fresh);
        assertEquals("新备注", remark(updated));
        assertEquals("最新昵称", updated.name);
        assertEquals("100", updated.selectionKey);
        assertEquals("既有规则名", updated.senderName);
    }
    @Test public void userRemarksDoNotLeakIntoOtherUidsOrConversationEntries() {
        ProfileListEntry known = ProfileListEntry.fromUser("100", new RemarkProfile("真实昵称", "个人备注"), null);
        ProfileListEntry another = new ProfileListEntry("999", "其他用户", "");
        assertEquals("", remark(another.withFallback(known)));
        assertEquals("", remark(another.withDisplayProfile(known)));
        assertEquals("", remark(ProfileListEntry.conversation("100", "群聊", "").withDisplayProfile(known)));
        assertEquals("", remark(ProfileListEntry.named("昵称规则")));
    }
    static String remark(ProfileListEntry profile) {
        return profile.remarkName;
    }
    static final class Profile { @SerializedName("avatar_thumb") final Avatar avatar = new Avatar(); }
    static final class AvatarCandidatesProfile {
        @SerializedName("avatar_thumb") final CandidateAvatar avatar;
        AvatarCandidatesProfile(String... urls) { avatar = new CandidateAvatar(urls); }
    }
    static final class CandidateAvatar {
        @SerializedName("url_list") final List<String> urls;
        CandidateAvatar(String... urls) { this.urls = Arrays.asList(urls); }
    }
    public static final class AvatarGetterFallbackProfile {
        @SerializedName("avatar_thumb") final EmptyRawUrlAvatar avatar = new EmptyRawUrlAvatar();
    }
    public static final class EmptyRawUrlAvatar {
        @SerializedName("url_list") final List<String> raw = Collections.emptyList();
        public List<String> getUrlList() { return Collections.singletonList("https://p3.douyinpic.com/getter-fallback.jpg"); }
    }
    static final class Avatar { @SerializedName("url_list") final List<String> urls = Collections.singletonList("https://p3.douyinpic.com/avatar.jpg"); }
    public static final class ImProfile { public GetterAvatar getDisplayAvatar() { return new GetterAvatar(); } }
    public static final class GetterAvatar { public List<String> getUrlList() { return Collections.singletonList("https://p3.douyinpic.com/avatar.jpg"); } }
    static final class IdProfile {
        final String uniqueId, shortId;
        IdProfile(String uniqueId, String shortId) { this.uniqueId = uniqueId; this.shortId = shortId; }
    }
    public static final class EmptyAvatarImProfile {
        @SerializedName("avatar_thumb") final List<String> avatar = Collections.emptyList();
        public GetterAvatar getDisplayAvatar() { return new GetterAvatar(); }
    }
    public static final class EmptyNicknameProfile {
        @SerializedName("nickname") final String raw;
        EmptyNicknameProfile(String raw) { this.raw = raw; }
        public String getNickname() { return "被覆盖的备注名"; }
    }
    public static final class RemarkProfile {
        @SerializedName("nickname") final String rawName;
        @SerializedName("remark_name") final String rawRemark;
        RemarkProfile(String name, String remark) { rawName = name; rawRemark = remark; }
        public String getNickname() { return "计算后的显示名"; }
        public String getRemarkName() { return "不能冒充空备注的计算昵称"; }
    }
    public static final class PlainRemarkProfile {
        final String nickName = "字段昵称";
        final String remarkName = "字段备注";
    }
    public static final class GetterRemarkProfile {
        public String getNickname() { return "getter昵称"; }
        public String getRemarkName() { return "getter备注"; }
    }
}
