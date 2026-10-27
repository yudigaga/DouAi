package com.douai.module.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.BreezeSpeechSettings
import com.douai.module.config.FishSpeechSettings
import com.douai.module.config.HostSettingsStore
import com.douai.module.config.LocalOptions
import com.douai.module.config.SpeechSettings
import com.douai.module.feature.VoiceCatalog
import com.douai.module.feature.VoiceCatalogLabels
import com.douai.module.feature.VoiceNameTranslationCache
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class GestureQuickDialogInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var dialog: android.app.AlertDialog? = null
    private var nestedDialog: android.app.AlertDialog? = null
    private val store by lazy { HostSettingsStore(compose.activity) }

    @After fun closeDialogs() {
        compose.runOnUiThread {
            nestedDialog?.dismiss()
            dialog?.dismiss()
        }
    }

    @Test fun featureShortcutsPersistBothAutomationSwitchesAndOpenVoicePicker() {
        var voicePickerOpened = 0
        compose.runOnUiThread {
            clearPreferences()
            dialog = GestureQuickFeaturesDialog.create(
                compose.activity,
                store,
                { voicePickerOpened++ },
                {})
            dialog!!.show()
        }

        compose.onNodeWithTag("settings.toggle.自动评论").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.toggle.自动回复").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings.option.切换音色").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(store.getBoolean("native_auto_comment", false))
            assertTrue(store.getBoolean("native_auto_reply", false))
            assertEquals(1, voicePickerOpened)
        }
    }

    @Test fun modelQuickSwitchChangesOnlyTheActiveProfile() {
        val first = model("profile-one", "One", "model-one")
        val second = model("profile-two", "Two", "model-two")
        compose.runOnUiThread {
            clearPreferences()
            AiModelSettings(listOf(first, second), first.id).save(store)
            dialog = GestureQuickModelDialog.create(compose.activity, store, {})
            dialog!!.show()
        }

        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val current = AiModelSettings.read(store)
            assertEquals(second.id, current.activeId)
            assertEquals(2, current.configs.size)
            assertEquals(first.apiEndpoint, current.configs[0].apiEndpoint)
        }
    }

    @Test fun cachedProviderVoiceCanBeChangedWithoutOpeningFullSpeechSettings() {
        compose.runOnUiThread {
            clearPreferences()
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY)
            store.putString(
                "tts_voice_sample",
                "[{\"id\":\"voice-a\",\"name\":\"Voice A\"},{\"id\":\"voice-b\",\"name\":\"Voice B\"}]")
            dialog = GestureQuickVoiceDialog.create(compose.activity, store, {}) { nestedDialog = it }
            dialog!!.show()
        }

        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voice-b", store.getString(SpeechSettings.SELECTED_VOICE, ""))
            assertEquals("Voice B", store.getString(SpeechSettings.SELECTED_VOICE_NAME, ""))
        }
    }

    @Test fun cachedBreezeVoiceChangesOnlyItsOwnProviderSelection() {
        compose.runOnUiThread {
            prepareBreeze()
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE,
                "[{\"id\":\"voc_a\",\"name\":\"Breeze A\"},{\"id\":\"voc_b\",\"name\":\"Breeze B\"}]")
            showVoicePicker()
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voc_b", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze B", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
            assertOtherVoicesUnchanged()
        }
    }

    @Test fun breezeManualVoiceRemainsAvailableWithoutACachedList() {
        compose.runOnUiThread { prepareBreeze(); showVoicePicker() }
        openManualVoice("  voc_manual  ")
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voc_manual", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("", store.getString(BreezeSpeechSettings.VOICE_NAME, "missing"))
            assertOtherVoicesUnchanged()
        }
    }

    @Test fun breezeManualVoiceRejectsControlCharactersWithoutChangingAnyVoice() {
        compose.runOnUiThread { prepareBreeze(); showVoicePicker() }
        openManualVoice("voc_bad\nheader")
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze before", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
            assertOtherVoicesUnchanged()
        }
    }

    @Test fun anOldBreezePickerCannotWriteAfterTheSpeechProviderChanges() {
        compose.runOnUiThread {
            prepareBreeze()
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"voc_next\",\"name\":\"Next\"}]")
            showVoicePicker()
            store.putString(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX)
        }
        compose.runOnUiThread { dialog!!.listView.performItemClick(null, 0, 0) }
        compose.runOnIdle {
            assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze before", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
            assertOtherVoicesUnchanged()
        }
    }

    @Test fun anOldBreezeManualEditorCannotWriteAfterTheSpeechProviderChanges() {
        compose.runOnUiThread { prepareBreeze(); showVoicePicker() }
        openManualVoice("voc_late")
        compose.runOnUiThread { store.putString(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY) }
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze before", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
            assertOtherVoicesUnchanged()
        }
    }

    @Test fun cachedFishVoiceChangesOnlyItsOwnReferenceSelection() {
        compose.runOnUiThread {
            prepareFish()
            store.putString(FishSpeechSettings.VOICE_SAMPLE,
                "[{\"id\":\"fish_a\",\"name\":\"Fish A\"},{\"id\":\"fish_b\",\"name\":\"Fish B\"}]")
            showVoicePicker()
        }
        compose.onNodeWithTag("miuix.dialog.choice.2").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("fish_b", store.getString(FishSpeechSettings.VOICE_ID, ""))
            assertEquals("Fish B", store.getString(FishSpeechSettings.VOICE_NAME, ""))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun fishManualVoiceCanBeClearedToUseTheServiceDefault() {
        compose.runOnUiThread { prepareFish(); showVoicePicker() }
        openManualVoice("")
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("", store.getString(FishSpeechSettings.VOICE_ID, "missing"))
            assertEquals("", store.getString(FishSpeechSettings.VOICE_NAME, "missing"))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun anOldFishManualEditorCannotClearItsVoiceAfterTheProviderChanges() {
        compose.runOnUiThread { prepareFish(); showVoicePicker() }
        openManualVoice("")
        compose.runOnUiThread { store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE) }
        compose.onNodeWithTag("miuix.dialog.button.-1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("fish_before", store.getString(FishSpeechSettings.VOICE_ID, ""))
            assertEquals("Fish before", store.getString(FishSpeechSettings.VOICE_NAME, ""))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun fishDefaultVoiceIsDirectlyAvailableWithoutACachedCatalog() {
        compose.runOnUiThread {
            prepareFish()
            store.putString(FishSpeechSettings.VOICE_MODE, "multi")
            store.putString(FishSpeechSettings.REFERENCE_IDS, "[\"11111111111111111111111111111111\"]")
            showVoicePicker()
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("", store.getString(FishSpeechSettings.REFERENCE_ID, "missing"))
            assertEquals("single", store.getString(FishSpeechSettings.VOICE_MODE, ""))
            assertEquals("[\"11111111111111111111111111111111\"]", store.getString(FishSpeechSettings.REFERENCE_IDS, ""))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun pickingAFishVoiceExplicitlyReturnsToSingleSpeakerAndKeepsOtherVoiceModes() {
        val speakers = "[\"11111111111111111111111111111111\",\"22222222222222222222222222222222\"]"
        compose.runOnUiThread {
            prepareFish()
            store.putString(FishSpeechSettings.VOICE_MODE, "multi")
            store.putString(FishSpeechSettings.REFERENCE_IDS, speakers)
            store.putString(FishSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"fish_new\",\"name\":\"Fish new\"}]")
            showVoicePicker()
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("single", store.getString(FishSpeechSettings.VOICE_MODE, ""))
            assertEquals("fish_new", store.getString(FishSpeechSettings.REFERENCE_ID, ""))
            assertEquals(speakers, store.getString(FishSpeechSettings.REFERENCE_IDS, ""))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun cachedBreezeNamesAndOfficialLabelsAreSearchableWithoutChangingTheOriginalName() {
        compose.runOnUiThread {
            prepareBreeze()
            val first = VoiceCatalog.Entry("voc_a", "Breeze A")
            val second = VoiceCatalog.Entry("voc_b", "Breeze B", listOf("语言：中文", "语气：温柔"))
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, org.json.JSONArray()
                .put(VoiceCatalogLabels.cached(first)).put(VoiceCatalogLabels.cached(second)).toString())
            VoiceNameTranslationCache.merge(store, VoiceNameTranslationCache.scope(speech()),
                listOf(second), mapOf("voc_b" to "微风乙"))
            showVoicePicker()
        }
        enterQuickSearch("温柔")
        compose.onNodeWithTag("miuix.dialog.choice.0").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.1").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(dialog!!.listView.adapter.getItem(0).toString().contains("微风乙（Breeze B）"))
        }
        enterQuickSearch("微风乙")
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.runOnIdle {
            assertEquals("voc_b", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze B", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
            assertOtherVoicesUnchanged()
        }
        compose.runOnUiThread {
            dialog = GestureQuickFeaturesDialog.create(compose.activity, store, {}, {})
            dialog!!.show()
        }
        compose.onNodeWithText("微风乙（Breeze B） · voc_b").assertIsDisplayed()
    }

    @Test fun filteringFishByIdKeepsTheVisibleRowMappedToItsRealVoice() {
        compose.runOnUiThread {
            prepareFish()
            store.putString(FishSpeechSettings.VOICE_SAMPLE,
                "[{\"id\":\"fish_a\",\"name\":\"One\"},{\"id\":\"fish_b\",\"name\":\"Two\"}]")
            showVoicePicker()
        }
        enterQuickSearch("ＦＩＳＨ＿Ｂ")
        compose.onNodeWithTag("miuix.dialog.choice.0").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("fish_b", store.getString(FishSpeechSettings.VOICE_ID, ""))
            assertEquals("Two", store.getString(FishSpeechSettings.VOICE_NAME, ""))
            assertBreezeAndOtherVoicesUnchanged()
        }
    }

    @Test fun anEmptyQuickSearchCanBeClearedWithoutLosingTheVoiceSelection() {
        compose.runOnUiThread {
            prepareFish()
            store.putString(FishSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"fish_a\",\"name\":\"Voice A\"}]")
            showVoicePicker()
        }
        enterQuickSearch("不存在的音色")
        compose.onNodeWithTag("miuix.dialog.choice.0").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals("fish_before", store.getString(FishSpeechSettings.VOICE_ID, ""))
            dialog!!.window!!.decorView.findViewWithTag<android.widget.Button>("speech.quick.voice.search.clear").performClick()
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed()
        compose.runOnIdle { assertEquals("fish_before", store.getString(FishSpeechSettings.VOICE_ID, "")) }
    }

    @Test fun aQuickPickerCannotApplyThePreviousFilterAfterItsLanguageChanges() {
        compose.runOnUiThread {
            prepareBreeze()
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"voc_next\",\"name\":\"Next\"}]")
            showVoicePicker()
            store.putString(BreezeSpeechSettings.VOICE_LANGUAGE, "en")
        }
        compose.runOnUiThread { dialog!!.listView.performItemClick(null, 0, 0) }
        compose.runOnIdle {
            assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
            assertEquals("Breeze before", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
        }
    }

    @Test fun anOldCatalogWithoutAFilterMarkerIsNotShownAsTheCurrentFilteredCatalog() {
        compose.runOnUiThread {
            prepareBreeze()
            store.putString(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"voc_old\",\"name\":\"Old unfiltered\"}]")
            store.putString(VoiceCatalogLabels.BREEZE_QUERY_KEY, "")
            showVoicePicker()
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").assertDoesNotExist()
        compose.runOnIdle { assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, "")) }
    }

    private fun enterQuickSearch(query: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val input = dialog!!.window!!.decorView.findViewWithTag<android.widget.EditText>("speech.quick.voice.search")
            assertTrue(input.isShown)
            input.setText(query)
        }
        compose.waitForIdle()
    }

    private fun speech() = SpeechSettings(LocalOptions(store.snapshot()))

    private fun prepareFish() {
        prepareBreeze()
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.FISH)
        store.putString(FishSpeechSettings.VOICE_ID, "fish_before")
        store.putString(FishSpeechSettings.VOICE_NAME, "Fish before")
        store.putString(VoiceCatalogLabels.FISH_QUERY_KEY, VoiceCatalogLabels.queryKey(speech()))
    }

    private fun assertBreezeAndOtherVoicesUnchanged() {
        assertEquals("voc_before", store.getString(BreezeSpeechSettings.VOICE_ID, ""))
        assertEquals("Breeze before", store.getString(BreezeSpeechSettings.VOICE_NAME, ""))
        assertOtherVoicesUnchanged()
    }

    private fun prepareBreeze() {
        clearPreferences()
        store.putString(SpeechSettings.PROVIDER, SpeechSettings.BREEZE)
        store.putString(BreezeSpeechSettings.VOICE_ID, "voc_before")
        store.putString(BreezeSpeechSettings.VOICE_NAME, "Breeze before")
        store.putString(SpeechSettings.MINIMAX_VOICE_ID, "minimax-before")
        store.putString(SpeechSettings.MINIMAX_VOICE_NAME, "MiniMax before")
        store.putString(SpeechSettings.SELECTED_VOICE, "third-before")
        store.putString(SpeechSettings.SELECTED_VOICE_NAME, "Third before")
        store.putString(SpeechSettings.LOCAL_VOICE_NAME, "local-before")
        store.putString("tts_language", "zh-CN")
        store.putString(VoiceCatalogLabels.BREEZE_QUERY_KEY, VoiceCatalogLabels.queryKey(speech()))
    }

    private fun showVoicePicker() {
        dialog = GestureQuickVoiceDialog.create(compose.activity, store, {}) {
            nestedDialog = it
            it.show()
        }
        dialog!!.show()
    }

    private fun openManualVoice(value: String) {
        compose.onNodeWithTag("miuix.dialog.button.-3").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnUiThread {
            val input = nestedDialog!!.window!!.decorView
                .findViewWithTag<android.widget.EditText>("settings.input.音色编号")
            assertTrue(input.isShown)
            input.setText(value)
        }
    }

    private fun assertOtherVoicesUnchanged() {
        assertEquals("minimax-before", store.getString(SpeechSettings.MINIMAX_VOICE_ID, ""))
        assertEquals("MiniMax before", store.getString(SpeechSettings.MINIMAX_VOICE_NAME, ""))
        assertEquals("third-before", store.getString(SpeechSettings.SELECTED_VOICE, ""))
        assertEquals("Third before", store.getString(SpeechSettings.SELECTED_VOICE_NAME, ""))
        assertEquals("local-before", store.getString(SpeechSettings.LOCAL_VOICE_NAME, ""))
        assertEquals("zh-CN", store.getString("tts_language", ""))
    }

    private fun clearPreferences() {
        compose.activity.applicationContext
            .getSharedPreferences("douai_module_settings", 0)
            .edit()
            .clear()
            .commit()
    }

    private fun model(id: String, name: String, modelName: String): AiModelConfig =
        AiModelConfig.fromJson(
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("apiEndpoint", "https://example.test/v1")
                .put("modelName", modelName),
        )
}
