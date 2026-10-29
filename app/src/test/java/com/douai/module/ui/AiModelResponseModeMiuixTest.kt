package com.douai.module.ui

import android.app.AlertDialog
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.douai.module.config.AiModelConfig
import com.douai.module.config.AiModelSettings
import com.douai.module.config.HostSettingsStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class AiModelResponseModeMiuixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var editor: AiModelEditor? = null

    @After fun closeEditor() {
        compose.runOnUiThread { editor?.close() }
    }

    @Test fun responseModeChoicePersistsAndReopensThroughTheMiuixControls() {
        lateinit var store: HostSettingsStore
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            val original = AiModelConfig.fromJson(JSONObject().put("id", "miuix-response-mode")
                .put("name", "配置").put("apiEndpoint", "https://example.test/v1")
                .put("modelName", "fixture-model"))
            openEditor(store, original)
        }
        compose.onNodeWithTag("ai.response_mode").assertExists()
        compose.runOnIdle {
            val mode = responseMode()
            assertEquals("非流式", mode.selectedItem)
            assertTrue(mode.performClick())
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").assertIsDisplayed()
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle {
            assertEquals("流式", responseMode().selectedItem)
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(AiModelSettings.read(store).configs.single().streaming())
            assertEquals("", AiModelSettings.read(store).activeId)
            openEditor(store, AiModelSettings.read(store).configs.single())
        }
        compose.onNodeWithTag("ai.response_mode").assertExists()
        compose.runOnIdle {
            assertEquals("流式", responseMode().selectedItem)
            assertTrue(responseMode().performClick())
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.runOnIdle {
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(AiModelSettings.read(store).configs.single().streaming())
        }
    }

    @Test fun reasoningEffortUsesActualValuesAndPersistsThroughTheMiuixChoices() {
        lateinit var store: HostSettingsStore
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            openEditor(store, AiModelConfig.fromJson(JSONObject().put("id", "miuix-effort")
                .put("name", "配置").put("apiEndpoint", "https://example.test/v1")
                .put("modelName", "fixture-model")))
        }
        compose.onNodeWithTag("ai.reasoning_effort").assertExists()
        compose.runOnIdle {
            val effort = reasoningEffort()
            assertEquals("模型默认", effort.selectedItem)
            assertEquals("max", effort.getItemAtPosition(7))
            assertTrue(effort.performClick())
        }
        compose.onNodeWithTag("miuix.dialog.choices").performScrollToIndex(7)
        compose.onNodeWithTag("miuix.dialog.choice.7").performClick()
        compose.runOnIdle {
            assertEquals("max", reasoningEffort().selectedItem)
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("max", AiModelSettings.read(store).configs.single().reasoningEffort)
            openEditor(store, AiModelSettings.read(store).configs.single())
        }
        compose.onNodeWithTag("ai.reasoning_effort").assertExists()
        compose.runOnIdle {
            assertEquals("max", reasoningEffort().selectedItem)
            assertTrue(reasoningEffort().performClick())
        }
        compose.onNodeWithTag("miuix.dialog.choice.0").performClick()
        compose.runOnIdle {
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(AiModelSettings.read(store).configs.single().hasReasoningEffort())
        }
    }

    @Test fun switchingProtocolRetainsAnInapplicableEffortUntilTheUserChangesIt() {
        lateinit var store: HostSettingsStore
        compose.runOnUiThread {
            store = HostSettingsStore(compose.activity)
            val original = AiModelConfig.fromJson(JSONObject().put("id", "miuix-effort-switch")
                .put("name", "配置").put("apiEndpoint", "https://example.test/v1")
                .put("modelName", "fixture-model").put("reasoningEffort", "max"))
            AiModelSettings(listOf(original), original.id).save(store)
            openEditor(store, original)
        }
        compose.onNodeWithTag("ai.protocol").assertExists()
        compose.runOnIdle { editor!!.dialog().window!!.decorView.findViewWithTag<Spinner>("ai.protocol").performClick() }
        compose.onNodeWithTag("miuix.dialog.choice.3").performClick()
        compose.runOnIdle {
            val effort = reasoningEffort()
            assertEquals("max（当前协议不适用）", effort.selectedItem)
            assertEquals("minimal", effort.getItemAtPosition(1))
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(editor!!.dialog().isShowing)
            assertEquals(AiModelConfig.CHAT, AiModelSettings.read(store).configs.single().protocol)
            assertEquals("max", AiModelSettings.read(store).configs.single().reasoningEffort)
            effort.performClick()
        }
        compose.onNodeWithTag("miuix.dialog.choice.1").performClick()
        compose.runOnIdle {
            editor!!.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val saved = AiModelSettings.read(store).configs.single()
            assertEquals(AiModelConfig.GEMINI, saved.protocol)
            assertEquals("minimal", saved.reasoningEffort)
        }
    }

    private fun openEditor(store: HostSettingsStore, profile: AiModelConfig) {
        editor = AiModelEditor(compose.activity, store, profile, true, {})
        editor!!.show { dialog, onDismiss ->
            dialog.setOwnerActivity(compose.activity)
            dialog.setOnDismissListener { onDismiss.run() }
            dialog.show()
        }
    }

    private fun responseMode(): Spinner {
        val mode = editor!!.dialog().window!!.decorView.findViewWithTag<Spinner>("ai.response_mode")
        assertNotNull("The rendered Miuix control must attach its native compatibility value", mode)
        assertEquals("响应方式", mode.contentDescription.toString())
        return mode
    }

    private fun reasoningEffort(): Spinner {
        val effort = editor!!.dialog().window!!.decorView.findViewWithTag<Spinner>("ai.reasoning_effort")
        assertNotNull("The rendered reasoning control must attach its native compatibility value", effort)
        assertEquals("推理强度", effort.contentDescription.toString())
        return effort
    }
}
