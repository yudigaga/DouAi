package com.douai.module.config;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelAutomationSelectionTest {
    @Test public void replyAndCommentSelectDifferentExistingProfilesWithoutChangingTheGlobalProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        String saved = store.getString(AiModelSettings.KEY, "");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        store.writes = 0;

        AiModelConfig reply = AiModelSettings.readForAutomation(store, "reply").selected();
        AiModelConfig comment = AiModelSettings.readForAutomation(store, "comment").selected();
        assertEquals("reply-provider", reply.id);
        assertEquals("comment-provider", comment.id);
        assertEquals("https://reply-provider.example.test/v1", reply.apiEndpoint);
        assertEquals("https://comment-provider.example.test/v1", comment.apiEndpoint);
        assertEquals("key-reply-provider", reply.apiKey);
        assertEquals("key-comment-provider", comment.apiKey);
        assertEquals("model-reply-provider", reply.modelName);
        assertEquals("model-comment-provider", comment.modelName);
        assertEquals("prompt-reply-provider", reply.systemPrompt);
        assertEquals("prompt-comment-provider", comment.systemPrompt);
        assertTrue(reply.streaming());
        assertFalse(comment.streaming());
        assertEquals("global", AiModelSettings.read(store).activeId);
        assertEquals(saved, store.getString(AiModelSettings.KEY, ""));
        assertEquals("Reading an automation selection must not rewrite model settings", 0, store.writes);
    }

    @Test public void omittedOrClearedBindingsKeepFollowingTheGlobalProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            assertEquals("global", AiModelSettings.readForAutomation(store, kind).selected().id);
            store.putString(AiModelSettings.automationModelKey(kind), "");
            assertEquals("global", AiModelSettings.readForAutomation(new LocalOptions(store.values), kind).selected().id);
        }
        AiModelSettings.read(store).activate("reply-provider").save(store);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
    }

    @Test public void commentRepliesUseTheReplyBindingAndBothAutomationsCanSelectTheSameProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        LocalOptions options = new LocalOptions(store.values);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(options, "comment_reply").selected().id);
        assertEquals(AiModelSettings.REPLY_MODEL_ID, AiModelSettings.automationModelKey("comment_reply"));
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "reply-provider");
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
    }

    @Test public void boundProfilesWorkWhenThereIsNoGlobalActiveProfile() throws Exception {
        MemoryStore store = configuredStore("");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        assertEquals("reply-provider", AiModelSettings.readForAutomation(store, "reply").selected().id);
        assertEquals("comment-provider", AiModelSettings.readForAutomation(store, "comment").selected().id);
        assertEquals("", AiModelSettings.read(store).activeId);
    }

    @Test public void missingBoundProfilesNeverSilentlyUseAnotherProvider() throws Exception {
        MemoryStore store = configuredStore("global");
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            store.putString(AiModelSettings.automationModelKey(kind), "deleted-profile");
            IllegalStateException storeFailure = assertThrows(IllegalStateException.class,
                    () -> AiModelSettings.readForAutomation(store, kind));
            IllegalStateException snapshotFailure = assertThrows(IllegalStateException.class,
                    () -> AiModelSettings.readForAutomation(new LocalOptions(store.values), kind));
            assertEquals(storeFailure.getMessage(), snapshotFailure.getMessage());
            assertTrue(storeFailure.getMessage().contains("请重新选择"));
            assertFalse(storeFailure.getMessage().contains("deleted-profile"));
        }
        assertEquals("global", AiModelSettings.read(store).selected().id);
        assertThrows(IllegalArgumentException.class, () -> AiModelSettings.automationModelKey("manual"));
    }

    @Test public void independentBindingsIgnoreGlobalSwitchesAndUnrelatedProfileEdits() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        LocalOptions before = new LocalOptions(store.values);
        AiModelSettings profiles = AiModelSettings.read(before);
        LocalOptions switched = withProfiles(store.values, profiles.activate("reply-provider"));
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            assertTrue(kind, AiModelSettings.sameAutomationSelection(before, switched, kind));
        }
        AiModelConfig unrelated = AiModelConfig.fromJson(profile("global", false).toJson()
                .put("apiKey", "changed-unrelated-key").put("systemPrompt", "changed unrelated prompt"));
        LocalOptions edited = withProfiles(store.values, profiles.upsert(unrelated, false));
        LocalOptions added = withProfiles(store.values, profiles.upsert(profile("new-provider", true), false));
        LocalOptions removed = withProfiles(store.values, profiles.activate("reply-provider").delete("global"));
        for (LocalOptions next : new LocalOptions[]{edited, added, removed}) {
            for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
                assertTrue(kind, AiModelSettings.sameAutomationSelection(before, next, kind));
            }
        }
    }

    @Test public void globalFollowersAndBindingPolicyChangesInvalidateTheSelection() throws Exception {
        MemoryStore store = configuredStore("global");
        LocalOptions before = new LocalOptions(store.values);
        LocalOptions switched = withProfiles(store.values, AiModelSettings.read(before).activate("reply-provider"));
        for (String kind : new String[]{"reply", "comment", "comment_reply"}) {
            assertFalse(kind, AiModelSettings.sameAutomationSelection(before, switched, kind));
        }
        store.putString(AiModelSettings.REPLY_MODEL_ID, "global");
        LocalOptions pinnedToTheCurrentGlobal = new LocalOptions(store.values);
        assertFalse(AiModelSettings.sameAutomationSelection(before, pinnedToTheCurrentGlobal, "reply"));
        assertFalse(AiModelSettings.sameAutomationSelection(before, pinnedToTheCurrentGlobal, "comment_reply"));
        assertTrue(AiModelSettings.sameAutomationSelection(before, pinnedToTheCurrentGlobal, "comment"));
        store.putString(AiModelSettings.REPLY_MODEL_ID, "reply-provider");
        assertFalse(AiModelSettings.sameAutomationSelection(pinnedToTheCurrentGlobal,
                new LocalOptions(store.values), "reply"));
    }

    @Test public void changesToAnySelectedRequestSettingInvalidateTheBoundProfile() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        LocalOptions before = new LocalOptions(store.values);
        AiModelSettings profiles = AiModelSettings.read(before);
        AiModelConfig selected = AiModelSettings.readForAutomation(before, "comment").selected();
        Object[][] changes = {
                {"apiEndpoint", "https://changed.example.test/v1"}, {"apiKey", "changed-key"},
                {"protocol", AiModelConfig.RESPONSES}, {"responseMode", AiModelConfig.RESPONSE_STREAM},
                {"modelName", "changed-model"}, {"systemPrompt", "changed primary prompt"},
                {"provider", "changed-provider"}, {"contextRounds", 4}, {"timeoutSeconds", 45},
                {"reasoningEffort", "low"}, {"temperatureEnabled", true}, {"temperature", 0.3},
                {"topPEnabled", true}, {"topP", 0.8}, {"maxTokensEnabled", true}, {"maxTokens", 128},
                {"customHeaders", "{\"X-Fixture\":\"changed\"}"}, {"customParameters", "{\"seed\":1}"}
        };
        for (Object[] change : changes) {
            AiModelConfig edited = AiModelConfig.fromJson(selected.toJson().put((String) change[0], change[1]));
            LocalOptions next = withProfiles(store.values, profiles.upsert(edited, false));
            assertFalse((String) change[0], AiModelSettings.sameAutomationSelection(before, next, "comment"));
            assertTrue((String) change[0], AiModelSettings.sameAutomationSelection(before, next, "reply"));
        }
    }

    @Test public void deletedAndInvalidSelectionsConservativelyCompareTheRawDocumentAndBinding() throws Exception {
        MemoryStore store = configuredStore("global");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "comment-provider");
        LocalOptions before = new LocalOptions(store.values);
        LocalOptions deleted = withProfiles(store.values, AiModelSettings.read(before).delete("comment-provider"));
        assertFalse(AiModelSettings.sameAutomationSelection(before, deleted, "comment"));
        assertThrows(IllegalStateException.class, () -> AiModelSettings.readForAutomation(deleted, "comment"));
        assertTrue(AiModelSettings.sameAutomationSelection(deleted, deleted, "comment"));
        LocalOptions changedWhileMissing = withProfiles(store.values,
                AiModelSettings.read(deleted).activate("reply-provider"));
        assertFalse(AiModelSettings.sameAutomationSelection(deleted, changedWhileMissing, "comment"));

        LocalOptions invalid = new LocalOptions(Map.of(AiModelSettings.KEY, "{invalid", AiModelSettings.COMMENT_MODEL_ID, "selected"));
        LocalOptions identical = new LocalOptions(Map.of(AiModelSettings.KEY, "{invalid", AiModelSettings.COMMENT_MODEL_ID, "selected"));
        LocalOptions changedDocument = new LocalOptions(Map.of(AiModelSettings.KEY, "{different", AiModelSettings.COMMENT_MODEL_ID, "selected"));
        LocalOptions changedBinding = new LocalOptions(Map.of(AiModelSettings.KEY, "{invalid", AiModelSettings.COMMENT_MODEL_ID, "other"));
        assertTrue(AiModelSettings.sameAutomationSelection(invalid, identical, "comment"));
        assertFalse(AiModelSettings.sameAutomationSelection(invalid, changedDocument, "comment"));
        assertFalse(AiModelSettings.sameAutomationSelection(invalid, changedBinding, "comment"));
        assertThrows(IllegalArgumentException.class, () -> AiModelSettings.sameAutomationSelection(before, before, "manual"));
    }

    @Test public void unselectedProfilesAskForSelectionInsteadOfASeparateEnabledFlag() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AiModelSettings(List.of(), "").selected());
        assertEquals("请先选择模型配置", failure.getMessage());
    }

    private static LocalOptions withProfiles(Map<String, Object> source, AiModelSettings settings) {
        Map<String, Object> next = new LinkedHashMap<>(source);
        next.put(AiModelSettings.KEY, settings.serialize());
        return new LocalOptions(next);
    }

    private static MemoryStore configuredStore(String activeId) throws Exception {
        MemoryStore store = new MemoryStore();
        AiModelConfig global = profile("global", false);
        AiModelConfig reply = profile("reply-provider", true);
        AiModelConfig comment = profile("comment-provider", false);
        new AiModelSettings(List.of(global, reply, comment), activeId).save(store);
        return store;
    }

    private static AiModelConfig profile(String id, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "配置 " + id)
                .put("provider", id).put("apiEndpoint", "https://" + id + ".example.test/v1")
                .put("apiKey", "key-" + id).put("modelName", "model-" + id)
                .put("systemPrompt", "prompt-" + id).put("responseMode",
                        streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        int writes;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public void putString(String key, String value) { values.put(key, value); writes++; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); writes++; }
    }
}
