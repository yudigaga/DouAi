package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
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
    static final class Profile { @SerializedName("avatar_thumb") final Avatar avatar = new Avatar(); }
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
}
