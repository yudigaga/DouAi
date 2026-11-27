package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;
import com.douai.module.ai.skills.SkillImageModelCatalog;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.ui.miuix.MiuixFormControls;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AiSkillImageProviderTest {
    private static final String NAME = "生图";
    private static final String BASE_ENDPOINT = "https://images.example.test/v1";
    private static final String ENDPOINT = "https://images.example.test/v1/images/generations";
    private static final String MODELS_ENDPOINT = "https://images.example.test/v1/models";
    private static final String MODEL = "test-image-model";
    private static final String KEY = "fixture-image-token";
    private static final String ORDINARY_KEY = "ordinary-model-key-not-for-image";

    @Test
    @Config(shadows = {FakeChatModelCatalog.class}, instrumentedPackages = {"com.douai.module.feature"})
    public void theSameHttpsBaseCanListModelsFromChatAndTheScopedImageForm() throws Exception {
        // Abort before pressing either fetch button if this runtime did not install the network shadow.
        assertTrue(Shadow.extract(new ChatCompletionClient.ModelCatalogRequest()) instanceof FakeChatModelCatalog);
        FakeChatModelCatalog.modelRequest = null;
        ModelExecutor chatWorker = new ModelExecutor();
        ModelExecutor imageWorker = new ModelExecutor();
        AtomicReference<String> imageGeneration = new AtomicReference<>();
        AtomicReference<String> imageLookup = new AtomicReference<>();
        AiCredentialVault vault = new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES"));
        try (Fixture fixture = new Fixture(vault, (name, draftId, generation, models, bearer) -> {
            assertEquals(KEY, bearer);
            imageGeneration.set(generation);
            imageLookup.set(SkillImageModelCatalog.resolveEndpoint(generation, models));
            return List.of(MODEL);
        }, imageWorker);
             AiModelEditor chatEditor = new AiModelEditor(fixture.activity, new HostSettingsStore(fixture.activity),
                     AiModelConfig.fromJson(new JSONObject().put("id", "catalog-comparison")
                             .put("name", "聊天目录比较").put("apiEndpoint", "").put("modelName", "")),
                     false, () -> { }, chatWorker)) {
            chatEditor.show((dialog, dismissed) -> {
                dialog.setOwnerActivity(fixture.activity);
                dialog.setOnDismissListener(ignored -> dismissed.run());
                dialog.show();
            });
            AlertDialog chat = chatEditor.dialog();
            EditText chatEndpoint = findInput(chat.getWindow().getDecorView(), "API 基础地址");
            EditText chatKey = findInput(chat.getWindow().getDecorView(), "API 密钥");
            assertNotNull(chatEndpoint);
            assertNotNull(chatKey);
            chatEndpoint.setText(BASE_ENDPOINT);
            chatKey.setText(KEY);
            click(chat, "ai.fetch_models");
            chatWorker.runNext();
            ShadowLooper.idleMainLooper();
            assertNotNull(FakeChatModelCatalog.modelRequest);
            assertEquals(KEY, FakeChatModelCatalog.modelRequest.apiKey);
            assertEquals(MODELS_ENDPOINT, ChatCompletionClient.modelsEndpoint(FakeChatModelCatalog.modelRequest));
            assertEquals("已获取 1 个模型", ((TextView) view(chat, "ai.status")).getText().toString());
            AlertDialog chatChoices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(MODEL, chatChoices.getListView().getItemAtPosition(0));
            assertTrue(chatChoices.getListView().performItemClick(null, 0, 0));
            ShadowLooper.idleMainLooper();
            assertTrue(chat.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            ShadowLooper.idleMainLooper();
            AiModelConfig chatProfile = AiModelSettings.read(new HostSettingsStore(fixture.activity)).configs.get(0);
            assertEquals(BASE_ENDPOINT, chatProfile.apiEndpoint);
            assertEquals(KEY, chatProfile.apiKey);

            fixture.save(BASE_ENDPOINT, "", "");
            SkillStore.Detail unconfigured = fixture.store.detail(NAME);
            assertEquals(ENDPOINT, unconfigured.service().endpoint());
            assertFalse(fixture.services.hasCredential(unconfigured.service()));
            assertFalse(unconfigured.canAuthorize());

            fixture.save(BASE_ENDPOINT, "", KEY);
            SkillStore.Detail saved = fixture.store.detail(NAME);
            assertEquals("配置已保存，请选择或填写模型名称", fixture.status());
            assertEquals(ENDPOINT, saved.service().endpoint());
            assertTrue(fixture.services.hasCredential(saved.service()));
            assertFalse(fixture.services.isAuthorized(saved.service()));
            EditText savedKey = (EditText) view(fixture.dialog, "skills.detail.key");
            assertEquals(0, savedKey.length());
            assertTrue(savedKey.getHint().toString().contains("已保存"));
            assertEquals(BASE_ENDPOINT, ((EditText) view(fixture.dialog, "skills.detail.endpoint")).getText().toString());
            fixture.fill(BASE_ENDPOINT, "", "", KEY);
            click(fixture.dialog, "skills.detail.fetch_models");
            imageWorker.runNext();
            ShadowLooper.idleMainLooper();

            assertEquals("生图配置页反馈：" + fixture.modelStatus(), MODELS_ENDPOINT, imageLookup.get());
            assertEquals(ENDPOINT, imageGeneration.get());
            AlertDialog imageChoices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(MODEL, imageChoices.getListView().getItemAtPosition(0));
            assertTrue(imageChoices.getListView().performItemClick(null, 0, 0));
            ShadowLooper.idleMainLooper();
            ((EditText) view(fixture.dialog, "skills.detail.key")).setText("");
            click(fixture.dialog, "skills.detail.save");
            assertTrue(fixture.services.hasCredential(fixture.store.detail(NAME).service()));
            click(fixture.dialog, "skills.detail.authorize");
            AlertDialog authorization = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(((TextView) authorization.findViewById(android.R.id.message)).getText().toString().contains(ENDPOINT));
            assertFalse(fixture.services.isAuthorized(fixture.store.detail(NAME).service()));
        }
    }

    @Implements(value = ChatCompletionClient.ModelCatalogRequest.class, isInAndroidSdk = false)
    public static final class FakeChatModelCatalog {
        static AiModelConfig modelRequest;

        @Implementation protected List<String> fetch(AiModelConfig draft) {
            modelRequest = draft;
            return List.of(MODEL);
        }
    }

    @Test public void editedMockDraftSavesTheExactHttpsProviderAndCredentialWithAWritableVault() throws Exception {
        AtomicInteger keyReads = new AtomicInteger();
        AiCredentialVault vault = new AiCredentialVault(() -> {
            keyReads.incrementAndGet();
            return new SecretKeySpec(new byte[32], "AES");
        });
        try (Fixture fixture = new Fixture(vault)) {
            fixture.save(ENDPOINT, "");
            SkillStore.Detail savedWithoutKey = fixture.store.detail(NAME);
            assertEquals(fixture.status(), SkillServices.Adapter.OPENAI_IMAGES, savedWithoutKey.service().adapter());
            assertEquals(ENDPOINT, savedWithoutKey.service().endpoint());
            assertFalse(savedWithoutKey.canAuthorize());
            assertEquals(0, keyReads.get());

            fixture.save(ENDPOINT, KEY);
            SkillStore.Detail saved = fixture.store.detail(NAME);
            SkillServices.Service provider = fixture.services.scoped(saved.service().id(), NAME);
            assertEquals("配置已保存，请授权已保存的服务", fixture.status());
            assertEquals(saved.service(), provider);
            assertEquals(savedWithoutKey.service().id(), provider.id());
            assertEquals(ENDPOINT, provider.endpoint());
            assertEquals(MODEL, provider.modelName());
            assertTrue(fixture.services.hasCredential(provider));
            assertTrue(saved.canAuthorize());
            assertFalse(fixture.services.isAuthorized(provider));
            assertEquals(1, saved.previousVersion());
            assertTrue(fixture.services.isAuthorized(fixture.activeMock));
            assertEquals(0, ((EditText) view(fixture.dialog, "skills.detail.key")).length());
        }
    }

    @Test public void pastedBearerCanBeSavedWithoutASelectedModel() throws Exception {
        try (Fixture fixture = new Fixture(new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")))) {
            fixture.save(ENDPOINT, "", "  Bearer " + KEY + "  ");
            SkillStore.Detail saved = fixture.store.detail(NAME);
            assertEquals(fixture.status(), SkillServices.Adapter.OPENAI_IMAGES, saved.service().adapter());
            assertTrue(fixture.services.hasCredential(saved.service()));
            assertEquals("", saved.service().modelName());
            assertEquals("配置已保存，请选择或填写模型名称", fixture.status());
            assertFalse(saved.canAuthorize());
            assertEquals(1, saved.previousVersion());
        }
    }

    @Test public void missingAndNonHttpsAddressesLeaveTheExistingProviderAndDraftUntouched() throws Exception {
        AtomicInteger keyReads = new AtomicInteger();
        AiCredentialVault vault = new AiCredentialVault(() -> {
            keyReads.incrementAndGet();
            return new SecretKeySpec(new byte[32], "AES");
        });
        try (Fixture fixture = new Fixture(vault)) {
            SkillStore.Detail before = fixture.store.detail(NAME);
            int registryCount = fixture.services.listForSkill(NAME).size();
            for (String invalid : new String[]{"", "http://images.example.test/v1/images/generations"}) {
                fixture.save(invalid, KEY);
                assertTrue(fixture.status(), fixture.status().contains("HTTPS"));
                assertEquals(KEY.length(), ((EditText) view(fixture.dialog, "skills.detail.key")).length());
                SkillStore.Detail after = fixture.store.detail(NAME);
                assertEquals(before.draftId(), after.draftId());
                assertEquals(before.service(), after.service());
                assertEquals(registryCount, fixture.services.listForSkill(NAME).size());
            }
            assertEquals(0, keyReads.get());
        }
    }

    @Test public void missingRobolectricKeystoreIsReportedSeparatelyFromEndpointValidation() throws Exception {
        assumeTrue("This test inspects a runtime without AndroidKeyStore", Security.getProvider("AndroidKeyStore") == null);
        try (Fixture fixture = new Fixture(null)) {
            SkillStore.Detail before = fixture.store.detail(NAME);
            fixture.save(ENDPOINT, KEY);

            assertEquals(before.draftId(), fixture.store.detail(NAME).draftId());
            assertEquals(before.service(), fixture.store.detail(NAME).service());
            assertTrue(fixture.status(), fixture.status().contains("本机") && fixture.status().contains("凭据"));
            assertFalse(fixture.status().contains(KEY));
            EditText key = (EditText) view(fixture.dialog, "skills.detail.key");
            assertEquals(KEY.length(), key.length());
            assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
            assertFalse(key.getHint().toString().contains("已保存"));
        }
    }

    @Test public void aCompleteCustomEndpointAndLongCredentialSurviveSaveAndReopen() throws Exception {
        String custom = "https://images.example.test/provider/generate";
        String longBearer = "  Bearer " + "a".repeat(4000) + "  ";
        try (Fixture fixture = new Fixture(new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")))) {
            ((CompoundButton) view(fixture.dialog, "skills.detail.endpoint_complete")).setChecked(true);
            fixture.fill(custom, "", MODEL, longBearer);
            assertEquals(longBearer.length(), ((EditText) view(fixture.dialog, "skills.detail.key")).length());
            click(fixture.dialog, "skills.detail.save");
            SkillStore.Detail saved = fixture.store.detail(NAME);
            assertEquals(fixture.status(), custom, saved.service().endpoint());
            assertTrue(fixture.services.hasCredential(saved.service()));
            assertFalse(fixture.services.isAuthorized(saved.service()));
            assertTrue(((CompoundButton) view(fixture.dialog, "skills.detail.endpoint_complete")).isChecked());

            AiSkillDetailDialog reopened = new AiSkillDetailDialog(fixture.activity, false, NAME, null);
            try {
                AlertDialog reopenedDialog = reopened.dialog();
                reopenedDialog.setOwnerActivity(fixture.activity);
                reopenedDialog.show();
                assertEquals(custom, ((EditText) view(reopenedDialog, "skills.detail.endpoint")).getText().toString());
                assertTrue(((CompoundButton) view(reopenedDialog, "skills.detail.endpoint_complete")).isChecked());
                assertEquals(0, ((EditText) view(reopenedDialog, "skills.detail.key")).length());
            } finally {
                reopened.close();
                reopened.dialog().dismiss();
            }
        }
    }

    @Test public void ordinaryBaseDefaultsAndPickerOnlyCopyTheBaseAndClearKeysForChangedDestinations() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            AiModelConfig ordinary = saveOrdinaryProfile(activity);
            SkillStore store = SkillStore.forContext(activity);
            SkillStore.Pending draft = createImageDraft(store);
            try (AiSkillDetailDialog page = new AiSkillDetailDialog(activity, false, NAME, null)) {
                AlertDialog dialog = page.dialog();
                dialog.setOwnerActivity(activity);
                dialog.show();
                EditText endpoint = (EditText) view(dialog, "skills.detail.endpoint");
                EditText catalogue = (EditText) view(dialog, "skills.detail.models_endpoint");
                EditText model = (EditText) view(dialog, "skills.detail.model");
                EditText key = (EditText) view(dialog, "skills.detail.key");
                assertEquals(BASE_ENDPOINT, endpoint.getText().toString());
                assertFalse(((CompoundButton) view(dialog, "skills.detail.endpoint_complete")).isChecked());
                assertTrue(((TextView) view(dialog, "skills.detail.ai_base_source")).getText().toString().contains(ordinary.name));
                assertEquals(0, catalogue.length());
                assertEquals(0, model.length());
                assertEquals(0, key.length());

                key.setText(KEY);
                endpoint.setText(BASE_ENDPOINT + "/");
                ShadowLooper.idleMainLooper();
                assertEquals(KEY, key.getText().toString());
                endpoint.setText("https://other-images.example.test/v1");
                ShadowLooper.idleMainLooper();
                assertEquals(0, key.length());
                String override = "https://other-images.example.test/custom-models";
                catalogue.setText(override);
                key.setText(KEY);

                click(dialog, "skills.detail.use_ai_base");
                AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(picker.getListView().getItemAtPosition(0).toString().contains(ordinary.name));
                assertTrue(picker.getListView().performItemClick(null, 0, 0));
                ShadowLooper.idleMainLooper();
                assertEquals(BASE_ENDPOINT, endpoint.getText().toString());
                assertEquals(override, catalogue.getText().toString());
                assertEquals(0, key.length());
                assertEquals(0, model.length());
                assertTrue(((TextView) view(dialog, "skills.detail.ai_base_source")).getText().toString().contains(ordinary.name));
                assertEquals(draft.draftId(), store.detail(NAME).draftId());
                assertNull(store.detail(NAME).service());
                assertEquals(ORDINARY_KEY, AiModelSettings.read(new HostSettingsStore(activity)).selected().apiKey);
                dialog.dismiss();
            }
        } finally {
            activity.finish();
        }
    }

    @Test public void reopenedCustomCatalogueUsesOnlyTheSavedImageKeyAndDoesNotRunOrSaveASelection() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            saveOrdinaryProfile(activity);
            String custom = "https://images.example.test/provider/generate";
            String override = "https://images.example.test/provider/catalog";
            AiCredentialVault vault = new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES"));
            SkillStore setup = SkillStore.forContext(activity);
            SkillServices seedServices = (SkillServices) field(SkillStore.class, "services").get(setup);
            field(SkillServices.class, "credentials").set(seedServices, vault);
            SkillStore.Pending pending = createImageDraft(setup);
            SkillStore.Detail saved = setup.saveConfiguration(NAME, pending.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                    custom, override, "", 0, KEY);

            ModelExecutor executor = new ModelExecutor();
            try (AiSkillDetailDialog reopened = new AiSkillDetailDialog(activity, false, NAME, null, null, executor)) {
                SkillStore store = (SkillStore) field(AiSkillDetailDialog.class, "store").get(reopened);
                SkillServices services = (SkillServices) field(SkillStore.class, "services").get(store);
                field(SkillServices.class, "credentials").set(services, vault);
                AtomicInteger gets = new AtomicInteger();
                AtomicInteger posts = new AtomicInteger();
                AtomicReference<String> authorization = new AtomicReference<>();
                stubImageCatalogue(services, override, gets, posts, authorization);

                AlertDialog dialog = reopened.dialog();
                dialog.setOwnerActivity(activity);
                dialog.show();
                assertEquals(custom, ((EditText) view(dialog, "skills.detail.endpoint")).getText().toString());
                assertTrue(((CompoundButton) view(dialog, "skills.detail.endpoint_complete")).isChecked());
                assertEquals(override, ((EditText) view(dialog, "skills.detail.models_endpoint")).getText().toString());
                assertEquals(0, ((EditText) view(dialog, "skills.detail.key")).length());

                click(dialog, "skills.detail.fetch_models");
                assertEquals(0, gets.get());
                executor.runNext();
                ShadowLooper.idleMainLooper();
                assertEquals(1, gets.get());
                assertEquals("Bearer " + KEY, authorization.get());
                assertEquals(0, posts.get());
                AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
                assertEquals(MODEL, picker.getListView().getItemAtPosition(0));
                assertTrue(picker.getListView().performItemClick(null, 0, 0));
                ShadowLooper.idleMainLooper();
                assertEquals(MODEL, ((EditText) view(dialog, "skills.detail.model")).getText().toString());
                assertEquals(saved.draftId(), store.detail(NAME).draftId());
                assertEquals("", store.detail(NAME).service().modelName());
                assertFalse(services.isAuthorized(saved.service()));
                assertTrue(store.list().isEmpty());
                dialog.dismiss();
            }
        } finally {
            activity.finish();
        }
    }

    @Test public void miuixImageCredentialUsesTheSharedSecureFormControlAndIsClearedOnClose() throws Exception {
        try (Fixture fixture = new Fixture(null)) {
            fixture.save(ENDPOINT, "", "");
            AiSkillDetailDialog miuix = new AiSkillDetailDialog(fixture.activity, true, NAME, null);
            try {
                EditText key = (EditText) field(AiSkillDetailDialog.class, "keyInput").get(miuix);
                assertNotNull(key);
                View surface = MiuixFormControls.viewFor(key);
                assertNotSame(key, surface);
                assertNotNull(surface.getParent());
                assertEquals(0, key.length());
                assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, key.getInputType() & InputType.TYPE_MASK_VARIATION);
                assertFalse(key.isSaveEnabled());
                assertFalse(key.isSaveFromParentEnabled());
                assertFalse(surface.isSaveEnabled());
                assertFalse(surface.isSaveFromParentEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, key.getImportantForAutofill());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, surface.getImportantForAutofill());
                assertTrue((miuix.dialog().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                key.setText(KEY);
                miuix.close();
                assertEquals(0, key.length());
            } finally {
                miuix.close();
                miuix.dialog().dismiss();
            }
        }
    }

    @Test public void explicitModelLookupOnlyFillsTheChoiceAndRequiresAnIndependentSave() throws Exception {
        ModelExecutor executor = new ModelExecutor();
        AtomicInteger calls = new AtomicInteger();
        AiCredentialVault vault = new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES"));
        try (Fixture fixture = new Fixture(vault, (name, draftId, endpoint, modelsEndpoint, bearer) -> {
            assertEquals(NAME, name);
            assertEquals(ENDPOINT, endpoint);
            assertEquals(MODELS_ENDPOINT, modelsEndpoint);
            assertEquals(KEY, bearer);
            calls.incrementAndGet();
            return List.of(MODEL);
        }, executor)) {
            SkillStore.Detail before = fixture.store.detail(NAME);
            int registryCount = fixture.services.listForSkill(NAME).size();
            fixture.fill(ENDPOINT, MODELS_ENDPOINT, "", KEY);
            assertEquals(0, calls.get());
            click(fixture.dialog, "skills.detail.fetch_models");
            assertEquals(0, calls.get());
            executor.runNext();
            ShadowLooper.idleMainLooper();
            assertEquals(1, calls.get());
            AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, choices.getListView().getCount());
            assertTrue(choices.getListView().performItemClick(null, 0, 0));
            ShadowLooper.idleMainLooper();

            assertEquals(MODEL, ((EditText) view(fixture.dialog, "skills.detail.model")).getText().toString());
            assertEquals(before.draftId(), fixture.store.detail(NAME).draftId());
            assertEquals(registryCount, fixture.services.listForSkill(NAME).size());
            assertEquals(KEY.length(), ((EditText) view(fixture.dialog, "skills.detail.key")).length());
            click(fixture.dialog, "skills.detail.save");
            SkillStore.Detail saved = fixture.store.detail(NAME);
            assertEquals(MODEL, saved.service().modelName());
            assertEquals(MODELS_ENDPOINT, saved.service().modelsEndpoint());
            assertFalse(fixture.services.isAuthorized(saved.service()));
            assertTrue(fixture.services.isAuthorized(fixture.activeMock));
            assertEquals(0, ((EditText) view(fixture.dialog, "skills.detail.key")).length());
        }
    }

    @Test public void queuedModelsAreIgnoredAfterEditingTheFormOrClosingIt() throws Exception {
        ModelExecutor executor = new ModelExecutor();
        try (Fixture fixture = new Fixture(null, (name, draftId, endpoint, modelsEndpoint, bearer) ->
                List.of(MODEL), executor)) {
            fixture.fill(ENDPOINT, "", "", KEY);
            Dialog last = ShadowDialog.getLatestDialog();
            click(fixture.dialog, "skills.detail.fetch_models");
            executor.runNext();
            EditText endpoint = (EditText) view(fixture.dialog, "skills.detail.endpoint");
            endpoint.setText(BASE_ENDPOINT + "/provider2");
            ShadowLooper.idleMainLooper();
            assertSame(last, ShadowDialog.getLatestDialog());
            assertEquals(BASE_ENDPOINT + "/provider2", endpoint.getText().toString());
            click(fixture.dialog, "skills.detail.fetch_models");
            executor.runNext();
            fixture.page.close();
            fixture.dialog.dismiss();
            ShadowLooper.idleMainLooper();
            assertSame(last, ShadowDialog.getLatestDialog());
        }
    }

    @Test public void changingCostAndManualModelDoesNotDiscardTheQueuedCatalogReply() throws Exception {
        ModelExecutor executor = new ModelExecutor();
        try (Fixture fixture = new Fixture(null, (name, draftId, endpoint, modelsEndpoint, bearer) ->
                List.of(MODEL), executor)) {
            fixture.fill(ENDPOINT, "", "", KEY);
            View fetch = view(fixture.dialog, "skills.detail.fetch_models");
            assertTrue(fetch.performClick());
            assertFalse(fetch.isEnabled());
            assertEquals("正在查询模型列表…", fixture.modelStatus());
            executor.runNext();
            EditText model = (EditText) view(fixture.dialog, "skills.detail.model");
            model.setText("手动模型");
            ((EditText) view(fixture.dialog, "skills.detail.cost")).setText("3");
            ShadowLooper.idleMainLooper();

            assertTrue(fetch.isEnabled());
            assertEquals("已获取 1 个模型", fixture.modelStatus());
            AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(choices.isShowing());
            assertNotNull(choices.getListView());
            assertEquals(MODEL, choices.getListView().getItemAtPosition(0));
            assertEquals("手动模型", model.getText().toString());
        }
    }

    @Test public void aRejectedDiscoverySubmissionReenablesTheButtonForTheNextLookup() throws Exception {
        ModelExecutor executor = new ModelExecutor();
        try (Fixture fixture = new Fixture(null, (name, draftId, endpoint, modelsEndpoint, bearer) ->
                List.of(MODEL), executor)) {
            fixture.fill(ENDPOINT, "", "", KEY);
            View fetch = view(fixture.dialog, "skills.detail.fetch_models");
            executor.rejectNext = true;
            assertTrue(fetch.performClick());
            assertTrue(fetch.isEnabled());
            assertEquals("暂时无法启动模型查询，请重试", fixture.modelStatus());

            assertTrue(fetch.performClick());
            assertFalse(fetch.isEnabled());
            executor.runNext();
            ShadowLooper.idleMainLooper();
            assertTrue(fetch.isEnabled());
            AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(choices.isShowing());
            assertEquals(MODEL, choices.getListView().getItemAtPosition(0));
        }
    }

    @Test public void catalogueTraceClassifiesVisibleResultsWithoutRecordingFormValues() throws Exception {
        ModelExecutor executor = new ModelExecutor();
        AtomicInteger responses = new AtomicInteger();
        List<String> evidence = new CopyOnWriteArrayList<>();
        boolean previousDebug = DebugLog.isEnabled();
        try (Fixture fixture = new Fixture(null, (name, draftId, endpoint, modelsEndpoint, bearer) -> {
            assertNotNull(DouAiLog.current());
            if (responses.getAndIncrement() == 0) return List.of(MODEL);
            SkillImageModelCatalog.resolveEndpoint(endpoint, "https://other.example.test/v1/models");
            throw new AssertionError("Expected a fixed cross-origin catalogue failure");
        }, executor)) {
            fixture.fill(ENDPOINT, MODELS_ENDPOINT, "", KEY);
            assertTrue(DouAiLog.awaitIdle(5000));
            DebugLog.setEnabled(true);
            DouAiLog.setFrameworkSink((priority, line) -> evidence.add(line));
            click(fixture.dialog, "skills.detail.fetch_models");
            executor.runNext();
            ShadowLooper.idleMainLooper();
            AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(choices.isShowing());
            assertEquals(MODEL, choices.getListView().getItemAtPosition(0));
            choices.dismiss();
            ShadowLooper.idleMainLooper();
            click(fixture.dialog, "skills.detail.fetch_models");
            executor.runNext();
            ShadowLooper.idleMainLooper();
            assertTrue(fixture.modelStatus(), fixture.modelStatus().contains("相同的 HTTPS"));
            ((EditText) view(fixture.dialog, "skills.detail.models_endpoint")).setText("");
            assertEquals("", fixture.modelStatus());

            assertTrue(DouAiLog.awaitIdle(5000));
            String logs = String.join("\n", evidence);
            assertTrue(logs.contains("image_model_catalogue"));
            assertTrue(logs.contains("options_presented"));
            assertTrue(logs.contains("catalogue_different_origin"));
            assertFalse(logs.contains(ENDPOINT));
            assertFalse(logs.contains(MODELS_ENDPOINT));
            assertFalse(logs.contains(KEY));
            assertFalse(logs.contains(MODEL));
        } finally {
            DouAiLog.awaitIdle(5000);
            DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(previousDebug);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final SkillStore store;
        final SkillServices.Service activeMock;
        final AiSkillDetailDialog page;
        final AlertDialog dialog;
        final SkillServices services;

        Fixture(AiCredentialVault vault) throws Exception {
            this(vault, null, null);
        }

        Fixture(AiCredentialVault vault, AiSkillDetailDialog.ModelDiscovery discovery, ModelExecutor executor) throws Exception {
            SkillStore setup = SkillStore.forContext(activity);
            createImageDraft(setup);
            SkillStore.Detail pending = setup.detail(NAME);
            SkillStore.Detail saved = setup.saveConfiguration(NAME, pending.draftId(), SkillServices.Adapter.MOCK_IMAGE,
                    "", "", 0, "");
            SkillStore.Detail installed = setup.authorizeConfiguration(NAME, saved.draftId(), saved.service());
            assertEquals(1, installed.previousVersion());
            assertFalse(installed.pending());
            activeMock = installed.service();
            assertEquals(NAME, activeMock.skillName());
            SkillStore.EditTarget target = setup.editTarget(installed.deleteTarget());
            setup.saveEdit(target, target.description(), "输入画面主题和构图要求。", null);

            page = discovery == null ? new AiSkillDetailDialog(activity, false, NAME, null)
                    : new AiSkillDetailDialog(activity, false, NAME, null, discovery, executor);
            store = (SkillStore) field(AiSkillDetailDialog.class, "store").get(page);
            services = (SkillServices) field(SkillStore.class, "services").get(store);
            if (vault != null) field(SkillServices.class, "credentials").set(services, vault);
            dialog = page.dialog();
            dialog.setOwnerActivity(activity);
            dialog.show();
            assertTrue(store.detail(NAME).canReplace());
            click(dialog, "skills.detail.configure");
            ((Spinner) view(dialog, "skills.detail.adapter")).setSelection(0);
            ShadowLooper.idleMainLooper();
        }

        void save(String endpoint, String key) {
            save(endpoint, MODEL, key);
        }

        void save(String endpoint, String model, String key) {
            fill(endpoint, "", model, key);
            click(dialog, "skills.detail.save");
            ShadowLooper.idleMainLooper();
        }

        void fill(String endpoint, String modelsEndpoint, String model, String key) {
            ((EditText) view(dialog, "skills.detail.endpoint")).setText(endpoint);
            ((EditText) view(dialog, "skills.detail.models_endpoint")).setText(modelsEndpoint);
            ((EditText) view(dialog, "skills.detail.model")).setText(model);
            ((EditText) view(dialog, "skills.detail.cost")).setText("2");
            ((EditText) view(dialog, "skills.detail.key")).setText(key);
        }

        String status() { return ((TextView) view(dialog, "skills.detail.status")).getText().toString(); }
        String modelStatus() { return ((TextView) view(dialog, "skills.detail.model_status")).getText().toString(); }

        @Override public void close() {
            page.close();
            dialog.dismiss();
            activity.finish();
            ShadowLooper.idleMainLooper();
        }
    }

    private static AiModelConfig saveOrdinaryProfile(Activity activity) throws Exception {
        AiModelConfig profile = AiModelConfig.fromJson(new JSONObject().put("id", "base-source")
                .put("name", "普通 AI 配置").put("apiEndpoint", BASE_ENDPOINT + "/chat/completions")
                .put("modelsEndpoint", BASE_ENDPOINT + "/custom-ordinary-models")
                .put("modelName", "ordinary-text-model").put("apiKey", ORDINARY_KEY));
        new AiModelSettings(List.of(profile), profile.id).save(new HostSettingsStore(activity));
        return profile;
    }

    private static SkillStore.Pending createImageDraft(SkillStore store) throws Exception {
        var source = SkillStore.validateAuthoredDraft("""
                {"schemaVersion":3,"name":"生图","description":"生成图片","documentation":"输入画面描述。",
                 "execution":{"kind":"service","outputType":"image","maxInput":4000},
                 "trial":{"input":"一只猫","expectedType":"image"}}
                """);
        SkillStore.Pending pending = store.saveAuthoredDraft(source).pending();
        assertNotNull(pending);
        return pending;
    }

    private static void stubImageCatalogue(SkillServices services, String expectedAddress, AtomicInteger gets,
                                          AtomicInteger posts, AtomicReference<String> authorization) throws Exception {
        SkillImageModelCatalog catalog = new SkillImageModelCatalog();
        Field catalogTransport = field(SkillImageModelCatalog.class, "transport");
        Class<?> transportType = catalogTransport.getType();
        catalogTransport.set(catalog, Proxy.newProxyInstance(transportType.getClassLoader(), new Class<?>[]{transportType},
                (proxy, method, arguments) -> {
                    assertEquals(expectedAddress, arguments[0]);
                    assertEquals("GET", arguments[1]);
                    assertNull(arguments[3]);
                    gets.incrementAndGet();
                    authorization.set((String) ((Map<?, ?>) arguments[2]).get("Authorization"));
                    AiExtensionHttp.ResponseReader<?> reader = (AiExtensionHttp.ResponseReader<?>) arguments[6];
                    byte[] response = ("{\"data\":[{\"id\":\"" + MODEL + "\"}]}").getBytes(StandardCharsets.UTF_8);
                    return reader.read(200, ignored -> "application/json", new ByteArrayInputStream(response));
                }));
        field(SkillServices.class, "imageModels").set(services, catalog);
        Field imageTransport = field(SkillServices.class, "transport");
        Class<?> imageType = imageTransport.getType();
        imageTransport.set(services, Proxy.newProxyInstance(imageType.getClassLoader(), new Class<?>[]{imageType},
                (proxy, method, arguments) -> {
                    posts.incrementAndGet();
                    throw new AssertionError("Model lookup must not send an image POST");
                }));
    }

    private static final class ModelExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        private boolean rejectNext;

        @Override public void execute(Runnable task) {
            if (rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException("test model worker is busy");
            }
            tasks.add(task);
        }
        void runNext() { tasks.remove().run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            shutdown();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static void click(AlertDialog dialog, String tag) {
        assertTrue(tag, view(dialog, tag).performClick());
    }

    private static View view(AlertDialog dialog, String tag) {
        View view = dialog.getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private static EditText findInput(View view, String label) {
        if (view instanceof EditText input && label.contentEquals(input.getContentDescription())) return input;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText found = findInput(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
